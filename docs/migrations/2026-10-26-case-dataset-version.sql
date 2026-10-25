-- 2026-10-26 案例记忆按数据版本收窄（ADR-0002 × ADR-0001 P3 第三项）
--
-- 背景：recon_case_memory 记的是「上次这类差异谁怎么处理的」。数据版本一变
-- （口径改了、粒度改了），旧案例就不再是"同源经验"：它的结论可能来自另一套
-- 对账口径。所以案例要能标出它是在哪一版数据上得到的。
--
-- 兼容：老数据这一列为 NULL，读的时候按"同一规则仍然有参考价值"处理，
-- 所以不需要回填——回填只能靠猜，猜错比留空更糟。
--
-- H2：schema.sql 里直接带这一列（H2 每次重建，无需迁移）。
-- MySQL：用 information_schema 判断后再 ALTER，可以重复执行。

DELIMITER $$

DROP PROCEDURE IF EXISTS recon_add_case_dataset_version $$

CREATE PROCEDURE recon_add_case_dataset_version()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'recon_case_memory'
          AND COLUMN_NAME = 'dataset_version'
    ) THEN
        ALTER TABLE recon_case_memory
            ADD COLUMN dataset_version VARCHAR(256) NULL AFTER job_id;
    END IF;
END $$

CALL recon_add_case_dataset_version() $$
DROP PROCEDURE recon_add_case_dataset_version $$

DELIMITER ;
