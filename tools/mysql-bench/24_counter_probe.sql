-- 24_counter_probe.sql
-- 为什么需要这个文件：
--   `SHOW GLOBAL STATUS` 是累计值（`FLUSH STATUS` 只清会话计数器），所以场景文件里的计数器
--   只能靠「相邻两张状态表相减」得到增量。而每个场景文件的**头部**都跑过
--   ALTER TABLE ADD INDEX / ANALYZE TABLE / CREATE TABLE ... SELECT，这些开销会落进该文件
--   「第一张状态表」，导致文件首块的增量不可归因（典型症状：S2b-1 覆盖索引的
--   Innodb_rows_read 里混着 `ALTER TABLE t50 ADD INDEX idx_cov` 的建索引读）。
-- 本文件补齐这些首块的计数器：
--   文件里**没有任何 DDL**，块之间只有 FLUSH STATUS，于是每张状态表都只比前一张多出
--   「一条 EXPLAIN ANALYZE」的开销，逐块可归因。
--   PROBE-0 是文件基线锚点（本身不归因），后面每块对应一个原场景的首块。
-- 用法：
--   & tools/mysql-bench/run.ps1 -File 24_counter_probe.sql -Tag 24_probe
-- 注意：这里复测的是「当前索引/分区状态」下的计划，与原始场景文件首次跑时的状态一致
--   （t50 上 idx_order_no / idx_cov / idx_cust_status / idx_shard_bucket 与分区表 t50p 都已存在）。
--   唯一不复测的是 S2a-1（建 idx_cov 之前只看 idx_order_no 的回表形态），那一块引用
--   11_s2a_order_index.sql 的计划即可。

USE bench;
SET SESSION sql_log_bin = 0;

SELECT '=== PROBE-0  基线锚点（不归因，仅供后面逐块相减）===' AS block;
FLUSH STATUS;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-1  首块复测 = S1a-1：ORDER BY id（物理有序，不该有 filesort）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src ORDER BY id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-2  首块复测 = S2b-1：覆盖索引，ORDER BY order_no + 3 列 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT order_no, amount, status FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-3  首块复测 = S2b-2：覆盖索引被破坏，多取一列 fee ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT order_no, amount, status, fee FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-4  首块复测 = S3-0：GROUP BY 纯列（有 idx_cust_status）+ ORDER BY 同列 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT customer_id, status, SUM(amount) AS amount, COUNT(*) AS cnt
FROM t50 GROUP BY customer_id, status ORDER BY customer_id, status;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-5  首块复测 = S4-1：单次全量（不分片）COUNT(*) ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-6  首块复测 = S5-1：分区表裁剪到单月 + ORDER BY order_no ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50p
WHERE biz_date >= '2025-11-01' AND biz_date < '2025-12-01' ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-7  同批对照 = S5-6：同一个"单月"谓词在非分区表 t50 上（无法裁剪）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50
WHERE biz_date >= '2025-11-01' AND biz_date < '2025-12-01' ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-8  表达式替换成功：AS UNSIGNED（与生成列定义一致）=== ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 0;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-9  表达式替换失败：AS SIGNED（同长度、不同类型）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), 8) = 0;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-10  单分片取数：桶列 + ORDER BY order_no（引擎分片内取数形态）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src WHERE shard_bucket = 0 ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== PROBE-11  尾部锚点（本文件累计，不归因）===' AS block;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');
