<template>
  <nodeSkeleton
    v-bind="nodeProps"
    :topVisible="node.selected"
    :bottomVisible="node.selected"
    :bottomWidth="660"
    topWidth="max-content"
    :downloadUrl="previewUrl"
    :downloadName="outputFile?.url.split(/[\\/]/).at(-1)"
    :fullscreenVisible="false"
    style="width: 360px">
    <template #topActions>
      <mediaHistory mediaType="audio" :current="outputFile" :disabled="busy" @select="outputs.audio = { dataType: 'AUDIO', value: $event }" />
      <el-button :icon="IconTransfer" :loading="uploading" :disabled="busy" text title="替换音频" aria-label="替换音频" @click.stop="fileInput?.click()">替换音频</el-button>
      <input ref="fileInput" type="file" accept="audio/*" hidden aria-label="选择替换音频" :disabled="busy" @change="replaceOutput" />
    </template>
    <div v-loading="generating || uploading" class="audioContent nopan" :aria-busy="generating || uploading">
      <audioPlayer v-if="previewUrl" ref="player" class="audioPreview" :src="previewUrl" @loadedmetadata="updateNodeInternals" />
      <div v-else class="audioEmpty" role="img" aria-label="暂无生成音频"><icon-music-bolt :size="48" stroke="1.25" aria-hidden="true" /></div>
    </div>
    <template #bottom>
      <el-card class="promptCard" shadow="never" :bodyStyle="{ padding: '14px 16px 12px' }">
        <referenceItem v-if="refList.length" v-model="refList" @preview="setReferencePreview" @remove="removeReference" />
        <promptInput v-model="data.promptModel" v-model:text="data.prompt" :references="referenceMentions" placeholder="描述您想要的音频效果或输入待朗读文本" expandable />
        <div class="promptFooter">
          <el-select
            v-model="data.model"
            class="modelSelect"
            filterable
            :loading="modelsLoading"
            :disabled="busy"
            placeholder="选择模型"
            aria-label="音频生成模型"
            noDataText="请先在设置中添加音频模型"
            placement="top-start"
            @visibleChange="(visible: boolean) => visible && loadModels().catch(error => showNodeError(error, '模型读取失败'))">
            <template #prefix><icon-music-bolt :size="17" /></template>
            <el-option-group v-for="provider in modelGroups" :key="provider.id" :label="provider.label">
              <el-option v-for="item in provider.models" :key="item.modelId" :label="item.label" :value="JSON.stringify([item.providerId, item.modelId])" />
            </el-option-group>
          </el-select>
          <generationSettings v-model:voice="data.voice" v-model:language="data.language" v-model:sampleRate="data.sampleRate" v-model:format="data.format" :voices="selectedModel?.voices ?? []" :disabled="busy || !selectedModel" />
          <div class="generationActions">
            <mediaPrice v-if="generationInput" :input="{ ...generationInput, mediaType: 'audio' }" :supported="selectedModel?.supportsPrice === true" />
            <el-button
              class="sendButton"
              :icon="generating ? IconPlayerStop : IconArrowUp"
              :disabled="deleting || uploading || (!generating && (!generationPrompt || !selectedModel))"
              :title="generating ? '停止生成' : '生成音频'"
              :aria-label="generating ? '停止生成' : '生成音频'"
              @click="generating ? generationController?.abort() : startGeneration().catch(error => showNodeError(error, '音频生成失败'))" />
          </div>
        </div>
      </el-card>
    </template>
  </nodeSkeleton>
</template>

<script setup lang="ts">
import { toolDefinitions } from "./toolDefinitions";
import { computed, onMounted, onScopeDispose, ref, watch } from "vue";
import { ElButton, ElCard, ElLoading, ElOption, ElOptionGroup, ElSelect } from "element-plus";
import { IconArrowUp, IconMusicBolt, IconPlayerStop, IconTransfer } from "@tabler/icons-vue";
import { getTargetValues, isTypeCompatible, nodeSkeleton, nodeTools, useNode, type NodeAudioRequest, type NodeHandle, type NodeInputValue, type NodeMediaModel } from "@/nodes/nodeCore/runtime";
import { groupNodeModels } from "@/nodes/nodeCore/groupNodeModels";
import { showNodeError } from "@/nodes/nodeCore/showNodeError";
import { useNodeGeneration } from "@/nodes/nodeCore/useNodeGeneration";
import { useNodeReferences } from "@/nodes/nodeCore/useNodeReferences";
import audioPlayer from "@/nodes/audioNode/components/audioPlayer.vue";
import promptInput from "@/nodes/nodeCore/components/promptInput.vue";
import referenceItem from "@/nodes/nodeCore/components/referenceItem.vue";
import mediaPrice from "@/nodes/nodeCore/components/mediaPrice.vue";
import mediaHistory from "@/nodes/nodeCore/components/mediaHistory.vue";
import generationSettings from "./components/generationSettings.vue";
import { formatOptions, languageOptions, sampleRateOptions } from "./audioSettings";

