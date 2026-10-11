-- S7-8b：给"分片桶 + 排序键 + 投影列"建一个**分片局部覆盖索引**
-- 论点：单列桶索引(idx_shard_bucket)只解决"定位分片"，行还要回表 + filesort；
--       把桶列放最左、后面跟 order_no 与投影列，才能同时拿到「定位 + 有序 + 覆盖」。
USE bench;

SELECT '=== S7-8b-0  建索引前的计划（单列桶索引：看回表和 filesort）===' AS block;
EXPLAIN ANALYZE
SELECT id, order_no, amount, status, shard_bucket
FROM bill_left WHERE shard_bucket = 3 ORDER BY order_no;

SELECT '=== S7-8b-1  建分片局部覆盖索引（可重复执行）===' AS block;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA='bench' AND TABLE_NAME='bill_left' AND INDEX_NAME='idx_s7_shard_cover') > 0,
               'DROP INDEX idx_s7_shard_cover ON bill_left', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
SET @sql := IF((SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA='bench' AND TABLE_NAME='bill_right' AND INDEX_NAME='idx_s7_shard_cover') > 0,
               'DROP INDEX idx_s7_shard_cover ON bill_right', 'DO 0');
PREPARE st FROM @sql; EXECUTE st; DEALLOCATE PREPARE st;
FLUSH STATUS;
CREATE INDEX idx_s7_shard_cover ON bill_left (shard_bucket, order_no, amount, status, id);
CREATE INDEX idx_s7_shard_cover ON bill_right (shard_bucket, order_no, amount, status, id);
ANALYZE TABLE bill_left, bill_right;
SELECT '建索引期间 Innodb_rows_read' AS note;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_rows_read');

SELECT '=== S7-8b-2  建索引后的计划（期望：Using index，无 filesort）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT id, order_no, amount, status, shard_bucket
FROM bill_left WHERE shard_bucket = 3 ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN
  ('Innodb_rows_read','Sort_rows','Sort_scan','Sort_range','Handler_read_next','Handler_read_rnd_next');
