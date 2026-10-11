-- S4：分片谓词 —— 函数化谓词（引擎演示规则原样） vs 生成列桶（同一函数物化成列 + 索引）
-- 引擎演示规则：WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS INT), {shards}) = {index}
USE bench;
SET SESSION sql_log_bin = 0;
ALTER TABLE t50
  ADD COLUMN shard_bucket TINYINT GENERATED ALWAYS AS (MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8)) STORED,
  ADD INDEX idx_shard_bucket (shard_bucket);
ANALYZE TABLE t50;

SELECT '=== S4-1  单次全量（不分片）基线：COUNT(*) ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S4-2  8 个分片，函数化谓词，各片 COUNT(*) ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 0;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 1;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 2;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 3;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 4;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 5;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 6;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 7;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S4-3  8 个分片，生成列桶 + 索引，各片 COUNT(*) ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 0;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 1;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 2;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 3;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 4;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 5;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 6;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE shard_bucket = 7;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S4-4  分片均衡度：每片行数与最大/平均比 ===' AS block;
SELECT shard_bucket, COUNT(*) AS rows_in_shard
FROM (SELECT shard_bucket FROM t50) x GROUP BY shard_bucket ORDER BY shard_bucket;
SELECT MIN(c) AS min_rows, ROUND(AVG(c)) AS avg_rows, MAX(c) AS max_rows,
       ROUND(MAX(c) / AVG(c), 3) AS max_over_avg
FROM (SELECT COUNT(*) AS c FROM t50 GROUP BY shard_bucket) y;

SELECT '=== S4-5  真实取数形态：单分片 SELECT * + ORDER BY order_no（函数谓词）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src
WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 0 ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S4-6  真实取数形态：单分片 SELECT * + ORDER BY order_no（生成列桶）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src
WHERE shard_bucket = 0 ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');
