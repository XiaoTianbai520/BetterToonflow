<template>
  <el-container class="home">
    <el-header class="pageHeader">
      <div class="brand">
        <span class="brandMark"><el-image class="brandLogo" :src="logoUrl" fit="contain" alt="" /></span>
        <span>Toonflow</span>
      </div>
      <div class="headerActions">
        <el-button class="headerButton" round :icon="IconBrandGithub" tag="a" href="https://github.com/HBAI-Ltd/Toonflow-app" target="_blank" rel="noopener noreferrer">GitHub</el-button>
        <el-button class="headerButton" round :icon="IconSettings" aria-label="设置" @click="settingsVisible = true">设置</el-button>
        <span class="avatar" aria-hidden="true">T</span>
      </div>
    </el-header>
    <el-main class="pageContent">
      <section class="creationPanel" aria-label="创建项目">
        <div class="heroCopy">
          <span class="heroBadge"><icon-sparkles :size="14" />AI 短剧工作台</span>
          <h1>把故事，变成画面</h1>
          <p>从剧本到分镜，从角色到成片，一张画布完成。</p>
        </div>
        <el-card class="promptCard" :class="{ draggingAttachments }" shadow="never" :bodyStyle="{ padding: '18px' }" :footerStyle="{ padding: '0 18px 18px' }" @dragenter.capture="dragEnterAttachments" @dragover.capture="dragOverAttachments" @dragleave.capture="dragLeaveAttachments" @drop.capture="dropAttachments">
          <div v-if="draggingAttachments" class="attachmentDropOverlay" role="status" aria-live="polite">
            <icon-paperclip :size="20" aria-hidden="true" />
            <strong>松开添加附件</strong>
            <span>图片、视频、文档或代码文件</span>
          </div>
          <input ref="attachmentInput" type="file" :accept="attachmentAccept" multiple hidden :disabled="creating || opening" @change="selectAttachments" />
          <attachmentList v-if="promptAttachments.length" class="promptAttachments" :attachments="promptAttachments" removable restorable :disabled="creating || opening" @remove="promptAttachments.splice($event, 1)" @restore="restoreAttachment" />
          <el-input ref="promptInput" v-model="prompt" type="textarea" :rows="3" resize="none" :disabled="creating || opening" :placeholder="promptPlaceholder" aria-label="创作描述" @paste.capture="pasteAttachments" />
          <template #footer>
            <div class="composerFooter">
              <workspacePicker ref="promptWorkspacePicker" v-model="workspaceDirectory" :disabled="creating || opening" />
              <el-button class="attachmentButton" :icon="IconPaperclip" :disabled="creating || opening" aria-label="添加附件" title="添加附件" @click="attachmentInput?.click()" />
              <modelPopover v-model="selectedModel" v-model:reasoningEffort="reasoningEffort" class="modelSelect" :disabled="creating || opening" />
              <el-button class="sendButton" :icon="workspaceDirectory ? IconArrowUp : IconFolder" :loading="creating" :disabled="creating || opening" :aria-label="workspaceDirectory ? '开始创作' : '选择工作目录'" @click="workspaceDirectory ? createProject() : promptWorkspacePicker?.chooseDirectory()">
                {{ workspaceDirectory ? "开始创作" : "选择工作目录" }}
              </el-button>
            </div>
            <p v-if="!workspaceDirectory" class="workspaceHint" role="status">请先选择一个空文件夹作为工作目录，画布和素材会保存在这里。</p>
          </template>
        </el-card>
        <div class="templateGrid" aria-label="快捷创作模板">
          <button v-for="(item, index) in creationTemplates" :key="item.title" class="templateCard" :class="`tone-${index + 1}`" type="button" :disabled="creating || opening" @click="applyTemplate(item.prompt)">
            <span class="templateCover" aria-hidden="true"><component :is="item.icon" :size="34" stroke="1.25" /></span>
            <span class="templateInfo"><strong>{{ item.title }}</strong><span>{{ item.description }}</span></span>
          </button>
        </div>
      </section>
      <section class="projectList" aria-labelledby="projectListTitle">
        <div class="sectionHeader">
          <h2 id="projectListTitle">项目列表 <span>{{ sortedProjects.length }}</span></h2>
          <div class="projectTools">
            <el-button class="outlineButton" :icon="IconFolderOpen" :disabled="creating || opening" @click="openProject()">导入项目</el-button>
            <el-button class="outlineButton" :icon="IconFolderPlus" :disabled="creating || opening" @click="createProject(false)">添加项目</el-button>
            <el-button class="iconButton" :icon="sortDescending ? IconSortDescending : IconSortAscending" :aria-label="sortDescending ? '按时间降序' : '按时间升序'" @click="sortDescending = !sortDescending" />
            <el-radio-group v-model="viewMode" class="viewSwitch" aria-label="项目视图">
              <el-radio-button value="grid" aria-label="网格视图"><icon-layout-grid :size="15" /></el-radio-button>
              <el-radio-button value="list" aria-label="列表视图"><icon-list :size="15" /></el-radio-button>
            </el-radio-group>
          </div>
        </div>
        <div v-if="sortedProjects.length" class="projectItems" :class="{ listView: viewMode === 'list' }">
          <el-card v-for="(project, index) in sortedProjects" :key="project.directory" class="projectCard" shadow="never" :bodyStyle="{ padding: '0' }">
            <button class="projectEntry" type="button" :disabled="creating || opening" :aria-label="`打开项目 ${project.name}`" @click="openProject(project)">
              <span class="projectCover" :class="`tone-${index % 4 + 1}`" aria-hidden="true">
                <span class="coverGrid" />
                <icon-folder v-if="index % 3 === 0" :size="34" stroke="1.2" />
                <icon-movie v-else-if="index % 3 === 1" :size="34" stroke="1.2" />
                <icon-photo v-else :size="34" stroke="1.2" />
              </span>
              <span class="projectInfo">
                <span class="projectName" :title="project.name">{{ project.name }}<icon-dots :size="16" /></span>
                <span class="projectPath" :title="project.directory">{{ project.directory }}</span>
                <span class="projectTime">最近打开 {{ new Date(project.lastOpenedAt).toLocaleString("zh-CN", { hour12: false }) }}</span>
              </span>
            </button>
            <div class="projectActions">
              <el-button text :icon="IconEdit" :disabled="creating || opening" :aria-label="`重命名项目 ${project.name}`" title="重命名" @click="renameProject(project)" />
              <el-button text type="danger" :icon="IconFolderMinus" :disabled="creating || opening" :aria-label="`移除项目 ${project.name}`" title="从列表移除，不删除文件" @click="workspaceStore.removeProject(project.directory)" />
            </div>
          </el-card>
        </div>
        <div v-else class="emptyProjects">
          <icon-folder-plus :size="28" stroke="1.4" />
          <strong>从第一个项目开始</strong>
          <span>选择一个空目录，或在上方描述你的故事创意。</span>
        </div>
      </section>
      <footer class="homeFoot"><icon-lock :size="13" />画布与素材保存在本地工作目录 · 无需账号即可开始</footer>
    </el-main>
    <settings v-model="settingsVisible" />
    <workspacePicker ref="relocationPicker" hideTrigger />
  </el-container>
