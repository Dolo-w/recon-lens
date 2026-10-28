-- 2026-10-27：给长任务加恢复点（把结构化目标存下来）
--
-- 为什么需要 goal_json：`goal` 那一列存的是人能读的那句话（`AgentGoal.describe()`），
-- 恢复时不够用 —— `AgentGoal` 还有 trigger / intent / focusTypes / batchId / timeWindow。
-- 其中 intent 最要紧：DRAFT_RULE 和 RUN_RECONCILIATION 走两条完全不同的路，
-- 猜错会让"只想起草"的会话真的跑一次生产对账。
--
-- 只加列、不动已有数据：旧记录 goal_json 为 NULL，恢复时退化成
-- "按 goal 文本 + 默认 intent=DRAFT_RULE 重建"，并在轨迹里留一条 RESUME 步。
--
-- 幂等：先查 information_schema 再加列，生产 DBA 可直接重复执行。
-- H2（测试/演示）不跑这个脚本——schema.sql 的建表语句已含该列。
--
-- 回滚脚本：同目录 2026-10-27-agent-run-goal-json.rollback.sql（不自动执行）。

DROP PROCEDURE IF EXISTS add_agent_run_goal_json;

DELIMITER $$
CREATE PROCEDURE add_agent_run_goal_json()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'recon_agent_run'
                     AND column_name = 'goal_json') THEN
        ALTER TABLE recon_agent_run ADD COLUMN goal_json TEXT NULL AFTER goal;
    END IF;
END $$

CALL add_agent_run_goal_json() $$
DROP PROCEDURE IF EXISTS add_agent_run_goal_json $$
DELIMITER ;
