-- ============ recon-lens 元数据表（任务 / 分片 / 差异）============
CREATE TABLE IF NOT EXISTS recon_job (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_code     VARCHAR(64)  NOT NULL,
  batch_id      VARCHAR(64)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  shard_count   INT          NOT NULL,
  shard_done    INT          NOT NULL DEFAULT 0,
  left_rows     BIGINT       NOT NULL DEFAULT 0,
  right_rows    BIGINT       NOT NULL DEFAULT 0,
  matched_rows  BIGINT       NOT NULL DEFAULT 0,
  diff_rows     BIGINT       NOT NULL DEFAULT 0,
  remark        VARCHAR(255),
  error_msg     VARCHAR(1000),
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recon_shard (
  job_id        BIGINT      NOT NULL,
  shard_id      INT         NOT NULL,
  status        VARCHAR(16) NOT NULL,
  attempts      INT         NOT NULL DEFAULT 0,
  scanned_left  BIGINT      NOT NULL DEFAULT 0,
  scanned_right BIGINT      NOT NULL DEFAULT 0,
  matched_rows  BIGINT      NOT NULL DEFAULT 0,
  diff_rows     BIGINT      NOT NULL DEFAULT 0,
  error_msg     VARCHAR(1000),
  started_at    TIMESTAMP,
  finished_at   TIMESTAMP,
  PRIMARY KEY (job_id, shard_id)
);

CREATE TABLE IF NOT EXISTS recon_diff (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  job_id       BIGINT       NOT NULL,
  shard_id     INT          NOT NULL,
  diff_type    VARCHAR(32)  NOT NULL,
  severity     VARCHAR(8)   NOT NULL,
  biz_key      VARCHAR(128) NOT NULL,
  field_name   VARCHAR(64)  NOT NULL DEFAULT '',
  left_value   VARCHAR(512),
  right_value  VARCHAR(512),
  delta_value  DECIMAL(20,4),
  delta_ratio  DECIMAL(12,6),
  explanation  VARCHAR(1000),
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 幂等基石：同一 job 同一分片同一业务键同一字段只允许一条差异，重跑先删后写天然去重
CREATE UNIQUE INDEX IF NOT EXISTS uk_diff_shard_key
  ON recon_diff (job_id, shard_id, diff_type, biz_key, field_name);
CREATE INDEX IF NOT EXISTS idx_diff_job_type ON recon_diff (job_id, diff_type);

-- ============ 演示数据：账单（我方）与结算（对方）============
CREATE TABLE IF NOT EXISTS bill_left (
  id          BIGINT        PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS bill_right (
  id          BIGINT        PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_left_order ON bill_left (order_no);
CREATE INDEX IF NOT EXISTS idx_right_order ON bill_right (order_no);
