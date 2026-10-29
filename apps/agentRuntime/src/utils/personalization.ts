import { t, translateMessage } from "@/lib/messages";
import { createHash } from "node:crypto";
import { lstat, readFile, realpath } from "@toonflow/file";
import { dirname, join } from "node:path";
import conf from "@/utils/conf";
import { resolveWorkspacePath } from "@/utils/workspace/files";
import { z } from "zod";

export type PersonalizationDocument = "memory" | "agents";

const documents = { memory: "memories/memory.md", agents: "AGENTS.md" };
const labels = { memory: "全局记忆", agents: "全局 AGENTS.md" };
export const maxDocumentLength = 20000;

export function isMemoryEnabled() {
  const value = conf.get("settings", {}).personalization;
  return !value || typeof value !== "object" || Array.isArray(value) || (value as Record<string, unknown>).memoryEnabled !== false;
}

function result(content: string) {
  return { content, revision: createHash("sha256").update(content).digest("hex") };
}

async function documentPath(document: PersonalizationDocument) {
  if (!Object.hasOwn(documents, document)) throw Object.assign(new Error("个性化文档类型无效"), { status: 400 });
  const directory = dirname(conf.path);
  const directories = [directory, ...(document === "memory" ? [join(directory, "memories")] : [])];
  for (const path of directories) {
    const info = await lstat(path).catch((err: NodeJS.ErrnoException) => { if (err.code === "ENOENT") return null; throw err; });
    if (info?.isSymbolicLink()) throw Object.assign(new Error("不能通过符号链接访问个性化文档"), { status: 403 });
    if (info && !info.isDirectory()) throw Object.assign(new Error("个性化文档所在路径不是目录"), { status: 400 });
    if (!info) return join(directory, documents[document]);
  }
  return (await resolveWorkspacePath(await realpath(directory), documents[document], true)).path;
}

async function readContent(path: string) {
  const info = await lstat(path).catch((err: NodeJS.ErrnoException) => { if (err.code === "ENOENT") return null; throw err; });
  if (!info) return "";
  if (info.isSymbolicLink()) throw Object.assign(new Error("不能通过符号链接访问个性化文档"), { status: 403 });
  if (!info.isFile()) throw Object.assign(new Error("个性化文档必须是普通文件"), { status: 400 });
  if (info.size > maxDocumentLength * 3 + 3) throw Object.assign(new Error(t`个性化文档不能超过 ${maxDocumentLength} 个字符`), { status: 413 });
  // 每次读取最新原子快照并计算 revision，不缓存文档正文。
  const content = (await readFile(path, "utf8")).replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
  if (content.length > maxDocumentLength) throw Object.assign(new Error(t`个性化文档不能超过 ${maxDocumentLength} 个字符`), { status: 413 });
  return content;
}

function documentError(action: "read" | "save", document: PersonalizationDocument, cause: unknown) {
  const err = cause as NodeJS.ErrnoException & { status?: number };
  const label = translateMessage(labels[document] ?? "个性化文档");
  const message = translateMessage(err?.message ?? String(cause));
  return Object.assign(new Error(action === "read" ? t`读取${label}失败：${message}` : t`保存${label}失败：${message}`, { cause }), { status: err?.status, code: err?.code });
}

export async function readDocument(document: PersonalizationDocument) {
  try {
    return result(await readContent(await documentPath(document)));
  } catch (err) { throw documentError("read", document, err); }
}

export async function saveDocument(document: PersonalizationDocument, content: string, revision: string) {
  try {
    if (!Object.hasOwn(documents, document) || !/^[a-f0-9]{64}$/.test(revision)) throw Object.assign(new Error("个性化文档参数无效"), { status: 400 });
    content = content.replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
    if (content.length > maxDocumentLength) throw Object.assign(new Error(t`个性化文档不能超过 ${maxDocumentLength} 个字符`), { status: 413 });
    // DEC-05：复用核心后端既有接口完成 CAS 与事务；Runtime 不直接写入派生快照。
    const response = await fetch("http://127.0.0.1:3000/api/settings/personalization/save", {
      method: "PUT",
      headers: { "Content-Type": "application/json", "X-Toonflow-Internal-Token": process.env.TOONFLOW_INTERNAL_TOKEN ?? "", "X-Request-Id": crypto.randomUUID() },
      body: JSON.stringify({ document, content, revision }),
      signal: AbortSignal.timeout(30000),
      redirect: "error",
    });
    const body = z.object({ code: z.number(), message: z.string(), data: z.unknown() }).parse(await response.json());
    if (!response.ok || body.code !== 200) throw Object.assign(new Error(body.message), { status: response.ok ? body.code : response.status,
      code: body.data && typeof body.data === "object" && "code" in body.data ? body.data.code : undefined });
    return z.object({ content: z.string().max(maxDocumentLength), revision: z.string().regex(/^[a-f0-9]{64}$/) }).parse(body.data);
  } catch (err) { throw documentError("save", document, err); }
}
