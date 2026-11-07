import { createApp, h } from "vue";
import {
  ElButton, ElCheckbox, ElCheckboxGroup, ElInput, ElInputNumber, ElOption,
  ElRadio, ElRadioButton, ElRadioGroup, ElResult, ElSelect, ElSwitch,
} from "element-plus";
import { createPinia } from "pinia";
import { createPersistedState } from "pinia-plugin-persistedstate";
import appRoot from "./app.vue";
import "@/assets/main.scss";
import "element-plus/es/components/message-box/style/css";
import "element-plus/es/components/button/style/css";
import "element-plus/es/components/result/style/css";

import router from "@/router";
import { registerDownloads } from "@/lib/saveFile";
import { registerAnonymousData } from "@/lib/anonymousData";
import { loadSettings, settingsStorage } from "@/stores/settings";

const app = createApp(appRoot);
let isMounted = false;

// form-create 内置渲染器通过 resolveComponent 解析 Element Plus 组件，必须全局注册后才能渲染为可交互控件。
for (const [name, component] of Object.entries({
  ElInput, ElInputNumber, ElSelect, ElOption, ElSwitch,
  ElRadio, ElRadioGroup, ElRadioButton, ElCheckbox, ElCheckboxGroup,
})) app.component(name, component);

loadSettings().then(async () => {
  app.use(createPinia().use(createPersistedState({ storage: settingsStorage })));
  app.use(router);
  await router.isReady();
  app.onUnmount(registerAnonymousData());
  app.mount("#app");
  isMounted = true;
  app.onUnmount(registerDownloads());
}).catch(async (error) => {
  console.error("页面初始化失败：", error);
  if (isMounted) app.unmount();
  createApp({
    render: () => h(ElResult, {
      icon: "error",
      title: "启动失败",
      subTitle: error instanceof Error ? error.message : "无法加载应用，请重试。",
    }, {
      extra: () => [h(ElButton, { type: "primary", onClick: () => window.location.reload() }, () => "重试")],
    }),
  }).mount("#app");
});
