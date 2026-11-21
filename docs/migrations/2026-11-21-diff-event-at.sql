-- 2026-11-21 事件级对账：recon_diff 加业务发生时刻列
-- 用途：规则配置 eventTime 后，差异带业务发生时刻，价格按业务发生时刻匹配（月中调价精确归因）。
-- 已有部署执行本迁移；新部署直接用 schema-mysql.sql（已含该列）。回滚见同名 .rollback.sql。
ALTER TABLE recon_diff
  ADD COLUMN event_at VARCHAR(64) NULL AFTER explanation;
