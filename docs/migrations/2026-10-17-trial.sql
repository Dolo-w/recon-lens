-- ============================================================================
-- M2 迁移：闸门 2（影子试算）
-- 目标库：MySQL 8.0（本项目实测 8.0.29，utf8mb4，sql_mode 含 STRICT_TRANS_TABLES）
-- 执行方式：整条 ALTER 一次执行，不要手工拆开
-- ============================================================================
--
-- 为什么必须一次执行：实测拆成两条时，先加的 trial_report 会成功、第二条再加 draft_reason
-- 才报 ERROR 1118（Row size too large），表会停在"改了一半"的中间态。
-- 这一条要防的是"同一次变更被手拆成两条"，不是禁止分里程碑 —— M1 的生命周期 8 列已在
-- 2026-10-17-rule-lifecycle.sql 里单独执行过，这里只补 M2 的部分。
--
-- 行宽说明：spec_json / trial_report 都是 MEDIUMTEXT，属于 off-page 存储，
-- 不占 65,535 的行内配额；trial_coverage / trial_verdict 等短列才占。
-- ============================================================================

ALTER TABLE recon_rule
  MODIFY COLUMN spec_json            MEDIUMTEXT   NOT NULL,
  ADD COLUMN trial_verdict           VARCHAR(32)  NULL,
  ADD COLUMN trial_coverage          VARCHAR(16)  NULL,
  ADD COLUMN trial_coverage_reason   VARCHAR(255) NULL,
  ADD COLUMN skip_reason             VARCHAR(255) NULL,
  ADD COLUMN trial_digest            CHAR(64)     NULL,
  ADD COLUMN trial_report            MEDIUMTEXT   NULL;

-- 试算与 canary 也写 recon_job（要拿分片记录与差异），必须能区分"生产对账"与"闸门自检"，
-- 否则巡检的"最近 N 次任务差异率环比"会把裁剪样本算进基线，直接制造误报。
ALTER TABLE recon_job
  MODIFY COLUMN rule_snapshot MEDIUMTEXT NULL,
  ADD COLUMN kind  VARCHAR(8) NOT NULL DEFAULT 'PROD',
  ADD COLUMN phase VARCHAR(8) NULL,
  ADD KEY idx_job_code_kind (rule_code, kind, id);

-- 闸门自身的健康状态：canary 失败是"闸门级"事件，不持久化则重启后 trusted 丢失，
-- 降级签字通道会悄悄打开。一行小表，不要合并进 recon_rule。
CREATE TABLE IF NOT EXISTS recon_agent_gate (
  gate_version  VARCHAR(64) NOT NULL PRIMARY KEY,
  trusted       TINYINT(1)  NOT NULL DEFAULT 0,
  canary_job_id BIGINT      NULL,
  checked_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  detail        TEXT        NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
