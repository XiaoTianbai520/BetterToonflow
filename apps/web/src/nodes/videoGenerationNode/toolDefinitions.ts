import { z } from "zod";

export const ratioOptions = ["16:9", "9:16", "1:1", "4:3", "3:4"];

export const toolDefinitions = {
  getConfig: { name: "getConfig", description: "读取此视频生成节点的当前配置、可选视频模型能力、通用比例及适合当前引用的模式，不含密钥；时长与分辨率须符合 durationResolutionMap", parameters: z.strictObject({}) },
  setConfig: { name: "setConfig", description: "修改此视频生成节点的模型、时长、分辨率、比例、模式或声音；先用 getConfig 查询能力，providerId 与 modelId 必须同时提供；mode 使用返回的原始字符串或数组，须匹配当前引用；不修改提示词、不启动生成", parameters: z.strictObject({
    providerId: z.string().min(1).optional(),
    modelId: z.string().min(1).optional(),
    duration: z.number().positive().optional(),
    resolution: z.string().min(1).optional(),
    ratio: z.enum(ratioOptions).optional(),
    mode: z.union([z.string().min(1), z.array(z.string().min(1)).min(1)]).optional(),
    generateAudio: z.boolean().optional(),
  }).refine((args) => (args.providerId === undefined) === (args.modelId === undefined), "providerId 与 modelId 必须同时提供") },
  setPrompt: { name: "setPrompt", description: "修改此节点的视频生成提示词，支持 {{ref 1}} 等参考标记；只修改提示词，不启动生成", parameters: z.strictObject({ prompt: z.string() }) },
  generateVideo: { name: "generateVideo", description: "启动此节点的后台视频生成，使用当前提示词、模型、模式、时长、分辨率、比例和参考素材；立即返回已开始，用 getGenerationStatus 查询完成结果，cancelGeneration 停止生成", parameters: z.strictObject({}) },
};
