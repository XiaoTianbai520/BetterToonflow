<template>
  <div class="workspaceMenu">
    <div class="menuContent">
      <el-button class="toolButton" text aria-label="退出项目" title="退出项目" @click="exitVisible = true">
        <icon-logout :size="20" aria-hidden="true" />
      </el-button>
      <el-button class="toolButton" text aria-label="设置" title="设置" @click="emit('openSettings')">
        <icon-settings :size="20" aria-hidden="true" />
      </el-button>
      <span class="avatar" aria-hidden="true">T</span>
    </div>
    <el-dialog v-model="exitVisible" title="退出项目" width="360px" alignCenter appendToBody>
      <span>是否退出当前项目并返回首页？</span>
      <template #footer>
        <el-button @click="exitVisible = false">取消</el-button>
        <el-button type="primary" :loading="leaving" @click="exitProject">退出项目</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { IconLogout, IconSettings } from "@tabler/icons-vue";

const emit = defineEmits<{ openSettings: [] }>();
const router = useRouter();
const exitVisible = ref(false);
const leaving = ref(false);

async function exitProject() {
  if (leaving.value) return;
  leaving.value = true;
  try {
    await router.push("/home");
  } finally {
    leaving.value = false;
  }
}
</script>

<style scoped lang="scss">
.workspaceMenu {
  .menuContent {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;

    .toolButton {
      width: 40px;
      height: 40px;
      margin: 0;
      padding: 0;
      border-radius: 10px;
      color: var(--tf-text-3);
      &:hover { background: var(--tf-panel-2); color: var(--tf-text); }
    }
    .avatar {
      display: grid;
      place-items: center;
      width: 30px;
      height: 30px;
      margin-top: 6px;
      border-radius: 50%;
      background: linear-gradient(135deg, #3a4d7a, #5b3f6e);
      color: #fff;
      font-size: 12px;
    }
  }
}
</style>
