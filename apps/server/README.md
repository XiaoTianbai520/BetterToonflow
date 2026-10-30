# Toonflow Java Server（W1-T3）

Java 21、Spring Boot 3、MyBatis-Plus、PostgreSQL 16、Flyway 工程底座。此阶段不注册业务 Controller。

## 编译与运行

在本目录执行 `mvn package -DskipTests`，然后执行 `java -jar target/server-1.0.0-SNAPSHOT.jar`。
启动前准备 PostgreSQL 16 数据库与对应用户。Flyway 自动创建 PRD 7.3 的六张表；不写入演示数据，也不导入已有磁盘配置。

| 环境变量 | 默认值 / 说明 |
| --- | --- |
| `TOONFLOW_SERVER_PORT` | `3000` |
| `TOONFLOW_DB_URL` | `jdbc:postgresql://192.168.1.102:5432/toonflow` |
| `TOONFLOW_DB_USERNAME` | `root` |
| `TOONFLOW_DB_PASSWORD` | 空；部署时注入数据库密码 |
| `TOONFLOW_DATA_DIR` | `../../data`，相对于进程工作目录；容器部署应传入共享目录绝对路径 |
| `TOONFLOW_SNAPSHOT_MAX_ATTEMPTS` | `3`，单轮 1~10 次 |
| `TOONFLOW_SNAPSHOT_BACKOFF_MS` | `50`，首次退避 1~5000ms，后续指数递增，最高 5000ms |
| `TOONFLOW_SNAPSHOT_RECOVERY_DELAY_MS` | `5000`，待恢复队列轮询间隔，应大于零 |
| `TOONFLOW_SNAPSHOT_RECONCILIATION_DELAY_MS` | `60000`，全量对账间隔，应大于零 |

## 后续写服务接入

数据库写服务在 Spring 管理的事务内调用 `DataSnapshotSyncService.syncAfterCommit()`。
提交成功后回调启动同步；事务回滚不写文件。没有事务时，该方法立即从数据库已提交状态对账。
`afterCommit` 内使用独立、只读、可重复读事务读取相关权威表，避免复用尚未释放的原事务资源。
写服务仍负责请求校验、字段剥离、数据库 CAS 和事务边界；本模块不在 `sys_setting` 中写入任何记录。

快照对应关系：

| 权威数据 | 派生文件 |
| --- | --- |
| `sys_setting`、语言/媒体供应商表、`skill_meta` 禁用状态 | `settings.json` |
| `ai_media_provider.source_code`、`revision` | `providers/<fileName>` |
| `sys_personalization` 的 `memory` | `memories/memory.md` |
| `sys_personalization` 的 `agents` | `AGENTS.md` |

`settings.json` 保留 Bun 当前读取的 `{ settings, disabledSkills, toolConfigs }` 外壳。
供应商配置由独立表派生；`settings.providers` / `settings.mediaProviders` 为配置数据，
`settings.customProviders`（仅已启用语言供应商）与 `settings.mediaProviderConfigs` 兼容 Bun 现有读取路径。
顶层 `memory`、`agents`、`desktopUpdate*`、`mcp` 及 `personalization.memory/agents` 不导出。
禁止正文存入 `sys_setting` 的约束由后续业务写服务在入库前执行，不能仅依赖快照过滤。

## 同步与恢复语义

所有覆盖写入经 `PathSandbox`，比较原始字节 SHA-256，仅在缺失或不一致时重写。
同目录临时文件写完并 `force(true)` 后通过 `ATOMIC_MOVE + REPLACE_EXISTING` 替换，平台不支持原子替换时直接失败并重试，不降级覆盖。
媒体源码和个性化文档必须与数据库 revision 一致，否则记录失败并等待权威数据修复。
对账删除已不在数据库中的 `providers/*.ts` 和已不存在权威记录的个性化快照；不修改技能、素材和工作区数据。

首次启动通过 `ApplicationRunner` 全量对账。单轮重试耗尽后保留待恢复意图；后台调度持续重新读取数据库最新状态，直至成功。
队列将多个请求合并为全量对账代次，不保留旧文件内容；同进程串行化数据库读取和文件更新，避免旧重试覆盖新状态。
周期全量对账还覆盖进程在提交后回调前崩溃、外部文件修改等情形。单文件替换原子，多文件快照不提供跨文件原子事务。

请求触发的同步失败抛出 HTTP 500 业务异常，附加 `{ code: "SNAPSHOT_SYNC_PENDING", partial: true }`，明确数据库已经提交。
启动/后台失败记录日志和运行状态，保留队列继续恢复，不伪装为同步成功。
`getStatus()` 返回请求/完成代次、尝试/失败/修复计数、待恢复项目、最近成功对账时间及最近失败类型；此阶段不新增状态 HTTP 接口。
日志使用 `event=...`、`file=...`、`revision=...` 等字段，不记录配置正文、底层 SQL 或凭据。

## 文件入口

`PathSandbox.root` 的根目录必须由业务服务从已授权工作区或服务端配置中取得，不能直接接受请求中的任意绝对目录。
已有路径与已存在祖先通过 `toRealPath()` 校验；拒绝 `..`、`.toonflow`、符号链接及根外路径。
Windows 拒绝 ADS 与尾随点/空格的路径别名。原子写与 CAS 使用同路径公平互斥队列，空闲锁自动释放。
`writeIfRevision` 在锁内比较原始文件 SHA-256；已删除或版本不匹配时返回 409 / `ESTALE`。
数据库文本 CAS 仍须由后续业务服务在数据库更新条件中实现，不能用文件锁替代数据库并发校验。

## 依赖依据

工程固定使用已发布的 [Spring Boot 3.5.7](https://spring.io/blog/2025/10/23/spring-boot-3-5-7-available-now/)、
[MyBatis-Plus Spring Boot 3 starter](https://baomidou.com/en/getting-started/install/)（3.5.14）和
[Apache Commons Compress 1.28.0](https://commons.apache.org/proper/commons-compress/changes.html)。
JSONB 使用专用 TypeHandler 与 PostgreSQL `PGobject`，实体开启 `autoResultMap`；所有时间字段使用 `OffsetDateTime`。
