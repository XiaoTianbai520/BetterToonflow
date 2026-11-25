# 本地 ComfyUI 制作规范（Qwen-Image-2.1 + MiniMax H3 + Breeze TTS 2）

当用户指定本地 ComfyUI 套件（`qwenImage21Local` + `h3Local3060` + `breezeTtsLocal`）、工作区既有节点使用本地供应商，或当前环境仅配置本地 H3 模型时，严格执行本规范。**不进入 Seedance PART A，不反问“Seedance 2.0 还是 2.5”。**

## 1. 本地三套件能力边界

| 供应商 | 模型 ID | 适用用途 | 关键约束 |
|---|---|---|---|
| `breezeTtsLocal` | `breezeTtsDesign` | 角色音色设计与台词配音 | 纯文字声音描述造音色，原生 24kHz，不接收参考音频 |
| `breezeTtsLocal` | `breezeTtsClone` | 固定音色克隆配音 | 必须提供 1 段参考音频（无逐字稿时自动 Whisper 转写） |
| `breezeTtsLocal` | `breezeTtsDirection` | 固定音色 + 表演情绪导演 | 1 段参考音频 + 表演指令（耳语/激动/悲伤等） |
| `qwenImage21Local` | `qwenImage21Text` | 角色三视图设定板、空场景主视图、文生分镜图 | `mode: "text"`，不接收参考图，默认 1K（约 1MP） |
| `qwenImage21Local` | `qwenImage21Edit` | 基于角色/场景图生成单帧分镜首帧图、局部改图 | `mode: "singleImage"`，必须提供 1 张参考图 |
| `h3Local3060` | `h3T2v` | 纯文生视频（无参考图） | `mode: "text"`，不接收任何图片/视频/音频 |
| `h3Local3060` | `h3I2v` | **首帧图生视频（0.00s 画面锚点）** | `mode: "singleImage"`，**仅接收单帧分镜画面或上段尾帧；严禁接角色三视图设定板** |
| `h3Local3060` | `h3Fl2v` | **首尾帧生视频** | `mode: "startEndRequired"`，首帧 + 尾帧均为单帧画面 |
| `h3Local3060` | `h3L2v` | **尾帧生视频** | `mode: "startFrameOptional"`，仅提供尾帧（首帧留空） |
| `h3Local3060` | `h3R2v` | **参考生视频（身份/场景/风格参考）** | `mode: ["imageReference:9", "videoReference:3", "audioReference:3"]`，支持角色三视图与场景设定图 |

> 所有 `h3Local3060` 模型均联合生成原生音频，支持时长 `5 / 8 / 10 / 15` 秒、分辨率 `352p / 480p / 544p`、比例 `16:9 / 9:16 / 1:1 / 4:3 / 3:4`。

---

## 2. MiniMax H3 双轨视频制作规则（核心防错）

> **硬性禁令（HARD PROHIBITION）：绝对禁止将多视角角色三视图/四视图设定板、表情拼图或 2×2 道具设定板连入 `h3I2v`、`h3Fl2v` 或 `h3L2v`！**
> `h3I2v` 在 ComfyUI 中会将输入图片直接挂载到 `MiniMaxH3ImageToVideo.first_frame`（0.00s 首帧强锚点），接入设定板必然导致视频开头或中间直接出现三视图设定板画面。

根据是否有单帧首帧图，严格二选一：

### 路线 1：单帧分镜首帧图 / 上段尾帧接力 → `h3I2v` / `h3Fl2v` / `h3L2v`

适用：追求精确构图与镜头起止控制，或已通过 `qwenImage21Edit` 生成各镜头单帧分镜图 / 截取上段视频尾帧。

1. **先准备单帧画面**：
   - 先用 `qwenImage21Text` 生成角色三视图与场景设定图并固定采用；
   - 再用 `qwenImage21Edit` 以角色/场景设定图为参考，生成该镜头的**单帧完整电影构图分镜图**（无分格、无多视角拼板、无文字标注）；或从上一段已生成的视频截取真实尾帧。
