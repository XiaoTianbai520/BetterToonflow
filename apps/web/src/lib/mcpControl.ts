import { watch, type WatchSource } from "vue";

/**
 * 等待目标值就绪的最小工具，行为为 `watch(flush: "post")` + 可中止。
 *
 * MCP 控制台属于 PRD 4.1.6 永久排除能力，原 `useMcpControl` / `registerWorkspaceControl`
 * 等控制入口已全部移除。此函数与 MCP 无关，仅因文档面板（`pages/workspace/panels/document`）
 * 仍以本路径引用而暂留；该面板迁移到本地同名实现后，本文件应整体删除。
 */
export function waitForControlValue<T>(read: WatchSource<T | undefined>, signal: AbortSignal): Promise<T> {
  signal.throwIfAborted();
  return new Promise((resolve, reject) => {
    let stop = () => {};
    const cancel = () => { stop(); reject(signal.reason); };
    signal.addEventListener("abort", cancel, { once: true });
    stop = watch(read, value => {
      if (value === undefined) return;
      stop();
      signal.removeEventListener("abort", cancel);
      resolve(value);
    }, { flush: "post" });
    const value = typeof read === "function" ? read() : read.value;
    if (value !== undefined) {
      stop();
      signal.removeEventListener("abort", cancel);
      resolve(value);
    }
  });
}
