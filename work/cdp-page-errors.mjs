import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9335;
const URL = 'http://127.0.0.1:18090/';
const profile = mkdtempSync(join(tmpdir(), 'edge-cdp-err-'));
const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--window-size=1440,960', 'about:blank'], { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function pageTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find(t => t.type === 'page');
      if (page) return page;
    } catch {}
    await sleep(250);
  }
  throw new Error('CDP 没起来');
}
const page = await pageTarget();
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let nextId = 1;
const pending = new Map();
const events = [];
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result); }
  else if (m.method === 'Runtime.exceptionThrown' || m.method === 'Log.entryAdded') events.push(m);
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }));
});
const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) return { exception: r.exceptionDetails.text, desc: r.exceptionDetails.exception?.description };
  return r.result.value;
};

await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
await send('Page.addScriptToEvaluateOnNewDocument', {
  source: 'window.__errs=[];window.addEventListener("error",e=>window.__errs.push(String(e.message)+" @"+(e.filename||"")+":"+e.lineno));',
});
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 960, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: URL });
await sleep(2500);

console.log(JSON.stringify(await evalJs(`({
  errs: window.__errs,
  actions: document.getElementById('actions').children.length,
  sideVar: document.documentElement.style.getPropertyValue('--side-w'),
  hasBar: !!document.getElementById('sideResizer'),
})`), null, 2));
console.log('events', events.slice(0, 5).map(e => e.params?.exceptionDetails?.exception?.description || e.params?.entry?.text));
ws.close(); edge.kill();
