<template>
  <component v-if="component && !error && !runtimeError" :is="component" v-bind="$attrs" />
  <el-card v-else class="remoteNodeState failed" shadow="never" role="alert">
    <div class="stateHeader">
      <strong>{{ node.data.label || node.type }}</strong>
      <span class="stateLabel">error</span>
      <div class="stateActions nodrag nopan" @pointerdown.stop @mousedown.stop @dblclick.stop>
        <el-button :icon="IconRefresh" text title="重新加载节点" aria-label="重新加载节点" @click.stop="retry" />
        <el-button :icon="IconTrash" text title="移除节点" aria-label="移除节点" @click.stop="removeNodes(node.id)" />
      </div>
    </div>
    <p>{{ error || runtimeError || "该节点类型不属于内置节点" }}</p>
  </el-card>
</template>

<script setup lang="ts">
import { onErrorCaptured, ref, watch, type Component } from "vue";
import { useNode, useVueFlow } from "@vue-flow/core";
import { ElButton, ElCard } from "element-plus";
import { IconRefresh, IconTrash } from "@tabler/icons-vue";
import type { NodeData } from "@/nodes/nodeCore/connection";

defineOptions({ inheritAttrs: false });
const props = defineProps<{ component?: Component | string; error?: string }>();
const { node } = useNode<NodeData>();
const { removeNodes } = useVueFlow();
const runtimeError = ref("");

onErrorCaptured(error => {
  runtimeError.value = error instanceof Error ? error.message : String(error);
  console.error(`节点 ${node.type} (${node.id}) 运行失败`, error);
  return false;
});
watch(() => props.component, () => { runtimeError.value = ""; });

function retry() { runtimeError.value = ""; }
</script>

<style scoped lang="scss">
.remoteNodeState {
  width: 320px;
  &.failed { border-color: var(--el-color-danger); }
  .stateHeader {
    display: flex;
    align-items: center;
    gap: 8px;
    strong { flex: 1; min-width: 0; overflow-wrap: anywhere; }
    .stateLabel { font-size: 12px; color: var(--el-text-color-secondary); }
    .stateActions {
      display: flex;
      .el-button { width: 28px; height: 28px; margin: 0; padding: 0; }
    }
  }
  &.failed .stateLabel { color: var(--el-color-danger); }
  p { margin: 10px 0 0; font-size: 12px; color: var(--el-text-color-secondary); white-space: pre-wrap; overflow-wrap: anywhere; }
}
</style>
