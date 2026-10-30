<template>
  <main class="hello">
    <section class="setupPanel" aria-labelledby="setupTitle">
      <header class="setupHeader">
        <img :src="logoUrl" alt="Toonflow" width="36" height="36" />
        <h1 id="setupTitle">首次文本模型配置</h1>
      </header>
      <languageModel />
      <el-alert v-if="setupError" :title="setupError" type="error" :closable="false" showIcon>
        <el-button :loading="saving" @click="completeSetup">重试</el-button>
      </el-alert>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import logoUrl from "@toonflow/assets/logo.svg";
import languageModel from "@/components/settings/panels/languageModel/index.vue";
import { useHelloStore } from "@/stores/hello";

const hello = useHelloStore();
const router = useRouter();
const saving = ref(false);
const setupError = ref("");

async function completeSetup() {
  if (saving.value || !hello.hasConfiguredModel) return;
  saving.value = true;
  setupError.value = "";
  try {
    await hello.complete();
    await router.replace("/home");
  } catch (error) {
    setupError.value = error instanceof Error ? error.message : "保存引导状态失败，请重试";
  } finally {
    saving.value = false;
  }
}

watch(() => hello.hasConfiguredModel, configured => { if (configured) void completeSetup(); }, { immediate: true });
</script>

<style lang="scss" scoped>
.hello {
  min-height: 100dvh;
  padding: 48px 24px;
  box-sizing: border-box;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);

  .setupPanel {
    max-width: 900px;
    margin: 0 auto;

    .setupHeader {
      display: flex;
      align-items: center;
      gap: 16px;
      margin-bottom: 32px;

      h1 { margin: 0; font-size: 24px; font-weight: 600; }
    }
  }
}
</style>
