import type { Component } from "vue";
import type { Rule } from "@form-create/element-ui";
import type useWorkspaceFiles from "@/lib/workspaceFiles";

export type ExtResource =
  | { kind: "file"; directory: string; path: string; label: string }
  | { kind: "canvasNode"; directory: string; path: string; nodeId: string; label: string };

export type ExtFiles = Pick<ReturnType<typeof useWorkspaceFiles>,
  "list" | "read" | "readText" | "readJson" | "write" | "writeJson" | "acquireUrl" | "rename" | "remove" | "mkdir">;

export interface ExtContext {
  resource: ExtResource;
  files: ExtFiles;
  text: string;
  loading: boolean;
  error: string;
  dirty: boolean;
  active: boolean;
  config: Record<string, unknown>;
  saveConfig: (config: Record<string, unknown>) => Promise<void>;
  updateText: (text: string) => void;
  flushSave: () => Promise<void>;
  writeClipboardText: (text: string) => Promise<void>;
  openFile: (path: string) => Promise<void>;
  location?: { line: number; column: number; query: string; revision: number };
  // 宿主静默激活 resource 中的画布与节点，再将节点自身的 teleport 目标设为 element；返回释放函数。
  mountNode?: (element: HTMLElement) => Promise<() => void>;
}

export interface ExtDefinition {
  id: string;
  displayName: string;
  icon?: string;
  configRules?: Rule[];
  extensions: string[];
  resourceKind: ExtResource["kind"];
  text?: boolean;
  load: () => Promise<{ default: Component }>;
}

export function defineExt(definition: ExtDefinition): ExtDefinition {
  if (typeof definition.id !== "string" || definition.id.length > 96 || !/^ext-[a-z][a-zA-Z0-9]*$/.test(definition.id)) throw new Error(`扩展 ID 必须为 ext- 加小驼峰名称，最多 96 个字符：${definition.id}`);
  if (typeof definition.displayName !== "string" || !definition.displayName.trim()) throw new Error("扩展显示名不能为空");
  if (definition.icon !== undefined) {
    const icon = definition.icon;
    if (typeof icon !== "string" || (icon && !/^data:image\/(?:svg\+xml|png|jpeg|webp|gif|avif)(?:;[^,]*)?,/i.test(icon)
      && !(URL.canParse(icon) && ["http:", "https:"].includes(new URL(icon).protocol)))) throw new Error("扩展图标必须是 HTTP、HTTPS 或图片 data URL");
  }
  if (definition.configRules !== undefined && (!Array.isArray(definition.configRules) || definition.configRules.length > 100)) throw new Error("扩展配置规则必须为最多 100 项的表单规则数组");
  if (definition.resourceKind !== "file" && definition.resourceKind !== "canvasNode") throw new Error("扩展资源类型无效");
  if (definition.text !== undefined && typeof definition.text !== "boolean") throw new Error("扩展文本标记必须为布尔值");
  if (!Array.isArray(definition.extensions) || definition.extensions.some(extension => typeof extension !== "string" || !/^[a-z0-9]+$/.test(extension))) throw new Error("文件后缀必须是不带点的小写字母或数字");
  if (definition.resourceKind === "file" && !definition.extensions.length) throw new Error("文件扩展至少需要一个后缀");
  if (new Set(definition.extensions).size !== definition.extensions.length) throw new Error("文件后缀不能重复");
  if (typeof definition.load !== "function") throw new Error("扩展必须提供组件加载函数");
  return definition;
}
