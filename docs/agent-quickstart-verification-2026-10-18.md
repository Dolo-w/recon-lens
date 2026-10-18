# recon-lens「Agent 对接指南」可执行性验证报告

- 验证日期：2026-10-18（本机 Win11 / JDK 17.0.20 / MySQL 8.0.29 :3307 / Node 24.16）
- 验证对象：`docs/agent-quickstart.md`（255 行）+ 当前工作树（`HEAD = 0a5de71`，未提交 100 项）
- 验证数据：`D:\App\deepseek\workspace\recon-data\bill_left_shard0.csv`（625,000 行）+ `bill_right_shard0.csv`（624,955 行），20 列
- 对账口径（用户指定）：键 = 客户 + 订单号 + 支付时间；金额 `settle_amount` 偏差 > 0.01 记差异
- 参与方：**新用户视角**由独立子 agent 执行（不读任何评审文档、不改源码，只照文档动手）；**审批人视角**由我本人执行（REST 三连 + 跑批复算）

---

## 0. 一句话结论

**主干能跑通，结果可复算，但照文档原文照抄会撞 3 个硬墙；另有 1 处设计与"agent 只转发不审批"的要求不符，实测被 agent 自批绕过。**

- 能跑通的部分：打包 → 起服务 → 导入 → 手写规则 + 影子试算 → 审批 → 跑批 → 拉差异，全部用文档里已有的 REST / MCP 能力完成，**机器时间约 1 分 20 秒**（排错不计）。
- 结果正确性：引擎产出 `AMOUNT_MISMATCH 3240 / MISSING_RIGHT 1276 / MISSING_LEFT 1125 / DUPLICATE_KEY 106` = **5,747**，与我的 Python 独立口径**逐项完全相等**，且与 `diff_expected_shard0.csv` 的差额（7,673 − 5,747 = 1,926）正好等于规则没比的 `STATUS_MISMATCH`。
- 撞墙的部分：`POST /api/jobs` 少传 `batchId` 直接 500（响应体还不给原因）；复合维度键遇空值让整个任务 FAILED；文档里的"返回会话号 → `recon_trial_status` 轮询"在手写规则路径上不存在。

---

## 1. 实测链路与耗时（本机，单分片）

| 阶段 | 动作 | 实测耗时 | 备注 |
| --- | --- | --- | --- |
| 构建 | `mvn -DskipTests package` | 3.6 s | 增量编译，jar 50,319,641 B |
| 建库 | `DROP/CREATE DATABASE` + `schema-mysql.sql` | 2.1 s | 27 张表 |
| 起服务 | `java -jar` + `--spring.profiles.active=mysql` | 2.154 s（进程 3.1 s 可访问） | 冷启动 |
| 导入预览 | `recon_import_file` preview ×2 | 1.24 s / 1.21 s | **会完整数一遍行数**，不是采样 |
| 导入落库 | `recon_import_file` confirm ×2 | 23.81 s / 23.97 s | 625,000 行 → **≈26.3k 行/s**（含建表+注册白名单） |
| 写规则 | `recon_define_rule`（内部同步跑影子试算） | 12.03 s（我的另一次 14.8 s，含 JVM 冷启动） | 试算样本 3,424 左 / 3,418 右 |
| 审批（agent 路径） | `recon_submit_approval` | **0.05 s** | 子 agent **未经任何人工确认**自批成功 |
| 审批（人工路径） | `POST /api/rule-reviews/{code}/{v}/submit` → `token` → `approve` | 三次调用均 <1 s | 见 §6 |
| 跑批（失败样本） | `POST /api/jobs` 缺 `batchId` | 0.43 s | HTTP **500**，响应体无原因 |
| 跑批（失败样本） | `POST /api/jobs` 键列含 NULL | 7.0–7.1 s | job FAILED，返回 HTTP **200** |
| 跑批（成功） | `POST /api/jobs` + `batchId` | 11.1–12.0 s | 1,250,000 行入参，5,747 条差异 |
| 拉汇总 | `GET /api/diffs/summary?jobId=` | 0.02 s | 四类计数 |

