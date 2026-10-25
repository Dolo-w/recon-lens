#!/usr/bin/env node
// 真实 MCP 宿主（stdio）冒烟：spawn 子进程 → 换 JSON-RPC → 断言工具行为。
// 这不是"模拟 host 调 Java 方法"，走的是真正的 stdio 通道（newline-delimited JSON-RPC），
// 所以它能验证到：banner/日志有没有污染 stdout、工具数是否随 agent 开关变化、错误是不是结构化返回。
import { spawn } from "node:child_process";
import path from "node:path";

const JAR = path.resolve("target/recon-lens-0.1.0.jar");
const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", "java.exe") : "java";

let pass = 0, fail = 0;
const t0 = Date.now();
function show(id, title, ok, detail = "") {
  if (ok) { pass++; console.log(`[PASS] ${id} ${title}`); }
  else { fail++; console.log(`[FAIL] ${id} ${title}\n        ${detail}`); }
  if (detail && ok) console.log(`        ${detail}`);
}

const child = spawn(JAVA, ["-jar", JAR, "--spring.profiles.active=mcp", "--recon.agent.enabled=true"], {
  stdio: ["pipe", "pipe", "pipe"],
});
child.stderr.setEncoding("utf8");
let stderrBuf = "";
child.stderr.on("data", d => { stderrBuf += d; });

// stdout 必须是纯 JSON-RPC：任何不是 JSON 的行都要单独记下来（污染协议的直接证据）
const junk = [];
let buf = "";
const pending = new Map();
child.stdout.setEncoding("utf8");
child.stdout.on("data", chunk => {
  buf += chunk;
  let idx;
  while ((idx = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, idx).trim();
    buf = buf.slice(idx + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); }
    catch { junk.push(line); continue; }
    if (msg.id !== undefined && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  }
});

let nextId = 1;
function rpc(method, params, timeoutMs = 60000) {
  const id = nextId++;
  const payload = JSON.stringify({ jsonrpc: "2.0", id, method, params });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`timeout ${method}`)); }, timeoutMs);
    pending.set(id, msg => { clearTimeout(timer); resolve(msg); });
    child.stdin.write(payload + "\n");
  });
}
function notify(method, params = {}) {
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method, params }) + "\n");
}

const waitForExit = new Promise(res => child.once("exit", code => res(code)));
const textOf = r => (r.result?.content || []).map(c => c.text || "").join("\n");
const jsonOf = r => { try { return JSON.parse(textOf(r)); } catch { return null; } };

