import { z } from "zod";
import type { CanvasContext, ToolDefinition } from "@/tools/types";

const nodeIdSchema = z.string().trim().min(1).max(256);

export const waitForCanvasGenerationSchema = z.strictObject({
  nodeIds: z.array(nodeIdSchema).min(1).max(20)
    .refine(value => new Set(value).size === value.length, "nodeIds 不能重复，请先去重"),
  mode: z.enum(["all", "any"]).default("all"),
  timeoutSeconds: z.number().int().min(10).max(14400).default(5400),
  pollIntervalSeconds: z.number().int().min(5).max(30).optional(),
});

const generationStatusSchema = z.object({
  status: z.string(),
  outputs: z.record(z.string(), z.unknown()).optional(),
  error: z.string().optional(),
});

export interface WaitCanvasNodeState {
  nodeId: string;
  status: string;
  outputs?: Record<string, unknown>;
  error?: string;
}

export interface WaitCanvasGenerationResult {
  completed: boolean;
  timedOut: boolean;
  elapsedSeconds: number;
  nodes: WaitCanvasNodeState[];
}

export interface WaitCanvasGenerationOptions {
  signal?: AbortSignal;
  onProgress?: (text: string, snapshot: WaitCanvasGenerationResult) => void;
  now?: () => number;
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>;
}

function abortReason(signal?: AbortSignal) {
  return signal?.reason ?? Object.assign(new Error("等待已取消"), { name: "AbortError" });
}

export function sleepAbortable(ms: number, signal?: AbortSignal): Promise<void> {
  if (ms <= 0) {
    if (signal?.aborted) return Promise.reject(abortReason(signal));
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortReason(signal));
      return;
    }
    const onAbort = () => {
      clearTimeout(timer);
      reject(abortReason(signal));
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function formatElapsed(elapsedSeconds: number) {
  const minutes = Math.floor(elapsedSeconds / 60);
  const seconds = elapsedSeconds % 60;
  return `${minutes} 分 ${seconds} 秒`;
}

export async function waitForCanvasGeneration(
  canvas: CanvasContext,
  input: z.input<typeof waitForCanvasGenerationSchema>,
  options: WaitCanvasGenerationOptions = {},
): Promise<WaitCanvasGenerationResult> {
  const { nodeIds, mode, timeoutSeconds, pollIntervalSeconds } = waitForCanvasGenerationSchema.parse(input);
  const { signal, onProgress, now = () => Date.now(), sleep = sleepAbortable } = options;
  const startTime = now();
  const deadline = startTime + timeoutSeconds * 1000;
  const states = new Map<string, WaitCanvasNodeState>();
  const pendingIds = new Set<string>(nodeIds);
  let firstPass = true;

  while (true) {
    signal?.throwIfAborted();
    for (const nodeId of [...pendingIds]) {
      signal?.throwIfAborted();
      const raw = await canvas.call(
        { name: "nodeTools", args: { nodeId, name: "node:getGenerationStatus", args: {} } },
        signal,
      );
      const parsed = generationStatusSchema.parse(raw);
      states.set(nodeId, {
        nodeId,
        status: parsed.status,
        ...(parsed.outputs !== undefined ? { outputs: parsed.outputs } : {}),
        ...(parsed.error ? { error: parsed.error } : {}),
      });
      // ACT: 除 running 外（succeeded / failed / idle 等）均视为终态，避免节点未启动或复位时永久空等。
      if (parsed.status !== "running") {
        pendingIds.delete(nodeId);
        if (mode === "any" && !firstPass) break;
      }
    }
    firstPass = false;

    const currentTime = now();
    const elapsedMs = Math.max(0, currentTime - startTime);
    const elapsedSeconds = Math.round(elapsedMs / 1000);
    const nodes = nodeIds.map(id => states.get(id)!);
    const completed = mode === "all" ? pendingIds.size === 0 : pendingIds.size < nodeIds.length;
    if (completed) {
      return { completed: true, timedOut: false, elapsedSeconds, nodes };
    }

    const remainingMs = deadline - currentTime;
    if (remainingMs <= 0) {
      return { completed: false, timedOut: true, elapsedSeconds, nodes };
    }

    const runningCount = pendingIds.size;
    const finishedCount = nodeIds.length - runningCount;
    const statusText = nodes.map(item => `${item.nodeId}:${item.status}`).join("，");
    const reportProgress = (seconds: number) => onProgress?.(
      `已等待 ${formatElapsed(seconds)}：已完成 ${finishedCount}/${nodeIds.length}，运行中 ${runningCount}/${nodeIds.length}（${statusText}）`,
      { completed: false, timedOut: false, elapsedSeconds: seconds, nodes },
    );
    reportProgress(elapsedSeconds);

    const intervalSeconds = pollIntervalSeconds ?? (elapsedMs < 60_000 ? 5 : 15);
    let sleepMs = Math.min(intervalSeconds * 1000, remainingMs);
    while (sleepMs > 0) {
      // 状态查询仍按原间隔执行。等待期间推送进度，让网关及时发现客户端断开。
      const step = onProgress ? Math.min(sleepMs, 1000) : sleepMs;
      await sleep(step, signal);
      sleepMs -= step;
      if (sleepMs > 0) reportProgress(Math.max(0, Math.round((now() - startTime) / 1000)));
    }
  }
}

export function createWaitForCanvasGenerationTool(canvas: CanvasContext, baseGuidelines: string[]): ToolDefinition {
  return {
    name: "waitForCanvasGeneration",
    label: "等待节点生成完成",
    description: "等待激活画布中指定生成节点完成（状态离开 running，包括 succeeded、failed、idle），全程在运行时内定时轮询，不消耗额外 LLM 回合。支持 mode='all'（全部结束）或 'any'（任一结束），默认最长等待 5400 秒（1.5 小时，最高 14400 秒 / 4 小时）；省略 pollIntervalSeconds 时自动退避（前 60 秒每 5 秒查询一次，之后每 15 秒查询一次）。返回结构化状态、耗时与最新 outputs/error。",
    promptSnippet: "等待激活画布中的生成节点完成，在运行时内轮询而不消耗额外 LLM 回合。",
    promptGuidelines: [
      ...baseGuidelines,
      "触发 node:generateImage / node:generateVideo / node:generateAudio 后需要等待真实完成结果时，调用 waitForCanvasGeneration，不要反复轮询 node:getGenerationStatus。",
    ],
    parameters: z.toJSONSchema(waitForCanvasGenerationSchema, { io: "input", target: "draft-07" }),
    executionMode: "sequential",
    async execute(_id, params, signal, onUpdate) {
      try {
        const result = await waitForCanvasGeneration(canvas, params as z.input<typeof waitForCanvasGenerationSchema>, {
          signal,
          onProgress(text, snapshot) {
            if (!signal?.aborted) {
              onUpdate?.({ content: [{ type: "text", text }], details: snapshot });
            }
          },
        });
        return {
          content: [{ type: "text", text: JSON.stringify(result) }],
          details: result,
        };
      } catch (error) {
        if (!signal?.aborted) throw error;
        return { content: [{ type: "text", text: "等待已取消" }], details: { cancelled: true } };
      }
    },
  };
}
