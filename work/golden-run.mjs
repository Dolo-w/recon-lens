#!/usr/bin/env node
// B4：golden set —— 30 条单轮问句 + 8 个多轮任务，全部打真实 HTTP /api/chat，
// 量四件事：意图命中率、工具选择率、平均轮数、每会话 token 中位数。
// token 用与 ChatSummarizer.estimateTokens 完全相同的规则估算（CJK=1，其它 4 字符=1），
// 这样报告里的数字和系统内部"要不要压缩"的判断口径一致。
import { writeFileSync } from "node:fs";

const BASE = process.env.BASE || "http://127.0.0.1:18080";
const OPERATOR = "golden";
const H = { "Content-Type": "application/json", "X-Operator": OPERATOR };

// ---------- 30 条单轮问句：期望意图 + 期望工具 ----------
// 期望工具是"这条问句必须触发的那个工具"，工具选择率按它算（多调 recall 之类不影响判定）。
const SINGLE = [
  ["规则库里有哪几条规则？", "LIST_RULES", "recon_list_rules"],
  ["现在有哪些对账规则", "LIST_RULES", "recon_list_rules"],
  ["列一下生效中的规则", "LIST_RULES", "recon_list_rules"],
  ["跑一下 BILL_VS_SETTLE", "RUN", "recon_run"],
  ["用 BILL_VS_SETTLE 帮我做一次对账", "RUN", "recon_run"],
  ["按 BILL_VS_SETTLE 对一次账", "RUN", "recon_run"],
  ["CUSTOMER_STATUS_RECON 跑一次", "RUN", "recon_run"],
  ["看下差异", "LIST_DIFFS", "recon_list_diffs"],
  ["有哪些对不上的", "LIST_DIFFS", "recon_list_diffs"],
  ["左边缺了哪些记录", "LIST_DIFFS", "recon_list_diffs"],
  ["差异明细给我", "LIST_DIFFS", "recon_list_diffs"],
  ["这次对账跑完了吗", "JOB_SUMMARY", "recon_job_summary"],
  ["任务进度怎么样", "JOB_SUMMARY", "recon_job_summary"],
  ["这单扫了多少行", "JOB_SUMMARY", "recon_job_summary"],
  ["为什么差这么多", "EXPLAIN_JOB", "recon_explain_job"],
  ["整单给我归因一下", "EXPLAIN_JOB", "recon_explain_job"],
  ["这批差异都是什么原因", "EXPLAIN_JOB", "recon_explain_job"],
  ["第 1 条差异怎么解释", "EXPLAIN_DIFF", "recon_explain_diff"],
  ["差异 3 是什么原因", "EXPLAIN_DIFF", "recon_explain_diff"],
  ["第一条差异帮我看看", "EXPLAIN_DIFF", "recon_explain_diff"],
  ["重跑失败分片", "RETRY", "recon_retry"],
  ["重试一下", "RETRY", "recon_retry"],
  ["把失败的分片重新跑一遍", "RETRY", "recon_retry"],
  ["给 BILL_VS_SETTLE 建预处理模型", "BUILD_MODEL", "recon_build_model"],
  ["物化这张账单表", "BUILD_MODEL", "recon_build_model"],
  // 会话里的 TRIAL_STATUS 直接读规则库（不代理 MCP 的 recon_trial_status），所以这里不期望工具
  ["BILL_VS_SETTLE 的试算结论是什么", "TRIAL_STATUS", null],
  ["规则能不能批", "APPROVE", null],
  ["审批要怎么走", "APPROVE", null],
  ["你能做什么", "HELP", null],
  ["帮助", "HELP", null],
];

// ---------- 8 个多轮任务：每步实际发的话 + 最后一步的期望（判"轮数"用） ----------
const MULTI = [
  {
    name: "跑一次 → 看差异 → 整单归因（槽位接力）",
    steps: ["跑一下 BILL_VS_SETTLE", "有哪些对不上的", "为什么差这么多"],
    expect: /归因|原因/,
  },
  {
    name: "跑一次 → 单条归因",
    steps: ["BILL_VS_SETTLE 对一次账", "第 1 条差异怎么解释"],
    expect: /归因|模板|解释器/,
  },
  {
    name: "跑一次 → 重跑失败分片",
    steps: ["用 BILL_VS_SETTLE 对一次账", "重跑失败分片"],
    expect: /重跑|SUCCESS|分片/,
  },
  {
    name: "列规则 → 跑其中一条",
    steps: ["有哪些对账规则", "跑一下 CUSTOMER_SUM_RECON"],
    expect: /任务|差异/,
  },
  {
    name: "任务总览 → 看差异",
    steps: ["跑一下 BILL_VS_SETTLE", "这单扫了多少行", "看下差异"],
    expect: /差异/,
  },
  {
    name: "起草规则（贴 YAML）→ 试算结论",
    steps: [
      "帮我加一条规则\n```yaml\ncode: GOLDEN_SMOKE_RECON\nname: golden 冒烟规则\nversion: 1\nleft:\n  table: bill_left\n  dimension: CONCAT(customer_id, '|', order_no)\nright:\n  table: bill_right\n  dimension: CONCAT(customer_id, '|', order_no)\ncompare:\n  - field: amount\n    tolerance: 0.01\n```",
      "试算结论怎么样",
    ],
    expect: /试算|草稿|版本/,
  },
  {
    name: "审批怎么做 → 不真的批准",
    steps: ["审批要怎么走"],
    expect: /确认码|api-key|签字/,
  },
  {
    name: "草稿直接跑 → 给出可读理由",
    steps: [
      "帮我加一条规则\n```yaml\ncode: GOLDEN_DRAFT_ONLY\nname: 只落草稿\nversion: 1\nleft:\n  table: bill_left\n  dimension: CONCAT(customer_id, '|', order_no)\nright:\n  table: bill_right\n  dimension: CONCAT(customer_id, '|', order_no)\ncompare:\n  - field: amount\n    tolerance: 0.01\n```",
      "跑一下 GOLDEN_DRAFT_ONLY",
    ],
    expect: /草稿|还没生效|DRAFT/,
  },
];

