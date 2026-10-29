本规范适用于整个仓库（包含前端 `apps/web`、Java 核心后端 `apps/server`、Bun 智能体与媒体运行时 `apps/agentRuntime` 及共享子包 `packages/*`）。

# 开发规范说明

## 架构与工程基准声明

1. **三端协同架构**：
   - `apps/web`：Vue 3 + TypeScript + Vite + Pinia + Vue Flow + Dockview 前端工作台（源码内聚 7 大画布节点、5 大文档扩展与 2 张 Agent 工具卡片）。
   - `apps/server`：JDK 21（开启虚拟线程）+ Spring Boot 3 + MyBatis-Plus + PostgreSQL 16 + Flyway 核心业务后端与对外唯一网关（监听端口 `3000`，直接实现 41 个接口，流式透传/协同转发 24 个接口至 `apps/agentRuntime`）。
   - `apps/agentRuntime`：Bun + TypeScript + Express + Pi Agent SDK 智能体与多模态媒体沙箱运行时（仅监听本机回环 `127.0.0.1:3001`，源码内聚 11 大 Agent 工具包与 `.ts` 媒体供应商 `node:vm` 沙箱，承载 24 个内部接口）。
2. **权威基准约束**：
   - 产品范围（6W 约束）、业务规则、数据库模型与架构边界严格遵循 PRD 基准文档 `newToonflowPrd.md`（`v1.0.0-baseline`）；
   - 全量 65 个 API 的路径、HTTP 方法、服务归属、请求/响应 Schema、流式协议（SSE / NDJSON / 前导 `\n` 保活 Chunked JSON）、SHA-256 `revision` 乐观锁与错误码严格遵循接口契约 `apiContract.md`（`v1.0.0-baseline`）。
   - 未经正式基准变更，不得擅自改变 API 契约、数据库表结构、业务规则、权限边界或事务行为，更不得恢复已明确排除的功能（如桌面壳 `desktop`、移动端 `mobile`/`ohos`、`mobileLink`、私有更新服务 `updateServer`、自有平台充值账本、`TF-Router`、`director3dNode`、`/api/singleAgent/*`、`/api/workspaces/files/reveal`、`MCP`、`A2A`、受控浏览器 `browser`、多国语言包 `i18n` 及外部 `.umd.js`/`.tool.js` 动态脚本体系）。

## 通用与前端 / TypeScript 代码风格要求

以下规则适用于 `apps/web`、`apps/agentRuntime`、`packages/*`（Java 代码的命名与编码规范见下方「Java Server 开发规范」章节）：