</template>

<script setup lang="ts">
import axios from "axios";
import { storeToRefs } from "pinia";
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox, type InputInstance } from "element-plus";
import { getReadableFileMimeType, readableFileAccept } from "../../../../agentRuntime/src/lib/documentFormats";
import {
  IconSettings, IconBrandGithub,
  IconArrowUp, IconLayoutGrid,
  IconList, IconSortDescending,
  IconSortAscending, IconFolder, IconEdit,
  IconFolderMinus, IconFolderPlus, IconFolderOpen, IconPaperclip,
  IconSparkles, IconMovie, IconPhoto, IconDots, IconLock, IconUsers, IconVideo,
} from "@tabler/icons-vue";
import modelPopover from "@/components/modelPopover.vue";
import attachmentList from "@/components/agent/attachmentList.vue";
import { createPastedTextFile, readTextAttachment } from "@/components/agent/textAttachments";
import type { AgentAttachment } from "@/components/agent/types";
import logoUrl from "@toonflow/assets/logo.svg";
import { useWorkspaceStore, type Project } from "@/stores/workspace";
import useWorkspaceFiles from "@/lib/workspaceFiles";
import settings from "@/components/settings/index.vue";
import workspacePicker from "./workspacePicker.vue";

const settingsVisible = ref(false);
const router = useRouter();
const creating = ref(false);
const opening = ref(false);
const promptWorkspacePicker = ref<InstanceType<typeof workspacePicker>>();
const relocationPicker = ref<InstanceType<typeof workspacePicker>>();
const prompt = ref("");
const promptInput = ref<InputInstance>();
const promptAttachments = ref<AgentAttachment[]>([]);
const attachmentInput = ref<HTMLInputElement>();
const attachmentAccept = `image/*,video/*,${readableFileAccept}`;
const draggingAttachments = ref(false);
let attachmentDragDepth = 0;
const workspaceStore = useWorkspaceStore();
const { project, projectList } = storeToRefs(workspaceStore);
const workspaceDirectory = ref(project.value?.directory ?? "");
const placeholderPhrases = [
  "描述你想创作的内容，让灵感从这里开始…",
  "把一个故事灵感，变成一段精彩的短片…",
  "为你的主角设计独特的外形和性格…",
  "创作一段雨夜街头的电影感镜头…",
  "把这段文字拆解成连贯的分镜画面…",
  "为一场奇幻冒险生成场景和角色…",
  "为你的画面配上一段合适的音乐…",
  "写一段温暖的旁白，讲述这个故事…",
  "设计一支富有想象力的产品宣传片…",
  "从一句话开始，搭建你的创作工作流…",
];
const promptPlaceholder = ref(placeholderPhrases[0]!);

