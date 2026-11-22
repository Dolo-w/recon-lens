-- 告警表（清单 P0-6）
-- 回滚：见 2026-11-22-alert.rollback.sql
CREATE TABLE IF NOT EXISTS recon_alert (
  id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
  alert_key       VARCHAR(64)   NOT NULL,
  severity        VARCHAR(16)   NOT NULL,
  message         VARCHAR(500)  NOT NULL,
  metric_value    DECIMAL(20,4) NOT NULL DEFAULT 0,
  threshold_value DECIMAL(20,4) NOT NULL DEFAULT 0,
  status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
  notified        TINYINT(1)    NOT NULL DEFAULT 0,
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at     TIMESTAMP     NULL,
  KEY idx_alert_status (status, alert_key),
  KEY idx_alert_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
