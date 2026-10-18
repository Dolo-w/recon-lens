# 用 MCP 让 Agent 直接调对账

REST 接口是给"人"和"调用方系统"用的：你得知道 URL、参数、返回值长什么样。
但运营问的那句话是**"这批账单和结算到底差在哪、为什么差"**——这种问题现在多半是丢给 Agent 的。
MCP（Model Context Protocol）就是让 Agent 能直接调工具的协议：模型自己决定调 `recon_run` 跑对账、
调 `recon_list_diffs` 看差异、调 `recon_explain_job` 拿归因、调 `recon_define_rule` 换一套对账维度、
调 `recon_build_model` 把大表先物化好，全程不用人拼 URL。

recon-lens 暴露 9 个工具（启用 Agent 后 14 个），**和 REST 接口共用同一套 service**（`ReconEngine` / `ReconRepository` / `ExplainService` /
`ModelBuilder`），所以两条路的结果必然一致，不存在两套逻辑。

## 启动

```bash
./mvnw -DskipTests package
java -jar target/recon-lens-0.1.0.jar --spring.profiles.active=mcp
```

`mcp` profile 做三件事（见 `src/main/resources/application-mcp.yml`）：

| 配置 | 为什么必须 |
| --- | --- |
| `spring.main.web-application-type=none` | stdio 模式不需要 Web 容器，也不该占端口 |
| `spring.main.banner-mode=off` | 启动横幅会写 stdout，而 stdout 是 JSON-RPC 通道 |
| `recon.engine.run-demo-on-startup=false` | 启动演示会打印报告，同样污染通道 |

## 客户端配置

任何 MCP 客户端（Claude Desktop、Cursor、自研 Agent）都是同一份 JSON，把 `command` 换成绝对路径即可：

```json
{
  "mcpServers": {
    "recon-lens": {
      "command": "java",
      "args": [
        "-jar",
        "/绝对路径/recon-lens-0.1.0.jar",
        "--spring.profiles.active=mcp"
      ]
    }
  }
}
```

## 工具清单

| 工具 | 入参 | 返回 |
| --- | --- | --- |
| `recon_list_rules` | 无 | 列出规则库里的对账规则（版本、两侧数据源、对账维度、比对字段、分片与重试配置） |
| `recon_define_rule` | `ruleBody`（必填）、`operator` | 提交一份规则体（JSON 或 YAML）落库为草稿并自动跑影子试算，审批（recon_submit_approval）通过才生效；返回版本号、试算可批性与两侧维度 |
| `recon_run` | `ruleCode`、`ruleJson`、`batchId`、`remark`（**都非必填**） | 同步跑完一次对账，返回任务号、扫描量、差异分类、分片明细 |
| `recon_build_model` | `ruleCode`（必填）、`shards`、`operator` | 预处理物化：按规则声明的粒度把两侧数据提前汇总成模型表，并注册一条读模型表的 `<CODE>__MODEL` 规则 |
| `recon_job_summary` | `jobId`（必填） | 任务状态 / 分片完成度 / 差异分类与级别 / 分片明细 / 错误原因 / 耗时 |
| `recon_list_diffs` | `jobId`（必填）、`diffType`、`offset`、`limit` | 差异明细：业务键、字段、左右值、相对偏差、已有归因 |
| `recon_explain_diff` | `diffId`（必填） | 单条差异的归因说明 |
| `recon_explain_job` | `jobId`（必填）、`limit` | 批量补归因并回显前几条 |

启用 Agent（`recon.agent.enabled=true`）后额外暴露 5 个：

| 工具 | 入参 | 返回 |
| --- | --- | --- |
| `recon_draft_rule` | `businessDescription`（必填）、`ruleCode`、`operator` | 创建 Agent 会话，后台起草 + 闸门 1 + 影子试算，立即返回会话号。**businessDescription 按结构化逐项写明（键/字段与类型/diffType/容差/表名）一次通过，口语化描述极易被闸门拦截** |
| `recon_trial_status` | `runId` 或 `ruleCode`+`version` | 试算进度与结论（逐项检查、覆盖等级、可批性）。生效须经 `recon_submit_approval` 审批 |
| `recon_register_table` | `table`（必填）、`action`（`preview`/`confirm`）、`operator` | 把一张业务表注册进闸门 1 白名单（见下节「动态表注册」） |
| `recon_import_file` | `file`（必填）、`action`（`preview`/`confirm`）、`table`（confirm 必填）、`operator` | 把导入目录里的 CSV/Excel 导入成表并注册白名单（见下节「文件导入」） |
| `recon_submit_approval` | `ruleCode`、`version`、`decision`（APPROVED/REJECTED）、`confirmedBy`（必填）、`confirmationCode`（**必填**）、`note` | 审批代办：Agent 只转发、不审批。`confirmationCode` 是用户从 REST（`POST /api/rule-reviews/{code}/{ver}/confirmation`）领到的**一次性确认码**（5 分钟过期、只能用一次、绑定 code/version/decision）—— 没有码直接拒绝，模型编不出码也就批不了 |

