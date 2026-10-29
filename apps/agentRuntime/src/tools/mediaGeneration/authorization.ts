import { createHash } from "node:crypto";
import { readFile, stat } from "@toonflow/file";
import { getMediaPrice, listMediaModels } from "@/media/generation";
import { getMediaProvider } from "@/media/provider";
import conf from "@/utils/conf";
import { resolveWorkspacePath } from "@/utils/workspace/files";
import type { MediaGenerationRequest, QuestionContext } from "../types";

export async function authorizeMediaGeneration(cwd: string, question: QuestionContext | undefined, callId: string | undefined,
  mediaType: "image" | "video" | "audio", request: MediaGenerationRequest, signal?: AbortSignal) {
  signal?.throwIfAborted();
  if (!question) throw new Error("媒体生成必须通过 askUser 确认，当前会话没有回答通道");
  const model = (await listMediaModels()).find(model => model.providerId === request.providerId && model.modelId === request.modelId && model.type === mediaType);
  if (!model) throw new Error("所选媒体模型不存在或类型不匹配，请重新查询模型");
  const fingerprint = async () => {
    const provider = await getMediaProvider(request.providerId);
    const digest = createHash("sha256").update(JSON.stringify({ request, source: provider.source,
      config: conf.get("settings", {}).mediaProviderConfigs }));
    for (const reference of [...request.images ?? [], ...request.videos ?? [], ...request.audios ?? [],
      ...request.firstFrame ? [request.firstFrame] : [], ...request.lastFrame ? [request.lastFrame] : []]) {
      signal?.throwIfAborted();
      const { path } = await resolveWorkspacePath(cwd, reference.path);
      const info = await stat(path);
      if (!info.isFile() || info.size > 100 * 1024 * 1024) throw new Error("参考素材必须是不超过 100 MB 的普通文件");
      digest.update(await readFile(path, { signal }));
    }
    return digest.digest("hex");
  };
  const revision = await fingerprint();
  const price = await getMediaPrice(cwd, mediaType, request, signal);
  const result = await question.ask(callId ?? crypto.randomUUID(), {
    title: "确认消耗算力",
    question: `模型：${model.providerLabel} / ${model.label}（${request.providerId}/${request.modelId}）\n数量：1 次生成调用（返回数量由模型决定）\n预估消耗：${price ? `${price.price}${price.tips ? `；${price.tips}` : ""}` : "算力消耗未知，供应商未提供报价"}\n参数与参考素材：${JSON.stringify({ ...request, prompt: undefined })}\n完整提示词：\n${request.prompt}\n仅本次调用有效；重试或更改方案需要重新确认。`,
    fields: [{ field: "confirmGeneration", title: "是否授权本次生成", type: "radio", required: true, options: ["确认生成", "拒绝生成"] }],
  }, signal);
  signal?.throwIfAborted();
  if (result.skipped || result.values?.confirmGeneration !== "确认生成") throw new Error("用户未确认本次算力消耗，已取消生成");
  if (await fingerprint() !== revision) throw new Error("等待确认期间模型、参数或参考素材发生变化，请重新估价并确认");
  if (JSON.stringify(await getMediaPrice(cwd, mediaType, request, signal)) !== JSON.stringify(price)) {
    throw new Error("等待确认期间预估价格发生变化，请重新确认");
  }
}
