import { z } from "zod";
import type { CanvasContext, MediaGenerationRequest, MediaReference, QuestionContext } from "../types";
import { audioGenerationSchema, imageGenerationSchema, videoGenerationSchema } from "../mediaGeneration/runtime";
import { authorizeMediaGeneration } from "../mediaGeneration/authorization";
import { canvasSchemas } from "./runtime";

const nodeSchema = z.object({ id: z.string(), data: z.record(z.string(), z.unknown()), truncated: z.array(z.unknown()).default([]) });
const edgeSchema = z.object({ source: z.string(), sourceHandle: z.string(), target: z.string(), targetHandle: z.string() });
const pageSchema = z.object({ edges: z.array(edgeSchema), hasMore: z.boolean(), nextCursor: z.string().nullable().optional() });
const outputSchema = z.object({ dataType: z.string(), value: z.unknown() });
const generationTypes: Record<string, "image" | "video" | "audio" | undefined> = {
  "node:generateImage": "image", "node:generateVideo": "video", "node:generateAudio": "audio",
};

export function createAuthorizedCanvas(cwd: string, canvas: CanvasContext, question?: QuestionContext): CanvasContext {
  return {
    ...canvas,
    async call(operation, signal, toolCallId) {
      const mediaType = operation.name === "nodeTools" && typeof operation.args.name === "string" ? generationTypes[operation.args.name] : undefined;
      if (!mediaType) return canvas.call(operation, signal);
      if (!question) throw new Error("节点生成必须通过 askUser 确认，当前会话没有回答通道");
      const args = canvasSchemas.nodeTools.parse(operation.args);
      const snapshot = async () => {
        const overview = z.object({ id: z.string() }).parse(await canvas.call({ name: "getCanvas", args: {} }, signal));
        const readNode = async (nodeId: string, source = false) => {
          const result = z.object({ nodes: z.array(nodeSchema), hasMore: z.boolean() }).parse(await canvas.call({
            name: "getCanvasNodes", args: { canvasId: overview.id, nodeIds: [nodeId], fields: ["data"],
              dataKeys: source ? ["outputs"] : ["prompt", "referenceOrder", "inpaint"], valueLimit: 100, textLimit: 4000 },
          }, signal));
          const node = result.nodes.find(node => node.id === nodeId);
          if (!node || result.hasMore) throw new Error("生成节点或参考素材读取不完整，请重新读取画布");
          for (const item of node.truncated) {
            const omitted = z.object({ path: z.array(z.string()), reason: z.string(), total: z.number().optional(), nextOffset: z.number().optional() }).parse(item);
            // ACT: 长文本逐段读取；结构截断不能当作完整输入，拒绝以不完整素材报价。
            if (omitted.reason !== "text" || !omitted.nextOffset || !omitted.total || omitted.total > 1000000) {
              throw new Error("节点参考结构过大或不完整，请缩小内容后重新确认");
            }
            let target: Record<string, unknown> = { data: node.data };
            for (const key of omitted.path.slice(0, -1)) {
              if (!Object.hasOwn(target, key) || !target[key] || typeof target[key] !== "object") throw new Error("节点文本路径无效");
              target = target[key] as Record<string, unknown>;
            }
            const key = omitted.path.at(-1)!;
            let text = z.string().parse(target[key]);
            while (text.length < omitted.total) {
              const page = z.object({ nodes: z.array(z.object({ id: z.string(), value: z.string() })) }).parse(await canvas.call({
                name: "getCanvasNodes", args: { canvasId: overview.id, nodeIds: [nodeId], path: omitted.path, textOffset: text.length, textLimit: 4000 },
              }, signal));
              const part = page.nodes.find(item => item.id === nodeId)?.value;
              if (!part) throw new Error("节点文本分页失效，请重新读取画布");
              text += part;
            }
            if (text.length !== omitted.total) throw new Error("节点文本分页期间已变化，请重新读取画布");
            target[key] = text;
          }
          return node;
        };
        const configuration = z.object({ config: z.record(z.string(), z.unknown()) }).parse(await canvas.call({
          name: "nodeTools", args: { nodeId: args.nodeId, name: "node:getConfig", args: {} },
        }, signal));
        const node = await readNode(args.nodeId);
        if (node.data.inpaint) throw new Error("请先退出局部重绘，使用普通生成节点确认算力消耗");
        const edges: z.infer<typeof edgeSchema>[] = [];
        let cursor: string | undefined;
        const cursors = new Set<string>();
        do {
          signal?.throwIfAborted();
          const page = pageSchema.parse(await canvas.call({ name: "getCanvasEdges", args: {
            canvasId: overview.id, nodeIds: [args.nodeId], direction: "incoming", limit: 200, ...(cursor ? { cursor } : {}),
          } }, signal));
          edges.push(...page.edges.filter(edge => edge.target === args.nodeId && edge.targetHandle === "in"));
          cursor = page.hasMore ? page.nextCursor ?? undefined : undefined;
          if (page.hasMore && (!cursor || cursors.has(cursor))) throw new Error("参考连线分页无效，请重新读取画布");
          if (cursor) cursors.add(cursor);
        } while (cursor);
        const references: { source: string; sourceHandle: string; dataType: string; value: unknown }[] = [];
        const sources = new Map<string, z.infer<typeof nodeSchema>>();
        for (const edge of edges) {
          if (!sources.has(edge.source)) sources.set(edge.source, await readNode(edge.source, true));
          const outputs = z.record(z.string(), outputSchema).parse(sources.get(edge.source)!.data.outputs);
          if (!Object.hasOwn(outputs, edge.sourceHandle)) throw new Error("上游节点暂无输出，不能确认生成");
          references.push({ source: edge.source, sourceHandle: edge.sourceHandle, ...outputs[edge.sourceHandle] });
        }
        const order = z.object({ in: z.array(z.string()).optional() }).optional().parse(node.data.referenceOrder)?.in ?? [];
        const positions = new Map(order.map((key, index) => [key, index]));
        references.sort((left, right) => (positions.get(encodeURIComponent(JSON.stringify([left.source, left.sourceHandle]))) ?? positions.size)
          - (positions.get(encodeURIComponent(JSON.stringify([right.source, right.sourceHandle]))) ?? positions.size));
        const prompt = [z.string().parse(node.data.prompt).trim(), ...references.flatMap((reference, index) => {
          if (reference.dataType !== "STRING") return [];
          const text = z.string().parse(reference.value).trim();
          return text ? [`参考 ${index + 1}：\n${text}`] : [];
        })].filter(Boolean).join("\n\n");
        const media = (type: string): MediaReference[] => references.filter(reference => reference.dataType === type).map(reference => {
          const value = z.object({ url: z.string(), mimeType: z.string() }).parse(reference.value);
          return { path: value.url, mimeType: value.mimeType };
        });
        const images = media("IMAGE");
        const config = configuration.config;
        const frameMode = mediaType === "video" && typeof config.mode === "string"
          && ["singleImage", "startEndRequired", "endFrameOptional", "startFrameOptional"].includes(config.mode);
        const input = { ...config, prompt, outputDirectory: `assets/${args.nodeId}`,
          images: frameMode ? undefined : images,
          ...(mediaType !== "image" ? { audios: media("AUDIO") } : {}),
          ...(mediaType === "video" ? {
            videos: media("VIDEO"),
            firstFrame: frameMode && (config.mode !== "startFrameOptional" || images.length > 1) ? images[0] : undefined,
            lastFrame: frameMode ? images[config.mode === "startFrameOptional" && images.length === 1 ? 0 : 1] : undefined,
          } : {}),
        };
        const request: MediaGenerationRequest = (mediaType === "image" ? imageGenerationSchema : mediaType === "video" ? videoGenerationSchema : audioGenerationSchema).parse(input);
        return { canvasId: overview.id, request, references };
      };
      const before = await snapshot();
      await authorizeMediaGeneration(cwd, question, toolCallId, mediaType, before.request, signal);
      if (JSON.stringify(await snapshot()) !== JSON.stringify(before)) throw new Error("等待授权期间画布或节点输入已变化，请重新估价并确认");
      signal?.throwIfAborted();
      return canvas.call(operation, signal);
    },
  };
}
