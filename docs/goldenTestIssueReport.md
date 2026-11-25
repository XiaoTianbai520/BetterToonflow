# 黄金全量测试问题整理与排查报告

> 本文档基于《樱花信笺》黄金全量测试（工作区 `data/workspaces/goldenSchoolRomance`）的真实会话日志、画布 JSON 产物、验收记录以及三端源码（`apps/web`、`apps/server`、`apps/agentRuntime`、`data/providers`、`data/skills`、`packages/skills`）排查整理，供后续 AI 开发与修复任务直接使用。

---

## 问题总览

| 编号 | 问题摘要 | 涉及模块 | 严重程度 |
| --- | --- | --- | --- |
| **P1** | Agent 生成视频时将人物参考图（三视图设定板）接入了 `h3I2v` 首帧槽位，导致成片开头/中间出现参考图画面 | `data/skills/*`、`packages/skills/*`、`data/providers/h3Local3060.ts`、`apps/web/.../videoGenerationNode`、`apps/agentRuntime/.../canvas/authorization.ts` | 高（成片可用性） |
| **P2** | Agent 缺乏面向本地长耗时生成（单段可达 30~60 分钟）的等待与全自动调度机制，等待极短时间即停轮并需人工唤醒，且多层存在硬超时与逐次弹窗阻塞 | `apps/agentRuntime`、`apps/server`、`apps/web`、`docs/apiContract.md`、`data/skills/*` | 高（全流程无人值守能力） |

---

## 问题一：本地 ComfyUI 模型与 Skill 未适配，人物参考图被误作首帧导致视频出现参考图画面

### 1.1 实测现象与黄金测试证据

在黄金全量测试工作区 [樱花信笺_第1集_制作.json](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/workspaces/goldenSchoolRomance/樱花信笺_第1集_制作.json) 及主会话日志 [2026-11-07T03-18-17-203Z_01a1b403-9973-7271-8eac-358bbf3ab611.jsonl](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/workspaces/goldenSchoolRomance/.agent/sessions/2026-11-07T03-18-17-203Z_01a1b403-9973-7271-8eac-358bbf3ab611.jsonl) 中可以看到以下事实：

1. **角色设定图是“多视角拼接参考板”**：
   - `角色设定 藤井律`（`9f5683f9-6550-40e3-92df-769e2a47d8a2`）与 `角色设定 佐仓千夏`（`60eff61e-084d-4b19-8bfa-be45972e87dc`）均为 **三视图 + 面部特写拼版**（提示词明确要求“正面半身、侧面半身、背面全身三个视角并排，下方一张面部特写”）。
