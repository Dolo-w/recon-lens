# Agent 本地运行指南（Quickstart）

> 实测环境：Windows + JDK 17 + MySQL 8（3307 端口）。以下命令全部经过 2026-10-17 / 2026-10-18 两轮实测验证。
> ⚠️ **3307/空密码是实测机的环境**，你的机器上没有这个实例是正常的——把下文所有连接串/账号/密码换成你已有 MySQL 的即可，**不要因此新装数据库**。
>
> 2026-10-18 那轮用一份 **10,000,000 行（2.1 GB）的 CSV 数据集**跑完整链路（文件导入 → 手写复合键规则 →
> 试算 → 人工审批 → 跑批 → 与独立算出的 45,093 条差异对账），过程记录见
> [agent-quickstart-verification-2026-10-18.md](agent-quickstart-verification-2026-10-18.md)。本文按那轮的坑做了修订：
> 补 `batchId`、复合键空值、多文件合并、手写规则路径、真实审批端点。

## 最短路径（照抄这六步，约 5 分钟到"跑完一次对账"）

| 步骤 | 做什么 | 缺了就卡在哪 |
|---|---|---|
| 1 | `mvn -DskipTests package` | 没有 jar，后面全是空谈 |
| 2 | 建空库 + 执行 `schema-mysql.sql` | 表不存在；**不要**让 `RECON_SQL_INIT=always` 去建 MySQL 库 |
| 3 | `node tools/mock-llm.mjs 8123` | 不起也行，但要换成真 LLM，否则起草 BLOCKED |
| 4 | 起 Web 实例（§2，`--spring.profiles.active=mysql`） | MCP 进程与它**必须同一个库**，否则草稿/试算查不到 |
| 5 | 把 CSV 放进 `data/import`，`recon_import_file` 预览→confirm（§4.5） | 数据进不来 |
| 6 | 规则：`recon_draft_rule`（LLM 起草）或 `recon_define_rule`（手写，§3）；审批走 §4 | 规则不生效，跑批才有数据 |

> 文档里所有命令都在**仓库根目录**执行。Windows 下 `^` 是 cmd 的续行符，PowerShell 用 `` ` ``（或干脆一行写完）。

## 0. 前置条件

| 组件 | 说明 |
|---|---|
| JDK 17+ | 打包与运行 |
| Maven 3.9+ | 打包。`mvn` 不在 PATH 时用全路径，如 `D:/App/deepseek/tools/apache-maven-3.9.16/bin/mvn.cmd` |
| Node 18+ | **只有**跑 `tools/mock-llm.mjs`、`tools/agent-smoke.mjs` 这类脚本时才需要，不跑可跳过 |
| MySQL 8 | **直接复用你机器上已有的 MySQL，不要新装**。只需建一个空库（名字任意，如 `recon_agent_verify`）并在库里执行 `src/main/resources/schema-mysql.sql`；业务表可手动建 `bill_left`/`bill_right` 各 10 行，或用文件导入（不需要 `gen_bill_data.sql` 那个百万行版） |
| 打包 | `mvn -DskipTests package` → `target/recon-lens-0.1.0.jar` |
| LLM | 两种选择：① 本地 Mock（`node tools/mock-llm.mjs 8123`，起草只会返回固定的演示规则，适合验链路）；② 任意 OpenAI 兼容端点（DeepSeek/OpenAI/本地 vLLM），起草才会真正按你的需求生成规则 |

## 1. 启动 LLM（Mock 或跳过用真 LLM）

```bash
cd D:/App/deepseek/workspace/recon-lens   # 不能省：脚本里的 jar/规则路径都是相对仓库根的
node tools/mock-llm.mjs 8123
# 验证：curl http://127.0.0.1:8123/__stats   （返回调用次数，能确认 LLM 通路真的被用到）
```

⚠️ **mock 只会返回写死的演示规则**（一张 `bill_left`/`bill_right` 的样板），它跟你自己的表无关：
用 mock 能验证"链路通不通"，但**别拿它的试算结论当业务结论**。要看真实起草效果，换真 LLM。

用真 LLM 就跳过这步，把第 2 步里的 `--recon.llm.*` 换成真实端点和 key 即可（例如 DeepSeek：`--recon.llm.base-url=https://api.deepseek.com/v1 --recon.llm.api-key=sk-xxx --recon.llm.model=deepseek-chat`）。

