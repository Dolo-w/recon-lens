-- 2026-10-27：回滚 2026-10-27-agent-run-goal-json.sql
--
-- 什么时候可以回滚：代码里已经没有 `agentRunRepository.goalJson(...)` / `startRun(goal, goalJson, ...)`
-- 的调用（旧版本 jar 上就没有）。**不要只回滚库而留着新代码** —— 新代码写 goal_json 会直接报
-- "Unknown column"，那比不回滚更糟。
--
-- 数据影响：goal_json 只用来恢复长任务；删掉它之后，`resume` 退化成"按 goal 文本 +
-- 默认 intent=DRAFT_RULE 重建"。也就是说：回滚不会丢已经跑完的会话与轨迹，只会让
-- "恢复时记住原意图"这个能力消失。
--
-- 幂等：列不存在时什么都不做。

DROP PROCEDURE IF EXISTS drop_agent_run_goal_json;

DELIMITER $$
CREATE PROCEDURE drop_agent_run_goal_json()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'recon_agent_run'
                 AND column_name = 'goal_json') THEN
        ALTER TABLE recon_agent_run DROP COLUMN goal_json;
    END IF;
END $$

CALL drop_agent_run_goal_json() $$
DROP PROCEDURE IF EXISTS drop_agent_run_goal_json $$
DELIMITER ;
