// CDP driver for docs/moonlit-preview.html —— **真截图**判读 + 数值探针。
//
// 为什么需要它：内嵌浏览器（browser-use）在本机拿不到画面
//   （take_screenshot → NATIVE_BROWSER_VIEWPORT_UNAVAILABLE，且 clientWidth=0 ⇒ 几何全 NaN），
//   所以云的"像不像"、月面贴图"有没有"必须由 headless Edge 真渲染 + 真截图来判读。
//
// 用法：node docs/archive/verification/scripts/moonlit_visual_driver.mjs <cmd> [args]
//   snap <LOW|MED|HIGH> <frames> [tag]      驱动 frames 帧后截一张 → output/moonlit_frames/
//   series <LOW|MED|HIGH> <n> <step> [tag]  每 step 帧截一张（看云的**流动**）
//   probe <LOW|MED|HIGH> <frames> [occl]     不打图：回报 ops/fill/native/occl/烘焙/贴图源
//   cost <LOW|MED|HIGH> <seconds> [occl]     每 0.5 s 采一次 fill/ops，报 max/p95/mean（判预算用这个）
//   passcost <LOW|MED|HIGH> <seconds>        真过月云 + 逐 0.5 s 采样，报半遮态的 RIM 填充与 fill 峰值
//   flow <LOW|MED|HIGH> <seconds>            数值验证云的运动：每 0.5 s 采一次团心 x 与 occl
//
// ⚠️ `[occl]`（probe / cost 的第 3 参）把 `S.occlManual` 钉死，⛔ 只用于**预算实测**，
//   且 ⚠️ **测不到 `CLOUD_RIM`**：银边的门槛是逐斑 `back > 0.34`（真实压盘程度），
//   钉住标量 `occl` 不改几何 ⇒ `rimA` 能到最大 0.14，`RIM` 那一行却依然缺席（本轮实测）。
//   要测半遮态填充必须用 **`passcost`**。⛔ `occlManual` / `cloudPassT` 都是原型演示开关，不移植（R13）。
//
// 确定性：rAF 被桩掉 ⇒ 只有 __dbg.step() 推进；performance.now 固定； setSize() 绕开
// clientWidth=0。⛔ 不要在页面上跑真实时钟，否则两次启动的结果不可比。
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9334;                                   // ⛔ 别和 seaside 驱动（9333）撞
const PAGE = resolve(ROOT, 'docs', 'moonlit-preview.html');
const URL_STR = 'file:///' + PAGE.replace(/\\/g, '/');
const OUT = resolve(ROOT, 'output', 'moonlit_frames');
const DT = 1 / 60;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function launch() {
  const udd = OUT + '_profile';
  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${udd}`, `--remote-debugging-port=${PORT}`,
    '--window-size=1600,900', '--force-device-scale-factor=1', '--hide-scrollbars',
    '--mute-audio', 'about:blank',
  ];
  const proc = spawn(EDGE, args, { stdio: 'ignore' });
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) return proc; } catch (e) { /* 端口还没起 */ }
    await sleep(250);
  }
  throw new Error('Edge 没开出调试端口');
}

async function pageTarget() {
  for (let i = 0; i < 60; i++) {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const p = list.find((t) => t.type === 'page');
    if (p) return p;
    await sleep(200);
  }
  throw new Error('没有 page target');
}

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) {
        const { resolve, reject } = c.pending.get(m.id);
        c.pending.delete(m.id);
        m.error ? reject(new Error(m.error.message)) : resolve(m.result);
      }
    };
    return c;
  }
  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.id;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  close() { this.ws.close(); }
}

async function ev(cdp, expr) {
  const r = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) {
    const d = (r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text;
    throw new Error('evaluate 失败: ' + d + '\n  @ ' + JSON.stringify(r.exceptionDetails).slice(0, 300));
  }
  return r.result.value;
}

/** 启动页面前先桩掉 rAF（⛔ 页面自己的 `requestAnimationFrame(frame)` 必须落到空桩） */
async function setup(cdp) {
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.requestAnimationFrame = () => 1; window.cancelAnimationFrame = () => {};`,
  });
  await cdp.send('Page.navigate', { url: URL_STR });
  await sleep(1800);
  const probe = await ev(cdp, `({
    href: location.href, hasDbg: !!window.__dbg,
    tex: window.__dbg ? { src: !!window.__dbg.tex.src, proc: window.__dbg.tex.procedural,
                          w: window.__dbg.tex.srcW, h: window.__dbg.tex.srcH,
                          err: window.__dbg.tex.loadError } : null,
  })`);
  if (!probe.hasDbg) throw new Error('__dbg 钩子缺失 ⇒ 页面没加载：' + JSON.stringify(probe));
  // 等月面图解码（data URI 是异步 onload）
  for (let i = 0; i < 40 && !probe.tex.src; i++) {
    await sleep(150);
    probe.tex = await ev(cdp, `({ src: !!window.__dbg.tex.src, proc: window.__dbg.tex.procedural,
      w: window.__dbg.tex.srcW, h: window.__dbg.tex.srcH, err: window.__dbg.tex.loadError })`);
  }
  // 判读画面：DOM 面板/读数条/开场遮罩全是噪声 ⇒ 直接 display:none
  //   （⛔ 不要用 `classList.add('hide')` —— 那是带 transition 的淡出，截图会拍到半透明残留）
  await ev(cdp, `(() => {
    document.getElementById('gate').style.display = 'none';
    document.getElementById('panel').style.display = 'none';
    document.getElementById('hud').style.display = 'none';
    window.__dbg.setSize(1600, 900);
    return true; })()`);
  return probe;
}

