import { z } from "zod";
import type { ToolDefinition } from "@earendil-works/pi-coding-agent";
import { addUsage, emptyUsage, type SubAgentResult } from "@/agent/runtime/subAgent";

const parameters = z.strictObject({
  tasks: z.array(z.strictObject({
    name: z.string().trim().min(1).max(80).describe("本次任务名称"),
    task: z.string().trim().min(1).max(24000).describe("完整任务、必要背景、相对路径、已获授权和预期交付；不继承主对话历史"),
  })).min(1).max(8),
});

export async function createSubAgentTool({ tools, runTask }: {
  tools: ToolDefinition[];
  runTask: (name: string, task: string, signal?: AbortSignal, onProgress?: (text: string) => void) => Promise<{ result: SubAgentResult; usage: ReturnType<typeof emptyUsage> }>;
}): Promise<ToolDefinition> {
  if (tools.some(tool => tool.name === "subAgent")) throw new Error("工具名称 subAgent 已被内置子任务工具占用");
  const subAgentTool: ToolDefinition = {
    name: "subAgent", label: "子任务",
    description: `同工作区委派独立子 Agent，每次最多 8 个任务，同工作区最多 4 个并发，其余排队。子 Agent 使用宿主工具、askUser 确认与 report 上报，不继续嵌套委派。宿主工具：${tools.map(tool => tool.name).join("、")}。`,
    promptSnippet: "按需使用 subAgent 委派独立工作。",
    promptGuidelines: [
      "简单任务直接完成；只委派相互独立的工作，提供必要背景和真实授权。并行任务不得修改同一文件或画布，依赖任务分次处理。",
      "委派不扩大权限或消耗算力授权，只提供完成任务所需且获准分享的内容。",
      "核验结果和 status；错误、取消、达到限制均不等于完成，不自动重试可能已经产生副作用的任务。",
    ],
    parameters: z.toJSONSchema(parameters, { io: "input", target: "draft-07" }),
    executionMode: "sequential",
    async execute(_id, params, signal, onUpdate) {
      signal?.throwIfAborted();
      const { tasks } = parameters.parse(params);
      const results: SubAgentResult[] = tasks.map(task => ({ name: task.name, status: "running", result: "准备执行" }));
      const usage = emptyUsage();
      const content = () => [{ type: "text" as const, text: JSON.stringify({ tasks: results }) }];
      const update = () => { if (!signal?.aborted) onUpdate?.({ content: content(), details: {} }); };
      update();
      await Promise.all(tasks.map(async (task, index) => {
        const onProgress = (text: string) => { results[index]!.result = text; update(); };
        try {
          const output = await runTask(task.name, task.task, signal, onProgress);
          results[index] = { ...output.result, name: task.name };
          addUsage(usage, output.usage);
        } catch (error) {
          results[index] = { name: task.name, status: signal?.aborted ? "cancelled" : "error", result: error instanceof Error ? error.message : String(error) };
        }
        update();
      }));
      return { content: content(), details: {}, usage };
    },
  };
  return subAgentTool;
}
