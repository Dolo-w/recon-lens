-- 02_t50.sql：1000 万行 × 50 个业务列的宽表（复刻对账取数里那张大表）
-- 口径说明（刻意做的分布设计，后面每个场景都会用到）：
--   order_no        = ORD + n 的 12 位零填充，全表唯一；SUBSTRING(order_no,10,2) 取到的是 n 的第 7、8 位十进制数字
--                     -> 演示规则的 MOD(CAST(SUBSTRING(order_no,10,2) AS INT), N) 在这张表上就是"按 n 的两位数字取模"
--   customer_id     5 万个不同值（聚合维度 G≈5 万）
--   acct_id         100 万个不同值（聚合维度 G≈100 万）
--   resource_id     1000 万个不同值（聚合维度 G≈N，等价明细）
--   tenant_id       极端重尾：1 号租户 500 万行、2 号 200 万、3 号 100 万、4 号 50 万，其余 996 个各约 1500 行
--                     -> 用来演示"按值域等宽切分片必然倾斜"
--   biz_date        730 天（约 24 个月），且越近期行越多（POW(n,0.6)）-> 用来演示按时间范围切片的倾斜
--   c19 / created_at 均匀分布的时间列，做对照组
USE bench;
SET SESSION sql_log_bin = 0;
SET SESSION unique_checks = 0;

DROP VIEW IF EXISTS v50;
DROP TABLE IF EXISTS t50;

