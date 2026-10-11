-- S3：聚合口径 —— GROUP BY 表达式键 + ORDER BY 同一表达式（引擎 aggregated 形态的逐字复刻）
--   SELECT <keyExpr> AS __biz_key, <measures> FROM (<inner>) src GROUP BY <keyExpr> ORDER BY __biz_key
-- 变量只有一个：分组基数 G（约 15 万 / 100 万 / 1000 万），看临时表、落盘与排序规模怎么变
USE bench;
SET SESSION sql_log_bin = 0;

SELECT '=== S3-0 基线：GROUP BY 纯列（有索引）+ ORDER BY 同列 ===' AS block;
ALTER TABLE t50 ADD INDEX idx_cust_status (customer_id, status);
ANALYZE TABLE t50;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT customer_id, status, SUM(amount) AS amount, COUNT(*) AS cnt
FROM t50 GROUP BY customer_id, status ORDER BY customer_id, status;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S3-1  G≈15 万（customer_id × status），表达式键 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY CONCAT(customer_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S3-2  G≈100 万（acct_id × status），表达式键 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(acct_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY CONCAT(acct_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S3-3  G≈1000 万（resource_id 唯一），表达式键 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(resource_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY CONCAT(resource_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S3-4  把内存临时表限额从 1G 压到 128M，再跑 G≈100 万 ===' AS block;
SET GLOBAL temptable_max_ram = 134217728;
SELECT @@temptable_max_ram AS temptable_max_ram_now;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(acct_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY CONCAT(acct_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S3-5  同样 128M 限额下跑 G≈15 万 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM (SELECT * FROM t50) src GROUP BY CONCAT(customer_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SET GLOBAL temptable_max_ram = 1073741824;
SELECT @@temptable_max_ram AS temptable_max_ram_restored;