const drive = (cdp, n) => ev(cdp, `(() => { for (let k = 0; k < ${n}; k++) window.__dbg.step(${DT}); return true; })()`);
const tier = (cdp, t) => ev(cdp, `window.__dbg.setTier('${t}'); true`);

async function shot(cdp, name) {
  const r = await cdp.send('Page.captureScreenshot', { format: 'png', fromSurface: true });
  mkdirSync(OUT, { recursive: true });
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(r.data, 'base64'));
  return `${OUT}/${name}.png`;
}

/** 把**中间位图**落盘：源等距圆柱图 + 烘焙出的圆盘 —— 判读"贴图到底有没有生效" */
async function dumpBitmaps(cdp) {
  mkdirSync(OUT, { recursive: true });
  const r = await ev(cdp, `(() => {
    const t = window.__dbg.tex;
    const c = document.createElement('canvas'); c.width = t.srcW; c.height = t.srcH;
    c.getContext('2d').putImageData(t.src, 0, 0);
    const hist = (img) => { const d = img.data; const h = new Array(8).fill(0);
      for (let i = 0; i < d.length; i += 4) { const l = (d[i]*0.299 + d[i+1]*0.587 + d[i+2]*0.114);
        h[Math.min(7, Math.floor(l / 32))]++; }
      return h.map((v) => +(v / (d.length / 4) * 100).toFixed(1)); };
    // 圆盘中心 5×5 采样（看月海有没有对比度）
    const dc = t.disk.getContext('2d'); const R = t.diskR;
    const patch = []; for (let y = -2; y <= 2; y++) for (let x = -2; x <= 2; x++) {
      const p = dc.getImageData(R + x * 12, R + y * 12, 1, 1).data;
      patch.push(Math.round(p[0] * 0.299 + p[1] * 0.587 + p[2] * 0.114)); }
    return { src: c.toDataURL('image/png'), disk: t.disk.toDataURL('image/png'),
             srcHist: hist(t.src),
             diskHist: hist(dc.getImageData(0, 0, t.disk.width, t.disk.height)),
             centerPatch: patch };
  })()`);
  writeFileSync(`${OUT}/src_equirect.png`, Buffer.from(r.src.split(',')[1], 'base64'));
  writeFileSync(`${OUT}/disk_baked.png`, Buffer.from(r.disk.split(',')[1], 'base64'));
  console.log('  源图亮度直方图(0-255 分 8 桶, %):', JSON.stringify(r.srcHist));
  console.log('  圆盘亮度直方图(%)           :', JSON.stringify(r.diskHist));
  console.log('  圆盘中心 5×5 亮度           :', r.centerPatch.join(' '));
  return [`${OUT}/src_equirect.png`, `${OUT}/disk_baked.png`];
}