onMounted(() => {
  window.addEventListener("dragend", clearAttachmentDrag, true);
  window.addEventListener("drop", clearAttachmentDrag, true);
  window.addEventListener("blur", clearAttachmentDrag);
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  let phraseIndex = 0;
  watch(() => !!prompt.value, (hasInput, _previous, onCleanup) => {
    if (reduceMotion) {
      promptPlaceholder.value = placeholderPhrases[0]!;
      return;
    }
    if (hasInput) return;
    const segmenter = new Intl.Segmenter("zh-CN", { granularity: "grapheme" });
    const phrases = placeholderPhrases.map(phrase => [...segmenter.segment(phrase)].map(part => part.segment));
    let characterCount = 0;
    let deleting = false;
    let timer: ReturnType<typeof setTimeout>;
    promptPlaceholder.value = "";

    function typePlaceholder() {
      const phrase = phrases[phraseIndex]!;
      characterCount += deleting ? -1 : 1;
      promptPlaceholder.value = phrase.slice(0, characterCount).join("");
      let delay = deleting ? 35 : 85;
      if (characterCount === phrase.length) {
        deleting = true;
        delay = 1800;
      } else if (characterCount === 0) {
        deleting = false;
        phraseIndex = (phraseIndex + 1) % placeholderPhrases.length;
        delay = 300;
      }
      timer = setTimeout(typePlaceholder, delay);
    }

    timer = setTimeout(typePlaceholder, 300);
    onCleanup(() => clearTimeout(timer));
  }, { immediate: true });
});

onUnmounted(() => {
  window.removeEventListener("dragend", clearAttachmentDrag, true);
  window.removeEventListener("drop", clearAttachmentDrag, true);
  window.removeEventListener("blur", clearAttachmentDrag);
});
watch([creating, opening], ([creating, opening]) => {
  if (creating || opening) clearAttachmentDrag();
}, { flush: "sync" });

const selectedModel = ref("");
const reasoningEffort = ref("");
const sortDescending = ref(true);
const viewMode = ref("grid");
const creationTemplates = [
  { title: "短剧分镜", description: "从小说章节拆解为分镜脚本与画面", icon: IconMovie, prompt: "把这段故事拆成 6 个连贯分镜，补充景别、运镜、画面描述与时长。" },
  { title: "角色设定", description: "生成三视图，锁定全剧一致形象", icon: IconUsers, prompt: "为故事主角生成角色设定与三视图，保持后续所有镜头中的形象一致。" },
  { title: "产品宣传片", description: "一句话生成 30 秒产品短片", icon: IconVideo, prompt: "创作一支 30 秒产品宣传片，拆成 5 个镜头，风格简洁、现代、有质感。" },
];
function applyTemplate(value: string) {
  prompt.value = value;
  void nextTick(() => promptInput.value?.focus());
}
const sortedProjects = computed(() => [...projectList.value].sort((left, right) =>
  sortDescending.value ? right.lastOpenedAt - left.lastOpenedAt : left.lastOpenedAt - right.lastOpenedAt
));

