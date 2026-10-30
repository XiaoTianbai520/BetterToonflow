// 前端 Agent 契约：与 apps/agentRuntime/src/agent/runtime/types.ts 的 NDJSON 事件帧严格一一对应。
// 这里只放浏览器可直接使用的纯类型，禁止引入 Bun / Node 运行时依赖，供会话、流式回复、@ 引用与工具卡片共用。

export type AgentQuestionField = {
  field: string;
  title: string;
  type: "input" | "textarea" | "radio" | "checkbox" | "select" | "inputNumber" | "switch";
  required?: boolean;
  options?: string[];
  placeholder?: string;
};

export type AgentQuestionRequest = {
  title: string;
  question: string;
  options?: string[];
  fields?: AgentQuestionField[];
};

export type AgentToolCall = {
  id: string;
  name: string;
  args?: Record<string, unknown>;
  status: "running" | "success" | "error" | "interrupted";
  result?: string;
  question?: AgentQuestionRequest & { callId: string };
};

export type AgentCanvasToolCall = { name: string; args: Record<string, unknown> };

export type AgentMention = {
  id: string;
  label: string;
  source:
    | { kind: "canvas"; canvasId: string; canvasName: string; nodeId: string; nodeName: string; outputId: string; outputName: string }
    | { kind: "asset"; path: string };
  dataType: string;
  value: unknown;
};

export type AgentStats = {
  tokens: { input: number; output: number; cacheRead: number; cacheWrite: number; total: number };
  tokensPerSecond?: number;
};

export type AgentContext = { tokens: number | null; contextWindow: number; percent: number | null };

export type AgentSubAgent = {
  file: string;
  parentFile: string;
  name: string;
  task: string;
  status: "running" | "completed" | "error" | "limited" | "cancelled" | "inputRequired";
  result?: string;
  providerId?: string;
  modelId?: string;
  thinkingLevel?: "off" | "low" | "medium" | "high";
};

export type AgentEvent =
  | { type: "text" | "thinking"; blockId: string; delta?: string; content?: string; done?: boolean }
  | { type: "compaction"; active: boolean }
  | { type: "tool"; blockId: string; tool: AgentToolCall }
  | ({ type: "canvasCall"; callId: string } & AgentCanvasToolCall)
  | ({ type: "question"; callId: string; toolCallId: string } & AgentQuestionRequest)
  | { type: "error"; message: string }
  | { type: "session"; file: string }
  | { type: "userMessage"; id: string; content?: string; attachments?: { name: string; path: string; mimeType: string }[]; mentions?: AgentMention[] }
  | { type: "subAgent"; agent: AgentSubAgent }
  | { type: "subAgentEvent"; file: string; event: AgentEvent }
  | { type: "report"; parentFile: string; file: string; name: string; content: string; id: string }
  | { type: "accepted" }
  | { type: "stats"; stats: AgentStats; contextUsage?: AgentContext }
  | { type: "done" };

export type AgentAttachment = { name: string; path: string; mimeType: string; file?: File };

export type AgentMessagePart =
  | { id: string; type: "text"; content: string }
  | { id: string; type: "thinking"; content: string; collapsed?: boolean; duration?: number }
  | { id: string; type: "tool"; tool: AgentToolCall; collapsed?: boolean };

export type AgentMessage = {
  id: string;
  entryId?: string;
  replyTo?: string;
  role: "user" | "assistant";
  content: string;
  attachments?: AgentAttachment[];
  mentions?: AgentMention[];
  parts?: AgentMessagePart[];
  streaming?: boolean;
  error?: string;
  report?: { file: string; name: string };
};

export type AgentHistory = { file: string; name: string; modified: string; messageCount: number };

export type AgentConversation = {
  file: string;
  name: string;
  messages: AgentMessage[];
  providerId?: string;
  modelId?: string;
  thinkingLevel?: string;
  stats?: AgentStats;
  contextUsage?: AgentContext;
  parentFile?: string;
  subAgents?: AgentSubAgent[];
  running?: boolean;
};
