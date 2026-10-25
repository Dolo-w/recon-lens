-- 2026-10-24：数据版本与数据集身份（ADR-0002 的 P0/P1 部分）。
-- 对已有 MySQL 部署执行一次；H2 的 schema.sql 已同步（新库直接建好）。
--
-- 只做三件**不改变任何现有语义**的事：
--   1) 注册表补 L1 指纹（列名哈希 + 行数）——行数本来就在预览时算过，只是没存；
--   2) job 补 data_snapshot —— 与已有的 rule_snapshot 对称，钉住「本次读的是哪些物理表」；
--   3) 建 recon_dataset —— 逻辑名到物理表的映射，P2 才用得上，先建表不影响老代码。
--
-- 规则 `dataset:` 间接层、升版闸门、recon_compare_jobs 属于 P2/P3，不在本脚本范围内。

-- 1) 注册表：结构指纹与行数（允许为空，老记录不回填）
ALTER TABLE recon_registered_table ADD COLUMN schema_hash VARCHAR(64) NULL;
ALTER TABLE recon_registered_table ADD COLUMN row_count   BIGINT      NULL;

-- 2) 任务：数据快照 + 冗余的版本串
ALTER TABLE recon_job ADD COLUMN data_snapshot   TEXT         NULL;
ALTER TABLE recon_job ADD COLUMN dataset_version VARCHAR(256) NULL;

-- 3) 数据集身份表
--    一个逻辑名只能有一个 ACTIVE 版本：用 active_slot（ACTIVE 时 = logical_name，其余 NULL）
--    + 唯一索引实现，避开 MySQL 8 / H2 都没有的部分唯一索引。
CREATE TABLE IF NOT EXISTS recon_dataset (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  logical_name     VARCHAR(128)  NOT NULL,
  version          INT           NOT NULL,
  table_name       VARCHAR(128)  NOT NULL,
  schema_hash      VARCHAR(64)   NOT NULL,
  row_count        BIGINT,
  content_checksum VARCHAR(64),
  status           VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE',
  active_slot      VARCHAR(128),
  supersedes_id    BIGINT,
  relation         VARCHAR(16)   NOT NULL,
  source           VARCHAR(16)   NOT NULL,
  created_by       VARCHAR(64),
  created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  note             VARCHAR(512),
  UNIQUE KEY uk_dataset_name_ver (logical_name, version),
  UNIQUE KEY uk_dataset_active (active_slot),
  KEY idx_dataset_table (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 灰度期提醒：老 job 的 data_snapshot 为 NULL 是**预期状态**，不是数据缺失。
-- recon_job_summary 必须返回 dataRecorded=false（ADR-0002 N5），UI 显示「未记录」而不是空。
