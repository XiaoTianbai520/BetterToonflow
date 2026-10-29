import { z } from "zod";

export const toolDefinitions = {
  setAudio: { name: "setAudio", description: "选择工作区内已有的音频文件作为此节点的输出，path 使用工作区相对路径", parameters: z.strictObject({
    path: z.string().min(1).max(4096),
    mimeType: z.string().regex(/^audio\/[a-zA-Z0-9.+-]+$/),
  }) },
};
