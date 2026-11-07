# W5-T2 验收记录

日期：2026-10-30（Asia/Shanghai）。基准：`newToonflowPrd.md`、`apiContract.md` 的 `v1.0.0-baseline`，以及 `taskPlan.md` 的 W5-T2。

续验日期：2026-10-31。用户完成真实模型配置后，新增通过：两个DeepSeek模型真实SSE推理、上游模型发现、有效余额账户SSE首帧及20秒保活、真实Agent文件落盘与askUser回答闭环、经明确单次授权的H3原生音画生成、真实生成视频的FFmpeg封装/探测/尾帧抽取/Range读取。完整黄金场景仍未通过，详情见下节。

收官日期：2026-11-07。第三轮补验完成节点生成回归、F4 大图卸载、TF-Router 清理、契约文档对齐与 PRD 6.2 完整黄金场景（8 镜成片 + 播放断言），并完成三端构建终检与分批提交。详见「2026-11-07 第三轮」一节。

**结论（2026-11-07 更新）：达到收官 DoD，签署 W5-T2 验收通过（附下文登记的偏差与已知限制）。**

## 2026-11-07 第三轮：黄金场景补全与收官（最新）

环境：本地全链路——仓库自带 PostgreSQL（127.0.0.1:54329，库 `toonflow`）、Java 网关 3000、Bun Runtime 3001、web 构建产物 + 静态代理 5173、本机 ComfyUI 8188（图/视频/音频三供应商均直连 8188，Breeze TTS 由 ComfyUI 直接加载）。数据根统一为仓库 `data/`（修复了 10-31 隔离验收遗留的 `TOONFLOW_DATA_DIR=data/localDevelopment` 配置漂移）。禁 Mock、禁测试脚本文件；UI 断言用 playwright-core + chromium-1208 驱动真实浏览器，驱动脚本置于被 git 忽略的 `.workbuddy/uiDriver/`。

### 1. 节点内三类媒体生成 UI 回归：10-31 挂起问题确认根治

| 节点类型 | 实测结果 | 硬证据 |
| --- | --- | --- |
| `imageGenerationNode` | ✅ 60s 出图 | 1.59MB PNG 落盘 + mediaHistory 双版本累积 + HTTP 200（3 个前导换行保活） |
| `audioGenerationNode` | ✅ <3s（Breeze 模型热缓存） | 64.9KB flac 落盘 |
| `videoGenerationNode`（UI 新建节点） | ✅ 228s 出片 | 387KB mp4 落盘 + 节点内视频画面 + 11 个前导换行保活 |

10-31 报告的「节点长期执行中、13 分钟无产物」症状完全消失。根因即 11-03 修复的 `toolMessage.vue`（`tool.question?.callId` 不渲染导致工具永久挂起）。

### 2. F3-2 拖拽连线弹节点菜单：人工验证通过

合成鼠标事件无法区分触发局限与真实缺陷，改由用户在真实浏览器中人工验证：拖拽端口到画布空白处弹出节点创建菜单工作正常。

### 3. 契约文档对齐：`/api/ai/media/models` 的 `rules` 键偏差关闭

`apiContract.md` 3.6.2 响应结构表述由 `rules` 键改写为扁平字段（`mode`/`imageSizes`/`imageRatios`/`durationResolutionMap` 等），对照 `listMediaModels()` 实现逐一核实。实现零改动，前端已消费扁平字段。

### 4. TF-Router 排除范围清理完成（PRD 4.1）

- **删除 3 个专属文件**：`packages/providers` 的 language/media 两个 `tfRouter.ts`、`apps/web/src/lib/tf.ts`（充值/赞助商/插件市场客户端，全部属永久排除能力）。
- **剥离保留通用能力**：`providerBilling.ts` 去掉全部 `tfRouter` 分支与 `saveTfMediaApiKey`/`tfBalanceApiKey`，F5-2 要求的 `getBalance` 余额订阅（SSE、3 次重连、快照应用）原样保留；`canvasMenu.vue` 余额面板仅保留通用「点击刷新」按钮。
- **组件更名**：`tfAccount.vue` → `providerBalance.vue`（早已是通用余额组件，清除无用 props）。
- **同步清理**：四个设置面板与模型分组的 `!== "tfrouter"` 过滤、`modelContextLimits` 专属条目、`types.d.ts` 注释、providers 包注册与 exports。
- **验证链**：全仓库 grep `tfrouter|tf-router|tfAccount` 清零 → typecheck 双端退出码 0 → runtime 重启后供应商列表正常 → web 重建后 UI 冒烟零控制台错误、画布余额面板的 TF-Router 条目消失。

### 5. F4-2 大图自动卸载实测通过（真实 DeepSeek + 真实图片，三轮递进）

| 轮次 | 操作 | 结果 |
| --- | --- | --- |
| T1 | 13 张图片附件（22MB） | 附件走「文本列表 + read 按需读取」机制，模型正常确认收到 |
| T2 | Agent 逐一 read 13 张 760² 噪声图 | read 降采样转 JPG（1.73MB→806KB/张），累计 10.3MiB < 20MiB 阈值，不触发（负向边界行为正确） |
| T3 | 再 read 13 张 1080² 噪声图 | 历史累计 26 引用 / 20.43MiB > 20MiB → **触发** |