## 2. 启动 Web 实例（Agent 编排、审批、巡检都在这个进程）

```bash
java -Xms256m -Xmx1024m -jar target/recon-lens-0.1.0.jar ^
  --server.port=18080 ^
  --spring.profiles.active=mysql ^
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3307/recon_agent_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useCursorFetch=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false" ^
  --spring.datasource.username=root ^
  --spring.datasource.password= ^
  --recon.agent.enabled=true ^
  --recon.agent.perception-interval-ms=60000 ^
  --recon.llm.enabled=true ^
  --recon.llm.api-key=mock-key ^
  --recon.llm.model=mock-model ^
  --recon.llm.base-url=http://127.0.0.1:8123/v1 ^
  --recon.review.api-key=test-key ^
  --recon.review.token-secret=test-secret ^
  --recon.agent.schema.tables=bill_left,bill_right
```

命令要在仓库根执行（`target/recon-lens-0.1.0.jar` 是相对路径）。启动后探活：
`curl http://127.0.0.1:18080/api/rules/BILL_VS_SETTLE` —— 能返回规则 JSON，说明"库 + 表结构 + 规则加载"三者都对。

关键参数含义（都是实测踩过的坑）：

- `--recon.agent.enabled=true`：不开的话 Agent 工具和起草链路整个不存在；
- `--recon.agent.schema.tables=...`：**闸门 1 的表白名单，fail-closed** —— 不配等于"拒绝所有规则"，只放行你允许 agent 使用的表；
- `--recon.llm.enabled=true` + api-key：只配 base-url 不开 enabled 会被判"LLM 未配置"直接转人工（BLOCKED）；
- `--recon.review.api-key` + `token-secret`：审批接口与规则直写的密钥，**不配就一律 403**（fail-closed）；
- `--recon.agent.perception-interval-ms=60000`：巡检间隔；**0 = 关闭定时、只保留按需诊断**（不是"立刻循环"）；
- `--recon.engine.run-demo-on-startup=false`：默认 profile（H2）里是 **true**，启动即跑一遍演示对账；`mysql`/`mcp` profile 默认 false，连真实库时保持 false 即可，别无谓地往你库里写任务。

启动成功的标志：日志出现 `Started ReconLensApplication`，且无 `schema.tables 为空` 的 WARN。

## 3. 用 Agent 的三种方式

### 方式 A：MCP 客户端接入（推荐，真正"对话式"用 agent）

Agent 的起草入口是 MCP 工具 `recon_draft_rule` / `recon_trial_status`（stdio 协议）。在你的 MCP 客户端（Claude Desktop / Cursor / 任何支持 stdio MCP 的宿主）里加：

```json
{
  "mcpServers": {
    "recon-lens": {
      "command": "D:/App/deepseek/tools/jdk-17.0.20.1+1/bin/java.exe",
      "args": [
        "-jar", "D:/App/deepseek/workspace/recon-lens/target/recon-lens-0.1.0.jar",
        "--spring.profiles.active=mcp",
        "--recon.agent.enabled=true",
        "--recon.llm.enabled=true",
        "--recon.llm.api-key=mock-key",
        "--recon.llm.model=mock-model",
        "--recon.llm.base-url=http://127.0.0.1:8123/v1",
        "--recon.review.api-key=test-key",
        "--recon.review.token-secret=test-secret",
        "--recon.agent.schema.tables=bill_left,bill_right"
      ],
      "env": {
        "RECON_SQL_INIT": "never",
        "SPRING_DATASOURCE_URL": "jdbc:mysql://127.0.0.1:3307/recon_agent_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useCursorFetch=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false",
        "SPRING_DATASOURCE_USERNAME": "root",
        "SPRING_DATASOURCE_PASSWORD": "",
        "SPRING_DATASOURCE_DRIVER_CLASS_NAME": "com.mysql.cj.jdbc.Driver"
      }
    }
  }
}
```

**MCP 模式的四个坑（实测踩过）**：
1. `RECON_SQL_INIT=never` 必须设 —— schema.sql 是 H2 方言，不对 MySQL 跳过就启动即崩；
2. `DRIVER_CLASS_NAME` 必须显式给 —— 只给 URL 不给驱动，Hikari 仍用默认 H2 驱动报"claims to not accept jdbcUrl"；
3. env 里的数据源必须与第 2 步 Web 实例**同一个库** —— 草稿、试算结论、审批全靠共享数据库状态；
4. stdio 通道只走 stdout（JSON-RPC），日志走 stderr，别往 stdout 打印东西。

