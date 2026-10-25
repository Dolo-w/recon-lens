// 假的 OpenAI 兼容向量服务：只为了让"有向量时走向量层"这条路在真 HTTP 上跑一次。
// 真实部署用 bge-m3，这里用 8 维哈希桶代替——够验证串联，不代表真实语义质量。
import http from 'node:http';

const DIM = 8;

function embed(text) {
  const v = new Array(DIM).fill(0);
  const s = String(text || '');
  for (let i = 0; i < s.length; i++) {
    v[s.charCodeAt(i) % DIM] += 1;
  }
  // 归一到单位长度，余弦比较更稳定
  const norm = Math.sqrt(v.reduce((a, b) => a + b * b, 0)) || 1;
  return v.map(x => x / norm);
}

const server = http.createServer((req, res) => {
  if (req.method !== 'POST' || !req.url.startsWith('/v1/embeddings')) {
    res.writeHead(404, { 'Content-Type': 'application/json' });
    res.end('{"error":"not found"}');
    return;
  }
  let body = '';
  req.on('data', c => (body += c));
  req.on('end', () => {
    let input = '';
    try {
      input = JSON.parse(body).input || '';
    } catch (_) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end('{"error":"bad json"}');
      return;
    }
    const payload = { data: [{ embedding: embed(input), index: 0 }], model: 'fake-8' };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(payload));
  });
});

server.listen(18099, '127.0.0.1', () => {
  console.log('fake-embed listening on 18099, dim=' + DIM);
});
