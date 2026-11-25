<template>
  <main class="workspacePage" :style="{ '--agentWidth': `${agentVisible ? agentWidth : 0}px` }">
    <nav class="workspaceRail" aria-label="工作区导航">
      <span class="railLogo" aria-hidden="true"><img :src="logoUrl" alt="" /></span>
      <button class="railItem" :class="{ active: activePanel === 'canvas' }" type="button" title="画布" aria-label="画布" @click="switchPanel('canvas')"><icon-vector :size="20" /></button>
      <button class="railItem" type="button" title="素材库" aria-label="素材库" @click="openAssets"><icon-photo :size="20" /></button>
      <button class="railItem" :class="{ active: activePanel === 'document' }" type="button" title="文档" aria-label="文档" @click="switchPanel('document')"><icon-file-text :size="20" /></button>
      <button class="railItem" type="button" title="技能与模型" aria-label="技能与模型" @click="settingsVisible = true"><icon-book :size="20" /></button>
      <span class="railDivider" />
      <button class="railItem" type="button" title="快速打开" aria-label="快速打开" @click="openDocumentSearch"><icon-search :size="20" /></button>
      <workspaceMenu class="workspaceMenu" @openSettings="settingsVisible = true" />
    </nav>

    <section class="workspaceMain">
      <header class="workspaceTopbar">
        <div class="crumbs">
          <span class="projectName">{{ workspaceStore.project?.name || "未命名项目" }}</span>
          <span class="separator">/</span>
          <span class="fileName">{{ currentFileName }}<icon-chevron-down :size="13" /></span>
        </div>
        <el-segmented :modelValue="activePanel" class="panelSwitcher" :options="panelOptions" size="small" aria-label="切换面板" @change="switchPanel">
          <template #default="{ item }">
            <span class="panelOption"><component :is="item.icon" :size="14" aria-hidden="true" />{{ item.label }}</span>
          </template>
        </el-segmented>
        <div class="topbarActions">
          <span class="saveState"><icon-check :size="13" />本地自动保存</span>
          <button class="creditChip" type="button" aria-label="算力与模型设置" title="算力与模型设置" @click="settingsVisible = true"><icon-bolt :size="14" /><strong>{{ balanceLabel }}</strong><span>算力</span></button>
        </div>
      </header>

      <div class="workspaceBody">
        <aside v-show="activePanel === 'canvas' && sidebarVisible" class="workspaceSidebar">
          <div class="sidebarTabs" role="tablist" aria-label="资源面板">
            <button type="button" role="tab" :aria-selected="sidebarTab === 'assets'" :class="{ active: sidebarTab === 'assets' }" @click="sidebarTab = 'assets'">素材</button>
            <button type="button" role="tab" :aria-selected="sidebarTab === 'canvases'" :class="{ active: sidebarTab === 'canvases' }" @click="sidebarTab = 'canvases'">画布</button>
          </div>
          <el-button class="addNodeWide" :icon="IconPlus" :disabled="!canvasPanelRef?.canvasReady || settingsVisible" @click="canvasPanelRef?.addNodeAtPointer()">添加节点</el-button>
          <div class="sidebarContent">
            <assetLibrary v-if="sidebarTab === 'assets'" v-model="sidebarAssetsVisible" :directory="workspaceStore.project?.directory" embedded />
            <div v-else class="canvasList">
              <div class="sidebarSectionTitle"><span>项目画布</span><small>{{ canvasItems.length }}</small></div>
              <button v-for="item in canvasItems" :key="item.id" class="canvasListItem" :class="{ active: item.id === canvasPanelRef?.canvasId }" type="button" @click="canvasPanelRef?.activateCanvas(item.id)">
                <icon-vector :size="16" /><span>{{ item.name }}</span><icon-chevron-right v-if="item.id === canvasPanelRef?.canvasId" :size="14" />
              </button>
              <div v-if="!canvasItems.length" class="sidebarEmpty">画布加载后会显示在这里</div>
            </div>
          </div>
          <button class="sidebarCollapse" type="button" @click="sidebarVisible = false"><icon-chevron-left :size="13" />收起面板</button>
        </aside>

        <section class="workspaceStage">
          <canvasPanel
            :key="workspaceStore.project?.directory"
            v-model:selectedTool="selectedTool"
            ref="canvasPanelRef"
            class="canvasPanel"
            :class="{ backgroundPanel: activePanel !== 'canvas' }"
            :inert="activePanel !== 'canvas'"
            :aria-hidden="activePanel !== 'canvas'"
            :active="activePanel === 'canvas'"
            :settingsVisible="settingsVisible" />
          <keep-alive :max="1">
            <documentPanel
              v-if="activePanel === 'document'"
              :key="workspaceStore.project?.directory"
              :ref="setDocumentPanel"
              class="documentPanelHost"
              :readNode="readDocumentNode"
              :readNodes="readDocumentNodes"
              :focusNode="focusDocumentNode"
              :mountNode="mountDocumentNode"
              :resolveNodeFile="resolveDocumentNodeFile"
              :observeNode="observeDocumentNode"
              :flushNodes="flushDocumentNodes"
              :fileAction="performFileAction"
              :saveNode="saveDocumentNode" />
          </keep-alive>
          <div class="workspaceTools">
            <div class="bottomTools">
              <el-card v-if="activePanel === 'canvas'" class="toolMenu" shadow="never" :bodyStyle="{ padding: '5px' }">
                <div class="toolMenuContent" role="toolbar" aria-label="画布工具">
                  <el-tooltip content="添加节点" placement="top" :showArrow="false" :hideAfter="0" :enterable="false" :triggerKeys="[]">
                    <el-button class="toolButton addNodeButton" :disabled="!canvasPanelRef?.canvasReady || settingsVisible" aria-label="添加节点" aria-haspopup="menu" @click="canvasPanelRef?.addNodeAtPointer()"><icon-plus :size="18" /></el-button>
                  </el-tooltip>
                  <el-tooltip :content="selectedTool === 'move' ? '移动工具，点击切换为抓手' : '抓手工具，点击切换为移动'" placement="top" :showArrow="false" :hideAfter="0" :enterable="false" :triggerKeys="[]">
                    <el-button class="toolButton" text :aria-pressed="selectedTool === 'hand'" aria-label="切换抓手/移动工具" @click="selectedTool = selectedTool === 'move' ? 'hand' : 'move'">
                      <component :is="selectedTool === 'move' ? IconPointer : IconHandStop" :size="18" />
                    </el-button>
                  </el-tooltip>
                  <span class="toolSeparator" />
                  <el-tooltip :showArrow="false" content="帮助" placement="top" :hideAfter="0" :enterable="false" :triggerKeys="[]" :disabled="helpVisible">
                    <span class="toolTrigger">
                      <el-popover v-model:visible="helpVisible" trigger="click" placement="top-end" :width="196">
                        <template #reference><el-button class="toolButton" text aria-label="帮助"><icon-help :size="18" /></el-button></template>
                        <div class="helpMenu">
                          <el-button class="helpAction" tag="a" text :icon="IconBook" href="https://qcn7xdsqgc4z.feishu.cn/docx/RXFqdgR2Xo0dXZxGfd0cZCGgnwf" target="_blank" rel="noopener noreferrer" @click="helpVisible = false">使用教程</el-button>
                          <el-button class="helpAction" tag="a" text :icon="IconBug" href="https://docs.qq.com/smartsheet/form/EmvmQBrmlPmr%2Fss_vsqk2v%2FvhiGzE?tab=ss_vsqk2v" target="_blank" rel="noopener noreferrer" @click="helpVisible = false">汇报 BUG</el-button>
                          <el-button class="helpAction" text :icon="IconBrandWechat" @click="showContact('community')">加入交流群</el-button>
                          <el-button class="helpAction" text :icon="IconBriefcase" @click="showContact('business')">商务合作</el-button>
                        </div>
                      </el-popover>
                    </span>
                  </el-tooltip>
                </div>
              </el-card>
              <el-tooltip v-if="!agentVisible" content="Toonflow Agent" placement="top" :showArrow="false" :hideAfter="0">
                <el-button class="agentButton" :class="{ standalone: activePanel !== 'canvas' }" aria-label="Toonflow Agent" aria-controls="agentPanel" @click="agentVisible = true"><icon-sparkles :size="20" /></el-button>
              </el-tooltip>
            </div>
          </div>
        </section>
      </div>
    </section>
    <el-button v-if="activePanel === 'canvas' && !sidebarVisible" class="sidebarReopen" :icon="IconLayoutSidebar" aria-label="展开资源面板" title="展开资源面板" @click="sidebarVisible = true" />
    <floatingAgent v-model="agentVisible" :topOffset="56" @resize="agentWidth = $event" />
    <settings v-model="settingsVisible" />
    <el-dialog v-model="contactVisible" :title="contactInfo.title" width="min(360px, calc(100vw - 32px))" alignCenter appendToBody>
      <div class="contactContent">
        <q-r-code :value="contactInfo.url" :size="192" type="svg" color="#000000" bgColor="#ffffff" borderless role="img" :aria-label="`${contactInfo.title}二维码`" />
        <p class="contactTip">{{ contactInfo.tip }}</p>
      </div>
    </el-dialog>
  </main>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, provide, ref, shallowRef, watch, type ComponentPublicInstance } from "vue";