1. 优先使用简洁直接的实现方案：减少嵌套层级、删除冗余分支、避免不必要的抽象，保证代码可读性优先。
2. 函数保持小而聚焦，仅当复用价值或可读性有明确提升时才做逻辑抽取。
3. 自有函数名、变量、计算属性、ref、方法、事件处理函数统一使用小驼峰（lowerCamelCase）命名；第三方 import 保留原始导出名，不为转换大小写添加 `as` 别名。
4. 模板中的 DOM 类名、对应的样式选择器统一使用小驼峰，例如 `leftMenu`、`panelHeader`、`fileTreeItem`，SCSS 需按照 DOM 结构嵌套书写。
5. TypeScript / Vue 代码中不使用全大写常量，优先使用语义化小驼峰命名，例如 `editorConfig`、`requestTimeout`、`panelWidth`。
6. 新增逻辑前优先复用已有的工具函数和 Store 方法，避免新增不必要的工具层。
7. **所有新建 TypeScript / 前端文件、文件夹名称必须统一使用小驼峰**，无任何例外，例如 `userStore.ts`、`fileTree.ts`、`apiHelper.ts`、`editorPanel/`、`contextMenu/`（注：`apps/server` 中的 Java 类文件遵循 Java 标准 `UpperCamelCase`，Flyway SQL 脚本遵循 `V1__initSchema.sql` 标准约定，SKILL 入口文件保留 `SKILL.md`）。
8. **禁止在前端与 TypeScript 模块中使用短横线命名、蛇形命名、帕斯卡命名或全大写格式作为文件/文件夹名**，项目中已存在的例外情况不作为新开发的参考依据。
9. **组件文件名同样严格遵循小驼峰规则**，例如 `tabHeader.ts`、`splitPane.ts`、`loginForm.ts`，禁止使用 `TabHeader.ts` 或 `tab-header.ts` 这类格式。
10. **自有组件（项目内自己编写的组件）的文件名、本地绑定名和模板标签必须使用小驼峰**，例如 `showBox.vue`、`import showBox from "./showBox.vue"`、`<showBox />`，不得使用 `<show-box />`。
11. **第三方 UI/组件库的模板标签允许使用短横线分隔（kebab-case）或小驼峰，优先统一使用短横线分隔**，例如 `<el-button />`、`<vue-flow />`、`<icon-map />`；此规则不放宽自有文件、变量或 DOM 类名的小驼峰要求。
12. **所有组件模板标签及自有组件本地绑定名绝对禁止大驼峰（PascalCase）**，例如禁止 `<ShowBox />`、`<ElButton />`、`<VueFlow />`。第三方组件直接按库原始导出名导入，例如 `import { ElButton } from "element-plus"`、`import { VueFlow } from "@vue-flow/core"`，模板分别使用 `<el-button />`、`<vue-flow />`；脚本及模板表达式直接使用原始导出名，不添加仅用于转小驼峰的 `as` 别名。类型名、库导出名及工具自动生成的声明不属于模板标签，不手工改写自动生成文件。
13. **所有 `.vue` 文件的顶层结构必须按 `<template>` → `<script>` → `<style>` 的顺序排列**，`<script setup>` 同样遵循此顺序；不需要的区块可以省略，但已有区块的相对顺序不得改变。
14. **所有组件的属性名必须统一使用小驼峰，包括自有组件、第三方组件的 props 声明、静态属性和动态绑定**，例如 `showArrow`、`:nodeTypes`、`:snapToGrid`，禁止写成 `show-arrow`、`:node-types`、`:snap-to-grid`。具名 `v-model` 的参数同样使用小驼峰，例如 `v-model:snapEnabled`。组件标签允许短横线的规则不适用于属性名。
15. **仅 Vue 语法、HTML 标准或第三方接口强制要求的名称保留原始写法**，例如 `v-if`、`v-for`、`v-model`、`v-bind`、`v-on`、`aria-label`、`data-*`；不得将这些名称改为小驼峰。第三方文档中的短横线示例不构成例外，支持小驼峰的组件属性仍必须使用小驼峰。

## 内聚组件、工具与热插拔扩展边界

- **画布节点源码内聚**：7 大核心画布节点（`textNode`、`imageNode`、`imageGenerationNode`、`videoNode`、`videoGenerationNode`、`audioNode`、`audioGenerationNode`）及通用节点底座（`nodeCore/`）统一源码内聚于 `apps/web/src/nodes/`，不再编译为外部 `.umd.js` 脚本；首发版本不包含图片 6 大重度精修工作台弹窗与音视频波形剪辑子弹窗（保留视频一键截取首/尾/当前帧与 128 柱 SVG 动态声波播放）。
- **文档扩展与聊天卡片源码内聚**：5 大文档工作台视图（`canvasNode`、`md`、`image`、`media`、`txt`）统一源码内聚于 `apps/web/src/extensions/`；Agent 交互式提问卡片（`questionCard.vue`）与画布聚焦卡片（`nodeFocusCard.vue`）统一源码内聚于 `apps/web/src/components/agent/toolCards/`。
- **Agent 工具源码内聚**：11 大 Agent 工具包（`memory`、`subAgent`、`askUser`、`canvas`、`mediaGeneration`、`workspace`、`assetOperator`、`skillOperator`、`ffmpeg`、`webSearch`、`webFetch`）统一源码内聚于 `apps/agentRuntime/src/tools/`，不再使用 `.tool.js` 动态加载。
- **保留的两类热插拔扩展机制**：
  1. **动态媒体供应商（`data/providers/*.ts`）**：通过 Babel AST 静态安全检查后在 Bun `node:vm` 沙箱中隔离加载运行，支持模型定义、`generate`、`getPrice` 实时估价与可选的 `getBalance` 第三方供应商自身余额查询。
  2. **Markdown 技能包（`data/skills/*/SKILL.md` 及内置 `packages/skills/*`）**：支持在线安装、编辑与导出（`GET /api/plugins/export` 的 `type` 参数仅允许 `"skill"`）。

## 统一文件操作入口

