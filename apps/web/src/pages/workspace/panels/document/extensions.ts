import { computed, toRaw } from "vue";
import { extensionCandidates, extensions, type ExtResource } from "@/extensions";
import { saveSettings, settings } from "@/stores/settings";

// 文档工作台只注册源码内聚的 5 大内置扩展（PRD 8.2）：没有安装 / 启用 / 版本升级流程，
// 因此扩展条目本身就是 ExtDefinition，不再派生带 enabled / version 的第二套模型。
export { extensionCandidates, resolveExtension } from "@/extensions";

const extensionAssociations = computed<Record<string, string>>(() => {
  const value = settings.value.documentExtensions;
  return value && typeof value === "object" && !Array.isArray(value)
    ? Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === "string")) : {};
});

export function extensionIcon(resource: ExtResource) {
  const candidates = extensionCandidates(resource);
  const key = resource.kind === "canvasNode" ? "canvasNode" : resource.path.split(".").at(-1)!.toLowerCase();
  return (candidates.find(extension => extension.id === extensionAssociations.value[key]) ?? candidates[0])?.icon;
}

export function extensionConfig(id: string): Record<string, unknown> {
  const configs = settings.value.documentExtensionConfigs;
  const config = configs && typeof configs === "object" && !Array.isArray(configs) ? Reflect.get(configs, id) : undefined;
  return config && typeof config === "object" && !Array.isArray(config) ? config : {};
}

export async function saveExtensionConfig(id: string, config: Record<string, unknown>) {
  if (!extensions.some(extension => extension.id === id)) throw new Error("内置扩展不存在");
  const snapshot = structuredClone(toRaw(config));
  await saveSettings(current => {
    const previous = current.documentExtensionConfigs;
    return { documentExtensionConfigs: { ...(previous && typeof previous === "object" && !Array.isArray(previous) ? previous : {}), [id]: snapshot } };
  });
}