import { onBeforeRouteLeave } from "vue-router";
import axios from "axios";
import { IconVector, IconFileText, IconPointer, IconHandStop, IconPlus, IconHelp, IconBook, IconBug, IconBrandWechat, IconBriefcase, IconPhoto, IconSearch, IconChevronDown, IconChevronLeft, IconChevronRight, IconCheck, IconBolt, IconSparkles, IconLayoutSidebar } from "@tabler/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { QRCode } from "tdesign-vue-next";
import settings from "@/components/settings/index.vue";
import { useWorkspaceStore } from "@/stores/workspace";
import anonymousData from "@/lib/anonymousData";
import { useProviderBalances, providerBalances } from "@/lib/providerBilling";
import logoUrl from "@toonflow/assets/logo.svg";
import canvasPanel from "./panels/canvas/canvasHost.vue";
import assetLibrary from "./panels/canvas/components/assetLibrary.vue";
import type { DocumentNodeOptions } from "./panels/canvas/canvasFiles";
import workspaceMenu from "./components/workspaceMenu.vue";
import floatingAgent from "./components/floatingAgent.vue";

const documentPanel = defineAsyncComponent(() => import("./panels/document/index.vue"));

const activePanel = ref<"canvas" | "document">("canvas");
const selectedTool = ref<"move" | "hand">("move");
onMounted(() => anonymousData.track("workspace.canvas"));
const workspaceStore = useWorkspaceStore();
useProviderBalances();
const panelOptions = [
  { label: "画布", value: "canvas", icon: IconVector },
  { label: "文档", value: "document", icon: IconFileText },
];
const agentVisible = ref(true);
const agentWidth = ref(0);
const sidebarVisible = ref(true);
const sidebarTab = ref<"assets" | "canvases">("assets");
const sidebarAssetsVisible = ref(true);
const settingsVisible = ref(false);
const helpVisible = ref(false);
const contactVisible = ref(false);
const contactType = ref<"community" | "business">("community");
const contacts = {
  community: {
    title: "加入交流群",
    url: "https://work.weixin.qq.com/u/vc36adcc89845edcbe?v=5.0.3.63936&bb=85b8d228e8",
    tip: "Toonflow 是为爱发电的开源项目。欢迎文明交流、友善反馈；回复可能需要一些时间，请避免责问或命令式沟通，感谢你的理解与尊重。",
  },
  business: {
    title: "商务合作",
    url: "https://work.weixin.qq.com/u/vc0f54596c5837d05a?v=5.0.8.70675",
    tip: "此联系方式仅用于商务合作接洽，不提供问题答疑。使用问题欢迎在交流群交流，需求与 BUG 可通过反馈表提交。感谢理解。",
  },
};
const contactInfo = computed(() => contacts[contactType.value]);
const balanceLabel = computed(() => {
  const balance = providerBalances.value.find(item => item.balance)?.balance;
  return balance ? `${balance.balance.toFixed(2)} ${balance.unit}` : "未配置";
});

