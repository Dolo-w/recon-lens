-- ============ recon-lens 元数据表（MySQL 版 DDL，给 DBA 执行的参考脚本）============
-- 为什么单独有一份：`schema.sql` 是 H2 用的，里面 `CREATE INDEX IF NOT EXISTS` 这种写法
-- MySQL 不支持（MySQL 只有 `CREATE TABLE IF NOT EXISTS`，索引要在建表语句里定义或单独 CREATE INDEX）。
-- 实测校验过：本文件能在 MySQL 8.0.29 上从空库一次跑通、可重复执行。
--
-- 注意：mysql profile 默认 `spring.sql.init.mode=never`（生产建表由 DBA 负责），
-- 所以这份文件不会被应用自动执行，位置在 src/main/resources/schema-mysql.sql 便于随包分发。

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
  started_at    TIMESTAMP    NULL,
  finished_at   TIMESTAMP    NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  started_at    TIMESTAMP   NULL,
  finished_at   TIMESTAMP   NULL,
  PRIMARY KEY (job_id, shard_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 幂等基石：同一 job 同一分片同一业务键同一字段只允许一条差异，重跑先删后写天然去重
  UNIQUE KEY uk_diff_shard_key (job_id, shard_id, diff_type, biz_key, field_name),
  KEY idx_diff_job_type (job_id, diff_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 规则库：使用方可以不改代码、不重启就新增/修改对账维度 ============
CREATE TABLE IF NOT EXISTS recon_rule (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  code        VARCHAR(64)   NOT NULL,
  version     INT           NOT NULL,
  spec_json   VARCHAR(8000) NOT NULL,
  enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
  created_by  VARCHAR(64),
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 规则版本：同一个 code 的每一版都留档，任务表把生效版本钉住，重跑才能复现当时的语义
  UNIQUE KEY uk_rule_code_version (code, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============ 演示数据表（只有 demo/联调需要；生产上就是被对账的业务表）============
CREATE TABLE IF NOT EXISTS bill_left (
  id          BIGINT        PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  TIMESTAMP     NOT NULL,
  KEY idx_left_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bill_right (
  id          BIGINT        PRIMARY KEY,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  TIMESTAMP     NOT NULL,
  KEY idx_right_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS resource_left (
  id          BIGINT        PRIMARY KEY,
  account_id  VARCHAR(32)   NOT NULL,
  resource_id VARCHAR(64)   NOT NULL,
  region      VARCHAR(32)   NOT NULL,
  spec        VARCHAR(32)   NOT NULL,
  monthly_fee DECIMAL(18,2) NOT NULL,
  state       VARCHAR(16)   NOT NULL,
  -- 复合业务键的取数顺序依赖 (account_id, resource_id)
  KEY idx_res_left_key (account_id, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS resource_right (
  id          BIGINT        PRIMARY KEY,
  account_id  VARCHAR(32)   NOT NULL,
  resource_id VARCHAR(64)   NOT NULL,
  region      VARCHAR(32)   NOT NULL,
  spec        VARCHAR(32)   NOT NULL,
  monthly_fee DECIMAL(18,2) NOT NULL,
  state       VARCHAR(16)   NOT NULL,
  KEY idx_res_right_key (account_id, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