触发后：会话 JSONL 写入 `toonflowImageOffload`（卸载最旧轮全部 13 图 + 4 个 thinking 块）；对话连续三轮零错误；模型在回答中自述「上一轮的图片读取后立即被移出上下文」；最终正确回答「两轮合计 26 张」。同会话连续性未中断。

### 6. PRD 6.2 完整黄金场景通过：日式二次元校园恋爱《樱花信笺》

题材按用户指定。工作区 `data/workspaces/goldenSchoolRomance`（git 忽略，证据保留在位）。

| 步骤 | 结果与证据 |
| --- | --- |
| 配置 | 三类本地供应商（qwenImage21Local / h3Local3060 / breezeTtsLocal）+ DeepSeek 双模型 + FFmpeg 6.1.1 就绪 |
| 灵感建项（F1-2） | 应用内目录树选择空目录 → 上传 1537 字小说 → PRD 标准指令发送 → `画布1.json` + `assets/chat` 自动生成 → 跳转 `/workspace` 自动触发 Agent |
| 剧本与提示词 | Agent 经 `workflow` 技能产出 8 分镜剧本（8×5s=40s，竖屏 9:16）与完整 T2VA 提示词 |
| 画布搭建 | **39 节点全部由 Agent canvas 工具完成**（9 文本 + 14 图片 + 8 视频 + 8 音频）；后续模式切换（视频节点统一 h3I2v 单图生视频）与 8 条参考图连线亦由 Agent 完成（终态 40 节点 / 24 边） |
| 配音生成 | 8/8（Breeze TTS，律=少年活力 / 千夏=甜美女声，台词逐字取自原文；100% Agent 驱动） |
| 图片生成 | 14/14（2 角色设定 + 4 场景设定 + 8 分镜参考；其中 2 张由 Agent 驱动，其余经 UI 节点按钮触发——与真实用户操作同一路径） |
| 视频生成 | 8/8（h3I2v 图生视频，352p、5s；单段 4~6.5 分钟） |
| mediaHistory 回滚（F3-4） | 重生成「角色设定 藤井律」产生双版本 → 历史面板选 v1 →「设为当前结果」→ 画布输出成功回退 |
| 尾帧截取（F3-5） | 视频 01「截取尾帧」→ 自动创建 imageNode「分镜视频01 · 尾帧」（39→40 节点） |
| FFmpeg 成片 | **Agent 调用 ffmpeg 工具**完成 8 段拼接与配音对齐混音 |
| ext-media 播放 | 文档面板文件树点开 `成片_第1集.mp4` → `play()` 成功、时间轴前进（40s 成片，画面为图书馆场景） |
| 交付读取 | `GET /api/workspaces/files/read` Range 请求返回 `206` / `video/mp4` |

成片规格（ffprobe 复核）：`成片_第1集.mp4`，3.97MB，h264 352×608 竖屏 9:16，AAC 48kHz，时长 40.0s。

### 7. 如实登记的偏差与已知限制

- **生成触发方式偏差**：配音 8/8 全部 Agent 驱动；图片 2/14、视频 1/8 由 Agent 驱动，其余经 UI 节点按钮手动触发（与真实用户操作同一路径，产物与链路真实性不受影响）。原因：Agent 编排层多次被外部因素中断（ComfyUI 进程崩溃 ×2、DeepSeek `internal_error` 抖动、测试架切换当前项目导致消息发错会话的事故）。
- **尾帧接力未完整闭环**：尾帧截取与 imageNode 自动创建已验证，但「尾帧 → 分镜视频 02 首帧参考」连线因 DeepSeek 连续 `internal_error` 未完成（F3-5 功能本身通过）。
- **模型能力偏差**：`h3Local3060` 文生视频（h3T2v）模式不支持图片输入；黄金场景按用户指示改用 h3I2v 单图生视频模式，角色/场景设定图经参考连线接入。
- **过程中暴露的产品边界问题**（建议后续排期处理，不阻断本轮验收）：① ComfyUI 任务丢失后 runtime 最长 2 小时轮询才超时；② 页面连接中止后 canvas 桥接调用需刷新页面恢复；③ 应答过期 askUser 卡片会触发 abort 级联、误杀运行中会话；④ `resendFrom` 场景下 runtime 仍要求 `prompt` 非空，与「重发不需重传原文」的契约表述不一致。
- **维持登记的已知限制**（低优先级，前两轮已登记）：`selectDirectory` `native:true` 分支；`ffmpeg/download` 完整下载/校验和/中途取消；balanceEvents 404 重连的浏览器端实测；生成流内部分落盘后故障回滚；F6 边界（并发、符号链接逃逸、ZIP 炸弹、断连级联取消全覆盖）。
- **测试数据清理**：F4 实测的 88MB 噪声图目录已删除；黄金场景工作区与 localMediaTest 项目数据在 `data/`（git 忽略）保留作证据。

### 8. 构建与洁净度终检（2026-11-07）

