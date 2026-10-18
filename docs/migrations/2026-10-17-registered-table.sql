-- 2026-10-17：动态表注册（M6，闸门 1 白名单的"运行时半边"）
--
-- 背景：每个接入方的数据结构不同，白名单原来只来自配置（recon.agent.schema.tables），
-- 新用户接入要改配置并重启。现在支持对话内注册（MCP 工具 recon_register_table）：
-- preview 只读预览表结构与行数，人工确认后 confirm 才落这条记录并立即生效。
--
-- 已有环境执行本脚本；新环境由 schema.sql / schema-mysql.sql 自动创建。
-- 白名单语义变为：配置 ∪ 本表。注册与注销都要求人工确认，起草链路的 LLM 只有 preview 权力。

CREATE TABLE IF NOT EXISTS recon_registered_table (
  id               BIGINT        AUTO_INCREMENT PRIMARY KEY,
  table_name       VARCHAR(128)  NOT NULL,
  columns_snapshot VARCHAR(2048) NOT NULL,
  registered_by    VARCHAR(64)   NOT NULL,
  registered_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_registered_table (table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
