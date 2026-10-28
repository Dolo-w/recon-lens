-- 批 5 M15：给 agent 轨迹补两列能回答"哪一步慢、这轮多少钱"的字段。
--
-- 背景：cost_ms 早就在表上（限时用的是墙钟毫秒，不是钱），但从没进过接口；
-- tokens_est 是新列，存这一步上下文的估算 token（口径 = llm/Tokens，估算而非账单）。
-- 只加列，不删不改，老版本读不到列时按 NULL 处理（AgentStep.tokensEst 是 Integer）。
--
-- 幂等：先查 information_schema 再加列，生产 DBA 可直接重复执行。
-- H2（测试/演示）不跑这个脚本——schema.sql 里建表语句已含该列。

DROP PROCEDURE IF EXISTS add_agent_step_tokens;

DELIMITER $$
CREATE PROCEDURE add_agent_step_tokens()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'recon_agent_step'
                     AND column_name = 'tokens_est') THEN
        ALTER TABLE recon_agent_step ADD COLUMN tokens_est INT NULL AFTER cost_ms;
    END IF;
END $$

CALL add_agent_step_tokens() $$
DROP PROCEDURE IF EXISTS add_agent_step_tokens $$
DELIMITER ;

-- 回滚（手工执行，非自动）：
-- ALTER TABLE recon_agent_step DROP COLUMN tokens_est;
