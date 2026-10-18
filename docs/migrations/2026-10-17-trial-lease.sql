-- D2：试算样本表的租约登记（多实例下"清理别删错"的判据）
--
-- 背景：原先的回收只认表名前缀 trial_%，在单实例下成立 —— 那时库里的样本表要么是本次刚建的，
-- 要么确实是谁崩溃后留下的。多实例下这个推论断了：B 启动时看到的 trial_xxx_l 可能正是 A 此刻
-- 在跑的那张，于是"清理"变成"删别人的工作区"。
--
-- 为什么归属不挂在 recon_job 上：
--   ① 样本表在 job 创建之前就已建好（CTAS 先于 runTraced），建表后崩掉的话没有任何 job 指向它；
--   ② 一次试算有 TRIAL×2 + CANARY 三个 job 共用同一对表，按 job 判归属连"这对表属于谁"都说不清。
--   表属于"这次试算"，所以租约按 run_id 记。
--
-- 上线顺序：先建这张表，再发布带租约逻辑的代码。
-- 反过来的话，新代码 register 会因表不存在而失败 —— 那是启动期硬错，不会静默放过，但会挡住试算。
-- 老残留表在这张表建好之后，会被"表存在、无登记"的孤儿分支回收。

CREATE TABLE IF NOT EXISTS recon_trial_sample (
  run_id       VARCHAR(32) NOT NULL PRIMARY KEY,
  table_left   VARCHAR(64) NOT NULL,
  table_right  VARCHAR(64) NOT NULL,
  owner        VARCHAR(96) NOT NULL,
  heartbeat_at TIMESTAMP   NOT NULL,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_trial_sample_heartbeat (heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
