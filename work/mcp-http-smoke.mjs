// MCP streamable HTTP 通道实测（P3）：不装任何 MCP 客户端，直接用 fetch 说 JSON-RPC
// 用法：node work/mcp-http-smoke.mjs [baseUrl]
const base = process.argv[2] || 'http://127.0.0.1:18080';
const endpoint = base + '/mcp';
let pass = 0, fail = 0;
const lines = [];
function say(name, ok, detail) {
  (ok ? pass++ : fail++);
  const line = `${ok ? 'PASS' : 'FAIL'}  ${name}  ->  ${detail}`;
  console.log(line);
  lines.push(line);
}

let sessionId = null;
let nextId = 1;

// streamable HTTP 的响应可能是 application/json，也可能是一条 SSE 流（单事件）。
async function rpc(method, params) {
  const body = { jsonrpc: '2.0', id: nextId++, method };
  if (params !== undefined) body.params = params;
  const headers = {
    'content-type': 'application/json',
    'accept': 'application/json, text/event-stream'
  };
  if (sessionId) headers['mcp-session-id'] = sessionId;
  const res = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify(body) });
  const sid = res.headers.get('mcp-session-id');
  if (sid) sessionId = sid;
  const text = await res.text();
  let payload = null;
  // SSE：抓最后一条 data: 行
  if (text.includes('data:')) {
    const datas = text.split('\n').filter(l => l.startsWith('data:')).map(l => l.slice(5).trim());
    if (datas.length) payload = JSON.parse(datas[datas.length - 1]);
  } else if (text.trim()) {
    payload = JSON.parse(text);
  }
  return { status: res.status, headers: res.headers, payload, raw: text };
}

function parseToolJson(result) {
  // 工具结果在 content[0].text（JSON 字符串）或 structuredContent
  if (result.structuredContent) return result.structuredContent;
  const first = (result.content || [])[0];
  if (!first) return null;
  if (first.type === 'text') { try { return JSON.parse(first.text); } catch { return { text: first.text }; } }
  return null;
}

const init = await rpc('initialize', {
  protocolVersion: '2024-11-05',
  capabilities: {},
  clientInfo: { name: 'recon-lens-http-smoke', version: '0.1.0' }
});
say('H1 initialize 走 HTTP 成功', init.status === 200 && init.payload?.result?.serverInfo?.name === 'recon-lens',
  `status=${init.status} server=${init.payload?.result?.serverInfo?.name}`);
say('H2 拿到 session id（可续用，不必每次重新初始化）', !!sessionId, sessionId || '(无)');

await rpc('notifications/initialized', {});

const list = await rpc('tools/list', {});
const tools = (list.payload?.result?.tools || []).map(t => t.name);
say('H3 tools/list 返回 17 个工具', tools.length === 17, `count=${tools.length}`);
say('H4 Agent 工具也在（和 stdio 同一份表）', tools.includes('recon_draft_rule') && tools.includes('recon_submit_approval'),
  tools.filter(t => t.startsWith('recon_draft') || t.startsWith('recon_submit')).join(',') || '(缺)');

const rules = await rpc('tools/call', { name: 'recon_list_rules', arguments: {} });
// recon_list_rules 只给文本（人读的规则清单），没有 structuredContent
const rulesText = (rules.payload?.result?.content || []).map(c => c.text || '').join('\n');
const codes = [...rulesText.matchAll(/([A-Z][A-Z0-9_]{2,})\s*（v\d+）/g)].map(m => m[1]);
say('H5 调工具：recon_list_rules 有结果', !rules.payload?.result?.isError && codes.length > 0, `codes=${codes.join(',')}`);

const bad = await rpc('tools/call', { name: 'recon_job_summary', arguments: { jobId: 'abc' } });
const badResult = bad.payload?.result;
// 参数在 SDK 的 schema 校验层就被挡下，所以文案是英文的 validation failed —— 关键是 isError 而不是 500
say('H6 参数错变成 isError 文本而不是协议错误',
  badResult?.isError === true && /validation failed|必须是/.test(JSON.stringify(badResult?.content || [])),
  (badResult?.content?.[0]?.text || '').slice(0, 70));

// GET /mcp 是给服务端推送用的 SSE 通道；没有会话时不应该 500
const getRes = await fetch(endpoint, { method: 'GET', headers: sessionId ? { 'mcp-session-id': sessionId } : {} });
say('H7 GET /mcp 不 5xx', getRes.status < 500, `status=${getRes.status}`);

// 没有 session id 的 POST（新客户端）也要能用：一次独立的 initialize
const fresh = await (async () => {
  const res = await fetch(endpoint, {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 99, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'second', version: '0' } } })
  });
  return res.status;
})();
say('H8 第二个客户端能独立建会话（stdio 做不到这点）', fresh === 200, `status=${fresh}`);

console.log(`\n== MCP HTTP 通道：${pass}/${pass + fail} ==`);