function pasteAttachments(event: ClipboardEvent) {
  if (creating.value || opening.value) return;
  try {
    const files = Array.from(event.clipboardData?.files ?? []);
    if (!files.length) {
      const file = createPastedTextFile(event.clipboardData?.getData("text/plain") ?? "");
      if (file) files.push(file);
    }
    if (!files.length) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    addAttachments(files);
  } catch (error) {
    event.preventDefault();
    event.stopImmediatePropagation();
    ElMessage.error(error instanceof Error ? error.message : "添加附件失败，请重试");
  }
}

function selectAttachments(event: Event) {
  const input = event.target as HTMLInputElement;
  addAttachments(Array.from(input.files ?? []));
  input.value = "";
}

function clearAttachmentDrag() {
  attachmentDragDepth = 0;
  draggingAttachments.value = false;
}

function dragEnterAttachments(event: DragEvent) {
  if (!event.dataTransfer?.types.includes("Files")) return;
  event.preventDefault();
  event.stopPropagation();
  if (creating.value || opening.value) return clearAttachmentDrag();
  attachmentDragDepth++;
  draggingAttachments.value = true;
}

function dragOverAttachments(event: DragEvent) {
  if (!event.dataTransfer?.types.includes("Files")) return;
  event.preventDefault();
  event.stopPropagation();
  event.dataTransfer.dropEffect = creating.value || opening.value ? "none" : "copy";
  if (creating.value || opening.value) return clearAttachmentDrag();
  attachmentDragDepth = Math.max(1, attachmentDragDepth);
  draggingAttachments.value = true;
}

function dragLeaveAttachments(event: DragEvent) {
  if (!draggingAttachments.value) return;
  attachmentDragDepth = Math.max(0, attachmentDragDepth - 1);
  const target = event.currentTarget;
  if (!attachmentDragDepth || (target instanceof Node && event.relatedTarget instanceof Node && !target.contains(event.relatedTarget))) clearAttachmentDrag();
}

function dropAttachments(event: DragEvent) {
  if (!event.dataTransfer?.types.includes("Files")) return;
  event.preventDefault();
  event.stopPropagation();
  clearAttachmentDrag();
  addAttachments(Array.from(event.dataTransfer.files));
}

function addAttachments(files: File[]) {
  if (creating.value || opening.value) return;
  for (const file of files) {
    const readableMimeType = getReadableFileMimeType(file.name, file.type);
    const mimeType = readableMimeType ?? file.type.split(";")[0]!.trim().toLowerCase();
    if (!readableMimeType && !/^(image|video)\//.test(mimeType)) {
      ElMessage.warning("只支持图片、视频和受支持的文本或文档文件");
      continue;
    }
    if (!file.size || file.size > 100 * 1024 * 1024) {
      ElMessage.warning("附件不能为空且不能超过 100 MB");
      continue;
    }
    if (mimeType === "text/plain" && file.size > 400000) {
      ElMessage.warning("文本附件不能超过 400000 字节");
      continue;
    }
    if (promptAttachments.value.length >= 20) {
      ElMessage.warning("每条消息最多添加 20 个附件");
      break;
    }
    promptAttachments.value.push({ name: file.name, path: "", mimeType, file });
  }
}

async function restoreAttachment(index: number) {
  const attachment = promptAttachments.value[index];
  if (attachment?.mimeType !== "text/plain" || creating.value || opening.value) return;
  try {
    const text = await readTextAttachment(attachment);
    const currentIndex = promptAttachments.value.indexOf(attachment);
    if (currentIndex < 0 || creating.value || opening.value) return;
    prompt.value += `${prompt.value ? "\n" : ""}${text}`;
    promptAttachments.value.splice(currentIndex, 1);
    await nextTick();
    promptInput.value?.focus();
    promptInput.value?.textarea?.setSelectionRange(prompt.value.length, prompt.value.length);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "取回文本失败，请重试");
  }
}

