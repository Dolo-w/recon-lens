const base = 'http://127.0.0.1:18080/mcp';
let sid = null, id = 1;
async function rpc(method, params) {
  const body = { jsonrpc: '2.0', id: id++, method, ...(params !== undefined ? { params } : {}) };
  const h = { 'content-type': 'application/json', accept: 'application/json, text/event-stream' };
  if (sid) h['mcp-session-id'] = sid;
  const res = await fetch(base, { method: 'POST', headers: h, body: JSON.stringify(body) });
  const s = res.headers.get('mcp-session-id'); if (s) sid = s;
  const t = await res.text();
  if (t.includes('data:')) { const d = t.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trim()); return JSON.parse(d[d.length - 1]); }
  return t.trim() ? JSON.parse(t) : null;
}
await rpc('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'x', version: '0' } });
await rpc('notifications/initialized', {});
const r = await rpc('tools/call', { name: 'recon_list_rules', arguments: {} });
console.log(JSON.stringify(r).slice(0, 700));
const b = await rpc('tools/call', { name: 'recon_job_summary', arguments: { jobId: 'abc' } });
console.log('--- bad ---');
console.log(JSON.stringify(b).slice(0, 400));