2. **正确连线与选型**：
   - 将**该单帧分镜图（或上段尾帧）**连入视频节点，选用 **`h3I2v`**（`mode: "singleImage"`，首帧模式）或 **`h3Fl2v`**（首尾帧同时提供）。
   - 如果画布上同时存在“角色设定图”和“该镜单帧分镜图”，**只能连接单帧分镜图，绝对不能连角色设定图**。
3. **提示词格式（Base 三字段）**：
   - 必须读取 `h3-prompt-writing/references/base-en.txt`（中文优先润色可配合 `minimax-h3-prompt-optimizer`）。
   - 写入首帧对齐行 + 完整三字段：`integrated_multimodal_description`、`overall_soundscape`、`non_diegetic_music`。
   - `h3I2v` 首行对齐示例：`For the target video, at 0.00 seconds into the target video, <Picture 1> (from [Shot 1]) is fully referenced.`

### 路线 2：角色/场景设定图直连身份参考 → `h3R2v`

适用：不预先逐镜渲染单帧首帧图，希望直接把角色三视图设定板、场景主视图连入视频节点做外观与身份迁移。

1. **正确连线与选型**：
   - **必须选用 `h3R2v`**（`mode: ["imageReference:9", "videoReference:3", "audioReference:3"]`），绝不可选 `h3I2v`。
   - RTX 3060 12GB 显存下，单节点建议优先接入 **1–2 张最核心参考图**（如 1 张角色设定图 + 1 张场景设定图），确保稳定出片；若需接入参考视频（仅 MP4）或独立音频（仅 WAV），每段须在 2–15 秒且同类合计不超过 15 秒。
2. **提示词格式（Full-Reference 六字段）**：
   - **必须读取 `h3-prompt-writing/references/ref-en.txt`**（中文优先润色与质检配合 `minimax-h3-prompt-optimizer`）。
   - 必须包含完整六个字段（缺一不可，否则供应商直接报错）：
     1. `subject_definitions:` — 明确定义 `<Subject N>` 对应的 `<Picture N>`（说明是角色外观/服装身份参考或场景空间参考，**不复制设定板的多视图拼版布局与文字标签**）；
     2. `summary:` — 任务类型与核心参考关系；
     3. `retention_analysis:` — 逐项用保留词（`fully_preserved` / `partially_preserved` / `attribute_transfer` / `weak_reference`）声明保留哪些特征、排除拼版背景；
     4. `detailed_description:` — 按 `[Shot 1]`、`[Shot N] At MM:SS.mmm` 逐镜头写画面、物理化表演、自然语言运镜与 `<d>` 对白；
     5. `overall_soundscape:` — 环境声与物理动作声；
     6. `non_diegetic_music:` — 配乐描述或 `N/A`。

---

## 3. 本地显存与长耗时无人值守调度 SOP

本地 RTX 3060 运行 `qwenImage21Local` 与 `h3Local3060` 时，单张 1K 图像需数十秒至数分钟，单段 H3 视频（5–15 秒）耗时可达 **5–60 分钟**。全自动整集制作必须遵守以下调度规范：

### 3.1 严格按阶段串行（单次 1 个 ComfyUI 任务）

禁止同时向 ComfyUI 并发提交多个不同模型或重型视频任务（频繁卸载/重载 32B 编码器与 DiT 会显著拖慢速度并增加显存 OOM 风险）。按同模型聚批、分阶段串行推进：