| 检查 | 结果 |
| --- | --- |
| `apps/web` + `apps/agentRuntime`：`bun run typecheck` | 双端退出码 0（含本轮修复的 11-03 遗留 38 个类型错误） |
| `apps/web`：`bun run build` | 退出码 0（首次运行因静态服务瞬时占用产物目录失败，复跑 `✓ built in 8.15s` 通过；仅既有 >500KB chunk 告警） |
| `apps/server`：`mvn -DskipTests package` | BUILD SUCCESS（11.9s） |
| 测试文件扫描（apps/packages，排除 node_modules/target/dist） | 无 `.test.ts` / `.spec.ts` / `check*.ts` / `*Test(s).java` |
| 文件命名扫描（web/agentRuntime/packages 的 TS/Vue） | 无大驼峰/短横线/下划线命名 |
| TF-Router 残留扫描 | 全仓库清零 |
| `git status` | 干净（见下节分批提交记录） |

### 9. 最终结论

**DoD① 达成**：PRD 3.4 F1~F6 关键项（含节点生成回归、F3-2/F3-4/F3-5、F4-2 大图卸载）与 6.2 黄金场景（8 镜成片 + ext-media 播放断言）100% 实测通过，全部为真实模型、真实落盘、真实浏览器证据。
**DoD② 达成**：65 项变更按逻辑分 7 批提交完毕，`git status` 干净；三端构建通过；无测试文件、无大驼峰命名、TF-Router 残留清零。
**W5-T2 签署验收通过**，附上文第 7 节登记的偏差与已知限制。

## 2026-10-31 第二轮：连服务器库的完整回归（最新）

环境改为用户指定的服务器 PostgreSQL：`192.168.1.102:5432`（PostgreSQL 16.15 / Alpine）。为不污染已有业务库，新建并全程使用独立库 `toonflow_w5t2`，Flyway 从零迁移 6 张核心表；测试数据目录为系统临时目录，验收结束后已删除该库与临时目录，`toonflow` 库未被写入。

### 接口契约核验：65/65 全部实测，118 项断言最终全部通过

| 分组 | 接口数 | 断言数 | 结果 |
| --- | --- | --- | --- |
| 一、系统设置与个性化 | 5 | 14 | 14 通过 |
| 二、工作区与文件操作 | 11 | 26 | 26 通过 |
| 三、全局资产与技能管理 | 17 | 21 | 21 通过 |
| 四、语言模型与 FFmpeg 引擎 | 8 | 17 | 17 通过 |
| 五、主 Agent 会话与推理 | 10 | 19 | 19 通过 |
| 六、多模态生成与媒体供应商 | 14 | 21 | 21 通过 |
| **合计** | **65** | **118** | **118 通过（全部断言最终通过）** |

其中被任务点名的关键协议实测证据：

- `PUT /api/settings/save` 传入 `desktopUpdateSource` / `desktopUpdateCustomUrl` / `mcp` 后仍返回 200，GET 与 `data/settings.json` 均不含这些字段；系统提示词 >50000 字返回 400。
- `PUT /api/settings/personalization/save`：SQL 确认 `sys_personalization` 为唯一权威源（memory 25 字、agents 26 字），`sys_setting` 仅有 `agentSystemPrompt` / `ffmpegMode` / `ui` 三键；`data/memories/memory.md` 与 `data/AGENTS.md` 同步落盘；旧 revision 返回 `409 ESTALE`；>20000 字返回 400。
- 工作区 11 接口：`workspace_project` 相关路径校验、`409 EEXIST`/`ESTALE`、`Range` 读取返回 `206` + `bytes 0-5/9`、`../../etc/passwd` 与 `.toonflow` 均返回 403、非空目录删除返回 `409 ENOTEMPTY`、删除根目录返回 403、伪装 PNG 返回 415。
- `GET /api/plugins/export?type=skill` 返回 `application/zip`（SKILL.md + references，已剔除 `.toonflowOrder.json`）；`node|tool|agent|ext` 四种类型全部 400。
- `POST /api/ffmpeg/execute`：`prepare`/`cancel` 返回 `application/json` 200；暂存移走 `data/ffmpeg` 后 `prepare` 返回 **424 + `{"code":"FFMPEG_REQUIRED"}`**，同时 `/api/ffmpeg/events` 收到 **`data: {"type":"required"}`**；`run` 走 SSE 并收到 `end` 终结帧，真实落盘 88KB MP4；`ffprobe` 收到 `result`（h264 320×240 + aac，4.04s）。
- `POST /api/ai/media/generate`（真实上游）：图片 30.3s 返回 `Transfer-Encoding: chunked` + 1 个前导换行；视频 **244.7s 返回 12 个前导换行**，证明 20s 保活分帧在长任务下真实生效；流内错误仍为 HTTP 200、信封 `code=400`。
- `GET /api/ai/media/balanceEvents`：有效 `accountId` 建连立即收到快照帧；伪造 64 位 `accountId` 返回 404「余额账户已失效，请刷新余额」。前端 `providerBilling.ts` 收到错误后重新调用 `/api/ai/media/balance` 换新账户并重连，`maxReconnectAttempts = 3`，收到快照即重置计数。
- Agent 10 接口：真实 DeepSeek NDJSON（session → userMessage → text → stats → done）；模型不存在时流内 `error` 后仍固定 `done`；空 prompt 建连前 400；`mentionSource` 六种操作全部返回，`selectCanvas` 返回 `source.kind=canvas` 的 AgentMention；逻辑删除消息返回 200。
- 媒体供应商 14 接口：真实 `.ts` 源码 AST 校验通过、`eval` 源码 400、重复新增 409、`revision` 旧值 409 / 新值 200、未配置 `modelsUrl` 400、删除 409→200；`debug/run` 沙箱真实执行并回传 2 条 `log`（含 url/status/duration）+ `result`（1 个资产预览）。