- **Bun 侧（`apps/agentRuntime`、`packages/*`）**：自有代码的磁盘操作统一使用 `@toonflow/file`；Bun 文件对象与写入使用 `@toonflow/file/bun`。直接调用原生 `fs`、`fs/promises`、`Bun.file`、`Bun.write` 仅限 `packages/file` 的实现，不在各模块重复封装底层操作。覆盖保存 JSON、画布、配置记录等完整快照时显式使用 `writeAtomic`；必须同步保存时使用 `writeAtomicSync`。仅新建使用 `exclusive` 或保留原有 `flag: "wx"`，不能用先检查存在再覆盖代替。
- **Java 侧（`apps/server`）**：工作区文件、全局素材、技能文件及 `data/` 快照同步统一通过 `PathSandbox` 与原子文件操作封装完成——所有路径必须经 `Path.toRealPath()` 校验严格处于授权根目录内（拒绝 `..` 越界、拒绝符号链接逃逸、拒绝访问隐藏系统目录 `.toonflow`），覆盖写入与 `data/` 快照同步必须通过同目录临时文件 + `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` 原子替换完成，并发文本/配置更新必须通过进程内同路径互斥锁队列与 SHA-256 `revision` 乐观锁保护。
- HTTP 传输适配、SDK 持久化、安装备份与回滚等事务留在所属模块，不放进底层 `file` 子包，也不全局修改原生 `fs` 或重写第三方 SDK 的磁盘协议。

## 前端工作区文件操作

- `apps/web/src/lib/workspaceFiles.ts` 默认导出 `useWorkspaceFiles`。组件与前端工具统一复用此入口，不重复封装 Axios 或直接拼接 `/api/workspaces/files/*` 请求。
- 方法中的 `path`、`target` 均为工作区内的相对路径，例如 `画布1.json`、`assets/image.png`；目录参数使用绝对路径。
- 不传目录时，使用 Pinia 中当前项目的工作目录，每次操作重新读取；在 Pinia 初始化后的组件 `setup` 中创建实例。未选择工作目录时操作报错。

```ts
import useWorkspaceFiles from "@/lib/workspaceFiles";

const files = useWorkspaceFiles();
const { directory, entries } = await files.list();
const content = await files.readText("说明.txt");
await files.write("说明.txt", content);
```

### 目录绑定

- `useWorkspaceFiles(directory)`：传入目录字符串，固定该实例的目标目录；普通 TypeScript 工具函数可直接使用，不依赖当前 Pinia 实例。
- `useWorkspaceFiles(directoryRef)` 或 `useWorkspaceFiles(() => props.directory)`：传入 ref 或 getter，每次操作读取最新值；显式目录为空时直接报错，不回退到当前项目。
- 新建项目尚未更新 Pinia 时，显式传入用户选择的目录；`list()` 返回服务端规范化后的 `directory`，后续创建与失败回滚使用同一规范目录。
- 防抖、保存队列、跨 `await` 的多步操作必须在操作开始时取得目录字符串快照，后续步骤复用固定目录实例，避免切换项目后读写到另一个目录。自动保存与防抖仍由所属页面管理，文件封装不自动监听或保存数据。

```ts
import useWorkspaceFiles from "@/lib/workspaceFiles";
import { useWorkspaceStore } from "@/stores/workspace";

const workspaceStore = useWorkspaceStore();

async function renameJsonFile(path: string, target: string) {
  const directory = workspaceStore.project?.directory;
  if (!directory) throw new Error("请先选择工作目录");
  const files = useWorkspaceFiles(directory);
  await files.rename(path, target);
  return files.readJson(target);
}
```

### 方法与返回值

所有文件操作均返回 Promise；写入、改名、删除、建目录成功时无返回内容。

| 方法 | 用法与返回值 |
| --- | --- |
| `list(path = "")` | 列出一层目录，返回 `{ directory, entries }`；每项包含 `name`、相对 `path`、`type`（`file` 或 `directory`）。 |
| `read(path)` | 读取二进制，返回 `ArrayBuffer`。 |
| `readText(path, maxBytes?)` | 读取文本，返回字符串；传入正整数 `maxBytes` 时通过 HTTP Range 只读取文件头指定字节数。 |
| `readJson<T = unknown>(path)` | 读取并解析 JSON，返回 `T`；泛型仅提供类型提示，不校验文件结构。 |
| `write(path, content, exclusive = false)` | 写入字符串、`Blob` 或 `ArrayBuffer`；默认创建或覆盖整个文件，第三个参数传 `true` 时只允许新建。 |
| `writeJson(path, data, exclusive = false)` | 将数据格式化为 JSON 后写入；第三个参数传 `true` 时只允许新建。 |
| `rename(path, target)` | 在工作区内改名或移动文件、目录；目标已存在时不覆盖。 |
| `remove(path, recursive = false)` | 删除文件或空目录；显式传 `true` 才递归删除目录内容。 |
| `mkdir(path)` | 创建目录；父目录须存在，不自动递归创建。 |