接入后就能这样用：

```
你：帮我起草一条对账规则，按客户 + 订单号 + 支付时间对账，金额容差 0.01
Agent（调 recon_draft_rule）→ 立即返回 runId，起草 + 闸门 + 试算在后台跑
你：试算结果怎么样了？
Agent（调 recon_trial_status，传 runId 或 ruleCode+version）→ 逐项检查 + 覆盖等级 + 可批性
你：通过
Agent（调 recon_submit_approval，confirmedBy = 你的标识）→ 提示你去领确认码
你：在 REST 上 POST .../confirmation → 拿到 6 位码 → 告诉 Agent
Agent（带 confirmationCode 再调 recon_submit_approval）→ 规则 ACTIVE
```

- `recon_trial_status` 两种用法等价：`{runId}` 或 `{ruleCode, version}`。**试算通常 10~15 秒**，第一次查多半
  `inFlight=true`，隔几秒再查，别当成卡死；
- 生效的最后一步必须由人签发：**没有用户从 REST 领取的 `confirmationCode`，`recon_submit_approval` 直接拒绝**
  （码 5 分钟过期、只能用一次、绑定 `(规则, 版本, 决策)`）。Agent 只负责转发，编不出码也就批不了。

### 3.5 不想让 LLM 起草？直接手写规则（`recon_define_rule`）

engine 侧常驻的 `recon_define_rule` 接受**规则文件里的那份配置**（JSON 或 YAML 文本，字段见
`recon_list_rules` 的输出：`code/name/left/right/compare/missing/duplicate/shard/options`）：

```json
recon_define_rule {
  "ruleCode": "MY_BILL_RECON",
  "operator": "wang",
  "ruleBody": "code: MY_BILL_RECON\nname: 客户对账\nleft:\n  label: 账单\n  table: bill_left\n  keyType: STRING\n  dimension: [customer_name, order_no, pay_time]\nright:\n  label: 结算\n  table: bill_right\n  keyType: STRING\n  dimension: [customer_name, order_no, pay_time]\nshard:\n  count: 1\ncompare:\n  - field: settle_amount\n    type: DECIMAL\n    tolerance: 0.01\n"
}
```

`ruleBody` 就是规则文件那份内容（JSON 或 YAML 字符串都行），两侧的 `dimension` 放**裸列名**，
引擎会生成 `CONCAT(a, '|', b, '|', c) AS __biz_key` 并按它归并。完整字段与三种取数写法
（`keyField` / `sql` + `keyField` / `dimension` + `measures`）见 [rule-dsl.md](rule-dsl.md)。

- 落库即 **DRAFT，不会直接生效**（MCP 通路没有直通 ACTIVE 的口子）；
- 调用**立即返回**，影子试算在后台排队（单次约 1 分钟），用 `recon_trial_status` 轮询结论；
  结论里带可批性和逐项检查；
- 闸门 1（表白名单）、闸门 2（维度/比对列必须是物理列）、闸门 3（影子试算）照常生效，被拒时返回原文原因，
  规则仍是草稿，改完再提新版本即可；
- 之后走 §4 的审批把它变成 ACTIVE，再 `recon_run` 跑真实数据。

### 方式 B：一键冒烟（验证链路是否通）

```bash
# 依赖：mock-llm 已启动、18080 Web 实例已启动、bill_left/bill_right 有数据
node tools/agent-smoke.mjs target/recon-lens-0.1.0.jar
# 16 项断言：起草 → 闸门 → 影子试算 → 人工审批 → 实跑 → 一致性
```

### 方式 C：不用 MCP 客户端，直接手动调工具

`tools/agent-probe.mjs`（如果没保留，参考 agent-smoke.mjs 的 send/call 骨架）就是裸 JSON-RPC 说话的最小客户端：initialize → tools/call(recon_draft_rule) → 轮询 recon_trial_status。

### 接入方数据结构不同？先注册表（M6）

agent 对表结构零假设 —— 全部结构信息来自白名单 + 数据库元数据 + 起草 prompt。所以换一批用户
就是换一批表：闸门 1 白名单 = **配置（`recon.agent.schema.tables`）∪ 动态注册**（落库审计，带登记人）。

对话内注册走 `recon_register_table` 两段式：

