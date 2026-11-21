# recon-lens

<p>
  <a href="https://github.com/Dolo-w/recon-lens/actions/workflows/build.yml"><img alt="build" src="https://github.com/Dolo-w/recon-lens/actions/workflows/build.yml/badge.svg"></a>
  <img alt="java" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="spring boot" src="https://img.shields.io/badge/Spring%20Boot-3.3-6db33f">
  <img alt="mcp" src="https://img.shields.io/badge/MCP-stdio-6f42c1">
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
</p>

**配置化的对账引擎 + 可对话的对账 Agent。**

规则即数据：用 YAML 声明"对账维度 + 比对字段"，提交经影子试算与人工审批后生效；
引擎按业务键流式归并对账，分片并行、断点重跑、水位判定区分"真缺失"与"未到达"、差异自动分类归因；
同时是一个 MCP server —— 18 个工具把全部能力暴露给 Agent，一句业务描述就能起草规则。

## 它解决什么问题

线上对账脚本通常长这样：一段硬编码 SQL + 一堆 `if`，跑出几万条差异丢给运营，然后运营来问"为什么差"。
recon-lens 把这件事拆成四件确定的事：

| 传统脚本 | recon-lens |
| --- | --- |
| 对账逻辑写在代码里，改规则要发版 | 规则即数据：声明式 DSL，提交新版本经试算与审批后生效 |
| 全量 `SELECT` 进内存再比对 | 按业务键升序流式取数 + 双指针归并，内存与数据量无关 |
| 失败只能整批重跑 | 按分片记录进度，只重跑失败分片，重跑天然幂等 |
| 只给差异数量，原因靠人猜 | 差异四分类 + 两级归因（离线模板兜底，可接大模型） |
| 数据没到齐就敢报"缺失" | 配置水位（cut-off）：未就绪侧缺失标 `*_PENDING`，等数据到齐重跑出终判 |

## 核心能力

- **规则 DSL**：`keyField` 逐笔 / `dimension` 复合维度 / `measures` 聚合 / 自定义 SQL；分片支持取模与 `rangeKey` 范围切分
- **金额归一导入**：CSV 里的 `¥1,234.50` / `(1,234.50)` / `1.234.567,89` 这类脏金额自动归一成 DECIMAL，币种提示单独摘出、原始值留档 `__raw_*` 旁列——对账引擎能直接比，差异复盘能回看原文
- **厂商导出 profile**：`recon_import_file` 传 `profile=lago-invoice-fees` / `lago-invoices`，按厂商导出表头重命名列、`_cents` 分单位自动换算成元、强制列类型；表头不匹配直接报错（带期望表头），表尾可选列照走通用推断。profile 是配置不是代码路径——接新厂商 = 加一份映射 + 一组按真实表头写的测试
- **归并式对账**：两侧流式游标 + 双指针归并，实测 `-Xmx256m` 跑 200 万×2 行 0 次 Full GC
- **分片并行 + 断点重跑**：重跑前先清本分片旧差异，幂等
- **水位/cut-off**：按规则配置两侧水位列，建任务时探测 `MAX(水位列)`；未就绪侧的单边缺失标 `*_PENDING` 而非冒充真缺失——`MISSING ≠ NOT_YET_ARRIVED`
- **差异分类 + 归因**：`VALUE_MISMATCH` / `MISSING_LEFT` / `MISSING_RIGHT` / `DUPLICATE_KEY` 四类语义；模板归因永远可用，LLM 失败安静降级
- **规则库版本化**：同一 `code` 多版本共存，任务钉住当时的规则快照，改规则不影响历史任务
- **对账 Agent**（MCP）：起草规则 → 安全闸门 → 影子试算 → 人工审批 → 生效实跑，全程对话式

## 当前能力边界（诚实版）

定位是**架构原型 / 单机工具**，不是生产级平台。以下能力明确**没有**，评估时别按平台预期：

