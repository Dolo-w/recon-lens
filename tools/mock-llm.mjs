// OpenAI 兼容的本地 Mock LLM（仅用于联调/压测，不参与生产）。
//   node tools/mock-llm.mjs [port]
//   切换行为：curl 'http://127.0.0.1:8123/__mode?set=ok'
//   查看计数：curl 'http://127.0.0.1:8123/__stats'
//
// 为什么要它：起草与归因都要调模型，但真实模型有三件事测不了 ——
// ① 首次输出必然违规（验自纠正循环）；② 一直不收敛（验 3 轮转人工）；③ 延迟可控（验异步与预算）。
import { createServer } from 'node:http';

const port = Number(process.argv[2] || 8123);

let mode = 'ok';
let calls = 0;
let lastUserPrompt = '';
const log = [];

const VALID_RULE = `{"code":"AGENT_BILL_CHECK","reasoning":"按订单号逐笔比对金额，容差沿用业务给的 0.01","assumptions":["假设两侧 order_no 是同一个业务键"],"rule":{
  "code":"AGENT_BILL_CHECK","name":"Agent 起草的账单对账","description":"按订单号比对金额",
  "left":{"label":"账单","table":"bill_left","keyField":"order_no","keyType":"STRING","orderBy":"order_no"},
  "right":{"label":"结算","table":"bill_right","keyField":"order_no","keyType":"STRING","orderBy":"order_no"},
  "shard":{"count":4,"parallelism":4,
    "whereLeft":"MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}",
    "whereRight":"MOD(CAST(SUBSTRING(order_no, 10, 2) AS SIGNED), {shards}) = {index}"},
  "compare":[{"field":"amount","type":"DECIMAL","tolerance":0.01,"diffType":"AMOUNT_MISMATCH","severity":"HIGH"},
             {"field":"status","type":"STRING","ignoreCase":true,"diffType":"STATUS_MISMATCH","severity":"MEDIUM"}],
  "missing":{"leftMissingDiffType":"MISSING_LEFT","rightMissingDiffType":"MISSING_RIGHT","severity":"HIGH"},
  "duplicate":{"diffType":"DUPLICATE_KEY","severity":"MEDIUM"},
  "maxAttempts":2}}`;

// 容差放大版：闸门 1 必须拦下（首版规则走的是"绝对上限"那条判定）
const VIOLATING_RULE = JSON.parse(VALID_RULE);
VIOLATING_RULE.rule.compare[0].tolerance = 999;

function replyFor(messages) {
  const user = messages.filter((m) => m.role === 'user').map((m) => m.content).join('\n');
  lastUserPrompt = user;
  const isDraft = user.includes('规则结构') || user.includes('business-request');

  if (mode === 'bad-json') return '抱歉，我不能输出 JSON。';
  if (mode === 'always-violation') return JSON.stringify(VIOLATING_RULE);
  if (mode === 'violation-then-fix') {
    // 第一次违规，看到反馈块（feedback 里带"闸门 1"）之后改正
    return user.includes('闸门 1') ? VALID_RULE : JSON.stringify(VIOLATING_RULE);
  }
  if (mode === 'explain' || !isDraft) {
    return '【Mock 归因】两侧金额差异属于渠道结算延迟，建议核对渠道对账单的入账时点。';
  }
  return VALID_RULE;
}

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (url.pathname === '/__mode') {
    const next = url.searchParams.get('set');
    if (next) mode = next;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ mode }));
    return;
  }
  if (url.pathname === '/__stats') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ mode, calls, recent: log.slice(-5) }));
    return;
  }
  if (url.pathname === '/__reset') {
    calls = 0;
    log.length = 0;
    res.writeHead(200).end('ok');
    return;
  }
  if (!url.pathname.endsWith('/chat/completions')) {
    res.writeHead(404).end('not found');
    return;
  }
  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', async () => {
    let payload = {};
    try { payload = JSON.parse(body); } catch { /* 忽略 */ }
    const messages = payload.messages || [];
    calls++;
    const content = replyFor(messages);
    const delay = mode.startsWith('slow:') ? Number(mode.split(':')[1]) || 0 : 0;
    if (delay) await new Promise((r) => setTimeout(r, delay));
    log.push({ at: new Date().toISOString(), chars: content.length, promptChars: lastUserPrompt.length });
    if (log.length > 200) log.shift();
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      id: 'mock-' + calls,
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: payload.model || 'mock',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      usage: { prompt_tokens: lastUserPrompt.length, completion_tokens: content.length, total_tokens: lastUserPrompt.length + content.length },
    }));
  });
});

server.listen(port, '127.0.0.1', () => {
  console.log(`mock-llm listening on http://127.0.0.1:${port} (mode=${mode})`);
});
