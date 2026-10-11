# recon-lens

**配置化的对账引擎**：YAML 写规则 → 分片并行归并对账 → 差异自动分类 → 规则模板 / 大模型给出归因。

> Reconciliation engine for money pipelines: rule-as-YAML, shard-parallel merge join, streamed cursors,
> idempotent re-runs, and diff attribution (rule templates + optional OpenAI-compatible LLM).

---

## 它解决什么问题

线上对账脚本通常长这样：一段硬编码 SQL + 一堆 `if`，跑出几万条差异丢给运营，然后运营来问"为什么差"。
真出事的时候更麻烦 —— 脚本跑到一半挂了，只能整批重来；数据涨到千万级，直接 OOM。

recon-lens 把这件事拆成四件确定的事：

| 传统脚本 | recon-lens |
| --- | --- |
| 对账逻辑写在代码里，改规则要发版 | 规则是 YAML，改完重启即生效，且上线前被 DSL 校验拦一遍 |
| 全量 `SELECT` 进内存再比对 | 按业务键升序流式取数 + 双指针归并，内存与数据量无关 |
| 失败只能整批重跑，差异表越跑越脏 | 按分片记录进度，只重跑失败分片；先删本分片旧差异再写，重跑天然幂等 |
| 只给差异数量，原因靠人猜 | 每条差异自动分类 + 生成归因（规则模板兜底，可接大模型） |

## 30 秒跑起来

```bash
mvn spring-boot:run
```

启动日志里会直接打出一份演示对账报告（H2 内存库自带 20 条账单 / 21 条结算数据，埋了 5 类典型差异）：

```
================ recon-lens 启动演示 ================
任务 #1 状态=SUCCESS 分片=4
扫描行数: 左侧 20 / 右侧 21，匹配 19，差异 6 条
差异分类: {AMOUNT_MISMATCH=2, DUPLICATE_KEY=1, MISSING_LEFT=1, MISSING_RIGHT=1, STATUS_MISMATCH=1}
归因补齐: 6 条（解释器=rule-template）
----------------------------------------------------
[MEDIUM] STATUS_MISMATCH key=ORD20260004 field=status UNPAID -> PAID
    归因: 账单 与 结算 的 status 不一致：左侧 UNPAID，右侧 PAID。常见原因：入账时点不同、手续费/汇率进位、撤单退单未同步、数据被人工修改。
[MEDIUM] DUPLICATE_KEY key=ORD20260019 账单 1 条(id=19) -> 结算 2 条(id=18/19)
    归因: 业务键 ORD20260019 出现重复记录（账单 1 条(id=19) / 结算 2 条(id=18/19)）。常见原因：重复入账或消息重放，需要按幂等键去重后重跑本分片。
[HIGH] MISSING_RIGHT key=ORD20260017 order_no=ORD20260017, amount=3300, status=PAID -> （无）
    归因: 业务键 ORD20260017 在结算侧没有记录，只有账单侧有（order_no=ORD20260017, amount=3300, status=PAID）。常见原因：对方延迟入账或推送失败、我方已受理但未落库、批次边界跨天。
[HIGH] AMOUNT_MISMATCH key=ORD20260003 field=amount 3000 -> 2999
    归因: 账单 与 结算 的 amount 不一致：左侧 3000，右侧 2999，相差 1（相对 0.03%）。常见原因：入账时点不同、手续费/汇率进位、撤单退单未同步、数据被人工修改。
----------------------------------------------------
试一下: curl http://localhost:8080/api/jobs/1
====================================================
```

```bash
mvn test        # 单元测试 + 端到端测试（真 H2、真 SQL、真并发）
```

## 核心设计

### 1. 规则即配置

一条对账规则的完整定义（`src/main/resources/rules/bill-recon.yml`）：

```yaml
code: BILL_VS_SETTLE
name: 账单与结算逐笔对账
left:  { label: 账单, table: bill_left,  keyField: order_no, keyType: STRING, orderBy: order_no }
right: { label: 结算, table: bill_right, keyField: order_no, keyType: STRING, orderBy: order_no }

shard:                      # 分片 = 并行的单位，也是断点重跑的单位
  count: 4
  parallelism: 4
  # 两侧必须按「同一个业务键口径」切分：按各自的 id 取模会把同一笔业务切到不同分片，凭空多出单边缺失
  whereLeft:  "MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}"
  whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}"

compare:
  - { field: amount, type: DECIMAL, tolerance: 0.01, diffType: AMOUNT_MISMATCH, severity: HIGH }
  - { field: status, type: STRING,  ignoreCase: true,  diffType: STATUS_MISMATCH,  severity: MEDIUM }

missing:                    # 单边缺失必须显式声明归到哪种差异，避免被静默丢弃
  leftMissingDiffType: MISSING_LEFT
  rightMissingDiffType: MISSING_RIGHT
  severity: HIGH

duplicate: { diffType: DUPLICATE_KEY, severity: MEDIUM }
options:   { maxAttempts: 2 }
```

