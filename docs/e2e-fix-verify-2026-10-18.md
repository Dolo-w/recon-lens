# 修复验证：空白环境端到端（2026-10-18）

> 验证对象：本轮针对评审问题做的 6 项修复 —— ① Agent 不能自批（一次性确认码）② 篡改检测不再只看冷缓存
> ③ 写接口鉴权 ④ 试算改后台（不再卡住 MCP 会话）⑤ MCP 提交的规则正确标记 `origin=MCP`
> ⑥ `NO_SIGNOFF_REQUIRED` 文案不再暗示"可自动批准"。
> 环境从零重建（新库 `recon_fix_verify`、新导入的两张表、新进程），不沿用任何上一轮残留的数据或进程。

## 0. 结论

| # | 修复 | 结论 | 关键实测 |
| --- | --- | --- | --- |
| 1 | 审批必须持用户签发的一次性确认码 | ✅ 通过 | 无码调用被 JSON Schema 拒绝（32 ms）；带码 72 ms 批完；同一码重放被拒 |
| 2 | 篡改检测覆盖热缓存（"直接走索引"） | ✅ 通过 | 缓存已热 + 手改库 → 下一个请求 400，lifecycle 当场降 REVIEWING，**不重启** |
| 3 | 写接口鉴权（`recon.security.api-key`） | ✅ 通过 | 4 个写端点无 key / 错 key 全部 403 `api_key_required`；带 key 正常 |
| 4 | 试算改后台，不再阻塞工具调用 | ✅ 通过 | `recon_define_rule` **83 ms** 返回（改前 14,871 ms），25 s 后 `recon_trial_status` 出 PASS × FULL |
| 5 | MCP 提交标记 `origin=MCP` | ✅ 通过 | `recon_rule.origin = MCP`，`created_by = wang` |
| 6 | 可批性文案 | ✅ 通过 | 「NO_SIGNOFF_REQUIRED（不需要额外签字，但仍需用户确认）」 |
| — | 回归：全量测试 | ✅ 通过 | `mvn -B clean package`：**403 用例 / 0 失败 / BUILD SUCCESS** |
| — | 回归：对账结果 | ✅ 通过 | shard0：左 625,000 / 右 624,955 / 匹配 623,724 / 差异 **5,747**，四类与独立 Python 口径逐类相同 |

## 1. 环境

| 项 | 值 |
| --- | --- |
| 代码 | 工作区 `HEAD 0a5de71` + 未提交修复（115 项） |
| 构建 | `mvn -B clean package` → `target/recon-lens-0.1.0.jar`（48 MB），403 用例全绿 |
| 数据库 | MySQL 8.0.29 `127.0.0.1:3307`，新库 `recon_fix_verify`，`schema-mysql.sql` 建 14 张表、0 行 |
| Web 实例 | `--spring.profiles.active=mysql`、`--recon.agent.enabled=true`、`--recon.security.api-key=write-key`、`--recon.review.api-key=test-key`、`--recon.seed-rules=false`，`Started ReconLensApplication in 2.654 seconds` |
| MCP 宿主 | 自写 stdio 客户端（`spawn java -jar --spring.profiles.active=mcp,mysql`），工具面 **14** 个（agent 关时 9 个） |
| 数据 | `recon-data` 的 shard0 两张 CSV（136.2 MB / 134.4 MB）经 `recon_import_file` 导入 |

## 2. 逐步实测

### 2.1 文件导入（未改动，作为基线）

| 动作 | 耗时 | 结果 |
| --- | --- | --- |
| preview 左 / 右 | 1,773 / 1,565 ms | 625,000 / 624,955 行 × 20 列，类型推断全对 |
| confirm 左 / 右 | 26,510 / 26,073 ms | 建表 + 注册白名单，≈23k 行/秒 |

### 2.2 定义规则：试算改后台

```
recon_define_rule(operator=wang, ruleBody=…)
→ 83 ms
  规则 FIX_E2E_SHARD0 已落库为草稿 v1（来源 MCP，操作人 wang）…
  影子试算已排队（会话 #1，单次约 1 分钟）。
  下一步：recon_trial_status(ruleCode=FIX_E2E_SHARD0, version=1) 或 runId=1 轮询结论，不要重复提交。
```

- 改前同一份规则定义要同步等 14,871 ms（机器忙时 23,940 ms），期间 MCP 会话完全阻塞；
- 立即查询（9 ms）：`会话 #-｜UNKNOWN｜… 可批性：NOT_APPROVABLE（还没跑试算）` —— 后台还没跑完时给的是明确的"未开始"，不是假结论；
- 25 s 后再查（7 ms）：`试算：PASS × FULL`、`T1/T2/T5-a/T5-b/T5-d/T0 = PASS`，`T3/T4/T6 = N/A`（无历史生产任务、无基线）；
- `message` 字段（上一轮修的 `outputSchema` 必填项）两次都在，不再报 `未找到所需属性 "message"`。

### 2.3 审批：没有确认码就批不了

| 步骤 | 调用方 | 实测 |
| --- | --- | --- |
| ① 不带码直接调 `recon_submit_approval` | Agent | `Tool (recon_submit_approval) input validation failed: … 未找到所需属性"confirmationCode"`（32 ms，`isError=true`） |
| ② 领码 | **人**（curl + `X-Api-Key: test-key`） | `{"confirmationCode":"89TCC2VP","issuedBy":"wang","expiresAt":"…","ttlSeconds":300}` |
| ③ 带码代办 | Agent | 72 ms → 「已批准，状态 ACTIVE」，`confirmationConsumed=true` |
| ④ 同一个码再用一次 | Agent（模拟泄漏/重放） | 56 ms → 「确认码无效：这串确认码已经被用过（…），一次确认只能办一件事」 |
| ⑤ 库里对账 | — | `lifecycle=ACTIVE / origin=MCP / created_by=wang / reviewed_by=wang / trial=PASS,FULL`；`recon_approval_confirmation` 里 `used_at` 已填、`used_by=wang`、`expires_at=+5min` |