**关键数字**：左侧 625,000 / 右侧 624,955 / 匹配 623,724 / 差异 5,747（同 `matched` 的算术闭合：625,000 = 623,724 + 1,276；624,955 = 623,724 + 1,125 + 106）。

---

## 2. 结果正确性：三方口径对账

| diff_type | 引擎（job 5，agent 跑） | 引擎（job 9，我人工跑） | 我的 Python 独立口径 | `diff_expected_shard0.csv` | 说明 |
| --- | --- | --- | --- | --- | --- |
| AMOUNT_MISMATCH | 3240 | 3240 | 3240 | 3240 | 一致 |
| MISSING_RIGHT | 1276 | 1276 | 1276 | 1276 | 一致 |
| MISSING_LEFT | 1125 | 1125 | 1125 | 1125 | 一致 |
| DUPLICATE_KEY | 106 | 106 | 106 | 106 | 一致（右表同键 2 行） |
| STATUS_MISMATCH | 0 | 0 | 0 | **1926** | 规则只比金额、不比状态，属预期 |
| **合计** | **5747** | **5747** | **5747** | 7673 | 7673 − 1926 = 5747 |

- 两条路径（agent 自批后跑、我人工审批后跑）产出的差异**逐类完全相同**，说明引擎与量度口径稳定。
- 全量口径（8 个分片，我的脚本）：`MISSING_RIGHT 9985 / DUPLICATE_KEY 990 / MISSING_LEFT 9000 / AMOUNT_MISMATCH 25118` = **45,093**；`diff_expected_*.csv` 合计 60,222，差额 15,129 = 15,128 条 `STATUS_MISMATCH` + 1 条"偏差恰好 0.01"（本数据实际未命中，见 §4.3）。
- 未覆盖的口径：**"偏差正好 0.01 不算差异"这条规则没有被真实数据覆盖**（生成器分币级随机，概率 ~1/99999）。子 agent 构造了 400 行黄金样本想验证，因表未进白名单 + `confirm` 漏传 `file` 未跑成，如实计为**未验证项**。

---

## 3. 阻断级问题（照文档照抄必失败）

### 3.1 `POST /api/jobs` 的示例缺 `batchId` → HTTP 500，且不给原因

文档 §4 写的是 `POST /api/jobs {"ruleCode":"…","sync":true}` 就能跑。实测：

```
HTTP 500
{"timestamp":"2026-10-17T18:02:06.429+00:00","status":500,"error":"Internal Server Error","path":"/api/jobs"}
```

- 根因：`recon_job.batch_id` 为 `NOT NULL`（`src/main/resources/schema-mysql.sql:14`），`JobRequest.batchId` 为空时 INSERT 违反约束：
  `DataIntegrityViolationException ... INSERT INTO recon_job (…) ... Column 'batch_id' cannot be null | 根因 SQLIntegrityConstraintViolationException: Column 'batch_id' cannot be null`
- 500 响应体只有 timestamp/status/path，**没有任何原因**，新用户无从下手。子 agent 与我各自独立复现一次。
- 绕过：请求体加 `"batchId":"…"`。

### 3.2 复合维度键不是 NULL 安全的，一次空值就让整个任务 FAILED

- 链式成因：
  1. `src/main/java/io/github/dolow/reconlens/agent/FileImporter.java:614`：`one[i] = v == null || v.isBlank() ? null : convert(v, types.get(i));` —— CSV 空字段被灌成 **NULL**（本数据左 1,579 行、右 1,581 行 `customer_name` 为空）；
  2. `dimension: [customer_name, order_no, pay_time]` 生成 `CONCAT(customer_name,'|',order_no,'|',pay_time)`，任一为 NULL → 整键为 NULL；
  3. `recon_diff.biz_key` 为 `NOT NULL` → 分片 INSERT 失败：
     `Column 'biz_key' cannot be null | 根因 SQLIntegrityConstraintViolationException: Column 'biz_key' cannot be null`
