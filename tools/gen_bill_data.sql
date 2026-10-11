-- ============================================================================
-- recon-lens 造数与压测脚本（MySQL 8）
--   mysql -uroot -p recon_lens < tools/gen_bill_data.sql
-- 生成两张百万级表 bill_left / bill_right，并按比例埋入四类差异。
-- 差异分布由取模条件精确控制（每 1 万条 1 个），可以按需改比例。
-- ============================================================================

-- 序号表：1..1000000 的连续整数，两张表都由它派生，保证可复现
DROP TABLE IF EXISTS gen_numbers;
CREATE TABLE gen_numbers (n BIGINT PRIMARY KEY);

SET SESSION cte_max_recursion_depth = 2000000;
INSERT INTO gen_numbers (n)
WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000000
)
SELECT n FROM seq;

-- ---------------------------------------------------------------- 左侧（账单）
DROP TABLE IF EXISTS bill_left;
CREATE TABLE bill_left (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    n           BIGINT        NOT NULL COMMENT '造数序号，可用作分片依据',
    order_no    VARCHAR(32)   NOT NULL,
    customer_id VARCHAR(32)   NOT NULL,
    amount      DECIMAL(18,2) NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_left_order (order_no),
    KEY idx_left_n (n)
) ENGINE=InnoDB;

-- order_no 定宽零填充：字典序与数值序一致，这是归并式对账的前提
INSERT INTO bill_left (n, order_no, customer_id, amount, status, updated_at)
SELECT n,
       CONCAT('ORD', LPAD(n, 10, '0')),
       CONCAT('C', LPAD(n % 5000, 4, '0')),
       ROUND(100 + (n % 9000) / 100, 2),
       IF(n % 11 = 0, 'UNPAID', 'PAID'),
       NOW(3)
FROM gen_numbers;

-- ---------------------------------------------------------------- 右侧（结算）
DROP TABLE IF EXISTS bill_right;
CREATE TABLE bill_right (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    n           BIGINT        NOT NULL,
    order_no    VARCHAR(32)   NOT NULL,
    customer_id VARCHAR(32)   NOT NULL,
    amount      DECIMAL(18,2) NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    updated_at  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_right_order (order_no),
    KEY idx_right_n (n)
) ENGINE=InnoDB;

-- 主体：与左侧一一对应，但
--   n % 10000 = 0  → 右侧整条缺失     → MISSING_RIGHT（100 条）
--   n % 2000  = 0  → 金额各减 1 元     → AMOUNT_MISMATCH（500 条）
--   n % 5000  = 0  → 状态取反          → STATUS_MISMATCH（200 条）
INSERT INTO bill_right (n, order_no, customer_id, amount, status, updated_at)
SELECT n,
       order_no,
       customer_id,
       IF(n % 2000 = 0, amount - 1, amount),
       IF(n % 5000 = 0, IF(status = 'PAID', 'UNPAID', 'PAID'), status),
       NOW(3)
FROM bill_left
WHERE MOD(n, 10000) <> 0;

-- 重复入账：n % 10000 = 1 的键再插一条 → DUPLICATE_KEY（100 条）
INSERT INTO bill_right (n, order_no, customer_id, amount, status, updated_at)
SELECT n, order_no, customer_id, amount, status, NOW(3)
FROM bill_left
WHERE MOD(n, 10000) = 1;

-- 对方多推：n % 10000 = 2 的键给一批右侧独有编号 → MISSING_LEFT（100 条）
INSERT INTO bill_right (n, order_no, customer_id, amount, status, updated_at)
SELECT 2000000 + n,
       CONCAT('ORD', LPAD(2000000 + n, 10, '0')),
       CONCAT('C', LPAD(n % 5000, 4, '0')),
       ROUND(100 + (n % 9000) / 100, 2),
       'PAID',
       NOW(3)
FROM gen_numbers
WHERE MOD(n, 10000) = 2;

-- ---------------------------------------------------------------- 自检
SELECT 'bill_left' AS t, COUNT(*) AS rows_, COUNT(DISTINCT order_no) AS keys_ FROM bill_left
UNION ALL
SELECT 'bill_right', COUNT(*), COUNT(DISTINCT order_no) FROM bill_right;

-- 差异预期（用规则 BILL_VS_SETTLE 跑一遍后应与此吻合）
--   VALUE_MISMATCH(amount)  500 条   （其中与状态取反、重复重叠的部分仍算一次 amount 差异）
--   VALUE_MISMATCH(status)  200 条
--   MISSING_RIGHT           100 条
--   MISSING_LEFT            100 条
--   DUPLICATE_KEY           100 条
SELECT 'expected' AS kind, 'AMOUNT_MISMATCH≈500 / STATUS_MISMATCH≈200 / MISSING_RIGHT=100 / MISSING_LEFT=100 / DUPLICATE_KEY=100' AS detail;

-- ---------------------------------------------------------------- 压测
-- 关键观察三项：
--   1) 完成时间与分片并行度是否接近线性
--   2) JVM 堆峰值是否与数据量解耦：用 -Xmx256m 跑完百万级，说明流式游标 + 归并确实没有整表进内存
--      （IDE 里可直接改 VM options；命令行：mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx256m"）
--   3) recon_diff 的差异数与上面的取模比例是否吻合
--
-- TRUNCATE TABLE recon_diff; TRUNCATE TABLE recon_shard; TRUNCATE TABLE recon_job;
-- curl -X POST http://localhost:8080/api/jobs -H 'Content-Type: application/json' \
--      -d '{"ruleCode":"BILL_VS_SETTLE","batchId":"bench-1m","sync":true}'
--
-- 先用 shard.count=8 跑一次，再改成 1 跑一次做对照；两次的差异总数必须完全一致（分片只影响耗时，不影响结果）。
