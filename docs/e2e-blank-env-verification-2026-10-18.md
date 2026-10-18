# 空白环境端到端验证（2026-10-18）

> 目的：把上一轮 `docs/agent-quickstart-verification-2026-10-18.md` 里的坑逐条拿到**空白环境**复验一遍：
> 旧库删除、旧进程杀掉、表从 `schema-mysql.sql` 重新建，数据用 `recon-data` 的 shard0 两个 CSV。
> 结论先说：**主链路全通，金额口径与独立实现逐类一致；过程中又踩到 1 个新 bug（已修）+ 1 个确认成立的漏检**。

## 0. 结论一览

| 项 | 结果 |
| --- | --- |
| 空库 → 导入 → 手写规则 → 人工审批 → 跑批 | ✅ 全通 |
| 差异计数（客户+订单号+支付时间键，`settle_amount` 容差 0.01） | ✅ **5,747**，与独立 Python 口径逐类相同 |
| `batchId` 缺省、`biz_key` 为 NULL 的错误信息 | ✅ 本轮修复生效（见 §4） |
| MCP 工具数 | ✅ 关 agent **9** / 开 agent **14** |
| `recon_trial_status` 按 `ruleCode + version` 查询 | ❌→✅ 原来直接报错（新 bug，已修，见 §5） |
| Agent 自批（relay 红线） | ❌ 仍成立：21 ms、`confirmedBy` 自填即 ACTIVE |
| `POST /api/models/{ruleCode}` 无鉴权 | ❌ 仍成立：不带任何密钥 200，建出 ACTIVE 规则 |
| 审批后篡改检测 | ⚠️ 冷缓存能拦（降级 REVIEWING），**热缓存直接放过**（见 §6） |

## 1. 环境

| 项 | 值 |
| --- | --- |
| 代码 | `HEAD = 0a5de71` + 100 项未提交改动（含本轮修复） |
| JDK / Maven | 17.0.20.1 / 3.9.16 |
| MySQL | 8.0.29，端口 **3307**，库 `recon_e2e`（验证后已 DROP） |
| 数据 | `recon-data/bill_left_shard0.csv` 625,000 行 × 20 列、`bill_right_shard0.csv` 624,955 行 × 20 列 |
| 规则 | `dimension: [customer_name, order_no, pay_time]`，`compare: settle_amount DECIMAL tolerance 0.01` |

清场动作：杀上一轮的 Web（PID 44316）与 mock-llm（PID 48452）→ `DROP DATABASE recon_agent_verify` → 删 `verify-run` 与 `data/import` 里的旧文件 → 重新 `mvn -DskipTests package`。

## 2. 步骤与耗时（本机实测）

| 步骤 | 耗时 | 说明 |
| --- | --- | --- |
| 打包（增量） | 3.0 s | `mvn -B -DskipTests package` |
| 建库 + `schema-mysql.sql` | < 1 s | 13 张表，0 行数据 |
| Web 实例启动 | **2.575 s** | `Started ReconLensApplication in 2.575 seconds` |
| `recon_import_file` preview | 1.59 s | 625,000 行 × 20 列，类型推断结果正确（`order_time/pay_time`→DATETIME、`*_amount`→DECIMAL(24,6)） |
| 导入左表 | 26.97 s | ≈ **23.2k 行/秒** |
| 导入右表 | 26.70 s | ≈ **23.4k 行/秒** |
| `recon_define_rule`（落草稿 + 同步试算） | **14.87 s** | 第二次 23.94 s（与全量测试并行，机器有负载） |
| REST 审批 submit → token → approve | < 1 s | 三次调用都是毫秒级 |
| 跑批（键含 NULL） | 24 s | 失败，见 §4 |
| 跑批（清洗后） | 18 s | 与 `mvn test` 并行，空闲时同规模实测约 11 s |
| `POST /api/models/{code}` | 13 s | 两侧各约 6 s（625k 行） |

## 3. 对账结果（三方核对）

引擎跑批（job id 5，`batchId=e2e-shard0-1`）：`left_rows=625000 / right_rows=624955 / matched=623724 / diff_rows=5747`，`recon_diff.biz_key` 无一条 NULL。

