-- ADR-0001 P2：长期记忆两张表（案例记忆 + 口径文档切片）
-- 幂等：用 information_schema 判断后再建，生产 DBA 可直接重复执行。

DROP PROCEDURE IF EXISTS add_long_term_memory;
DELIMITER $$
CREATE PROCEDURE add_long_term_memory()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'recon_case_memory') THEN
        CREATE TABLE recon_case_memory (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            rule_code VARCHAR(128) NOT NULL,
            diff_type VARCHAR(64) NOT NULL,
            cause_key VARCHAR(512) NOT NULL,
            resolution VARCHAR(2000) NOT NULL,
            source VARCHAR(16) NOT NULL,
            confidence DECIMAL(3,3) NOT NULL,
            operator VARCHAR(64),
            diff_id BIGINT,
            job_id BIGINT,
            hits INT NOT NULL DEFAULT 0,
            last_hit_at TIMESTAMP NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            KEY idx_case_rule_type (rule_code, diff_type, created_at),
            KEY idx_case_key (cause_key)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'recon_doc_chunk') THEN
        CREATE TABLE recon_doc_chunk (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            doc_id VARCHAR(64) NOT NULL,
            title VARCHAR(256) NOT NULL,
            source VARCHAR(64) NOT NULL,
            meta_json TEXT,
            ordinal INT NOT NULL,
            content VARCHAR(4000) NOT NULL,
            content_hash VARCHAR(64) NOT NULL,
            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            KEY idx_doc_docid (doc_id, ordinal),
            KEY idx_doc_source (source, created_at),
            KEY idx_doc_hash (content_hash)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
    END IF;
END$$
DELIMITER ;

CALL add_long_term_memory();
DROP PROCEDURE add_long_term_memory;

-- 说明：老库无需回填。两张表都是"有就用、没有就空"，检索不到就退回模板归因。
