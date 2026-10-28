-- 2026-10-27：回滚 2026-10-27-eval-case.sql
--
-- 什么时候可以回滚：代码里已经没有 `EvalCaseRepository` / `POST /api/eval/...` 的调用
-- （旧版本 jar 上就没有）。**不要只回滚库而留着新代码**：新代码写 recon_eval_case 会直接报
-- "Table doesn't exist"，而那个接口是 CI 回放链路的一部分，会比不回滚更难查。
--
-- 数据影响：这张表只存回放记录（一次 30~300 行），删掉不丢业务数据；
-- 但历史批次的「逐题明细」会一起没了 —— 只剩 docs/eval/*.md 里的人读版本。
-- 真要留证据，先 `SELECT * FROM recon_eval_case` 导出再回滚。
--
-- 幂等：表不存在时什么都不做。

DROP PROCEDURE IF EXISTS drop_eval_case;

DELIMITER $$
CREATE PROCEDURE drop_eval_case()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = DATABASE()
                 AND table_name = 'recon_eval_case') THEN
        DROP TABLE recon_eval_case;
    END IF;
END $$

CALL drop_eval_case() $$
DROP PROCEDURE IF EXISTS drop_eval_case $$
DELIMITER ;