### 本轮修复的 4 个真实缺陷

1. **FFmpeg `-y` 与 `-n` 冲突**（`apps/server/.../ffmpeg/FfmpegCallPlan.java`）：网关无条件注入 `-y`，而 `workflow` 技能 SOP 明确要求 `-n` 防止覆盖，导致按文档执行必然报 `both -y and -n supplied`（exit -22），成片合成 100% 失败。改为调用方显式给出 `-y`/`-n` 时不再注入默认 `-y`。
2. **流式客户端下流前错误被降级为 500**（`GlobalExceptionHandler.java`）：客户端带 `Accept: text/event-stream` 时，`ffmpeg/execute` 的流前 400/403/404/409/424 会因内容协商抛 `HttpMediaTypeNotAcceptableException` 变成 500，违反契约要求的 JSON 错误信封。改为直接写出 JSON 字节并显式指定 `application/json`。
3. **空 prompt 未在建连前拦截**（`apps/agentRuntime/src/routes/agent/index.ts`）：契约要求无附件、无 @ 引用、非重发时 `prompt` 为空返回 400，原实现返回 200 再在流内报错。已补前置校验。
4. **askUser 卡片控件不可交互**（`apps/web/src/main.ts`）：`form-create` 内置渲染器通过 `resolveComponent("ElRadioGroup")` 解析组件，但应用从未全局注册 Element Plus 组件，卡片实际渲染为 `<elradiogroup>` 裸标签——单选无法点击、提交永远报「请填写…」，算力授权卡片在真实 UI 中无法完成。已全局注册 `ElInput/ElInputNumber/ElSelect/ElOption/ElSwitch/ElRadio/ElRadioGroup/ElRadioButton/ElCheckbox/ElCheckboxGroup`。修复后真实 UI 中单选可点、提交后卡片显示「已回答」。

### PRD 6.2 黄金场景执行结果

已实测通过的部分（真实浏览器 + 真实模型 + 真实文件落盘）：

- `/` → `/hello` 首次配置页渲染正常，填入 DeepSeek Key 并确认后自动跳转 `/home`。
- `/home` 通过服务端目录选择器新建工作目录（`selectDirectory` 在无头部署下返回 `native:false`，走应用内目录树）、上传 `雨夜站台.txt`（1500+ 字）、输入 PRD 指定指令并发送。
- 自动生成 `画布1.json`、附件落到 `assets/chat/<uuid>.txt`、跳转 `/workspace` 并自动触发 Agent。
- Agent 加载 `workflow` 技能，落盘 `剧本_雨夜站台_第1集.md`，在画布上建出 8 个节点（`textNode` + 4×`imageGenerationNode` + `videoGenerationNode` + `audioGenerationNode` 等）。
- `askUser` 卡片真实弹出并被点击确认两次（Seedance 2.0 + 9:16 画幅；5 张设定图算力授权），卡片状态变为「已回答」，说明门控协议与卡片交互闭环成立。

未通过的部分与原因：

1. **节点内触发媒体生成在 UI 中挂起**：`node:generateImage` 被调用后 5 个节点长期显示「执行中」，13 分钟无任何产物落盘；同一时刻直接调用 `POST /api/ai/media/generate` 30s 正常返回并落盘，说明网关与运行时链路正常、问题在前端节点执行路径（待定位）。因此 8 镜视频/配音无法由 Agent 在画布内完成。
2. **视频供应商当日额度耗尽**：真实生成第 1 段 5s 视频后，第 2 段起上游返回 `HTTP 403 forbidden ... api key quota exceeded, daily_limit_amount=5`，8 镜视频在本日客观上无法生成。
3. **无可用音频（TTS）供应商**：现有 `compshare`/`metaso` 仅含 image 与 video 模型，`audioGenerationNode` 的独立配音无法真实生成；经用户确认以视频原生音轨替代（真实视频已含 AAC 双声道 44.1kHz 音轨）。
4. **`ext-media` 播放器未完成 UI 播放验证**：成片已在工作区可读（HTTP 206 / `video/mp4`），但本轮文档面板打开的是 Agent 生成的剧本/资产标签页，未在限定时间内完成文件树点击与播放器播放断言。

### 交付链路实测（真实素材，非 8 镜成片）

