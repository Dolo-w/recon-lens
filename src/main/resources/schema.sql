-- ============ recon-lens 元数据表（任务 / 分片 / 差异）============
CREATE TABLE IF NOT EXISTS recon_job (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  rule_code     VARCHAR(64)  NOT NULL,
  rule_version  INT,
  rule_snapshot VARCHAR(8000),
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

-- ============ 规则库：使用方可以不改代码、不重启就新增/修改对账维度 ============
CREATE TABLE IF NOT EXISTS recon_rule (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64)   NOT NULL,
  version     INT           NOT NULL,
  spec_json   VARCHAR(8000) NOT NULL,
  enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
  created_by  VARCHAR(64),
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 规则版本：同一个 code 的每一版都留档，任务表把生效版本钉住，重跑才能复现当时的语义
CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_code_version ON recon_rule (code, version);

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

-- ============ 演示数据：资源清单与计费清单（复合维度：账号 + 资源ID）============
CREATE TABLE IF NOT EXISTS resource_left (
  id          BIGINT        PRIMARY KEY,
  account_id  VARCHAR(32)   NOT NULL,
  resource_id VARCHAR(64)   NOT NULL,
  region      VARCHAR(32)   NOT NULL,
  spec        VARCHAR(32)   NOT NULL,
  monthly_fee DECIMAL(18,2) NOT NULL,
  state       VARCHAR(16)   NOT NULL
);

CREATE TABLE IF NOT EXISTS resource_right (
  id          BIGINT        PRIMARY KEY,
  account_id  VARCHAR(32)   NOT NULL,
  resource_id VARCHAR(64)   NOT NULL,
  region      VARCHAR(32)   NOT NULL,
  spec        VARCHAR(32)   NOT NULL,
  monthly_fee DECIMAL(18,2) NOT NULL,
  state       VARCHAR(16)   NOT NULL
);

-- 复合业务键的取数顺序依赖 (account_id, resource_id)，这里给它一条索引（声明式维度生成的是表达式键，索引是否命中见 docs/rule-dsl.md）
CREATE INDEX IF NOT EXISTS idx_res_left_key ON resource_left (account_id, resource_id);
CREATE INDEX IF NOT EXISTS idx_res_right_key ON resource_right (account_id, resource_id);
