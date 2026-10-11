-- 90_probe_diffs.sql：把 job#7（S7_NARROW 单分片）的差异逐条摊开，对照源表真值，判断引擎分类是否正确
USE bench;

SELECT '=== 1. 差异按类型/分片 ===' AS section;
SELECT diff_type, COUNT(*) AS cnt, MIN(shard_id) AS shard_min, MAX(shard_id) AS shard_max
FROM recon_diff WHERE job_id = 7 GROUP BY diff_type ORDER BY cnt DESC;

SELECT '=== 2. STATUS_MISMATCH 样例（biz_key / 两侧值）===' AS section;
SELECT biz_key, field_name, left_value, right_value, shard_id
FROM recon_diff WHERE job_id = 7 AND diff_type = 'STATUS_MISMATCH' ORDER BY biz_key LIMIT 6;

SELECT '=== 3. DUPLICATE_KEY 全部 ===' AS section;
SELECT biz_key, field_name, left_value, right_value, shard_id
FROM recon_diff WHERE job_id = 7 AND diff_type = 'DUPLICATE_KEY' ORDER BY biz_key LIMIT 6;

SELECT '=== 4. MISSING 两侧各取 3 条 ===' AS section;
SELECT diff_type, biz_key, field_name, left_value, right_value
FROM recon_diff WHERE job_id = 7 AND diff_type LIKE 'MISSING%' ORDER BY diff_type, biz_key LIMIT 6;

SELECT '=== 5. STATUS_MISMATCH 的 biz_key 分布（是否集中在改写区）===' AS section;
SELECT MIN(biz_key) AS k_min, MAX(biz_key) AS k_max, COUNT(*) AS cnt
FROM recon_diff WHERE job_id = 7 AND diff_type = 'STATUS_MISMATCH';
SELECT MIN(biz_key) AS k_min, MAX(biz_key) AS k_max, COUNT(*) AS cnt
FROM recon_diff WHERE job_id = 7 AND diff_type = 'MISSING_RIGHT';

SELECT '=== 6. 源表真值：两侧 order_no 交集口径 ===' AS section;
SELECT (SELECT COUNT(*) FROM bill_left) AS left_rows,
       (SELECT COUNT(*) FROM bill_right) AS right_rows,
       (SELECT COUNT(*) FROM bill_right WHERE id % 10000 = 0) AS planted_amount,
       (SELECT COUNT(*) FROM bill_left l JOIN bill_right r ON r.order_no = l.order_no) AS join_pairs,
       (SELECT COUNT(*) FROM bill_left l LEFT JOIN bill_right r ON r.order_no = l.order_no WHERE r.id IS NULL) AS only_left,
       (SELECT COUNT(*) FROM bill_right r LEFT JOIN bill_left l ON l.order_no = r.order_no WHERE l.id IS NULL) AS only_right;

SELECT '=== 7. 同 id 两侧值对比（真实差异面）===' AS section;
SELECT (SELECT COUNT(*) FROM bill_left l JOIN bill_right r ON r.id = l.id WHERE l.status <> r.status) AS status_diff_same_id,
       (SELECT COUNT(*) FROM bill_left l JOIN bill_right r ON r.id = l.id WHERE ABS(l.amount - r.amount) > 0.01) AS amount_diff_same_id;

SELECT '=== 8. 改写区实拍（id 1700000..1700003）===' AS section;
SELECT 'L' AS side, id, order_no, status, amount FROM bill_left  WHERE id BETWEEN 1700000 AND 1700003
UNION ALL
SELECT 'R' AS side, id, order_no, status, amount FROM bill_right WHERE id BETWEEN 1700000 AND 1700003
ORDER BY order_no, side, id;