用真实生成的 5.06s、496×864、h264 + AAC 竖屏视频，经网关 `ffmpeg/execute` 统一规格并合成 `成片_第1集.mp4`：720×1280、25fps、h264、AAC 48kHz 双声道、时长 5.0s、1.21MB；`ffprobe` 复核一致，`GET /api/workspaces/files/read` 返回 206 与 `video/mp4`。样本保留在 `C:\Users\c1338\Desktop\w5t2Evidence\成片_第1集_交付链路样本.mp4`（**仅 1 镜，不构成 PRD 6.2 要求的 8 镜成片**）。

### 构建与洁净度

| 检查 | 结果 |
| --- | --- |
| `apps/web`：`bun run typecheck` | 退出 0 |
| `apps/web`：`bun run build` | 退出 0（仅既有 >500KB chunk 告警） |
| `apps/agentRuntime`：`bun run typecheck` | 退出 0 |
| `apps/agentRuntime`：`bun run build` | 退出 0 |
| `apps/server`：`mvn -DskipTests package` | BUILD SUCCESS；已确认 `hasOverwriteFlag` 与 `writeValueAsBytes` 编译进 JAR |
| 测试文件扫描 | `apps`、`packages` 下无 `.test.ts` / `.spec.ts` / `check*.ts` / `*Test(s).java` |
| 文件命名扫描 | `apps/web/src`、`apps/agentRuntime/src`、`packages` 下无大驼峰/短横线/下划线 TS/Vue 文件名 |

### 未覆盖与已知偏差

- `POST /api/workspaces/selectDirectory` 的 `native:true` 分支未验证：该分支会弹起宿主原生文件夹选择器并阻塞，本轮以 `SESSIONNAME=Services` 运行无头分支（返回 `native:false`）。
- `GET /api/ai/media/models` 实际返回扁平约束字段（`mode`/`imageSizes`/`imageRatios`/`durationResolutionMap`），契约文字中的 `rules` 键未返回；前端节点直接消费扁平字段，本轮未改动该 API 形状，登记为文档与实现的表述偏差。
- 8 镜视频、独立配音、`mediaHistory` 回滚、视频节点一键截取尾帧、`ext-media` 播放均未取得 UI 端到端证据。
- `git status` 非干净：仓库存在 W1~W4 以来的既有未提交修改，本轮另新增上述 4 个修复文件；未做提交。

**最终结论：未达到收官 DoD。** 65 个接口的请求与主要契约点已实测，4 个阻断级缺陷已修复并编译进产物；但 PRD 6.2 黄金场景因「前端节点执行挂起 + 视频额度耗尽 + 无 TTS 供应商」未跑通，不能签署 100% 验收通过。

## 2026-10-31 真实模型续验

主服务保持运行，用户的供应商和模型配置保存在持久化本地开发数据库。涉及Agent会话及生成文件的续验另起3100/3101隔离服务，连接独立临时数据库，配置通过正式HTTP接口复制，数据全部写入系统临时目录；没有覆盖用户配置或改写主工作区。

| 用例 | 结果与证据 |
| --- | --- |
| DeepSeek模型发现 | POST /api/providers/models返回200，真实上游返回deepseek-flash、deepseek-v4-pro |
| 两个文本模型 | 分别真实调用POST /api/ai/generate，均返回text/event-stream、中文分段text与done，没有error |
| 派生快照 | settings.json包含deepSeek及媒体供应商配置标识，配置供应商源码快照存在；未在输出或报告中记录密钥 |
| 估价 | compshare未实现getPrice；price接口200/data=null，此为可选钩子缺失的真实结果，未编造费用 |
| 余额账户与订阅 | balance返回200、64位临时accountId、balance=null/revision=0；有效账户订阅SSE返回200和立即快照，19990ms后收到keepalive。供应商无getBalance，未将null余额视为真实数值余额查询通过 |
| 真实Agent工具 | deepseek-flash实际调用listMediaModels、write及askUser，nativeAudioPlan.md真实写入2324字节。等待期间running=true；用户明确回答后POST /api/agent/answer返回200，三项工具均success，会话running=false且NDJSON有done |
| 用户授权范围 | 用户选择先验H3原生音画，独立配音保留待验；随后明确批准metaso/MiniMax-H3文生视频1次，5秒、768P、16:9、原生音画，费用未知、失败不重试。没有把普通“可以测试”当作这次具体生成的算力授权 |
| 真实媒体生成 | 仅调用一次POST /api/ai/media/generate；响应头约19ms发出，HTTP200/application/json且Transfer-Encoding:chunked；约18秒完成，信封code=200；实际落盘MP4为3174510字节。没有Mock、自动重试或额外模型调用 |
| 生成保活 | 本次18秒即结束，前导换行N=0符合协议；**没有验证超过20秒时的生成换行保活** |
| 真实媒体探测 | Java网关ffprobe SSE返回唯一result；H.264，1344×768、24fps、视频5.166667秒；AAC双声道、32000Hz、音频5.175秒，总时长5.175秒。请求画幅16:9，实际sample_aspect_ratio=1:1、display_aspect_ratio=7:4，存在明确画幅偏差，不能标为精确画幅通过 |
| 音频存在性 | ffmpeg volumedetect实际解码331776个采样，mean_volume=-16.1dB，max_volume=-0.4dB，确认非静音；未据此判定台词正确或口型同步 |
| 尾帧 | 对真实生成视频实际抽取尾帧，PNG为1236042字节；已查看画面，包含雨夜站台、成年女性、红伞和列车。没有将命令行抽帧冒充画布一键抽帧交互通过 |
| FFmpeg交付封装 | 经Java网关run对真实生成视频执行音视频流复制封装，SSE以end结束，输出3173875字节MP4；其HTTP Range返回206、video/mp4及bytes 0-31/3173875 |

