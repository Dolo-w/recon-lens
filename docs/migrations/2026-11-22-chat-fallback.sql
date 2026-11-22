-- 2026-11-22：意图路由 fallback 语料表（路由评估闭环）
-- 用途：收集"没听懂"的用户原话当负样本，每一条都是候选的新词表条目或新意图。
-- 前置检查：SELECT COUNT(*) FROM information_schema.tables
--           WHERE table_name = 'recon_chat_fallback';

CREATE TABLE IF NOT EXISTS recon_chat_fallback (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  session_id  BIGINT       NULL,
  query_text  VARCHAR(512) NOT NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_fallback_time ON recon_chat_fallback (created_at);

-- ============ 回滚 ============
-- 回滚前先确认语料已导出（这些原话丢了就要重新踩一遍坑）：
--   SELECT query_text, created_at FROM recon_chat_fallback ORDER BY created_at DESC;
-- DROP INDEX idx_chat_fallback_time ON recon_chat_fallback;
-- DROP TABLE recon_chat_fallback;