function showContact(type: "community" | "business") {
  contactType.value = type;
  helpVisible.value = false;
  contactVisible.value = true;
}
const canvasPanelRef = ref<InstanceType<typeof canvasPanel>>();
const canvasItems = computed(() => canvasPanelRef.value?.canvases ?? []);
const currentFileName = computed(() => activePanel.value === "canvas" ? canvasPanelRef.value?.canvasId || "画布" : "文档工作区");
function openAssets() {
  sidebarVisible.value = true;
  sidebarTab.value = "assets";
  void switchPanel("canvas");
}
async function openDocumentSearch() {
  if (await switchPanel("document")) {
    await nextTick();
    documentPanelRef.value?.openQuickOpen();
  }
}
const documentPanelCache = shallowRef<{ directory: string; instance: InstanceType<typeof documentPanel> }>();
const documentPanelRef = computed(() => documentPanelCache.value?.directory === workspaceStore.project?.directory ? documentPanelCache.value?.instance : undefined);
watch(() => workspaceStore.project?.directory, () => { documentPanelCache.value = undefined; }, { flush: "sync" });
function setDocumentPanel(value: Element | ComponentPublicInstance | null) {
  const directory = workspaceStore.project?.directory;
  // KeepAlive 失活会清空模板 ref，保留同一工作区实例以协调后台文档保存和文件操作。
  if (value && directory) documentPanelCache.value = { directory, instance: value as InstanceType<typeof documentPanel> };
}
provide("canvas", () => canvasPanelRef.value?.getCanvasContext());
provide("mentionCanvas", () => canvasPanelRef.value?.mentionSource);
provide("activateCanvasPanel", () => switchPanel("canvas"));
provide("performWorkspaceFileAction", (directory: string, action: "copy" | "rename" | "move" | "delete", path: string, target?: string, entryType: "canvas" | "directory" = "canvas") =>
  documentPanelRef.value ? documentPanelRef.value.performFileAction(directory, action, path, target, entryType) : performFileAction(directory, action, path, target));

