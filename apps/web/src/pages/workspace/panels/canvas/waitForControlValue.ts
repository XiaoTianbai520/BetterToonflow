import { watch, type WatchSource } from "vue";

// ACT: 画布宿主等待实例就绪的最小工具，替代原 @/lib/mcpControl 的同名实现。
// MCP 控制台属于永久排除能力，画布不应再依赖该模块；行为保持 watch(flush: post) + 可中止。
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
