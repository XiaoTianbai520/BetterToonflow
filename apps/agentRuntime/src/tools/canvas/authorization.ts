import { z } from "zod";
import { getMediaPrice, listMediaModels } from "@/media/generation";
import type {
  CanvasAuthorizationScope,
  CanvasContext,
  CanvasGenerationGrant,
  CanvasMediaType,
  MediaGenerationRequest,
  MediaModel,
  MediaReference,
  QuestionContext,
  ToolDefinition,
} from "../types";
import { audioGenerationSchema, imageGenerationSchema, videoGenerationSchema } from "../mediaGeneration/runtime";
import { authorizeMediaGeneration, computeMediaGenerationFingerprint } from "../mediaGeneration/authorization";
import { canvasSchemas } from "./runtime";

const nodeSchema = z.object({
  id: z.string(),
  label: z.string().optional(),
  type: z.string().optional(),
  data: z.record(z.string(), z.unknown()),
  truncated: z.array(z.unknown()).default([]),
});
const edgeSchema = z.object({ source: z.string(), sourceHandle: z.string(), target: z.string(), targetHandle: z.string() });
const pageSchema = z.object({ edges: z.array(edgeSchema), hasMore: z.boolean(), nextCursor: z.string().nullable().optional() });
const outputSchema = z.object({ dataType: z.string(), value: z.unknown() });
const nodeToolsPageSchema = z.object({
  nodeTools: z.array(z.object({ nodeId: z.string(), name: z.string() })),
  hasMore: z.boolean().optional(),
});

const generationTypes: Record<string, CanvasMediaType | undefined> = {
  "node:generateImage": "image",
  "node:generateVideo": "video",
  "node:generateAudio": "audio",
};

const nodeTypeMediaTypes: Record<string, CanvasMediaType | undefined> = {
  imageGenerationNode: "image",
  videoGenerationNode: "video",
  audioGenerationNode: "audio",
};

const mediaTypeLabels: Record<CanvasMediaType, string> = {
  image: "图片",
  video: "视频",
  audio: "音频",
};

const batchNodeIdSchema = z.string().trim().min(1).max(256);

export const authorizeCanvasGenerationBatchSchema = z.strictObject({
  nodeIds: z.array(batchNodeIdSchema).min(1).max(32)
    .refine(value => new Set(value).size === value.length, "nodeIds 不能重复，请先去重"),
});

export interface CanvasNodeSnapshot {
  canvasId: string;
  label?: string;
  mediaType: CanvasMediaType;
  request: MediaGenerationRequest;
  references: { source: string; sourceHandle: string; dataType: string; value: unknown }[];
}

export function createCanvasAuthorizationScope(): CanvasAuthorizationScope {
  const grants = new Map<string, CanvasGenerationGrant>();
  return {
    grant(entries, count = 1) {
      if (!Number.isInteger(count) || count < 1) throw new Error("授权次数必须为正整数");
      for (const entry of entries) {
        grants.set(entry.nodeId, { ...entry, remaining: count });
      }
    },
    consume(canvasId, nodeId, mediaType, fingerprint) {
      const entry = grants.get(nodeId);
      if (!entry) return false;
      if (entry.canvasId !== canvasId || entry.mediaType !== mediaType || entry.fingerprint !== fingerprint || entry.remaining <= 0) {
        return false;
      }
      entry.remaining -= 1;
      if (entry.remaining <= 0) grants.delete(nodeId);
      return true;
    },
    clear() {
      grants.clear();
    },
  };
}