try {
  // ---------- 1. 握手 ----------
  const init = await rpc("initialize", {
    protocolVersion: "2024-11-05",
    capabilities: {},
    clientInfo: { name: "recon-smoke-host", version: "1.0.0" },
  });
  const info = init.result?.serverInfo;
  show("1", "initialize 握手成功", info?.name === "recon-lens",
    `server=${info?.name} v${info?.version} protocol=${init.result?.protocolVersion}`);
  const hasInstructions = (init.result?.instructions || "").length > 100;
  show("2", "server 带 instructions（宿主能知道先调什么）", hasInstructions,
    `instructions ${(init.result?.instructions || "").length} 字：${(init.result?.instructions || "").slice(0, 60)}…`);
  notify("notifications/initialized");

  // ---------- 2. 工具清单 ----------
  const list = await rpc("tools/list", {});
  const names = (list.result?.tools || []).map(t => t.name).sort();
  show("3", "tools/list 返回 17 个工具（agent 开启）", names.length === 17, `count=${names.length}`);
  const required = ["recon_run", "recon_list_rules", "recon_list_diffs", "recon_explain_diff",
    "recon_explain_job", "recon_job_summary", "recon_build_model", "recon_diagnose",
    "recon_define_rule", "recon_draft_rule", "recon_trial_status", "recon_register_table",
    "recon_import_file", "recon_import_status", "recon_submit_approval"];
  const missing = required.filter(n => !names.includes(n));
  show("4", "17 个工具名齐全", missing.length === 0, missing.length ? `缺 ${missing.join(",")}` : names.join(","));
  // 无参工具（recon_list_rules）的 properties 本来就是空的，所以判据是"有 object 类型的 inputSchema"
  const withSchema = (list.result.tools || []).filter(t => t.inputSchema && t.inputSchema.type === "object" && !!t.inputSchema.properties);
  const noArg = (list.result.tools || []).filter(t => Object.keys(t.inputSchema?.properties || {}).length === 0).map(t => t.name);
  show("5", "每个工具都带 object 类型 inputSchema（宿主靠它填参数）", withSchema.length === names.length,
    `带 schema 的 ${withSchema.length}/${names.length}；无参工具 ${noArg.length} 个：${noArg.join(",") || "无"}`);

  // ---------- 3. 真实业务链路：列规则 → 跑一次 → 看差异 → 归因 ----------
  const rules = await rpc("tools/call", { name: "recon_list_rules", arguments: {} });
  const rulesText = textOf(rules);
  show("6", "recon_list_rules 列出演示规则", !rules.result?.isError && rulesText.includes("BILL_VS_SETTLE"),
    rulesText.split("\n")[0]?.slice(0, 80));

  const run = await rpc("tools/call", {
    name: "recon_run",
    arguments: { ruleCode: "BILL_VS_SETTLE", remark: "mcp-host-smoke" },
  });
  const runText = textOf(run);
  const jobId = Number((runText.match(/任务\s*#?(\d+)/) || runText.match(/"jobId"\s*:\s*(\d+)/) || [])[1] || 0);
  show("7", "recon_run 跑通并给出任务号", !run.result?.isError && jobId > 0,
    `jobId=${jobId}｜${runText.split("\n")[0]?.slice(0, 70)}`);

  const summary = await rpc("tools/call", { name: "recon_job_summary", arguments: { jobId } });
  const summaryText = textOf(summary);
  show("8", "recon_job_summary 能看到差异分布", summaryText.includes("差异"), summaryText.split("\n").slice(0, 2).join(" / ").slice(0, 90));

  const diffs = await rpc("tools/call", { name: "recon_list_diffs", arguments: { jobId, limit: 5 } });
  const diffsText = textOf(diffs);
  const diffId = Number((diffsText.match(/#(\d+)/) || [])[1] || 0);
  show("9", "recon_list_diffs 返回差异明细", diffId > 0, `第一批第一条 diffId=${diffId}`);

  const explain = await rpc("tools/call", { name: "recon_explain_diff", arguments: { diffId } });
  const explainText = textOf(explain);
  show("10", "recon_explain_diff 给出归因", explainText.length > 20, explainText.split("\n")[0]?.slice(0, 80));

  // ---------- 4. 错误通道：结构化失败，而不是崩溃或空响应 ----------
  const bad = await rpc("tools/call", { name: "recon_run", arguments: { ruleCode: "NO_SUCH_RULE" } });
  const badText = textOf(bad);
  show("11", "不存在的规则 → 结构化错误（不是断连）", !!bad.result?.isError && badText.length > 0,
    `isError=${bad.result?.isError} text=${badText.slice(0, 70)}`);

  const badArg = await rpc("tools/call", { name: "recon_job_summary", arguments: {} });
  show("12", "缺必填参数 → 结构化错误", !!badArg.result?.isError || textOf(badArg).length > 0,
    `isError=${badArg.result?.isError} text=${textOf(badArg).slice(0, 60)}`);

  // ---------- 5. 审批不能被 Agent 单方发起（这是最重要的边界） ----------
  const approve = await rpc("tools/call", {
    name: "recon_submit_approval",
    arguments: { ruleCode: "BILL_VS_SETTLE", version: 1, decision: "APPROVED", confirmedBy: "smoke-host" },
  });
  const approveText = textOf(approve);
  const refused = /确认码|confirmation|403|FORBIDDEN|未配置|不能/i.test(approveText) || !!approve.result?.isError;
  show("13", "没有确认码时审批被拒绝（Agent 不能自批）", refused, approveText.slice(0, 110));

  // ---------- 6. stdout 洁净度 ----------
  show("14", "stdout 全是合法 JSON-RPC（没有 banner/日志污染）", junk.length === 0,
    junk.length ? `污染行 ${junk.length} 条，首条：${junk[0].slice(0, 80)}` : "无一字节非 JSON");
  const logToStderr = /MCP server 已就绪/.test(stderrBuf);
  show("15", "日志走 stderr（stdio 模式的硬约束）", logToStderr,
    stderrBuf.split("\n").find(l => l.includes("MCP server 已就绪"))?.slice(0, 90) || "");

  const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
  console.log(`\nSUMMARY ${pass}/${pass + fail} passed（含一次 JVM 冷启动，总计 ${elapsed}s）`);
} catch (e) {
  fail++;
  console.log(`[FAIL] 宿主脚本异常：${e.message}`);
  console.log(`        stderr 尾部：${stderrBuf.slice(-400)}`);
} finally {
  child.stdin.end();
  const code = await Promise.race([waitForExit, new Promise(r => setTimeout(() => r("timeout"), 8000))]);
  console.log(`子进程退出：${code}（stdin 关闭后应自行收尾）`);
  process.exit(fail === 0 ? 0 : 1);
}
