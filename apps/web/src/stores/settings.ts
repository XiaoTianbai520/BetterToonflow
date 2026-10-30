import axios from "axios";
import { computed, nextTick, onScopeDispose, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { invalidateNodeModels } from "@/nodes/nodeCore/nodeAi";
import { canvasShortcutFields, defaultCanvasShortcuts, getShortcutBindings, isShortcutAllowed, normalizeShortcut, type CanvasShortcuts } from "@/lib/canvasShortcuts";
import { normalizeNodeMenu, type StoredNodeMenuItem } from "@/components/settings/nodeMenu";
import "element-plus/es/components/message/style/css";

export const settings = ref<Record<string, unknown>>({});
// ACT: 页面在 loadSettings 完成后才挂载，加载标记仅保留在设置初始化与自动保存内部。
let settingsReady = false;
let saveQueue = Promise.resolve();
let applyingSettings = false;

export const settingsStorage = {
  getItem(key: string) {
    const stores = settings.value.stores as Record<string, unknown> | undefined;
    if (stores && Object.hasOwn(stores, key)) return JSON.stringify(stores[key]);
    // ACT: 只迁移当前来源可读取的旧缓存，保留原值；不同端口的 localStorage 不能互读。
    const value = localStorage.getItem(key);
    if (value !== null) settingsStorage.setItem(key, value);
    return value;
  },
  setItem(key: string, value: string) {
    settings.value.stores = { ...(settings.value.stores as Record<string, unknown> | undefined), [key]: JSON.parse(value) };
  },
};

export const defaultUiSettings = { theme: "light", language: "system", primaryColor: "#409eff", fontScale: 100, mobileScale: 75, radius: 8, startupAnimation: true };
export const uiSettings = computed(() => {
  const raw = settings.value.ui;
  const ui = raw && typeof raw === "object" && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  return {
    language: "zh-CN" as const,
    theme: ui.theme === "dark" || ui.theme === "system" ? ui.theme : "light",
    primaryColor: typeof ui.primaryColor === "string" && /^#[\da-f]{6}$/i.test(ui.primaryColor) ? ui.primaryColor : defaultUiSettings.primaryColor,
    fontScale: typeof ui.fontScale === "number" && Number.isFinite(ui.fontScale) ? Math.min(125, Math.max(85, ui.fontScale)) : defaultUiSettings.fontScale,
    mobileScale: typeof ui.mobileScale === "number" && Number.isFinite(ui.mobileScale) ? Math.min(100, Math.max(50, ui.mobileScale)) : defaultUiSettings.mobileScale,
    radius: typeof ui.radius === "number" && Number.isFinite(ui.radius) ? Math.min(16, Math.max(0, ui.radius)) : defaultUiSettings.radius,
    startupAnimation: ui.startupAnimation !== false,
  };
});

export function updateUiSettings(patch: Partial<typeof defaultUiSettings>) {
  const current = settings.value.ui;
  settings.value = { ...settings.value, ui: { ...(current && typeof current === "object" && !Array.isArray(current) ? current : {}), ...patch } };
}

export const generalSettings = computed(() => {
  const raw = settings.value.general;
  const general = raw && typeof raw === "object" && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  const shortcuts = general.canvasShortcuts && typeof general.canvasShortcuts === "object" && !Array.isArray(general.canvasShortcuts)
    ? general.canvasShortcuts as Record<string, unknown> : {};
  const savedBindings = new Map(canvasShortcutFields.flatMap(field => {
    const value = shortcuts[field.id];
    const binding = typeof value === "string" ? normalizeShortcut(value) : undefined;
    return binding !== undefined && isShortcutAllowed(field, binding) ? [[field.id, binding] as const] : [];
  }));
  return {
    nodeMenu: normalizeNodeMenu(general.nodeMenu),
    canvasCompositingEnabled: general.canvasCompositingEnabled === true,
    canvasEdgeAnimationEnabled: general.canvasEdgeAnimationEnabled !== false,
    canvasEdgeColorMode: general.canvasEdgeColorMode === "none" || general.canvasEdgeColorMode === "custom" ? general.canvasEdgeColorMode : "theme",
    canvasEdgeColor: typeof general.canvasEdgeColor === "string" && /^#[\da-f]{6}$/i.test(general.canvasEdgeColor) ? general.canvasEdgeColor : defaultUiSettings.primaryColor,
    canvasShortcuts: Object.fromEntries(canvasShortcutFields.map(field => {
      if (savedBindings.has(field.id)) return [field.id, savedBindings.get(field.id)];
      const binding = defaultCanvasShortcuts[field.id];
      const conflicts = canvasShortcutFields.some(other => other.gesture === field.gesture
        && getShortcutBindings(savedBindings.get(other.id) ?? "").some(value => getShortcutBindings(binding).includes(value)));
      return [field.id, conflicts ? "" : binding];
    })) as CanvasShortcuts,
  };
});

export function updateGeneralSettings(patch: Omit<Partial<typeof generalSettings.value>, "nodeMenu"> & { nodeMenu?: StoredNodeMenuItem[] }) {
  const current = settings.value.general;
  settings.value = { ...settings.value, general: { ...(current && typeof current === "object" && !Array.isArray(current) ? current : {}), ...patch } };
}

export const privacySettings = computed(() => {
  const raw = settings.value.privacy;
  const privacy = raw && typeof raw === "object" && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  return {
    dataCollectionEnabled: privacy.dataCollectionEnabled !== false,
    anonymousId: typeof privacy.anonymousId === "string" ? privacy.anonymousId : "",
  };
});

export type CustomProviderModel = { id: string; label: string; contextWindow?: number; maxOutputTokens?: number };
export type CustomProvider = { id: string; label: string; icon?: string; version?: string; apiUrl: string; apiKey: string; protocol: string; enabled?: boolean; models: CustomProviderModel[] };
export const customProviders = computed<CustomProvider[]>(() => Array.isArray(settings.value.providers)
  ? settings.value.providers.filter((item): item is CustomProvider => !!item && typeof item.id === "string" && typeof item.label === "string" && Array.isArray(item.models)
    && item.models.every((model: CustomProviderModel) => !!model && typeof model.id === "string" && typeof model.label === "string"))
  : []);

export const modelChoices = computed(() => customProviders.value.filter(provider => provider.enabled !== false).flatMap(provider => provider.models.map(model => ({
  value: JSON.stringify([provider.id, model.id]), providerId: provider.id, modelId: model.id, label: model.label, contextWindow: model.contextWindow,
}))));

function cleanSettings(value: Record<string, unknown>) {
  const result = { ...value };
  for (const key of ["desktopUpdateSource", "desktopUpdateCustomUrl", "mcp", "memory", "agents", "customProviders", "mediaProviderConfigs"])
    delete result[key];
  return result;
}

export type MediaProviderSettings = { config?: Record<string, unknown>; enabled?: boolean; models?: unknown[] };
export const mediaProviderSettings = computed(() => (settings.value.mediaProviders ?? {}) as Record<string, MediaProviderSettings>);

export async function loadSettings() {
  if (settingsReady) return;
  const { data } = await axios.get("/api/settings/get", { headers: { "Cache-Control": "no-cache", "x-toonflow-workspace": "1" } });
  if (settingsReady) return;
  if (data.code !== 200 || !data.data || typeof data.data !== "object" || Array.isArray(data.data)) {
    throw new Error("读取设置失败");
  }
  settings.value = cleanSettings(data.data);
  // 等初始化引发的监听执行完，再允许自动保存。
  await nextTick();
  settingsReady = true;
}

export function saveSettings(update?: (current: Record<string, unknown>) => Record<string, unknown> | undefined) {
  // ACT: 队列内读取最新配置再计算变更，确认成功后发布；仅协调当前页面的保存。
  const saving = saveQueue.then(async () => {
    const patch = update?.(settings.value);
    if (update && !patch) return false;
    const nextSettings = cleanSettings({ ...settings.value, ...patch });
    const requestedNodeMenu = JSON.stringify(((patch?.general ?? settings.value.general) as { nodeMenu?: unknown } | undefined)?.nodeMenu);
    const { data } = await axios.put<{ code: number; data: { models?: unknown[]; nodeMenu?: StoredNodeMenuItem[] | null } | null }>(
      "/api/settings/save", { settings: nextSettings }, { headers: { "x-toonflow-workspace": "1" } },
    );
    if (data.code !== 200) throw new Error("保存设置失败");
    if (patch && Object.hasOwn(patch, "providers")) invalidateNodeModels("language");
    applyingSettings = true;
    try { settings.value = cleanSettings({ ...settings.value, ...patch }); }
    finally { applyingSettings = false; }
    if (data.data && Object.hasOwn(data.data, "nodeMenu")
      && requestedNodeMenu === JSON.stringify((settings.value.general as { nodeMenu?: unknown } | undefined)?.nodeMenu)
      && requestedNodeMenu !== JSON.stringify(data.data.nodeMenu ?? undefined)) {
      applyingSettings = true;
      try {
        const general = { ...(settings.value.general as Record<string, unknown> | undefined) };
        if (data.data.nodeMenu?.length) general.nodeMenu = data.data.nodeMenu;
        else delete general.nodeMenu;
        settings.value = { ...settings.value, general };
      } finally { applyingSettings = false; }
    }
    if (patch && Object.hasOwn(patch, "mediaProviders")) {
      invalidateNodeModels("media");
      window.dispatchEvent(new Event("toonflow:provider-config-changed"));
    }
    return true;
  });
  saveQueue = saving.then(() => {}, () => {});
  return saving;
}

watch(settings, () => {
  if (!settingsReady || applyingSettings) return;
  void saveSettings().catch(() => { ElMessage.error("设置保存失败，请稍后重试"); });
}, { deep: true, flush: "sync" });

type ProviderBalance = {
  balance: number; unit: string; keyBalance?: number | null; totalConsumption?: number; totalRecharge?: number;
};
type BalanceSnapshot = { accountId: string; balance: ProviderBalance | null; revision: number };

export function useMediaProviderBalance(providerId: () => string, active: () => boolean) {
  const snapshot = ref<BalanceSnapshot>();
  const loading = ref(false);
  const error = ref("");
  const status = ref<"idle" | "loading" | "connected" | "retrying" | "stopped" | "error" | "unsupported">("idle");
  const retryCount = ref(0);
  const maxRetries = 3;
  const retryDelay = 1500;
  let controller: AbortController | undefined;

  function applySnapshot(value: BalanceSnapshot) {
    if (!value || !/^[a-f0-9]{64}$/.test(value.accountId) || !Number.isSafeInteger(value.revision)
      || value.revision < 0 || (value.balance !== null && (!value.balance || !Number.isFinite(value.balance.balance)
        || typeof value.balance.unit !== "string" || !value.balance.unit.trim()))) throw new Error("余额响应格式错误");
    if (snapshot.value?.accountId === value.accountId && snapshot.value.revision > value.revision) return;
    snapshot.value = value;
  }

  async function readEvents(response: Response, signal: AbortSignal) {
    if (!response.headers.get("content-type")?.includes("text/event-stream") || !response.body)
      throw new Error("余额订阅未返回事件流");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let data: string[] = [];
    try {
      while (!signal.aborted) {
        const chunk = await reader.read();
        signal.throwIfAborted();
        if (chunk.done) throw new Error("余额订阅已断开，请刷新余额");
        buffer += decoder.decode(chunk.value, { stream: true });
        let end: number;
        while ((end = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, end).replace(/\r$/, "");
          buffer = buffer.slice(end + 1);
          if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
          if (line || !data.length) continue;
          const value: BalanceSnapshot = JSON.parse(data.join("\n"));
          data = [];
          if (value.accountId !== snapshot.value?.accountId) continue;
          applySnapshot(value);
          // 只有收到有效快照才结束连续失败计数，HTTP 200 或 balance 成功不代表订阅恢复。
          retryCount.value = 0;
          status.value = "connected";
          error.value = "";
        }
      }
    } finally {
      await reader.cancel().catch(() => {});
      reader.releaseLock();
    }
  }

  async function refresh() {
    controller?.abort();
    snapshot.value = undefined;
    error.value = "";
    retryCount.value = 0;
    loading.value = false;
    status.value = "idle";
    const id = providerId();
    if (!active() || !id) return;
    const request = new AbortController();
    controller = request;
    const signal = request.signal;
    status.value = "loading";
    let recovering = false;
    while (!signal.aborted) {
      try {
        if (recovering) {
          if (retryCount.value >= maxRetries) {
            status.value = "stopped";
            error.value = "余额订阅连续重试 3 次失败，已停止自动重连，请手动刷新";
            return;
          }
          retryCount.value++;
          status.value = "retrying";
          await new Promise<void>((resolve, reject) => {
            const abort = () => { clearTimeout(timer); reject(signal.reason); };
            const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, retryDelay);
            signal.addEventListener("abort", abort, { once: true });
          });
        }
        signal.throwIfAborted();
        loading.value = true;
        const { data } = await axios.post<{ code: number; data: BalanceSnapshot; message?: string }>(
          "/api/ai/media/balance", { providerId: id }, { signal, timeout: 25000 },
        );
        signal.throwIfAborted();
        if (data.code !== 200) throw new Error(data.message || "查询余额失败");
        applySnapshot(data.data);
        loading.value = false;
        if (data.data.balance === null) { status.value = "unsupported"; return; }
        // Fetch 直接读取 HTTP 状态，并由 AbortController 关闭旧流，避免 EventSource 隐式无限重连。
        const response = await fetch(`/api/ai/media/balanceEvents?accountIds=${encodeURIComponent(data.data.accountId)}`, {
          signal, headers: { Accept: "text/event-stream" }, cache: "no-store",
        });
        if (response.status === 404) {
          await response.body?.cancel();
          recovering = true;
          continue;
        }
        if (!response.ok) throw new Error(`余额订阅失败（HTTP ${response.status}）`);
        await readEvents(response, signal);
      } catch (reason) {
        if (signal.aborted) return;
        error.value = axios.isAxiosError<{ message?: string }>(reason)
          ? reason.response?.data?.message || reason.message : reason instanceof Error ? reason.message : "余额订阅失败";
        if (!recovering) { status.value = "error"; return; }
      } finally {
        if (!signal.aborted) loading.value = false;
      }
    }
  }

  watch([providerId, active, () => JSON.stringify(mediaProviderSettings.value[providerId()])], () => void refresh(), { immediate: true });
  onScopeDispose(() => controller?.abort());
  return { snapshot, loading, error, status, retryCount, refresh };
}