async function readNodeWithFullText(canvas: CanvasContext, canvasId: string, nodeId: string, source: boolean, signal?: AbortSignal) {
  const result = z.object({ nodes: z.array(nodeSchema), hasMore: z.boolean() }).parse(await canvas.call({
    name: "getCanvasNodes",
    args: {
      canvasId,
      nodeIds: [nodeId],
      fields: source ? ["data"] : ["label", "type", "data"],
      dataKeys: source ? ["outputs"] : ["prompt", "referenceOrder", "inpaint"],
      valueLimit: 100,
      textLimit: 4000,
    },
  }, signal));
  const node = result.nodes.find(item => item.id === nodeId);
  if (!node || result.hasMore) throw new Error("生成节点或参考素材读取不完整，请重新读取画布");
  for (const item of node.truncated) {
    const omitted = z.object({
      path: z.array(z.string()),
      reason: z.string(),
      total: z.number().optional(),
      nextOffset: z.number().optional(),
    }).parse(item);
    // ACT: 长文本逐段读取；结构截断不能当作完整输入，拒绝以不完整素材报价。
    if (omitted.reason !== "text" || !omitted.nextOffset || !omitted.total || omitted.total > 1000000) {
      throw new Error("节点参考结构过大或不完整，请缩小内容后重新确认");
    }
    const root: Record<string, unknown> = { label: node.label, type: node.type, data: node.data };
    let target: Record<string, unknown> = root;
    for (const key of omitted.path.slice(0, -1)) {
      if (!Object.hasOwn(target, key) || !target[key] || typeof target[key] !== "object") throw new Error("节点文本路径无效");
      target = target[key] as Record<string, unknown>;
    }
    const key = omitted.path.at(-1)!;
    let text = z.string().parse(target[key]);
    while (text.length < omitted.total) {
      const page = z.object({ nodes: z.array(z.object({ id: z.string(), value: z.string() })) }).parse(await canvas.call({
        name: "getCanvasNodes",
        args: { canvasId, nodeIds: [nodeId], path: omitted.path, textOffset: text.length, textLimit: 4000 },
      }, signal));
      const part = page.nodes.find(entry => entry.id === nodeId)?.value;
      if (!part) throw new Error("节点文本分页失效，请重新读取画布");
      text += part;
    }
    if (text.length !== omitted.total) throw new Error("节点文本分页期间已变化，请重新读取画布");
    target[key] = text;
    if (typeof root.label === "string") node.label = root.label;
  }
  return node;
}

async function resolveNodeMediaType(
  canvas: CanvasContext,
  canvasId: string,
  nodeId: string,
  nodeType: string | undefined,
  signal?: AbortSignal,
): Promise<CanvasMediaType> {
  const fromType = nodeType ? nodeTypeMediaTypes[nodeType] : undefined;
  if (fromType) return fromType;
  const page = nodeToolsPageSchema.parse(await canvas.call({
    name: "getNodeTools",
    args: {
      canvasId,
      nodeIds: [nodeId],
      names: ["node:generateImage", "node:generateVideo", "node:generateAudio"],
      limit: 10,
    },
  }, signal));
  const matched = [...new Set(page.nodeTools
    .filter(tool => tool.nodeId === nodeId)
    .map(tool => generationTypes[tool.name])
    .filter((type): type is CanvasMediaType => type !== undefined))];
  if (matched.length !== 1) throw new Error(`节点 ${nodeId} 不是可批量授权的媒体生成节点`);
  return matched[0]!;
}

