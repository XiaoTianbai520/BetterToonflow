import { computed } from "vue";
import { defineStore } from "pinia";
import { customProviders, saveSettings, settings } from "@/stores/settings";

export const useHelloStore = defineStore("hello", () => {
  const hasConfiguredModel = computed(() => customProviders.value.some(provider =>
    provider.enabled !== false && provider.apiUrl?.trim()
    && ["openai-completions", "openai-responses", "anthropic-messages"].includes(provider.protocol)
    && provider.models.some(model => model.id.trim()),
  ));
  const completed = computed(() => hasConfiguredModel.value && settings.value.helloCompleted === true);

  async function load() {
    if (hasConfiguredModel.value && !completed.value) await complete();
    return completed.value;
  }

  async function complete() {
    if (!hasConfiguredModel.value) throw new Error("请先配置一个有效的文本模型");
    await saveSettings(() => ({ helloCompleted: true }));
  }

  async function reset() {
    await saveSettings(() => ({ helloCompleted: false }));
  }

  return { completed, hasConfiguredModel, load, complete, reset };
});
