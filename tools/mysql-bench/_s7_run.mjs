// S7：让 recon-lens 引擎连本机 MySQL（127.0.0.1:3307/bench）跑真实对账，记录墙钟与差异
// 用法： node _s7_run.mjs [baseUrl]
const base = process.argv[2] || 'http://127.0.0.1:8080';

const post = async (path, body) => {
  const t0 = Date.now();
  const r = await fetch(base + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 保留原文 */ }
  return { ms: Date.now() - t0, status: r.status, json, text };
};

const get = async (path) => {
  const r = await fetch(base + path);
  return { status: r.status, json: await r.json().catch(() => null) };
};

const line = (s) => console.log(s);

const main = async () => {
  const health = await get('/api/rules');
  line(`规则库：${(health.json || []).map(r => `${r.code}(v${r.version ?? '-'})`).join('、')}`);

  // 先热身一次（避免把 JIT/JDBC 首次开销算进第一条）
  const warm = await post('/api/jobs', { ruleCode: 'S7_NARROW', batchId: 's7-warmup', sync: true });
  line(`热身 S7_NARROW：${warm.ms} ms  status=${warm.status}  ${JSON.stringify(warm.json)}`);

  const cases = (process.argv[3] || 'S7_NARROW,S7_WIDE,S7_NARROW_FUNC8,S7_NARROW_BUCKET8').split(',');
  for (const code of cases) {
    const run = await post('/api/jobs', { ruleCode: code, batchId: `s7-${Date.now()}`, sync: true });
    const detail = run.json && run.json.jobId ? await get(`/api/jobs/${run.json.jobId}`) : null;
    const j = detail && detail.json ? detail.json.job : null;
    const sum = detail && detail.json ? detail.json.diffSummary : null;
    line(`\n===== ${code} =====`);
    line(`墙钟(含建任务+取数+落差异)：${run.ms} ms   HTTP ${run.status}`);
    line(`响应：${JSON.stringify(run.json)}`);
    if (j) line(`job#${j.id} status=${j.status} 分片=${j.shardDone}/${j.shardCount} 扫描 左=${j.leftRows} 右=${j.rightRows} 匹配=${j.matchedRows} 差异=${j.diffRows}`);
    // /api/jobs/{id} 的 diffSummary 就是 {类型:条数} 本身；severity 要去 /api/diffs/summary 拿
    if (sum) line(`差异分类(按行)：${JSON.stringify(sum)}`);
    const ds = run.json && run.json.jobId ? await get(`/api/diffs/summary?jobId=${run.json.jobId}`) : null;
    if (ds && ds.json) line(`diff 表统计：total=${ds.json.total} byType=${JSON.stringify(ds.json.byType)} bySeverity=${JSON.stringify(ds.json.bySeverity)}`);
  }
};

main().catch(e => { console.error('FAILED', e); process.exit(1); });