async function openProject(project?: Project) {
  if (creating.value || opening.value) return;
  opening.value = true;
  try {
    const directory = project?.directory ?? await relocationPicker.value?.chooseDirectory();
    if (!directory) return;
    try { await workspaceStore.openProject(directory); }
    catch (err) {
      if (!project || !axios.isAxiosError(err) || err.response?.status !== 404) throw err;
      const reselect = await ElMessageBox.confirm(`项目“${project.name}”的文件夹不存在，是否重新选择文件夹？`, "工作目录不存在", {
        confirmButtonText: "重新选择", cancelButtonText: "取消", type: "warning",
      }).then(() => true, () => false);
      if (!reselect) return;
      const directory = await relocationPicker.value?.chooseDirectory();
      if (!directory) return;
      await workspaceStore.openProject(directory, project.directory);
    }
    await router.push("/workspace");
  } catch (err) {
    ElMessage.error(axios.isAxiosError<{ message?: string }>(err)
      ? err.response?.data.message || "无法打开项目，请重试"
      : err instanceof Error ? err.message : "无法打开项目，请重试");
  } finally { opening.value = false; }
}

async function renameProject(project: Project) {
  const result = await ElMessageBox.prompt("请输入项目名称", "重命名项目", {
    inputValue: project.name, confirmButtonText: "保存", cancelButtonText: "取消",
    inputValidator: value => !!value?.trim() || "项目名称不能为空",
  }).catch(() => null);
  if (result) workspaceStore.renameProject(project.directory, result.value);
}

async function createProject(fromPrompt = true) {
  if (creating.value || opening.value || (fromPrompt && !workspaceDirectory.value)) return;
  creating.value = true;
  const message = { prompt: prompt.value, attachments: promptAttachments.value.map(item => ({ ...item })), model: selectedModel.value, reasoningEffort: reasoningEffort.value };
  let files: ReturnType<typeof useWorkspaceFiles> | undefined;
  const createdPaths: string[] = [];
  let projectReady = false;
  try {
    let path = workspaceDirectory.value;
    if (!fromPrompt) {
      const confirmed = await ElMessageBox.confirm("请选择一个空文件夹作为项目目录，画布和素材将保存在其中。", "添加项目", {
        confirmButtonText: "选择空文件夹", cancelButtonText: "取消", type: "info",
      }).then(() => true, () => false);
      if (!confirmed) return;
      path = await relocationPicker.value?.chooseDirectory() ?? "";
      if (!path) return;
    }
    const { directory, empty } = await useWorkspaceFiles(path).list();
    if (!empty) return ElMessage.warning("该文件夹不为空，请重新选择空文件夹；已有项目请使用“导入项目”或点击项目列表打开。");
    files = useWorkspaceFiles(directory);
    await files.writeJson("画布1.json", { toonflowCanvas: true, nodes: [], edges: [], viewport: { x: 0, y: 0, zoom: 1 } }, true);
    createdPaths.push("画布1.json");
    if (fromPrompt && message.attachments.some(item => item.file)) {
      for (const path of ["assets", "assets/chat"]) {
        await files.mkdir(path);
        createdPaths.push(path);
      }
      for (const attachment of message.attachments) {
        if (!attachment.file) continue;
        const extension = attachment.name.match(/\.[a-zA-Z0-9]{1,10}$/)?.[0].toLowerCase() ?? "";
        const path = `assets/chat/${crypto.randomUUID()}${extension}`;
        await files.write(path, attachment.file, true);
        createdPaths.push(path);
        attachment.path = path;
        attachment.file = undefined;
      }
    }
    await workspaceStore.openProject(directory);
    projectReady = true;
    if (fromPrompt && (message.prompt.trim() || message.attachments.length)) {
      workspaceStore.pendingAgentMessage = { directory, ...message };
    }
    await router.push("/workspace");
  } catch (err) {
    const rollbackErrors: string[] = [];
    // 只回滚本次独占创建的文件与空目录，不递归删除用户选择的目录。
    if (files && !projectReady) for (const path of createdPaths.reverse()) {
      try { await files.remove(path); }
      catch { rollbackErrors.push(path); }
    }
    const message = axios.isAxiosError<{ message?: string }>(err)
      ? err.response?.data?.message || "创建项目失败，请重试"
      : err instanceof Error ? err.message : "创建项目失败，请重试";
    ElMessage.error(rollbackErrors.length ? `${message}；部分新建文件未清理，请检查：${rollbackErrors.join("、")}` : message);
  } finally {
    creating.value = false;
  }
}

