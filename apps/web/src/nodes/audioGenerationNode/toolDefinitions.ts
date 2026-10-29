import { z } from "zod";

export const toolDefinitions = {
  getConfig: { name: "getConfig", description: "读取此音频生成节点的配置、可选音频模型、音色、语种、采样率、输出格式与输入数量限制，不含密钥；文本不限，图片与音频互斥且合计最多一个，具体模型能力由供应商校验", parameters: z.strictObject({}) },
  setConfig: { name: "setConfig", description: "修改音频生成模型、音色、语种、采样率或格式；先用 getConfig 查询选项，providerId 与 modelId 必须同时提供；不修改提示词、不启动生成", parameters: z.strictObject({
    providerId: z.string().min(1).optional(),
    modelId: z.string().min(1).optional(),
    voice: z.string().max(200).optional(),
    language: z.string().optional(),
    sampleRate: z.number().int().positive().optional(),
    format: z.string().optional(),
  }).refine(args => (args.providerId === undefined) === (args.modelId === undefined), "providerId 与 modelId 必须同时提供") },
  setPrompt: { name: "setPrompt", description: "修改此节点的音频生成提示词或待朗读文本，支持 {{ref 1}} 等参考标记；只修改文本，不启动生成", parameters: z.strictObject({ prompt: z.string() }) },
  generateAudio: { name: "generateAudio", description: "使用当前提示词、模型、语种、采样率、格式和可选参考图片或音频启动后台生成；图片与音频互斥且合计最多一个，文本不限；立即返回已开始，用 getGenerationStatus 查询结果、cancelGeneration 停止", parameters: z.strictObject({}) },
};
