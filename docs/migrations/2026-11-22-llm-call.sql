-- 大模型调用账本（清单 P0-2 成本归因）
-- 回滚：见 2026-11-22-llm-call.rollback.sql
CREATE TABLE IF NOT EXISTS recon_llm_call (
  id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
  purpose               VARCHAR(32)   NOT NULL,
  provider              VARCHAR(32)   NOT NULL,
  model                 VARCHAR(128)  NOT NULL,
  prompt_tokens         INT           NOT NULL DEFAULT 0,
  completion_tokens     INT           NOT NULL DEFAULT 0,
  total_tokens          INT           NOT NULL DEFAULT 0,
  latency_ms            BIGINT        NOT NULL DEFAULT 0,
  attempt               INT           NOT NULL DEFAULT 1,
  outcome               VARCHAR(16)   NOT NULL,
  error_kind            VARCHAR(64)   NULL,
  cost_micros           BIGINT        NOT NULL DEFAULT 0,
  price_input_snapshot  DECIMAL(12,4) NOT NULL DEFAULT 0,
  price_output_snapshot DECIMAL(12,4) NOT NULL DEFAULT 0,
  called_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_llm_call_at (called_at),
  KEY idx_llm_call_purpose (purpose, called_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
