-- S6-6（补）：把「采样 8 分位边界」真实落地
-- S6-5 采样得到的边界是 b1=1, b2=1, b3=1, b4=2, b5=2, b6=3, b7=170（tenant_id 的重尾分布）
-- 本场景只回答一个问题：这 7 个边界切出来的 8 段，各自有多少行？以及最重的一段取数+排序要多久？
USE bench;

SELECT '=== S6-6a  采样边界（b1..b7）分段后的真实行数 ===' AS block;

DROP TABLE IF EXISTS s6b_seg;
CREATE TABLE s6b_seg (seg VARCHAR(24), lo BIGINT, hi BIGINT, row_cnt BIGINT);
INSERT INTO s6b_seg
SELECT 'seg1 (-inf,1]',   NULL, 1,   COUNT(*) FROM t50 WHERE tenant_id <= 1;
INSERT INTO s6b_seg
SELECT 'seg2 (1,1]',      1,    1,   COUNT(*) FROM t50 WHERE tenant_id > 1  AND tenant_id <= 1;
INSERT INTO s6b_seg
SELECT 'seg3 (1,1]',      1,    1,   COUNT(*) FROM t50 WHERE tenant_id > 1  AND tenant_id <= 1;
INSERT INTO s6b_seg
SELECT 'seg4 (1,2]',      1,    2,   COUNT(*) FROM t50 WHERE tenant_id > 1  AND tenant_id <= 2;
INSERT INTO s6b_seg
SELECT 'seg5 (2,2]',      2,    2,   COUNT(*) FROM t50 WHERE tenant_id > 2  AND tenant_id <= 2;
INSERT INTO s6b_seg
SELECT 'seg6 (2,3]',      2,    3,   COUNT(*) FROM t50 WHERE tenant_id > 2  AND tenant_id <= 3;
INSERT INTO s6b_seg
SELECT 'seg7 (3,170]',    3,    170, COUNT(*) FROM t50 WHERE tenant_id > 3  AND tenant_id <= 170;
INSERT INTO s6b_seg
SELECT 'seg8 (170,+inf)', 170,  NULL,COUNT(*) FROM t50 WHERE tenant_id > 170;

SELECT seg, lo, hi, row_cnt FROM s6b_seg;
SELECT MIN(row_cnt) AS min_rows, MAX(row_cnt) AS max_rows, ROUND(AVG(row_cnt)) AS avg_rows,
       ROUND(MAX(row_cnt) / NULLIF(AVG(row_cnt), 0), 2) AS max_over_avg,
       SUM(row_cnt = 0) AS empty_segments,
       MAX(row_cnt) - MIN(row_cnt) AS spread
FROM s6b_seg;

SELECT '=== S6-6b  最重的一段（seg1，5M 行）取数 + 排序耗时 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE
SELECT id, order_no, amount, status
FROM t50
WHERE tenant_id <= 1
ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN
  ('Innodb_rows_read','Sort_rows','Sort_scan','Sort_range','Sort_merge_passes','Created_tmp_files');

SELECT '=== S6-6c  对照：等行数的理想分段不可能用采样得到 ===' AS block;
SELECT
  (SELECT COUNT(*) FROM t50 WHERE tenant_id <= 1)                                   AS sampled_seg1,
  (SELECT COUNT(*) FROM t50 WHERE tenant_id BETWEEN 2 AND 2)                        AS sampled_seg4,
  (SELECT COUNT(*) FROM t50 WHERE tenant_id > 3 AND tenant_id <= 170)               AS sampled_seg7,
  (SELECT COUNT(*) FROM t50 WHERE tenant_id > 170)                                  AS sampled_seg8;