defineOptions({
  inheritAttrs: false,
  icon: IconMusicBolt,
  handles: [
    { id: "in", type: "target", dataType: ["IMAGE", "AUDIO", "STRING"], label: "图片或音频限1个，文本不限" },
    { id: "audio", type: "source", dataType: "AUDIO", label: "音频输出" },
  ] satisfies NodeHandle[],
});
const vLoading = ElLoading.directive;
const { id, node, nodeProps, nodeEvent, outputs, files, ai, updateNodeInternals } = useNode({ label: "音频生成" });
type PromptModel = NonNullable<InstanceType<typeof promptInput>["$props"]["modelValue"]>;
const data = computed(() => node.data as { prompt: string; promptModel: PromptModel; model: string; voice: string; language: string; sampleRate: number; format: string });
data.value.prompt ??= "";
data.value.promptModel ??= [];
data.value.model ??= "";
data.value.voice ??= "";
data.value.language ??= "zh-CN";
data.value.sampleRate ??= 24000;
data.value.format ??= "wav";
const { refList, referenceMentions, setReferencePreview, removeReference } = useNodeReferences();
const models = ref<NodeMediaModel[]>([]);
const modelsLoading = ref(false);
const uploading = ref(false);
const deleting = ref(false);
const fileInput = ref<HTMLInputElement>();
const player = ref<InstanceType<typeof audioPlayer>>();
let disposed = false;
let generationController: AbortController | undefined;
let generation: Promise<void> | undefined;
let modelsRequest: Promise<void> | undefined;
let modelsRefreshPending = false;
const generationState = useNodeGeneration(outputs, () => generationController?.abort());
const { generating } = generationState;
const busy = computed(() => generating.value || uploading.value || deleting.value);
watch(busy, value => { if (!value && modelsRefreshPending) void refreshProviderModels(); });
const selectedModel = computed(() => models.value.find(item => JSON.stringify([item.providerId, item.modelId]) === data.value.model));
const modelGroups = computed(() => groupNodeModels(models.value));
const generationPrompt = computed(() => [data.value.prompt.trim(), ...refList.value.flatMap((item, index) =>
  item.dataType === "STRING" && item.value?.trim() ? [`参考 ${index + 1}：\n${item.value.trim()}`] : [])].filter(Boolean).join("\n\n"));
const generationInput = computed<Omit<NodeAudioRequest, "directory"> | undefined>(() => {
  const choice = selectedModel.value;
  if (!choice) return;
  return {
    providerId: choice.providerId,
    modelId: choice.modelId,
    voice: data.value.voice || undefined,
    prompt: generationPrompt.value,
    language: data.value.language,
    sampleRate: data.value.sampleRate,
    format: data.value.format,
    outputDirectory: `assets/${id}`,
    images: refList.value.flatMap(item => item.dataType === "IMAGE" && item.value ? [{ path: item.value.url, mimeType: item.value.mimeType }] : []),
    audios: refList.value.flatMap(item => item.dataType === "AUDIO" && item.value ? [{ path: item.value.url, mimeType: item.value.mimeType }] : []),
  };
});
const outputFile = computed(() => outputs.value.audio?.dataType === "AUDIO" ? outputs.value.audio.value : undefined);
const previewUrl = files.useFileUrl(outputFile, error => showNodeError(error, "音频读取失败"));

function referencesAllowed(values: NodeInputValue[]) {
  return values.filter(item => isTypeCompatible(item.dataType, ["IMAGE", "AUDIO"])).length <= 1;
}

