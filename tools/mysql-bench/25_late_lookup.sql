-- 25_late_lookup.sql
-- 问题：对账取数能不能改成"先排序只取主键，再按主键一条条取对账要的列"（late row lookup /
--   deferred join），这样排序输入变窄、回表只在需要时发生，能不能更快？
-- 文件内**没有任何 DDL** ⇒ 块之间只有一条 EXPLAIN ANALYZE，逐块可归因（同 24_counter_probe.sql）。
-- 三个形态对照（t50 1000 万行，索引：PRIMARY(id)、idx_order_no(order_no)、idx_cov(order_no,amount,status)）：
--   LL-1       只排序取主键（用户方案的第一阶段，全量）
--   LL-2/LL-4  单阶段：一条 SQL 排序 + 取 4 列（当前引擎形态；有 LIMIT 便于和两阶段同规模比较）
--   LL-3/LL-5  两阶段：物化"排序后的主键序列" + 主键回表取 4 列（= 用户方案的合成写法）
--   LL-6       分页形态（取前 N）：优化器自己会不会改用"索引序 + 回表"
-- 注意 LIMIT 在这里有两个作用：① 把两阶段的代价限制在可测规模；② 阻止派生表被合并回外层
--   （含 LIMIT 的派生表会物化，否则 MySQL 会把两阶段重写成单阶段，测不到"回表"）。
-- 用法：
--   & tools/mysql-bench/run.ps1 -File 25_late_lookup.sql -Tag 25_latelookup

USE bench;
SET SESSION sql_log_bin = 0;

SELECT '=== LL-0  基线锚点（不归因，仅供后面逐块相减）===' AS block;
FLUSH STATUS;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-1  第一阶段：只排序取主键 id（1000 万行，全量）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT id FROM (SELECT * FROM t50) src ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-2  单阶段基线：ORDER BY order_no 取 4 列，LIMIT 200000（覆盖索引）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT id, order_no, amount, status FROM (SELECT * FROM t50) src ORDER BY order_no LIMIT 200000;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-3  两阶段：物化 20 万个主键 + 逐个主键回表取 4 列（200000 行）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT t.id, t.order_no, t.amount, t.status
FROM (SELECT id FROM t50 ORDER BY order_no LIMIT 200000) k
JOIN t50 t ON t.id = k.id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-4  单阶段基线：ORDER BY order_no 取 4 列，LIMIT 2000000（覆盖索引）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT id, order_no, amount, status FROM (SELECT * FROM t50) src ORDER BY order_no LIMIT 2000000;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-5  两阶段：物化 200 万个主键 + 逐个主键回表取 4 列（2000000 行）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT t.id, t.order_no, t.amount, t.status
FROM (SELECT id FROM t50 ORDER BY order_no LIMIT 2000000) k
JOIN t50 t ON t.id = k.id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-6  分页形态：单条 SQL 排序取前 100000 行，但多取一列 fee（覆盖被破坏）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT id, order_no, amount, status, fee FROM (SELECT * FROM t50) src ORDER BY order_no LIMIT 100000;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-8  回表的下限：2000000 行按主键顺序读取（顺序读聚簇行，无排序、无往返）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT id, order_no, amount, status FROM t50 ORDER BY id LIMIT 2000000;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-9  回表的下限：全量 10000000 行按主键顺序读取（= 任何"取明细"方案的绝对下限）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT id, order_no, amount, status FROM t50 ORDER BY id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== LL-10 尾部锚点（本文件累计，不归因）===' AS block;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');