```
① recon_register_table {table: "cust_bills"}                       # preview：只读列清单+行数，不改任何状态
② 人确认列没问题 → recon_register_table {table: "cust_bills",
                                          action: "confirm",
                                          operator: "alice"}        # 立即生效，不用重启
③ recon_draft_rule 针对（刚注册的）表起草规则
```

- 注册**放宽的是安全边界本身**：preview 可以随便看，confirm 必须人确认，LLM 没有确认权；
- 表名不合法（引号/分号/空白）在 preview 就被拒，注不进去；
- Web 实例与 MCP 进程两个 JVM 靠共享库对齐白名单（30 秒 TTL + 本进程注册即时生效）；
- 两侧字段不同名（如 `pay_amt` vs `settle_amt`）时规则里用 `sql:` 别名对齐，引擎要求比对字段同名是有意防护。

## 4. 审批（两条路：对话内 agent 转发确认码 / REST 手工）

**对话内（推荐）**：`recon_trial_status` 出结论后，agent 把 verdict/coverage/可批性讲给用户听。
用户明确表态"通过"或"拒绝"后，**由用户在 REST 上领一串一次性确认码**，再把它原样转给 agent：

```bash
# 用户自己执行（人在这里做决策 + 签发凭据）
curl -X POST "http://127.0.0.1:18080/api/rule-reviews/AGENT_BILL_CHECK/1/confirmation" \
  -H "X-Api-Key: test-key" -H "X-Operator: wang" -H "Content-Type: application/json" \
  -d '{"decision":"APPROVED"}'
# → {"confirmationCode":"7K3M9Q2F","issuedBy":"wang","expiresAt":"…","ttlSeconds":300,"hint":"…"}
```

```
# agent 拿着这串码代办（它编不出来，也就批不了）
recon_submit_approval {ruleCode: "AGENT_BILL_CHECK", version: 1, decision: "APPROVED",
                       confirmedBy: "wang", confirmationCode: "7K3M9Q2F", note: "用户确认通过"}
```

确认码的三条硬性质：**5 分钟过期、只能用一次、绑定 `(规则, 版本, 决策)`**。
漏了 `confirmationCode` 工具直接报"confirmationCode 必填"，拿别的规则/决策的码也会被拒；
用错一次不核销，码仍可用于原本的决策。

> ✅ **机制已落地**：早先这条"必须问过用户"只写在工具描述里，代码不校验 —— `confirmedBy` 是模型自填的
> 自由文本，一次调用就把 submit → token → approve 全做完（实测 47 ms 自批）。现在入口必须持码。
> 另外 `NO_SIGNOFF_REQUIRED` 这个可批性等级改名为 `NO_SIGNOFF_REQUIRED` 语义的文案（"该结论未必需要签字"），
> 仍然需要用户点一次确认。

**REST 手工**（三步，等价于上面的领码 + 审批，推荐由人自己执行）：

```bash
KEY=test-key; CODE=AGENT_BILL_CHECK; VER=1
# 1) 提交审核
curl -X POST "http://127.0.0.1:18080/api/rule-reviews/$CODE/$VER/submit" -H "X-Api-Key: $KEY" -H "X-Operator: wang"
# 2) 领审批 token（绑定内容哈希，规则内容变了 token 作废）
curl -X POST "http://127.0.0.1:18080/api/rule-reviews/$CODE/$VER/token" -H "X-Api-Key: $KEY" -H "X-Operator: wang"
# 3) 批准（带 token + 签字说明）
curl -X POST "http://127.0.0.1:18080/api/rule-reviews/$CODE/$VER/approve" \
  -H "X-Api-Key: $KEY" -H "X-Operator: wang" -H "Content-Type: application/json" \
  -d '{"token":"<上一步返回的 approvalToken>","note":"试算通过，批准"}'
```

审批通过后规则 ACTIVE，直接跑批：

```bash
curl -X POST http://127.0.0.1:18080/api/jobs \
  -H "X-Api-Key: test-key" -H "Content-Type: application/json" \
  -d '{"ruleCode":"MY_BILL_RECON","batchId":"2026-10-18-账单","sync":true}'
# → {"jobId":9,"ruleCode":"MY_BILL_RECON","ruleVersion":1,"batchId":"2026-10-18-账单",
#    "status":"SUCCESS","diffRows":5747}
```

- `batchId` 是 `recon_job` 的非空列，**建议显式传**（同一批数据的多次对账靠它串联）。省略也行：
  服务端自动补 `api-<yyyyMMdd-HHmmss>` 并在响应里回显实际用的值；