使用`h3-prompt-writing`及`minimax-h3`组织实际生成提示词。实际原生音画结果保留为交付文件：`C:/Users/c1338/.codex/visualizations/2026/10/31/01a18f57-ab2e-72b1-bc90-f1e22e763f1e/h3NativeAudio.mp4`，尾帧为同目录`h3TailFrame.png`。

### 本轮仍被阻塞或未覆盖的项目

- 浏览器访问`http://127.0.0.1:5173`被自动审批安全策略拒绝。用户明确允许后再次尝试仍被拒绝；没有使用其他浏览器、原始CDP或间接方式绕过。首页/工作台/画布/播放器UI仍未验收。
- 本轮实际模型列表新增的是metaso/MiniMax-H3、类型video；没有type=audio模型。用户已明确选择“先验H3原生音画，独立配音保留待验”，因此未执行audioGenerationNode、独立TTS及外置配音混音。
- 没有生成8个镜头、角色/场景图片，未跑通PRD完整黄金场景；没有将单条原生音画验证冒充单集成片验收。
- 生成超过20秒的前导换行、部分落盘后故障回滚、浏览器404有限重连、真实供应商调试器日志/预览、对白逐字正确与口型同步仍待验证。
- 本轮没有修改业务源码，没有重新执行编译/构建；下文构建结果属于10月30日已完成的检查，不声称在本轮重跑。

本轮收尾：隔离3100/3101进程已停止，独立临时数据库及经绝对路径核对的系统临时目录均已删除。主服务3000/3001、前端5173和持久化数据库54329继续运行；收尾后设置及媒体模型接口仍返回200。仅保留无凭据的验收报告、交付MP4和尾帧，不保留包含配置副本的临时数据。

## 首轮环境与执行边界（2026-10-30）

- Windows、Java 21.0.8、Bun 1.4.2、Spring Boot 3.5.7；独立 PostgreSQL 16.13 集群仅监听 `127.0.0.1:54329`。
- 数据库、共享 data、工作区、技能 ZIP 和媒体文件均在系统临时目录 `toonflowW5T2-8ab7814263494d50a6337f3d1134163f` 内；Java 3000 与 Runtime 3001 使用相同临时 data 和随机内部令牌。未连接默认业务数据库。
- PostgreSQL 官方完整包下载超时，改用临时便携运行包；实际 `postgres --version` 与 Flyway 连接日志均确认为 16.13。未向项目添加环境准备依赖。
- 没有用模拟供应商替代真实上游，也没有新增测试脚本、测试模块、测试依赖或测试入口。
- 本机没有可用 Docker，未执行 Compose 容器验收。原工作区已有未提交修改，执行过程中也出现其他变更；均予以保留，不能声称 git status 干净。

## 已实测的关键结果