2. **视频节点错误选择了 `h3I2v`（首帧模式）而非 `h3R2v`（多参参考模式）**：
   - 最初节点配置为 `h3T2v`（文生视频），因 `h3T2v` 不支持图片输入（见 [w5T2Acceptance.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/docs/w5T2Acceptance.md#L129)），后续把 8 个视频节点全部改成了 `h3I2v`（`mode: "singleImage"`）。
   - 同时直接把 `角色设定 藤井律` 连给了 `分镜视频01`，把 `角色设定 佐仓千夏` 连给了 `分镜视频03~08`；而画布上真正已经生成好的 8 张单帧画面 `分镜参考01~08`（如 `bb5f3a76...`、`060cc4d8...` 等）反而没有任何出边、完全未被视频节点使用。
3. **提示词格式也未按 I2VA / R2V 规范对齐**：
   - 8 个视频节点的提示词仍保留最初 T2VA 写法（直接以 `integrated_multimodal_description: [Shot 1] ...` 开头），既没有写入首帧对齐指令，也没有说明参考图是角色设定而非开篇画面。

### 1.2 根因排查（4 个层面的耦合问题）

#### 原因 A：主流程 Skill 强绑定云端 Seedance，未适配本地 ComfyUI 模型能力
1. **默认主工作流硬锁 Seedance 2.0/2.5**：
   - [data/skills/workflow/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/workflow/SKILL.md) 与 [packages/skills/workflow/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/packages/skills/workflow/SKILL.md) 均强制锁存 Seedance 路由（“无论用户是否提到 Seedance……都立即进入 SEEDANCE EPISODE DIRECTOR · PART A”），完全没有覆盖本地 ComfyUI 供应商（`qwenImage21Local`、`h3Local3060`、`breezeTtsLocal`）的模型选择与节点拓扑差异。
   - [data/skills/canvas/references/videoProduction.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/canvas/references/videoProduction.md#L7) 明确规定：“不默认生成关键帧，角色/场景资产图直接作为视频输入”。该规则**仅对多参参考模式（Seedance 多参 / H3 `h3R2v`）成立**，一旦落到首帧模式（`h3I2v`）就会直接导致参考板当首帧。
2. **H3 专用 Skill 缺乏 Toonflow 模式路由与“三视图禁止当首帧”硬约束**：
   - 虽然运行时目录 `data/skills/` 已存在 `h3-prompt-writing` 和 `minimax-h3-prompt-optimizer`，但在黄金测试中 Agent 只读了 `h3-prompt-writing/SKILL.md` 与 `references/base-en.txt`（T2VA/I2VA/FL2VA/L2VA），**未读取 `references/ref-en.txt`（Ref2VA）**，也未读取 `minimax-h3-prompt-optimizer`。
   - `h3-prompt-writing/SKILL.md` 没有告知 Agent 如何把 Toonflow 画布资产映射到 `h3Local3060` 的 5 个模型：
     - **角色三视图/场景主视图（外观身份参考）** 必须选 **`h3R2v`（Ref2VA）**；
     - **单镜开篇画面/上镜尾帧（具体帧锚点）** 才能选 **`h3I2v`（I2VA）** / `h3Fl2v` / `h3L2v`。
   - 注：旧版 [v118workflow videoPromptCraft.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/v118workflow/references/knowledge/videoPromptCraft.md#L23) 曾写过“资产图负责身份/外观，分镜图负责该镜构图，不把角色三视图说成首帧画面”，但当前默认激活的 `workflow` 与 `h3-prompt-writing` 均缺失该规则。
3. **仓库与运行时 Skill 存在漂移**：
   - 经哈希比对，`packages/skills/workflow/SKILL.md` 与 `data/skills/workflow/SKILL.md` 内容不一致；`h3-prompt-writing` 与 `minimax-h3-prompt-optimizer` 仅存在于 `data/skills/`，未纳入 `packages/skills/`。

#### 原因 B：`h3Local3060.ts` 中 `h3I2v` 的 ComfyUI 工作流与自动补齐提示词机制
在 [data/providers/h3Local3060.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/providers/h3Local3060.ts) 中：
- **`h3I2v`（`i2v_*` 工作流）**：第 146 行、第 181 行、第 212 行把唯一输入图片直接挂载到 ComfyUI `MiniMaxH3ImageToVideo.inputs.first_frame`；且第 129–135 行 `formatPrompt()` 会在 prompt 不以 `For the target video` 开头时自动在头部拼接：
  > `For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.`
- **`h3R2v`（`r2v_*` 工作流）**：第 149 行、第 214–221 行才使用 ComfyUI `MiniMaxH3ReferenceToVideo.inputs.ref_images.ref_image_0..8`，且需要六段式结构化提示词（`subject_definitions` / `summary` / `retention_analysis` / `detailed_description` / `overall_soundscape` / `non_diegetic_music`）。
- 因此，只要把角色三视图设定图连给 `h3I2v`，ComfyUI 就会严格把这张多视图设定板当作 `0.00s` 首帧进行生成，导致视频开头或中间直接出现参考图帧。

#### 原因 C：前后端对 `singleImage` 模式的语义分裂与误导
1. **`videoGenerationNode/index.vue` 与 `authorization.ts` 逻辑不一致**：
   - [apps/web/src/nodes/videoGenerationNode/index.vue](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/web/src/nodes/videoGenerationNode/index.vue#L152) 中：
     ```ts
     const frameMode = computed(() => ["startEndRequired", "endFrameOptional", "startFrameOptional"].includes(String(selectedMode.value)));
     ```
     这里**没有包含 `"singleImage"`**，导致当节点选 `h3I2v`（`mode: ["singleImage"]`）时：
     - 前端不显示第 47–48 行的 `referenceHint`（“图片引用按顺序作为首帧、尾帧”）；
     - 第 225–227 行组装请求时把图片放进 `images` 而不是 `firstFrame`（靠 `h3Local3060.ts` 第 181 行的兜底 `if (mode === "i2v" && images.length === 1 && !first) first = images[0]` 才转回 `first_frame`）。
   - 而 [apps/agentRuntime/src/tools/canvas/authorization.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/tools/canvas/authorization.ts#L99-L108) 第 99–100 行却把 `"singleImage"` 算进了 `frameMode`（组装进 `firstFrame`）。
2. **`singleImage` 命名在多模型间歧义**：
   - 在 `h3Local3060` 中，`h3I2v`（首帧图生视频）使用 `mode: ["singleImage"]`，而 `h3R2v`（参考生视频）使用 `mode: [["imageReference:9", "videoReference:3", "audioReference:3"]]`。当画布连了 1 张图且目标要求“单图生视频”时，`getMatchingModes()` 对 `h3I2v` 命中 `singleImage`，极其容易诱使 Agent 和用户选错成首帧模型而非参考模型 `h3R2v`。

### 1.3 修复目标与建议方案

1. **完善 Skill 对本地 ComfyUI（H3 / Qwen2.1 / Breeze）的适配**：
   - 更新 `packages/skills/workflow`（并同步至 `data/skills/workflow`）、`canvas/references/videoProduction.md`、`h3-prompt-writing` 与 `minimax-h3-prompt-optimizer`，解除对云端 Seedance 的强制单一路由，显式增加**本地 ComfyUI 路由分支**：
     - 若模型为 `h3R2v`（或支持 `imageReference` 的多参模型）：可接角色三视图设定图、场景图、分镜参考图，使用 `Ref2VA` 六段式提示词（`ref-en.txt` / `minimax-h3-prompt-optimizer`）。
     - 若模型为 `h3I2v` / `h3Fl2v` / `h3L2v`（首/尾帧模型）：**硬性禁止直接接入角色三视图设定板或 2×2 道具参考板**；必须先经 `qwenImage21Edit`（或生图模型）生成该镜头的**单帧分镜首帧图**（或截取上一段视频尾帧），再把该单帧图连入视频节点，并使用 `I2VA` / `FL2VA` / `L2VA` 格式提示词。
   - 若画布已存在“分镜参考图”节点，视频采用首帧路线时应连入对应的单帧分镜参考图（或尾帧），而不是连入角色设定板。
   - 统一将 `h3-prompt-writing`、`minimax-h3-prompt-optimizer` 及更新后的 `workflow` 纳入 `packages/skills/` 与 `data/skills/` 同步体系。
2. **统一 `h3Local3060` 与前端节点的“首帧 vs 单图参考”语义**：
   - 核对 `h3I2v` 的 `mode` 与前端 `videoGenerationNode` 的 `frameMode` 语义：若 `h3I2v` 本质是“必须首帧”，应核对是否采用 `startFrameRequired` / `endFrameOptional` 等明确首帧语义或在 `node:getConfig` 返回中对每个模型明确标注 `inputSemantics`（例如区分“首帧锚点图”与“主体参考图”），并在 `videoGenerationNode` UI 与 `authorization.ts` 保持一致的 `frameMode` 判断。
   - 检查 `h3Local3060.ts` 中 `h3R2v` 在 3060 显存下接收 1~2 张参考图（如 1 角色设定 + 1 场景设定）时的可用性与提示词格式校验（注意 `h3Local3060.ts` 第 113–115 行要求若含 `subject_definitions:` 则必须完整包含六个字段）。

---

## 问题二：Agent 等待与长耗时调度策略问题（短时间停止等待、无法全自动完成整部影片）

### 2.1 实测现象与黄金测试证据

在黄金测试与日常长耗时测试中暴露出以下典型现象：

1. **Agent 默认不等待、直接停止并要求人工唤醒**：
   - 根据 [prompt.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/agent/runtime/prompt.ts#L30)、[workflow/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/workflow/SKILL.md#L1404) 与 [canvasExecution.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/workflow/references/canvasExecution.md#L86-L93)，`node:generateVideo` 调用后立即返回 `{ status: "generating" }`，规则明确要求 Agent“没有等待能力时结束本轮，告知用户完成后回复”。因此正常流程下 Agent 触发生成后即停轮，必须由用户手动发消息唤醒。
2. **即便要求 Agent 轮询等待，也会高频空转并在极短时间后主动放弃**：
   - 在黄金测试子 Agent 会话 [2026-11-07T04-29-20-429Z_01a1b444-a6ad-7271-8eac-358cdf2524c9.jsonl](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/workspaces/goldenSchoolRomance/.agent/sessions/2026-11-07T04-29-20-429Z_01a1b444-a6ad-7271-8eac-358cdf2524c9.jsonl) 中，由于没有 `sleep`/`wait` 工具，Agent **每秒调用一次 `node:getGenerationStatus`**（6 分钟内刷出上百轮工具调用）。
   - 更致命的是，在 `04:35:22.227Z` 的思考记录中可以看到模型自发判断：
     > *"I'm going to keep polling but reduce verbiage. If this continues much longer, I'll report blockage. Let me do ~15 more polls."*
     LLM 自身倾向于轮询十几次（约 15~30 秒）后就停止等待并上报阻塞。面对本地 ComfyUI 视频生成动辄 **5 分钟 ~ 1 小时** 的耗时，这种“靠 LLM 自己循环调状态工具”的策略必然过早退出或把上下文/Token 耗尽。
3. **长耗时链路上存在多道硬超时与人工弹窗阻断**：
   - 黄金测试中多次出现 `aborted`（如 `04:29:20` → `04:58:24` 中断、`05:13:46` → `05:23:36` 中断），且每次节点生成都被 `askUser` 阻塞等待点击。

### 2.2 根因排查（5 个层面的架构与超时瓶颈）

#### 瓶颈 A：工具层缺少“非 LLM 消耗的阻塞等待 / 任务完成唤醒”机制
- 查看 [apps/agentRuntime/src/tools/index.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/tools/index.ts) 与 [apps/web/src/nodes/nodeCore/toolDefinitions.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/web/src/nodes/nodeCore/toolDefinitions.ts)：
  - 当前仅有 `node:generateVideo`（异步启动、立刻返回 `{ status: "generating" }`）、`node:getGenerationStatus`（瞬时返回状态）与 `node:cancelGeneration`。
  - **不存在任何 `wait` / `sleep` / `awaitGeneration` 工具，也不存在“后台任务完成后自动触发 Agent 下一轮继续执行”的事件调度器**。
  - 若让 LLM 自行轮询 `node:getGenerationStatus`，每次查询都会触发完整 LLM 推理回合，在 1 小时的等待周期里意味着数千轮推理，不仅必然触发模型主动放弃、上下文爆炸或上游 API 限流/500 错误，也完全不具备工程可行性。

#### 瓶颈 B：Java 网关 `STREAM_TIMEOUT` 设死为 31 分钟总超时（必杀 1 小时本地生成）
- [apps/server/src/main/java/com/toonflow/server/proxy/AgentRuntimeClient.java](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/server/src/main/java/com/toonflow/server/proxy/AgentRuntimeClient.java#L32)：
  ```java
  private static final Duration STREAM_TIMEOUT = Duration.ofMillis(1_860_000);
  ```
- 在 JDK `java.net.http.HttpClient` 中，`HttpRequest.Builder.timeout(Duration)` **限制的是从请求发出到响应体读取结束的“总时长”，而不是空闲/首字节/单次读取间隔超时**！
- 尽管 `agentRuntime` 每 20 秒发送 `\n` 保活心跳，且 `data/providers/h3Local3060.ts`（第 190 行）与 `qwenImage21Local.ts`（第 103 行）分别设定了 **4 小时** 和 **2 小时** 超时，但只要单次 `/api/agent` 对话或单次 `/api/ai/media/generate` 超过 **31 分钟（1,860 秒）**，Java 网关就会强行超时断开连接并触发上游 `AbortController` 取消生成！（注：[docs/apiContract.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/docs/apiContract.md#L226) 第 2.2.3 节也写了 `1,860,000ms` 需同步评估修订）。

#### 瓶颈 C：Bun `agentRuntime` Express HTTP Server 与 `canvasCall` 存在隐式超时墙
1. **Bun `http.Server` 默认 `requestTimeout = 300,000ms`（5 分钟）**：
   - [apps/agentRuntime/src/index.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/index.ts#L8) 通过 `app.listen(...)` 启动的 Express server 未覆盖默认超时参数；实测该运行时下的 `server.requestTimeout` 默认值为 **`300000`（5 分钟）**、`keepAliveTimeout` 为 `5000`（5 秒）。长耗时流式响应（`/api/agent`、`/api/ai/media/generate`）必须核实并显式关闭/放开请求级总超时限制。
2. **画布桥接调用的 2 分钟 / 5 分钟超时**：
   - 后端 [apps/agentRuntime/src/agent/bridge/canvas.ts](file:///c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/agent/bridge/canvas.ts#L36) 单次 `canvasCall` 硬超时 **`300,000ms`（5 分钟）**；
   - 前端 [apps/web/src/pages/workspace/panels/canvas/useCanvasTools.ts](file:///c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/web/src/pages/workspace/panels/canvas/useCanvasTools.ts#L75) 单次 `call` 硬超时 **`120,000ms`（2 分钟）**。
   - 当前因 `node:generateVideo` 立刻返回所以未撞此限制，但若直接把 `node:generateVideo` 改为同步等待 1 小时，会立即触发 2 分钟 / 5 分钟超时失败。

#### 瓶颈 D：生成任务与 Agent 会话生命周期耦合在“前端浏览器单次长连接”上，不可断点续接
1. **前端组件卸载即中止任务**：
   - [apps/web/src/nodes/videoGenerationNode/index.vue](file:///c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/web/src/nodes/videoGenerationNode/index.vue#L243-L248) 的 `onScopeDispose` 会在切换画布、切换项目、刷新或关闭网页时调用 `generationController?.abort()`，直接取消正在排队/生成的本地 ComfyUI 任务。
2. **Agent NDJSON 连接与 `canvasCall` 强依赖当前打开的会话流**：
   - [apps/agentRuntime/src/routes/agent/index.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/routes/agent/index.ts#L52-L55) 在 HTTP 响应/socket 关闭时会立即执行 `controller.abort()` 并销毁 `bridge` 与 `questions`；
   - 而整部影片（如 8 段视频 × 15~60 分钟，或包含图片+配音+合成）的总耗时可能长达 **数小时**。完全依赖浏览器单一不间断 HTTP 长连接来维持整片制作，一旦浏览器休眠、页面刷新或网络微抖动就会前功尽弃。
3. **缺少 ComfyUI / 媒体后台作业状态台账**：
   - 当前没有服务端的 `jobId` 持久化与恢复机制（见 [w5T2Acceptance.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/docs/w5T2Acceptance.md#L130) 已知问题①：ComfyUI 任务丢失后只能等到最久 2~4 小时超时）。

#### 瓶颈 E：逐次 `askUser` 授权门控阻塞全自动批跑
- [apps/agentRuntime/src/tools/canvas/authorization.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/tools/canvas/authorization.ts#L19-L21) 与 [apps/agentRuntime/src/tools/mediaGeneration/authorization.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/tools/mediaGeneration/authorization.ts#L9-L42) 在**每次**调用 `node:generateImage`、`node:generateVideo`、`node:generateAudio`（以及直接生成工具）时，都强制弹出 `askUser` 卡片阻塞等待人工点击“确认生成”。
- 在整片全自动制作场景中（例如 8 条配音 + 14 张图 + 8 段视频 = 30 次生成），即使总执行方案已在一开始经 `askUser` 明确批准，后续每一步依然会停下来等人工逐个点击 30 次（在黄金测试子 Agent 中就出现了 `04:35:23` 触发 `askUser` 后无人及时点击、挂起 23 分钟后被取消的情况）。

### 2.3 修复目标与架构建议

建议按以下方向进行系统改造（注意涉及 `docs/apiContract.md` 与 `docs/newToonflowPrd.md` 边界变动的部分需同步评估更新基准文档）：

1. **引入“低开销阻塞等待 / 后台作业唤醒”机制（彻底替代 LLM 高频轮询）**：
   - **短期/轻量方案**：在 `apps/agentRuntime` 提供专用等待工具（例如 `waitForGeneration` 或 `awaitCanvasGeneration`，内部通过服务端定时检查或订阅完成信号阻塞等待，支持按 10s~30s 间隔静默等待、单次等待可配置长达数小时、中途不消耗 LLM 回合；前端或后端状态变化后立即唤醒返回最新 `status` 和 `outputs`）。同时更新 `workflow`、`canvasExecution` 与 `defaultSystemPrompt` 指导 Agent 在明确长等待任务时使用该等待机制，而不是“调用 15 次就放弃”或“立刻停轮等人唤醒”。
   - **中长期/健壮方案（推荐用于整片无人值守）**：将媒体生成下沉为 `apps/agentRuntime` 持久化的异步后台 Job（提交即返回 `jobId` + 持久状态，脱离浏览器组件 `onScopeDispose` 生命周期，ComfyUI `promptId` 可查询、可断线重连恢复），并支持在后台任务完成/失败后由 Agent 调度器**自动继续唤醒会话**推进下一镜（如抽尾帧 → 连线下一镜 → 生成下一镜 → 全部分镜完成后自动 `ffmpeg` 合成）。
2. **打通全链路长耗时超时限制（支持 1 小时+ 单任务及数小时整片流水线）**：
   - **Java 网关（[AgentRuntimeClient.java](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/server/src/main/java/com/toonflow/server/proxy/AgentRuntimeClient.java)）**：解除 `STREAM_TIMEOUT = 1_860_000ms` 对流式接口总时长的硬限制——改为使用基于“读取空闲超时 / 保活心跳检测”的流式读取（或显著放宽总超时 / 改用独立异步作业查询），确保在每 20s 心跳正常到达时，1~4 小时级别的本地 ComfyUI 生成与整片编排不会被 Java 网关在 31 分钟强杀。同步修订 `docs/apiContract.md` 第 2.2.3 节对应描述。
   - **Bun Runtime（[apps/agentRuntime/src/index.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/index.ts)）**：显式配置 `server.requestTimeout = 0`（或足够长的流式周期），避免 Node/Bun HTTP 兼容层 300s 默认请求超时中断长连接。
   - **节点生成耗时与状态查询隔离**：`canvasCall` 保持短时控制调用语义（不让单次 `canvasCall` 卡 1 小时去撞 120s/300s 超时），等待动作在 `agentRuntime` 内部或基于后端作业状态完成。
3. **协同解决“整片全自动执行 vs 算力授权门控”**：
   - 在遵循 PRD 算力安全防误刷原则的前提下，将 `authorizeMediaGeneration` / `createAuthorizedCanvas` 从“仅支持单次 1 节点 1 弹窗”升级为支持**“批次授权凭证（Batch Authorization Token / Manifest）”**：用户在开始前通过一次 `askUser` 卡片审阅并批准整批计划（明确锁定每个节点的模型、参数、提示词哈希、数量上限与总预估消耗，本地算力也可明确标注“本地 ComfyUI 算力，批次 N 次”），后台校验每次调用是否严格落在已批准批次清单内；仅当出现**超纲重抽、改模型/改提示词/改参数**时才再次弹 `askUser`。

---

## 附：关键核查文件索引

- **黄金测试现场证据**：
  - [樱花信笺_第1集_制作.json](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/workspaces/goldenSchoolRomance/樱花信笺_第1集_制作.json)
  - [主会话 JSONL](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/workspaces/goldenSchoolRomance/.agent/sessions/2026-11-07T03-18-17-203Z_01a1b403-9973-7271-8eac-358bbf3ab611.jsonl)
  - [子 Agent 轮询与阻塞 JSONL](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/workspaces/goldenSchoolRomance/.agent/sessions/2026-11-07T04-29-20-429Z_01a1b444-a6ad-7271-8eac-358cdf2524c9.jsonl)
  - [w5T2Acceptance.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/docs/w5T2Acceptance.md)
- **Skill 与提示词**：
  - [data/skills/workflow/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/workflow/SKILL.md) / [packages/skills/workflow/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/packages/skills/workflow/SKILL.md)
  - [data/skills/canvas/references/videoProduction.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/canvas/references/videoProduction.md)
  - [data/skills/workflow/references/canvasExecution.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/workflow/references/canvasExecution.md)
  - [data/skills/h3-prompt-writing/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/h3-prompt-writing/SKILL.md)
  - [data/skills/minimax-h3-prompt-optimizer/SKILL.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/skills/minimax-h3-prompt-optimizer/SKILL.md)
- **供应商与运行时代码**：
  - [data/providers/h3Local3060.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/data/providers/h3Local3060.ts)
  - [apps/web/src/nodes/videoGenerationNode/index.vue](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/web/src/nodes/videoGenerationNode/index.vue)
  - [apps/web/src/pages/workspace/panels/canvas/useCanvasTools.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/web/src/pages/workspace/panels/canvas/useCanvasTools.ts)
  - [apps/agentRuntime/src/index.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/index.ts)
  - [apps/agentRuntime/src/agent/runtime/prompt.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/agent/runtime/prompt.ts)
  - [apps/agentRuntime/src/agent/bridge/canvas.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/agent/bridge/canvas.ts)
  - [apps/agentRuntime/src/tools/canvas/authorization.ts](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/agentRuntime/src/tools/canvas/authorization.ts)
  - [apps/server/src/main/java/com/toonflow/server/proxy/AgentRuntimeClient.java](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/apps/server/src/main/java/com/toonflow/server/proxy/AgentRuntimeClient.java)
  - [docs/apiContract.md](file:///c:/Users/c1338/Desktop/MyWorkplace/toonflow/Toonflow-app/docs/apiContract.md)
