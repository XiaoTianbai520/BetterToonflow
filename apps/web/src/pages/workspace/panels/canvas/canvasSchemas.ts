import { z } from "zod";
import type { NodeToolInfo } from "@/nodes/nodeCore/types";

// ACT: 画布工具参数的唯一权威定义。字段、默认值、约束与报错文案沿用 W2-T3 迁移前
// packages/tools/canvas/src/runtime.ts 的 canvasSchemas，前端校验与 AgentRuntime 保持逐字一致；
// 前端只消费参数校验与上下文类型，工具描述与注册表仍由 apps/agentRuntime 持有。

const nodeId = z.string().min(1).max(256);
const position = z.strictObject({ x: z.number().finite(), y: z.number().finite() });
const canvasId = nodeId.optional();
const cursor = z.string().min(1).max(4096).optional();
const nodeIds = z.array(nodeId).min(1).max(20);

export const canvasSchemas = {
  getCanvas: z.strictObject({
    canvasId,
    include: z.array(z.enum(["canvases", "nodeTypes"])).max(2).optional(),
    cursor,
    limit: z.number().int().min(1).max(100).default(30),
  }),
  findCanvasNodes: z.strictObject({
    canvasId,
    query: z.string().max(200).optional(),
    types: z.array(nodeId).max(20).optional(),
    selectedOnly: z.boolean().optional(),
    cursor,
    limit: z.number().int().min(1).max(100).default(30),
  }),
  getCanvasNodes: z.strictObject({
    canvasId,
    nodeIds: nodeIds.refine(value => new Set(value).size === value.length, "nodeIds 不能重复，请先去重"),
    fields: z.array(z.enum(["label", "type", "position", "ports", "data", "outputs"])).max(6).default(["label", "type", "position"]),
    dataKeys: z.array(nodeId).min(1).max(20).optional(),
    path: z.array(z.string().max(256)).min(1).max(64)
      .refine(value => ["label", "type", "position", "ports", "data", "outputs"].includes(value[0]!), "path 必须从节点投影字段开始")
      .refine(value => new TextEncoder().encode(JSON.stringify(value)).byteLength <= 2048, "path 的 JSON UTF-8 长度不能超过 2048 字节").optional(),
    valueOffset: z.number().int().min(0).default(0),
    valueLimit: z.number().int().min(1).max(100).default(30),
    textOffset: z.number().int().min(0).default(0),
    textLimit: z.number().int().min(1).max(4000).default(1000),
    cursor,
  }).refine(value => Boolean(value.path) || (value.textOffset === 0 && value.valueOffset === 0), "textOffset 或 valueOffset 大于 0 时必须提供 path"),
  getCanvasEdges: z.strictObject({
    canvasId,
    nodeIds: nodeIds.optional(),
    direction: z.enum(["incoming", "outgoing", "both"]).default("both"),
    cursor,
    limit: z.number().int().min(1).max(200).default(100),
  }),
  getNodeTools: z.strictObject({
    canvasId,
    nodeIds: nodeIds.refine(value => new Set(value).size === value.length, "nodeIds 不能重复，请先去重"),
    names: z.array(nodeId).min(1).max(20).optional(),
    cursor,
    limit: z.number().int().min(1).max(50).default(20),
  }),
  addCanvas: z.strictObject({ name: z.string().trim().min(1).max(120).optional() }),
  switchCanvas: z.strictObject({ canvasId: z.string().min(1).max(256) }),
  renameCanvas: z.strictObject({ canvasId: z.string().min(1).max(256).optional(), name: z.string().trim().min(1).max(120) }),
  addNode: z.strictObject({ type: z.string().min(1), position, label: z.string().trim().min(1).max(200).optional() }),
  deleteNodes: z.strictObject({ nodeIds: z.array(nodeId).min(1).max(64) }),
  moveNodes: z.strictObject({ moves: z.array(z.strictObject({ nodeId, position })).min(1).max(64) }),
  renameNodes: z.strictObject({ renames: z.array(z.strictObject({ nodeId, label: z.string().trim().min(1).max(200) })).min(1).max(64) }),
  connectNodes: z.strictObject({
    connections: z.array(z.strictObject({ source: nodeId, sourceHandle: z.string().min(1), target: nodeId, targetHandle: z.string().min(1) })).min(1).max(64),
  }),
  deleteEdges: z.strictObject({ edgeIds: z.array(z.string().min(1).max(256)).min(1).max(64) }),
  selectNodes: z.strictObject({ nodeIds: z.array(nodeId) }),
  arrangeCanvas: z.strictObject({}),
  fitCanvas: z.strictObject({ nodeIds: z.array(nodeId).optional() }),
  nodeTools: z.strictObject({ nodeId, name: z.templateLiteral(["node:", z.string().regex(/^[a-z][a-zA-Z0-9]{0,63}$/)]), args: z.record(z.string(), z.json()) }),
};

export type CanvasOperationName = keyof typeof canvasSchemas;

// 画布工具调用请求：name 为操作名，args 为未经校验的原始入参，进入各操作前按 canvasSchemas 解析。
export interface CanvasToolCall {
  name: string;
  args: Record<string, unknown>;
}

// 画布基本信息：会话创建与画布重定向时提交给 AgentRuntime 的 { id, tools } 结构。
export interface CanvasInfo {
  id: string;
  tools: NodeToolInfo[];
}

// 画布上下文：会话侧注入的调用入口，节点函数通过 getNodeTools 按需发现，初始化不携带节点清单。
// 与历史 tools-scaffold 定义一致：浏览器侧只传请求与中止信号，工具调用 ID 由 AgentRuntime 维护。
export interface CanvasContext extends CanvasInfo {
  getNodeLabel?(nodeId: string): string | undefined;
  call(request: CanvasToolCall, signal?: AbortSignal): Promise<unknown>;
}
