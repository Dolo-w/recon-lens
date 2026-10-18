-- M3 + M4：Agent 会话轨迹 + 归因知识库
--
-- 为什么轨迹要落库：验收 A8 要求
--   recon_agent_run.llm_calls ↔ recon_agent_step ↔ recon_rule.draft_reason ↔ recon_job
-- 串得起来。只打日志的话，"模型第几轮才改对、它当时看到了什么"会随日志滚动一起消失。
--
-- 三个字段的取舍（都是评审里踩过的坑）：
--   ① plan_json 用 TEXT 不用 VARCHAR(8000)：utf8mb4 下 VARCHAR(8000) 占 32,002 字节，
--      两个就顶穿 MySQL 行上限，写入是 ERROR 1406（不是静默截断）。
--   ② payload 与 digest 分两列：v0.1 叫 output_digest 却存内容 —— 既不能按哈希比对，也不敢截断。
--      现在 payload 可截断（truncated 标记），digest 是真 sha256。
--   ③ recon_diff_kb.cause 是"值"不是键：查库时还不知道原因，把原因放进 cause_key
--      等于拿答案查索引，命中率恒为 0（评审 R3）。
--
-- 上线顺序：先执行本脚本，再发布带 Agent 的版本。
-- 缺表时 startRun 会立刻失败（启动期硬错），不会静默丢轨迹。

CREATE TABLE IF NOT EXISTS recon_agent_run (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  goal           TEXT         NOT NULL,
  trigger_type   VARCHAR(16)  NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  plan_json      TEXT         NULL,
  plan_truncated TINYINT(1)   NOT NULL DEFAULT 0,
  rule_code      VARCHAR(64)  NULL,
  job_id         BIGINT       NULL,
  draft_version  INT          NULL,
  llm_calls      INT          NOT NULL DEFAULT 0,
  llm_tokens     INT          NOT NULL DEFAULT 0,
  error_msg      VARCHAR(1000) NULL,
  created_by     VARCHAR(64)  NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at    TIMESTAMP    NULL,
  KEY idx_agent_run_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recon_agent_step (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id     BIGINT      NOT NULL,
  seq        INT         NOT NULL,
  phase      VARCHAR(16) NOT NULL,
  action     VARCHAR(64) NOT NULL,
  input_json TEXT        NULL,
  payload    TEXT        NULL,
  digest     CHAR(64)    NULL,
  truncated  TINYINT(1)  NOT NULL DEFAULT 0,
  status     VARCHAR(16) NOT NULL,
  cost_ms    INT         NULL,
  created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_step (run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recon_diff_kb (
  id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
  cause_key         VARCHAR(64)   NOT NULL,
  rule_code         VARCHAR(64)   NOT NULL,
  diff_type         VARCHAR(64)   NOT NULL,
  field_name        VARCHAR(64)   NOT NULL DEFAULT '',
  feature_signature VARCHAR(256)  NOT NULL DEFAULT '',
  cause             VARCHAR(1000) NOT NULL,
  action            VARCHAR(1000) NULL,
  confidence        DECIMAL(4,3)  NOT NULL DEFAULT 0.500,
  source            VARCHAR(16)   NOT NULL,
  hits              INT           NOT NULL DEFAULT 0,
  misses            INT           NOT NULL DEFAULT 0,
  created_by        VARCHAR(64)   NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP     NULL,
  UNIQUE KEY uk_kb_cause_key (cause_key),
  KEY idx_kb_lookup (rule_code, diff_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