- `sync=true` 同步等结果（十万级约 10~15 秒）；`sync=false` 返回 `jobId` + 202，之后 `GET /api/jobs/{id}` 轮询；
- **失败任务也回传 `errorMsg`** —— 失败原因就写在那里，不用去翻服务端日志；
- **写接口鉴权**：配了 `recon.security.api-key` 之后，`POST /api/jobs`、`POST /api/models/{ruleCode}`、
  `DELETE /api/rules/{code}` 都要求 `X-Api-Key`（常量时间比较）。没配则启动时 WARN"当前不鉴权"。

## 4.2 复合键、空值和容差：三个最容易踩的点

**① 键列里有 NULL，整个分片会失败。** `dimension` 生成 `CONCAT(a,'|',b)`，任一列为 NULL 结果就是 NULL，
而 `recon_diff.biz_key` 是 NOT NULL → 差异落不了库、分片失败。现在的报错会指到列并给出处理办法：

```
对账键为空（biz_key = NULL），这条差异无法落库，分片会整片失败。
成因：规则用 dimension 拼键时会生成 CONCAT(列1,'|',列2,…)，键列里只要有一个是 NULL，整个键就是 NULL …
排查：对两侧的键列各跑一次 SELECT COUNT(*) … WHERE 键列 IS NULL
处理：先清洗键列（如 UPDATE 表 SET 列='' WHERE 列 IS NULL），或改用不含空值的列做键 …
本条差异：diffType=AMOUNT_MISMATCH，field=settle_amount，shardId=0。
```

```sql
-- 先查清楚有多少行、哪几列
SELECT COUNT(*) FROM bill_left WHERE customer_name IS NULL OR order_no IS NULL OR pay_time IS NULL;
-- 再按业务口径清洗（空串还是占位符由业务定）
UPDATE bill_left SET customer_name = '' WHERE customer_name IS NULL;
```

> 文件导入把 CSV 里的**空单元格写成 NULL**（不是空串），所以"源文件有空值"就会踩这个坑。
> 不想改数据的话，`keyField` 里用 `COALESCE(...)` 自己拼键也行 —— 但 `keyField` 必须是**物理列**，
> 表达式要用 `dimension` 写法，见 [rule-dsl.md](rule-dsl.md)。

**② 容差是"超过才算差异"。** `tolerance: 0.01` 表示 `|左 − 右| > 0.01` 才记差异，**恰好等于 0.01 不算**；
百分比用 `tolerancePercent`（小数比率：`0.0001` = 0.01%）。实测里那 1 条恰好 0.01 的记录就是被这条排除的，
所以 45,093 而不是 45,094。

**③ 键值里含分隔符。** 默认用 `|` 拼复合键，列值本身带 `|` 会拼出歧义键；显式设 `dimensionSeparator: "#"`
（**两侧必须一致**），或改用 `sql` + `keyField` 自己控制键格式。

## 4.5 文件导入（用户只有 CSV/Excel 原始数据时）

接入方没有任何库表、只有数据文件时，用 `recon_import_file` 一条龙：**解析 → 建表灌数 → 注册白名单**，
之后起草/对账与原生表完全一样。

```bash
# 1) 把文件放进导入目录（默认只接受该目录内的相对路径）
mkdir -p data/import && cp ~/Downloads/账单.xlsx data/import/

#    本地自用、数据就在自己机器的任意目录？加一个参数放开绝对路径，免拷贝：
#    --recon.agent.file-import.allow-absolute-paths=true
#    之后直接说"预览导入 D:/mydata/账单.xlsx"即可（后缀白名单等护栏不变）
#    MCP 场景（连接器暴露给智能体）保持默认关闭 —— 别把整台机器的文件递给它

# 2) 预览（只读：规范化列名 + 推断类型 + 行数，不改任何状态）
#    在对话里说"用 recon-lens 预览导入 账单.xlsx"，或 MCP 直接调：
#    recon_import_file {file: "账单.xlsx"}
#    → {"columns":["order_no",…],"originalHeaders":["订单号",…],"columnTypes":["VARCHAR(64)",…],"rows":100000}
# 3) 人确认后 confirm：table 指定目标表名，已存在的表会被拒绝
#    ⚠️ file 必须再传一次 —— 两段式不是会话状态，confirm 只认这一次调用里的参数
#    recon_import_file {file: "账单.xlsx", action: "confirm", table: "recon_bills", operator: "wang"}
#    → {"imported":true,"rows":100000,"columns":[…],"message":"…"}
#
#    大文件（几十万行）加 async:true 立刻返回任务号，别赌客户端不超时：
#    recon_import_file {file: "账单.xlsx", action: "confirm", table: "recon_bills", async: true}
#    → {"taskId":7,"status":"QUEUED",…}    然后 recon_import_status {taskId: 7}
# 4) 直接起草规则（或按 §3.5 手写）
#    recon_draft_rule {businessDescription: "按流水号比对 recon_bills 和对侧表的金额"}
```