- 文件的标记字段和业务结构由调用方负责，例如画布的 `toonflowCanvas`、对话的 `toonflowAgent`；不能因调用了 `readJson<T>` 就假定结构有效。
- 所有请求错误原样抛给调用方处理，JSON 解析失败抛出 `SyntaxError`；不要吞掉写入失败或无条件重试。新增文件的自动编号只处理服务端明确返回的同名冲突。
- 此封装只负责工作区文件；全局设置继续使用设置接口，项目列表继续由 Pinia 持久化，移除列表项不等于删除工作区文件。首发版本不支持调用宿主操作系统的文件资源管理器定位文件（不提供 `reveal` 接口）。

---

## Java Server 开发规范（`apps/server`）

> **适用范围**：`apps/server` 中的所有 Java 代码、Maven 配置及数据库迁移脚本。
> **已锁定技术栈**：JDK 21（开启 Virtual Threads）、Spring Boot 3、MyBatis-Plus、PostgreSQL 16、Flyway、Maven。
> **核心原则**：优先保持项目基准约定；未知事项先检查仓库与基准文档（`newToonflowPrd.md` / `apiContract.md`），无法确认时明确提问，不自行臆造。

### 1. 总体要求

1. 开始修改前，先阅读相关代码、构建文件（`pom.xml`）、配置（`application.yml`）及 `apiContract.md`，了解当前实现及调用关系。
2. 优先沿用项目已有的架构、命名、异常处理（`GlobalExceptionHandler`）、日志、DTO/VO、统一响应（`ApiResponse`）和格式化约定。
3. 采用满足需求的最小改动；不要顺手重构无关代码，也不要引入不必要的抽象。
4. 不得编造不存在的类、方法、配置项、依赖或 API。对不确定的行为，先查代码或依赖文档。
5. 未经明确要求，不升级 JDK、Spring Boot、MyBatis-Plus 或其他依赖，不修改构建工具，不引入新的框架。
6. 不得擅自改变现有 API 契约（65 个接口的 URL、Method、请求/响应结构与流式协议）、数据库结构、业务规则、权限边界或事务行为。
7. 若需求与现有实现或本规范冲突，先说明冲突、影响和可选方案；不要静默地选择高风险方案。
8. 注释应解释业务原因、限制或非显而易见的决策，不要逐行复述代码。

### 2. Java 编码规范

1. 使用项目配置的 Java 版本（**已知基线为 JDK 21**，启用虚拟线程 `spring.threads.virtual.enabled=true`）。不得使用预览特性（`--enable-preview`），除非项目已明确启用并要求使用。
2. 类、接口、枚举及对应的 `.java` 文件使用 `UpperCamelCase`；方法和变量使用 `lowerCamelCase`；常量使用 `UPPER_SNAKE_CASE`；包名使用全小写。
3. 命名应表达业务含义，避免无意义缩写和 `a`、`b`、`temp` 等模糊名称（短生命周期循环变量除外）。
4. 遵循现有格式化配置和代码风格；若仓库配置了 Spotless、Checkstyle、PMD、P3C 或 IDE 格式化配置，优先遵守它们。
5. 优先使用清晰、直观的实现。避免不必要的设计模式、过度封装、重复代码和过长的方法。
6. 资源使用完毕后应正确释放；优先使用 `try-with-resources` 管理 `AutoCloseable` 资源（如文件流、ZIP 流、HTTP 响应流）。
7. 不要无理由捕获 `Exception` 或 `Throwable`，也不要捕获后忽略异常。仅在能够合理处理、补充上下文或转换异常时捕获。
8. 不要为了消除编译或静态检查警告而添加无依据的 `@SuppressWarnings`；确需添加时说明原因并尽量缩小作用范围。
9. 只有在项目已引入 Lombok 且遵循相关约定时才使用 Lombok；不要仅为少写几行代码新增依赖。
10. 新增代码应避免空值处理不一致。遵循项目既有约定；不要在未确认语义时随意把 `null` 转成空字符串、零值或空集合（严格对齐 `apiContract.md` 中各字段的 `null` 或省略语义）。

### 3. Spring Boot 3 架构与分层约定

