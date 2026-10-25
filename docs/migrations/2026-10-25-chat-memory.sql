-- 2026-10-25：会话记忆（ADR-0001 的 P0 部分）。
-- 对已有 MySQL 部署执行一次；H2 的 schema.sql 已同步（新库直接建好）。
--
-- 只建两张表，**不改变任何现有语义**：
--   1) recon_chat_session —— 一段对话一行，state_json 存槽位、summary 存压缩后的结论；
--   2) recon_chat_turn    —— 原始轮次，只增不改（压缩只写 summary，不动这里）。
--
-- 会话隔离（ADR-0001 D8）靠 visibility 一列实现，不加 tenant_id：
-- 当前唯一身份源是一个 api-key、X-Operator 由调用方自填，tenant 必然永远是 'default'，
-- 加了反而让人误以为多租户已支持。真要做多租户的前置条件是认证，不是这一列。
--
-- 未建的表（P1/P2 再说）：recon_case_memory、recon_doc_chunk。

CREATE TABLE IF NOT EXISTS recon_chat_session (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  operator    VARCHAR(64)  NOT NULL,
  -- PRIVATE（默认，只有自己）/ SHARED（同部署可见，只有 owner 能继续问）/ HANDOVER（交接后归新 owner）
  visibility  VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
  title       VARCHAR(200),
  state_json  TEXT,                       -- {ruleCode,jobId,shard,timeWindow,focusTypes}
  summary     TEXT,                       -- 滚动摘要（必须记下"被否决的方案"，见 N3）
  turn_count  INT          NOT NULL DEFAULT 0,
  status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 列表固定是 WHERE (operator = ? OR visibility <> 'PRIVATE') ORDER BY last_at DESC
  KEY idx_chat_session_operator (operator, last_at),
  KEY idx_chat_session_visibility (visibility, last_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recon_chat_turn (
  id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
  session_id  BIGINT       NOT NULL,
  seq         INT          NOT NULL,
  role        VARCHAR(16)  NOT NULL,      -- user / assistant / tool
  content     TEXT,
  tool_calls  TEXT,                       -- 与 recon_agent_step 用同一个截断策略（N7）
  tokens      INT,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- seq 在会话内唯一：顺序错乱在复盘时等于"对话不可读"
  UNIQUE KEY uk_chat_turn_seq (session_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 交接的审计不单开表：复用 recon_agent_run / recon_agent_step
-- （trigger_type='SESSION_HANDOVER'、action='handover-session'），一行 run + 一行 step。