- **单机单库**：元数据存 H2 或单个 MySQL；没有分布式执行与高可用，进程挂了靠启动自检标 FAILED 后人工重跑
- **取数靠 SQL**：数据必须能被一条 SELECT 睹到 —— 没有 Lago/Stripe 式 API 连接器，不支持跨库取数
- **快照是"指纹"不是"冻结"**：任务记录建任务时的行数/校验和指纹与水位判定，能回答"读的是哪份数据、当时两侧是否就绪"，但不冻结数据本身
- **差异生命周期只到"归因"**：有豁免性质的说明字段，没有"认领 → 处理 → 核销"的闭环台账
- **鉴权是单密钥**：写接口靠 `X-Api-Key` 常量时间比较，没有用户体系/角色/审计日志
- **ChatRuntime 偏重**：会话/记忆/规划与引擎同进程，`recon.agent.enabled=false` 可关掉纯引擎跑

## 30 秒跑起来

需要 JDK 17+，没装 Maven 就用自带 wrapper：

```bash
./mvnw spring-boot:run        # Windows 用 mvnw.cmd
```

默认 H2 内存库自带演示数据（两表各 10 行、埋 4 条差异）和 3 条演示规则，启动日志直接打出演示对账报告：

```
================ recon-lens 启动演示 ================
任务 #1 状态=SUCCESS 分片=2
扫描行数: 左侧 10 / 右侧 10，匹配 9，差异 4 条
差异分类: {AMOUNT_MISMATCH=1, DUPLICATE_KEY=1, MISSING_LEFT=1, MISSING_RIGHT=1}
试一下: curl http://localhost:8080/api/jobs/1
====================================================
```

```bash
./mvnw test        # 全量测试（真 H2、真 SQL、真并发）
```

## 写一条对账规则

完整 DSL 见 [docs/rule-dsl.md](docs/rule-dsl.md)。最常用的复合维度形态（`src/main/resources/rules/bill-recon.yml`，即演示规则）：

```yaml
code: BILL_VS_SETTLE
name: 账单与结算对账（客户+订单号）
left:
  label: 账单
  table: bill_left
  keyType: STRING
  dimension: [customer_id, order_no]     # 引擎生成 CONCAT(...) AS __biz_key 并按它归并
right:
  label: 结算
  table: bill_right
  keyType: STRING
  dimension: [customer_id, order_no]

shard:                      # 分片 = 并行与断点重跑的单位
  count: 2
  parallelism: 2
  # 两侧必须按「同一个业务键口径」切分，函数用 ANSI 公共子集
  whereLeft:  "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"
  whereRight: "MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"

compare:
  - field: amount
    type: DECIMAL
    tolerancePercent: 0.0001     # 小数比率：0.0001 = 0.01%
    diffType: AMOUNT_MISMATCH
    severity: HIGH

missing:                    # 单边缺失必须显式声明差异类型
  leftMissingDiffType: MISSING_LEFT
  rightMissingDiffType: MISSING_RIGHT
  severity: HIGH
duplicate: { diffType: DUPLICATE_KEY, severity: MEDIUM }
options:   { maxAttempts: 2 }
```

关键约束：

- `dimension` 与 `keyField` 二选一；配了 `measures` 就必须聚合比对
- 两侧分片口径必须按**同一业务键**切，否则同一笔业务劈进不同分片，凭空多出假缺失
- 分片谓词里的函数会被校验：`HASH()` 这类 MySQL 没有的函数提交时直接拒绝，改用 `MOD(CRC32(...), {shards})`
- 容差 `tolerance` 是绝对值，`tolerancePercent` 是**小数比率**

## 水位/cut-off：区分"真缺失"与"未到达"

应用配置里按规则配水位列（只支持表取数侧；不配置则行为与老版本完全一致）：

```yaml
recon:
  engine:
    watermarks:
      BILL_VS_SETTLE:
        left: updated_at
        right: updated_at
        lag-seconds: 300        # 水位 >= now-lag 即算就绪
```

建任务时探测两侧 `MAX(水位列)`，晚于 `now-lag` 判"未就绪"；未就绪侧的单边缺失标
`MISSING_LEFT_PENDING` / `MISSING_RIGHT_PENDING`，并附水位归因说明。
数据到齐后重跑（`POST /api/jobs/{id}/retry`）会重新探测水位，PENDING 自动变终判。
探测失败 fail-loud（列名不合法、水位列不是时间型都直接拒绝），绝不给一份没验过水位的报告。