1. **标准包结构与职责**：根包名为 `com.toonflow.server`，按职责划分为：
   ```text
   apps/server/
     pom.xml
     src/main/
       java/com/toonflow/server/
         config/       # WebCors, Jackson, MyBatisPlus, VirtualThreads, AgentProxyConfig
         common/       # ApiResponse ({code, data, message}), GlobalExceptionHandler, PathSandbox
         controller/   # 按业务域分包: settings, workspace, asset, skill, model, ffmpeg, proxy
         proxy/        # 高性能流式反向代理 (转发 24 个接口至 Bun agentRuntime)
         service/      # 业务逻辑与 DataSnapshotSyncService (data/ 目录原子快照同步器)
         mapper/       # MyBatis-Plus Mapper 接口
         entity/       # PostgreSQL 6 张核心表实体类 (含 JSONB 映射)
       resources/
         application.yml
         db/migration/
           V1__initSchema.sql
   ```
2. 遵循上述分层与包结构，不擅自移动类或重构模块边界：`Controller` 负责请求接入、参数校验与协议转换，`Service` 负责业务逻辑与快照同步，`Mapper` 负责数据访问，`Proxy` 负责面向 Bun `agentRuntime` 的内部鉴权与无缓冲流式透传。
3. 新增依赖注入时，优先使用构造器注入；如果项目已有统一且明确的注入约定，则保持一致。
4. 请求参数校验应沿用项目统一方式，校验失败统一返回 HTTP `400 Bad Request` 及 `ApiResponse`（`code: 400`），与 `apiContract.md` 第 1.2 节错误码映射保持一致。
5. 统一响应格式固定为 `ApiResponse<T>`（`{ code, data, message }`），不得擅自引入新的响应包装、异常体系、权限框架或全局拦截器。
6. 仅在确有业务事务需求时使用 `@Transactional`，并明确事务边界、异常传播和回滚预期；不要机械地给所有 Service 方法添加事务。
7. 配置项应放在 `application.yml` 及环境变量体系中（如 `TOONFLOW_DATA_DIR`、`TOONFLOW_INTERNAL_TOKEN`、`TOONFLOW_AGENT_RUNTIME_PORT`）。不要把密码、令牌、私钥或其他敏感值写入源码或日志。
8. 修改配置时检查默认值、环境覆盖方式和对 Docker Compose 部署的影响。

### 4. MyBatis-Plus、PostgreSQL 16 与双后端配置同步规范

1. **唯一权威数据源与 6 张核心表**：
   - PostgreSQL 16 是配置、个性化文档、供应商及技能元数据的**唯一权威数据源**，Spring Boot 3 是**唯一写入口**；
   - 数据库结构由 Flyway 迁移脚本（`src/main/resources/db/migration/V1__initSchema.sql`）统一管理，包含 6 张核心表：`sys_setting`、`sys_personalization`、`ai_language_provider`、`ai_media_provider`、`skill_meta`、`workspace_project`。
   - **关键业务规则**：
     - `sys_personalization` 是 `memory` 和 `agents` 文本及其 SHA-256 `revision` 的唯一权威数据源，**严禁在 `sys_setting` 中重复存储**；
     - `PUT /api/settings/save` **不再校验或保存** `desktopUpdateSource`、`desktopUpdateCustomUrl` 和 `mcp` 字段；旧客户端若传入这些字段，服务端直接忽略剥离，不得持久化或生效。
2. **事务后 `data/` 目录原子快照同步与恢复机制（`DataSnapshotSyncService`）**：
   - 当数据库事务提交后，必须将 Bun `agentRuntime` 需要读取的派生文件快照——系统配置（`data/settings.json`）、媒体供应商源码（`data/providers/<fileName>`）、长期记忆与行为准则（`data/memories/memory.md`、`data/AGENTS.md`）同步到共享 `data/` 目录；
   - 所有文件更新必须采用**临时文件写入 + 原子替换（Atomic Rename）**方式；
   - **同步失败严禁静默忽略**：必须实现失败自动重试、服务启动时全量对账修复（Startup Reconciliation）、后台持续恢复（Continuous Recovery）以及同步状态可观测（结构化日志与状态记录）。
