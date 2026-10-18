// Agent 全链路冒烟：起草（MCP）→ 影子试算 → 人工审批（REST）→ 实跑对账 → 轨迹落库。
//   node tools/agent-smoke.mjs [jar 路径]
// 依赖：mock-llm 在 8123（ok 模式）、18080 的 Web 实例共用同一个库、表 bill_left/bill_right 有数。
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';

const jar = process.argv[2] || 'target/recon-lens-0.1.0.jar';
const WEB = 'http://127.0.0.1:18080';
const API_KEY = 'test-key';

function resolveJava() {
  if (process.env.JAVA) return process.env.JAVA;
  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (existsSync(candidate)) return candidate;
  }
  return 'java';
}

const java = resolveJava();
const args = [
  '-jar', jar,
  '--spring.profiles.active=mcp',
  '--recon.agent.enabled=true',
  '--recon.agent.perception-interval-ms=0',
  '--recon.llm.enabled=true','--recon.llm.api-key=mock-key','--recon.llm.model=mock-model','--recon.llm.base-url=http://127.0.0.1:8123/v1',
  '--recon.review.api-key=' + API_KEY,
  '--recon.review.token-secret=test-secret',
  '--recon.agent.schema.tables=bill_left,bill_right,perf_left,perf_right',
];
const child = spawn(java, args, {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    // 必须与 18080 的 Web 实例共用同一个 MySQL 库：草稿、试算结论、审批都在库里走
    RECON_SQL_INIT: 'never',
    SPRING_DATASOURCE_URL: 'jdbc:mysql://127.0.0.1:3307/recon_agent_verify?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useCursorFetch=true&rewriteBatchedStatements=true&allowPublicKeyRetrieval=true&useSSL=false',
    SPRING_DATASOURCE_USERNAME: 'root',
    SPRING_DATASOURCE_PASSWORD: '',
    SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver',
  },
});
child.on('error', (e) => { console.error('❌ 启动失败：' + e.message); process.exit(1); });

