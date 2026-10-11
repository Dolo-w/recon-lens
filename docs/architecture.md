# 架构说明

## 一句话

把"对账"拆成**规则（配置）**、**取数（流式游标）**、**比对（归并匹配）**、**并行/重跑（分片）**、**归因（规则模板/大模型）**五件事，
每一件都能单独测、单独换。

## 模块

```
io.github.dolow.reconlens
├── dsl/         YAML → RuleSpec：解析 + 校验（RuleParser / RuleSpec / SideSpec / FieldRule / ShardSpec）
│                RuleRegistry 只是 classpath YAML 的种子来源，不再是对账时取规则的入口
├── engine/      纯计算与编排，不碰 Web
│   ├── RowCursor / JdbcRowCursor / ListRowCursor   流式取数（FORWARD_ONLY + fetchSize），List 版供单测
│   ├── MergeMatcher                                双指针归并：比对、单边缺失、同键重复
│   ├── ValueComparator                             字段比较与容差、业务键比较
│   ├── ShardPlanner                                占位符替换 → 每个分片的 where 与取数 SQL
│   ├── ShardRunner                                 单个分片的一次尝试：先删旧差异 → 对账 → 批量落库
│   └── ReconEngine                                 建任务、建分片、线程池并行、重试、汇总任务状态
├── rule/        RuleCatalog：规则库（版本、缓存、内联规则、classpath 种子导入），入库前先过 RuleParser 校验
├── store/       ReconRepository：recon_job / recon_shard / recon_diff 的读写（JdbcTemplate）
│                RuleRepository：recon_rule 规则表读写与版本号分配（JdbcTemplate）
├── explain/     DiffExplainer 接口 + RuleBasedExplainer（离线模板）+ LlmDiffExplainer（OpenAI 兼容）
│                ExplainService 是统一入口，负责"大模型优先、模板兜底、绝不留空"
├── web/         JobController / DiffController / RuleController
├── mcp/         ReconMcpTools（对账能力 → 7 个 MCP 工具）+ McpServerRunner（stdio 会话）
│                只在 --spring.profiles.active=mcp 下启动，和 REST 共用同一套 service
└── bootstrap/   DemoRunner：启动时跑一遍演示规则并把报告打到日志
```

依赖方向是单向的：`web → engine/explain/rule/store → dsl`，`engine` 不依赖 `web`；`mcp` 只做"把已有 service 翻译成人话"，
不含任何对账逻辑，所以两条调用路径的结果必然一致；`ShardRunner` 通过
`DiffWriter` 接口写差异，`store` 反过来实现它 —— 引擎因此可以在没有数据库的情况下单测。

## 一次对账的完整流程

```
POST /api/jobs {ruleCode, batchId}                 （也可用 rule / ruleYaml 内联一份规则，不落规则库；MCP 侧对应 ruleJson）
  └─ ReconEngine.run
       1. resolve   RuleCatalog 解析规则：ruleJson 内联优先，否则取规则库里该 code 的最新启用版本；
                    解析时会归一化声明式维度（keyField/orderBy → __biz_key）
       2. prepare   ShardPlanner 生成 N 个分片；INSERT recon_job（PENDING，连 rule_version + rule_snapshot
                    一起落库）+ N 条 recon_shard（PENDING）；job → RUNNING
       3. execute   ThreadPoolExecutor(min(规则并行度, 全局并行度), CallerRunsPolicy)
                    每个分片提交一个 ShardRunner.run：
                      markShardRunning(attempt)
                      DELETE FROM recon_diff WHERE job_id=? AND shard_id=?   ← 幂等
                      开左右 JdbcRowCursor（ORDER BY 业务键）
                      校验 keyField / compare 字段是否存在于结果集列
                      MergeMatcher 归并比对 → BatchDiffSink（500 条一批）→ insertDiffs
                      markShardSuccess(扫描行数/匹配数/差异数)
                    异常 → warn + 退避 200ms*attempt 重试，用尽则 markShardFailed
       4. finish    汇总分片状态写回 recon_job：
                    无失败 → SUCCESS；部分成功 → PARTIAL_FAILED；全失败 → FAILED
```

