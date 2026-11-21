-- 回滚 2026-11-21-kb-multi-candidate.sql
-- ⚠️ 前提：必须先把同一 cause_key 的多行候选人工合并到一行（保留 confidence 最高那条），
-- 否则唯一索引创建会直接失败 —— 这是刻意的：回滚不允许悄悄丢知识。
-- 查重：SELECT cause_key, COUNT(*) FROM recon_diff_kb GROUP BY cause_key HAVING COUNT(*) > 1;
ALTER TABLE recon_diff_kb DROP INDEX idx_kb_key;
ALTER TABLE recon_diff_kb ADD UNIQUE INDEX uk_kb_cause_key (cause_key);