CREATE TABLE t50 (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  order_no      VARCHAR(32)  NOT NULL,
  settle_no     VARCHAR(32)  NOT NULL,
  customer_id   VARCHAR(24)  NOT NULL,
  acct_id       VARCHAR(24)  NOT NULL,
  resource_id   VARCHAR(40)  NOT NULL,
  tenant_id     INT          NOT NULL,
  biz_date      DATE         NOT NULL,
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  amount        DECIMAL(18,2) NOT NULL,
  fee           DECIMAL(18,4) NOT NULL,
  tax           DECIMAL(18,2) NOT NULL,
  discount      DECIMAL(18,2) NOT NULL,
  paid_amount   DECIMAL(18,2) NOT NULL,
  refund_amount DECIMAL(18,2) NOT NULL,
  settle_amount DECIMAL(18,2) NOT NULL,
  exchange_rate DECIMAL(12,6) NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  settle_status VARCHAR(16)  NOT NULL,
  channel       VARCHAR(16)  NOT NULL,
  pay_channel   VARCHAR(16)  NOT NULL,
  region        VARCHAR(16)  NOT NULL,
  currency      CHAR(3)      NOT NULL,
  biz_type      VARCHAR(16)  NOT NULL,
  product_code  VARCHAR(24)  NOT NULL,
  operator      VARCHAR(24)  NOT NULL,
  remark        VARCHAR(64)  NOT NULL,
  ext_ref       VARCHAR(40)  NOT NULL,
  batch_no      VARCHAR(24)  NOT NULL,
  c1  INT          NOT NULL, c2  INT          NOT NULL, c3  INT          NOT NULL,
  c4  BIGINT       NOT NULL, c5  BIGINT       NOT NULL,
  c6  DECIMAL(12,2) NOT NULL, c7  DECIMAL(12,2) NOT NULL, c8  DECIMAL(12,4) NOT NULL,
  c9  DECIMAL(12,4) NOT NULL, c10 DECIMAL(12,2) NOT NULL,
  c11 VARCHAR(16)  NOT NULL, c12 VARCHAR(16)  NOT NULL, c13 VARCHAR(24)  NOT NULL,
  c14 VARCHAR(24)  NOT NULL, c15 VARCHAR(32)  NOT NULL,
  c16 TINYINT      NOT NULL, c17 TINYINT      NOT NULL, c18 SMALLINT     NOT NULL,
  c19 DATE         NOT NULL, c20 DATETIME     NOT NULL,
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 50 个业务列的取值映射（列顺序与 t50 去掉 id 后完全一致）
CREATE VIEW v50 AS
SELECT
  CONCAT('ORD', LPAD(n, 12, '0'))                                AS order_no,
  CONCAT('SET', LPAD(n, 12, '0'))                                AS settle_no,
  CONCAT('C', LPAD(n MOD 50000, 8, '0'))                         AS customer_id,
  CONCAT('A', LPAD(n MOD 1000000, 9, '0'))                       AS acct_id,
  CONCAT('R', LPAD(n MOD 10000000, 9, '0'))                      AS resource_id,
  CASE WHEN n < 5000000 THEN 1
       WHEN n < 7000000 THEN 2
       WHEN n < 8000000 THEN 3
       WHEN n < 8500000 THEN 4
       ELSE 5 + (n MOD 996) END                                  AS tenant_id,
  DATE_ADD('2024-01-01', INTERVAL FLOOR(730 * POW(n / 10000000, 0.6)) DAY) AS biz_date,
  DATE_ADD('2024-01-01 00:00:00', INTERVAL (n MOD 31536000) SECOND)          AS created_at,
  DATE_ADD('2024-01-01 00:00:00', INTERVAL ((n MOD 31536000) + 3600) SECOND) AS updated_at,
  ROUND((n MOD 500000) / 100.0, 2)                               AS amount,
  ROUND((n MOD 5000) / 100.0, 4)                                 AS fee,
  ROUND((n MOD 90000) / 100.0, 2)                                AS tax,
  ROUND((n MOD 20000) / 100.0, 2)                                AS discount,
  ROUND((n MOD 500000) / 100.0, 2)                               AS paid_amount,
  ROUND((n MOD 3000) / 100.0, 2)                                 AS refund_amount,
  ROUND((n MOD 499000) / 100.0, 2)                               AS settle_amount,
  ROUND(6.5 + (n MOD 1000) / 10000.0, 6)                         AS exchange_rate,
  ELT(1 + (n MOD 4), 'PAID', 'UNPAID', 'REFUND', 'CLOSED')       AS status,
  ELT(1 + (n MOD 3), 'SETTLED', 'PENDING', 'FAILED')             AS settle_status,
  ELT(1 + (n MOD 5), 'ALIPAY', 'WECHAT', 'UNIONPAY', 'BANK', 'CASH') AS channel,
  ELT(1 + ((n DIV 3) MOD 5), 'ALIPAY', 'WECHAT', 'UNIONPAY', 'BANK', 'CASH') AS pay_channel,
  ELT(1 + (n MOD 8), 'CN-BJ', 'CN-SH', 'CN-GZ', 'CN-SZ', 'CN-HZ', 'CN-CD', 'CN-WH', 'CN-XA') AS region,
  ELT(1 + (n MOD 3), 'CNY', 'USD', 'HKD')                        AS currency,
  ELT(1 + (n MOD 6), 'CONSUME', 'RECHARGE', 'REFUND', 'TRANSFER', 'WITHDRAW', 'ADJUST') AS biz_type,
  CONCAT('P', LPAD(n MOD 2000, 6, '0'))                          AS product_code,
  CONCAT('op', LPAD(n MOD 500, 5, '0'))                          AS operator,
  CONCAT('remark-', n MOD 100000)                                AS remark,
  CONCAT('EXT', LPAD(n MOD 9999999, 10, '0'))                    AS ext_ref,
  CONCAT('B', LPAD(n DIV 100000, 8, '0'))                        AS batch_no,
  n MOD 100000                                                   AS c1,
  n MOD 10000                                                    AS c2,
  n MOD 1000                                                     AS c3,
  n                                                              AS c4,
  n * 2                                                          AS c5,
  ROUND((n MOD 70000) / 100.0, 2)                                AS c6,
  ROUND((n MOD 80000) / 100.0, 2)                                AS c7,
  ROUND((n MOD 90000) / 10000.0, 4)                              AS c8,
  ROUND((n MOD 95000) / 10000.0, 4)                              AS c9,
  ROUND((n MOD 60000) / 100.0, 2)                                AS c10,
  CONCAT('x', LPAD(n MOD 100, 4, '0'))                           AS c11,
  CONCAT('y', LPAD(n MOD 200, 4, '0'))                           AS c12,
  CONCAT('z', LPAD(n MOD 300, 6, '0'))                           AS c13,
  CONCAT('w', LPAD(n MOD 400, 6, '0'))                           AS c14,
  CONCAT('v', LPAD(n MOD 500, 8, '0'))                           AS c15,
  n MOD 2                                                        AS c16,
  n MOD 4                                                        AS c17,
  n MOD 300                                                      AS c18,
  DATE_ADD('2023-01-01', INTERVAL (n MOD 400) DAY)               AS c19,
  DATE_ADD('2024-01-01 00:00:00', INTERVAL (n MOD 1000000) SECOND) AS c20
FROM nums;

-- 分 4 批灌入（每批 250 万行），任何一批失败都能看清进度
INSERT INTO t50 SELECT NULL, v.* FROM v50 v WHERE v.order_no <= 'ORD000002500000';
INSERT INTO t50 SELECT NULL, v.* FROM v50 v WHERE v.order_no >  'ORD000002500000' AND v.order_no <= 'ORD000005000000';
INSERT INTO t50 SELECT NULL, v.* FROM v50 v WHERE v.order_no >  'ORD000005000000' AND v.order_no <= 'ORD000007500000';
INSERT INTO t50 SELECT NULL, v.* FROM v50 v WHERE v.order_no >  'ORD000007500000';

ANALYZE TABLE t50;
SELECT COUNT(*) AS t50_rows, MIN(id) AS min_id, MAX(id) AS max_id,
       COUNT(DISTINCT customer_id) AS g_customer, COUNT(DISTINCT acct_id) AS g_acct,
       COUNT(DISTINCT resource_id) AS g_resource, COUNT(DISTINCT tenant_id) AS g_tenant
FROM t50;
SELECT TABLE_NAME, TABLE_ROWS,
       ROUND(DATA_LENGTH / 1048576) AS data_mb,
       ROUND(INDEX_LENGTH / 1048576) AS index_mb,
       ROUND(DATA_LENGTH / GREATEST(TABLE_ROWS, 1)) AS bytes_per_row
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'bench' AND TABLE_NAME IN ('t50', 'nums');
