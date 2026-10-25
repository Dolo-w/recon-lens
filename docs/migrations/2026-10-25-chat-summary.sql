-- 滚动摘要：让会话能在「不把全部轮次塞进上下文」的前提下继续说下去。
--
-- 为什么加 summary_upto_seq 而不是只加 summary：
--   摘要必须能增量。没有水位线，每次压缩都要从头再压一遍，压到第 50 轮时
--   要重读 50 轮历史——而压缩的目的恰恰是「别每次都读全部历史」。
--   有了水位线，第 N 次压缩只处理 summary_upto_seq 之后的新轮次。
--
-- 兼容性：两列都可为空/有默认值，老库 ALTER 之后历史会话的 summary 是 NULL、
-- summary_upto_seq 是 0，等价于「还没压过」，不需要回填。

ALTER TABLE recon_chat_session ADD COLUMN summary_upto_seq INT NOT NULL DEFAULT 0;

-- 幂等写法（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报 1060；
-- 生产执行前请先 SELECT 确认列不存在，或改用下面的存储过程版本）：
--
--   SET @exist := (SELECT COUNT(*) FROM information_schema.columns
--                  WHERE table_schema = DATABASE() AND table_name = 'recon_chat_session'
--                    AND column_name = 'summary_upto_seq');
--   SET @sql := IF(@exist = 0,
--                  'ALTER TABLE recon_chat_session ADD COLUMN summary_upto_seq INT NOT NULL DEFAULT 0',
--                  'SELECT 1');
--   PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