const PROBE_EXPR = `(() => {
  const D = window.__dbg, t = D.tex, g = D.geo(), f = D.fills(), o = D.opsMap();
  const sum = (x) => Object.values(x).reduce((a, b) => a + b, 0);
  return { tier: D.S.tier, phaseLock: D.S.phaseLock, ops: o, opsTotal: sum(o),
    fill: +sum(f).toFixed(3), fillBy: Object.fromEntries(Object.entries(f).map(([k, v]) => [k, +v.toFixed(3)])),
    geo: g && { f: +g.f.toFixed(4), occl: +g.occl.toFixed(3), diskA: +g.diskA.toFixed(3),
                haloA: +g.haloA.toFixed(3), reflA: +g.reflA.toFixed(3), glitA: +g.glitA.toFixed(3),
                rimA: +g.rimA.toFixed(3) },
    tex: { src: !!t.src, proc: t.procedural, embedded: t.embedded, w: t.srcW, h: t.srcH,
           disk: t.disk ? t.disk.width : 0, bakePct: t.disk ? Math.round(t.bakeRow / t.disk.height * 100) : 0,
           err: t.loadError },
    native: (t.disk ? t.disk.width * t.disk.height : 0) + (t.srcW || 0) * (t.srcH || 0) };
})()`;

async function main() {
  const [cmd, a1, a2, a3, a4] = process.argv.slice(2);
  if (!cmd) throw new Error('缺命令：snap|series|probe|flow|cost|pass|disk');
  const T = (a1 || 'MED').toUpperCase();
  const OCCL = (cmd === 'probe' || cmd === 'cost') && a3 !== undefined
    ? parseFloat(a3) : -1;                                  // ⛔ 只有 probe/cost 收这个参（snap 的 a3 是 tag）
  const proc = await launch();
  const tgt = await pageTarget();
  const cdp = await CDP.connect(tgt.webSocketDebuggerUrl);
  try {
    const boot = await setup(cdp);
    console.log('  贴图通路:', JSON.stringify(boot.tex));
    await tier(cdp, T);
    if (OCCL >= 0) {
      await ev(cdp, `window.__dbg.setOccl(${OCCL}); true`);
      console.log('  ⚠️ 已钉住 occlManual =', OCCL, '（仅预算实测，⛔ 不是观态）');
    }
    if (cmd === 'snap' || cmd === 'probe') {
      const n = parseInt(a2 || '90', 10);
      await drive(cdp, n);
      if (cmd === 'snap') console.log('  ✓', await shot(cdp, `snap_${T}_${n}_${a4 || Date.now()}`));
      else console.log(JSON.stringify(await ev(cdp, PROBE_EXPR), null, 1));
    } else if (cmd === 'series') {
      const n = parseInt(a2 || '60', 10), stepN = parseInt(a3 || '10', 10), tag = process.argv[6] || 'ser';
      for (let k = 0; k < n; k += stepN) {
        await drive(cdp, stepN);
        console.log('  ✓', await shot(cdp, `${tag}_${T}_f${String(k + stepN).padStart(4, '0')}`));
      }
    } else if (cmd === 'disk') {
      await drive(cdp, parseInt(a2 || '90', 10));           // 烘完整盘需要若干帧
      console.log('  ✓', (await dumpBitmaps(cdp)).join('\n  ✓ '));
    } else if (cmd === 'flow') {
      const secs = parseFloat(a2 || '20'), tickN = Math.round(0.5 / DT);
      const rows = [];
      for (let s = 0; s < secs; s += 0.5) {
        await drive(cdp, tickN);
        rows.push(await ev(cdp, `(() => { const D = window.__dbg; const g = D.geo();
          const near = D.clouds.near;
          return { t: +D.S.tSec.toFixed(2), occl: +g.occl.toFixed(4),
                   cx0: Math.round(near[0] ? near[0].cx : -1), cx1: Math.round(near[1] ? near[1].cx : -1),
                   a0: near[0] ? +near[0].alphaK.toFixed(3) : null }; })()`));
      }
      const d = rows.map((r, i) => i ? +(r.cx0 - rows[i - 1].cx0).toFixed(1) : null).filter((x) => x !== null);
      console.log(JSON.stringify(rows, null, 0));
      console.log('每 0.5s 首团位移 px:', d.join(', '));
      console.log('occl 序列:', rows.map((r) => r.occl).join(' '));
    } else if (cmd === 'passcost') {
      // ⭐ 半遮态**填充**实测（§9.2 欠的 `CLOUD_RIM` 行）。
      // ⛔ 不能用 `cost` + `occlManual`：银边的门槛是**逐斑** `back > 0.34`（真实压盘程度），
      //   钉住标量 `occl` 不改几何 ⇒ `back` 依然≈0，RIM 一行永远不出现在 fillBy 里（本轮实测）。
      //   这里走 `cloudPassT`：它真的把一缕近云推过月盘，`occl` 与 `back` 同步起来。
      const secs = parseFloat(a2 || '60'), chunk = Math.max(1, Math.round(0.5 / DT));
      await ev(cdp, `window.__dbg.S.cloudPassT = 0`);
      const rows = [];
      for (let s = 0; s < secs; s += 0.5) {
        await drive(cdp, chunk);
        rows.push(await ev(cdp, `(() => { const D = window.__dbg;
          const sum = (x) => Object.values(x).reduce((a, b) => a + b, 0);
          const f = D.fills(), o = D.opsMap(), g = D.geo();
          return { t: +D.S.tSec.toFixed(1), occl: +g.occl.toFixed(3), rimA: +g.rimA.toFixed(3),
                   rimOps: o.RIM || 0, rimFill: +(f.RIM || 0).toFixed(4),
                   fill: +sum(f).toFixed(3), ops: sum(o) }; })()`));
      }
      const by = (k) => rows.map((r) => r[k]);
      const mx = (a) => a.reduce((x, y) => Math.max(x, y), 0);
      const top = rows.slice().sort((x, y) => y.rimFill - x.rimFill).slice(0, 6);
      console.log(JSON.stringify({ tier: T, n: rows.length,
        occlMax: mx(by('occl')), rimAMax: mx(by('rimA')), rimOpsMax: mx(by('rimOps')),
        rimFillMax: +mx(by('rimFill')).toFixed(4), fillMax: mx(by('fill')), opsMax: mx(by('ops')) }));
      console.log('RIM 最大的几帧:', JSON.stringify(top));
      console.log('occl 序列:', by('occl').join(' '));
      console.log('rimFill 序列:', by('rimFill').join(' '));
      console.log('fill 序列:', by('fill').join(' '));
    } else if (cmd === 'cost') {
      // 预算**最坏值**：fill/ops 随云位、boil、涟漪数逐帧波动，单帧 probe 会低估。
      // 每 chunk 帧采一次，报 max / mean / p95（⛔ 判预算只能看 max）。
      const secs = parseFloat(a2 || '20'), chunk = Math.max(1, Math.round(0.5 / DT));
      const samples = [];
      for (let s = 0; s < secs; s += 0.5) {
        await drive(cdp, chunk);
        samples.push(await ev(cdp, `(() => { const D = window.__dbg;
          const sum = (x) => Object.values(x).reduce((a, b) => a + b, 0);
          return { fill: +sum(D.fills()).toFixed(3), ops: sum(D.opsMap()) }; })()`));
      }
      const f = samples.map((r) => r.fill), o = samples.map((r) => r.ops);
      const srt = (a) => a.slice().sort((x, y) => x - y);
      console.log(JSON.stringify({ tier: T, n: samples.length,
        fillMax: srt(f)[f.length - 1], fillP95: srt(f)[Math.max(0, Math.floor(f.length * 0.95) - 1)],
        fillMean: +(f.reduce((a, b) => a + b, 0) / f.length).toFixed(3),
        opsMax: Math.max(...o), opsMean: +(o.reduce((a, b) => a + b, 0) / o.length).toFixed(1) }));
      console.log('fill 序列:', f.join(' '));
    } else if (cmd === 'pass') {
      // 「云遮月」专拍：打开演示用的过月云（__dbg.S.cloudPassT ≥ 0 即接管近层首团），
      // 每 stepN 帧落一张，用来肉眼验收 occl → 盘面变暗 / 银边 / 倒影变暗 这一整条链。
      const shots = parseInt(a2 || '5', 10), stepN = parseInt(a3 || '24', 10);
      const tag = process.argv[6] || 'pass';
      await ev(cdp, `window.__dbg.S.cloudPassT = 0`);
      for (let s = 0; s < shots; s++) {
        await drive(cdp, stepN);
        const p = await shot(cdp, `${tag}_${T}_s${s}`);
        const g = await ev(cdp, `(() => { const q = window.__dbg.geo();
          return { occl: +q.occl.toFixed(3), diskA: +q.diskA.toFixed(3), reflA: +q.reflA.toFixed(3), rimA: +q.rimA.toFixed(3) }; })()`);
        console.log('  ✓', p, JSON.stringify(g));
      }
    } else throw new Error('未知命令: ' + cmd);
  } finally {
    cdp.close(); proc.kill();
    await sleep(300);
  }
}
main().catch((e) => { console.error('✗', e.message); process.exit(1); });
