import useWorkspaceFiles from "@/lib/workspaceFiles";
import type { AgentAttachment } from "./types";
import { decodeTextFile } from "@/extensions/documentFormats";

export function createPastedTextFile(text: string): File | undefined {
  if (text.length <= 2000 && text.split(/\r\n|\r|\n/).length <= 30) return;
  if (text.length > 100000) throw new Error("文本附件不能超过 100000 字符");
  const file = new File([text], `${"粘贴文本"}.txt`, { type: "text/plain" });
  if (file.size > 400000) throw new Error("文本附件不能超过 400000 字节");
  return file;
}

export async function readTextAttachment(attachment: AgentAttachment, directory?: string) {
  if (attachment.file) return decodeTextFile(new Uint8Array(await attachment.file.arrayBuffer()));
  if (!directory) throw new Error("请先打开项目");
  return decodeTextFile(new Uint8Array(await useWorkspaceFiles(directory).read(attachment.path)));
}