nodeEvent.on("canConnect", (connection, context) => {
  // ACT: 重验已有连线时不重复计数；未产出内容的来源仍按声明类型占位。
  const edges = context.edges.filter(edge => edge.source !== connection.source || edge.sourceHandle !== connection.sourceHandle
    || edge.target !== connection.target || edge.targetHandle !== connection.targetHandle);
  return referencesAllowed(getTargetValues(id, "in", context.nodes, [...edges, connection]));
});

onMounted(() => {
  window.addEventListener("toonflow:plugin-installed", refreshProviderModels);
  window.addEventListener("toonflow:provider-config-changed", refreshProviderModels);
  void refreshProviderModels();
});
onScopeDispose(() => {
  disposed = true;
  window.removeEventListener("toonflow:plugin-installed", refreshProviderModels);
  window.removeEventListener("toonflow:provider-config-changed", refreshProviderModels);
  generationController?.abort();
});

async function refreshProviderModels(event?: Event) {
  if (event?.type === "toonflow:plugin-installed" && (event as CustomEvent<{ type?: string }>).detail?.type !== "provider") return;
  modelsRefreshPending = true;
  if (disposed || busy.value) return;
  try {
    await modelsRequest;
    if (disposed || busy.value) return;
    modelsRefreshPending = false;
    await loadModels();
  } catch (error) {
    if (!disposed) showNodeError(error, "模型读取失败");
  }
}

function loadModels() {
  if (modelsRequest) return modelsRequest;
  modelsLoading.value = true;
  modelsRequest = ai.getMediaModels().then(items => {
    if (disposed) return;
    if (busy.value) {
      modelsRefreshPending = true;
      return;
    }
    models.value = items.filter(item => item.type === "audio");
    // ACT: 仅空配置选择默认模型，临时不可用的模型保持原选择，避免静默换供应商。
    if (!data.value.model) {
      const first = models.value[0];
      data.value.model = first ? JSON.stringify([first.providerId, first.modelId]) : "";
    }
  }).finally(() => {
    modelsLoading.value = false;
    modelsRequest = undefined;
  });
  return modelsRequest;
}

async function startGeneration() {
  if (busy.value || disposed) throw new Error("音频正在处理或节点已关闭，请稍后重试");
  const input = generationInput.value;
  if (!input) throw new Error("请先选择音频模型");
  if (!generationPrompt.value) throw new Error("请输入生成提示词或待朗读文本");
  if (!referencesAllowed(refList.value)) throw new Error("图片和音频不能同时引用，且合计最多引用一个；文本数量不限");
  if (refList.value.some(item => item.value === undefined)) throw new Error("引用节点暂无内容，请先补充引用内容");
  if (!languageOptions.some(item => item.value === data.value.language) || !sampleRateOptions.includes(data.value.sampleRate) || !formatOptions.includes(data.value.format)) throw new Error("请重新选择有效的语种、采样率和输出格式");
  const workspace = files.getWorkspaceFiles();
  const controller = new AbortController();
  generationController = controller;
  generation = generationState.run(async () => {
    const { directory } = await workspace.list();
    controller.signal.throwIfAborted();
    const [result] = await ai.generateAudio({ ...input, directory }, controller.signal);
    controller.signal.throwIfAborted();
    if (!result) throw new Error("供应商未返回音频");
    outputs.value.audio = { dataType: "AUDIO", value: { url: result.path, mimeType: result.mimeType } };
  }).catch(error => showNodeError(error, "音频生成失败")).finally(() => {
    generationController = undefined;
  });
  return { status: "generating" };
}

async function replaceOutput(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || busy.value || disposed) return;
  if (!file.type.startsWith("audio/")) return void showNodeError("请选择音频文件", "音频替换失败");
  if (!file.size || file.size > 100 * 1024 * 1024) return void showNodeError("音频不能为空且不能超过 100 MB", "音频替换失败");
  uploading.value = true;
  try {
    const workspace = files.getWorkspaceFiles();
    const url = await files.uploadFile(file);
    if (disposed) {
      await workspace.remove(url);
      return;
    }
    // ACT: 替换不删除旧音频，保留复制节点与撤销记录的文件引用。
    outputs.value.audio = { dataType: "AUDIO", value: { url, mimeType: file.type } };
  } catch (error) {
    showNodeError(error, "音频替换失败");
  } finally {
    uploading.value = false;
  }
}

