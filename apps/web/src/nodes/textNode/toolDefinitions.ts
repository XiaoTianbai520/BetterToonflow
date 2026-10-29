import { z } from "zod";

export const toolDefinitions = {
  setText: { name: "setText", description: "修改此节点的文本输出", parameters: z.strictObject({ text: z.string() }) },
};