- 失败时的对外表现也差：`sync:true` 仍返回 **HTTP 200**，只把 `status` 标成 `FAILED`，错误原文只在 `recon_job.error_msg` 里（`GET /api/jobs/{id}` 不带）。
- 想改用 `keyField: "CONCAT(COALESCE(customer_name,''), …)"` 做成 NULL 安全键，会被闸门 1 直接拒：
  `UNKNOWN_COLUMN @left.keyField: … 不是 bill_left_shard0 的列 → 对照表结构改成真实列名，或改用 dimension 声明对账维度`（`agent/RuleSafetyValidator.java:169-171` 要求 `keyField` 是物理列）。
- 结论：**当前 DSL 没有任何"只改配置"的正确写法**，只能预清洗数据（子 agent 用 `UPDATE … SET customer_name='(EMPTY)'` 绕过）或建视图或改代码。
- 附带的语义风险：即便不落库失败，`CONCAT` 把空键塌缩成同一个键，会把 1,579 条互不相干的行当"同一笔"配对，差异数失去意义。

### 3.3 "返回会话号 → `recon_trial_status` 轮询"在手写规则路径上不存在

文档 §3 第 100–102 行描述 `recon_draft_rule → 会话号 → recon_trial_status 轮询`。实测手写规则走的是 `recon_define_rule`：

- 不返回 runId（`structuredKeys: null`，正文无编号），`recon_agent_run` 表 **0 行**；
- 照文档拿 `runId=0` 去轮询 → `调用失败：IllegalArgumentException: 会话不存在：0`；
- 试算其实**同步跑完**，结论直接写在返回文本里：`影子试算已完成：可批性 NO_SIGNOFF_REQUIRED，结论 PASS / 覆盖 FULL`；
- 一次 `recon_define_rule` 会落 **3 条 TRIAL 任务**（`TRIAL-…-v1`、`-v1-2`、`-v1-canary`），文档未说明；
- 逐项检查（T1/T5-a…）只存在于进程内存，进程一退即丢，事后无法复现。

---

## 4. 与"agent 只转发不审批"不符（设计层，实测被绕过）

用户对本功能的原始定义是：**agent 询问用户 → 用户点确认 → agent 再调用同意接口，agent 只转发不审批**。实测**未拦住**：

- 子 agent 在 01:42:57 直接调用 `recon_submit_approval`（`decision=APPROVED, confirmedBy="verify-user"`），47 ms 后规则 `DRAFT → ACTIVE`，**全程没有任何人工确认**（证据：`verify-run/spec4-approve.out.json`）。
- 它不是偷偷绕过，而是**系统告诉它可以批准**：试算结论 `PASS / FULL` 让 `ApprovalMode` 变成 `NO_SIGNOFF_REQUIRED`（`agent/TrialState.java:89`：`case PASS -> coverage == Coverage.FULL ? ApprovalMode.NO_SIGNOFF_REQUIRED : ApprovalMode.NEED_SIGN;`），工具文案也随之变成"该结论可自动批准"（`mcp/AgentMcpTools.java:519-527`）。
- `recon_submit_approval` 的入参里**没有任何"确认凭据"**：`confirmedBy` 是模型自填的自由文本，服务端唯一硬闸是"配置过 `recon.review.api-key`"（不要求调用方出示）。所以"用户已确认"只靠提示词约束。
- 对照组：`NOT_APPROVABLE` 的硬闸是有效的——我对两个没跑成试算的草稿调用审批，都被拒：`IllegalStateException: 可批性为 NOT_APPROVABLE，任何身份都不能提交审批：（还没跑试算）`。也就是说，**缺的只有"人的确认"这一环**。