工具返回的是**给人看的中文文本**，不是 JSON —— Agent 拿到就能直接转述给运营，不用再解释字段含义。
参数缺失、规则不存在、任务号不存在这类错误都会被包成 `isError=true` 的结果文本，而不是让服务端崩掉。

## 动态表注册（M6）

每个接入方的数据结构不同：闸门 1 白名单原来是纯配置（`recon.agent.schema.tables`），新用户要改配置重启。
现在白名单 = **配置 ∪ 动态注册**（`recon_registered_table` 表，带登记人/时间审计），注册走对话两段式：

1. `recon_register_table {table: "t_order"}`（默认 `action=preview`）：**只读**表结构 + 行数，不改任何状态；
2. 人确认列清单没问题后：`recon_register_table {table: "t_order", action: "confirm", operator: "alice"}`：
   落库 + 立即生效 —— 起草规则（`recon_draft_rule`）与闸门 1 立刻能看见这张表，不用重启。

三条边界：① 注册**放宽的是安全边界本身**，confirm 必须由人确认，LLM 只有 preview 的权力；
② confirm 前内部重新探测表存在性；③ Web 实例与 MCP 进程是两个 JVM，白名单落库 + 30 秒 TTL
让两边对齐（本进程注册立即生效，另一进程最多等 30 秒或调 `refresh`）。

配套修复：表达式维度（`dimension: "CONCAT(...)"`）原来不做列校验，编造列名会漏到试算阶段
才以数据库原生 `Unknown column` 报错。现在闸门 1 用 `SELECT <expr> FROM <table> WHERE 1=0`
探针让数据库判定，违规建议里直接给出**可用列清单**，LLM 自纠正一轮就能修好；
子查询与写操作关键字在探针前就被拒绝。

## 文件导入（M7）

接入方手里只有原始数据文件（CSV/Excel）时，`recon_import_file` 补上最后一段管道：
**解析 → 建表 → 灌数 → 复用动态表注册落审计进白名单**，之后起草/对账与原生表完全一致。

同样是两段式：

1. `recon_import_file {file: "orders.csv"}`（默认 `action=preview`）：**只读**解析 ——
   规范化列名（对照原始表头）、推断的列类型、数据行数；
2. 人确认后：`recon_import_file {file: "orders.csv", action: "confirm", table: "recon_orders", operator: "alice"}`：
   建表灌数 + 注册，立即生效。

关键行为：

- **文件边界**：默认只接受导入目录（`recon.agent.file-import.dir`，默认 `./data/import`）内的相对路径，
  规范化后仍在目录内才放行 —— `../` 穿越与绝对路径都拒绝；
  本地自用可设 `recon.agent.file-import.allow-absolute-paths=true` 放行本地绝对路径
  （MCP 连接器场景保持默认关闭：导入工具本质是"读文件→入库"通道，放开等于把整台机器的文件递给智能体）；
  后缀限 `.csv` / `.xlsx` / `.xls`（对绝对路径同样生效）；
- **类型推断**：采样前 500 行 —— 全整数 → `BIGINT`，有小数 → `DECIMAL(24,6)`，
  形似日期 → `DATETIME`，超 255 字符 → `TEXT`，其余 `VARCHAR`（宽度取实测最大值），
  全空列给 `VARCHAR(255)`（宁可宽，不让灌数因推断激进而失败）；
- **列名规范化**：只保留 ASCII 字母数字（其余转 `_`）、空表头补 `col_N`、重名加序号 ——
  H2 与 MySQL 都合法；原文保留在 `originalHeaders` 里供人对照；
- **绝不覆盖**：目标表已存在直接拒绝 —— 同名表要么是笔误要么是上次导入的产物；
- **失败干净**：灌数/注册失败时把新建表删回去，不留"建了表没数据"的中间态；
- **护栏**：行列上限（`max-rows` 默认 10 万 / `max-columns` 默认 200）；
  标识符进 DDL 从严校验，数据值全部走 PreparedStatement 参数。

CSV 解析按 RFC 4180（引号转义、引号内换行、UTF-8 BOM），分隔符按首行探测（逗号/分号/制表符）；
Excel 只读第一个 Sheet，数字去尾零。

