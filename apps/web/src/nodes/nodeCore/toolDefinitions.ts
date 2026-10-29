import { z } from "zod";

export const toolDefinitions = {
  getGenerationStatus: { name: "getGenerationStatus", description: "查询本次打开节点后的生成状态（idle/running/succeeded/failed）、当前输出和最近一次生成错误。当前输出可能来自之前的生成；idle 不表示没有历史输出，只有 succeeded 表示本次生成成功", parameters: z.strictObject({}) },
  cancelGeneration: { name: "cancelGeneration", description: "请求停止当前后台生成；cancellationRequested 表示已发出停止请求，随后用 getGenerationStatus 查询终止状态。不会删除已有输出，不能保证供应商撤销任务或费用", parameters: z.strictObject({}) },
};
