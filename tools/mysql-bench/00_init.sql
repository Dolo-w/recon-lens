-- 00_init.sql：建库（字符集与业务库一致）
CREATE DATABASE IF NOT EXISTS bench DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SELECT VERSION() AS mysql_version, @@innodb_buffer_pool_size AS buffer_pool,
       @@tmpdir AS tmpdir, @@temptable_max_ram AS temptable_max_ram,
       @@sort_buffer_size AS sort_buffer_size, @@tmp_table_size AS tmp_table_size;