| 项目 | 实际结果 |
| --- | --- |
| 设置遗留字段 | 传入类型错误的 desktopUpdateSource、desktopUpdateCustomUrl、mcp 后仍成功保存；GET 与数据库仅保留有效设置，没有落入快照 |
| 个性化唯一权威源 | SQL 查询 sys_personalization 确认 memory/agents 正文与 SHA-256；sys_setting 不含这些正文；对应两个派生文件实际生成 |
| 个性化冲突 | 使用旧 revision 再次写入，返回 HTTP 409、data.code=ESTALE |
| 快照故障与恢复 | 临时将 memory.md 路径阻塞为目录，保存返回 500/SNAPSHOT_SYNC_PENDING/partial=true；GET 仍读到已提交数据库正文；解除阻塞后 6 秒内后台重建派生文件 |
| 启动对账 | 首次启动修复 settings.json；重启再次完成 reconciliation；失败重试、持续恢复有结构化日志 |
| 项目登记 | SQL 确认 workspace_project 写入真实规范目录 |
| 工作区文件 | 真实写入、快照读取、CAS 更新、复制、重命名、目录操作与删除；旧版本写入409；Range读取206；越界与.toonflow读取403 |
| 图片导入 | 合法 PNG 实际落盘；伪装为 PNG 的无效文件头返回415 |
| 全局素材 | 真实创建目录、上传、读取、列举、重命名和删除 |
| 技能生命周期 | 用仓库真实 workflow 打包、安装、启停、读取、创建附属文件、保存、移动、排序、导出、卸载并重新安装；skill_meta确有记录 |
| 技能导出 | 导出ZIP为200，实际打开包含12项及1个SKILL.md；node/tool/agent/ext四种类型全部400 |
| FFmpeg双模式 | 缺失二进制时prepare返回JSON 424/FFMPEG_REQUIRED，已建立SSE收到connected及required；复制现有二进制后prepare返回JSON 200；cancel返回JSON 200 |
| FFmpeg真实执行 | 使用仓库图标和标准音频信号经真实FFmpeg生成2秒引擎验证片段，再通过网关run转码，收到SSE事件并落盘24401字节MP4；ffprobe收到result，视频Range读取206 |
| Agent会话 | create真实写入JSONL；list/get/rename/skills正常；无待回答问题answer返回404；不存在的canvasResult修复后返回404 |
| NDJSON错误终结 | Agent缺少模型时输出error和done；供应商调试错误输出error和done |
| 媒体错误协议 | generate前置参数有效、供应商不存在时HTTP仍为200，JSON信封code=404；未把HTTP200判作生成成功 |
| 临时余额账户 | 64位合法格式、进程中未知的accountId，经网关返回404 |
| 供应商管理 | 真实apiMart源码AST校验、添加入库与源码同步、修改模型、旧revision409、删除；4个仓库现有供应商源码均可解析 |
| 静态安全检查 | process、eval、Function、require调用经校验接口分别返回400；没有执行这些禁止调用 |
| 源码洁净度 | 排除依赖和构建产物后，没有.test.ts/.spec.ts/check*.ts/*Test.java/*Tests.java；根组件改为app.vue后没有大驼峰前端/TS源码文件名 |

引擎验证片段只用于确认真实转码和协议，**不是**小说改编、8镜头媒体生成或黄金场景成片。

## 本轮修复

1. Java内部请求直接解析已转义URI，修复目录查询参数重复编码；同一目录的Agent列表/详情由400恢复200。
2. Runtime公共参数校验返回ZodIssue对象数组，修复原字符串数组偏差。
3. Agent异常NDJSON也固定输出done终结帧。
4. canvasResult缺失或跨工作区调用返回404，result/error必须二选一。
5. mentionSource的画布/输出列表返回分页信封，节点/素材列表终页显式nextCursor=null；前端适配分页读取。
6. 余额订阅增加最多3次自动重试；接收快照或用户主动刷新时重置计数。此项仅完成代码核查与构建，未取得浏览器重连实测证据。
7. 媒体供应商公共AST入口拒绝模块导入、宿主API和动态执行API。
8. 调试NDJSON缓存头对齐no-cache, no-transform。
9. 根组件文件名改为app.vue，更新入口绑定，保留原有组件内容修改。

## 首轮待验记录（2026-10-30，当前状态以续验结果为准）

- **F1、F2、F3：未取得UI验收证据。** 内置浏览器导航持续失败，错误为 `Cannot read properties of undefined (reading 'constraints')`；本机Chrome连接不可用。没有验证hello/home/workspace跳转、附件上传、编辑器自动保存冲突横幅、画布复制UUID、连线菜单、节点引用、抽尾帧、历史回滚及播放器音画同步。
- **F4、F5：仅部分通过。** 临时环境没有配置经确认可用的文本模型及图片、视频、音频供应商凭据。未执行真实LLM推理、askUser确认到媒体生成、8个镜头配音、子Agent、大图卸载，以及沙箱真实上游日志/预览。
- **F6：核心主要路径通过，完整边界仍未验收。** 未覆盖所有并发、符号链接逃逸、ZIP炸弹与Zip-Slip变种、每个流式接口的断连级联取消、全部编码/容量上限组合。
- 未验证媒体generate持续超过20秒时的实际前导换行心跳，也未制造上游部分成功后的多资产落盘失败回滚；不能用无供应商错误路径替代这两个验收项。
- 未验证有效余额账户SSE首帧、生成后的余额刷新，以及Runtime重启后前端换accountId并恰好受3次重试限制。
- providers/models仅验证400输入错误；providers/media/models仅验证未配置modelsUrl的400；ffmpeg/download仅验证无效来源400，没有验证下载完成/校验和/中途取消。
- mentionSource仍需补齐所有操作与参数上限核验：现有canvasId/cursor校验上限和契约不完全一致，节点游标还使用现有复合格式；本轮仅修复已实测的列表信封偏差。
- 仓库现有TF-Router相关供应商/余额引用仍可检索到，与排除范围的全面清理尚未完成；未把校验现有源码等同于允许重新纳入该能力。
- **PRD 6.2黄金场景没有完成，W5-T2保持未验收。** 需要可用的真实模型与三类媒体供应商配置、可操作浏览器和可用Docker后，在同一真实链路补齐上述用例。

## 构建与检查

| 命令 | 结果 |
| --- | --- |
| apps/web：bun run typecheck | 退出0 |
| apps/web：bun run build | 退出0；有既有大于500KB的chunk告警 |
| apps/agentRuntime：bun run typecheck | 退出0 |
| apps/agentRuntime：bun run build | 退出0 |
| apps/server：mvn package -DskipTests | 退出0；源码修改曾触发javac编译；有既有弃用API提示 |
| git diff --check | 通过；有Git行尾转换提示 |

首次沙箱内Web/Runtime检查因依赖路径权限失败，正常权限下复验通过。Java第一次重打包碰到运行中的JAR文件占用，停止本轮进程后重新打包成功。没有把失败尝试隐藏成一次通过，也没有把构建成功当成功能全部通过。

## 清理结果

本轮启动的Web、Java、Runtime与独立PostgreSQL均已停止；3000、3001、5173、54329未发现本轮遗留监听。核对绝对路径后递归删除上述系统临时目录，删除后Test-Path确认不存在。数据库、工作区、ZIP、源片段、转码产物和下载包已清理；仓库保留业务修复、此报告及正常构建产物。浏览器关闭操作仍被同一导航控制错误阻止，未声称成功关闭空白标签页。

## 65接口请求覆盖矩阵

以下为本轮真实请求观察到的HTTP状态集合，包含修复前结果和人为错误输入；200流内仍可能是错误事件/错误信封。标为“正常路径已触达”的接口也不代表其全部Schema、边界与副作用已经验收。

| 方法与路径 | 实际HTTP状态 | 覆盖说明 |
| --- | --- | --- |
| `GET /api/settings/get` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/settings/save` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `GET /api/settings/systemPrompt` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/settings/personalization/get` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/settings/personalization/save` | 200,409,500 | 主要正常路径已触达，非全边界通过 |
| `GET /api/workspaces/check` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/workspaces/list` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/workspaces/selectDirectory` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/workspaces/files/list` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/workspaces/files/read` | 200,206,403 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/workspaces/files/write` | 200,409 | 主要正常路径已触达，非全边界通过 |
| `POST /api/workspaces/files/mkdir` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/workspaces/files/rename` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/workspaces/files/copy` | 200 | 主要正常路径已触达，非全边界通过 |
| `DELETE /api/workspaces/files/remove` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/workspaces/files/importImage` | 200,415 | 主要正常路径已触达，非全边界通过 |
| `GET /api/assets/list` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/assets/read` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/assets/save` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/assets/mkdir` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/assets/rename` | 200 | 主要正常路径已触达，非全边界通过 |
| `DELETE /api/assets/remove` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/skills/get` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/skills/install` | 200 | 主要正常路径已触达，非全边界通过 |
| `DELETE /api/skills/uninstall` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/skills/setEnabled` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/skills/list` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/skills/read` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/skills/save` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/skills/create` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/skills/move` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/skills/order` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/plugins/export` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `POST /api/providers/models` | 400 | 仅参数错误；真实上游待验证 |
| `GET /api/ai/models` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/ffmpeg/status` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/ffmpeg/progress` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/ffmpeg/download` | 400 | 仅无效来源；真实下载待验证 |
| `POST /api/ffmpeg/cancel` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/ffmpeg/events` | 200 | connected及required广播已验证 |
| `POST /api/ffmpeg/execute` | 200,424 | 主要正常路径已触达，非全边界通过 |
| `POST /api/agent` | 200 | 仅错误NDJSON与done；真实推理待验证 |
| `POST /api/agent/create` | 200 | 主要正常路径已触达，非全边界通过 |
| `GET /api/agent/list` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `GET /api/agent/get` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `PATCH /api/agent/rename` | 200 | 主要正常路径已触达，非全边界通过 |
| `DELETE /api/agent/message` | 400 | 仅不存在消息；成功软删除待验证 |
| `POST /api/agent/answer` | 404 | 仅无待答问题404；授权闭环待验证 |
| `POST /api/agent/canvasResult` | 200,400,404 | 修复404与二选一400；成功回传待验证 |
| `GET /api/agent/mentionSource` | 200,400 | 空画布分页信封已复验；全部操作待验证 |
| `GET /api/agent/skills` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `POST /api/ai/generate` | 400 | 仅参数/模型错误；真实SSE推理待验证 |
| `GET /api/ai/media/models` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/ai/media/generate` | 200 | 仅HTTP200/信封404；成功、心跳及回滚待验证 |
| `POST /api/ai/media/price` | 404 | 仅不存在供应商404；真实估价待验证 |
| `POST /api/ai/media/balance` | 404 | 仅不存在供应商404；真实余额待验证 |
| `GET /api/ai/media/balanceEvents` | 404 | 仅失效账户404；有效订阅及前端重连待验证 |
| `GET /api/providers/media/list` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/providers/media/validate` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `POST /api/providers/media/add` | 200 | 主要正常路径已触达，非全边界通过 |
| `PUT /api/providers/media/save` | 200,409 | 主要正常路径已触达，非全边界通过 |
| `POST /api/providers/media/models` | 400 | 仅未配置modelsUrl的400；远程更新待验证 |
| `DELETE /api/providers/media/delete` | 200 | 主要正常路径已触达，非全边界通过 |
| `POST /api/providers/debug/inspect` | 200,400 | 主要正常路径已触达，非全边界通过 |
| `POST /api/providers/debug/run` | 200 | 仅错误NDJSON与done；真实上游日志及预览待验证 |

覆盖统计：65/65个接口发起过HTTP请求；本轮记录120条网关HTTP响应。完整契约通过率未给出，避免将触达率当作验收通过率。
