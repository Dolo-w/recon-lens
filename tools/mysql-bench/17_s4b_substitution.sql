-- 17（补充）：生成列与"表达式等价替换" —— 到底哪种写法能让优化器用上索引、让排序消失
-- 起因：14_s4 里函数谓词的执行计划变成了 `Index lookup on t50 using idx_shard_bucket (shard_bucket=0)`，
--       也就是说 MySQL 8.0.29 把与 STORED 生成列定义**完全一致**的表达式替换成了生成列并用了索引。
--       但演示规则写的是 `CAST(... AS INT)`（生成列用的是 AS UNSIGNED）——必须验证"写法差一点会不会就失效"。
USE bench;

SELECT '=== S4b-0  表达式替换探查（只看计划，不执行）===' AS block;
SELECT 'plan: AS UNSIGNED（与生成列定义一致）' AS case_1;
EXPLAIN SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 0;
SELECT 'plan: AS SIGNED（MySQL 里最接近 INT 的写法）' AS case_2;
EXPLAIN SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), 8) = 0;
SELECT 'plan: AS SIGNED + 8（与生成列同构，但常量顺序不同）' AS case_3;
EXPLAIN SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), 8) = 0;
SELECT 'plan: CAST(... AS CHAR) 再比较' AS case_4;
EXPLAIN SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS CHAR), 8) = 0;
-- 注意：演示规则与文档里写的是 CAST(... AS INT)，MySQL 8.0 直接语法报错：ERROR 1064 ... near 'INT), 8) = 0'
-- （H2 接受 AS INT，MySQL 只认 SIGNED/UNSIGNED —— 这是规则跨库可移植性上的一个真坑）

SELECT '=== S4b-1  计时对照：AS UNSIGNED（能替换成生成列）vs AS SIGNED（同样长度但类型不同）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 0;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), 8) = 0;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S4b-2  把派生维度键物化成 STORED 生成列 + 索引（10M 行，需要一两分钟）===' AS block;
ALTER TABLE t50 ADD COLUMN biz_key_gen VARCHAR(64)
  GENERATED ALWAYS AS (CONCAT(customer_id, '|', status)) STORED,
  ADD INDEX idx_biz_key_gen (biz_key_gen);
ANALYZE TABLE t50;

SELECT '=== S4b-3  明细形态：ORDER BY 表达式（引擎当前形态，仍要排）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, src.*
FROM (SELECT * FROM t50) src ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_next','Innodb_rows_read');

SELECT '=== S4b-4  明细形态：ORDER BY 生成列（有序读，排序消失）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, src.*
FROM (SELECT * FROM t50) src ORDER BY biz_key_gen;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_next','Innodb_rows_read');

SELECT '=== S4b-5  聚合形态：GROUP BY 表达式 + ORDER BY 表达式（临时表 + 排序）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY CONCAT(customer_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_next','Innodb_rows_read');

SELECT '=== S4b-6  聚合形态：GROUP BY 生成列 + ORDER BY 生成列（流式分组，无临时表）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT biz_key_gen AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY biz_key_gen ORDER BY biz_key_gen;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_next','Innodb_rows_read');
