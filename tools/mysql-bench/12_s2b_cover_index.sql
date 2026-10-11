-- S2b：覆盖索引 —— 同一查询、只是把要比的列也放进索引
-- 对照 S2a-1（只有 idx_order_no，回表）与本节（idx_cov 覆盖，不回表）
USE bench;
SET SESSION sql_log_bin = 0;
ALTER TABLE t50 ADD INDEX idx_cov (order_no, amount, status);
ANALYZE TABLE t50;

SELECT '=== S2b-1  覆盖索引：ORDER BY order_no + 3 列（期望：Using index，不回表）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT order_no, amount, status FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S2b-2  覆盖索引被破坏：多取一列 fee（回到回表）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT order_no, amount, status, fee FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S2b-3  聚合口径、分组键还没有索引（临时表 + 排序；索引版对照见 13_s3_agg.sql 的 S3-0）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT customer_id, status, SUM(amount) AS amount, COUNT(*) AS cnt
FROM t50 GROUP BY customer_id, status ORDER BY customer_id, status;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');