## 换对账维度 / 临时试算

对账维度（`dimension` / `measures` 等，完整字段见 [rule-dsl.md](rule-dsl.md)）本来就是规则体的一部分，
所以"换一套维度"= 提交一份新规则：调 `recon_define_rule` 提交规则体会落库为**草稿**并自动跑影子试算，**审批通过后才对后续任务生效**（不用改代码、不用重启）。

`recon_define_rule` 只接两个参数：`ruleBody`（必填，规则体原文，JSON 或 YAML 都行 —— 工具按首个字符是不是 `{` 判断走哪种解析）和 `operator`（可选，只记进版本历史，缺省记作 `mcp`）。下面这份规则体把"按订单号逐笔比金额"换成"按 客户+状态 汇总比金额"：

```json
{
  "code": "CUSTOMER_STATUS_TRIAL",
  "name": "客户+状态汇总对账",
  "left": {
    "label": "账单",
    "table": "bill_left",
    "keyType": "STRING",
    "dimension": ["customer_id", "status"],
    "measures": { "amount": "SUM(amount)" }
  },
  "right": {
    "label": "结算",
    "table": "bill_right",
    "keyType": "STRING",
    "dimension": ["customer_id", "status"],
    "measures": { "amount": "SUM(amount)" }
  },
  "shard": { "count": 1, "parallelism": 1 },
  "compare": [
    { "field": "amount", "type": "DECIMAL", "tolerance": 0.01, "diffType": "AMOUNT_MISMATCH", "severity": "HIGH" }
  ],
  "missing": { "leftMissingDiffType": "MISSING_LEFT", "rightMissingDiffType": "MISSING_RIGHT", "severity": "HIGH" }
}
```

`ruleBody` 是一个**字符串**（不是嵌套对象），所以调用时要把规则体序列化成一个字符串传进去：

```json
{"ruleBody": "{\"code\":\"CUSTOMER_STATUS_TRIAL\",\"name\":\"客户+状态汇总对账\",\"left\":{\"label\":\"账单\",\"table\":\"bill_left\",\"keyType\":\"STRING\",\"dimension\":[\"customer_id\",\"status\"],\"measures\":{\"amount\":\"SUM(amount)\"}},\"right\":{\"label\":\"结算\",\"table\":\"bill_right\",\"keyType\":\"STRING\",\"dimension\":[\"customer_id\",\"status\"],\"measures\":{\"amount\":\"SUM(amount)\"}},\"shard\":{\"count\":1,\"parallelism\":1},\"compare\":[{\"field\":\"amount\",\"type\":\"DECIMAL\",\"tolerance\":0.01,\"diffType\":\"AMOUNT_MISMATCH\",\"severity\":\"HIGH\"}],\"missing\":{\"leftMissingDiffType\":\"MISSING_LEFT\",\"rightMissingDiffType\":\"MISSING_RIGHT\",\"severity\":\"HIGH\"}}",
  "operator": "ops-alice"}
```

校验不过（和规则文件走同一套 `RuleParser` 校验）会返回 `isError` 并指出改哪里；通过则返回类似"规则 CUSTOMER_STATUS_TRIAL 已落库为 v1，对账维度：左 bill_left(维度=[customer_id, status])…"，
之后 `recon_list_rules` 能看到这条规则的新版本，`recon_run` 用 `ruleCode: "CUSTOMER_STATUS_TRIAL"` 就按新维度跑。
同一个 `code` 每提交一次就多一个版本（`version = max(version) + 1`），`recon_run` 默认取最新的启用版本。

**只想临时试算、不想落规则库**，就把规则体塞进 `recon_run` 的 `ruleJson`（同样是 JSON 或 YAML 原文）：

```json
{"ruleJson": "{\"code\":\"TEMP_AMOUNT_ONLY\",\"left\":{\"label\":\"账单\",\"table\":\"bill_left\",\"keyType\":\"STRING\",\"dimension\":[\"order_no\"]},\"right\":{\"label\":\"结算\",\"table\":\"bill_right\",\"keyType\":\"STRING\",\"dimension\":[\"order_no\"]},\"shard\":{\"count\":1,\"parallelism\":1},\"compare\":[{\"field\":\"amount\",\"type\":\"DECIMAL\",\"tolerance\":0.01,\"diffType\":\"AMOUNT_MISMATCH\",\"severity\":\"HIGH\"}],\"missing\":{\"leftMissingDiffType\":\"MISSING_LEFT\",\"rightMissingDiffType\":\"MISSING_RIGHT\",\"severity\":\"HIGH\"}}",
  "batchId": "trial-20260101", "remark": "临时只比金额"}
```

