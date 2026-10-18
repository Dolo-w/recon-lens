-- M1：规则生命周期（闸门 3）—— 已上线实例的增量迁移
--
-- 只做 M1 需要的列。试算相关的 trial_* 列属于 M2（闸门 2），到时另起一条 ALTER。
-- 为什么不一次加全 15 列：§7.1 那条"必须一条 ALTER"的约束是为了避免**半途失败留下中间态**，
-- 不是要求把 M1/M2 的列混在一次变更里 —— 迁移应当是"一个里程碑一条"，可回滚、可对账。
--
-- 执行前确认：recon_rule 只有几十行；本 ALTER 是 in-place（只加列、不 MODIFY 现有列）。

ALTER TABLE recon_rule
  ADD COLUMN lifecycle         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '版本级状态：DRAFT/REVIEWING/ACTIVE/REJECTED；resolve 只认 ACTIVE',
  ADD COLUMN origin            VARCHAR(16)  NOT NULL DEFAULT 'HUMAN'  COMMENT 'HUMAN / AGENT',
  ADD COLUMN base_version      INT          NULL                      COMMENT '草稿基于哪一版（闸门 3 算字段级 diff）',
  ADD COLUMN draft_reason      TEXT         NULL                      COMMENT '起草理由 / 假设',
  ADD COLUMN spec_sha256       CHAR(64)     NULL                      COMMENT 'spec_json 的内容哈希',
  ADD COLUMN reviewed_spec_sha CHAR(64)     NULL                      COMMENT '审批时钉住的内容哈希（R9）',
  ADD COLUMN reviewed_by       VARCHAR(64)  NULL,
  ADD COLUMN reviewed_at       TIMESTAMP    NULL;

-- 历史版本都是"当年生效的版本"，ADD COLUMN 的 DEFAULT 'ACTIVE' 已经把它们填好了。
-- 不需要 UPDATE ... WHERE lifecycle IS NULL：该列 NOT NULL，不存在 NULL 行（v0.3 那条是死代码）。
--
-- spec_sha256 由应用启动时回填（RuleCatalog#backfillSpecSha），不在 SQL 里算：
-- 哈希必须与运行时的 SpecHasher 完全一致，SQL 侧另写一份算法等于制造第二个口径。

-- 回滚（会丢掉审批记录，只在紧急回退时用）：
-- ALTER TABLE recon_rule
--   DROP COLUMN lifecycle, DROP COLUMN origin, DROP COLUMN base_version, DROP COLUMN draft_reason,
--   DROP COLUMN spec_sha256, DROP COLUMN reviewed_spec_sha, DROP COLUMN reviewed_by, DROP COLUMN reviewed_at;
