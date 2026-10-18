# 修复后空白环境端到端复验（2026-10-18 第二轮）

> 目的：上一轮三条 P1 修复 + 更早那批修复（确认码、试算异步、写接口鉴权、篡改热缓存、`origin=MCP`）
> 落地后，**从空库**再走一遍完整链路，确认改动在真实 MySQL 上成立。
> 与 `docs/e2e-fix-verify-2026-10-18.md` 的区别：那次用的是 `D:\App\deepseek\tools` 里的 MySQL 8.0.29，
> 而 DSH 升级把整个 `tools` 目录删掉了；这次工具装在 `D:\App` 下（用户要求：不再放 deepseek 目录）。

## 1. 环境

| 项 | 值 |
| --- | --- |
| 数据库 | MySQL **8.0.28**（zip 包，`D:\App\mysql-8.0.28-winx64`，数据目录 `D:\App\mysql-data`），端口 **3307**，root 空密码 |
| JDK / 构建 | IDEA JBR **21.0.8**（`D:\App\ideaIC-2025.3\jbr`）+ 仓库自带 `./mvnw`（Maven 3.10.0） |
| 应用 | `target/recon-lens-0.1.0.jar`（50,336,346 B），`spring.profiles.active=mysql`，端口 18080，`Started … in 1.614 seconds` |
| 空白库 | `recon_e2e_verify`，执行 `src/main/resources/schema-mysql.sql` → **14 张表、0 行**（多出的第 14 张是 `recon_approval_confirmation`） |
| 数据 | 用 `recon-data/gen_recon_csv.py` 重新生成的 24 份 CSV（2.12 GB，确定性：重跑后注入数量与上次完全一致），只导入 shard0 |
| 开关 | `--recon.seed-rules=false`、`--recon.agent.enabled=true`、`--recon.security.api-key=write-key`、`--recon.review.api-key=test-key`、`--recon.agent.file-import.max-rows=1000000` |
| 审批人 | 由人（本会话）领取确认码；模型只负责“拿码调用” |

## 2. 耗时

| 步骤 | 耗时 |
| --- | --- |
| 建库 + 灌 schema | < 1 s |
| 应用启动 | 1.6 s |
| 导入 preview（左/右） | 1,463 / 1,469 ms |
| 导入 confirm（左 625,000 / 右 624,955 行） | 21,860 / 21,323 ms（≈29k 行/秒） |
| `recon_define_rule` 返回 | **62 ms**（改前同步试算 14,871 ms） |
| 后台试算完成 | < 30 s，`PASS × FULL` |
| 领确认码（REST） | < 100 ms |
| `recon_submit_approval`（带码） | **41 ms**，`ACTIVE` |
| 跑批（首次，撞 NULL 键） | 13 s，`FAILED`，带可操作错误 |
| 跑批（清洗后） | 11 s，`SUCCESS`，5,747 条差异 |
| 建模型（无 key/有 key） | 403 / 10 s |

## 3. 修复项逐条实测

| 修复 | 实测结果 |
| --- | --- |
| 试算异步 | `recon_define_rule` 62 ms 返回「影子试算已排队（会话 #1）」；30 s 后 `recon_trial_status` 得 `PASS × FULL` |
| `trial_status` 缺 `message` | 按 `ruleCode+version` 查询返回的 structured 里有 `message: "试算结论见上；生效仍需人工审批"`，不再 schema 校验失败 |
| 确认码 | 无码调用 → JSON Schema 拒绝「未找到所需属性 confirmationCode」(20 ms)；带码 → 41 ms 批准并 `confirmationConsumed=true`；同码重放 → `确认码无效：这串确认码已经被用过` (5 ms) |
| 写接口鉴权 | `POST /api/jobs` 无 key → 403 `api_key_required`（带 `action` 说明）；`POST /api/models/{code}` 无 key → 403；领码接口无 key → 403 `FORBIDDEN`；读接口不受影响 |
| `batchId` 自动补 | 不传 `batchId` → 响应回显 `api-20261018-163516`，不再是裸 500 |
| `biz_key` 空值可定位 | 首次跑批 `FAILED`，`errorMsg` 写明成因/排查 SQL/处理办法，并标出 `diffType=DUPLICATE_KEY、shardId=0`；左 1,579 / 右 1,581 行 `customer_name` 为 NULL，清洗后重跑通过 |
| 篡改检测（热缓存） | 预热后 `UPDATE recon_rule SET spec_json=REPLACE(…'settle_amount'→'settle_amount_x')`，**不重启**下一次请求即 400「内容在审批后被修改…已降级回 REVIEWING」，库里 lifecycle 当场变 `REVIEWING`；恢复内容后重新审批回 `ACTIVE` |
| `origin=MCP` | 规则行 `origin=MCP`，`created_by=wang / reviewed_by=wang`，`trial=PASS/FULL`；审批记录 `used_by=wang` |