`ruleCode` 与 `ruleJson` 二选一：给了 `ruleJson` 就按内联规则跑（不写 `recon_rule` 表）；两个都不给会返回 `isError`，提示可以用 `recon_list_rules` 看有哪些规则。`batchId` 不填时自动生成 `mcp-<毫秒时间戳>`。

**规则版本和任务快照是两件事。**

- **规则版本**是规则库的当前状态：每次 `recon_define_rule` 追加一版，只影响**之后**创建的任务。
- **任务快照**是这一跑当时用的那份规则：任务落库时把 `rule_version` + `rule_snapshot` 一起写进 `recon_job`，
  重跑（和差异归因）优先用快照解析。所以**改了规则，历史任务重跑仍按旧快照执行**，语义不会漂移。
  内联规则（`ruleJson`）不落规则库、没有版本号，但同样存快照，临时试算的任务也能原样重跑。

## 预处理物化：把大表先算成模型表

一条规则跑到后面，瓶颈往往不是比较逻辑，而是**每轮都重新扫一遍明细再汇总**。`recon_build_model` 把这一步提前做一次，
落成模型表，之后每轮只读模型表：

| 步骤 | 做什么 |
| --- | --- |
| 1. `recon_build_model` | 按规则声明的粒度（`dimension` + `measures`，或行级规则的比对列）把两侧数据落成 `recon_model_*` 两张表，按 `biz_key` 建索引；同时注册一条 `<CODE>__MODEL` 规则 |
| 2. `recon_run` 传 `<CODE>__MODEL` | 就是对模型表做对账，差异口径与原规则逐条一致（同一套取数/比较/归因代码） |
| 3. 源数据变了 | 再调一次 `recon_build_model` 重建：先建影子表再改名切换，构建期间旧的模型表仍在服务 |

实测收益（1000 万行宽表按 `customer_id+status` 汇总，见 [benchmark.md](benchmark.md) §3.10）：
现状每轮 21.47 s；预处理一次 19.13 s 加建索引 0.28 s，之后每轮 **7.8 ms**（全量）/ 15.3 ms（单分片）；
模型表 5 MB，源表 4.2 GB —— 第二轮回本。

**什么时候不该建**：收益只来自"每轮都要重付的源侧扫描 + 汇总"，跟规则是不是行级无关、跟物化后行数比源表小多少有关。
汇总口径的规则，真实 MySQL 200 万行上实测 3.6 s → 0.16 s（建一次 5.4 s）；纯行级明细规则物化后行数不变，
建一次 28.6 s、之后每轮反而更慢（5 s → 12 s：多读一张表 + 多分片）。明细规则就别建了（见 [benchmark.md](benchmark.md) §3.10.2）。

几个实现上的选择，直接影响结果对不对：

- **不建主键**：行级规则本来就可能带重复业务键（重复键要留给引擎报 `DUPLICATE_KEY`），聚合维度也可能有 NULL。
  加主键会在构建阶段直接失败，而正确性不依赖模型表的唯一性，所以只用普通索引。
- **分片按生成列 `biz_key_bucket` 取模**：模型规则的分片条件写成 `biz_key_bucket = {index} AND biz_key_bucket < {shards}`，
  两侧用同一个桶函数，同一个业务键必然落到同一片 —— 否则同一个键在两侧落到不同分片，会凭空多出一堆单边缺失差异。
  MySQL 用 `CRC32(...)%n`；H2 没有 `CRC32`，用 `MOD(ORA_HASH(...), n)`：**H2 的 `ORA_HASH(expr, n)` 给出的是 0..n 共 n+1 个值**，
  不取模就会把落在第 n 个桶的行两侧都漏掉，而且一条差异都不会报（差异分类、行数看起来都正常）。
  键统一转成字符串再哈希，两侧同一个业务键哪怕列类型不同也落同一个桶；`COALESCE` 让 NULL 键也有桶，NULL 键的行照样参与对账。

## 自己验证（不用装客户端）

仓库里带了一个直接对着协议说话的 stdio 冒烟脚本：

```bash
node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar
```

它依次做 `initialize` → `tools/list` → 8 次工具调用（覆盖 7 个工具：`recon_run` 调两次 —— 原规则一次、模型规则一次；
`recon_define_rule` 只在 `tools/list` 里校验存在，不会真的改规则库），并断言：

