-- 26_prereq_model.sql：把"对账前的预处理"物化成模型表，值不值？
--
-- 场景：对账规则要按维度汇总（引擎现有 dimension + measures 形态，生成的 SQL 是
--   SELECT <keyExpr> AS __biz_key, <measures> FROM (<inner>) src GROUP BY <keyExpr> ORDER BY __biz_key
-- ）。这股活现在每一轮对账都要重付一遍。本文件把它**提前做一次**，落成一张窄的模型表
-- （业务键主键 + 聚簇 + 分片生成列索引），再测后续每一轮读这张表要多久。
--
-- 归因说明：M1/M2/M6/M7 是 DDL（CTAS / ALTER），块内没有 EXPLAIN ANALYZE，所以用
-- SET @t0 := NOW(6) ... SELECT TIMESTAMPDIFF(...) 自带计时；两边的 SHOW GLOBAL STATUS
-- 之间仍然只有一条语句，计数器差值可归因。其余块都是单条 EXPLAIN ANALYZE。
--
-- 用法：
--   tools/mysql-bench/run.ps1 -File 26_prereq_model.sql -Tag 26_model
USE bench;
SET SESSION sql_log_bin = 0;

SELECT '=== M0 基线锚点 ===' AS block;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

-- ===== 现状：每一轮对账都要在千万行宽表上重做一次汇总 + 排序（这就等于"每轮的成本"）=====
SELECT '=== M5 现状每轮：原始宽表上 GROUP BY 维度 + ORDER BY 业务键（引擎现在生成的 SQL）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT CONCAT(customer_id, '|', status) AS biz_key, SUM(amount) AS amount, COUNT(*) AS row_cnt
FROM (SELECT * FROM t50) src
GROUP BY CONCAT(customer_id, '|', status) ORDER BY biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

-- ===== 预处理：把汇总结果物化一次（G≈5 万行）=====
SELECT '=== M1 预处理：CTAS 建造模型表 t50_model（G≈5 万）===' AS block;
SET @t0 := NOW(6);
FLUSH STATUS;
DROP TABLE IF EXISTS t50_model;
CREATE TABLE t50_model AS
SELECT CONCAT(customer_id, '|', status) AS biz_key,
       SUM(amount)                     AS amount,
       COUNT(*)                        AS row_cnt,
       MAX(updated_at)                 AS last_update
FROM t50
GROUP BY CONCAT(customer_id, '|', status);
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000 AS m1_ms;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== M2 预处理：给模型表加主键（聚簇）+ 分片生成列与索引 ===' AS block;
SET @t0 := NOW(6);
FLUSH STATUS;
ALTER TABLE t50_model
  MODIFY biz_key VARCHAR(48) NOT NULL,
  ADD PRIMARY KEY (biz_key),
  ADD COLUMN shard_bucket TINYINT GENERATED ALWAYS AS (CRC32(biz_key) % 8) STORED,
  ADD KEY idx_model_shard (shard_bucket);
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000 AS m2_ms;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

-- ===== 每一轮对账：读模型表（引擎的分片包裹形态）=====
SELECT '=== M3 每轮：单分片读模型表（shard_bucket=3，引擎包裹形态）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT * FROM (SELECT biz_key, amount, row_cnt, shard_bucket FROM t50_model) src
WHERE shard_bucket = 3 ORDER BY biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== M4 每轮：不分片全量读模型表 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT biz_key, amount, row_cnt FROM t50_model ORDER BY biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

-- ===== 高基数维度（G≈100 万）对照：汇总粒度决定模型表行数，也决定收益大小 =====
SELECT '=== M6 预处理：CTAS 建造高基数模型表 t50_model_hi（G≈100 万）===' AS block;
SET @t0 := NOW(6);
FLUSH STATUS;
DROP TABLE IF EXISTS t50_model_hi;
CREATE TABLE t50_model_hi AS
SELECT CONCAT(acct_id, '|', status) AS biz_key,
       SUM(amount)                  AS amount,
       COUNT(*)                     AS row_cnt
FROM t50
GROUP BY CONCAT(acct_id, '|', status);
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000 AS m6_ms;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== M7 预处理：高基数模型表加主键 + 分片生成列与索引 ===' AS block;
SET @t0 := NOW(6);
FLUSH STATUS;
ALTER TABLE t50_model_hi
  MODIFY biz_key VARCHAR(48) NOT NULL,
  ADD PRIMARY KEY (biz_key),
  ADD COLUMN shard_bucket TINYINT GENERATED ALWAYS AS (CRC32(biz_key) % 8) STORED,
  ADD KEY idx_model_shard (shard_bucket);
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000 AS m7_ms;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== M8 每轮：单分片读高基数模型表（shard_bucket=3）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT * FROM (SELECT biz_key, amount, row_cnt, shard_bucket FROM t50_model_hi) src
WHERE shard_bucket = 3 ORDER BY biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables','Sort_merge_passes','Sort_rows','Sort_scan','Handler_read_rnd','Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');

-- ===== 规模对照：模型表 vs 原始宽表 =====
SELECT '=== M9 规模对照（行数 / 数据 / 索引 MB）===' AS block;
SELECT TABLE_NAME, TABLE_ROWS,
       ROUND(DATA_LENGTH / 1048576) AS data_mb,
       ROUND(INDEX_LENGTH / 1048576) AS index_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'bench' AND TABLE_NAME IN ('t50', 't50_model', 't50_model_hi');
SELECT (SELECT COUNT(*) FROM t50)          AS t50_rows,
       (SELECT COUNT(*) FROM t50_model)    AS model_rows,
       (SELECT COUNT(*) FROM t50_model_hi) AS model_hi_rows;
