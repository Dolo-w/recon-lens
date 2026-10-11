-- S5：分区 —— 分区裁剪能不能让排序成本消失？
-- 造一张与 t50 同数据的分区表 t50p（按 biz_date 月分区，24 个分区 + pmax），再逐条对照
-- 注意：分区键必须进主键，所以 t50p 的主键是 (id, biz_date)
USE bench;
SET SESSION sql_log_bin = 0;

DROP TABLE IF EXISTS t50p;
CREATE TABLE t50p (
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
  c1  INT NOT NULL, c2 INT NOT NULL, c3 INT NOT NULL, c4 BIGINT NOT NULL, c5 BIGINT NOT NULL,
  c6  DECIMAL(12,2) NOT NULL, c7 DECIMAL(12,2) NOT NULL, c8 DECIMAL(12,4) NOT NULL,
  c9  DECIMAL(12,4) NOT NULL, c10 DECIMAL(12,2) NOT NULL,
  c11 VARCHAR(16) NOT NULL, c12 VARCHAR(16) NOT NULL, c13 VARCHAR(24) NOT NULL,
  c14 VARCHAR(24) NOT NULL, c15 VARCHAR(32) NOT NULL,
  c16 TINYINT NOT NULL, c17 TINYINT NOT NULL, c18 SMALLINT NOT NULL,
  c19 DATE NOT NULL, c20 DATETIME NOT NULL,
  PRIMARY KEY (id, biz_date),
  INDEX idx_p_order_no (order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
PARTITION BY RANGE (TO_DAYS(biz_date)) (
  PARTITION p2024_01 VALUES LESS THAN (TO_DAYS('2024-02-01')),
  PARTITION p2024_02 VALUES LESS THAN (TO_DAYS('2024-03-01')),
  PARTITION p2024_03 VALUES LESS THAN (TO_DAYS('2024-04-01')),
  PARTITION p2024_04 VALUES LESS THAN (TO_DAYS('2024-05-01')),
  PARTITION p2024_05 VALUES LESS THAN (TO_DAYS('2024-06-01')),
  PARTITION p2024_06 VALUES LESS THAN (TO_DAYS('2024-07-01')),
  PARTITION p2024_07 VALUES LESS THAN (TO_DAYS('2024-08-01')),
  PARTITION p2024_08 VALUES LESS THAN (TO_DAYS('2024-09-01')),
  PARTITION p2024_09 VALUES LESS THAN (TO_DAYS('2024-10-01')),
  PARTITION p2024_10 VALUES LESS THAN (TO_DAYS('2024-11-01')),
  PARTITION p2024_11 VALUES LESS THAN (TO_DAYS('2024-12-01')),
  PARTITION p2024_12 VALUES LESS THAN (TO_DAYS('2025-01-01')),
  PARTITION p2025_01 VALUES LESS THAN (TO_DAYS('2025-02-01')),
  PARTITION p2025_02 VALUES LESS THAN (TO_DAYS('2025-03-01')),
  PARTITION p2025_03 VALUES LESS THAN (TO_DAYS('2025-04-01')),
  PARTITION p2025_04 VALUES LESS THAN (TO_DAYS('2025-05-01')),
  PARTITION p2025_05 VALUES LESS THAN (TO_DAYS('2025-06-01')),
  PARTITION p2025_06 VALUES LESS THAN (TO_DAYS('2025-07-01')),
  PARTITION p2025_07 VALUES LESS THAN (TO_DAYS('2025-08-01')),
  PARTITION p2025_08 VALUES LESS THAN (TO_DAYS('2025-09-01')),
  PARTITION p2025_09 VALUES LESS THAN (TO_DAYS('2025-10-01')),
  PARTITION p2025_10 VALUES LESS THAN (TO_DAYS('2025-11-01')),
  PARTITION p2025_11 VALUES LESS THAN (TO_DAYS('2025-12-01')),
  PARTITION p2025_12 VALUES LESS THAN (TO_DAYS('2026-01-01')),
  PARTITION pmax VALUES LESS THAN (MAXVALUE)
);

INSERT INTO t50p SELECT NULL, v.* FROM v50 v WHERE v.order_no <= 'ORD000002500000';
INSERT INTO t50p SELECT NULL, v.* FROM v50 v WHERE v.order_no >  'ORD000002500000' AND v.order_no <= 'ORD000005000000';
INSERT INTO t50p SELECT NULL, v.* FROM v50 v WHERE v.order_no >  'ORD000005000000' AND v.order_no <= 'ORD000007500000';
INSERT INTO t50p SELECT NULL, v.* FROM v50 v WHERE v.order_no >  'ORD000007500000';
ANALYZE TABLE t50p;

SELECT '=== S5-0  分区行数（月度分布本身就是倾斜的：越近期越多）===' AS block;
SELECT PARTITION_NAME, TABLE_ROWS
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = 'bench' AND TABLE_NAME = 't50p' AND PARTITION_NAME IS NOT NULL
ORDER BY PARTITION_ORDINAL_POSITION;

SELECT '=== S5-1  裁剪到单月 + ORDER BY order_no（期望：单分区内索引有序，无 filesort）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50p
WHERE biz_date >= '2025-11-01' AND biz_date < '2025-12-01' ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S5-2  不裁剪：全分区 UNION 式 ORDER BY order_no（关键问题：还排不排？）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50p ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S5-3  不裁剪：ORDER BY id（各分区内部物理有序，能不能直接顺序吐？）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50p ORDER BY id;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S5-4  不裁剪：ORDER BY biz_date（分区键有序）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50p ORDER BY biz_date;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S5-5  裁剪到单月 + 聚合口径（表达式键）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT CONCAT(customer_id, '|', status) AS __biz_key, SUM(amount) AS amount, COUNT(*) AS cnt
FROM t50p WHERE biz_date >= '2025-11-01' AND biz_date < '2025-12-01'
GROUP BY CONCAT(customer_id, '|', status) ORDER BY __biz_key;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S5-6  对照：同一"单月 + ORDER BY order_no"在非分区表 t50 上 ===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT * FROM t50
WHERE biz_date >= '2025-11-01' AND biz_date < '2025-12-01' ORDER BY order_no;
SHOW GLOBAL STATUS WHERE Variable_name IN ('Created_tmp_disk_tables','Sort_merge_passes','Sort_rows','Handler_read_rnd','Handler_read_key','Handler_read_next','Innodb_rows_read');

SELECT '=== S5-7  对照：非分区表上同样的"单月"谓词没有索引（biz_date 无索引，全表扫）===' AS block;
FLUSH STATUS;
EXPLAIN ANALYZE SELECT COUNT(*) FROM t50 WHERE biz_date >= '2025-11-01' AND biz_date < '2025-12-01';
SHOW GLOBAL STATUS WHERE Variable_name IN ('Handler_read_rnd_next','Handler_read_key','Handler_read_next','Innodb_rows_read');