重跑和归因都走 `ReconEngine.ruleForJob`：**优先用任务自己钉住的 `rule_snapshot`** 解析规则，
快照缺失才退到 `rule_version`、再退到该 `code` 的最新版本 —— 所以规则库改了版本，历史任务重跑仍按当时的语义执行。

分片状态机：`PENDING → RUNNING → SUCCESS | FAILED`；任务状态：`PENDING → RUNNING → SUCCESS | PARTIAL_FAILED | FAILED`。

## 表结构

| 表 | 作用 | 关键列 |
| --- | --- | --- |
| `recon_job` | 一次对账任务 | `rule_code` / `rule_version` / `rule_snapshot` / `batch_id` / `status` / `shard_count` / `shard_done` / `left_rows` / `right_rows` / `matched_rows` / `diff_rows` |
| `recon_rule` | 规则库（一个 `code` 多个版本，只追加不改写） | `code` / `version` / `spec_json` / `enabled` / `created_by` / `created_at`，唯一索引 `uk_rule_code_version (code, version)` |
| `recon_shard` | 任务的分片（重跑单位） | `job_id` + `shard_id` / `status` / `attempts` / 各计数 / `error_msg` |
| `recon_diff` | 差异明细 | `diff_type` / `severity` / `biz_key` / `field` / `left_value` / `right_value` / `delta_value` / `delta_ratio` / `explanation` |

`recon_diff` 上有唯一索引 `uk_diff_shard_key (job_id, shard_id, diff_type, biz_key, field_name)`，
配合"先删本分片旧差异再写"形成双保险：即使重试发生在写入过程中的任意时刻，也不会出现重复行。

`recon_rule` 上只追加不覆盖：`version = MAX(version) + 1`，下线一个规则是把该 `code` 的所有版本
`enabled` 置 FALSE（`DELETE /api/rules/{code}`）。取规则时只认 `enabled = TRUE`，`listLatest` 每个 `code` 取最新启用版。

`rule_snapshot`（VARCHAR(8000)）存的是**归一化之前的规则 Map 的 JSON**，不是 `RuleSpec` 的序列化结果。
因为声明式维度在解析时会把 `keyField` / `orderBy` 统一改写成内部别名 `__biz_key`（`RuleParser` 构造 `SideSpec`
时就这么做），再按 `RuleSpec` 序列化回去就会写出 `keyField: __biz_key`，回放时与 `dimension` 冲突、规则不可还原。
存原始 Map 的 JSON 则与磁盘上的 yml 同构，`RuleCatalog.parseSnapshot` 把它重新喂给 `RuleParser`，
校验和归一化再跑一遍即可 —— 规则库里的 `spec_json` 同理，存的是同一份原始 Map。

## 三个关键设计决定

**1. 归并匹配而不是哈希连接。**
哈希连接要先把一侧全部读进内存（或落磁盘），千万级就要面对内存上限。两侧都按业务键升序流式读、
双指针推进，内存占用只跟"同一个业务键的重复条数"有关。代价是必须保证两侧排序与引擎比较一致 ——
这就是 `orderBy` 必须等于 `keyField`、且业务键建议定宽零填充的原因。

**2. 分片是重跑单位，不是性能单位。**
分片同时承担并行与断点重跑两个职责，所以它必须能"独立重算且结果确定"：
每个分片把自己的差异先删后写，重跑任意次结果一致；`retry` 默认只跑 `FAILED` 分片。

**3. 归因必须可降级。**
大模型归因是加分项，不是依赖项。`LlmDiffExplainer` 在没配 key、超时、返回格式不合预期时都安静返回空，
由 `RuleBasedExplainer` 补齐 —— 离线环境跑出来的报告也有完整归因列。

## 可测性

