-- 2026-10-18：试算与列表查询的索引、以及注册表快照列的类型。
-- 对已有 MySQL 部署执行一次；H2 的 schema.sql 已同步（新库直接建好）。
--
-- 1) recon_diff (job_id, biz_key)：试算的 diffsInWindow 按 job_id + biz_key 范围扫。
--    原来只有 (job_id, shard_id, ...) 的唯一键和 (job_id, diff_type)，范围扫 biz_key 要回表全扫。
CREATE INDEX idx_diff_job_key ON recon_diff (job_id, biz_key);

-- 2) recon_job (kind, id)：listJobs 的 WHERE kind='PROD' ORDER BY id DESC。
--    原来的 idx_job_code_kind 以 rule_code 打头，服务不了这个查询。
CREATE INDEX idx_job_kind_id ON recon_job (kind, id);

-- 3) recon_registered_table.columns_snapshot 2048 字符装不下 200 列的宽表，
--    超长会让 confirm 抛异常、表已建好却没注册（半成品残留）。
ALTER TABLE recon_registered_table MODIFY columns_snapshot TEXT NOT NULL;
