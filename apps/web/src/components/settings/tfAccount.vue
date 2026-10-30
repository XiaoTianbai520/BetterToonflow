<template>
  <div v-if="providerId" class="providerBalance" :aria-busy="loading">
    <div class="balanceHeader">
      <span>第三方供应商余额</span>
      <strong>{{ snapshot?.balance ? `${numberFormat.format(snapshot.balance.balance)} ${snapshot.balance.unit}` : '—' }}</strong>
      <el-button text :icon="IconRefresh" :loading="loading" @click="refresh">刷新余额</el-button>
    </div>
    <p v-if="status === 'unsupported'" role="status">该供应商未提供余额查询</p>
    <p v-if="status === 'retrying'" role="status">正在恢复余额订阅（{{ retryCount }}/3）</p>
    <p v-if="error" class="balanceError" role="alert">{{ error }}</p>
    <dl v-if="snapshot?.balance" class="balanceDetails">
      <template v-if="snapshot.balance.keyBalance !== undefined">
        <dt>密钥余额</dt><dd>{{ snapshot.balance.keyBalance === null ? '无限制' : `${numberFormat.format(snapshot.balance.keyBalance)} ${snapshot.balance.unit}` }}</dd>
      </template>
      <template v-if="snapshot.balance.totalConsumption !== undefined">
        <dt>累计消费</dt><dd>{{ numberFormat.format(snapshot.balance.totalConsumption) }} {{ snapshot.balance.unit }}</dd>
      </template>
    </dl>
  </div>
</template>

<script setup lang="ts">
import { IconRefresh } from "@tabler/icons-vue";
import { useMediaProviderBalance } from "@/stores/settings";

// ACT: 保留原组件文件入口供工作台迁移；只有传入明确的第三方 providerId 才查询余额。
const props = withDefaults(defineProps<{
  providerId?: string; visible?: boolean; apiKey?: string; saveApiKey?: (key: string) => Promise<void>;
}>(), { providerId: "", visible: true });
const { snapshot, loading, error, status, retryCount, refresh } = useMediaProviderBalance(() => props.providerId, () => props.visible);
const numberFormat = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 6 });
</script>

<style lang="scss" scoped>
.providerBalance {
  margin-top: 16px;
  font-size: 13px;

  .balanceHeader {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
    strong { font-variant-numeric: tabular-nums; }
  }
  .balanceError { color: var(--el-color-danger); }
  .balanceDetails {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 8px 16px;
    dd { margin: 0; font-variant-numeric: tabular-nums; }
  }
}
</style>
