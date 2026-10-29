<template>
  <nodeSkeleton
    v-bind="nodeProps"
    :topVisible="node.selected"
    topWidth="max-content"
    :downloadUrl="previewUrl"
    :downloadName="outputFile?.url.split(/[\\/]/).at(-1)"
    :fullscreenVisible="false"
    style="width: 360px">
    <template #topActions>
      <el-button
        :icon="IconTransfer"
        :loading="uploading"
        text
        title="替换音频"
        aria-label="替换音频"
        @click.stop="fileInput?.click()">替换音频</el-button>
    </template>
    <div class="audioContent nopan">
      <audioPlayer
        v-if="previewUrl"
        ref="player"
        class="audioPreview"
        :src="previewUrl"
        @loadedmetadata="updateNodeInternals" />
      <input ref="fileInput" class="fileInput" type="file" accept="audio/*" aria-label="选择音频" :disabled="uploading" @change="uploadAudio" />
      <el-button
        v-if="!outputs.audio"
        class="uploadButton"
        text
        :loading="uploading"
        title="上传音频"
        aria-label="上传音频"
        @dblclick.stop
        @click="fileInput?.click()">
        <icon-upload v-if="!uploading" :size="48" stroke="1.5" />
      </el-button>
    </div>
  </nodeSkeleton>
</template>

<script setup lang="ts">
import { toolDefinitions } from "./toolDefinitions";
import { computed, ref } from "vue";
import { IconMusic, IconUpload, IconTransfer } from "@tabler/icons-vue";
import { ElButton, ElMessage } from "element-plus";
import { nodeSkeleton, nodeTools, useNode, type NodeHandle } from "@/nodes/nodeCore/runtime";
import audioPlayer from "./components/audioPlayer.vue";

defineOptions({
  inheritAttrs: false,
  icon: IconMusic,
  handles: [{ id: "audio", type: "source", dataType: "AUDIO", label: "音频输出" }] satisfies NodeHandle[],
});
const { node, nodeProps, outputs, nodeEvent, files, updateNodeInternals } = useNode({
  label: "音频",
});
const fileInput = ref<HTMLInputElement>();
const uploading = ref(false);
const player = ref<InstanceType<typeof audioPlayer>>();

const outputFile = computed(() => outputs.value.audio?.dataType === "AUDIO" ? outputs.value.audio.value : undefined);
const previewUrl = files.useFileUrl(
  outputFile,
  (error) => showError(error, "音频读取失败")
);

nodeEvent.on("save", () => {
  if (uploading.value) throw new Error("音频处理中，请完成后再切换或刷新节点");
});
nodeEvent.on("delete", async () => {
  if (uploading.value) throw new Error("音频上传中，请稍后删除节点");
  uploading.value = true;
  try {
    await files.removeNodeFiles();
  } finally {
    uploading.value = false;
  }
});

nodeTools.register({
    ...toolDefinitions.setAudio,
  async execute({ path, mimeType }, { signal }) {
    signal?.throwIfAborted();
    if (uploading.value) throw new Error("音频处理中，请稍后重试");
    uploading.value = true;
    try {
      const content = await files.getWorkspaceFiles().read(path);
      signal?.throwIfAborted();
      if (!content.byteLength || content.byteLength > 100 * 1024 * 1024) throw new Error("音频不能为空且不能超过 100 MB");
      outputs.value.audio = { dataType: "AUDIO", value: { url: path, mimeType } };
      return outputs.value.audio;
    } finally {
      uploading.value = false;
    }
  },
});

async function uploadAudio(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || uploading.value) return;
  if (!file.type.startsWith("audio/")) return void ElMessage.error("请选择音频文件");
  if (!file.size || file.size > 100 * 1024 * 1024) return void ElMessage.error("音频不能为空且不能超过 100 MB");
  uploading.value = true;
  try {
    const url = await files.uploadFile(file);
    // ACT: 复制节点可能仍引用旧音频，替换输出不删除共享文件。
    outputs.value.audio = { dataType: "AUDIO", value: { url, mimeType: file.type } };
  } catch (error) {
    showError(error, "音频替换失败");
  } finally {
    uploading.value = false;
  }
}

function showError(error: unknown, fallback: string) {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message;
  ElMessage.error(message || (error instanceof Error ? error.message : fallback));
}
</script>

<style scoped lang="scss">
.audioContent {
  position: relative;
  min-height: 144px;
  display: flex;
  align-items: center;

  .audioPreview {
    display: block;
    width: 100%;
    border-radius: var(--el-border-radius-base);
  }

  .fileInput {
    display: none;
  }

  .uploadButton {
    width: 100%;
    height: 144px;
    padding: 0;
    color: var(--el-text-color-placeholder);

    &:hover {
      color: var(--el-color-primary);
    }
  }
}
</style>