| 差异类型 | 独立 Python 口径 | 引擎（job 5） | `diff_expected_shard0.csv` |
| --- | --- | --- | --- |
| `AMOUNT_MISMATCH` | 3,240 | **3,240** | 3,240 |
| `DUPLICATE_KEY` | 106 | **106** | 106 |
| `MISSING_LEFT` | 1,125 | **1,125** | 1,125 |
| `MISSING_RIGHT` | 1,276 | **1,276** | 1,276 |
| 合计 | **5,747** | **5,747** | 5,747（+ `STATUS_MISMATCH` 15,128，本规则不比 `status`） |

独立口径脚本：`recon-data/verify_expected.py`（只按「客户+订单号+支付时间」拼键、`|Δsettle_amount| > 0.01` 判差异，不读引擎任何代码）。

## 4. 本轮修复的验证（batchId / biz_key）

**缺 `batchId` 不再裸 500**：`POST /api/jobs {"ruleCode":"AGENT_E2E_SHARD0","sync":true}` →

```json
{"jobId":4,"batchId":"api-20261018-022234","status":"FAILED","diffRows":0,
 "errorMsg":"分片0: IllegalStateException: 对账键为空（biz_key = NULL）……"}
```

**键为 NULL 给出可执行错误**：导入把空单元格写成 NULL（左 1,579 行 / 右 1,581 行 `customer_name` 为空），`CONCAT` 出来的 `__biz_key` 为 NULL，任务 FAILED 但 `errorMsg` 直接说清成因、排查 SQL（`SELECT COUNT(*) … WHERE 键列 IS NULL`）、清洗办法（`UPDATE 表 SET 列='' WHERE 列 IS NULL`）以及本条差异的 `diffType/field/shardId`。

按提示清洗两张表后重跑 → SUCCESS，5,747 条，与独立口径一致。

## 5. 新发现并已修复：`recon_trial_status` 按 `ruleCode + version` 不可用

**现象**（修复前，真实 MCP 调用）：

```json
{"isError":true,"content":[{"type":"text","text":
 "Tool (recon_trial_status) output validation failed: Validation failed: JSON schema validation errors:
  [ 未找到所需属性\"message\"]"}]}
```

**根因**：`mcp/AgentMcpTools.java` 给该工具声明了 `outputSchema`，`required` 是 `["status","inFlight","message"]`；
而 `appendTrial(...)` 只在「有会话消息（`completionMessage != null`）」时才写 `message`。
按 `runId` 查时任务对象里有消息，按 `ruleCode + version` 查时没有会话 → `message` 缺失 → MCP SDK 校验 `structuredContent` 直接失败，**工具整条不可用**。

**修复**：`message` 兜底为「试算结论见上；生效仍需人工审批」；新增回归用例
`McpToolCatalogTest.trialStatusByRuleCodeAndVersionCarriesTheRequiredMessageField`（断言 `status/inFlight/message` 三键齐全）。
修复后实测 56 ms 返回 `PASS × FULL`、`NO_SIGNOFF_REQUIRED`、逐项检查与起草说明。

全量测试：**393 用例 / 0 失败 / BUILD SUCCESS**（上一轮 392）。

## 6. 新发现未修复：篡改检测在「缓存命中」时被绕过

`RuleCatalog.resolve(code)` 先查 `latestCache`，**命中就直接返回，不再比对 `reviewed_spec_sha`**；只有冷缓存才会走 `verifyReviewedContent`。

复现（同一进程内）：

1. `GET /api/rules/AGENT_E2E_SHARD0`（把规则读进缓存）；
2. 直接改库：`UPDATE recon_rule SET spec_json = REPLACE(spec_json,'settle_amount','settle_amount_x')`；
3. `POST /api/jobs` → **SUCCESS，5,747**，`rule_snapshot` 里还是改前的 `settle_amount`（用的就是缓存里那份旧规则），`lifecycle` 仍是 ACTIVE。

重启进程（冷缓存）后同一个请求立刻被拦住：