`left`/`right` 还能直接写自定义 SQL（聚合对账、跨表口径），只要把业务键别名为 `keyField` 并保证按它升序：

```yaml
left:
  label: 客户账单
  keyField: biz_key
  keyType: STRING
  orderBy: biz_key
  sql: SELECT customer_id AS biz_key, SUM(amount) AS amount, COUNT(*) AS bill_count FROM bill_left GROUP BY customer_id
```

### 2. 归并式对账 + 流式游标

两侧数据都按业务键升序取，引擎用双指针推进：键相等就比对字段，左边小就是右侧缺失，右边小就是左侧缺失。
JDBC 游标（`TYPE_FORWARD_ONLY` + `fetchSize`）保证只驻留有限行 —— 千万级对账不需要把数据读进内存。

### 3. 分片并行 + 断点重跑

- `recon_job` 记录任务，`recon_shard` 记录每个分片的扫描行数/匹配数/差异数与状态
- 分片并行执行（线程池大小 = `min(规则并行度, 全局并行度)`），单分片失败按 `maxAttempts` 退避重试
- **幂等**：每次分片执行前先 `DELETE FROM recon_diff WHERE job_id=? AND shard_id=?`，重跑不会让差异表翻倍
- 重跑只做 `FAILED` 的分片，已经跑完的分片一次都不重复计算

> **切分口径必须统一**：分片条件只能依赖业务键（`where` 里出现 `keyField`），不能让两侧各按自己的代理主键
> （如 `MOD(id, {shards}) = {index}`）切分 —— 那样同一笔业务会被切到不同分片，两边都看不到对方，结果是一堆
> 假缺失。解析器会在 `where` 完全不引用业务键时打 WARN 提醒。

### 4. 差异分类 + 归因

引擎内部的差异语义固定为四类（`DiffKind`）：`VALUE_MISMATCH` / `MISSING_LEFT` / `MISSING_RIGHT` / `DUPLICATE_KEY`；
规则里的 `diffType` 是自由字符串（`AMOUNT_MISMATCH`、`STATUS_MISMATCH`…），归因只管这四类，规则怎么改名都不影响。

归因有两级，默认纯离线：

- `RuleBasedExplainer`：零成本中文模板，带 delta / 相对比例 / 常见原因话术
- `LlmDiffExplainer`：OpenAI 兼容 `/chat/completions`，批量一次请求解释多条；**没配 key、超时、模型乱答都会安静落回模板**

```bash
export RECON_LLM_ENABLED=true
export RECON_LLM_BASE_URL=https://api.openai.com/v1
export RECON_LLM_API_KEY=sk-...
mvn spring-boot:run
```

## REST API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/jobs` | 发起对账，`{"ruleCode":"BILL_VS_SETTLE","batchId":"2026-10-11","sync":true}` |
| GET | `/api/jobs` | 任务列表 |
| GET | `/api/jobs/{id}` | 任务详情 + 每个分片状态 + 差异分类汇总 |
| POST | `/api/jobs/{id}/retry?onlyFailed=true` | 断点续跑 |
| GET | `/api/diffs?jobId=1&diffType=AMOUNT_MISMATCH&offset=0&limit=50` | 差异分页查询 |
| GET | `/api/diffs/summary?jobId=1` | 差异按类型 / 严重级别汇总 |
| POST | `/api/diffs/{id}/explain` | 单条归因 |
| POST | `/api/diffs/explain?jobId=1&limit=200` | 批量补齐归因 |
| GET | `/api/rules` | 当前实例加载了哪些规则 |

## 生产使用（MySQL）

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

`application-mysql.yml` 已带上关键参数 `useCursorFetch=true`（否则 MySQL 驱动会把结果集整体拉进内存，流式取数形同虚设）
与 `rewriteBatchedStatements=true`（差异批量写入提速）。生产库的表结构见 `src/main/resources/schema.sql`，
规则目录建议挂 `file:./rules/`，改规则不用重新构建。

千万级数据的造数与压测方法见 [docs/benchmark.md](docs/benchmark.md)。

## 项目结构

```
src/main/java/io/github/dolow/reconlens/
├── dsl/        规则解析与校验（YAML → RuleSpec，错误配置在这里就被拦住）
├── engine/     归并匹配、容差比较、分片规划、并行编排、流式游标
├── store/      JdbcTemplate 持久化（任务 / 分片 / 差异）
├── explain/    归因：规则模板 + 大模型，统一入口 ExplainService
├── web/        REST 接口
└── bootstrap/  启动演示
docs/           架构说明、规则 DSL 手册、压测方法
tools/          造数脚本
```

## 后续计划

- [ ] `recon_shard` 落的检查点支持「同一分片内部」续跑（当前粒度是分片）
- [ ] 差异结果导出（CSV / 飞书表格）与按批次对比
- [ ] 规则热重载（不重启刷 `file:` 目录）
- [ ] 归因结果回写人工标注，做一个"差异原因"知识库

## License

Apache-2.0