nodeEvent.on("save", reason => {
  if (uploading.value || (reason === "reload" && busy.value)) throw new Error("音频处理中，请完成或取消后再刷新节点");
});
nodeEvent.on("delete", async () => {
  if (uploading.value) throw new Error("音频正在替换，请稍后删除节点");
  deleting.value = true;
  generationController?.abort();
  try {
    await generation;
    await files.removeNodeFiles();
  } finally {
    deleting.value = false;
  }
});

function getConfig() {
  return {
    config: { providerId: selectedModel.value?.providerId ?? "", modelId: selectedModel.value?.modelId ?? "", voice: data.value.voice, language: data.value.language, sampleRate: data.value.sampleRate, format: data.value.format },
    models: models.value,
    languages: languageOptions,
    sampleRates: sampleRateOptions,
    formats: formatOptions,
    inputLimits: { text: "unlimited", imageOrAudio: 1 },
  };
}

nodeTools.register({
    ...toolDefinitions.getConfig,
  async execute(_args, { signal }) {
    signal?.throwIfAborted();
    await loadModels();
    signal?.throwIfAborted();
    return getConfig();
  },
});
nodeTools.register({
    ...toolDefinitions.setConfig,
  async execute(args, { signal }) {
    signal?.throwIfAborted();
    if (busy.value || disposed) throw new Error("音频正在处理或节点已关闭，请稍后修改配置");
    await loadModels();
    signal?.throwIfAborted();
    if (busy.value || disposed) throw new Error("音频正在处理或节点已关闭，请稍后修改配置");
    const choice = args.modelId === undefined ? selectedModel.value : models.value.find(item => item.providerId === args.providerId && item.modelId === args.modelId);
    if (!choice) throw new Error("请选择 getConfig 返回的有效音频模型");
    if (args.voice && choice.voices?.length && !choice.voices.some(item => item.voice === args.voice)) throw new Error("请选择当前模型的有效音色");
    if (args.language !== undefined && !languageOptions.some(item => item.value === args.language)) throw new Error("请选择 getConfig 返回的有效语种");
    if (args.sampleRate !== undefined && !sampleRateOptions.includes(args.sampleRate)) throw new Error("请选择 getConfig 返回的有效采样率");
    if (args.format !== undefined && !formatOptions.includes(args.format)) throw new Error("请选择 getConfig 返回的有效输出格式");
    data.value.model = JSON.stringify([choice.providerId, choice.modelId]);
    if (args.voice !== undefined) data.value.voice = args.voice;
    if (args.language !== undefined) data.value.language = args.language;
    if (args.sampleRate !== undefined) data.value.sampleRate = args.sampleRate;
    if (args.format !== undefined) data.value.format = args.format;
    return getConfig();
  },
});
nodeTools.register({
    ...toolDefinitions.setPrompt,
  execute({ prompt }) {
    if (deleting.value || disposed) throw new Error("节点正在删除或已关闭，请稍后修改");
    data.value.prompt = prompt;
    data.value.promptModel = prompt.split("\n").map(text => [{ type: "Write", text }]);
    return { prompt };
  },
});
nodeTools.register({
    ...toolDefinitions.generateAudio,
  execute(_args, { signal }) {
    signal?.throwIfAborted();
    return startGeneration();
  },
});
</script>

<style scoped lang="scss">
.audioContent {
  position: relative;
  min-height: 144px;
  display: flex;
  align-items: center;

  .audioPreview { display: block; width: 100%; border-radius: var(--el-border-radius-base); }
  .audioEmpty { display: grid; place-items: center; width: 100%; min-height: 144px; color: var(--el-text-color-placeholder); }
  :deep(.el-loading-mask) { pointer-events: none; }
}
.promptCard {
  .promptFooter {
    display: flex;
    align-items: center;
    gap: 12px;

    .modelSelect {
      width: 190px;
      min-width: 0;
      &:deep(.el-select__wrapper) { gap: 6px; padding: 0; box-shadow: none; background: transparent; }
    }
    .generationActions {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-left: auto;

      .sendButton {
        width: 32px;
        height: 32px;
        margin-left: 0;
        padding: 0;
        --el-button-bg-color: var(--el-text-color-primary);
        --el-button-border-color: transparent;
        --el-button-text-color: var(--el-bg-color);
        --el-button-hover-bg-color: var(--el-text-color-regular);
        --el-button-hover-border-color: transparent;
        --el-button-hover-text-color: var(--el-bg-color);
      }
    }
  }
}
</style>