</script>

<style lang="scss" scoped>
.home {
  position: relative;
  isolation: isolate;
  min-height: 100dvh;
  overflow: hidden;
  background: var(--tf-bg);
  color: var(--tf-text);

  &::before,
  &::after {
    content: "";
    position: absolute;
    inset: 0;
    z-index: -1;
    pointer-events: none;
  }
  &::before {
    background: radial-gradient(ellipse 52% 52% at 50% 0%, var(--tf-glow), transparent 72%);
  }
  &::after {
    opacity: .8;
    background-image: radial-gradient(var(--tf-dot-color) 1px, transparent 1px);
    background-size: 26px 26px;
    mask-image: radial-gradient(ellipse at 50% 14%, #000 10%, transparent 66%);
  }

  .pageHeader {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: 64px;
    padding: 0 40px;

    .brand {
      display: flex;
      align-items: center;
      gap: 11px;
      font-size: 19px;
      font-weight: 600;
      letter-spacing: -.3px;
    }
    .brandMark {
      display: grid;
      place-items: center;
      width: 34px;
      height: 34px;
      border-radius: 10px;
      background: linear-gradient(135deg, #2b3f6e, #16213c);
    }
    .brandLogo { width: 21px; height: 21px; filter: invert(1); }
    .headerActions { display: flex; align-items: center; gap: 10px; }
    .headerButton {
      height: 34px;
      margin: 0;
      padding: 0 15px;
      border-color: var(--tf-line);
      background: var(--tf-panel);
      color: var(--tf-text-2);
      font-size: 12.5px;
      text-decoration: none;
      &:hover { border-color: var(--tf-line-strong); background: var(--tf-panel-2); color: var(--tf-text); }
    }
    .avatar {
      display: grid;
      place-items: center;
      width: 30px;
      height: 30px;
      margin-left: 2px;
      border-radius: 50%;
      background: linear-gradient(135deg, #3a4d7a, #5b3f6e);
      color: #fff;
      font-size: 12px;
      font-weight: 500;
    }
  }

  .pageContent {
    padding: 34px 32px 34px;
    overflow: visible;

    .creationPanel {
      max-width: 860px;
      margin: 0 auto;
    }
    .heroCopy {
      text-align: center;
      .heroBadge {
        display: inline-flex;
        align-items: center;
        gap: 7px;
        padding: 6px 14px;
        border: 1px solid var(--tf-accent-soft);
        border-radius: 999px;
        background: var(--tf-accent-soft);
        color: var(--tf-accent);
        font-size: 12px;
      }
      h1 {
        margin: 20px 0 0;
        font-size: clamp(34px, 3.2vw, 44px);
        font-weight: 600;
        line-height: 1.2;
        letter-spacing: -1px;
      }
      p { margin: 14px 0 0; color: var(--tf-text-2); font-size: 15px; }
    }

    .promptCard {
      position: relative;
      margin-top: 30px;
      border: 1px solid var(--tf-line-strong);
      border-radius: 18px;
      background: var(--tf-panel);
      box-shadow: var(--tf-shadow-float), 0 0 0 4px var(--tf-accent-soft);
      &:focus-within { border-color: var(--tf-accent); }
      &.draggingAttachments { border-color: var(--tf-accent); }
      :deep(.el-card__footer) { border: 0; background: transparent; }
      .attachmentDropOverlay {
        position: absolute;
        inset: 0;
        z-index: 2;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 5px;
        border-radius: inherit;
        background: var(--tf-panel);
        color: var(--tf-text);
        pointer-events: none;
        > svg { color: var(--tf-accent); }
        strong { font-size: 14px; font-weight: 500; }
        span { color: var(--tf-text-2); font-size: 12px; }
      }
      .promptAttachments { margin-bottom: 12px; }
      :deep(.el-textarea__inner) {
        min-height: 76px !important;
        padding: 4px 0;
        box-shadow: none;
        background: transparent;
        color: var(--tf-text);
        font-size: 15px;
        line-height: 1.8;
        &::placeholder { color: var(--tf-text-3); }
      }
      .composerFooter {
        display: flex;
        align-items: center;
        gap: 10px;
        padding-top: 14px;
        border-top: 1px solid var(--tf-line-soft);
        .attachmentButton,
        .sendButton { height: 36px; margin: 0; border-radius: 9px; }
        .attachmentButton {
          width: 36px;
          padding: 0;
          border-color: var(--tf-line);
          background: var(--tf-panel-2);
          color: var(--tf-text-2);
        }
        .modelSelect { width: 172px; margin-left: auto; }
        .sendButton {
          padding: 0 18px;
          border-color: transparent;
          background: var(--tf-cta);
          color: var(--tf-cta-text);
          font-weight: 500;
          &:hover { opacity: .86; }
          &.is-disabled { opacity: .45; }
        }
      }
      .workspaceHint { margin: 10px 0 0; color: var(--tf-text-3); font-size: 12px; line-height: 1.6; }
    }

    .templateGrid {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 14px;
      margin-top: 26px;
      .templateCard {
        min-width: 0;
        padding: 0;
        overflow: hidden;
        border: 1px solid var(--tf-line);
        border-radius: 14px;
        background: var(--tf-panel);
        color: var(--tf-text);
        text-align: left;
        cursor: pointer;
        transition: transform 160ms ease, border-color 160ms ease;
        &:hover { transform: translateY(-2px); border-color: var(--tf-line-strong); }
        &:focus-visible { outline: 2px solid var(--tf-accent); outline-offset: 3px; }
        &:disabled { cursor: wait; opacity: .55; }
      }
      .templateCover {
        display: grid;
        place-items: center;
        height: 76px;
        color: rgba(255,255,255,.78);
        background: linear-gradient(135deg, #1a2447, #3c2a5e);
      }
      .tone-2 .templateCover { background: linear-gradient(135deg, #3a2450, #c97a4a); }
      .tone-3 .templateCover { background: linear-gradient(135deg, #0f2b33, #1c4a4a); }
      .templateInfo {
        display: flex;
        flex-direction: column;
        gap: 5px;
        padding: 12px 14px 14px;
        strong { font-size: 13px; font-weight: 500; }
        span { overflow: hidden; color: var(--tf-text-3); font-size: 11.5px; line-height: 1.7; text-overflow: ellipsis; white-space: nowrap; }
      }
    }

    .projectList {
      max-width: 1080px;
      margin: 40px auto 0;
      .sectionHeader {
        display: flex;
        align-items: center;
        gap: 12px;
        padding-bottom: 14px;
        border-bottom: 1px solid var(--tf-line-soft);
        h2 {
          display: flex;
          align-items: center;
          gap: 8px;
          margin: 0;
          font-size: 16px;
          font-weight: 500;
          span { color: var(--tf-text-3); font-size: 11px; font-weight: 400; }
        }
      }
      .projectTools { display: flex; align-items: center; gap: 8px; margin-left: auto; }
      .outlineButton,
      .iconButton {
        height: 32px;
        margin: 0;
        border-color: var(--tf-line);
        background: var(--tf-panel);
        color: var(--tf-text-2);
        font-size: 12px;
      }
      .outlineButton { padding: 0 12px; }
      .iconButton { width: 32px; padding: 0; }
      .viewSwitch {
        :deep(.el-radio-button__inner) {
          display: grid;
          place-items: center;
          width: 32px;
          height: 30px;
          padding: 0;
          border-color: var(--tf-line);
          background: var(--tf-panel);
          color: var(--tf-text-3);
          box-shadow: none;
        }
        :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
          background: var(--tf-seg-active);
          color: var(--tf-text);
        }
      }
      .projectItems {
        display: grid;
        grid-template-columns: repeat(4, minmax(0, 1fr));
        gap: 14px;
        margin-top: 16px;
        &.listView { grid-template-columns: 1fr; }
      }
      .projectCard {
        position: relative;
        overflow: hidden;
        border: 1px solid var(--tf-line);
        border-radius: 14px;
        background: var(--tf-panel);
        transition: transform 160ms ease, border-color 160ms ease;
        &:hover { transform: translateY(-2px); border-color: var(--tf-line-strong); }
      }
      .projectEntry {
        display: block;
        width: 100%;
        padding: 0;
        border: 0;
        background: transparent;
        color: inherit;
        font: inherit;
        text-align: left;
        cursor: pointer;
        &:focus-visible { outline: 2px solid var(--tf-accent); outline-offset: -3px; }
        &:disabled { cursor: wait; opacity: .6; }
      }
      .projectCover {
        position: relative;
        display: grid;
        place-items: center;
        height: 88px;
        overflow: hidden;
        color: rgba(255,255,255,.75);
        background: linear-gradient(135deg, #101d36, #23445e);
        &.tone-2 { background: linear-gradient(135deg, #3a2450, #c97a4a); }
        &.tone-3 { background: linear-gradient(135deg, #0f2b33, #1c4a4a); }
        &.tone-4 { background: linear-gradient(135deg, #141c3f, #4a2c66); }
        > svg { position: relative; z-index: 1; }
        .coverGrid {
          position: absolute;
          inset: 0;
          opacity: .16;
          background-image: linear-gradient(rgba(255,255,255,.25) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.25) 1px, transparent 1px);
          background-size: 20px 20px;
          transform: perspective(180px) rotateX(55deg) scale(1.5) translateY(24px);
        }
      }
      .projectInfo {
        display: flex;
        flex-direction: column;
        min-width: 0;
        padding: 12px 13px;
      }
      .projectName {
        display: flex;
        align-items: center;
        gap: 7px;
        overflow: hidden;
        font-size: 13px;
        font-weight: 500;
        text-overflow: ellipsis;
        white-space: nowrap;
        svg { flex-shrink: 0; margin-left: auto; color: var(--tf-text-3); }
      }
      .projectPath,
      .projectTime { overflow: hidden; color: var(--tf-text-3); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
      .projectPath { margin-top: 6px; }
      .projectTime { margin-top: 8px; }
      .projectActions {
        position: absolute;
        top: 8px;
        right: 8px;
        display: flex;
        gap: 3px;
        opacity: 0;
        transition: opacity 150ms ease;
        .el-button { width: 28px; height: 28px; margin: 0; padding: 0; border-radius: 7px; background: rgba(10,12,18,.72); color: #fff; }
      }
      .projectCard:hover .projectActions,
      .projectCard:focus-within .projectActions { opacity: 1; }
      .listView .projectEntry { display: flex; align-items: center; }
      .listView .projectCover { flex: 0 0 142px; height: 86px; }
      .listView .projectInfo { flex: 1; }
      .emptyProjects {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
        margin-top: 16px;
        padding: 34px;
        border: 1px dashed var(--tf-line-strong);
        border-radius: 14px;
        color: var(--tf-text-3);
        strong { color: var(--tf-text-2); font-size: 13px; font-weight: 500; }
        span { font-size: 12px; }
      }
    }
    .homeFoot {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 7px;
      margin-top: 30px;
      color: var(--tf-text-3);
      font-size: 12px;
    }
  }

  @media (max-width: 1100px) {
    .pageContent .projectList .projectItems { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
  @media (max-width: 700px) {
    .pageHeader { padding: 0 20px; }
    .pageContent { padding: 28px 20px; }
    .pageContent .templateGrid { grid-template-columns: 1fr; }
    .pageContent .promptCard .composerFooter { flex-wrap: wrap; }
    .pageContent .promptCard .modelSelect { margin-left: 0; }
    .pageContent .promptCard .sendButton { margin-left: auto; }
    .pageContent .projectList .sectionHeader { align-items: flex-start; flex-direction: column; }
    .pageContent .projectList .projectTools { width: 100%; margin-left: 0; flex-wrap: wrap; }
  }
  @media (max-width: 520px) {
    .pageHeader .headerButton:first-child { display: none; }
    .pageContent .projectList .projectItems { grid-template-columns: 1fr; }
    .pageContent .projectList .listView .projectCover { flex-basis: 104px; }
  }
}
</style>