async function flushSave() {
  await documentPanelRef.value?.flushSave();
  await canvasPanelRef.value?.flushSave();
}

onBeforeRouteLeave(async () => {
  if (canvasPanelRef.value?.saveBusy) {
    ElMessage.warning("画布操作尚未完成，请稍后退出");
    return false;
  }
  try {
    await flushSave();
    return true;
  } catch (error) {
    const message = axios.isAxiosError<{ message?: string }>(error)
      ? error.response?.data?.message || error.message
      : error instanceof Error
      ? error.message
      : "项目保存失败";
    const leave = await ElMessageBox.confirm(`无法保存项目：${message}。文件或目录可能已被移动或删除。仍然退出将丢弃尚未保存的修改。`, "项目未保存", {
      type: "warning",
      confirmButtonText: "仍然退出",
      cancelButtonText: "留在项目",
      closeOnClickModal: false,
    }).then(
      () => true,
      () => false
    );
    if (leave) {
      documentPanelRef.value?.cancelSave();
      canvasPanelRef.value?.cancelSave();
    }
    return leave;
  }
});

async function switchPanel(value: string | number | boolean) {
  if (value !== "canvas" && value !== "document") return false;
  try {
    if (activePanel.value === "document") await documentPanelRef.value?.flushSave();
    const changed = activePanel.value !== value;
    activePanel.value = value;
    await nextTick();
    if (changed) anonymousData.track(value === "canvas" ? "workspace.canvas" : "workspace.document");
    return true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "文本保存失败");
    return false;
  }
}