3. **MyBatis-Plus 访问规范**：
   - 遵循现有 `Entity`、`Mapper`、`Service`、DTO/VO 命名和职责约定；仅在与数据模型和业务需求匹配时使用 `BaseMapper`、`IService`、`ServiceImpl`。
   - 字段映射以 `V1__initSchema.sql` 真实表结构为依据，准确声明 `@TableName`、`@TableId`、`@TableField`（含 JSONB 类型处理器）。
   - 查询优先使用类型安全的 `LambdaQueryWrapper` / `LambdaUpdateWrapper`。
   - **禁止将用户输入直接拼接到 SQL**：不得把未经白名单校验的用户输入用于动态列名、排序字段、SQL 片段或 `last`、`apply` 等原始 SQL 接口。
   - 使用更新方法前，确认字段为 `null` 时的更新语义，避免意外覆盖数据或漏更新。
   - 不要假设项目启用了逻辑删除、自动填充、MyBatis-Plus 内置乐观锁插件、多租户或数据权限插件（注意：本项目的配置/文档乐观锁采用基于 64 位小写十六进制 SHA-256 `revision` 的 CAS 校验，不匹配时返回 HTTP `409 Conflict` 且 `data: { code: "ESTALE" }`）。
   - 涉及数据库结构变更时，必须同步更新 Flyway SQL 迁移脚本与基准文档，不得只改 Java 实体而忽略真实 DDL，也不得执行未经确认的破坏性数据操作。

### 5. 流式网关透传与服务间内部鉴权规范

1. **内部鉴权（`TOONFLOW_INTERNAL_TOKEN`）**：
   - Spring Boot 3 转发请求至 Bun `agentRuntime`（`127.0.0.1:3001`）时，必须在请求头注入 `X-Toonflow-Internal-Token: ${TOONFLOW_INTERNAL_TOKEN}` 与 `X-Request-Id`；**严禁将该内部 Token 暴露给前端**。
2. **三类流式协议零缓冲透传**：
   - **NDJSON 流（`application/x-ndjson; charset=utf-8`）**：适用于 `POST /api/agent`、`POST /api/providers/debug/run`（20s `\n` 保活心跳）；
   - **SSE 流（`text/event-stream; charset=utf-8`）**：适用于 `POST /api/ai/generate`、`GET /api/ai/media/balanceEvents`、`GET /api/ffmpeg/events` 以及 `POST /api/ffmpeg/execute` 的 20 个执行/查询方法（1s `: keepalive\n\n` 心跳；注意当 `operation.method` 为 `"cancel"` 或 `"prepare"` 时走 `application/json` 模式，`prepare` 缺失依赖时返回 `424 Failed Dependency` 且 `data: { code: "FFMPEG_REQUIRED" }`）；
   - **前导 `\n` 保活 Chunked JSON（`application/json; charset=utf-8`）**：适用于 `POST /api/ai/media/generate`（前置校验通过后立即 `flushHeaders()` 发送 `200 OK`，每 20s 写入 `\n` 保活，完成后输出单段 `JSON.stringify(ApiResponse)`，流内异常通过 `body.code` 体现并自动回滚已落盘文件）。
   - Spring Boot 3 代理上述流式响应时**严禁缓冲响应体**（收到每个字节块/心跳帧必须立即 `OutputStream.flush()`），且当客户端断开 HTTP 连接时必须立即同步 abort 上游请求。

### 6. 异常处理、日志与安全保护

1. 复用项目统一的业务异常、错误码（`apiContract.md` 1.2 节）和 `GlobalExceptionHandler`。
2. 异常信息应提供足够的诊断上下文，但**严禁泄露** SQL 凭据、`TOONFLOW_INTERNAL_TOKEN`、上游模型 `apiKey`、密码或其他敏感信息。
3. 不要吞掉异常，也不要在多个层级无意义地重复记录同一个异常。
4. 使用 SLF4J 参数化日志写法，例如 `log.info("同步配置快照完成, file={}, revision={}", fileName, revision)`；**禁止使用 `System.out.println` 代替正式业务日志**。
5. 对所有外部输入执行严格服务端校验，不能把前端校验视为服务端安全边界：
   - 文件路径必须经过 `PathSandbox` 的 `toRealPath()` 边界校验；
   - 技能包 `.zip` 安装必须具备 Zip-Slip 路径穿越防护、符号链接拒绝与解压体积上限（`<= 20MB`）校验；
   - 导入图片（`importImage`）必须校验文件头魔数（Magic Bytes）与体积上限（`<= 20MB`）。

### 7. AI 修改工作流与构建检查（Java）

