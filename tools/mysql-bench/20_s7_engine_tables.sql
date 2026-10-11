-- S7：把 recon-lens 引擎真的接到这台 MySQL 上跑一次（先造两张 200 万行的宽表 + 埋已知差异）
-- 用 CTAS 而不是 LIKE：LIKE t50 会把 STORED 生成列也带过来，而插入值到生成列会被 MySQL 拒绝
-- （ERROR 3105）。CTAS 把生成列落成普通列，索引再按需要显式加，控制更清楚。
USE bench;

DROP TABLE IF EXISTS bill_left;
DROP TABLE IF EXISTS bill_right;
CREATE TABLE bill_left  AS SELECT * FROM t50 WHERE 1 = 0;
CREATE TABLE bill_right AS SELECT * FROM t50 WHERE 1 = 0;

INSERT INTO bill_left  SELECT * FROM t50 WHERE id <= 2000000;
INSERT INTO bill_right SELECT * FROM t50 WHERE id <= 2000000;

-- 差异 1：右侧每 1 万行金额多 1 元 → 期望 200 条字段级差异
UPDATE bill_right SET amount = amount + 1.00 WHERE id % 10000 = 0;
-- 差异 2：右侧整行缺失 100 行（左有右无 → MISSING_LEFT）
DELETE FROM bill_right WHERE id BETWEEN 1900001 AND 1900100;
-- 差异 3：左侧整行缺失 100 行（右有左无 → MISSING_RIGHT）
DELETE FROM bill_left  WHERE id BETWEEN 1800001 AND 1800100;
-- 差异 4：右侧 100 个业务键重复（把 order_no 改成上一行的，观察 DUPLICATE_KEY）
UPDATE bill_right r JOIN t50 t ON t.id = r.id - 1
SET r.order_no = t.order_no
WHERE r.id BETWEEN 1700001 AND 1700100;

ALTER TABLE bill_left  ADD PRIMARY KEY (id);
ALTER TABLE bill_right ADD PRIMARY KEY (id);
-- 业务键索引（真实表一定有）+ 参与对账的三列做覆盖索引（窄口径规则用得上）
ALTER TABLE bill_left  ADD INDEX idx_order_no (order_no), ADD INDEX idx_s7_cover (order_no, amount, status), ADD INDEX idx_shard_bucket (shard_bucket);
ALTER TABLE bill_right ADD INDEX idx_order_no (order_no), ADD INDEX idx_s7_cover (order_no, amount, status), ADD INDEX idx_shard_bucket (shard_bucket);
ANALYZE TABLE bill_left, bill_right;

SELECT '=== S7-0  两侧行数与埋点核对 ===' AS block;
SELECT (SELECT COUNT(*) FROM bill_left)  AS left_rows,
       (SELECT COUNT(*) FROM bill_right) AS right_rows,
       (SELECT COUNT(*) FROM bill_right WHERE id % 10000 = 0) AS amount_diffs,
       (SELECT COUNT(*) FROM bill_right r JOIN bill_left l ON l.order_no = r.order_no) AS matched_join;

SELECT '=== S7-1  窄口径取数（覆盖索引）===' AS block;
EXPLAIN ANALYZE SELECT * FROM (SELECT id, order_no, amount, status FROM bill_left) src ORDER BY order_no;

SELECT '=== S7-2  宽口径取数（54 列全量，参照物）===' AS block;
EXPLAIN ANALYZE SELECT * FROM (SELECT * FROM bill_left) src ORDER BY order_no;

SELECT '=== S7-3  分片谓词对照：函数谓词（AS UNSIGNED，能被生成列替换）vs 桶列 ===' AS block;
EXPLAIN ANALYZE SELECT COUNT(*) FROM bill_left WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 0;
EXPLAIN ANALYZE SELECT COUNT(*) FROM bill_left WHERE shard_bucket = 0;
EXPLAIN ANALYZE SELECT COUNT(*) FROM bill_left WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), 8) = 0;