对照上一轮：改前同一个工具一次调用 47 ms 就批完了，全程没有人工参与。

### 2.4 篡改检测：热缓存也拦得住

复现步骤（全部不重启进程）：

1. `GET /api/rules/FIX_E2E_SHARD0` → 200（规则进缓存）；
2. 跑批一次（规则被 `resolve` 解析过，缓存确定是热的）；
3. `UPDATE recon_rule SET spec_json = REPLACE(spec_json,'settle_amount','settle_amount_x')`（模拟 DBA 手改 / 绕过审批接口改内容）；
4. 再 `POST /api/jobs` → **HTTP 400**
   `规则 FIX_E2E_SHARD0 v1 的内容在审批后被修改（reviewed_spec_sha 不匹配），已降级回 REVIEWING，需重新审核后才能运行`
5. 库里 `lifecycle` 当场变成 `REVIEWING`。

改前同样步骤返回 `SUCCESS`（缓存命中直接返回，根本不做内容校验），只有重启进程才会发现。

实现：`RuleRepository.findGuard(code)` 只 `SELECT version, spec_json, reviewed_spec_sha … WHERE code=? AND enabled=TRUE AND lifecycle='ACTIVE' ORDER BY version DESC LIMIT 1`（走 `recon_rule(code, version)` 索引，不解析 JSON），`resolve` 每次都先读这一行，`cached.version() == guard.version()` 且内容哈希匹配才用缓存。

### 2.5 写接口鉴权

| 端点 | 无 key | 错 key | 带 key |
| --- | --- | --- | --- |
| `POST /api/jobs` | 403 `api_key_required`（action=发起对账任务） | 403 | 200 |
| `POST /api/models/{ruleCode}` | 403（action=构建预处理模型表…） | — | 200，13 s 建出 625,000 / 624,955 行的两张模型表，并落 `FIX_E2E_SHARD0__MODEL` v1 |
| `DELETE /api/rules/{code}` | 403（action=下线规则） | — | — |
| `POST /api/rule-reviews/{code}/{ver}/confirmation` | 403 `FORBIDDEN：X-Api-Key 无效`（走的是 `recon.review.api-key`） | — | 200 发码 |

### 2.6 对账结果（回归）

```
POST /api/jobs {"ruleCode":"FIX_E2E_SHARD0","batchId":"fix-e2e-clean","sync":true}
→ {"jobId":5,"status":"SUCCESS","diffRows":5747}        13 s（库里 13,000 ms）
   left_rows 625,000 / right_rows 624,955 / matched_rows 623,724
```

| diff_type | 引擎 | 独立 Python 口径（`recon-data/verify_expected.py`） |
| --- | --- | --- |
| AMOUNT_MISMATCH | 3,240 | 3,240 |
| DUPLICATE_KEY | 106 | 106 |
| MISSING_LEFT | 1,125 | 1,125 |
| MISSING_RIGHT | 1,276 | 1,276 |
| **合计** | **5,747** | **5,747** |

> 第一次跑批（未清洗空值）失败，但报错是上一轮补的那种"能照着修"的文案：
> `对账键为空（biz_key = NULL）… 排查：SELECT COUNT(*) … WHERE 键列 IS NULL；处理：UPDATE 表 SET 列='' WHERE 列 IS NULL … 本条差异：diffType=DUPLICATE_KEY，shardId=0`。
> 按提示清洗 `customer_name` 后即 5,747 —— 这两条修复串起来是通的。

## 3. 顺带确认的行为

- `recon_define_rule` 的落库来源是 `MCP`（不再是 `HUMAN`），文案里也写了"来源 MCP"；
- `recon_trial_status` 按 `ruleCode+version` 查询可用（不依赖会话 `runId`），`message` 兜底正常；
- `recon_approval_confirmation` 表随 schema 一起建出来（H2 / MySQL 两套脚本 + `docs/migrations/`）+ 单测 5 例；
- 撤销降级后的规则可以重新审批：恢复 `spec_json` → 重新领码 → `recon_submit_approval` → ACTIVE → 跑批通过（本轮就是这么回到 5,747 的）。

## 4. 仍未处理（与本轮无关，保留在评审账本里）

1. `DiffSetDigest` 溢出（> 5 万键）后清空 → 两次试算的空摘要相等 → T2 可能假 PASS；建议溢出直接判 `INCONCLUSIVE`。
2. `agent/FileImporter.java` 编码只认 UTF-8（GBK 静默乱码）、字段缓冲无字节上限、多余列静默丢弃。
3. `agent/SchemaCatalog` 白名单大小写不敏感：导入 `ORDERS` 会让真实表 `orders` 一起过闸门。
4. `DemoRunner` 无 try/catch（`run-demo-on-startup=true` 时演示失败会导致应用起不来）、`seed-rules` 覆盖用户改动只 WARN。
5. `recon_diff` 缺 `(job_id, biz_key)` 索引；生产路径每个分片仍无条件包 `DigestingDiffSink`（+0.5 µs/差异，摘要不落库）。

## 5. 清理记录

- 停 Web 实例（端口 18080 无监听）；
- `DROP DATABASE recon_fix_verify`；
- 清空 `recon-lens/data/import`（两张 136 MB 的 CSV 副本）；
- 证据留在 `D:\App\deepseek\workspace\verify-fix\`（`steps-*.json` / `run-*.json` / `mcp.mjs` / `web-out.log`）。