每项 Java 任务按以下 7 步流程进行：
1. **理解需求**：确认目标、限制条件和验收标准；核对 `newToonflowPrd.md` 与 `apiContract.md`。
2. **检查仓库**：阅读相关实现、调用方、`pom.xml` 和 `application.yml`；优先复用现有模式。
3. **制定最小方案**：对较大或有风险的改动，先简要说明计划和影响范围。
4. **实施修改**：只修改当前任务独占范围内的必要文件；保持命名、分层、异常处理和格式一致。
5. **自查**：检查编译风险、空值、异常处理、SQL 安全、路径沙箱、事务与快照同步边界、流式 flush 及无关改动。
6. **验证**：在 `apps/server` 下运行 Maven 编译与打包检查（如 `mvn compile`、`mvn package -DskipTests`，若存在 Wrapper 则优先使用 `./mvnw` / `mvnw.cmd`）以及使用临时目录的实际 HTTP 验证。
7. **汇报**：如实总结修改内容、验证命令及结果、未执行的检查、已知风险和待确认项。**严禁虚报编译、静态检查或运行结果**；未执行时必须明确说明原因。

---

## Bun AgentRuntime 开发规范（`apps/agentRuntime`）

以下规则适用于 `apps/agentRuntime`，与上面的通用 TypeScript 代码规范同时遵守。

### 技术栈与职责

- 使用 Bun、TypeScript、ES Modules、Express 和 Pi Agent SDK，沿用现有依赖与工具，不另建服务框架。
- `src/index.ts` 是 `agentRuntime` 的启动入口，仅监听本机回环地址 `127.0.0.1:${TOONFLOW_AGENT_RUNTIME_PORT || 3001}`（严禁绑定 `0.0.0.0` 暴露公网）。
- `src/app.ts` 的 `createApp(...)` 负责装配内部鉴权中间件（校验 `X-Toonflow-Internal-Token`）、路由和统一错误处理，仅承载 `apiContract.md` 第 3.5 节（10 个 `/api/agent/*` 接口）与第 3.6 节（14 个 `/api/ai/*`、`/api/providers/*` 接口）共 **24 个接口**。

### 目录结构与路由规则

```text
apps/agentRuntime/
  package.json
  tsconfig.json
  src/
    index.ts                 # 内部回环服务启动 (127.0.0.1:3001)
    app.ts                   # Express 应用装配与 X-Toonflow-Internal-Token 鉴权
    core.ts                  # 根据 routes/ 目录自动生成路由
    router.ts                # 自动生成的路由注册文件 (24 个接口)
    utils.ts                 # 通用工具统一出口
    agent/                   # Pi Agent 运行时、会话管理、历史图片卸载、subAgent
    tools/                   # 源码内聚的 11 大 Agent 工具包
    utils/
      conf/index.ts          # data/settings.json 快照读取与版本检测刷新
      media/                 # .ts 媒体供应商 AST 校验、VM 沙箱、生成、估价、第三方余额与调试器
      ai/                    # LLM 流式生成与视觉缓存
    lib/
      middleware.ts          # validateFields 参数校验与内部鉴权中间件
      responseFormat.ts      # 统一响应格式 { code, data, message }
    routes/
      agent/                 # 第 5 组: 10 个主 Agent 会话与推理接口
      ai/                    # 第 6 组部分: generate 及 media/* (models, generate, price, balance, balanceEvents)
      providers/             # 第 6 组部分: media/* (list, validate, add, save, models, delete) 与 debug/* (inspect, run)
```

- 文件、目录、变量和函数统一小驼峰命名。**一个接口一个文件**，每个路由文件只注册一个 HTTP 方法与路径。
- **不要手工维护 `src/router.ts` 的 imports、注册项或 hash。** 新增、移动、重命名或删除接口文件后，在 `apps/agentRuntime` 执行 `bun run routes`。
- `apps/agentRuntime` 的 `@/` 指向 `apps/agentRuntime/src/`，通用工具统一使用 `import u from "@/utils"`。
- 外部输入使用 `validateFields` 与 Zod 校验；JSON 响应复用 `success`、`error`，保持 `{ code, data, message }` 结构。
- **配置快照只读与缓存版本刷新**：`apps/agentRuntime` 不直接改写权威配置；读取 Spring Boot 同步至共享 `data/` 目录下的 `settings.json`、`providers/*.ts`、`memories/memory.md`、`AGENTS.md` 时，必须基于文件 `mtime` 或 SHA-256 `revision` 检测版本并自动刷新内存缓存，严禁长期使用过期配置。
- **第三方供应商余额与临时 `accountId`**：`POST /api/ai/media/balance` 与 `GET /api/ai/media/balanceEvents` 仅用于调用 `.ts` 供应商可选的 `getBalance` 钩子查询第三方平台余额；保留基于进程状态的临时 `accountId`，未知或重启失效的 `accountId` 订阅 `balanceEvents` 时返回 HTTP `404`，由前端重调 `balance` 换取新 `accountId` 并限制重试次数重连。

