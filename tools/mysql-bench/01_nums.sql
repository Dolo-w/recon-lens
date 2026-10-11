-- 01_nums.sql：造一张 1000 万行的数字表（后续所有宽表都从它派生）
USE bench;
SET SESSION sql_log_bin = 0;
SET SESSION unique_checks = 0;

DROP TABLE IF EXISTS d;
CREATE TABLE d (v TINYINT NOT NULL PRIMARY KEY) ENGINE = InnoDB;
INSERT INTO d VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

DROP TABLE IF EXISTS nums;
CREATE TABLE nums (n INT NOT NULL PRIMARY KEY) ENGINE = InnoDB;
-- 10^7 行：7 张 10 行小表做交叉连接
INSERT INTO nums (n)
SELECT a.v + b.v*10 + c.v*100 + e.v*1000 + f.v*10000 + g.v*100000 + h.v*1000000 + 1
FROM d a, d b, d c, d e, d f, d g, d h;

SELECT COUNT(*) AS nums_rows, MIN(n) AS min_n, MAX(n) AS max_n FROM nums;
ANALYZE TABLE nums;
