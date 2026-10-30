<template>
  <div class="skillManagement">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="技能" name="skills">
        <div class="skillActions">
          <el-button :loading="installing" @click="fileInput?.click()">安装本地技能</el-button>
          <input ref="fileInput" type="file" accept=".zip,.md" hidden @change="installFile" />
          <el-button :loading="loading" @click="loadSkills">刷新</el-button>
        </div>
        <form class="remoteInstall" @submit.prevent="installRemote">
          <el-input v-model="remoteUrl" type="url" :maxlength="4096" placeholder="技能包 URL" aria-label="技能包 URL" :disabled="installing" />
          <el-button nativeType="submit" :loading="installing" :disabled="!remoteUrl.trim()">从 URL 安装</el-button>
        </form>
        <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" showIcon />
        <div v-loading="loading" class="skillList">
          <el-card v-for="skill in skills" :key="skill.name" shadow="never">
            <h3>{{ skill.displayName }}</h3>
            <p v-if="skill.description">{{ skill.description }}</p>
            <div class="skillControls">
              <el-switch :modelValue="skill.enabled !== false" :disabled="!!pending" :aria-label="`启用 ${skill.displayName}`" @change="setEnabled(skill, $event === true)" />
              <el-button :disabled="!!pending" @click="selectedSkill = skill">编辑</el-button>
              <el-button :disabled="!!pending" @click="exportSkill(skill)">导出</el-button>
              <el-popconfirm :title="`确定卸载“${skill.displayName}”及其附属文件？`" confirmButtonText="卸载" cancelButtonText="取消" @confirm="uninstallSkill(skill)">
                <template #reference><el-button type="danger" :disabled="!!pending">卸载</el-button></template>
              </el-popconfirm>
            </div>
          </el-card>
        </div>
        <skillEditorDialog v-if="selectedSkill" :key="selectedSkill.name" :skill="selectedSkill" @saved="loadSkills" @closed="selectedSkill = undefined" />
      </el-tab-pane>
      <el-tab-pane label="FFmpeg" name="ffmpeg"><ffmpeg v-if="activeTab === 'ffmpeg'" :visible="visible" /></el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import axios from "axios";
import { ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { installPluginFile } from "../../installPluginFile";
import skillEditorDialog from "./skillEditorDialog.vue";
import ffmpeg from "./ffmpeg.vue";
import type { Plugin } from "./types";

type SkillSummary = { name: string; displayName: string; description: string; version: string; author: string; github: string; enabled: boolean };
const { visible = true } = defineProps<{ visible?: boolean }>();
const activeTab = ref("skills");
const skills = ref<Plugin[]>([]);
const selectedSkill = ref<Plugin>();
const loading = ref(false);
const installing = ref(false);
const loadError = ref("");
const pending = ref("");
const remoteUrl = ref("");
const fileInput = ref<HTMLInputElement>();

function errorMessage(error: unknown) {
  return axios.isAxiosError<{ message?: string }>(error) ? error.response?.data?.message || error.message
    : error instanceof Error ? error.message : "技能操作失败，请重试";
}

async function loadSkills() {
  if (loading.value) return;
  loading.value = true;
  loadError.value = "";
  try {
    const { data } = await axios.get<{ code: number; data: SkillSummary[]; message?: string }>("/api/skills/get");
    if (data.code !== 200 || !Array.isArray(data.data)) throw new Error(data.message || "读取技能失败");
    skills.value = data.data.map(skill => ({ ...skill, type: "skill", key: skill.name }));
  } catch (error) { loadError.value = errorMessage(error); }
  finally { loading.value = false; }
}

async function installFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file || installing.value) return;
  installing.value = true;
  try {
    await installPluginFile("skill", file);
    await loadSkills();
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { installing.value = false; }
}

async function installRemote() {
  if (installing.value) return;
  try {
    const url = new URL(remoteUrl.value.trim());
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password) throw new Error("请输入有效的 HTTP(S) 技能包 URL");
    installing.value = true;
    const { data } = await axios.post("/api/skills/install", { url: url.href });
    if (data.code !== 200) throw new Error(data.message || "安装技能失败");
    window.dispatchEvent(new CustomEvent("toonflow:plugin-installed", { detail: { type: "skill", name: data.data.name } }));
    remoteUrl.value = "";
    await loadSkills();
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { installing.value = false; }
}

async function setEnabled(skill: Plugin, enabled: boolean) {
  if (pending.value) return;
  pending.value = skill.name;
  try {
    const { data } = await axios.put("/api/skills/setEnabled", { name: skill.name, enabled });
    if (data.code !== 200) throw new Error(data.message || "更新技能失败");
    skill.enabled = enabled;
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { pending.value = ""; }
}

async function uninstallSkill(skill: Plugin) {
  if (pending.value) return;
  pending.value = skill.name;
  try {
    const { data } = await axios.delete("/api/skills/uninstall", { data: { name: skill.name } });
    if (data.code !== 200) throw new Error(data.message || "卸载技能失败");
    skills.value = skills.value.filter(item => item.name !== skill.name);
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { pending.value = ""; }
}

async function exportSkill(skill: Plugin) {
  if (pending.value) return;
  pending.value = skill.name;
  try {
    const { data } = await axios.get<Blob>("/api/plugins/export", { params: { type: "skill", name: skill.name }, responseType: "blob" });
    const url = URL.createObjectURL(data);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${skill.name}.zip`;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  } catch (error) { ElMessage.error(errorMessage(error)); }
  finally { pending.value = ""; }
}

watch(() => visible, value => { if (value) void loadSkills(); }, { immediate: true });
</script>

<style lang="scss" scoped>
.skillManagement {
  .skillActions { display: flex; gap: 12px; margin-bottom: 16px; }
  .remoteInstall { display: flex; gap: 12px; margin-bottom: 16px; }
  .skillList {
    display: grid;
    gap: 16px;
    h3 { margin: 0; font-size: 14px; }
    p { color: var(--el-text-color-secondary); font-size: 13px; }
    .skillControls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  }
}
</style>
