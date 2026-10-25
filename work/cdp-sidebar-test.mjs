// 侧栏拖动验证：headless Edge + CDP，真拖一次鼠标，再双击复原。
// 用法：node work/cdp-sidebar-test.mjs
import { spawn } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9333;
const URL = 'http://127.0.0.1:18090/';
const profile = mkdtempSync(join(tmpdir(), 'edge-cdp-'));

const edge = spawn(EDGE, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--window-size=1440,960', 'about:blank',
], { stdio: 'ignore' });

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function pageTarget() {
  for (let i = 0; i < 60; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
      const page = list.find(t => t.type === 'page');
      if (page) return page;
    } catch { /* 还没起来 */ }
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
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  }
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});

const evalJs = async expression => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('JS 异常: ' + JSON.stringify(r.exceptionDetails.text));
  return r.result.value;
};

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 960, deviceScaleFactor: 1, mobile: false });
await send('Page.navigate', { url: URL });
await sleep(2500);

const measure = () => evalJs(`(() => {
  const bar = document.getElementById('sideResizer');
  const r = bar.getBoundingClientRect();
  const main = getComputedStyle(document.querySelector('main')).gridTemplateColumns;
  return {
    asideWidth: Math.round(document.querySelector('aside').getBoundingClientRect().width),
    grid: main,
    seam: { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + 300) },
    stored: localStorage.getItem('recon.sidebarWidth'),
    draggable: getComputedStyle(bar).cursor,
  };
})()`);

const before = await measure();

// 真拖：按在缝上，往右 140px
const mouse = async (type, x, y, extra = {}) =>
  send('Input.dispatchMouseEvent', { type, x, y, button: type === 'mouseMoved' ? 'none' : 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1, ...extra });

const state = () => evalJs(`({
  cls: document.getElementById('sideResizer').classList.contains('dragging'),
  v: document.documentElement.style.getPropertyValue('--side-w'),
  aside: Math.round(document.querySelector('aside').getBoundingClientRect().width),
})`);

await mouse('mousePressed', before.seam.x, before.seam.y);
await sleep(120);
console.log('pressed', await state());
await mouse('mouseMoved', before.seam.x + 60, before.seam.y);
console.log('moved1', await state());
await mouse('mouseMoved', before.seam.x + 140, before.seam.y);
await sleep(120);
console.log('moved2', await state());
await mouse('mouseReleased', before.seam.x + 140, before.seam.y);
await sleep(300);
console.log('released', await state());

const afterDrag = await measure();

// 双击复原
const seam = afterDrag.seam;
for (let i = 0; i < 2; i++) {
  await mouse('mousePressed', seam.x, seam.y, { clickCount: 2 });
  await mouse('mouseReleased', seam.x, seam.y, { clickCount: 2 });
  await sleep(120);
}
await sleep(300);
const afterDbl = await measure();

console.log(JSON.stringify({ before, afterDrag, afterDbl }, null, 2));
ws.close();
edge.kill();