function readDocumentNode(directory: string, canvasPath: string, nodeId: string) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.readDocumentNode(directory, canvasPath, nodeId);
}

function readDocumentNodes(directory: string, options?: DocumentNodeOptions) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.readDocumentNodes(directory, options);
}

async function focusDocumentNode(directory: string, canvasPath: string, nodeId: string) {
  if (!directory || workspaceStore.project?.directory !== directory) throw new Error("工作目录已切换，请重新选择节点");
  if (!await switchPanel("canvas")) throw new Error("画布面板无法打开");
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  await canvasPanelRef.value.focusDocumentNode(directory, canvasPath, nodeId);
}

function mountDocumentNode(directory: string, canvasPath: string, nodeId: string, target: HTMLElement) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.mountDocumentNode(directory, canvasPath, nodeId, target);
}

function resolveDocumentNodeFile(directory: string, path: string) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.resolveDocumentNodeFile(directory, path);
}

function observeDocumentNode(directory: string, canvasPath: string, nodeId: string, onState: (state: { dirty: boolean; error: string; deleted: boolean }) => void) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.observeDocumentNode(directory, canvasPath, nodeId, onState);
}

async function flushDocumentNodes() {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  await canvasPanelRef.value.flushSave();
}

function performFileAction(directory: string, action: "copy" | "rename" | "move" | "delete", path: string, target?: string, nodeId?: string) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.performFileAction(directory, action, path, target, nodeId);
}

function saveDocumentNode(directory: string, canvasPath: string, nodeId: string, handleId: string, text: string, expectedText?: string) {
  if (!canvasPanelRef.value) throw new Error("画布尚未就绪");
  return canvasPanelRef.value.saveDocumentNode(directory, canvasPath, nodeId, handleId, text, expectedText);
}
</script>

