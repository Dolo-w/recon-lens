// MCP stdio 冒烟客户端：不装任何 MCP 客户端，直接对着协议说话，验证 recon-lens 能被 Agent 用起来。
//
//   用法：node tools/mcp-smoke.mjs [jar 路径]
//   默认：node tools/mcp-smoke.mjs target/recon-lens-0.1.0.jar
//
// 断言：工具齐全、真实跑通一次对账、错误以 isError 返回、stdout 只有 JSON-RPC、stdin 关闭后进程自行退出。
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';

const jar = process.argv[2] || 'target/recon-lens-0.1.0.jar';

function resolveJava() {
  if (process.env.JAVA) return process.env.JAVA;
  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (existsSync(candidate)) return candidate;
  }
  return 'java';
}

const java = resolveJava();
const child = spawn(java, ['-jar', jar, '--spring.profiles.active=mcp'], { stdio: ['pipe', 'pipe', 'pipe'] });

child.on('error', (error) => {
  console.error(`❌ 启动不了 MCP server（${java}）：${error.message}\n   用 JAVA 指定 java 可执行文件，或把 java 放进 PATH。`);
  process.exit(1);
});

let nextId = 1;
const pending = new Map();
const stdoutNoise = [];

createInterface({ input: child.stdout }).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    stdoutNoise.push(line);
    return;
  }
  if (message.id !== undefined && pending.has(message.id)) {
    const waiter = pending.get(message.id);
    pending.delete(message.id);
    waiter(message);
  }
});

child.stderr.on('data', (chunk) => process.stderr.write('[server] ' + chunk.toString('utf8')));

function send(method, params) {
  const id = nextId++;
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  return new Promise((resolve, reject) => {
    pending.set(id, resolve);
    setTimeout(() => reject(new Error('超时：' + method)), 90000);
  });
}

function notify(method, params) {
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');
}

async function call(name, args) {
  const reply = await send('tools/call', { name, arguments: args });
  const text = (reply.result.content || []).map((part) => part.text || '').join('\n').trim();
  console.log(`\n=== ${name}(${JSON.stringify(args)}) isError=${reply.result.isError} ===\n${text}`);
  return { text, isError: reply.result.isError === true };
}

let failures = 0;
function check(ok, what) {
  console.log(`${ok ? '  ✅' : '  ❌'} ${what}`);
  if (!ok) failures++;
}

try {
  const init = await send('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'stdio-smoke', version: '0.1.0' },
  });
  console.log('initialize -> serverInfo=' + JSON.stringify(init.result.serverInfo)
    + ' protocol=' + init.result.protocolVersion);
  check(init.result.serverInfo?.name === 'recon-lens', 'serverInfo.name = recon-lens');
  check(!!init.result.capabilities?.tools, '声明了 tools 能力');
  notify('notifications/initialized', {});

  const listed = await send('tools/list', {});
  const names = listed.result.tools.map((tool) => tool.name);
  console.log('tools/list -> ' + names.join(', '));
  const expected = ['recon_list_rules', 'recon_define_rule', 'recon_run', 'recon_build_model', 'recon_job_summary', 'recon_list_diffs', 'recon_explain_diff', 'recon_explain_job'];
  check(expected.every((name) => names.includes(name)), '八个工具都在 tools/list 里');
  const defineTool = listed.result.tools.find((tool) => tool.name === 'recon_define_rule');
  check(defineTool?.inputSchema?.required?.includes('ruleBody'), 'recon_define_rule 的 inputSchema 声明了必填 ruleBody');
  const modelTool = listed.result.tools.find((tool) => tool.name === 'recon_build_model');
  check(modelTool?.inputSchema?.required?.includes('ruleCode'), 'recon_build_model 的 inputSchema 声明了必填 ruleCode');
  const runTool = listed.result.tools.find((tool) => tool.name === 'recon_run');
  const runProps = Object.keys(runTool?.inputSchema?.properties ?? {});
  check(['ruleCode', 'ruleJson', 'batchId', 'remark'].every((name) => runProps.includes(name)),
    'recon_run 暴露 ruleCode/ruleJson/batchId/remark 四个参数');
  check((runTool?.inputSchema?.required ?? []).length === 0, 'recon_run 的四个参数都可选（支持只传 batchId/remark，或用 ruleJson 内联规则）');

  const rules = await call('recon_list_rules', {});
  check(!rules.isError && rules.text.includes('BILL_VS_SETTLE'), 'recon_list_rules 能列出规则');

  const run = await call('recon_run', { ruleCode: 'BILL_VS_SETTLE', batchId: 'mcp-smoke', remark: 'stdio 冒烟' });
  const jobId = Number(/任务 #(\d+)/.exec(run.text)?.[1]);
  check(!run.isError && Number.isFinite(jobId), 'recon_run 返回了任务号 ' + jobId);

  const summary = await call('recon_job_summary', { jobId });
  check(!summary.isError && summary.text.includes('差异分类：'), 'recon_job_summary 有差异分类');

  const diffs = await call('recon_list_diffs', { jobId, limit: 3 });
  const diffId = Number(/#(\d+)/.exec(diffs.text)?.[1]);
  check(!diffs.isError && Number.isFinite(diffId), 'recon_list_diffs 返回了差异编号 ' + diffId);

  const explained = await call('recon_explain_job', { jobId, limit: 10 });
  check(!explained.isError && explained.text.includes('解释器='), 'recon_explain_job 生成了归因');

  const one = await call('recon_explain_diff', { diffId });
  check(!one.isError && one.text.includes('归因'), 'recon_explain_diff 能解释单条差异');

  // 预处理物化：建模型表 → 用生成的 <CODE>__MODEL 规则再跑一次，差异必须逐条一致
  const built = await call('recon_build_model', { ruleCode: 'BILL_VS_SETTLE', shards: 4, operator: 'mcp-smoke' });
  check(!built.isError && built.text.includes('已注册规则 BILL_VS_SETTLE__MODEL'), 'recon_build_model 建表并注册了模型规则');
  const modelled = await call('recon_run', { ruleCode: 'BILL_VS_SETTLE__MODEL', batchId: 'mcp-smoke-model', remark: '读模型表' });
  const classification = (text) => (text.match(/差异分类：(.*)/) || [])[1] ?? '';
  check(classification(modelled.text) !== '' && classification(modelled.text) === classification(run.text),
    '读模型表跑出来的差异分类与原始规则一致：' + classification(modelled.text));
  check(modelled.text.includes('扫描 左=20 右=21'), '读模型表时两侧行数不变（左=20 右=21）');

  const badRule = await call('recon_run', { ruleCode: 'NOT_A_RULE' });
  check(badRule.isError && badRule.text.includes('未找到规则'), '未知规则被包成 isError 而不是断开连接');

  check(stdoutNoise.length === 0, 'stdout 全程只有 JSON-RPC（噪声 ' + stdoutNoise.length + ' 行）');
  if (stdoutNoise.length) console.log('噪声样本：' + stdoutNoise.slice(0, 3).join(' | '));

  const exited = new Promise((resolve) => child.on('exit', (code) => resolve(code)));
  child.stdin.end();
  const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve('未退出'), 15000))]);
  check(code === 0, 'stdin 关闭后进程自行退出（exit code=' + code + '）');
  if (code === '未退出') child.kill();
} catch (error) {
  failures++;
  console.error('❌ 冒烟失败：' + error.message);
  child.kill();
} finally {
  console.log(`\n结论：${failures === 0 ? '全部通过 ✅' : failures + ' 项未通过 ❌'}`);
  process.exit(failures === 0 ? 0 : 1);
}