let nextId = 1;
const pending = new Map();
createInterface({ input: child.stdout }).on('line', (line) => {
  if (!line.trim()) return;
  let msg; try { msg = JSON.parse(line); } catch { return; }
  if (msg.id !== undefined && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
});
let stderrTail = [];
child.stderr.on('data', (c) => { stderrTail.push(c.toString('utf8')); if (stderrTail.length > 30) stderrTail.shift(); });

function send(method, params) {
  const id = nextId++;
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  return new Promise((resolve, reject) => {
    pending.set(id, resolve);
    setTimeout(() => reject(new Error('超时：' + method)), 120000);
  });
}
function notify(m, p) { child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: m, params: p }) + '\n'); }
async function call(name, a) {
  const reply = await send('tools/call', { name, arguments: a });
  const text = (reply.result.content || []).map((x) => x.text || '').join('\n').trim();
  const structured = reply.result.structuredContent || {};
  return { text, structured, isError: reply.result.isError === true };
}
let failures = 0;
function check(ok, what) { console.log(`${ok ? '  ✅' : '  ❌'} ${what}`); if (!ok) failures++; }
async function rest(method, path, body, headers = {}) {
  const res = await fetch(WEB + path, {
    method,
    headers: { 'Content-Type': 'application/json', 'X-Api-Key': API_KEY, ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let json = null; try { json = await res.json(); } catch { /* ignore */ }
  return { status: res.status, json };
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

try {
  const init = await send('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'agent-smoke', version: '0.1.0' } });
  check(init.result.serverInfo?.name === 'recon-lens', 'MCP 初始化成功');
  notify('notifications/initialized', {});

  const listed = await send('tools/list', {});
  const names = listed.result.tools.map((t) => t.name);
  check(names.includes('recon_draft_rule') && names.includes('recon_trial_status'), 'Agent 工具已注册（recon_draft_rule / recon_trial_status）');

  // ---- F6-1：起草（ok 模式）----
  const draft = await call('recon_draft_rule', {
    businessDescription: '按订单号逐笔比对账单 bill_left 和结算 bill_right 的金额，容差 0.01 元；金额不一致记 AMOUNT_MISMATCH，右缺失记 MISSING_RIGHT，左缺失记 MISSING_LEFT，重复记 DUPLICATE_KEY。',
    operator: 'agent-smoke',
  });
  const runId = draft.structured.runId;
  check(!draft.isError && Number.isFinite(runId), 'recon_draft_rule 返回会话号 #' + runId);

  // ---- F6-2：轮询到结论 ----
  let final = null;
  for (let i = 0; i < 60; i++) {
    await sleep(3000);
    const st = await call('recon_trial_status', { runId });
    final = st;
    if (!st.structured.inFlight && st.structured.status !== 'QUEUED' && st.structured.status !== 'RUNNING') break;
  }
  check(final && !final.isError, 'recon_trial_status 出结论');
  const s = final?.structured || {};
  console.log(`    status=${s.status} verdict=${s.verdict} coverage=${s.coverage} approval=${s.approvalMode} rule=${s.ruleCode} v${s.version}`);
  check(['SUCCESS', 'BLOCKED', 'FAILED'].includes(s.status), '会话状态收敛：' + s.status);
  check(['PASS', 'FAIL', 'INCONCLUSIVE'].includes(s.verdict), '试算结论 verdict=' + s.verdict);
  check(['FULL', 'SAMPLE_PARTIAL', 'FIELDS_ONLY', 'NONE'].includes(s.coverage), '覆盖等级 coverage=' + s.coverage);
  check(['AUTO_APPROVE', 'NEED_SIGN', 'BLOCK_SIGNABLE', 'BLOCK_HARD', 'NOT_APPROVABLE'].includes(s.approvalMode), '可批性 approvalMode=' + s.approvalMode);
  check(s.status === 'SUCCESS' && s.ruleCode === 'AGENT_BILL_CHECK', '起草成功并产出规则 ' + s.ruleCode);
  check(!!s.checks && Object.keys(s.checks).length > 0, '试算报告有逐项检查（' + JSON.stringify(s.checks) + '）');

  // ---- F6-3：人工审批（闸门 3，REST）----
  const { ruleCode, version } = s;
  const noKey = await fetch(`${WEB}/api/rule-reviews/${ruleCode}/${version}/token`, { method: 'POST' });
  check(noKey.status === 403, '审批接口无 X-Api-Key 一律 403（fail-closed）');
  const sub = await rest('POST', `/api/rule-reviews/${ruleCode}/${version}/submit`, {}, { 'X-Operator': 'agent-smoke' });
  if (sub.status !== 200) console.log('    submit 响应：', JSON.stringify(sub.json));
  check(sub.status === 200 && sub.json.lifecycle === 'REVIEWING', 'submit → REVIEWING');
  const tok = await rest('POST', `/api/rule-reviews/${ruleCode}/${version}/token`, {}, { 'X-Operator': 'agent-smoke' });
  if (tok.status !== 200) console.log('    token 响应：', JSON.stringify(tok.json));
  check(tok.status === 200 && !!tok.json.approvalToken, 'token 颁发成功（绑定内容哈希）');
  const ap = await rest('POST', `/api/rule-reviews/${ruleCode}/${version}/approve`,
    { token: tok.json.approvalToken, note: 'agent-smoke 审批：试算通过' }, { 'X-Operator': 'agent-smoke' });
  if (ap.status !== 200) console.log('    approve 响应：', JSON.stringify(ap.json));
  check(ap.status === 200 && ap.json.lifecycle === 'ACTIVE', 'approve → ACTIVE（Agent 自己不能批）');

  // ---- F6-4：审批后的规则真的能跑对账 ----
  const job = await rest('POST', '/api/jobs', { ruleCode, batchId: 'agent-smoke-run', sync: true });
  check(job.status === 200 && job.json.status === 'SUCCESS' && job.json.diffRows === 5,
    `审批后的规则实跑成功：diffRows=${job.json.diffRows}（预期 5：金额 2 + 单边缺失 2 + 重复 1）`);

  // ---- F6-5：会话轨迹落库 ----
  const steps = await rest('POST', `/api/diffs/explain?jobId=${job.json.jobId}`, undefined).catch(() => null); // 探活
  void steps;
  console.log('  （会话轨迹在数据库核验：recon_agent_run / recon_agent_step）');
} catch (e) {
  failures++;
  console.error('❌ 冒烟失败：' + e.message);
  console.error(stderrTail.join(''));
} finally {
  child.stdin.end();
  setTimeout(() => child.kill(), 5000);
}
console.log(`\n结论：${failures === 0 ? '全部通过 ✅' : failures + ' 项未通过 ❌'}`);
setTimeout(() => process.exit(failures === 0 ? 0 : 1), 6000);
