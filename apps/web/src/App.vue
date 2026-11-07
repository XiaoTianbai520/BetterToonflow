<template>
  <el-config-provider :locale="zhCn">
    <config-provider :globalConfig="tdesignLocale">
      <router-view v-slot="{ Component: currentComponent }">
        <transition name="el-fade-in">
          <component :is="currentComponent" />
        </transition>
      </router-view>
      <ffmpegRequired />
    </config-provider>
  </el-config-provider>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watchEffect } from "vue";
import { useZIndex } from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import { ConfigProvider, type GlobalConfigProvider } from "tdesign-vue-next";
import tdesignZhCn from "tdesign-vue-next/es/locale/zh_CN";
import { uiSettings } from "@/stores/settings";
import ffmpegRequired from "@/components/settings/ffmpegRequired.vue";
import "element-plus/theme-chalk/dark/css-vars.css";

// ACT: 首发版本只服务简体中文，Element Plus 与 TDesign 直接使用官方中文语言包。
const chatLocale = {
  placeholder: "请输入消息...", stopBtnText: "中止", refreshTipText: "重新生成",
  copyTipText: "复制", likeTipText: "点赞", dislikeTipText: "点踩",
  copyCodeBtnText: "复制代码", copyCodeSuccessText: "已复制", clearHistoryBtnText: "清空历史记录",
  copyTextSuccess: "已成功复制到剪贴板", copyTextFail: "复制到剪贴板失败",
  confirmClearHistory: "确定要清空所有的消息吗？", loadingText: "思考中...", loadingEndText: "已深度思考",
  uploadImageText: "上传图片", uploadAttachmentText: "上传附件", shareTipText: "分享",
};
// ACT: TDesign 自带语言包声明为 readonly，而 ConfigProvider 的只读使用接口声明为可写。
const tdesignLocale = { ...tdesignZhCn, chat: chatLocale } as unknown as GlobalConfigProvider;

function preventPageZoom(event: WheelEvent) {
  if (event.ctrlKey || event.metaKey) event.preventDefault();
}
function preventPageZoomShortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && !event.altKey && ["+", "=", "-", "0"].includes(event.key)) event.preventDefault();
}
// 仅取消浏览器默认缩放，继续传递事件供 Vue Flow 缩放画布。
window.addEventListener("wheel", preventPageZoom, { capture: true, passive: false });
// 画布在捕获阶段先处理自己的快捷键，再在冒泡阶段取消浏览器缩放。
window.addEventListener("keydown", preventPageZoomShortcut);
onBeforeUnmount(() => {
  window.removeEventListener("wheel", preventPageZoom, true);
  window.removeEventListener("keydown", preventPageZoomShortcut);
});

const { currentZIndex } = useZIndex();
watchEffect(() => document.documentElement.style.setProperty("--markdown-tooltip-z-index", String(currentZIndex.value + 1)));

const systemTheme = window.matchMedia("(prefers-color-scheme: dark)");
const systemDark = ref(systemTheme.matches);
function updateSystemTheme(event: MediaQueryListEvent) {
  systemDark.value = event.matches;
}
systemTheme.addEventListener("change", updateSystemTheme);
onBeforeUnmount(() => systemTheme.removeEventListener("change", updateSystemTheme));

watchEffect(() => {
  const { theme, primaryColor, fontScale, radius } = uiSettings.value;
  const dark = theme === "system" ? systemDark.value : theme === "dark";
  const root = document.documentElement;
  root.classList.toggle("dark", dark);
  root.setAttribute("theme-mode", dark ? "dark" : "light");
  root.style.colorScheme = dark ? "dark" : "light";
  root.style.fontSize = `${(16 * fontScale) / 100}px`;
  root.style.setProperty("--ui-radius", `${radius}px`);
  root.style.setProperty("--el-color-primary", primaryColor);
  for (let level = 1; level <= 9; level++) {
    root.style.setProperty(
      `--el-color-primary-light-${level}`,
      `color-mix(in srgb, ${primaryColor} ${100 - level * 10}%, ${dark ? "#141414" : "#fff"})`
    );
  }
  root.style.setProperty("--el-color-primary-dark-2", `color-mix(in srgb, ${primaryColor} 80%, ${dark ? "#fff" : "#000"})`);
});
</script>

<style lang="scss">
html {
  background-color: var(--el-bg-color);
  color: var(--el-text-color-primary);
}

// 主题切换圆形扩散动效，坐标由触发点写入 --themeX/--themeY/--themeR。
::view-transition-old(root),
::view-transition-new(root) {
  animation: none;
  mix-blend-mode: normal;
}

::view-transition-new(root) {
  animation: themeReveal 0.4s ease-in forwards;
}

@keyframes themeReveal {
  from {
    clip-path: circle(0 at var(--themeX) var(--themeY));
  }
  to {
    clip-path: circle(var(--themeR) at var(--themeX) var(--themeY));
  }
}

.vue-flow {
  --vf-node-bg: var(--el-bg-color-overlay);
  --vf-node-text: var(--el-text-color-primary);
  --vf-node-color: var(--el-border-color-darker);
  --vf-connection-path: var(--el-text-color-secondary);
  --vf-handle: var(--el-text-color-secondary);

  .vue-flow__minimap {
    background-color: var(--el-bg-color);
  }
}
</style>