<style scoped lang="scss">
.workspacePage {
  display: flex;
  width: 100%;
  height: 100dvh;
  overflow: hidden;
  background: var(--tf-bg);
  color: var(--tf-text);

  .workspaceRail {
    position: relative;
    z-index: 60;
    display: flex;
    flex: 0 0 64px;
    flex-direction: column;
    align-items: center;
    padding: 14px 0 16px;
    border-right: 1px solid var(--tf-line-soft);
    background: var(--tf-chrome);
  }
  .railLogo {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    margin-bottom: 22px;
    border-radius: 10px;
    background: linear-gradient(135deg, #2b3f6e, #16213c);
    img { width: 21px; height: 21px; filter: invert(1); }
  }
  .railItem {
    position: relative;
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    margin-bottom: 6px;
    padding: 0;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: var(--tf-text-3);
    cursor: pointer;
    transition: background 150ms ease, color 150ms ease;
    &:hover { background: var(--tf-panel-2); color: var(--tf-text); }
    &.active { background: var(--tf-accent-soft); color: var(--tf-accent); }
    &.active::before {
      content: "";
      position: absolute;
      top: 10px;
      bottom: 10px;
      left: -12px;
      width: 3px;
      border-radius: 2px;
      background: var(--tf-accent);
    }
    &:focus-visible { outline: 2px solid var(--tf-accent); outline-offset: 2px; }
  }
  .railDivider { width: 24px; height: 1px; margin: 12px 0; background: var(--tf-line); }
  .workspaceMenu { margin-top: auto; }

  .workspaceMain {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
  }
  .workspaceTopbar {
    position: relative;
    z-index: 55;
    display: flex;
    flex: 0 0 56px;
    align-items: center;
    gap: 16px;
    padding: 0 18px;
    border-bottom: 1px solid var(--tf-line-soft);
    background: var(--tf-chrome);
  }
  .crumbs {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
    font-size: 13px;
    .projectName { overflow: hidden; max-width: 180px; color: var(--tf-text-2); text-overflow: ellipsis; white-space: nowrap; }
    .separator { color: var(--tf-text-3); }
    .fileName { display: flex; align-items: center; gap: 6px; font-weight: 500; white-space: nowrap; }
  }
  .panelSwitcher {
    --el-border-radius-base: 999px;
    --el-segmented-bg-color: var(--tf-panel-2);
    --el-segmented-item-selected-color: var(--tf-text);
    --el-segmented-item-selected-bg-color: var(--tf-seg-active);
    position: absolute;
    top: 50%;
    left: 50%;
    min-height: 32px;
    padding: 3px;
    border: 1px solid var(--tf-line);
    font-size: 12px;
    transform: translate(-50%, -50%);
    .panelOption { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 0 10px; line-height: 24px; }
  }
  .topbarActions { display: flex; align-items: center; gap: 14px; margin-left: auto; }
  .saveState { display: flex; align-items: center; gap: 5px; color: var(--tf-text-3); font-size: 12px; white-space: nowrap; }
  .creditChip {
    display: flex;
    align-items: center;
    gap: 6px;
    height: 30px;
    margin: 0;
    padding: 0 11px;
    border: 1px solid var(--tf-line);
    border-radius: 999px;
    background: var(--tf-panel);
    color: var(--tf-text-2);
    font-family: inherit;
    font-size: 12px;
    white-space: nowrap;
    cursor: pointer;
    strong { color: var(--tf-text); font-weight: 500; font-variant-numeric: tabular-nums; }
    svg { color: var(--tf-warning); }
  }

  .workspaceBody { display: flex; flex: 1; min-height: 0; }
  .workspaceSidebar {
    position: relative;
    z-index: 20;
    display: flex;
    flex: 0 0 250px;
    flex-direction: column;
    min-height: 0;
    border-right: 1px solid var(--tf-line-soft);
    background: var(--tf-panel);
  }
  .sidebarTabs {
    display: flex;
    flex: 0 0 47px;
    gap: 20px;
    align-items: flex-end;
    padding: 0 16px;
    button {
      height: 38px;
      padding: 0 1px;
      border: 0;
      border-bottom: 2px solid transparent;
      background: transparent;
      color: var(--tf-text-3);
      font-size: 13px;
      cursor: pointer;
      &.active { border-bottom-color: var(--tf-accent); color: var(--tf-text); }
    }
  }
  .addNodeWide {
    flex: 0 0 38px;
    margin: 10px 14px 12px;
    border-color: transparent;
    border-radius: 9px;
    background: var(--tf-cta);
    color: var(--tf-cta-text);
    font-weight: 500;
    &:hover { opacity: .86; }
    &.is-disabled { opacity: .45; }
  }
  .sidebarContent { flex: 1; min-height: 0; padding: 0 12px 10px; overflow: hidden; }
  .sidebarCollapse {
    display: flex;
    flex: 0 0 40px;
    align-items: center;
    justify-content: center;
    gap: 6px;
    border: 0;
    border-top: 1px solid var(--tf-line-soft);
    background: transparent;
    color: var(--tf-text-3);
    font-size: 12px;
    cursor: pointer;
    &:hover { background: var(--tf-panel-2); color: var(--tf-text-2); }
  }
  .canvasList { height: 100%; overflow-y: auto; }
  .sidebarSectionTitle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 5px 4px 10px;
    color: var(--tf-text-2);
    font-size: 12px;
    small { color: var(--tf-text-3); }
  }
  .canvasListItem {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding: 9px 10px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--tf-text-2);
    font-size: 12.5px;
    text-align: left;
    cursor: pointer;
    span { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    svg { flex-shrink: 0; color: var(--tf-text-3); }
    &:hover { background: var(--tf-panel-2); color: var(--tf-text); }
    &.active { background: var(--tf-accent-soft); color: var(--tf-accent); }
    &.active svg { color: var(--tf-accent); }
  }
  .sidebarEmpty { padding: 24px 8px; color: var(--tf-text-3); font-size: 12px; text-align: center; }

  .workspaceStage { position: relative; flex: 1; min-width: 0; min-height: 0; overflow: hidden; }
  .canvasPanel,
  .documentPanelHost {
    position: absolute;
    inset: 0;
    width: calc(100% - var(--agentWidth, 0px));
    container: canvasWorkspace / inline-size;
  }
  .canvasPanel.backgroundPanel { opacity: 0; visibility: hidden; pointer-events: none; }
  .workspaceTools {
    position: absolute;
    inset: 0;
    width: calc(100% - var(--agentWidth, 0px));
    container: workspaceTools / inline-size;
    pointer-events: none;
  }
  .bottomTools {
    position: absolute;
    bottom: 22px;
    left: 50%;
    z-index: 5;
    display: flex;
    align-items: center;
    gap: 8px;
    transform: translateX(-50%);
    pointer-events: auto;
  }
  .toolMenu {
    flex-shrink: 0;
    border: 1px solid var(--tf-line);
    border-radius: 12px;
    background: var(--tf-pill-bg);
    box-shadow: var(--tf-shadow-float);
    backdrop-filter: blur(12px);
    .toolMenuContent { display: flex; align-items: center; gap: 3px; }
    .toolButton {
      width: 32px;
      height: 32px;
      margin: 0;
      padding: 0;
      border-radius: 8px;
      color: var(--tf-text-2);
      &.addNodeButton { margin-right: 4px; border-color: transparent; background: var(--tf-cta); color: var(--tf-cta-text); }
      &.addNodeButton:hover { opacity: .85; }
      &[aria-pressed="true"] { background: var(--tf-accent-soft); color: var(--tf-accent); }
    }
    .toolTrigger { display: inline-flex; }
    .toolSeparator { width: 1px; height: 18px; margin: 0 4px; background: var(--tf-line); }
  }
  .agentButton {
    position: absolute;
    left: calc(100% + 10px);
    display: grid;
    place-items: center;
    width: 40px;
    height: 40px;
    margin: 0;
    padding: 0;
    border: 1px solid var(--tf-line-strong);
    border-radius: 50%;
    background: linear-gradient(135deg, #2b3f6e, #4a3267);
    box-shadow: var(--tf-shadow-float);
    color: #e5ecff;
    &.standalone { position: relative; left: auto; }
    &:hover { transform: translateY(-1px); }
  }
  .sidebarReopen {
    position: fixed;
    bottom: 22px;
    left: 78px;
    z-index: 30;
    width: 36px;
    height: 36px;
    margin: 0;
    padding: 0;
    border-color: var(--tf-line);
    border-radius: 10px;
    background: var(--tf-pill-bg);
    color: var(--tf-text-2);
    box-shadow: var(--tf-shadow-float);
  }

  @media (max-width: 1100px) {
    .workspaceSidebar { flex-basis: 220px; }
    .saveState { display: none; }
    .crumbs .projectName { max-width: 110px; }
  }
  @media (max-width: 900px) {
    .workspaceSidebar { flex-basis: 208px; }
    .creditChip span { display: none; }
    .crumbs .projectName { display: none; }
    .crumbs .separator { display: none; }
  }
}

.helpMenu {
  display: flex;
  flex-direction: column;
  gap: 4px;
  .helpAction { justify-content: flex-start; margin-left: 0; }
}
.contactContent {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  .contactTip { margin: 0; color: var(--tf-text-2); font-size: 12px; line-height: 1.7; }
}
</style>
