-- S2a：给排序键建二级索引后，同一批查询变成什么样
-- 关键问题：走索引序 = 不排序，但 ORDER BY 索引列 + SELECT * 是否变成"千万次回表"
USE bench;
SET SESSION sql_log_bin = 0;
ALTER TABLE t50 ADD INDEX idx_order_no (order_no);
ANALYZE TABLE t50;

SELECT '=== S2a-1  建索引后：ORDER BY order_no + 3 列（期望：索引有序读 + 回表）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT order_no, amount, status FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S2a-2  建索引后：ORDER BY order_no + SELECT *（排序没了，但每行都要回表）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S2a-3  建索引后：派生表达式键 + SELECT *（索引帮不上，仍要排）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, src.*
FROM (SELECT * FROM t50) src ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');
