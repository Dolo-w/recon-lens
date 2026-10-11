-- S1a：明细取数排序（**还没有任何二级索引**时的形态）
-- 对应引擎 ShardPlanner.selectSql：SELECT * FROM (SELECT * FROM t50) src ORDER BY <orderBy>
-- 四组对照：物理有序（ORDER BY id）/ 收窄列排序 / 50 列宽行排序 / 派生表达式键 + 50 列宽行排序
-- 每组前 FLUSH STATUS、后打增量计数器，便于把"谁落盘、谁多路归并"归到具体一条 SQL
USE bench;

SELECT '=== S1a-1  ORDER BY id（物理有序，不该有 filesort）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src ORDER BY id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S1a-2  ORDER BY order_no，只取 3 列（窄行 filesort）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT order_no, amount, status FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S1a-3  ORDER BY order_no，SELECT *（50 列宽行 filesort）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S1a-4  派生表达式键 + SELECT *（声明式明细维度的真实形态）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, src.*
FROM (SELECT * FROM t50) src ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Innodb_rows_read');

SELECT '=== S1a 累计状态 ===' AS block;
SHOW GLOBAL STATUS WHERE Variable_name IN (
  'Created_tmp_tables','Created_tmp_disk_tables','Created_tmp_files',
  'Sort_merge_passes','Sort_range','Sort_scan','Sort_rows',
  'Handler_read_rnd_next','Handler_read_rnd','Handler_read_key','Handler_read_next',
  'Innodb_rows_read','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',
  'Select_scan','Select_full_join');
