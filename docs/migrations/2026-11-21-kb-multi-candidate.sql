-- 2026-11-21 K2 多候选知识库：recon_diff_kb 的 cause_key 唯一索引降级为普通索引
-- 用途：同一 cause_key（同形状差异）允许多条归因并存 —— 9 月"税"、10 月"手续费"
--   是同形状差异的两种真实病因。teach 从"覆盖"改为"新增候选"，检索按
--   (confidence, hits, id) 取 top-1，完整候选集供 few-shot 与运营复核。
-- 已有部署执行本迁移；新部署直接用 schema-mysql.sql（已含普通索引）。
-- 回滚见同名 .rollback.sql（要求先人工去重到一键一行，否则唯一索引建不回来）。
ALTER TABLE recon_diff_kb DROP INDEX uk_kb_cause_key;
ALTER TABLE recon_diff_kb ADD INDEX idx_kb_key (cause_key);