行为与护栏（详见 [mcp.md](mcp.md) 的「文件导入」节）：

- 默认文件必须在 `recon.agent.file-import.dir`（默认 `./data/import`）内，`../` 穿越与绝对路径都拒；
  设 `recon.agent.file-import.allow-absolute-paths=true` 后放行本地绝对路径（本地自用推荐，MCP 场景别开）；
- 类型自动推断（BIGINT / DECIMAL(24,6) / DATETIME / VARCHAR / TEXT），中文表头规范化成 `col_N` 且保留原文对照；
- CSV 支持 RFC 4180 引号转义、BOM、分号/制表符分隔；Excel 读第一个 Sheet（可用 `sheet` 指定名称或序号）、数字去尾零；
  字段里的 `\N` / `NULL` 字面量按空值处理（MySQL dump 常见），引号没闭合、某行比表头多列都会明确报错；
- **编码只支持 UTF-8**（BOM 可以）。GBK/ANSI **会明确报错**（"CSV 不是合法的 UTF-8…"），不再静默入库乱码 ——
  按提示另存为 UTF-8 后重导（Excel：「另存为 → CSV UTF-8」）；
- **绝不覆盖已有表**；灌数失败自动删回新建表；默认上限 10 万行 / 200 列 / 512 MB（`recon.agent.file-import.max-rows`、
  `max-columns`、`max-bytes` 可调）；单个字段超过 1M 字符会报"引号多半没闭合"；
  某行比表头多列会报错而不是静默截断；灌数速度实测约 2.6 万行/秒。

> **百万行级数据别走 `recon_import_file`**：单线程灌数 + 默认 10 万行上限，是为中小文件设计的。
> 百万/千万行级改用 `tools/parallel-load.mjs` 并行灌数（N 并发 LOAD DATA + 先灌数后建索引，
> 千万行实测约 3 分钟），灌完再 `recon_register_table` 注册进白名单。**不要让 Agent 自己写脚本
> 单线程逐行 INSERT** —— 那会比并行方案慢一个数量级以上。

## 4.6 多个 CSV 怎么合成一张表（分片、分月导出）

引擎只认"一张左表 + 一张右表"，但表可以是视图，也可以让规则用 `sql:` 取数，所以拼接有两条路：

| 情况 | 做法 |
|---|---|
| 同一批数据切成 N 个文件 | 每个文件 `confirm` 成一张表（`bill_202610_01` …），再建视图合并：`CREATE VIEW bill_left_all AS SELECT * FROM bill_202610_01 UNION ALL SELECT * FROM …`；规则里 `table: bill_left_all`，`dimension` 照常写 |
| 左右两侧列名不同 | 各导各的表；列不同名时用视图把列名对齐（引擎要求两侧比对字段同名，这是有意防护） |
| 单文件超过 10 万行 | 调大 `--recon.agent.file-import.max-rows`（注意内存），或按分片列拆文件后 UNION ALL |
| 文件已在库里、想复用现有 SQL | 规则里用 `sql:`，在 SELECT 里把业务键别名为 `keyField`；分片谓词要能作用到这段 SQL 的列上 |

> 视图方案的前提：两侧视图都要能按业务键升序取数（引擎按归并算法对账），并且分片条件
> `whereLeft/whereRight` 里的列在视图里可见。

## 5. 在 WorkBuddy 里直接用这个 Agent

WorkBuddy 原生支持自定义 MCP 连接器，把 recon-lens 注册进去后，在 WorkBuddy 对话里就能直接起草/查询规则。

**第 1 步**：编辑 `C:\Users\wangs\.workbuddy\mcp.json`（没有就新建），加入（与方式 A 同构，LLM 换成真实端点）：

