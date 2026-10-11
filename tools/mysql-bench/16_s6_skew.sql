-- S6：数据倾斜 —— 同一份数据、三种切分方式
--   ① 按 tenant_id 值域等宽切 8 段（"范围分片"的直觉做法）
--   ② 按 MOD(tenant_id, 8) 哈希切 8 桶（均匀，但要建生成列桶才走索引）
--   ③ 先采样再定边界（每段行数近似相等，代价是要先算边界）
-- tenant_id 的分布是刻意做成重尾的：1 号 500 万行、2 号 200 万、3 号 100 万、4 号 50 万，其余 996 个各约 1500 行
USE bench;
SET SESSION sql_log_bin = 0;
ALTER TABLE t50 ADD INDEX idx_tenant (tenant_id);
ALTER TABLE t50 ADD COLUMN tenant_bucket TINYINT GENERATED ALWAYS AS (MOD(tenant_id, 8)) STORED,
  ADD INDEX idx_tenant_bucket (tenant_bucket);
ANALYZE TABLE t50;

SELECT '=== S6-0  tenant_id 分布（前 10 个最重的租户）===' AS block;
SELECT tenant_id, COUNT(*) AS rows_in_tenant FROM t50 GROUP BY tenant_id ORDER BY rows_in_tenant DESC LIMIT 10;

SELECT '=== S6-1  ① 值域等宽 8 段：每段行数 ===' AS block;
SELECT 'seg 0' AS seg, COUNT(*) AS rows_in_seg FROM t50 WHERE tenant_id > 0   AND tenant_id <= 125
UNION ALL SELECT 'seg 1', COUNT(*) FROM t50 WHERE tenant_id > 125 AND tenant_id <= 250
UNION ALL SELECT 'seg 2', COUNT(*) FROM t50 WHERE tenant_id > 250 AND tenant_id <= 375
UNION ALL SELECT 'seg 3', COUNT(*) FROM t50 WHERE tenant_id > 375 AND tenant_id <= 500
UNION ALL SELECT 'seg 4', COUNT(*) FROM t50 WHERE tenant_id > 500 AND tenant_id <= 625
UNION ALL SELECT 'seg 5', COUNT(*) FROM t50 WHERE tenant_id > 625 AND tenant_id <= 750
UNION ALL SELECT 'seg 6', COUNT(*) FROM t50 WHERE tenant_id > 750 AND tenant_id <= 875
UNION ALL SELECT 'seg 7', COUNT(*) FROM t50 WHERE tenant_id > 875 AND tenant_id <= 1000;

SELECT '=== S6-2  ① 值域等宽：每段取数耗时（真实形态 SELECT * + ORDER BY order_no）===' AS block;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src WHERE tenant_id > 0   AND tenant_id <= 125 ORDER BY order_no;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src WHERE tenant_id > 875 AND tenant_id <= 1000 ORDER BY order_no;

SELECT '=== S6-3  ② MOD(tenant_id, 8) 哈希桶：每桶行数 ===' AS block;
SELECT tenant_bucket, COUNT(*) AS rows_in_bucket FROM (SELECT tenant_bucket FROM t50) x GROUP BY tenant_bucket ORDER BY tenant_bucket;
SELECT MIN(c) AS min_rows, ROUND(AVG(c)) AS avg_rows, MAX(c) AS max_rows, ROUND(MAX(c)/AVG(c), 3) AS max_over_avg
FROM (SELECT COUNT(*) AS c FROM t50 GROUP BY tenant_bucket) y;

SELECT '=== S6-4  ② 哈希桶：单桶取数耗时（与最重的那段对照）===' AS block;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM t50) src WHERE tenant_bucket = 0 ORDER BY order_no;

SELECT '=== S6-5  ③ 采样定边界：8 分位点（按 tenant_id 有序取 OFFSET）===' AS block;
SELECT tenant_id AS b1 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 1249999;
SELECT tenant_id AS b2 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 2499999;
SELECT tenant_id AS b3 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 3749999;
SELECT tenant_id AS b4 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 4999999;
SELECT tenant_id AS b5 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 6249999;
SELECT tenant_id AS b6 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 7499999;
SELECT tenant_id AS b7 FROM t50 ORDER BY tenant_id, id LIMIT 1 OFFSET 8749999;

SELECT '=== S6-6  ③ 采样边界下每段行数（应各约 125 万）===' AS block;
SELECT 'bucket by b1..b7' AS note;
SELECT COUNT(*) AS rows_left_of_b1 FROM t50 WHERE tenant_id <= 1;