## 发起对账（REST）

```bash
# 发起对账（sync=true 同步等结果）
curl -X POST http://localhost:8080/api/jobs \
     -H 'Content-Type: application/json' \
     -d '{"ruleCode":"BILL_VS_SETTLE","batchId":"2026-11-21","sync":true}'

# 查结果
curl http://localhost:8080/api/jobs/1
curl "http://localhost:8080/api/diffs?jobId=1&limit=50"
curl "http://localhost:8080/api/diffs/summary?jobId=1"
```

常用端点：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/jobs` | 发起对账；也可带 `ruleYaml` 内联规则（按试算处理，不绕审批） |
| POST | `/api/jobs/{id}/retry?onlyFailed=true` | 断点续跑（按任务自带的规则快照，重探水位） |
| GET | `/api/jobs/{id}` / `/api/diffs` / `/api/diffs/summary` | 任务详情 / 差异分页 / 分类汇总 |
| POST | `/api/diffs/{id}/explain` | 差异归因（模板 / LLM） |
| GET | `/api/rules` / `POST /api/rules` | 规则库 / 提交新版本（运维直写通路，默认关闭，见下） |
| POST | `/api/models/{ruleCode}?shards=4` | 大表按规则粒度物化成模型表（默认草稿，需审批） |

> `POST /api/rules` 是运维 legacy 直写通路：默认关闭，需开 `recon.rules.legacy-define-enabled` 并带 `X-Api-Key`，提交即生效。生产上要挂在网关鉴权后面；Agent 通道的规则一律走审批。

## 生产部署（MySQL）

复用已有 MySQL 8 实例即可：

```bash
# 1. 建库 + 建表（schema 给 DBA 执行）
mysql -h <实例> -u <用户名> -p -e "CREATE DATABASE IF NOT EXISTS recon_demo DEFAULT CHARSET utf8mb4"
mysql -h <实例> -u <用户名> -p recon_demo < src/main/resources/schema-mysql.sql

# 2. 启动
./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql \
  --spring.datasource.url="jdbc:mysql://<实例>:3306/recon_demo?serverTimezone=Asia/Shanghai&useCursorFetch=true&rewriteBatchedStatements=true&useSSL=false" \
  --spring.datasource.username=<用户名> --spring.datasource.password=<密码>