export async function snapshotCanvasGenerationNode(
  canvas: CanvasContext,
  nodeId: string,
  explicitMediaType?: CanvasMediaType,
  signal?: AbortSignal,
): Promise<CanvasNodeSnapshot> {
  const overview = z.object({ id: z.string() }).parse(await canvas.call({ name: "getCanvas", args: {} }, signal));
  const node = await readNodeWithFullText(canvas, overview.id, nodeId, false, signal);
  const mediaType = explicitMediaType ?? await resolveNodeMediaType(canvas, overview.id, nodeId, node.type, signal);
  const configuration = z.object({ config: z.record(z.string(), z.unknown()) }).parse(await canvas.call({
    name: "nodeTools",
    args: { nodeId, name: "node:getConfig", args: {} },
  }, signal));
  if (node.data.inpaint) throw new Error("请先退出局部重绘，使用普通生成节点确认算力消耗");
  const edges: z.infer<typeof edgeSchema>[] = [];
  let cursor: string | undefined;
  const cursors = new Set<string>();
  do {
    signal?.throwIfAborted();
    const page = pageSchema.parse(await canvas.call({
      name: "getCanvasEdges",
      args: {
        canvasId: overview.id,
        nodeIds: [nodeId],
        direction: "incoming",
        limit: 200,
        ...(cursor ? { cursor } : {}),
      },
    }, signal));
    edges.push(...page.edges.filter(edge => edge.target === nodeId && edge.targetHandle === "in"));
    cursor = page.hasMore ? page.nextCursor ?? undefined : undefined;
    if (page.hasMore && (!cursor || cursors.has(cursor))) throw new Error("参考连线分页无效，请重新读取画布");
    if (cursor) cursors.add(cursor);
  } while (cursor);
  const references: { source: string; sourceHandle: string; dataType: string; value: unknown }[] = [];
  const sources = new Map<string, z.infer<typeof nodeSchema>>();
  for (const edge of edges) {
    if (!sources.has(edge.source)) sources.set(edge.source, await readNodeWithFullText(canvas, overview.id, edge.source, true, signal));
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
  const input = {
    ...config,
    prompt,
    outputDirectory: `assets/${nodeId}`,
    images: frameMode ? undefined : images,
    ...(mediaType !== "image" ? { audios: media("AUDIO") } : {}),
    ...(mediaType === "video" ? {
      videos: media("VIDEO"),
      firstFrame: frameMode && (config.mode !== "startFrameOptional" || images.length > 1) ? images[0] : undefined,
      lastFrame: frameMode && config.mode !== "singleImage"
        ? images[config.mode === "startFrameOptional" && images.length === 1 ? 0 : 1]
        : undefined,
    } : {}),
  };
  const request: MediaGenerationRequest = (
    mediaType === "image" ? imageGenerationSchema
      : mediaType === "video" ? videoGenerationSchema
        : audioGenerationSchema
  ).parse(input);
  return { canvasId: overview.id, label: node.label, mediaType, request, references };
}

function summarizeSpecs(request: MediaGenerationRequest) {
  const parts: string[] = [];
  if (request.mode !== undefined) parts.push(`模式=${Array.isArray(request.mode) ? request.mode.join("+") : request.mode}`);
  if (request.resolution) parts.push(`分辨率=${request.resolution}`);
  if (request.duration !== undefined) parts.push(`时长=${request.duration}s`);
  if (request.ratio) parts.push(`比例=${request.ratio}`);
  if (request.size) parts.push(`尺寸=${request.size}`);
  if (request.generateAudio !== undefined) parts.push(`伴音=${request.generateAudio ? "是" : "否"}`);
  if (request.voice) parts.push(`音色=${request.voice}`);
  if (request.language) parts.push(`语言=${request.language}`);
  if (request.format) parts.push(`格式=${request.format}`);
  if (request.sampleRate !== undefined) parts.push(`采样率=${request.sampleRate}`);
  const refs: string[] = [];
  if (request.firstFrame) refs.push("首帧1");
  if (request.lastFrame) refs.push("尾帧1");
  if (request.images?.length) refs.push(`图片${request.images.length}`);
  if (request.videos?.length) refs.push(`视频${request.videos.length}`);
  if (request.audios?.length) refs.push(`音频${request.audios.length}`);
  if (refs.length) parts.push(`参考=${refs.join("/")}`);
  return parts.length ? parts.join("，") : "默认规格";
}

function excerptPrompt(prompt: string, maxLength = 240) {
  const normalized = prompt.trim();
  if (normalized.length <= maxLength) return normalized;
  return `${normalized.slice(0, maxLength)}…（共 ${normalized.length} 字）`;
}

function summarizeBatchPrices(items: { price: { price: string; tips: string } | null }[]) {
  const counts = new Map<string, number>();
  let unknownCount = 0;
  for (const item of items) {
    if (!item.price) {
      unknownCount += 1;
    } else {
      counts.set(item.price.price, (counts.get(item.price.price) ?? 0) + 1);
    }
  }
  const parts = [...counts.entries()].map(([price, count]) => count > 1 ? `${price} × ${count}` : price);
  if (unknownCount > 0) parts.push(`算力消耗未知 × ${unknownCount}`);
  return parts.join("；") || "算力消耗未知";
}

interface PreparedBatchItem {
  nodeId: string;
  label?: string;
  mediaType: CanvasMediaType;
  model: MediaModel;
  snapshot: CanvasNodeSnapshot;
  fingerprint: string;
  price: { price: string; tips: string } | null;
}

export async function authorizeCanvasGenerationBatch(
  cwd: string,
  canvas: CanvasContext,
  question: QuestionContext | undefined,
  scope: CanvasAuthorizationScope,
  input: z.input<typeof authorizeCanvasGenerationBatchSchema>,
  callId?: string,
  signal?: AbortSignal,
) {
  signal?.throwIfAborted();
  if (!question) throw new Error("批量节点生成授权必须通过 askUser 确认，当前会话没有回答通道");
  const { nodeIds } = authorizeCanvasGenerationBatchSchema.parse(input);
  const models = await listMediaModels();
  const items: PreparedBatchItem[] = [];

  for (const nodeId of nodeIds) {
    signal?.throwIfAborted();
    const snapshot = await snapshotCanvasGenerationNode(canvas, nodeId, undefined, signal);
    if (items.length && snapshot.canvasId !== items[0]!.snapshot.canvasId) {
      throw new Error("读取批次节点期间画布已切换，请重新确认");
    }
    const model = models.find(
      candidate => candidate.providerId === snapshot.request.providerId
        && candidate.modelId === snapshot.request.modelId
        && candidate.type === snapshot.mediaType,
    );
    if (!model) throw new Error(`节点 ${nodeId} 所选媒体模型不存在或类型不匹配，请重新查询模型`);
    const fingerprint = await computeMediaGenerationFingerprint(cwd, snapshot.request, signal);
    const price = await getMediaPrice(cwd, snapshot.mediaType, snapshot.request, signal);
    items.push({
      nodeId,
      label: snapshot.label,
      mediaType: snapshot.mediaType,
      model,
      snapshot,
      fingerprint,
      price,
    });
  }

  const lines = items.map((item, index) => {
    const title = item.label && item.label !== item.nodeId ? `${item.label}（${item.nodeId}）` : item.nodeId;
    const costText = item.price
      ? `${item.price.price}${item.price.tips ? `；${item.price.tips}` : ""}`
      : "算力消耗未知，供应商未提供报价";
    return [
      `${index + 1}. ${title} [${mediaTypeLabels[item.mediaType]}]`,
      `   模型：${item.model.providerLabel} / ${item.model.label}（${item.snapshot.request.providerId}/${item.snapshot.request.modelId}）`,
      `   规格：${summarizeSpecs(item.snapshot.request)}`,
      `   预估消耗：${costText}`,
      `   提示词摘要：${excerptPrompt(item.snapshot.request.prompt)}`,
    ].join("\n");
  });

  const summary = [
    `本次将批量授权 ${items.length} 个画布节点（每节点 1 次生成调用，仅当前会话有效；重试或更改模型、规格、提示词、参考素材后需重新确认）：`,
    ...lines,
    `预估消耗汇总：${summarizeBatchPrices(items)}`,
  ].join("\n\n");

  const answer = await question.ask(callId ?? crypto.randomUUID(), {
    title: "确认整批消耗算力",
    question: summary,
    fields: [{
      field: "confirmBatchGeneration",
      title: `是否授权整批 ${items.length} 个节点各生成 1 次`,
      type: "radio",
      required: true,
      options: ["确认整批生成", "拒绝生成"],
    }],
  }, signal);
  signal?.throwIfAborted();
  if (answer.skipped || answer.values?.confirmBatchGeneration !== "确认整批生成") {
    throw new Error("用户未确认整批算力消耗，已取消授权");
  }

  for (const item of items) {
    signal?.throwIfAborted();
    const afterSnapshot = await snapshotCanvasGenerationNode(canvas, item.nodeId, item.mediaType, signal);
    if (JSON.stringify(afterSnapshot) !== JSON.stringify(item.snapshot)) {
      throw new Error("等待确认期间画布或节点输入已变化，请重新确认整批方案");
    }
    const afterFingerprint = await computeMediaGenerationFingerprint(cwd, afterSnapshot.request, signal);
    if (afterFingerprint !== item.fingerprint) {
      throw new Error("等待确认期间模型、参数或参考素材发生变化，请重新确认整批方案");
    }
    const afterPrice = await getMediaPrice(cwd, item.mediaType, afterSnapshot.request, signal);
    if (JSON.stringify(afterPrice) !== JSON.stringify(item.price)) {
      throw new Error("等待确认期间预估价格发生变化，请重新确认整批方案");
    }
  }

  scope.grant(items.map(item => ({
    canvasId: item.snapshot.canvasId,
    nodeId: item.nodeId,
    mediaType: item.mediaType,
    fingerprint: item.fingerprint,
  })), 1);

  return {
    authorized: true,
    canvasId: items[0]!.snapshot.canvasId,
    count: items.length,
    nodes: items.map(item => ({
      nodeId: item.nodeId,
      label: item.label,
      mediaType: item.mediaType,
      providerId: item.snapshot.request.providerId,
      modelId: item.snapshot.request.modelId,
      remaining: 1,
      price: item.price,
    })),
  };
}

export function createAuthorizeCanvasGenerationBatchTool(
  cwd: string,
  canvas: CanvasContext,
  question: QuestionContext | undefined,
  scope: CanvasAuthorizationScope | undefined,
  baseGuidelines: string[],
): ToolDefinition {
  return {
    name: "authorizeCanvasGenerationBatch",
    label: "批量授权节点生成",
    description: "一次性读取 1..32 个画布生成节点的实时配置、提示词、参考素材和预估报价，通过单次 askUser 卡片汇总确认整批算力消耗。用户确认后在当前会话（含子 Agent）内为每个节点授予 1 次与快照严格绑定的生成凭证，后续调用 node:generateImage / node:generateVideo / node:generateAudio 不再逐个弹窗；若节点提示词、模型、规格、参考素材被修改或重试超次，将自动回退单节点重新确认。",
    promptSnippet: "一次性汇总确认多个画布生成节点的方案与算力消耗，批准后同会话内按范围直接执行。",
    promptGuidelines: [
      ...baseGuidelines,
      "需要生成多个画布节点时，先完成全部节点配置与连线，再调用 authorizeCanvasGenerationBatch 一次性确认整批方案；未改动的节点在批准后可直接调用 node:generate*，改动或重试会自动重新确认。",
    ],
    parameters: z.toJSONSchema(authorizeCanvasGenerationBatchSchema, { io: "input", target: "draft-07" }),
    executionMode: "sequential",
    async execute(id, params, signal) {
      if (!scope) throw new Error("当前会话未启用画布批量授权作用域");
      const result = await authorizeCanvasGenerationBatch(
        cwd,
        canvas,
        question,
        scope,
        params as z.input<typeof authorizeCanvasGenerationBatchSchema>,
        id,
        signal,
      );
      return {
        content: [{ type: "text", text: JSON.stringify(result) }],
        details: result,
      };
    },
  };
}

export function createAuthorizedCanvas(
  cwd: string,
  canvas: CanvasContext,
  question?: QuestionContext,
  authorizationScope?: CanvasAuthorizationScope,
): CanvasContext {
  return {
    ...canvas,
    async call(operation, signal, toolCallId) {
      const mediaType = operation.name === "nodeTools" && typeof operation.args.name === "string"
        ? generationTypes[operation.args.name]
        : undefined;
      if (!mediaType) return canvas.call(operation, signal);
      const args = canvasSchemas.nodeTools.parse(operation.args);
      const before = await snapshotCanvasGenerationNode(canvas, args.nodeId, mediaType, signal);
      if (authorizationScope) {
        const fingerprint = await computeMediaGenerationFingerprint(cwd, before.request, signal);
        if (authorizationScope.consume(before.canvasId, args.nodeId, mediaType, fingerprint)) {
          signal?.throwIfAborted();
          return canvas.call(operation, signal);
        }
      }
      if (!question) throw new Error("节点生成必须通过 askUser 确认，当前会话没有回答通道");
      await authorizeMediaGeneration(cwd, question, toolCallId, mediaType, before.request, signal);
      if (JSON.stringify(await snapshotCanvasGenerationNode(canvas, args.nodeId, mediaType, signal)) !== JSON.stringify(before)) {
        throw new Error("等待授权期间画布或节点输入已变化，请重新估价并确认");
      }
      signal?.throwIfAborted();
      return canvas.call(operation, signal);
    },
  };
}
