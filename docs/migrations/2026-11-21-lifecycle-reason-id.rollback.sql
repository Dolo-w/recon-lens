-- 回滚 2026-11-21-lifecycle-reason-id.sql
ALTER TABLE recon_diff_lifecycle
  DROP COLUMN reason_id;
