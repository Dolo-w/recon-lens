# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 新增

- **自定义对账维度（声明式）**：`left`/`right` 新增 `dimension`（列名列表或任意 SQL 表达式，引擎统一生成为键列 `__biz_key`）、`dimensionSeparator`、`measures`（`别名: SQL 表达式`，声明后就是聚合维度，引擎先 `GROUP BY` 再比对）、`where`。
  `dimension` 与 `keyField` 二选一，声明维度时不需要（也不允许）写 `orderBy`；复合键按 `CONCAT` 拼接，单列维度直接用列名以命中索引
- **规则库与热更新**：规则存 `recon_rule` 表（`code` + 自增 `version`），新增规则 CRUD —— `GET /api/rules`、`GET /api/rules/{code}`（含版本历史）、`POST /api/rules`（JSON 或 YAML，提交即生效、不用重启）、`DELETE /api/rules/{code}`（停用，历史版本保留）
- **任务钉住规则版本**：`recon_job` 增加 `rule_version` + `rule_snapshot`，任务把当时生效的规则快照留在自己身上；重跑与补归因都走快照，改规则不会让历史任务换一种结果
- **请求级内联规则**：`POST /api/jobs` 支持 `rule` / `ruleYaml` 字段，MCP `recon_run` 支持 `ruleJson`（不落库、不产生版本）
- **MCP 工具 6 → 7**：新增 `recon_define_rule`（Agent 可自己提交一版新规则），`recon_run` 支持内联规则体
- **预处理物化模型表（MCP 工具 7 → 8）**：新增 `recon_build_model` 工具与 `POST /api/models/{ruleCode}`、`GET /api/models` —— 按规则声明的粒度把两侧数据提前算成模型表（`recon_model_<规则>_l/r`），并自动注册一条读模型表的 `<CODE>__MODEL` 规则；之后每轮对账只读模型表，差异口径与原规则逐条一致（走的是同一套取数 / 比较 / 归因代码）。1000 万行宽表实测每轮 21.47 s → **7.8 ms**（模型表 5 MB vs 源表 4.2 GB），第二次对账就回本（见 `docs/benchmark.md` §3.10）。实现要点：影子表 → 改名切换（构建期间旧模型表仍在服务）、`recon_model` 登记粒度 / 行数 / 构建耗时 / `created_at`、**不建主键**（行级规则本来就允许重复业务键，重复要留给引擎报 `DUPLICATE_KEY`；聚合维度也可能有 NULL，加主键会在构建期直接失败）、分片桶落成生成列 `biz_key_bucket` + 索引（MySQL `CRC32(...) % n`；H2 没有 `CRC32`，用 `MOD(ORA_HASH(...), n)` —— H2 的 `ORA_HASH(expr, n)` 返回的是 0..n 共 **n+1** 个值，不取模会把落在第 n 个桶的行两侧都漏掉，而且一条差异都不会报）。测试 68 → 79 个
- **声明式维度演示**：新增 `rules/resource-recon.yml`（复合维度：账号 + 资源ID）与 `rules/customer-status-recon.yml`（聚合维度：客户 + 状态）及配套演示数据；测试 40 → 79 个
- **文档守卫测试**：`MermaidDocsTest` 扫根目录与 `docs/` 下所有 markdown 的 ```` ```mermaid ```` 块，禁掉会破坏 GitHub 渲染的花括号（`{shards}` 这种标签文本；合法的 diamond 节点 `A{判断}` 放行），图数为 0 时同样报错。起因：README 架构图的边标签里写了 `{shards} {index}`，mermaid 把 `{` 当成 diamond 起始符，GitHub 上整张图渲染失败（`Parse error ... got 'DIAMOND_START'`）
- 修复：单列维度不再生成 `CONCAT(order_no)`（H2 直接报错），单列直接用列名
- 修复：`left.where` / `right.where` 之前"解析了但没人用"（静默无效），现在与分片条件是 **AND** 关系真正生效；写了 `{index}` / `{shards}` 占位符会在解析期报错（分片条件只能写 `shard.whereLeft/whereRight`）

### 变更

- **MCP SDK 0.18.4 → 2.0.1**：上游 2.x 把制品拆成 `mcp-core` + 各 JSON 实现，聚合包 `mcp` 只带 Jackson 3；
  本项目跑在 Spring Boot 3（Jackson 2）上，因此显式依赖 `mcp-core` + `mcp-json-jackson2`（代码零改动，
  `io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper` 包名未变，依赖树里也不会多出一套 Jackson 3）。
  协议版本随之从 `2024-11-05` 升到 `2025-06-18`：客户端请求新版本不再回落、不再打 WARN。
- **CI 与文档统一走仓库自带的 `./mvnw`**（wrapper 分发 Maven 3.10.0）：本地和 CI 用同一套 Maven，贡献者只要有 JDK 17 就能构建。

### 修复

- **游标打开失败时的连接泄漏**：`JdbcRowCursor` 在取连接/建语句抛异常时没有归还 `Connection`，压力一上来就把连接池占满（实测 8 分片 × 左右两个游标 = 16，正好占满默认池，日志里是 `SQLTransientConnectionException ... total=16, active=16, idle=0, waiting=7`）。新增 `JdbcRowCursorTest` 覆盖这条路径。
- **`error_msg` 超长导致任务落不了库**：`ReconRepository.finishJob` 落库前不截断（`markShardFailed` 是截断的），失败原因一长 MySQL 就报 `Data too long for column 'error_msg'`，`POST /api/jobs` 直接 500、任务状态没落库。现在统一截到 1000 字符，失败原因最多拼 3 条并附 `共 N 个分片失败`；回归测试 `ReconEngineEndToEndTest.overlongFailureReasonIsTruncatedInsteadOfBlowingUp`。
- **连接池默认值 16 → 24**：每个分片同时持有左右两个游标，8 分片就把 16 条连接占满，写差异 / 写分片状态只能等到超时再重试 —— 结果仍然全对，但耗时涨 6~11 倍（实测 2,329 ms → 13,992 ms，1,083 ms → 11,928 ms）。默认值改成"至少 `2 × engine.parallelism` 再加余量"。
- **MySQL 建表脚本**：新增 `src/main/resources/schema-mysql.sql`（索引写进 `CREATE TABLE`，MySQL 8.0.29 上从空库连跑两遍验证通过）。原来的 `schema.sql` 里有 `CREATE INDEX IF NOT EXISTS`，MySQL 不支持，README 又让 DBA 照着它建表 —— 照着跑会停在那两行索引语句上。

### 文档

- **`docs/benchmark.md` 文首新增「结论速览」**：一行一句话 + 关键数字的速查表（分片不改结果 / 内存解耦 / 排序成本由行宽决定 / 覆盖索引才消排序 / 分区裁剪是"读得更少" / 倾斜由最重分片决定 / 连接池 ≥ 2 × parallelism 等），并列出三条最容易踩的坑（分片谓词列必须在投影里、改 `rules/*.yml` 不生效、`schema.sql` 不能给 MySQL 用）。
- **文档同步到 8 个 MCP 工具**：`docs/mcp.md` 新增"预处理物化：把大表先算成模型表"一节（三步流程、§3.10 收益、不建主键与桶列取模两条实现说明）并贴入含 `recon_build_model` 的真实会话输出；`docs/architecture.md` 目录树补 `model/` 与 `ModelController`、可测性表补 `ModelBuilderTest` / `ModelApiTest`、CI 用例数 68 → 79；README 架构图 / 工具清单 / 项目结构同步，后续计划补"模型表刷新策略"。
- **`docs/benchmark.md` 新增 3.10.2：引擎实现在真实 MySQL 上的验证**（发布前补的，因为 3.10 的装置与 3.10.1 的实现各测了一半）。DBA 路径先验：`schema-mysql.sql` 对**已有 2M×2 数据**的库连跑两遍，只新增 `recon_model`，其余 `CREATE TABLE IF NOT EXISTS` 全是空操作。同一份数据上两种规则形态结论相反：汇总口径 `CUSTOMER_SUM_RECON` 每轮 3.6 s → 建一次 5.42 s（模型表 2 × 50,000 行）→ 之后每轮 **0.16 s**；行级明细 `S7_NARROW` 每轮 5 s → 建一次 28.6 s（模型表 2 × 1,999,900 行）→ 之后每轮 **12 s**，即**负收益**。两种形态的差异集合与原规则互差都为 0（501 条 / 100 条）。顺带记下 MySQL 侧物理形态（`crc32(...) % 4` 的 STORED 生成列 + 两个普通索引 + 无主键）、4 个分片各自产出的差异数 `116/117/133/135`（桶函数算错会表现为"某片安静地一条差异都没有"）、以及 `recon_model.aggregated` 表达的是"**规则**是否声明了 measures"而不是"源 SQL 是不是汇总 SQL"。README 与 `docs/mcp.md` 各补一句"行级明细规则别建模型表"。
- **`docs/benchmark.md` 第三节改成真机数字**：独立 MySQL 8.0.29 进程上对 2M×2 真对账、`-Xmx256m` 跑完（991 次 GC / 0 次 Full GC / GC 后堆 2~38 MB）、8 分片两种分片谓词的计划与墙钟对照、投影宽度导致的执行计划退化（10.6 倍）、连接池饿死的对照实验，以及千万行大表上排序 / 聚合 / 分片 / 分区 / 倾斜的纯 SQL 对照。装置（SQL、PowerShell 脚本、规则）入库到 `tools/mysql-bench/`，可以照着重跑。
- **新增 3.8 状态计数器的可归因增量**：`SHOW GLOBAL STATUS` 是累计值、`FLUSH STATUS` 只清会话计数器，所以只能"相邻两张状态表相减"；场景文件头部的 `ALTER/ANALYZE/CTAS` 会污染首块，于是新增 `tools/mysql-bench/24_counter_probe.sql`（文件内零 DDL，逐块可归因）复测首块。结论：排序读的行数完全相同、差别在 `Sort_merge_passes`（窄行 581 趟 vs 50 列宽行 2,651 趟，因为排序缓冲按行宽切）；覆盖索引让 `Sort_rows` 直接归零；表达式键分组让 `Innodb_rows_read` 从 10M 变 20M 并落一次临时表；分区裁剪与表达式替换的收益是**读得更少**（708,163 vs 10,000,000 行；命中桶列 1,300,000 vs `AS SIGNED` 10,000,000 行）；同时列出不可归因的块（`S4-2/S4-3`、`S4-5`、`17_s4b`、各文件首块）与原因。3.7 的数据倾斜行补上 S6-6 采样边界的真实分段行数（4,999,999 / 0 / 0 / 2,000,000 / 0 / 1,000,000 / 750,021 / 1,249,980，max/avg = 4.00、3 个空段）。
- **新增 3.9 late row lookup 对照**："先排序只取主键、再按主键取明细"值不值，`tools/mysql-bench/25_late_lookup.sql` 里单阶段（排序 + 取 4 列）与两阶段（物化主键 + 主键回表）跑在同一张 1000 万行表上：只排序取主键 1000 万行 1.06 s 且 `Sort_rows = 0`（InnoDB 二级索引叶子自带主键后缀，连排序算子都不出现），但同样 200 万行两阶段 2.91 s vs 单阶段覆盖索引 0.38 s（**7.6 倍**，计数器上是 `Handler_read_key` 每行 +1）。再往下测到这条路的天花板（LL-8/LL-9：按主键顺序顺序读聚簇行，零随机 IO，等价于分批 `IN` + MRR 的最好情况）：200 万行 0.63 s、1000 万行 4.37 s，仍比覆盖索引慢 1.7 / 4.1 倍。带 `LIMIT` 时 MySQL 自己就会选"索引序 + 逐行回表"（`type=index`，10 万行 111 ms），去掉 `LIMIT`（对账要全量）立刻改选 `type=ALL ... Using filesort`。另外"内存里攒主键、每批 2000 个再回查"这种分批方案省不了往返：引擎的 `recon.engine.fetch-size` 默认就是 2000，1000 万行本来就是 5000 次往返，而且 1000 万个主键是 76 MB（1 亿行 763 MB），会把内存重新变成 O(数据量)。结论：对"取前 N 行"划算，对全量流式对账不划算，全量的正解是让**覆盖索引**带上要比较的列。同批在文首结论速览里补了一行。
- **新增 3.10 预处理（把汇总物化成模型表）值不值**：对账引擎本来就会"按维度先汇总"（`dimension` + `measures` 生成 `GROUP BY`），缺的是**把这份汇总物化下来**。`tools/mysql-bench/26_prereq_model.sql` 在 1000 万行宽表上把同一件事量了两遍：现状每轮 21.47 s（`Index scan on t50 using idx_biz_key_gen` 读满 1000 万行再聚合，无排序、无落盘）→ 物化一次 19.13 s（CTAS，读的行数一样）+ 0.28 s 建主键与分片生成列索引 → 之后每轮全量读 7.8 ms、单分片读 15.3 ms（`Index lookup on t50_model using idx_model_shard`，6,272 行）；把维度换成高基数（`acct_id|status`，G≈100 万）物化要 55.08 s、建索引 3.97 s，每轮单分片 296 ms（仍快 20 倍）。关键是**读侧零引擎改动**：物化表的形态就是引擎的输入形态（主键 = 业务键、分片生成列 + 索引），规则把 `table:` 指到模型表、`keyField: biz_key` 即可。边界：只能物化规则已经声明的粒度（行级规则只能窄化、不能汇总），汇总在源库侧 SQL 做，引入的新失败模式是**新鲜度**（源变了模型没重建就是静默错账），所以模型表要带构建时间 / 水位 / 规则版本。同批入库 `tools/mysql-bench/extract_model.py`（含 DDL 的日志按块抽墙钟与状态计数器增量），文首结论速览与复现清单各补一行。

## [0.1.0] - 2026-10-11

首个可用版本：一个能用 YAML 规则跑千万级数据的对账引擎，同时是一个 MCP server。

### 新增

- **规则 DSL（YAML）**：`left`/`right` 支持表名或自定义 SQL、`shard` 分片模板、`compare` 字段比较（绝对 / 相对容差、忽略大小写）、`missing` 单边缺失、`duplicate` 重复键、`options.maxAttempts`；
  启动期做结构校验（code 命名、orderBy 必须等于 keyField、占位符完整性、diffType 不重复），运行期做结果集列名校验
- **归并式对账引擎**：两侧按业务键升序流式取数（`TYPE_FORWARD_ONLY` + `fetchSize`）+ 双指针归并，内存占用与数据量解耦；同键组内按位配对，重复键只报一条
- **分片并行**：`{index}` / `{shards}` 占位符生成分片计划，线程池并行度为 `min(规则并行度, 全局并行度)`，单分片失败按 `maxAttempts` 退避重试
- **幂等断点重跑**：分片即检查点，`retry?onlyFailed=true` 只重跑失败分片；每个分片执行前先删本分片旧差异，重跑不会让差异表翻倍
- **差异分类与归因**：固定四类语义（`VALUE_MISMATCH` / `MISSING_LEFT` / `MISSING_RIGHT` / `DUPLICATE_KEY`）+ 两级解释器
  （离线中文模板 `RuleBasedExplainer`、OpenAI 兼容 `LlmDiffExplainer`，无 key / 超时 / 乱答都安静落回模板）
- **REST API**：发起与查询任务、差异分页与汇总、单条与批量归因、规则清单
- **MCP server（stdio）**：6 个工具 —— `recon_list_rules` / `recon_run` / `recon_job_summary` / `recon_list_diffs` / `recon_explain_diff` / `recon_explain_job`，
  与 REST 共用同一套 service
- **开箱演示与生产路径**：H2 内存库自带 20 条账单 / 21 条结算数据（埋 5 类典型差异），启动即打印一份演示对账报告；MySQL profile 已带 `useCursorFetch=true` / `rewriteBatchedStatements=true`
- **测试与工具**：40 个测试（DSL 校验、容差比较、归并匹配、分片规划、端到端、MCP 工具）；`tools/gen_bill_data.sql` 百万级造数、`tools/mcp-smoke.mjs` 协议级冒烟客户端
- **文档**：`docs/architecture.md`（架构与设计决定）、`docs/rule-dsl.md`（规则手册）、`docs/mcp.md`（Agent 接入）、`docs/benchmark.md`（百万级压测）

### 实测

- 百万级（H2，JDK 17，`-Xmx2g`）：`BIG_VS_SETTLE` 8 分片 37,426 ms、`BIG_VS_SETTLE_SINGLE` 1 分片 4,334 ms，
  两次差异分类逐项一致（共 800 条）—— **分片只影响耗时，不影响结果**；8 分片更慢的原因（列上套函数的谓词用不到索引）与解法见 `docs/benchmark.md`
- MCP 冒烟：`initialize` → `tools/list`（6 个）→ 跑对账 → 查差异 → 批量归因，stdout 噪声 0 行，关闭 stdin 后进程 exit 0

[0.1.0]: https://github.com/Dolo-w/recon-lens/releases/tag/v0.1.0