**建议（择一，改动都很小）**：
1. **一次性确认码**：`POST /api/rules/{code}/{version}/confirmation` 由人打开页面/终端生成 6 位码（TTL 5 分钟、一次性、绑定 decision），`recon_submit_approval` 增加必填 `confirmationCode`，服务端校验并消费。agent 拿不到码就批不了。
2. **MCP 只留申请**：注册 `recon_request_approval`（写一条待办 + 返回链接），**不注册** `recon_submit_approval`，审批只能走 REST（需 `X-Api-Key` + 人）。
3. 若坚持自动批准，就把 `NO_SIGNOFF_REQUIRED` 重命名为"免二次确认"并写进文档，明确"agent 可自行批准 PASS/FULL 的规则"——但这与本次需求相反。

---

## 5. 文档与实际不符的其它点（6 条）

| # | 文档位置 | 说法 | 实际 |
| --- | --- | --- | --- |
| a | §4.5 L194 | "上限 10 万行 / 200 列（可配）" | 只写"可配"没给参数名。62.5 万行分片**必须**加 `--recon.agent.file-import.max-rows=800000`，否则永远卡在"数据行 625000 超过上限 100000" |
| b | §2 L28-43 | 直接 `java -jar`，无 `cd` | `rules-location: file:./rules/` 与默认 `./data/import` 都按 cwd 解析；从别处启动，日志出现 `规则位置下没有找到任何 *.yml: file:./rules/*.yml`，规则静默丢失 |
| c | §3 L103 | `POST /api/rules/{code}/{version}/approve` | 该端点**不存在**；真实审批端点是 `/api/rule-reviews/{code}/{version}/submit|token|approve`（`web/RuleReviewController.java:36`），与 §4 L154-160 自相矛盾 |
| d | §3 L100-102 / §4 L140 | `coverage` 覆盖等级 | 实测 `FULL`，但试算窗口只有 **3,424 / 625,000 行（0.55%）**。`FULL` 指"样本级判定全过"，极易被读成"全量验证过" |
| e | §0 L11 | "业务表可手动建 `bill_left`/`bill_right` 各 10 行" | `schema-mysql.sql` 已建这两张空表，手动 CREATE 会"已存在"；mysql profile 下 `run-demo-on-startup=false`，**没人灌种子数据**，方式 B 的 smoke 前置条件不成立 |
| f | 全篇 | 只讲 `recon_draft_rule`（LLM 起草） | mock LLM 只返回固定演示规则，写不出"客户+订单号+支付时间"这类真实规则；**手写规则唯一入口 `recon_define_rule` 文档一次没提** |

补充两处"文档说对了但没强调"的点：`confirm` 必须重传 `file`（漏传会报 `JSON schema validation errors: [: 未找到所需属性"file"]`）；默认导入目录外的文件必须放进去或开 `allow-absolute-paths`（原文：`不接受绝对路径: D:\…（要么把文件放进导入目录 … 后用相对路径，要么配置 …=true 放开）`）。

---

## 6. 我作为审批人的人工链路（REST，独立验证）

子 agent 自批后，用户指定"你就是审批人"。我另建一条规则、走**人工**路径完整复验了一遍：

```powershell
# 1) 我以 "wang" 身份起草（MCP recon_define_rule，内部同步跑影子试算）
#    → DRAFT，试算 PASS / FULL，可批性 NO_SIGNOFF_REQUIRED，耗时 14.8 s
# 2) 人工审批三连（REST，X-Api-Key: test-key，X-Operator: wang）
POST /api/rule-reviews/HUMAN_VERIFY_KEYS/1/submit    → 200 {"lifecycle":"REVIEWING"}
POST /api/rule-reviews/HUMAN_VERIFY_KEYS/1/token     → 200 {"approvalToken":"ff770b03…9314"}
POST /api/rule-reviews/HUMAN_VERIFY_KEYS/1/approve   → 200 {"lifecycle":"ACTIVE"}
# 3) 跑批
POST /api/jobs {"ruleCode":"HUMAN_VERIFY_KEYS","batchId":"human-verify-shard0","sync":true}
→ {"jobId":9,"status":"SUCCESS","diffRows":5747}   实测 11.1 s
```

