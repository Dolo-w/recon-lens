# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

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
