-- ============================================================================
-- 补丁：试算结论绑定"试算当时那份内容"（评审 P0-2）
-- 目标库：MySQL 8.0
-- ============================================================================
--
-- 漏洞链路（每一步都是合法调用，合起来绕过闸门 2 + 3）：
--   1. trial() → PASS × FULL
--   2. UPDATE recon_rule SET spec_json = ...        -- DBA 热修 / 回放导入
--   3. submit + issueToken                          -- token 绑定的是【改后】的内容
--   4. approve                                      -- token 对得上，findTrial 读到旧 PASS
--
-- reviewed_spec_sha 拦不住这条：它钉的是"审批那一刻的内容"，用来防"批完再改"。
-- 试算这一侧缺一个同等性质的时间戳，所以这里补 trial_spec_sha。
--
-- 存量数据：旧行的 trial_spec_sha 为 NULL。approve 侧按 fail-closed 处理 ——
-- 已有试算结论但哈希为空的，一律要求重跑，而不是"当作有效"。
-- 这条不走"给 NULL 补一个当前哈希"的捷径：那等于把漏洞原样写进历史数据。
-- ============================================================================

ALTER TABLE recon_rule
  ADD COLUMN trial_spec_sha CHAR(64) NULL AFTER trial_digest;

-- 存量清理（可选，但建议执行）：把"有结论、无绑定"的版本退回未试算，
-- 让审核页直接显示"没验过"，而不是等人到 approve 才被拒。
-- UPDATE recon_rule
--    SET trial_verdict = NULL, trial_coverage = NULL, trial_coverage_reason = NULL,
--        skip_reason = NULL, trial_digest = NULL, trial_report = NULL
--  WHERE trial_verdict IS NOT NULL
--    AND trial_spec_sha IS NULL
--    AND lifecycle = 'REVIEWING';
