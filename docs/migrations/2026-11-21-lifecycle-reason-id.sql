-- 2026-11-21 K1 知识回流前置：recon_diff_lifecycle 加定性结论分类列
-- 用途：核销/豁免时带上 reasonId（定性分类 ID），台账写一份、知识库 teach 一份（HUMAN/0.9）。
--   处理过的差异，下一批同类差异自动带出历史答案 —— 最贵的定性数据不再躺在台账里生锈。
-- 已有部署执行本迁移；新部署直接用 schema-mysql.sql（已含该列）。回滚见同名 .rollback.sql。
ALTER TABLE recon_diff_lifecycle
  ADD COLUMN reason_id VARCHAR(64) NULL AFTER note;
