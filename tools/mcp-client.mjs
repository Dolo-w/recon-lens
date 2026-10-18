// 通用 MCP stdio 客户端：不装任何 MCP 客户端，直接对着协议说话。
//
//   node tools/mcp-client.mjs list
//   node tools/mcp-client.mjs call recon_list_rules '{}'
//   node tools/mcp-client.mjs call recon_run @args.json
//   node tools/mcp-client.mjs call recon_import_file '{"file":"a.csv","action":"confirm","table":"t","async":true}' \
//        --profiles=mcp,mysql -- --recon.agent.enabled=true
//
// 环境变量：
//   RECON_JAR     jar 路径（默认 target/recon-lens-0.1.0.jar）
//   JAVA          java 可执行文件（默认用 JAVA_HOME/bin/java，再退回 PATH）
//   DATABASE_URL / DATABASE_USER / DATABASE_PASSWORD
//                 mysql profile 下要连的库（application-mcp.yml 不设数据源，必须叠 mysql profile）
//
// 退出码：0 = 调用成功；1 = 服务端返回 isError 或协议/启动错误。
import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';

const argv = process.argv.slice(2);
const command = argv[0] || 'list';
const toolName = argv[1];
let toolArgs = argv[2] && !argv[2].startsWith('--') ? argv[2] : '{}';
const profilesFlag = argv.find((a) => a.startsWith('--profiles='));
const passthroughIndex = argv.indexOf('--');
const passthrough = passthroughIndex >= 0 ? argv.slice(passthroughIndex + 1) : [];

if (toolArgs.startsWith('@')) {
  const path = toolArgs.slice(1);
  if (!existsSync(path)) {
    console.error(`❌ 参数文件不存在：${path}`);
    process.exit(1);
  }
  toolArgs = readFileSync(path, 'utf8');
}

const jar = process.env.RECON_JAR || 'target/recon-lens-0.1.0.jar';
if (!existsSync(jar)) {
  console.error(`❌ 找不到 jar：${jar}\n   先跑 mvnw -DskipTests package，或用 RECON_JAR 指定。`);
  process.exit(1);
}

function resolveJava() {
  if (process.env.JAVA) return process.env.JAVA;
  if (process.env.JAVA_HOME) {
    const candidate = join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (existsSync(candidate)) return candidate;
  }
  return 'java';
}

const profiles = profilesFlag ? profilesFlag.slice('--profiles='.length) : 'mcp';
const java = resolveJava();
// stdout 是 JSON-RPC 通道，服务端日志走 stderr（logback 配置保证），这里原样透传给人看
const child = spawn(java, ['-jar', jar, `--spring.profiles.active=${profiles}`, ...passthrough], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    // 连外部 MySQL 时默认不让 Spring 跑 schema.sql（表由 DBA/迁移脚本建）
    RECON_SQL_INIT: process.env.RECON_SQL_INIT || (profiles.includes('mysql') ? 'never' : 'always'),
    SPRING_DATASOURCE_URL: process.env.DATABASE_URL || process.env.SPRING_DATASOURCE_URL || '',
    SPRING_DATASOURCE_USERNAME: process.env.DATABASE_USER || process.env.SPRING_DATASOURCE_USERNAME || '',
    SPRING_DATASOURCE_PASSWORD: process.env.DATABASE_PASSWORD || process.env.SPRING_DATASOURCE_PASSWORD || '',
  },
});

child.on('error', (error) => {
  console.error(`❌ 启动不了 MCP server（${java}）：${error.message}\n   用 JAVA 指定 java 可执行文件，或把 java 放进 PATH。`);
  process.exit(1);
});

let nextId = 1;
const pending = new Map();

createInterface({ input: child.stdout }).on('line', (line) => {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch {
    // 任何非 JSON 输出都是协议污染，必须让人看见（否则会被当成"没响应"）
    console.error('[stdout 非 JSON] ' + line);
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
    // 导入 60 万行可能 30 秒以上；这里给足 10 分钟，超时说明服务端真的卡住了
    setTimeout(() => reject(new Error('超时（600s）：' + method)), 600000);
  });
}

function notify(method, params) {
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');
}

function finish(code) {
  try {
    child.stdin.end();
  } catch {
    // 已经关了就算了
  }
  child.kill();
  process.exit(code);
}

function printToolResult(reply) {
  const result = reply.result || {};
  const text = (result.content || []).map((part) => part.text || '').join('\n').trim();
  console.log(`isError=${result.isError === true}`);
  if (text) console.log(text);
  if (result.structuredContent) {
    console.log('\n--- structuredContent ---');
    console.log(JSON.stringify(result.structuredContent, null, 2));
  }
  return result.isError === true;
}

try {
  const init = await send('initialize', {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'recon-mcp-client', version: '0.1.0' },
  });
  console.log('server=' + JSON.stringify(init.result?.serverInfo) + ' protocol=' + init.result?.protocolVersion);
  notify('notifications/initialized', {});

  if (command === 'list') {
    const listed = await send('tools/list', {});
    for (const tool of listed.result?.tools ?? []) {
      console.log(`- ${tool.name}：${(tool.description || '').split('\n')[0]}`);
    }
    finish(0);
  }

  if (command === 'call') {
    if (!toolName) {
      console.error('❌ 用法：node tools/mcp-client.mjs call <tool> \'<json>\'');
      finish(1);
    }
    const parsed = toolArgs.trim() ? JSON.parse(toolArgs) : {};
    const reply = await send('tools/call', { name: toolName, arguments: parsed });
    console.log(`\n=== ${toolName}(${JSON.stringify(parsed)}) ===`);
    finish(printToolResult(reply) ? 1 : 0);
  }

  console.error(`❌ 未知命令：${command}（只支持 list / call）`);
  finish(1);
} catch (error) {
  console.error('❌ ' + error.message);
  finish(1);
}
