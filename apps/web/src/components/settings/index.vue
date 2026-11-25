<template>
  <el-dialog v-model="visible" class="settingsDialog" width="min(1080px, calc(100vw - 32px))" alignCenter appendToBody>
    <template #header>
      <div class="dialogTitle"><h2>设置</h2><span><icon-check :size="13" />更改会自动保存</span></div>
    </template>
    <div class="settings">
      <aside class="sidebar" aria-label="设置分类">
        <template v-for="item in settingsPanels" :key="item.id">
          <button
            class="settingsItem"
            type="button"
            :aria-label="item.label"
            :aria-pressed="activePanel.id === item.id"
            @click="activePanel = item">
            <span class="panelIcon">
              <component :is="item.icon" :size="18" aria-hidden="true" />
            </span>
            <span>{{ item.label }}</span>
          </button>
        </template>
      </aside>
      <section class="content" :aria-label="activePanel.label" tabindex="0">
        <h2 class="panelTitle">{{ activePanel.label }}</h2>
        <div class="panelContent">
          <transition name="el-fade-in" mode="out-in">
            <keep-alive include="personalization">
              <component
                :is="activePanel.component"
                v-bind="
                  ['pluginMarket', 'languageModel', 'mediaModel', 'personalization'].includes(activePanel.id) ? { visible } : {}
                " />
            </keep-alive>
          </transition>
        </div>
      </section>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { defineAsyncComponent, shallowRef } from "vue";
import { IconPalette, IconSettings, IconPhotoVideo, IconBook, IconUserCog, IconSubtitlesAi, IconCheck } from "@tabler/icons-vue";

const settingsPanels = [
  { id: "ui", label: "界面设置", icon: IconPalette, component: defineAsyncComponent(() => import("./panels/ui.vue")) },
  { id: "general", label: "常规与快捷键", icon: IconSettings, component: defineAsyncComponent(() => import("./panels/general/index.vue")) },
  { id: "languageModel", label: "文本模型供应商", icon: IconSubtitlesAi, component: defineAsyncComponent(() => import("./panels/languageModel/index.vue")) },
  { id: "mediaModel", label: "媒体模型供应商", icon: IconPhotoVideo, component: defineAsyncComponent(() => import("./panels/mediaModel/index.vue")) },
  { id: "pluginMarket", label: "技能与 FFmpeg 管理", icon: IconBook, component: defineAsyncComponent(() => import("./panels/pluginMarket/index.vue")) },
  { id: "personalization", label: "个性化", icon: IconUserCog, component: defineAsyncComponent(() => import("./panels/personalization.vue")) },
];
const activePanel = shallowRef(settingsPanels[0]!);
const visible = defineModel<boolean>({ default: false });
</script>

<style lang="scss">
.el-dialog.settingsDialog {
  overflow: hidden;
  border: 1px solid var(--tf-line-strong);
  border-radius: 18px;
  background: var(--tf-panel);
  box-shadow: var(--tf-shadow-modal);

  .el-dialog__header {
    margin: 0;
    padding: 18px 24px;
    border-bottom: 1px solid var(--tf-line-soft);
  }
  .el-dialog__headerbtn {
    top: 14px;
    right: 16px;
    width: 32px;
    height: 32px;
    .el-dialog__close { color: var(--tf-text-2); }
  }
  .el-dialog__body { padding: 0; color: var(--tf-text); }
  .dialogTitle {
    display: flex;
    align-items: center;
    gap: 14px;
    h2 { margin: 0; color: var(--tf-text); font-size: 17px; font-weight: 600; }
    span { display: flex; align-items: center; gap: 5px; color: var(--tf-text-3); font-size: 12px; font-weight: 400; }
  }
}
</style>

<style lang="scss" scoped>
.settings {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  height: min(680px, calc(100dvh - 150px));
  overflow: hidden;

  .sidebar {
    min-height: 0;
    overflow-y: auto;
    padding: 14px 10px;
    border-right: 1px solid var(--tf-line-soft);
    background: var(--tf-panel-2);

    .settingsItem {
      display: flex;
      align-items: center;
      gap: 10px;
      width: 100%;
      padding: 10px 12px;
      margin-bottom: 4px;
      border: 0;
      border-radius: 9px;
      background: transparent;
      color: var(--tf-text-2);
      font: inherit;
      text-align: start;
      cursor: pointer;

      .panelIcon {
        display: inline-flex;
        flex-shrink: 0;
      }

      > span {
        min-width: 0;
        overflow-wrap: anywhere;
      }

      &:hover {
        background: var(--tf-panel-3);
      }

      &[aria-pressed="true"] {
        background: var(--tf-accent-soft);
        color: var(--tf-accent);
      }

      &:focus-visible {
        outline: 2px solid var(--el-color-primary);
      }
    }
  }

  .content {
    display: flex;
    flex-direction: column;
    min-height: 0;
    padding: 24px 30px 0;
    overflow: hidden;

    .panelTitle {
      flex-shrink: 0;
      margin: 0 0 20px;
      color: var(--tf-text);
      font-size: 16px;
      font-weight: 600;
    }

    .panelContent {
      flex: 1;
      min-height: 0;
      overflow-x: hidden;
      overflow-y: auto;
      overscroll-behavior: contain;
      padding-left: 5px;
      padding-right: 5px;
      padding-bottom: 50px;

      > :deep(.el-fade-in-enter-active),
      > :deep(.el-fade-in-leave-active) {
        transition-duration: 100ms;
      }
    }
  }

  @media (max-width: 700px) {
    grid-template-columns: 44px minmax(0, 1fr);

    .sidebar {
      .settingsItem {
        justify-content: center;
        padding: 12px;
        > span:last-child {
          display: none;
        }
      }
    }

    .content {
      padding: 0 8px 0 16px;
    }
  }
}
</style>
