import { createApp } from "./app";

const port = Number(process.env.TOONFLOW_AGENT_RUNTIME_PORT || 3001);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("TOONFLOW_AGENT_RUNTIME_PORT 必须是 1..65535 的整数");
if (!process.env.TOONFLOW_INTERNAL_TOKEN) throw new Error("必须配置 TOONFLOW_INTERNAL_TOKEN");

const app = await createApp();
const server = app.listen(port, "127.0.0.1", () => {
  console.log(`[AgentRuntime 启动成功]: http://127.0.0.1:${port}`);
});
// ACT: 关闭默认 300s 请求总超时，避免长耗时流式对话与本地媒体生成被 HTTP Server 提前断开。
server.requestTimeout = 0;
server.timeout = 0;
server.headersTimeout = 60_000;
server.keepAliveTimeout = 65_000;