## 4. 对账正确性（与独立 Python 口径对照）

规则：`dimension = CONCAT(customer_name, '|', order_no, '|', pay_time)`，`settle_amount DECIMAL tolerance 0.01`。

| 指标 | 引擎（job 5） | 独立口径（`recon-data/verify_expected.py`） |
| --- | --- | --- |
| 左行数 | 625,000 | 625,000 |
| 右行数 | 624,955 | 624,955 |
| 匹配 | 623,724 | — |
| 差异合计 | **5,747** | **5,747** |
| `AMOUNT_MISMATCH` | 3,240 | 3,240 |
| `DUPLICATE_KEY` | 106 | 106 |
| `MISSING_LEFT` | 1,125 | 1,125 |
| `MISSING_RIGHT` | 1,276 | 1,276 |

逐类相同。`diff_expected_shard0.csv` 里的 `STATUS_MISMATCH` 不计入对比 —— 用户规则没有比较 `status` 字段。

## 5. 仍需注意（不是回归）

1. **`__MODEL` 规则仍无试算就落 ACTIVE**：`POST /api/models/{code}` 现在需要 `X-Api-Key`，但建出的 `AGENT_E2E_SHARD0__MODEL` v1 没有试算记录（与改前一致）。要不要给模型规则也走草稿+试算，是产品取舍，未动。
2. **REVIEWING 状态下 `POST /submit` 会 400**：`submit` 只接受 `DRAFT → REVIEWING`；内容被篡改降级后已经处于 REVIEWING，人应直接 `token → approve`。文档里可以补一句。
3. **导入仍是同步长请求**（21 s）：MCP 客户端超时设短了会误判失败，但数据其实已灌完（表已注册）。
4. **MCP 试算任务绑进程**：`recon_define_rule` 的排队试算跑在 MCP 进程的线程池里，进程退出后队列里的任务就没了（本轮是同一进程内轮询，正常完成）。

## 6. 清理与复现

- 应用进程 19856 已 `Stop-Process`；MySQL 用 `mysqladmin --host=127.0.0.1 --port=3307 -u root shutdown` 停。
- 库 `recon_e2e_verify` 保留（含证据数据：job 4/5、5,747 条差异、规则行、确认码审计），下次可直接查；不要时 `DROP DATABASE recon_e2e_verify`。
- 证据目录：`D:\App\deepseek\workspace\verify-e2e2\`（`mcp.mjs` 客户端、`steps-*.json`、`last-run.json`、`tools.json`、`web-out.log`）。
- 重新起服务：

```powershell
& 'D:\App\mysql-8.0.28-winx64\bin\mysqld.exe' --basedir=D:\App\mysql-8.0.28-winx64 --datadir=D:\App\mysql-data --port=3307 --mysqlx=OFF
& 'D:\App\ideaIC-2025.3\jbr\bin\java.exe' -jar D:\App\deepseek\workspace\recon-lens\target\recon-lens-0.1.0.jar `
  --spring.profiles.active=mysql `
  "--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/recon_e2e_verify?useCursorFetch=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai" `
  --spring.datasource.username=root --spring.datasource.password= `
  --recon.seed-rules=false --recon.agent.enabled=true `
  --recon.agent.schema.tables=bill_left_shard0,bill_right_shard0 `
  --recon.security.api-key=write-key --recon.review.api-key=test-key --recon.review.token-secret=test-secret `
  --recon.agent.file-import.max-rows=1000000 --server.port=18080
```
