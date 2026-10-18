-- 性能基准数据集：每侧 30 万行，按固定比例埋入四类差异（可复现）
USE recon_agent_verify;

DROP TABLE IF EXISTS perf_left;
DROP TABLE IF EXISTS perf_right;

CREATE TABLE perf_left (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  order_no    VARCHAR(32)   NOT NULL,
  customer_id VARCHAR(32)   NOT NULL,
  amount      DECIMAL(18,2) NOT NULL,
  status      VARCHAR(16)   NOT NULL,
  updated_at  DATETIME(3)   NOT NULL,
  PRIMARY KEY (id),
  KEY idx_perf_left_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE perf_right LIKE perf_left;
ALTER TABLE perf_right ADD KEY idx_perf_right_order (order_no);

SET SESSION cte_max_recursion_depth = 400000;

INSERT INTO perf_left (order_no, customer_id, amount, status, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 300000)
SELECT CONCAT('ORD', 20260000 + n),
       CONCAT('C', LPAD(MOD(n, 5000), 5, '0')),
       ROUND(100 + MOD(n * 37, 5000) + MOD(n, 100) / 100, 2),
       IF(MOD(n, 7) = 0, 'UNPAID', 'PAID'),
       TIMESTAMPADD(SECOND, n, '2026-10-01 00:00:00')
FROM seq;

-- 右侧：先整体复制，再按取模比例制造差异
INSERT INTO perf_right (order_no, customer_id, amount, status, updated_at)
SELECT order_no, customer_id,
       -- 每 10000 条一个金额差异（右侧多 3.00）
       IF(MOD(id, 10000) = 3, amount + 3.00, amount),
       -- 每 15000 条一个状态差异
       IF(MOD(id, 15000) = 4, IF(status = 'PAID', 'UNPAID', 'PAID'), status),
       updated_at
FROM perf_left
-- 每 20000 条漏一条（右侧缺失）
WHERE MOD(id, 20000) <> 5;

-- 每 25000 条多一条（左侧缺失）
INSERT INTO perf_right (order_no, customer_id, amount, status, updated_at)
SELECT CONCAT(order_no, 'X'), customer_id, amount, status, updated_at
FROM perf_left WHERE MOD(id, 25000) = 6;

-- 重复键：把 3 条订单各复制一次（右侧重复）
INSERT INTO perf_right (order_no, customer_id, amount, status, updated_at)
SELECT order_no, customer_id, amount, status, updated_at
FROM perf_left WHERE id IN (11, 22, 33);

SELECT 'perf_left' AS t, COUNT(*) AS rows_count FROM perf_left
UNION ALL SELECT 'perf_right', COUNT(*) FROM perf_right;
