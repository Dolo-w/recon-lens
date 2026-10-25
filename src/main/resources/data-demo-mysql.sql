-- 演示数据（MySQL 版）：只在联调/演示时手工执行，生产不跑。
-- 由 data.sql（H2 的 MERGE）转写：同一份数据重复执行不会撞主键。

INSERT INTO bill_left (id, order_no, customer_id, amount, status, updated_at) VALUES(1,  'ORD20260001', 'C001', 1200.50, 'PAID',   '2026-10-01 10:00:00'),
 (2,  'ORD20260002', 'C001',  800.00, 'PAID',   '2026-10-01 10:01:00'),
 (3,  'ORD20260003', 'C002', 1500.00, 'PAID',   '2026-10-01 10:02:00'),
 (4,  'ORD20260004', 'C003', 2000.00, 'PAID',   '2026-10-01 10:03:00'),
 (5,  'ORD20260005', 'C004',  300.00, 'UNPAID', '2026-10-01 10:04:00'),
 (6,  'ORD20260006', 'C005',  950.25, 'PAID',   '2026-10-01 10:05:00'),
 (7,  'ORD20260007', 'C006',  660.00, 'PAID',   '2026-10-01 10:06:00'),
 (8,  'ORD20260008', 'C007',  430.00, 'PAID',   '2026-10-01 10:07:00'),
 (9,  'ORD20260006', 'C005',  950.25, 'PAID',   '2026-10-01 10:08:00'),
 (10, 'ORD20260009', 'C008',  780.00, 'PAID',   '2026-10-01 10:09:00')
  ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), customer_id = VALUES(customer_id), amount = VALUES(amount), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO bill_right (id, order_no, customer_id, amount, status, updated_at) VALUES(1,  'ORD20260001', 'C001', 1200.50, 'PAID',   '2026-10-01 10:00:05'),
 (2,  'ORD20260002', 'C001',  800.00, 'PAID',   '2026-10-01 10:01:05'),
 (3,  'ORD20260003', 'C002', 1500.30, 'PAID',   '2026-10-01 10:02:05'),
 (4,  'ORD20260004', 'C003', 2000.10, 'PAID',   '2026-10-01 10:03:05'),
 (5,  'ORD20260005', 'C004',  300.00, 'UNPAID', '2026-10-01 10:04:05'),
 (6,  'ORD20260006', 'C005',  950.25, 'PAID',   '2026-10-01 10:05:05'),
 (7,  'ORD20260007', 'C006',  660.00, 'PAID',   '2026-10-01 10:06:05'),
 (8,  'ORD20260008', 'C007',  430.00, 'REFUNDED', '2026-10-01 10:07:05'),
 (9,  'ORD20260006', 'C005',  950.25, 'PAID',   '2026-10-01 10:08:05'),
 (10, 'ORD20260011', 'C010',  500.00, 'PAID',   '2026-10-01 10:11:05')
  ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), customer_id = VALUES(customer_id), amount = VALUES(amount), status = VALUES(status), updated_at = VALUES(updated_at);
