import type { AgentQuestionField, AgentQuestionRequest, AgentToolCall } from "../types";

// 工具卡片与 Agent 契约共用同一份提问/工具字段定义，不再各自复制一份。
export type QuestionField = AgentQuestionField;
export type QuestionRequest = AgentQuestionRequest & { callId: string };

// 卡片在流式过程中还会收到 streaming / completed 中间态，字段是 AgentToolCall 的浏览器侧超集。
export type ToolCall = Omit<AgentToolCall, "status" | "question"> & {
  status: "streaming" | "running" | "success" | "completed" | "error" | "interrupted";
  arguments?: Record<string, unknown>;
  content?: string;
  question?: QuestionRequest;
};

export interface CanvasContext {
  getNodeLabel?(nodeId: string): string | undefined;
  call(request: { name: string; args: Record<string, unknown> }, signal?: AbortSignal): Promise<unknown>;
}
