import { createHash } from "node:crypto";
import { lstatSync, readFileSync } from "@toonflow/file";
import { resolve } from "node:path";

type Snapshot = {
  settings: Record<string, unknown>;
  disabledSkills: string[];
  toolConfigs: Record<string, Record<string, unknown>>;
};

let revision: string | undefined;
let snapshot: Partial<Snapshot> = {};

function refresh() {
  let source: string;
  try {
    const info = lstatSync(config.path);
    if (!info.isFile() || info.isSymbolicLink()) throw new Error("配置快照必须是非符号链接的普通文件");
    source = readFileSync(config.path, "utf8");
  } catch (cause) {
    if ((cause as NodeJS.ErrnoException).code !== "ENOENT") throw cause;
    revision = undefined;
    snapshot = {};
    return;
  }
  // ACT: 沿用同步 get，每次核对摘要，成本为 O(快照字节数)；快照增大后可改为异步读取。
  const currentRevision = createHash("sha256").update(source).digest("hex");
  if (revision === currentRevision) return;
  let current: unknown;
  try { current = JSON.parse(source.replace(/^\uFEFF/, "")); }
  catch (cause) { throw new Error("配置快照 JSON 无效", { cause }); }
  if (!current || typeof current !== "object" || Array.isArray(current)) throw new Error("配置快照必须是 JSON 对象");
  const value = current as Record<string, unknown>;
  if (Object.hasOwn(value, "settings") && (!value.settings || typeof value.settings !== "object" || Array.isArray(value.settings))) {
    throw new Error("settings 必须是 JSON 对象");
  }
  // 接受原 conf 包装快照及直接保存的 settings 对象。
  snapshot = Object.hasOwn(value, "settings") ? value : { settings: value };
  revision = currentRevision;
}

function get<K extends keyof Snapshot>(key: K, defaultValue: Snapshot[K]): Snapshot[K];
function get(key: string): unknown;
function get(key: string, defaultValue?: unknown): unknown {
  refresh();
  let value: unknown = snapshot;
  for (const part of key.split(".")) {
    if (!value || typeof value !== "object" || !Object.hasOwn(value, part)) return defaultValue;
    value = (value as Record<string, unknown>)[part];
  }
  return value === undefined ? defaultValue : structuredClone(value);
}

const config = {
  get path() { return resolve(process.env.TOONFLOW_DATA_DIR ?? resolve(import.meta.dirname, "../../../../../data"), "settings.json"); },
  get,
};

export default config;