// ---------- 与 ChatSummarizer.estimateTokens 同一条规则 ----------
function estimateTokens(s) {
  let wide = 0, narrow = 0;
  for (const ch of s) (ch.codePointAt(0) >= 0x2e80 ? wide++ : narrow++);
  return wide + Math.ceil(narrow / 4);
}

async function newSession(title) {
  const r = await fetch(`${BASE}/api/chat/sessions`, { method: "POST", headers: H, body: JSON.stringify({ title }) });
  const j = await r.json();
  return j.session.id;
}

// 解析 SSE：只认 event:/data: 成对出现
async function ask(sessionId, message) {
  const r = await fetch(`${BASE}/api/chat`, {
    method: "POST",
    headers: H,
    body: JSON.stringify({ sessionId, message }),
  });
  if (!r.ok) return { intent: null, tools: [], text: `HTTP ${r.status}`, status: r.status };
  const raw = await r.text();
  const intents = [], tools = [];
  let text = "";
  for (const block of raw.split("\n\n")) {
    const ev = /event:\s*(\w+)/.exec(block);
    const dm = /data:(.*)$/m.exec(block);
    if (!ev || !dm) continue;
    let data;
    try { data = JSON.parse(dm[1].trim()); } catch { continue; }
    if (ev[1] === "intent") intents.push(data.intent);
    else if (ev[1] === "tool") tools.push(data.name);
    else if (ev[1] === "delta") text += data.text || "";
  }
  return { intent: intents[0] || null, tools, text, status: r.status };
}

const rows = [];
const sessionTokens = [];
let hitIntent = 0, hitTool = 0, toolDenom = 0;

// 两步量法：意图用**全新会话**（没有槽位，只能靠这句话判断）；
// 工具用**已经跑过一单的会话**（否则一句"看下差异"必然只能反问"看哪个任务"，
// 那测到的是"缺参数时是否反问"，不是"工具选得对不对"）。
for (const [q, wantIntent, wantTool] of SINGLE) {
  const fresh = await newSession("golden-intent: " + q.slice(0, 12));
  const res = await ask(fresh, q);
  const okIntent = res.intent === wantIntent;
  if (okIntent) hitIntent++;

  let okTool = null, gotTools = [];
  if (wantTool) {
    toolDenom++;
    const primed = await newSession("golden-tool: " + q.slice(0, 12));
    await ask(primed, "跑一下 BILL_VS_SETTLE");        // 先把 ruleCode/jobId 灌进槽位
    const t = await ask(primed, q);
    gotTools = t.tools;
    okTool = t.tools.includes(wantTool);
    if (okTool) hitTool++;
  }
  sessionTokens.push(estimateTokens(q) + estimateTokens(res.text));
  rows.push({
    q, wantIntent, gotIntent: res.intent, okIntent,
    wantTool, gotTools, okTool,
    chars: res.text.length,
  });
  console.log(`${okIntent && (okTool !== false) ? "[PASS]" : "[FAIL]"} ` +
    `意图 ${res.intent}/${wantIntent}　工具 ${gotTools.join("/") || "-"}/${wantTool || "-"} :: ${q.slice(0, 24)}`);
}

console.log(`\n--- 多轮任务 ---`);
const turnCounts = [];
for (const t of MULTI) {
  const sid = await newSession("golden: " + t.name.slice(0, 14));
  let last = null, tokens = 0;
  for (const step of t.steps) {
    last = await ask(sid, step);
    tokens += estimateTokens(step) + estimateTokens(last.text);
  }
  const ok = t.expect.test(last.text);
  turnCounts.push(t.steps.length);
  sessionTokens.push(tokens);
  console.log(`${ok ? "[PASS]" : "[FAIL]"} ${t.steps.length} 轮 · ${t.name}${ok ? "" : `\n        末轮文本：${last.text.slice(0, 120)}`}`);
  rows.push({ q: t.name, turns: t.steps.length, ok });
}

const median = a => {
  const s = [...a].sort((x, y) => x - y);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2);
};

const intentRate = (100 * hitIntent / SINGLE.length).toFixed(1);
const toolRate = (100 * hitTool / toolDenom).toFixed(1);
const avgTurns = (turnCounts.reduce((a, b) => a + b, 0) / turnCounts.length).toFixed(2);
const tokMedian = median(sessionTokens);

console.log(`\n===== 四指标 =====`);
console.log(`意图命中率：${intentRate}%  (${hitIntent}/${SINGLE.length})　目标 ≥90%`);
console.log(`工具选择率：${toolRate}%  (${hitTool}/${toolDenom})　目标 ≥85%`);
console.log(`平均轮数：${avgTurns}  (${MULTI.length} 个任务)　目标 ≤3`);
console.log(`每会话 token 中位数：${tokMedian}　目标 ≤6000`);

writeFileSync("work/golden-results.json",
  JSON.stringify({ intentRate, toolRate, avgTurns, tokMedian, rows, sessionTokens }, null, 2));

const misses = rows.filter(r => r.okIntent === false);
if (misses.length) {
  console.log(`\n--- 没命中意图的 ${misses.length} 条（诊断用）---`);
  for (const m of misses) console.log(`  「${m.q}」→ ${m.gotIntent}（期望 ${m.wantIntent}）`);
}