```

实测踩过的坑，四个关键点：

- `useCursorFetch=true` 必须带，否则驱动把结果集整体拉进内存，流式取数形同虚设
- 连接池默认 24：每个分片同时持左右两个游标，池子至少 `2 × recon.engine.parallelism`，否则出现"全在等连接"的假慢（实测慢 6~11 倍）
- 业务键排序口径自动对齐：MySQL `utf8mb4_general_ci` 大小写不敏感排序与 Java 码元序不一致会导致假缺失，引擎自动把业务键包成 `CONVERT(... USING utf16) COLLATE utf16_bin`（仅声明式 `dimension` 受保护）
- 大表灌数用 `tools/parallel-load.mjs`（N 并发 + 先灌数后建索引，2×200 万行实测 168.7s）

压测方法与调优结论见 [docs/benchmark.md](docs/benchmark.md)。一句话版：**分片只影响耗时，不影响结果**；把分片桶做成索引列（或用 `rangeKey`）才能真提速。

## 对账 Agent（MCP）

接进任何支持 stdio MCP 的宿主（Claude Desktop / Cursor / Cherry Studio…），就能对话式完成
**导入数据 → 起草规则 → 影子试算 → 人工审批 → 生效实跑 → 差异归因**。

```json
{
  "mcpServers": {
    "recon-lens": {
      "command": "java",
      "args": [
        "-jar", "/绝对路径/recon-lens-0.1.0.jar",
        "--spring.profiles.active=mcp",
        "--recon.agent.enabled=true",
        "--recon.agent.schema.tables=bill_left,bill_right",
        "--recon.review.api-key=<审批密钥>"
      ]
    }
  }
}
```

- 12 个基础工具（跑对账 / 看差异 / 归因 / 表结构探查）始终可用；`recon.agent.enabled=true` 额外开放 6 个 Agent 工具（起草、试算轮询、表注册、文件导入、审批代办）
- **表白名单 fail-closed**：`--recon.agent.schema.tables` 不配等于拒绝所有规则
- **审批铁律**：规则生效必须人批 —— 草稿 → 影子试算 → 人工批准 → ACTIVE，会话里说"批准"不算数
- 要起草规则再配 `--recon.llm.enabled=true` + OpenAI 兼容端点；不配也能跑，起草降级为模板骨架

完整流程（含每步提示词与返回）见 [docs/agent-quickstart.md](docs/agent-quickstart.md)，
工具入参/返回与动态表注册见 [docs/mcp.md](docs/mcp.md)。

冒烟验证：

```bash
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar    # MCP 协议级冒烟
node tools/agent-smoke.mjs target/recon-lens-0.1.0.jar  # Agent 全链路 16 项断言
```

## 三种使用方式（P0 / P1 / P2）

同一个 jar，按环境里有什么自动分三档，能力只增不减：

| 档位 | 环境要求 | 得到什么 | 典型用途 |
| --- | --- | --- | --- |
| **P0** 零依赖 | JDK 17，一个进程 | 引擎 + REST + MCP + 词表会话 + 审批面板 | 单机、内网、断网环境 |
| **P1** 有记忆 | P0 实例持续跑 | 滚动摘要、案例记忆、口径检索 | 会话很长；要回答"上次为什么差" |
| **P2** 有模型 | P1 + `DEEPSEEK_API_KEY` | 口语化意图、多轮指代、规则起名、差异归因 | 运营用大白话直接问账 |

三条边界在所有档位都一样：**模型只建议不落库**（输出过白名单校验，写库的永远是代码）、
**写动作先回执后执行**、**规则生效必须人批**。没配 key 时 P2 自动空转，行为与 P1 一致——key 不是启动前提。
逐档深拆见 [docs/deep-dive-p0.md](docs/deep-dive-p0.md) / [deep-dive-p1.md](docs/deep-dive-p1.md) / [deep-dive-p2.md](docs/deep-dive-p2.md)。

## 排障速查

| 症状 | 原因与解法 |
|---|---|
| 差异类型带 `_PENDING` 后缀 | 水位未就绪，属预期而非 bug；数据到齐后重跑即出终判 |
| 起草 BLOCKED"大模型未启用" | 漏了 `--recon.llm.enabled=true`（或 api-key 为空） |
| 起草 BLOCKED"闸门 1 拒绝（表不在白名单）" | `recon.agent.schema.tables` 没含规则用到的表 → `recon_register_table` 注册 |
| 审批 403 | 没配 `recon.review.api-key`，或 X-Api-Key 不对 |
| MCP 连 MySQL 启动崩 | 设 `RECON_SQL_INIT=never` + 显式 `DRIVER_CLASS_NAME` |
| 会话有结论但库里查不到 | MCP 进程写进了 H2 内存库 —— 检查数据源四个 env 变量 |
| 规则提交报 NON_PORTABLE_FUNCTION | 分片/维度里用了 `HASH()` 等 MySQL 没有的函数，改 `MOD(CRC32(...), {shards})` |

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [docs/rule-dsl.md](docs/rule-dsl.md) | 规则 DSL 完整手册（维度 / 聚合 / 分片 / 容差 / 校验规则） |
| [docs/agent-quickstart.md](docs/agent-quickstart.md) | Agent 本地运行与对接全流程实测指南 |
| [docs/mcp.md](docs/mcp.md) | MCP 接入：18 个工具入参/返回、动态表注册、文件导入 |
| [docs/architecture.md](docs/architecture.md) | 架构说明 |
| [docs/benchmark.md](docs/benchmark.md) | 压测方法与百万级实测数字 |

## 开发与贡献

```bash
./mvnw test                                             # 全量测试
./mvnw -DskipTests package                              # 产出 target/recon-lens-0.1.0.jar
```

CI 在每次 push / PR 上跑测试 + MCP 冒烟；推 `v*` tag 自动发 Release。改动记录见
[CHANGELOG.md](CHANGELOG.md)，提 PR 前请读 [CONTRIBUTING.md](CONTRIBUTING.md)。

## License

Apache-2.0
