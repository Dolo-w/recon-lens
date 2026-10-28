-- 2026-10-27：评测逐题记录表（在线指标闭环的第一格）
--
-- 为什么需要它：一份 markdown 报告只能被人看，不能聚合、不能 diff、不能进 CI 判定。
-- 行化之后同一套口径函数（eval/EvalMetrics）既能聚合回放记录，也能在真上线后
-- 改成聚合行为表 —— 口径不换，只换数据源。
--
-- 字段刻意与 recon_agent_step 对齐（turns / cost_ms / tokens_est），
-- 这样以后「回放口径 → 生产口径」的迁移只是换 FROM 子句。
--
-- 幂等：先查 information_schema 再建表，可重复执行。
-- H2（测试/演示）不跑这个脚本 —— schema.sql 的建表语句已含该表。
--
-- 回滚脚本：同目录 2026-10-27-eval-case.rollback.sql（不自动执行）。

DROP PROCEDURE IF EXISTS add_eval_case;

DELIMITER $$
CREATE PROCEDURE add_eval_case()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = DATABASE()
                     AND table_name = 'recon_eval_case') THEN
        CREATE TABLE recon_eval_case (
          id              BIGINT       NOT NULL AUTO_INCREMENT,
          batch           VARCHAR(64)  NOT NULL COMMENT '一次回放的标签（p1/p2-deepseek/ci-p2…）',
          case_no         INT          NOT NULL COMMENT '题号，同批次内唯一，用来对齐 diff',
          question        TEXT         NOT NULL,
          expected_intent VARCHAR(32)  NULL,
          actual_intent   VARCHAR(32)  NULL,
          expected_tool   VARCHAR(64)  NULL,
          actual_tool     VARCHAR(255) NULL COMMENT '实际调用集合，形如 [a, b]',
          turns           INT          NOT NULL DEFAULT 1,
          multi           BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '多轮任务；决定是否进「平均轮数」分母',
          cost_ms         INT          NULL COMMENT '该题耗时；回放脚本暂填 0',
          tokens_est      INT          NULL,
          passed          BOOLEAN      NOT NULL,
          created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (id),
          UNIQUE KEY uk_eval_batch_case (batch, case_no)
        ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
    END IF;
END $$

CALL add_eval_case() $$
DROP PROCEDURE IF EXISTS add_eval_case $$
DELIMITER ;