1. **阶段 1 — 全集配音（`breezeTtsLocal`）**：搭建本集全部角色配音节点 → 一次性批次授权 → 逐节点串行生成并等待完成 → 核验音频。
2. **阶段 2 — 角色/场景设定图（`qwenImage21Text`）**：搭建资产图节点 → 一次性批次授权 → 逐节点串行生成并等待完成 → 固定已采用素材。
3. **阶段 3 — 单帧分镜首帧图（仅路线 1，`qwenImage21Edit`）**：以已采用设定图为输入搭建全部单帧分镜图节点 → 一次性批次授权 → 逐节点串行生成并等待完成。
4. **阶段 4 — 全部分镜视频（`h3I2v` / `h3Fl2v` / `h3R2v`）**：
   - 无尾帧依赖的同批视频节点全部搭建、连线、配参、填完 H3 提示词后，使用一次批次授权确认整批方案；
   - 严格逐个节点执行：**发起 `node:generateVideo` → 立即等待完成 → 检查结果 → 再启动下一个视频节点**。
   - 若采用尾帧接力（下一镜依赖上镜真实尾帧），先完成上镜生成并等待拿到真实视频，再截取尾帧、连线下一镜并继续推进。
5. **阶段 5 — FFmpeg 自动成片**：所有采用视频与配音就绪后，按 [FFmpeg 自动成片](ffmpegAssembly.md) 用 `ffprobe` / `ffmpeg` 探测、对齐、混音并拼接输出单集 `.mp4`。

### 3.2 批次一次授权（`authorizeCanvasGenerationBatch`）

- 在启动某一阶段的多节点生成前，先完成该批所有节点的创建、连线、`node:setConfig` 与 `node:setPrompt`。
- 调用 **`authorizeCanvasGenerationBatch({ nodeIds })`**（单批最多 32 个节点），通过一张汇总确认卡片让用户一次性审阅并确认整批模型、规格、提示词摘要与本地算力消耗。
- 用户点击“确认整批生成”后，会话内获得整批 SHA-256 指纹授权，随后逐个调用 `node:generateAudio` / `node:generateImage` / `node:generateVideo` 时将直接放行、**不再逐节点弹出 `askUser` 中断无人值守流程**。
- 仅当修改了已授权节点的提示词/模型/参数/参考文件，或发生失败后需要额外重试时，才重新确认授权。

### 3.3 零 LLM 消耗长等待（`waitForCanvasGeneration`）

- 对每个需要等待结果的本地 ComfyUI 生成节点，调用 `node:generate*` 收到 `{ status: "generating" }` 后，**立即调用**：
  ```json
  {
    "nodeIds": ["<当前节点ID>"],
    "mode": "all",
    "timeoutSeconds": 5400
  }
  ```
  - 图像/配音单节点建议 `timeoutSeconds: 1800`–`3600`；
  - H3 视频单节点建议 `timeoutSeconds: 5400`（1.5 小时），长时长（10–15s）或高分辨率（480p/544p）可设为 `10800`–`14400`（3–4 小时）。
- **严禁事项**：
  1. **严禁**在对话循环里反复调用 `node:getGenerationStatus` 轮询；
  2. **严禁**等待几十秒或几分钟后主观判定“太慢/卡住”就放弃等待并停轮叫用户唤醒；
  3. **严禁**在节点生成期间切换画布、删除或重建正在运行的生成节点（前端节点卸载会中止请求）。
- **异常快失败处理**：
  - 若 ComfyUI 崩溃、重启或队列被人为清空，本地供应商会在连续约 20–30 秒确认任务既不在 `/history` 也不在 `/queue` 后自动快失败并抛出 `ComfyUI 任务已丢失或队列被清空（...）`；
  - `waitForCanvasGeneration` 返回后，读取每个节点的 `status` 与 `outputs` / `error`：
    - `succeeded`：核对新输出路径非空且已更新，继续推进下一节点；
    - `failed` 或 `idle`：读取错误详情，定位原因（如模型/参数错误或 ComfyUI 服务重启），修正并重新确认授权后再试，绝不静默跳过或盲目死循环重试；
    - `timedOut: true`：仅在超过数小时上限仍未完成时出现，汇报已等待时长与当前状态，请用户检查本机 ComfyUI 控制台。
