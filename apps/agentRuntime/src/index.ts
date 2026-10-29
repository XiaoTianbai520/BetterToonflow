import { createApp } from "./app";

const port = Number(process.env.TOONFLOW_AGENT_RUNTIME_PORT || 3001);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("TOONFLOW_AGENT_RUNTIME_PORT 必须是 1..65535 的整数");
if (!process.env.TOONFLOW_INTERNAL_TOKEN) throw new Error("必须配置 TOONFLOW_INTERNAL_TOKEN");

const app = await createApp();
app.listen(port, "127.0.0.1", () => {
  console.log(`[AgentRuntime 启动成功]: http://127.0.0.1:${port}`);
});
