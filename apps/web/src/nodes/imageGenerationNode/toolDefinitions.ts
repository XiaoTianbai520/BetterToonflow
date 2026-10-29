import { z } from "zod";

export const toolDefinitions = {
  getConfig: { name: "getConfig", description: "读取此图片生成节点的当前模型、分辨率、比例及可选图片模型能力，不含密钥；未声明分辨率或比例时分别使用 2K、16:9", parameters: z.strictObject({}) },
  setConfig: { name: "setConfig", description: "修改此图片生成节点的模型、分辨率或比例；先用 getConfig 查询可选能力，providerId 与 modelId 必须同时提供；不修改提示词、不启动生成", parameters: z.strictObject({
    providerId: z.string().min(1).optional(),
    modelId: z.string().min(1).optional(),
    size: z.string().min(1).optional(),
    ratio: z.string().min(1).optional(),
  }).refine((args) => (args.providerId === undefined) === (args.modelId === undefined), "providerId 与 modelId 必须同时提供") },
  setPrompt: { name: "setPrompt", description: "修改此节点的图片生成提示词，支持 {{ref 1}} 等参考标记；不启动生成", parameters: z.strictObject({ prompt: z.string() }) },
  generateImage: { name: "generateImage", description: "启动此节点的后台图片生成，使用提示词、模型、分辨率、比例和参考图片；立即返回已开始，用 getGenerationStatus 查询完成结果，cancelGeneration 停止生成", parameters: z.strictObject({}) },
};
