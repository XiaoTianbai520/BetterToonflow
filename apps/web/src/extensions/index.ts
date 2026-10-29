import canvasNode from "./canvasNode";
import md from "./md";
import image from "./image";
import media from "./media";
import txt from "./txt";
import type { ExtResource } from "./runtime";

export { canvasNode, md, image, media, txt };
export { defineExt } from "./runtime";
export type { ExtContext, ExtDefinition, ExtFiles, ExtResource } from "./runtime";

export const extensions = [canvasNode, md, image, media, txt];

export function extensionCandidates(resource: ExtResource) {
  const suffix = resource.path.split(".").at(-1)?.toLowerCase();
  return extensions.filter(extension => extension.resourceKind === resource.kind
    && (resource.kind === "canvasNode" || extension.extensions.includes(suffix ?? "")));
}

export function resolveExtension(resource: ExtResource, id?: string) {
  const candidates = extensionCandidates(resource);
  const extension = id ? candidates.find(extension => extension.id === id) : candidates[0];
  if (!extension) throw new Error(`没有支持此资源的内置扩展：${resource.label}`);
  return extension;
}