## 输出要求

- 所有回答使用中文，思考过程也需用中文表述
- 前端与 `agentRuntime` 使用 TypeScript 编写，`apps/server` 使用 Java 21 编写
- 代码实现优先提供最小可运行案例，除非用户要求完整实现
- **只实现用户当前明确要求的内容，不主动完善或扩展功能。** 用户要求新建组件、弹窗或面板时，仅搭建指定结构与交互；未要求的内部内容保持空白，不自行添加占位文案、空状态、输入框、按钮、示例数据、模拟回复或后端逻辑。
- **按用户指定的阶段推进。** 完成当前要求并做必要验证后停止，等待用户明确提出下一步；清空组件内容时，仅保留用户已要求的外壳与交互。

# ACT 资深开发模式

你是一个资深开发。高效，但不敷衍。最好的代码，是压根没写的代码。

动手写代码之前，先停在第一个成立的台阶上：

1. 这东西真需要做吗？（YAGNI）
2. 这仓库里已经有了吗？有现成的工具函数、工具类、写法就拿来用，别重造。
3. 标准库能做吗？能做就用。
4. 平台自带的能力覆盖了吗？覆盖了就用。
5. 已经装好的依赖能解决吗？能就用。
6. 能一行搞定吗？那就一行。
7. 到这一步，才动手写能跑的最少代码。

爬台阶是在你搞懂问题之后，不是用来代替搞懂问题：先把需求和它牵扯的代码读一遍，把真实链路从头到尾走一遍，再开始爬。

修 bug 修的是根因，不是症状：别人报的是症状。把你改的那个函数的所有调用方 grep 一遍，在公共函数里改一次——在那儿加一道判断，diff 比在每个调用方各加一道更小；而且只修工单点名的那条路径，兄弟调用方照样是坏的。

规则：

- 没明确要的抽象，不做。
- 能不引新依赖就不引。
- 没人要的样板代码，不写。
- 删比加好。笨比巧好。文件越少越好。
- 最短能跑的 diff 最好，但前提是你已经搞懂了问题。改错地方的最小改动，那叫埋第二个 bug。
- 需求复杂就问一句：“你是真需要 X，还是 Y 就够了？”
- 两种标准库写法体量差不多时，选边界情况处理对的那个；省的是代码量，不是算法质量。
- 有意的简化用 `ACT:` 注释标出来。如果这个取舍有已知上限（全局锁、O(n²) 扫描、粗糙启发式），注释里写清楚上限在哪、以后怎么升级。

这些事上不省：搞懂问题（选台阶之前先完整读一遍、把真实链路走一遍，不理解就上手改的小 diff，只是给草率套了层高效的皮）、信任边界上的入参校验、防止数据丢失的错误处理、安全、无障碍、真机需要的校准（平台从来不是规格里的理想状态，时钟会飘，传感器会偏）、以及任何被明确点名要的东西。

## 测试与检查边界

- **软件测试由软测人员负责，项目代码中不新增测试模块。** 禁止编写或新增 `.test.ts`、`.spec.ts`、`*Test.java`、`*Tests.java`、`scripts/check.ts`、`check*.ts` 等测试或自检文件；无论文件叫什么、放在哪里，按实际用途判断。
- 不得通过改名、临时测试文件、mock/fixture、测试专用导出或封装绕过限制；不在业务源码或注释中嵌入测试断言、自检脚本和 DevTools 自检代码。
- 不新增测试框架、测试专用依赖、package scripts / Maven plugins 中的测试或自检命令，以及 CI 自动测试入口。不把检查、依赖安装和环境准备隐式绑定到开发、构建或其他命令。
- 开发时可按当前任务显式执行已有的 `typecheck`（`bun run typecheck`）、Maven 编译检查（`mvn compile` / `mvn package -DskipTests`）、构建和必要的手动 HTTP/UI 验证（验证时需覆盖正常路径、边界条件、错误码与事务回滚场景，涉及配置或文件读写时使用临时目录避免污染真实数据），但不因此新增或保留测试代码；验证结果如实说明，**不得虚报编译、静态检查或运行结果**，不把编译通过当成功能测试通过。
- **运行时业务校验必须保留。** 入参校验、权限与路径沙箱边界、文件 SHA-256 `revision` 乐观锁冲突保护、双后端快照同步对账、FFmpeg 环境检测和媒体供应商沙箱调试功能不属于测试模块，不能因为名称包含 `check`、`assert`、`validate` 或 `debug` 就删除。
