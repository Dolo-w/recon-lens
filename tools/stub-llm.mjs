// 桩模型服务：把 recon-lens 实际发给模型的请求体原样记录下来。
//
// 用途不是"假装模型聪明"，而是**让 prompt 可观测**：真实请求走完整条装配路径
// （ChatRuntime → composeContext → LlmPlanner.systemPrompt → HTTP），
// 这里只是接收方，把收到的 messages 落到 logs/stub-llm.jsonl，供人工逐字比对。
//
// 用法：
//   node tools/stub-llm.mjs            # 监听 18999
//   echo '{"action":"RUN",...}' > logs/stub-reply.txt   # 热替换回复内容
//
// 环境变量：STUB_PORT（默认 18999）、STUB_LOG（默认 logs/stub-llm.jsonl）
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const PORT = Number(process.env.STUB_PORT || 18999);
const LOG = process.env.STUB_LOG || 'logs/stub-llm.jsonl';
const REPLY_FILE = path.join(path.dirname(LOG), 'stub-reply.txt');
const CURSOR_FILE = path.join(path.dirname(LOG), 'stub-cursor.txt');
const DEFAULT_REPLY = '{"action":"LIST_RULES","args":{},"confidence":0.9,"reason":"stub 默认回复","options":[]}';

fs.mkdirSync(path.dirname(LOG), { recursive: true });

// 回复文件可以是一个 JSON 对象（每次同样回它），也可以是一个 **JSON 数组**：
// 数组模式按调用次数依次取，用来演练"第二轮看结果再决定"这类多步链路。
// 取完不回头（停在最后一条），因此最后一条应当是收尾答案。
function reply() {
  let raw;
  try {
    raw = fs.readFileSync(REPLY_FILE, 'utf8').trim() || DEFAULT_REPLY;
  } catch {
    return DEFAULT_REPLY;
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return raw;
  }
  if (!Array.isArray(parsed) || parsed.length === 0) {
    return raw;
  }
  let at = 0;
  try {
    at = Number(fs.readFileSync(CURSOR_FILE, 'utf8').trim() || '0') || 0;
  } catch { /* 第一次没有游标文件 */ }
  const picked = parsed[Math.min(at, parsed.length - 1)];
  fs.writeFileSync(CURSOR_FILE, String(at + 1));
  return JSON.stringify(picked);
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET') {
    // 探活/回显**不消耗游标**：否则一次 /v1/models 就把多步脚本里的第一条吃掉了
    let peek = DEFAULT_REPLY;
    try { peek = fs.readFileSync(REPLY_FILE, 'utf8').trim() || DEFAULT_REPLY; } catch { /* 保持默认 */ }
    let at = 0;
    try { at = Number(fs.readFileSync(CURSOR_FILE, 'utf8').trim() || '0') || 0; } catch { /* 没游标 */ }
    if (req.url === '/reset') {
      fs.writeFileSync(CURSOR_FILE, '0');
      at = 0;
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, cursor: at, script: peek }));
    return;
  }
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    let body = null;
    try { body = JSON.parse(raw); } catch { /* 保留原文 */ }
    const entry = {
      at: new Date().toISOString(),
      path: req.url,
      jsonMode: Boolean(body && body.response_format),
      model: body && body.model,
      // messages 逐条记下来：system 是装配结果，user 是用户原话
      messages: body && body.messages ? body.messages : [],
      raw: body ? undefined : raw,
    };
    fs.appendFileSync(LOG, JSON.stringify(entry) + '\n');
    const content = reply();
    // 模拟真实服务端的 usage：真实 provider 会回总用量，桩也回一个"像样"的数，
    // 这样 Agent 预算账本走的是"真实用量"分支（不是 0 → 退回字符数估算）
    const promptChars = (entry.messages || []).reduce((n, m) => n + String(m.content || '').length, 0);
    const promptTokens = Math.max(1, Math.round(promptChars / 2));
    const completionTokens = Math.max(1, Math.round(content.length / 2));
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      id: 'stub-' + Date.now(),
      object: 'chat.completion',
      model: entry.model || 'stub',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      usage: {
        prompt_tokens: promptTokens,
        completion_tokens: completionTokens,
        total_tokens: promptTokens + completionTokens,
      },
    }));
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`stub-llm listening on http://127.0.0.1:${PORT}  log=${LOG}`);
});
