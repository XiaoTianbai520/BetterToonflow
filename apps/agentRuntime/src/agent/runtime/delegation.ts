import { readFile } from "@toonflow/file";
import { parseSessionEntries } from "@earendil-works/pi-coding-agent";
import type { CanvasContext } from "@/tools/types";
import type { AgentEvent } from "@/agent/runtime/types";
import { run } from "@/agent/runtime";
import { createAgentConversation, getToolResultText, trackAgentEvent, updateSubAgent } from "@/agent/runtime/sessions";
import { addUsage, emptyUsage, type SubAgentResult } from "@/agent/runtime/subAgent";
import { createCanvasContext } from "@/agent/bridge/canvas";
import { createQuestionContext } from "@/agent/bridge/question";
import { resolveWorkspacePath } from "@/utils/workspace/files";
import { translateError, translateMessage } from "@/lib/messages";
import { resolve } from "node:path";

const workspaceTasks = new Map<string, { active: number; waiting: Set<() => void> }>();

async function acquireTask(cwd: string, signal?: AbortSignal) {
  signal?.throwIfAborted();
  const key = process.platform === "win32" ? resolve(cwd).toLowerCase() : resolve(cwd);
  let pool = workspaceTasks.get(key);
  if (!pool) { pool = { active: 0, waiting: new Set() }; workspaceTasks.set(key, pool); }
  const current = pool;
  if (current.active >= 4) await new Promise<void>((accept, reject) => {
    const ready = () => { signal?.removeEventListener("abort", abort); accept(); };
    const abort = () => {
      current.waiting.delete(ready);
      signal?.removeEventListener("abort", abort);
      reject(signal?.reason);
    };
    current.waiting.add(ready);
    signal?.addEventListener("abort", abort, { once: true });
  });
  else current.active++;
  return () => {
    const next = current.waiting.values().next().value;
    if (next) { current.waiting.delete(next); next(); }
    else if (--current.active === 0) workspaceTasks.delete(key);
  };
}

export async function runDelegatedAgent(options: {
  cwd: string; parentFile: string; name: string; task: string;
  providerId: string; modelId: string; thinkingLevel: "off" | "low" | "medium" | "high";
  canvas?: CanvasContext; signal?: AbortSignal; send: (event: AgentEvent) => void; onProgress?: (text: string) => void;
}) {
  const release = await acquireTask(options.cwd, options.signal);
  try {
    options.signal?.throwIfAborted();
    return await executeDelegatedAgent(options);
  } finally { release(); }
}

async function executeDelegatedAgent(options: Parameters<typeof runDelegatedAgent>[0]) {
  const { cwd, parentFile, name, task, providerId, modelId, thinkingLevel, canvas, signal, onProgress } = options;
  const child = await createAgentConversation(cwd, { parentFile, name, task, providerId, modelId, thinkingLevel });
  const agent = { file: child.file, parentFile, name, task, providerId, modelId, thinkingLevel, status: "running" as const };
  await updateSubAgent(cwd, parentFile, agent);
  const send = (event: AgentEvent) => {
    trackAgentEvent(cwd, child.file, event);
    options.send({ type: "subAgentEvent", file: child.file, event });
    if (event.type === "tool" && event.tool.status === "running") onProgress?.(`正在调用 ${event.tool.name}`);
  };
  const controller = new AbortController();
  const childSignal = signal ? AbortSignal.any([signal, controller.signal]) : controller.signal;
  const bridge = canvas ? createCanvasContext(cwd, canvas, send) : undefined;
  const questions = createQuestionContext(cwd, send, () => controller.abort());
  const result: SubAgentResult = { name, status: "running", result: "准备执行" };
  try {
    await run({ prompt: task, cwd, sessionFile: child.file, providerId, modelId, thinkingLevel, canvas: bridge?.context, question: questions.context, signal: childSignal, onCancel: () => controller.abort() }, send);
    result.status = childSignal.aborted ? "cancelled" : "completed";
  } catch (error) {
    result.status = childSignal.aborted ? "cancelled" : (error as { code?: string })?.code === "AGENT_LENGTH" ? "limited" : "error";
    result.result = error instanceof Error ? translateError(error) : translateMessage("子任务执行失败");
    if (result.status !== "limited") await updateSubAgent(cwd, parentFile, { ...agent, status: result.status, result: result.result });
    send({ type: "error", message: result.result });
  } finally {
    bridge?.dispose();
    questions.dispose();
    send({ type: "done" });
  }
  const { path } = await resolveWorkspacePath(cwd, `.agent/sessions/${child.file}`);
  const entries = parseSessionEntries(await readFile(path, "utf8"));
  const usage = emptyUsage();
  for (const entry of entries) {
    if (entry.type === "message" && (entry.message.role === "assistant" || entry.message.role === "toolResult") && entry.message.usage) addUsage(usage, entry.message.usage);
  }
  if (result.status === "completed" || result.status === "limited") {
    const reply = entries.findLast(entry => entry.type === "message" && entry.message.role === "assistant");
    result.result = reply?.type === "message" && reply.message.role === "assistant" ? getToolResultText(reply.message.content.filter(part => part.type === "text")) : "";
    if (reply?.type === "message" && reply.message.role === "assistant" && reply.message.stopReason === "length") result.status = "limited";
  }
  return { result, usage };
}
