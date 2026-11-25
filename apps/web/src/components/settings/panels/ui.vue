<template>
  <div class="ui">
    <p class="intro">让创作空间更合你的习惯，修改会自动保存。</p>

    <section class="settingSection" aria-labelledby="themeTitle">
      <h3 id="themeTitle">
        <icon-sun-moon :size="18" />
        外观模式
      </h3>
      <el-radio-group
        class="themeOptions"
        :modelValue="uiSettings.theme"
        aria-label="外观模式"
        @click="captureThemeClickPoint"
        @change="(value) => changeTheme(String(value))">
        <el-radio v-for="item in themes" :key="item.value" :value="item.value" border>
          <span class="themeLabel">
            <span class="themePreview" :class="item.value" aria-hidden="true"><i /><b /></span>
            <span class="themeText"><strong><component :is="item.icon" :size="15" />{{ item.label }}</strong><small>{{ item.description }}</small></span>
          </span>
        </el-radio>
      </el-radio-group>
    </section>

    <section class="settingSection" aria-labelledby="colorTitle">
      <div class="settingHeader">
        <h3 id="colorTitle">
          <icon-palette :size="18" />
          主题颜色
        </h3>
        <span class="settingValue">{{ uiSettings.primaryColor.toUpperCase() }}</span>
      </div>
      <p class="description">用于按钮、选中状态与创作背景。</p>
      <div class="colorOptions">
        <el-button
          v-for="color in colors"
          :key="color.value"
          class="colorSwatch"
          circle
          :style="{ '--swatchColor': color.value }"
          :aria-label="color.label"
          :aria-pressed="uiSettings.primaryColor.toLowerCase() === color.value"
          :title="color.label"
          @click="updateUiSettings({ primaryColor: color.value })">
          <icon-check v-if="uiSettings.primaryColor.toLowerCase() === color.value" :size="18" />
        </el-button>
        <el-color-picker
          :modelValue="uiSettings.primaryColor"
          colorFormat="hex"
          aria-label="自定义主题颜色"
          @change="changeColor" />
        <span class="description">自定义</span>
      </div>
    </section>

    <section class="settingSection" aria-labelledby="fontTitle">
      <div class="settingHeader">
        <h3 id="fontTitle">
          <icon-text-size :size="18" />
          字体大小
        </h3>
        <span class="settingValue">{{ fontScale }}%</span>
      </div>
      <p class="description">统一调整界面、聊天和节点中的文字大小。</p>
      <el-slider
        v-model="fontScale"
        class="settingSlider"
        :min="85"
        :max="125"
        :step="5"
        :marks="{ 85: '较小', 100: '默认', 125: '较大' }"
        aria-label="字体大小"
        @change="(value) => typeof value === 'number' && updateUiSettings({ fontScale: value })" />
    </section>

    <section class="settingSection" aria-labelledby="radiusTitle">
      <div class="settingHeader">
        <h3 id="radiusTitle">
          <icon-border-radius :size="18" />
          界面圆角
        </h3>
        <span class="settingValue">{{ radius }} px</span>
      </div>
      <el-slider
        v-model="radius"
        class="settingSlider"
        :min="0"
        :max="16"
        :step="2"
        :marks="{ 0: '直角', 8: '默认', 16: '圆润' }"
        aria-label="界面圆角"
        @change="(value) => typeof value === 'number' && updateUiSettings({ radius: value })" />
    </section>

    <el-card class="appearancePreview" shadow="never">
      <div class="previewIcon"><icon-sparkles :size="20" /></div>
      <div class="previewText">
        <strong>Toonflow 每一个灵感，都值得被看见</strong>
        <p>这是当前颜色、字体与圆角的实际效果。</p>
      </div>
      <el-tag type="primary" effect="light">预览</el-tag>
    </el-card>

    <div class="settingsFooter">
      <el-button :icon="IconRestore" @click="updateUiSettings({ ...defaultUiSettings, startupAnimation: uiSettings.startupAnimation })">恢复界面默认设置</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, ref, watchEffect } from "vue";
import {
  IconSun,
  IconMoon,
  IconDeviceDesktop,
  IconSunMoon,
  IconPalette,
  IconSparkles,
  IconCheck,
  IconRestore,
} from "@tabler/icons-vue";
import { defaultUiSettings, uiSettings, updateUiSettings } from "@/stores/settings";

const fontScale = ref(uiSettings.value.fontScale);
const radius = ref(uiSettings.value.radius);
watchEffect(() => {
  fontScale.value = uiSettings.value.fontScale;
  radius.value = uiSettings.value.radius;
});

const themes = [
  { value: "light", label: "浅色", description: "明亮清晰", icon: IconSun },
  { value: "dark", label: "深色", description: "沉浸创作", icon: IconMoon },
  { value: "system", label: "跟随系统", description: "自动切换", icon: IconDeviceDesktop },
];
const colors = [
  { value: "#6e9eff", label: "天空蓝" },
  { value: "#6366f1", label: "鸢尾紫" },
  { value: "#a855f7", label: "薰衣紫" },
  { value: "#e34b83", label: "蔷薇粉" },
  { value: "#e89524", label: "琥珀橙" },
  { value: "#18a17c", label: "松石绿" },
];
function changeColor(value: string | null) {
  if (value) updateUiSettings({ primaryColor: value });
}