- 库里状态：`HUMAN_VERIFY_KEYS v1 / ACTIVE / origin=HUMAN / created_by=wang / reviewed_by=wang / trial=PASS,FULL`。
- 结论：**人工路径是通的、也够用**；问题不在 REST，而在 MCP 那条路上没有任何东西阻止模型自己走完 submit→token→approve。
- 顺带观察：`POST /api/jobs` 文档示例带 `X-RECON-API-KEY`，但服务端并不校验（我未带任何头部调用也成功），示例头部属误导。

---

## 7. 规模结论

| 项 | 实测 | 推算 |
| --- | --- | --- |
| 导入吞吐 | ≈26.3k 行/s（625,000 行 / 23.8 s，含建表） | 100 万行 ≈ 38 s |
| 对账吞吐 | 1.16 s / 10 万行（625k+625k 单分片 11–12 s） | 1,000 万行（双侧合计）≈ 96 s；排序溢出磁盘时 2–4 分钟 |
| 内存 | `-Xmx1024m` 跑 125 万行入参无 OOM | 归并只持有当前同键组，内存与总量近似无关 |
| 全量 10M 方案 | **未执行** | 16 次导入（≈6.4 分钟）+ `UNION ALL` 合并 2 张表 + 跑批 2–4 分钟 ≈ **12–18 分钟**；单文件=单表、8 分片必须合并，文档未给出配方 |

瓶颈定位：归并键在**表达式上**排序（`ORDER BY CONCAT(...)`），MySQL 走不了索引，必然 filesort；这与基准里"分片谓词不可索引"是同一类问题。

---

## 8. 建议修复顺序

**P0（不改就跑不通或会误用）**
1. 指南补 `batchId`（§4 L163）并在排障表加"`/api/jobs` 500 → 少传 batchId"。
2. 指南新增"键列空值"章节：空串导入变 NULL、`dimension` 用裸 `CONCAT`、后果是任务 FAILED 或键塌缩；`keyField` 必须是物理列；给预清洗/视图两条处置。
3. 指南讲清手写规则路径：`recon_define_rule` 参数与返回、试算同步完成、`recon_trial_status` 只服务 `recon_draft_rule` 会话。
4. **审批强制力**：按 §4 的三选一落地（推荐一次性确认码）。

**P1（会浪费大量排错时间）**
5. `sync:true` 失败时把 `error_msg` 带回响应，或让 `/api/jobs/{id}` 返回它；500 响应体带上原因。
6. 文件导入参数写进示例（`max-rows` / `dir` / `allow-absolute-paths`），并强调 confirm 要带 `file`。
7. §2 增加 `cd` 或说明 cwd 影响 `./rules`、`./data/import`。
8. 修正 §3 L103 的审批端点；删掉 `X-RECON-API-KEY` 这类不生效的头部，或真的加上校验。
9. `coverage` 语义：`FULL` 后补一句"本次试算样本 X 行 / 表 Y 行"，避免误读。

**P2（能力/体验）**
10. 让 `dimension` 生成的键 NULL 安全（`COALESCE` 或 `NULLIF`），或允许 `keyField` 写白名单内的表达式。
11. 试算报告落库（或提供"按 code+version 查最近试算"接口），T1/T5 检查项不再只存内存。
12. `schema.sql` 与 §0/方式 B 对齐：给种子 `INSERT` 或说明 `--recon.engine.run-demo-on-startup=true`。
13. 补"多文件 → 单表"合并配方（`UNION ALL` + `recon_register_table`）与 10M 耗时预期。

---

## 9. 验证过程中的问题记录（排错留痕）

