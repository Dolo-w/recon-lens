#!/usr/bin/env node
/**
 * 并行 LOAD DATA：把 recon-data 的分片 CSV 并行灌进 MySQL。
 *
 * 相比串行灌 16 片（实测 ~15 分钟）的两个提速点：
 * 1. **并行**：N 个 mysql 客户端同时灌不同分片（默认并发 4）；
 * 2. **先灌数后建索引**：建表 DDL 不带二级索引（idx_merchant），灌完再 ALTER TABLE ADD INDEX ——
 *    逐行维护二级索引是 LOAD DATA 最大的写入热点，先灌后建是一次性排序构建，快几倍。
 *
 * 用法：
 *   node tools/parallel-load.mjs <dataDir> [host] [port] [db] [concurrency]
 * 例：
 *   node tools/parallel-load.mjs D:/App/deepseek/workspace/recon-data 127.0.0.1 3307 recon_agent_verify 4
 *
 * 数据约定（README）：bill_left_shard*.csv / bill_right_shard*.csv，首行表头，
 * 20 列按 bill_left/bill_right 的 DDL 顺序。
 */
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const [dataDir = 'D:/App/deepseek/workspace/recon-data', host = '127.0.0.1', port = '3307',
       db = 'recon_agent_verify', concurrencyArg] = process.argv.slice(2);
const concurrency = Math.max(1, Number(concurrencyArg) || 4);

const MYSQL = 'D:/App/deepseek/tools/mysql-8.0.29-winx64/bin/mysql.exe';
if (!existsSync(MYSQL)) {
  console.error(`找不到 mysql 客户端: ${MYSQL}`);
  process.exit(1);
}

// 建表 DDL：与 README 对齐但**不含二级索引**（灌完统一补）
const DDL = `
CREATE TABLE IF NOT EXISTS bill_left (
  order_no VARCHAR(32) PRIMARY KEY, out_trade_no VARCHAR(64), trade_no VARCHAR(64),
  merchant_no VARCHAR(16), store_no VARCHAR(16), channel_code VARCHAR(16),
  pay_type VARCHAR(16), user_id VARCHAR(16), customer_name VARCHAR(32),
  currency CHAR(3), order_time DATETIME, pay_time DATETIME, settle_time DATETIME,
  bill_date CHAR(8), order_amount DECIMAL(18,2), paid_amount DECIMAL(18,2),
  fee_amount DECIMAL(18,2), settle_amount DECIMAL(18,2), refund_amount DECIMAL(18,2),
  status VARCHAR(16)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS bill_right (
  order_no VARCHAR(32), out_trade_no VARCHAR(64), trade_no VARCHAR(64),
  merchant_no VARCHAR(16), store_no VARCHAR(16), channel_code VARCHAR(16),
  pay_type VARCHAR(16), user_id VARCHAR(16), customer_name VARCHAR(32),
  currency CHAR(3), order_time DATETIME, pay_time DATETIME, settle_time DATETIME,
  bill_date CHAR(8), order_amount DECIMAL(18,2), paid_amount DECIMAL(18,2),
  fee_amount DECIMAL(18,2), settle_amount DECIMAL(18,2), refund_amount DECIMAL(18,2),
  status VARCHAR(16)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
`;

const COLUMNS = '(order_no, out_trade_no, trade_no, merchant_no, store_no, channel_code, pay_type, user_id, ' +
  'customer_name, currency, order_time, pay_time, settle_time, bill_date, order_amount, paid_amount, ' +
  'fee_amount, settle_amount, refund_amount, status)';

const t0 = Date.now();
const elapsed = () => ((Date.now() - t0) / 1000).toFixed(1) + 's';

function runSql(sql) {
  execFileSync(MYSQL, ['-h' + host, '-P' + port, '-uroot', '--local-infile=1', db, '-e', sql],
    { stdio: ['ignore', 'pipe', 'inherit'] });
}

console.log(`[0s] 建表（无二级索引）...`);
runSql(DDL);

const shards = readdirSync(dataDir)
  .filter(f => /^bill_(left|right)_shard\d+\.csv$/.test(f))
  .sort();
if (shards.length === 0) {
  console.error(`${dataDir} 下没有 bill_*_shard*.csv`);
  process.exit(1);
}
console.log(`[${elapsed()}] ${shards.length} 个分片，并发 ${concurrency} 开始灌数...`);

/** 简单工作队列：concurrency 个 worker 各领各的分片。 */
let cursor = 0;
let failures = 0;
async function worker(id) {
  while (cursor < shards.length) {
    const file = shards[cursor++];
    const table = file.startsWith('bill_left') ? 'bill_left' : 'bill_right';
    const sql = `LOAD DATA LOCAL INFILE '${dataDir}/${file}' INTO TABLE ${table} CHARACTER SET utf8mb4 ` +
      `FIELDS TERMINATED BY ',' LINES TERMINATED BY '\n' IGNORE 1 LINES ${COLUMNS}`;
    const started = Date.now();
    await new Promise((resolve) => {
      const child = spawn(MYSQL, ['-h' + host, '-P' + port, '-uroot', '--local-infile=1', db, '-e', sql],
        { stdio: ['ignore', 'ignore', 'inherit'] });
      child.on('exit', (code) => {
        if (code === 0) {
          console.log(`[${elapsed()}] worker${id} ${file} -> ${table} 完成 (${((Date.now() - started) / 1000).toFixed(1)}s)`);
        } else {
          failures++;
          console.error(`[${elapsed()}] worker${id} ${file} 失败 (exit ${code})`);
        }
        resolve();
      });
    });
  }
}
await Promise.all(Array.from({ length: concurrency }, (_, i) => worker(i)));
if (failures > 0) {
  console.error(`${failures} 个分片灌数失败，中止（不建索引，直接修数据重跑）`);
  process.exit(1);
}

console.log(`[${elapsed()}] 灌数完成，补建二级索引 idx_merchant（并行 DDL：4 线程 + 1G 排序缓冲）...`);
// 实测（Windows / MySQL 8.0.29，10M 行）：默认 ADD INDEX 27s，并行 DDL 24s——
// 收益有限但只是三条 SET，零成本带上；排序缓冲调大对更大的表收益更明显
const DDL_TUNE = 'SET SESSION innodb_ddl_threads=4; SET SESSION innodb_ddl_buffer_size=1073741824; ' +
  'SET SESSION innodb_parallel_read_threads=4;';
for (const table of ['bill_left', 'bill_right']) {
  const t = Date.now();
  runSql(`${DDL_TUNE} ALTER TABLE ${table} ADD INDEX idx_merchant (merchant_no, bill_date);`);
  console.log(`  ${table} 索引完成 (${((Date.now() - t) / 1000).toFixed(1)}s)`);
}
console.log(`[${elapsed()}] 全部完成。校验行数：`);
runSql('SELECT (SELECT COUNT(*) FROM bill_left) AS left_rows, (SELECT COUNT(*) FROM bill_right) AS right_rows;');
console.log(`总耗时 ${elapsed()}`);
