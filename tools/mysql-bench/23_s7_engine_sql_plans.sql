-- S7-9：把引擎真正发出去的 SQL 原样拿来 EXPLAIN ANALYZE
-- 引擎的分片 SQL 形态是：SELECT * FROM (<规则里的 sql>) src WHERE <分片谓词> ORDER BY <keyField>
-- 目的：判明「函数化分片谓词」在 MySQL 8 上到底走不走索引，以及 CAST 类型的影响。
USE bench;

SELECT '=== S7-9a 函数谓词 AS UNSIGNED（与生成列 shard_bucket 定义一致）===' AS block;
EXPLAIN ANALYZE
SELECT * FROM (SELECT id, order_no, amount, status FROM bill_left) src
WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS UNSIGNED), 8) = 3 AND 8 = 8
ORDER BY order_no;

SELECT '=== S7-9b 函数谓词 AS SIGNED（类型与生成列定义不一致）===' AS block;
EXPLAIN ANALYZE
SELECT * FROM (SELECT id, order_no, amount, status FROM bill_left) src
WHERE MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), 8) = 3 AND 8 = 8
ORDER BY order_no;

SELECT '=== S7-9c 生成列桶谓词（BUCKET8 写法）===' AS block;
EXPLAIN ANALYZE
SELECT * FROM (SELECT id, order_no, amount, status, shard_bucket FROM bill_left) src
WHERE shard_bucket = 3 AND 8 = 8
ORDER BY order_no;

SELECT '=== S7-9d 分片谓词写法的三种形态对照（EXPLAIN，不执行）===' AS block;
EXPLAIN
SELECT * FROM (SELECT id, order_no, amount, status, shard_bucket FROM bill_left) src
WHERE shard_bucket = 3 ORDER BY order_no;