```json
HTTP 400 {"error":"规则 AGENT_E2E_SHARD0 v1 的内容在审批后被修改（reviewed_spec_sha 不匹配），已降级回 REVIEWING，需重新审核后才能运行"}
```

库里 `lifecycle` 也确实变成 `REVIEWING` —— 检测逻辑本身是对的，缺的是「缓存不能绕过校验」。

**修复建议（二选一）**：
- 缓存命中时也做一次轻量校验：`SELECT reviewed_spec_sha, spec_sha FROM recon_rule WHERE code=?`（走唯一索引，亚毫秒），不读整行；
- 或给缓存加 TTL/版本号（`recon_rule.updated_at`），过期即重读。

## 7. 仍成立的开放问题（本轮复验）

| 问题 | 复验证据 |
| --- | --- |
| Agent 自批（relay 无强制力） | 定义 `AGENT_E2E_RELAY` 后直接 `recon_submit_approval`，`confirmedBy=e2e-self-approved`，**21 ms** 变 ACTIVE；全程无人工凭据 |
| `POST /api/models/{ruleCode}` 无鉴权 | 不带 `X-Api-Key` → **200**，13 s 建出 `AGENT_E2E_SHARD0__MODEL`，库里 `lifecycle=ACTIVE`、`trial_verdict=NULL`（绕过了草稿/试算/审批三道门） |
| `RuleOrigin` 误标 | MCP `recon_define_rule` 落库 `origin=HUMAN`（返回文案却写「来源 MCP」） |
| `NO_SIGNOFF_REQUIRED` 文案 | 工具输出「可批性：NO_SIGNOFF_REQUIRED（可直接批准）」，对模型是明确的放行暗示 |
| 试算同步阻塞 | `recon_define_rule` 一次调用 14.9~23.9 s，期间 MCP 会话不可用 |
| `/api/jobs` 无鉴权 | 全程未带任何 API Key 也能发起对账 |

## 8. 复现命令

```powershell
# 1) 空白库
mysql --host=127.0.0.1 --port=3307 -uroot -e "CREATE DATABASE recon_e2e DEFAULT CHARACTER SET utf8mb4"
mysql --host=127.0.0.1 --port=3307 -uroot --database=recon_e2e -e "source src/main/resources/schema-mysql.sql"

# 2) Web 实例（agent 工具也开）
java -Xmx1536m -Dfile.encoding=UTF-8 -jar target/recon-lens-0.1.0.jar `
  --spring.profiles.active=mysql `
  "--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/recon_e2e?...&useCursorFetch=true&rewriteBatchedStatements=true" `
  --spring.datasource.username=root --spring.datasource.password= `
  --recon.seed-rules=false --recon.agent.enabled=true `
  --recon.agent.schema.tables=bill_left_shard0,bill_right_shard0 `
  --recon.review.api-key=test-key --recon.review.token-secret=test-secret `
  --recon.agent.file-import.max-rows=1000000 --server.port=18080

# 3) MCP：导入（preview → confirm）→ 定义规则 → 查试算
node verify-e2e/mcp.mjs verify-e2e/steps-import.json
node verify-e2e/mcp.mjs verify-e2e/steps-define.json
node verify-e2e/mcp.mjs verify-e2e/steps-status.json

# 4) 人工审批（三步都带 X-Api-Key / X-Operator）
POST /api/rule-reviews/AGENT_E2E_SHARD0/1/submit
POST /api/rule-reviews/AGENT_E2E_SHARD0/1/token
POST /api/rule-reviews/AGENT_E2E_SHARD0/1/approve   {"token":"...","note":"..."}

# 5) 跑批
POST /api/jobs {"ruleCode":"AGENT_E2E_SHARD0","batchId":"e2e-shard0-1","sync":true}
```

证据文件：`D:\App\deepseek\workspace\verify-e2e\`（`mcp.mjs` 客户端、各步骤 `steps-*.json`、`last-run.json`、`web*-out.log`）。

## 9. 清理

验证结束后：停 Web 进程 → `DROP DATABASE recon_e2e` → 清空 `data/import`。
MySQL 上只剩系统库，端口 18080/8123 无监听。