- `tools/list` 里有 8 个工具，`recon_define_rule` 声明了必填 `ruleBody`、`recon_build_model` 声明了必填 `ruleCode`，`recon_run` 的四个参数都非必填
- **读模型表跑出来的差异分类与原始规则逐条一致**，扫描行数也一致（模型表是"窄化/汇总"，不是"换一套口径"）
- 未知规则被包成 `isError`，而不是抛异常断开连接
- **stdout 全程只有 JSON-RPC**（有 0 行噪声才通过）
- 关闭 stdin 后进程自行退出（`exit code=0`）

一次真实运行的关键输出：

```
initialize -> serverInfo={"name":"recon-lens","version":"0.1.0"} protocol=2025-06-18
tools/list -> recon_list_rules, recon_define_rule, recon_build_model, recon_run, recon_job_summary, recon_list_diffs, recon_explain_diff, recon_explain_job
recon_run  -> 任务 #1｜规则 BILL_VS_SETTLE｜批次 mcp-smoke｜状态 SUCCESS
              分片 4/4 完成｜扫描 左=20 右=21｜匹配 19｜差异 6
              差异分类：AMOUNT_MISMATCH=2，DUPLICATE_KEY=1，MISSING_LEFT=1，MISSING_RIGHT=1，STATUS_MISMATCH=1
recon_list_diffs -> #2 [MEDIUM] STATUS_MISMATCH 业务键=ORD20260004 字段=status 左=UNPAID → 右=PAID
                    #1 [HIGH] AMOUNT_MISMATCH 业务键=ORD20260003 字段=amount 左=3000 → 右=2999（相对偏差 0.0333%）
recon_explain_job -> 任务 #1 已生成 6 条归因（解释器=rule-template，LLM 未启用或未配置，已用规则模板降级）
recon_build_model -> 已为规则 BILL_VS_SETTLE（v1）建好模型表，分片 4：
                     - left 侧：recon_model_bill_vs_settle_1272d2d5_l，20 行，构建耗时 8 ms
                     - right 侧：recon_model_bill_vs_settle_1272d2d5_r，21 行，构建耗时 4 ms
                     已注册规则 BILL_VS_SETTLE__MODEL（v1），现在调 recon_run 传这个 code 就是对模型表做对账，差异口径与原规则一致
recon_run(BILL_VS_SETTLE__MODEL) -> 任务 #2｜状态 SUCCESS｜分片 4/4 完成｜扫描 左=20 右=21｜匹配 19｜差异 6
                     差异分类：AMOUNT_MISMATCH=2，DUPLICATE_KEY=1，MISSING_LEFT=1，MISSING_RIGHT=1，STATUS_MISMATCH=1（与原规则一致）
```

## 两个踩过的坑（stdio 传输的真实约束）

**1. stdout 是协议通道，日志必须让路。**
项目里没有任何 `System.out`，但 logback 自己的状态输出（版本号、configurator 清单等几十行）默认写 stdout ——
第一次冒烟就是它在 JSON-RPC 里插了 30 行。注意**光在配置文件里加 `statusListener` 太晚**（那些状态在解析配置之前就打出来了），
必须在 Spring 初始化日志系统之前设系统属性，所以 `ReconLensApplication.main` 里有一段：

```java
if (mcpProfileActive(args)) {
    System.setProperty("logback.statusListenerClass", "ch.qos.logback.core.status.NopStatusListener");
}
```

日志本身则由 `logback-spring.xml` 在 `mcp` profile 下全部改道 stderr。

**2. 协议版本。**
当前 SDK（`io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2`，2.0.1）支持到 `2025-06-18`，与冒烟客户端同版本，握手不再回落。
> 2.x 起上游把 `mcp` 拆成了 `mcp-core` + 各 JSON 实现，聚合包 `mcp` 只带 Jackson 3；本项目在 Spring Boot 3（Jackson 2）上，
> 所以 pom 里显式依赖 `mcp-core` + `mcp-json-jackson2`，避免同时拖进两套 Jackson。

## 边界

- **不做鉴权**：stdio 进程由调用它的客户端拉起，权限边界就是父子进程边界；对外暴露请走 REST + 网关
- **不做 HTTP/SSE 传输**：多客户端共享一个服务实例的场景应该用 REST 接口
- **只用 tools**：resources / prompts / sampling 都没接，因为对账这个场景就是"调工具拿结果"
- **规则以数据库为准**：`classpath` 下的 yml 只在规则库里还没有该 `code` 时作为 v1 导入一次，
  改规则走 `recon_define_rule`，落草稿 → 影子试算 → `recon_submit_approval` 审批通过才生效；
  （REST 的 `POST /api/rules` 是运维 legacy 通路，默认关闭，见 `recon.rules.legacy-define-enabled`）
  历史任务重跑用各自钉住的 `rule_snapshot`，不会被后来的规则改动改掉语义
