import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9334;
const URL = 'http://127.0.0.1:18090/';
const profile = mkdtempSync(join(tmpdir(), 'edge-cdp-debug-'));
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
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result); }
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }));
});
const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
};

await send('Page.enable'); await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 960, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: URL });
await sleep(2500);

await evalJs(`(() => {
  window.__ev = [];
  const bar = document.getElementById('sideResizer');
  ['pointerdown','pointermove','pointerup','mousedown','mousemove','mouseup'].forEach(t =>
    bar.addEventListener(t, e => window.__ev.push(t + '@' + Math.round(e.clientX))));
  const r = bar.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + 300, hit: document.elementFromPoint(r.left + r.width / 2, r.top + 300)?.id };
})()`).then(v => console.log('seam', v));

const mouse = (type, x, y, extra = {}) =>
  send('Input.dispatchMouseEvent', { type, x, y, button: type === 'mouseMoved' ? 'none' : 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1, ...extra });

await mouse('mousePressed', 304, 361);
await sleep(100);
await mouse('mouseMoved', 364, 361);
await mouse('mouseMoved', 444, 361);
await sleep(100);
await mouse('mouseReleased', 444, 361);
await sleep(300);

console.log(await evalJs(`({ events: window.__ev, stored: localStorage.getItem('recon.sidebarWidth'),
  aside: Math.round(document.querySelector('aside').getBoundingClientRect().width),
  varW: getComputedStyle(document.documentElement).getPropertyValue('--side-w') })`));
ws.close(); edge.kill();
