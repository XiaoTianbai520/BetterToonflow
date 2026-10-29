export interface QuestionField {
  field: string;
  title: string;
  type: "input" | "textarea" | "radio" | "checkbox" | "select" | "inputNumber" | "switch";
  required?: boolean;
  options?: string[];
  placeholder?: string;
}

export interface QuestionRequest {
  callId: string;
  title: string;
  question: string;
  options?: string[];
  fields?: QuestionField[];
}

export interface ToolCall {
  id: string;
  name: string;
  args?: Record<string, unknown>;
  arguments?: Record<string, unknown>;
  status: "streaming" | "running" | "success" | "completed" | "error" | "interrupted";
  result?: string;
  content?: string;
  question?: QuestionRequest;
}

export interface CanvasContext {
  getNodeLabel?(nodeId: string): string | undefined;
  call(request: { name: string; args: Record<string, unknown> }, signal?: AbortSignal): Promise<unknown>;
}