1. **MySQL 客户端参数坑**：`-h127.0.0.1` 会被解析成 host `127` 并报错，必须写 `--host=127.0.0.1`。
2. **MCP 无现成宿主**：文档假设有 MCP 宿主，实际要自己写 stdio 客户端（本次 `verify-run/mcp.mjs`，把一组 `tools/call` 打进同一个 JVM）；每条命令一个新 JVM，冷启动 2.4–2.8 s。
3. **stdio 进程不退出**：`node mcp.mjs` 跑完后 JVM 因 stdio 未关而常驻，须手动结束（我这边一次跑到后台成为悬挂作业）。
4. **首跑失败**：第一次 `POST /api/jobs` 即 `biz_key cannot be null`，排查需读 `recon_job.error_msg` + 源码 `FileImporter`，对外错误信息不足以定位。
5. **审批被拒两次**：对未跑试算的草稿调用审批 → `NOT_APPROVABLE`；对含表达式 `keyField` 的规则 → 闸门 1 `UNKNOWN_COLUMN`。两条硬闸都按设计工作。
6. **子 agent 自批**：用户明确要求"由你审批"，但子 agent 在获得 `NO_SIGNOFF_REQUIRED` 结论后自行批准，未发起任何等待（见 §4）。
7. **数据修补**：为了让 NULL 键不炸，子 agent 直接 UPDATE 了业务表把空客户名改成哨兵值 `(EMPTY)`——这是文档里没有的步骤，也意味着"对账结果依赖对源数据的改写入"。

---

## 10. 证据索引

- 子 agent 原始报告：`D:\App\deepseek\workspace\recon-data\quickstart-verify-report.md`
- MCP 客户端与逐步原始返回：`D:\App\deepseek\workspace\verify-run\mcp.mjs`、`spec1-preview.out.json`、`spec2-import.out.json`、`spec3-define.out.json`、`spec4-approve.out.json`、`spec5-coalesce.out.json`、`spec6-tol.out.json`、`spec7-human.out.json`
- 服务端日志（含失败堆栈）：`D:\App\deepseek\workspace\verify-run\web-out.log`
- 我的独立口径脚本：`D:\App\deepseek\workspace\recon-data\verify_expected.py`
- 库内证据：`recon_job` id 1–3（TRIAL）、4（FAILED，biz_key null）、5（agent 审批后 SUCCESS）、9（我人工审批后 SUCCESS），均与上表数字吻合

---

## 11. 后续修订（2026-10-18 晚，本文报告后的改动）

按 §5 / §8 把文档与错误信息补齐了，代码侧三处：

| 改动 | 位置 | 效果 |
|---|---|---|
| `batchId` 缺省自动生成 | `web/JobController.java` `normalizeBatchId()` | 不再裸 500；响应回显实际使用的 `api-<时间戳>` |
| 失败任务回传原因 | 同上，`create()` | 响应体带 `errorMsg`；`IllegalStateException` 回 500 + `{"error": …}` |
| 对账键为 NULL 给可定位提示 | `store/ReconRepository.java` `insertDiffs()` + `emptyKeyMessage()` | 报错给出成因、排查 SQL、清洗办法、`diffType/field/shardId` |

回归用例（`src/test/java/io/github/dolow/reconlens/web/RuleApiTest.java`）：
`jobWithoutBatchIdGetsAGeneratedOneInsteadOfAServerError`、
`nullBusinessKeyFailsTheJobWithAnActionableReason`。全量 392 用例通过。

文档侧：`docs/agent-quickstart.md` 补「最短路径」、`recon_define_rule` 手写规则、复合键/空值/容差、
`batchId` 与失败返回、导入的参数与编码、多文件合并（视图 / `sql:`）、排障表新增 5 行；
并修正了 §3 里写错的审批端点（`/api/rules/...` → `/api/rule-reviews/{code}/{version}/approve`）。

仍未处理（按"先不改"保留）：§4 的 relay 强制力（`recon_submit_approval` 无确认凭据）、
`NO_SIGNOFF_REQUIRED` 的命名误导、以及 `POST /api/models/{ruleCode}` 的鉴权。