```json
{
  "mcpServers": {
    "recon-lens": {
      "command": "D:/App/deepseek/tools/jdk-17.0.20.1+1/bin/java.exe",
      "args": [
        "-Xms128m", "-Xmx512m", "-jar",
        "D:/App/deepseek/workspace/recon-lens/target/recon-lens-0.1.0.jar",
        "--spring.profiles.active=mcp",
        "--recon.agent.enabled=true",
        "--recon.llm.enabled=true",
        "--recon.llm.base-url=https://api.deepseek.com/v1",
        "--recon.llm.api-key=<你的 DeepSeek key>",
        "--recon.llm.model=deepseek-chat",
        "--recon.review.api-key=test-key",
        "--recon.review.token-secret=test-secret",
        "--recon.agent.schema.tables=bill_left,bill_right,perf_left,perf_right"
      ],
      "env": {
        "RECON_SQL_INIT": "never",
        "SPRING_DATASOURCE_URL": "jdbc:mysql://127.0.0.1:3307/recon_agent_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useCursorFetch=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false",
        "SPRING_DATASOURCE_USERNAME": "root",
        "SPRING_DATASOURCE_PASSWORD": "",
        "SPRING_DATASOURCE_DRIVER_CLASS_NAME": "com.mysql.cj.jdbc.Driver"
      }
    }
  }
}
```

**第 2 步**：重启/回到 WorkBuddy，在连接器管理页面顶部的「自定义连接器」入口找到 recon-lens，点 **信任** 启用（新写的 mcp.json 不会自动激活）。

**第 3 步**：对话直接用：

```
用 recon-lens 帮我起草一条对账规则：按订单号逐笔比对 bill_left 和 bill_right 的金额，容差 0.01 元
```

WorkBuddy 会自动调 `recon_draft_rule`（内部由 DeepSeek 起草规则）→ 你再问"试算结果"，它调 `recon_trial_status` 轮询。

**审批怎么办**：走对话内 `recon_submit_approval`（用户表态后 agent 代办，注意 §4 的红线：代码不校验"真的问过你"）。想让审批权完全留在人手里：另起 Web 实例用第 4 节的 REST 三连 curl —— 前提是 Web 实例已启动且与 MCP 进程**同一个库**。

**与方式 A 的差别**：① LLM 换真实端点（key 直接写进 args，注意 mcp.json 是明文，别提交到仓库）；② WorkBuddy 自己的大模型负责"听懂你的需求 + 组织工具调用"，recon-lens 内部的 DeepSeek 只负责"起草规则 JSON"——两层 LLM 各司其职。

## 6. 排障速查

| 症状 | 原因与解法 |
|---|---|
| 起草 BLOCKED"大模型未启用" | 漏了 `--recon.llm.enabled=true`（或 api-key 为空） |
| 起草 BLOCKED"闸门 1 拒绝（表不在白名单）" | `recon.agent.schema.tables` 没包含规则用到的表 |
| 闸门 2 拒绝 `UNKNOWN_COLUMN @left.keyField` | `keyField` 只能是**物理列**；复合键/表达式键用 `dimension` 写 |
| 任务 FAILED，`对账键为空（biz_key = NULL）` | 键列里有 NULL：先清洗（§4.2），或换不含空值的列做键 |
| 任务 FAILED，`Column 'biz_key' cannot be null`（旧版本） | 同一条问题的老报错文案，升级后会给可定位提示 |
| `POST /api/jobs` 返回 500 且响应体没有原因 | 老版本缺 `batchId` 就会这样；现在缺省自动补并在响应里回显 |
| 试算查了两次都是 `inFlight=true` | 试算要 10~15 秒，隔几秒再查；不是卡死 |
| 导入后数据全是乱码 | 已不会发生：非 UTF-8 现在会报错并提示转码；按提示另存为 UTF-8 后重导 |
| 审批 403 | 没配 `recon.review.api-key`，或 X-Api-Key 不对；token 接口还要求 X-Operator |
| MCP 连 MySQL 启动崩 | 设 `RECON_SQL_INIT=never` + 显式 `DRIVER_CLASS_NAME` |
| 会话有结论但 recon_rule 查不到 | MCP 进程写进了 H2 内存库 —— 检查 env 数据源四个变量是否都给了 |
| 巡检没跑 | `perception-interval-ms=0` 的语义是关闭定时（只留按需诊断），设正值才轮询 |