// ACT: 圆心固定用视口中心，实际点击坐标由 captureThemeClickPoint 在 change 前写入。
let themeClickPoint = { x: innerWidth / 2, y: innerHeight / 2 };
function captureThemeClickPoint(event: MouseEvent) {
  themeClickPoint = { x: event.clientX, y: event.clientY };
}
function changeTheme(value: string) {
  const { x, y } = themeClickPoint;
  const root = document.documentElement;
  root.style.setProperty("--themeX", `${x}px`);
  root.style.setProperty("--themeY", `${y}px`);
  root.style.setProperty("--themeR", `${Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y))}px`);
  if (!document.startViewTransition) {
    updateUiSettings({ theme: value });
    return;
  }
  document.startViewTransition(async () => {
    updateUiSettings({ theme: value });
    await nextTick();
  });
}
</script>

<style lang="scss" scoped>
.ui {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 0 4px 8px;

  .intro,
  .description {
    margin: 0;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    line-height: 1.6;
  }

  .settingSection {
    min-width: 0;

    h3 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 12px;
      color: var(--el-text-color-primary);
      font-size: 14px;
      font-weight: 600;
    }

    .settingHeader {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 12px;

      h3 {
        margin-bottom: 6px;
      }
      .settingValue {
        color: var(--el-text-color-secondary);
        font-size: 12px;
        font-variant-numeric: tabular-nums;
      }
    }

    .languageCards { margin-top: 14px; }

    .themeOptions {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
      width: 100%;

      :deep(.el-radio) {
        height: auto;
        margin: 0;
        padding: 12px;
        align-items: flex-start;
        border: 1px solid var(--tf-line);
        border-radius: 12px;
        background: var(--tf-panel-2);

        &.is-checked {
          border-color: var(--tf-accent);
          background: var(--tf-panel-2);
          box-shadow: 0 0 0 3px var(--tf-accent-soft);
        }
        .el-radio__input { display: none; }
        .el-radio__label { min-width: 0; width: 100%; padding: 0; }
      }

      .themeLabel {
        display: flex;
        flex-direction: column;
        gap: 11px;
        width: 100%;
        white-space: normal;

        .themePreview {
          position: relative;
          display: block;
          height: 62px;
          overflow: hidden;
          border: 1px solid var(--tf-line-soft);
          border-radius: 8px;
          background: #f4f5f8;
          &.dark { background: #101117; }
          &.system { background: linear-gradient(105deg, #f4f5f8 50%, #101117 50%); }
          i { position: absolute; top: 10px; left: 10px; width: 42%; height: 7px; border-radius: 3px; background: rgba(140,150,170,.5); }
          b { position: absolute; top: 25px; left: 10px; width: 62%; height: 27px; border: 1px solid rgba(140,150,170,.3); border-radius: 5px; background: rgba(140,150,170,.25); }
        }
        .themeText { display: flex; flex-direction: column; gap: 4px; }
        strong { display: flex; align-items: center; gap: 6px; font-size: 12.5px; font-weight: 500; }
        small { color: var(--tf-text-3); font-size: 11px; }
      }
    }

    .colorOptions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
      margin-top: 14px;

      .colorSwatch {
        width: 30px;
        height: 30px;
        margin: 0;
        border: 0;
        color: #fff;
        background: var(--swatchColor);

        &[aria-pressed="true"] {
          outline: 2px solid var(--swatchColor);
          outline-offset: 3px;
        }
        &:focus-visible {
          outline: 2px solid var(--el-text-color-primary);
          outline-offset: 3px;
        }
      }
    }

    .settingSlider {
      width: calc(100% - 48px);
      margin: 10px 24px 20px;

      :deep(.el-slider__stop) {
        background-color: var(--el-text-color-secondary);
        box-shadow: 0 0 0 1px var(--el-bg-color-overlay);
      }
    }
  }

  .appearancePreview {
    background: var(--el-fill-color-extra-light);

    :deep(.el-card__body) {
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 18px;
    }
    .previewIcon {
      display: grid;
      place-items: center;
      flex-shrink: 0;
      width: 40px;
      height: 40px;
      border-radius: var(--el-border-radius-base);
      background: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
    }
    .previewText {
      flex: 1;
      min-width: 0;
      strong {
        font-size: 15px;
        font-weight: 500;
        color: var(--el-text-color-primary);
      }
      p {
        margin: 6px 0 0;
        color: var(--el-text-color-secondary);
        font-size: 12px;
        line-height: 1.6;
      }
    }
  }

  .settingsFooter {
    display: flex;
    justify-content: flex-end;
  }

  @media (max-width: 700px) {
    .settingSection .themeOptions {
      grid-template-columns: 1fr;
      .themeLabel {
        flex-direction: column;
      }
    }
    .appearancePreview {
      :deep(.el-card__body) {
        flex-wrap: wrap;
        padding: 12px;
      }
    }
  }
}
</style>
