-- 回滚 2026-11-21-diff-event-at.sql
ALTER TABLE recon_diff
  DROP COLUMN event_at;
