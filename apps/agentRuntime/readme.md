# AgentRuntime

`@toonflow/agent-runtime` 是 Spring Boot 网关调用的内部 Bun 服务，只监听 `127.0.0.1`，不托管前端。

启动前设置 `TOONFLOW_INTERNAL_TOKEN`；可用 `TOONFLOW_AGENT_RUNTIME_PORT` 覆盖默认端口 `3001`，用 `TOONFLOW_DATA_DIR` 指定共享数据目录（默认仓库 `data/`）。所有请求均校验 `X-Toonflow-Internal-Token`，未配置令牌时入口拒绝启动，中间件拒绝所有请求。

在本目录执行：

```sh
bun run routes
bun run typecheck
bun run build
bun run start
```

`routes` 根据 `src/routes/` 生成 24 个路由；`build` 仅写入本包 `dist/`，并复制文档解析与图片处理所需的原生/WASM 依赖。开发时使用 `bun run dev`。

配置读取兼容 `{ settings, disabledSkills, toolConfigs }` 包装快照和直接保存的 settings 对象，每次读取校验 SHA-256，摘要变化后重新解析；损坏快照报错，不退回旧缓存。个性化文档每次读取重新计算 revision；供应商源码每次使用重新读取、解析和创建 VM，余额刷新也重新加载源码与配置。

## 后续任务交接

- **W2-T3**：现有内置工具已迁入 `src/tools/`，本任务保留受限名单内的旧工具加载适配，完整的 11 工具源码注册与算力硬门控仍由 W2-T3 替换实现。
- **W3-T1**：`memory` 保存复用核心后端 `PUT http://127.0.0.1:3000/api/settings/personalization/save`，由 Java 完成 CAS、数据库事务及快照同步；Runtime 不直接覆盖记忆快照。
- **W3-T5**：供应商 `add`、`save`、`models` 仅生成待提交的元数据与源码；内部响应 `data.source` 供 Java 持久化，网关须剥离该字段后按公开契约返回。`delete` 仅校验快照 revision，Java 完成删除与关联配置更新。Java 必须对数据库的当前 revision 再执行 CAS，提交后同步 `data/providers/` 和设置快照；Runtime 的预校验不能代替数据库并发保护。

运行时只读 Spring Boot 管理的配置与供应商派生快照。上述写操作的完整持久化依赖相应 Java 任务接续完成。