| 测试 | 覆盖 |
| --- | --- |
| `dsl.RuleParserTest` | 非法配置都要在启动期报错、提示能指明位置；声明式维度（单列/复合/表达式/聚合）与侧级 `where` 的解析口径 |
| `engine.ValueComparatorTest` | 容差边界、空值、类型比较、业务键比较与 SQL 排序一致 |
| `engine.MergeMatcherTest` | 五种差异形态（含同键重复、尾部残留）与统计口径 |
| `engine.ShardPlannerTest` | 占位符替换、取数 SQL 包裹、不分片规则无 where、声明式维度的投影与 `GROUP BY`、侧级过滤与分片条件 AND |
| `engine.DerivedDimensionTest` | 声明式维度：与手写 `keyField` 等价、聚合维度先分组再比对、表达式维度原样下发、侧级过滤在匹配前生效 |
| `ReconEngineEndToEndTest` | 真 H2 + 真 SQL + 真线程池：6 类差异、幂等重跑、归因、自定义 SQL 规则、未知规则、只重跑失败分片 |
| `rule.RuleCatalogTest` | 规则库：classpath 种子只导一次、重复提交升版本、内联不落库、任务钉版本与快照 |
| `web.RuleApiTest` | 规则接口（MockMvc）：YAML/JSON 提交、版本历史、停用、400/404 口径、任务内联规则 |
| `BenchH2Test`（`@Tag("bench")`，默认不跑） | 百万级两张表：分片口径不影响差异、吞吐与堆占用实测，见 [benchmark.md](benchmark.md) |
| `mcp.ReconMcpToolsTest` | 工具返回的文本是否让人和模型都读得懂：规则清单、任务摘要、差异分页、归因，以及未知规则的报错口径 |
| `MermaidDocsTest` | 对外渲染的 markdown 里的 ```` ```mermaid ```` 块不含花括号（GitHub 会把标签里的 `{` 当 diamond 起始符，整张图渲染失败），并断言确实扫到了图 |
| `tools/mcp-smoke.mjs`（协议级冒烟，不属于 `./mvnw test`） | 真按 MCP 协议握手并调 6 个工具（`recon_define_rule` 只在 `tools/list` 里校验声明，不会改规则库），另外断言 **stdout 只有 JSON-RPC**、stdin 关闭后进程自行退出 |

CI 配置在 `.github/workflows/build.yml`：`./mvnw -B test`（压测被 `surefire.excludedGroups=bench` 排除，
所以 CI 只跑秒级的 68 个用例）。

> **踩过的坑：测试资源会遮住主资源目录。** 造数规则一开始放在 `src/test/resources/rules/` 下，
> 结果 `classpath:rules/` 只解析到了 `test-classes/rules`（类路径里 test-classes 排在 classes 前面），
> 主规则 `BILL_VS_SETTLE` 整个消失、端到端测试全红。压测规则现在单独放
> `src/test/resources/bench-rules/`，用 `recon.rules-location=classpath:bench-rules/` 指过去。

> **踩过的坑：文档里的图也会炸在生产页面上。** README 架构图的边标签写过 `{shards} {index}`，
> mermaid 把 `{` 当成 diamond 节点的起始符，期待闭合却等来 `|`，GitHub 上整张图渲染不出来
> （`Unable to render rich display / Parse error ... got 'DIAMOND_START'`）。`MermaidDocsTest` 现在盯着这件事：
> 扫根目录与 `docs/` 下的 markdown，放行合法的 diamond 写法 `A{判断}`，禁掉其余花括号，图数为 0 也判失败。
> 它只能判死花括号这一类；"用了 GitHub 那版 mermaid 不支持的新语法"得拿真解析器跑（`mermaid@11`）。

## 已知边界

- 检查点粒度是分片，不是分片内部的游标位置：一个分片失败后从头重算（因为结果幂等，重算比续跑更简单可靠）。
- 分片谓词若写成"列上套函数"（`MOD(SUBSTRING(...))`）就用不到索引，每个分片全表扫一遍；
  实测 8 分片比 1 分片慢 8.6 倍（[benchmark.md](benchmark.md)）。要提速得先把分片桶落成索引列。
- 规则以数据库为准、支持热更新：改规则是往 `recon_rule` 追加一个新版本（REST `POST /api/rules` 或 MCP `recon_define_rule`），
  立即对后续任务生效，不用重启。`classpath` / `file:` 下的 yml（`recon.rules-location`）只在规则库里还没有该 `code` 时
  导入一次 v1（受 `recon.seed-rules` 开关控制），之后不再覆盖；`RuleCatalog` 对已解析规则按 `code` 做缓存，定义或下线时失效。
- 差异表会随批次累积；跨批次对比与归档策略留给使用方。
