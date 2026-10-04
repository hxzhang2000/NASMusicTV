// Scratch CDP driver for seaside-preview.html visual verification.
// Usage: node seaside_drv.mjs <cmd> [args...]
// Commands:
//   snap <n>            — drive n frames then screenshot to output/seaside_frames/f_<seq>.png
//   series <n> <step>   — drive n frames, screenshot every `step` frames
//   waterline <n>       — drive n frames, then measure per-column waterline y
//   cost <n>            — drive n frames, report avg/max JS+render ms per frame
// Deterministic: rAF is stubbed; each frame advances the clock by DT=16.6667ms.
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

// 脚本自身目录 ⇒ 仓库根。
//   scripts → verification → archive → docs → <root> ＝ **四级**
//   ⛔ 只上溯三级会停在 `<repo>/docs`，于是 PAGE 变成 `docs/docs/…`、
//      OUT 变成 `docs/output/…` —— 页面直接加载失败（诊断里会看到
//      location.href = chrome-error://chromewebdata/）。
// ⛔ 不要用 `new URL(..., import.meta.url)`：本文件下面声明了 `const URL`（页面地址），
//   它会**遮蔽全局 URL 构造函数**，`new URL()` 直接抛 "URL is not a constructor"。
const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9333;
// 页面与截图路径都相对仓库根 ⇒ 仓库移动/换机不失效
const PAGE = resolve(ROOT, 'docs', 'seaside-preview.html');
const URL = 'file:///' + PAGE.replace(/\\/g, '/');
const DT = 16.6667;
// 截图输出：agent 产出的图像按仓库规则放 output/（不是 logs_temp/）
const OUT = resolve(ROOT, 'output', 'seaside_frames');

let seq = Math.floor(Date.now() % 100000);
let runPrefix = '';

function sleep(ms){ return new Promise(r => setTimeout(r, ms)); }

async function launch(){
  const udd = OUT + '_profile';
  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${udd}`, `--remote-debugging-port=${PORT}`,
    '--window-size=1600,900', '--force-device-scale-factor=1', '--hide-scrollbars',
    '--autoplay-policy=no-user-gesture-required', '--mute-audio', 'about:blank'
  ];
  const proc = spawn(EDGE, args, { stdio: 'ignore' });
  // wait for the debugging endpoint
  for (let i = 0; i < 60; i++){
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/list`);
      if (r.ok) return proc;
    } catch (e){}
    await sleep(250);
  }
  throw new Error('Edge did not open a debug port');
}

async function getTarget(){
  for (let i = 0; i < 40; i++){
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const page = list.find(t => t.type === 'page');
    if (page) return page;
    await sleep(200);
  }
  throw new Error('no page target');
}

class CDP {
  constructor(ws){ this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url){
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)){
        const { resolve, reject } = c.pending.get(m.id);
        c.pending.delete(m.id);
        m.error ? reject(new Error(m.error.message)) : resolve(m.result);
      }
    };
    return c;
  }
  send(method, params = {}){
    return new Promise((resolve, reject) => {
      const id = ++this.id;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  close(){ this.ws.close(); }
}

async function evaluate(cdp, expr){
  const r = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('evaluate failed: ' + JSON.stringify(r.exceptionDetails).slice(0, 400));
  return r.result.value;
}

async function setup(cdp){
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  // ⛔ Inject BEFORE any page script runs, so the page's top-level
  //    `requestAnimationFrame(frame)` lands in our stub and NO real-time frame
  //    ever fires (that stray real-timestamp frame was the cross-launch
  //    nondeterminism: it ran the wave state machine at a garbage `t`).
  //    AudioContext is stubbed to throw ⇒ startDemo() falls back to the
  //    deterministic readFallback() synth (pure function of performance.now,
  //    which we also pin). Demo mode uses ac.currentTime (real clock) — not A/B-safe.
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `(() => {
      window.__drv = { cb: null, next: 0, frames: 0 };
      window.__realPN = performance.now.bind(performance);
      window.__fakeNow = 200000;
      window.performance.now = () => window.__fakeNow;
      window.requestAnimationFrame = (cb) => { window.__drv.cb = cb; return 1; };
      window.cancelAnimationFrame = () => {};
      const throwNoAudio = () => { throw new Error('AudioContext stubbed out by CDP driver (fallback mode)'); };
      window.AudioContext = throwNoAudio;
      window.webkitAudioContext = throwNoAudio;
    })();`
  });
  await cdp.send('Page.navigate', { url: URL });
  // wait for load + settle
  await sleep(1500);
  // click gate: begin() captures t0 = performance.now() = __fakeNow = 200000
  // ⚠️ gate 为 null ⇒ 多半是页面没加载出来（路径/协议），而不是时序。
  //   直接 `.click()` 会抛 "Cannot read properties of null" 并把诊断信息吃掉，
  //   所以这里先回报文档状态再决定要不要点。
  const probe = await evaluate(cdp,
    `({ url: location.href, title: document.title, hasGate: !!document.getElementById('gate'),
        hasStage: !!document.getElementById('stage'),
        bodyLen: document.body ? document.body.innerHTML.length : -1 })`);
  if (!probe || !probe.hasGate) {
    console.error('  ✗ gate 未找到 —— 页面可能没加载。诊断：', JSON.stringify(probe));
    console.error('    请求的 URL:', URL);
    console.error('    解析出的路径:', PAGE);
    throw new Error('seaside-preview.html 未加载（gate 缺失）');
  }
  await evaluate(cdp, `document.getElementById('gate').click(); true`);
  // the initial rAF registration is already in our stub; seed the clock
  await evaluate(cdp, `window.__drv.next = 200030; window.__fakeNow = 200030; true`);
  return 200000;
}

async function drive(cdp, n){
  // returns [costs array] in ms; advances exactly n frames at DT
  return await evaluate(cdp, `(() => {
    const d = window.__drv;
    const costs = [];
    for (let k = 0; k < ${n}; k++){
      if (!d.cb) break;
      d.next += ${DT};
      window.__fakeNow += ${DT};          // keep the fake page clock in lockstep
      const t0r = window.__realPN();      // wall-clock cost via the saved real clock
      d.cb(d.next);
      costs.push(window.__realPN() - t0r);
    }
    return costs;
  })()`);
}

async function screenshot(cdp, clip){
  const params = { format: 'png', fromSurface: true };
  if (clip) params.clip = clip;
  const r = await cdp.send('Page.captureScreenshot', params);
  const name = runPrefix + `f_${String(seq++).padStart(4, '0')}.png`;
  writeFileSync(OUT + '/' + name, Buffer.from(r.data, 'base64'));
  return name;
}

async function waterline(cdp){
  // per-column waterline via sand/sea classification on the raw canvas
  return await evaluate(cdp, `(() => {
    const cvs = document.getElementById('stage');
    const ctx = cvs.getContext('2d');
    const W = cvs.width, H = cvs.height;
    const y0 = Math.floor(H * 0.52), y1 = Math.floor(H * 0.90);
    const cols = 32;
    const img = ctx.getImageData(0, y0, W, y1 - y0).data;
    const out = [];
    for (let c = 0; c < cols; c++){
      const x = Math.floor(W * (c + 0.5) / cols);
      let wl = -1;
      for (let y = 0; y < y1 - y0 - 3; y++){
        const i = (y * W + x) * 4;
        const s = (img[i] - img[i + 2] - 10) / 40;      // sandiness
        if (s > 0.5){
          let ok = true;
          for (let k = 1; k <= 3; k++){
            const j = ((y + k) * W + x) * 4;
            if ((img[j] - img[j + 2] - 10) / 40 <= 0.5){ ok = false; break; }
          }
          if (ok){ wl = y0 + y; break; }
        }
      }
      out.push(wl);
    }
    return { wl: out, H };
  })()`);
}

// ── analysis scripts (run inside the page, operate on the canvas pixels) ──
const ANALYZE = `
(() => {
  const cvs = document.getElementById('stage');
  const ctx = cvs.getContext('2d');
  const W = cvs.width, H = cvs.height;
  const img = ctx.getImageData(0, 0, W, H).data;
  const lum = (x, y) => { const i = (Math.min(W-1,Math.max(0,x|0)) + Math.min(H-1,Math.max(0,y|0)) * W) * 4; return (img[i] + img[i+1] + img[i+2]) / 765; };
  const rb  = (x, y) => { const i = (Math.min(W-1,Math.max(0,x|0)) + Math.min(H-1,Math.max(0,y|0)) * W) * 4; return (img[i] - img[i+2]) / 255; };
  const out = {};

  // ── waterline per column: first sustained sand row ──
  out.waterline = function(cols){
    const res = [];
    for (let c = 0; c < cols; c++){
      const x = Math.floor(W * (c + 0.5) / cols);
      const y0 = Math.floor(H * 0.50), y1 = Math.floor(H * 0.90);
      let wl = -1;
      for (let y = y0; y < y1 - 4; y++){
        const s = (rb(x, y) * 255 - 10) / 40;
        if (s > 0.5){
          let ok = true;
          for (let k = 1; k <= 3; k++) if ((rb(x, y + k) * 255 - 10) / 40 <= 0.5){ ok = false; break; }
          if (ok){ wl = y; break; }
        }
      }
      res.push(wl);
    }
    return res;
  };

  // ── lace connectivity: bright filament mask in the band just seaward of the waterline ──
  out.lace = function(){
    const cols = 64;
    const wl = out.waterline(cols);
    const X0 = Math.floor(W * 0.10), X1 = Math.floor(W * 0.90);
    const wmap = new Int32Array(W);
    let wlSum = 0, wlN = 0;
    for (let c = 0; c < cols; c++){
      const x0 = Math.floor(W * c / cols), x1 = Math.floor(W * (c + 1) / cols);
      for (let x = x0; x < x1; x++) wmap[x] = wl[c];
      if (wl[c] > 0){ wlSum += wl[c]; wlN++; }
    }
    const wlMean = wlN ? wlSum / wlN : H * 0.62;
    const bandTop = Math.floor(wlMean - 58), bandBot = Math.floor(wlMean + 4);
    if (bandTop < 0 || bandBot >= H) return { err: 'band out of range', wlMean };
    const seaLum = new Float32Array(W);
    for (let x = X0; x < X1; x++){
      let s = 0;
      for (let k = 0; k < 6; k++) s += lum(x, wlMean - 90 - k * 4);
      seaLum[x] = s / 6;
    }
    const mask = new Uint8Array(W * H);
    let total = 0;
    for (let y = bandTop; y < bandBot; y++){
      for (let x = X0; x < X1; x++){
        if (lum(x, y) > seaLum[x] + 0.14 && rb(x, y) * 255 < 30){ mask[y * W + x] = 1; total++; }
      }
    }
    const seen = new Uint8Array(W * H);
    const comps = [];
    const q = new Int32Array(W * H * 2);
    for (let y = bandTop; y < bandBot; y++){
      for (let x = X0; x < X1; x++){
        const idx = y * W + x;
        if (!mask[idx] || seen[idx]) continue;
        let qh = 0, qt = 0, size = 0, minX = x, maxX = x, minY = y, maxY = y;
        q[qt++] = x; q[qt++] = y; seen[idx] = 1;
        while (qh < qt){
          const cx = q[qh++], cy = q[qh++];
          size++;
          if (cx < minX) minX = cx; if (cx > maxX) maxX = cx;
          if (cy < minY) minY = cy; if (cy > maxY) maxY = cy;
          for (let dy = -1; dy <= 1; dy++){
            for (let dx = -1; dx <= 1; dx++){
              if (!dx && !dy) continue;
              const nx = cx + dx, ny = cy + dy;
              if (nx < X0 || nx >= X1 || ny < bandTop || ny >= bandBot) continue;
              const ni = ny * W + nx;
              if (mask[ni] && !seen[ni]){ seen[ni] = 1; q[qt++] = nx; q[qt++] = ny; }
            }
          }
        }
        if (size >= 2) comps.push({ size, w: maxX - minX + 1, h: maxY - minY + 1 });
      }
    }
    comps.sort((a, b) => b.size - a.size);
    const big = comps.filter(c => c.size >= 40);
    return {
      wlMean, totalFoamPx: total,
      nComps: comps.length,
      nBig: big.length, nSmall: comps.length - big.length,
      meanSize: comps.length ? Math.round(total / comps.length) : 0,
      meanBigSize: big.length ? Math.round(big.reduce((s, c) => s + c.size, 0) / big.length) : 0,
      maxSize: comps.length ? comps[0].size : 0,
      meanBigWH: big.length ? [Math.round(big.reduce((s, c) => s + c.w, 0) / big.length), Math.round(big.reduce((s, c) => s + c.h, 0) / big.length)] : null,
      top10: comps.slice(0, 10).map(c => c.size)
    };
  };

  // ── ASCII structural render: '#' solid foam, '+' thin foam, '~' water, '.' sand ──
  out.ascii = function(opt){
    const X0 = Math.floor(W * ((opt && opt.x0f) || 0.10));
    const X1 = Math.floor(W * ((opt && opt.x1f) || 0.90));
    const Y0 = Math.floor(H * ((opt && opt.y0f) || 0.58));
    const Y1 = Math.floor(H * ((opt && opt.y1f) || 0.78));
    const gw = (opt && opt.w) || 130, gh = (opt && opt.h) || 36;
    const seaLum = new Float32Array(W);
    for (let x = X0; x < X1; x++){
      let s = 0;
      for (let k = 0; k < 6; k++) s += lum(x, Math.max(0, Y0 - 70 - k * 4));
      seaLum[x] = s / 6;
    }
    const rows = [];
    for (let gy = 0; gy < gh; gy++){
      let line = '';
      const y0 = Y0 + Math.floor((Y1 - Y0) * gy / gh);
      const y1 = Y0 + Math.floor((Y1 - Y0) * (gy + 1) / gh);
      const ym = Math.floor((y0 + y1) / 2);
      for (let gx = 0; gx < gw; gx++){
        const x0 = X0 + Math.floor((X1 - X0) * gx / gw);
        const x1 = X0 + Math.floor((X1 - X0) * (gx + 1) / gw);
        let bright = 0, total = 0;
        for (let y = y0; y < y1; y += 2){
          for (let x = x0; x < x1; x += 2){
            total++;
            if (lum(x, y) > seaLum[x] + 0.11 && rb(x, y) * 255 < 30) bright++;
          }
        }
        if (!total) line += '?';
        else if (bright / total > 0.34) line += '#';
        else if (bright / total > 0.10) line += '+';
        else if (Math.abs(lum(x0 + ((x1 - x0) >> 1), ym) - seaLum[x0 + ((x1 - x0) >> 1)]) < 0.05) line += '~';
        else line += '.';
      }
      rows.push(line);
    }
    return { rows, X0, X1, Y0, Y1, gw, gh };
  };

  // ── sample avg RGB of a rect (diagnostic) ──
  out.pix = function(x, y, w, h){
    const d = ctx.getImageData(x, y, w, h).data;
    let r = 0, g = 0, b = 0, n = 0;
    for (let i = 0; i < d.length; i += 4){ r += d[i]; g += d[i + 1]; b += d[i + 2]; n++; }
    r /= n; g /= n; b /= n;
    return { avg: [Math.round(r), Math.round(g), Math.round(b)], rb: Math.round((r - b) * 10) / 10 };
  };

  // ── high-pass ASCII: isolate high-frequency luminance (caustic glints) from the smooth sea ──
  out.hpAscii = function(opt){
    const X0 = Math.floor(W * ((opt && opt.x0f) || 0.05));
    const X1 = Math.floor(W * ((opt && opt.x1f) || 0.95));
    const Y0 = Math.floor(H * ((opt && opt.y0f) || 0.05));
    const Y1 = Math.floor(H * ((opt && opt.y1f) || 0.45));
    const gw = (opt && opt.w) || 140, gh = (opt && opt.h) || 30;
    // cell centers + avg lum
    const cw = (X1 - X0) / gw, ch = (Y1 - Y0) / gh;
    const cell = new Float32Array(gw * gh);
    for (let gy = 0; gy < gh; gy++){
      const yc = Y0 + (gy + 0.5) * ch;
      for (let gx = 0; gx < gw; gx++){
        const xc = X0 + (gx + 0.5) * cw;
        let s = 0, n = 0;
        for (let dy = -2; dy <= 2; dy += 2)
          for (let dx = -2; dx <= 2; dx += 2){ s += lum(xc + dx, yc + dy); n++; }
        cell[gy * gw + gx] = s / n;
      }
    }
    const rows = [];
    for (let gy = 0; gy < gh; gy++){
      let line = '';
      for (let gx = 0; gx < gw; gx++){
        const i = gy * gw + gx;
        let acc = 0, nn = 0;
        for (let dy = -2; dy <= 2; dy++){
          for (let dx = -2; dx <= 2; dx++){
            const ny = gy + dy, nx = gx + dx;
            if (ny < 0 || ny >= gh || nx < 0 || nx >= gw) continue;
            acc += cell[ny * gw + nx]; nn++;
          }
        }
        const lo = acc / nn;
        const hp = cell[i] - lo;          // high-frequency residual
        if (hp > 0.012) line += '#';
        else if (hp > 0.006) line += '+';
        else if (hp < -0.008) line += ':';
        else line += '.';
      }
      rows.push(line);
    }
    return { rows, X0, X1, Y0, Y1 };
  };

  // ── caustic orientation: high-pass (box blur 7px) then gradient orientation histogram ──
  out.caustic = function(){
    const y0 = Math.floor(H * 0.05), y1 = Math.floor(H * 0.44);
    const bw = W, bh = y1 - y0;
    const R = 3;
    const lumF = new Float32Array(bw * bh);
    for (let y = 0; y < bh; y++) for (let x = 0; x < bw; x++) lumF[y * bw + x] = lum(x, y + y0);
    const tmp = new Float32Array(bw * bh);
    for (let y = 0; y < bh; y++){
      let acc = 0;
      for (let k = -R; k <= R; k++) acc += lumF[y * bw + ((k + bw) % bw)];
      for (let x = 0; x < bw; x++){
        tmp[y * bw + x] = acc / (2 * R + 1);
        const xa = (x - R + bw) % bw, xb = (x + R + 1) % bw;
        acc += lumF[y * bw + xb] - lumF[y * bw + xa];
      }
    }
    for (let x = 0; x < bw; x++){
      let acc = 0;
      for (let k = -R; k <= R; k++) acc += tmp[Math.min(bh - 1, Math.max(0, k)) * bw + x];
      for (let y = 0; y < bh; y++){
        lumF[y * bw + x] = acc / (2 * R + 1);
        const ya = Math.max(0, y - R), yb = Math.min(bh - 1, y + R + 1);
        acc += tmp[yb * bw + x] - tmp[ya * bw + x];
      }
    }
    const bins = new Float32Array(18);
    let tot = 0;
    for (let y = 2; y < bh - 2; y++){
      for (let x = 2; x < bw - 2; x++){
        const gx = lumF[y * bw + x + 1] - lumF[y * bw + x - 1];
        const gy = lumF[y * bw + 1 + x] - lumF[y * bw - 1 + x];
        const g = Math.abs(gx) + Math.abs(gy);
        if (g < 0.0015) continue;
        let ang = Math.atan2(gy, gx) * 180 / Math.PI;
        if (ang < 0) ang += 180;
        bins[Math.min(17, Math.floor(ang / 10))] += g;
        tot += g;
      }
    }
    const frac = bins.map(v => v / tot);
    const horiz = frac[7] + frac[8] + frac[9];
    const vert  = frac[0] + frac[1] + frac[17];
    return { tot: Math.round(tot), bins: frac.map(v => Math.round(v * 1000) / 1000), horizFrac: Math.round(horiz * 1000) / 1000, vertFrac: Math.round(vert * 1000) / 1000 };
  };

  // ── offshore: per-peak ahead/behind profile at several offsets ──
  out.offdir = function(){
    const y0 = Math.floor(H * 0.06), y1 = Math.floor(H * 0.50);
    const rows = [];
    for (let y = y0; y < y1; y++){
      let mx = 0;
      for (let x = Math.floor(W * 0.2); x < Math.floor(W * 0.8); x++){ const v = lum(x, y); if (v > mx) mx = v; }
      rows.push(mx);
    }
    const rowMean = rows.reduce((a, b) => a + b, 0) / rows.length;
    const cands = [];
    for (let i = 3; i < rows.length - 3; i++){
      if (rows[i] > rowMean + 0.020 && rows[i] >= rows[i - 1] && rows[i] >= rows[i + 1] &&
          rows[i] >= rows[i - 2] && rows[i] >= rows[i + 2]){
        cands.push({ y: y0 + i, v: rows[i] });
      }
    }
    const peaks = [];
    for (const c of cands){
      const prev = peaks[peaks.length - 1];
      if (prev && c.y - prev.y < 14){ if (c.v > prev.v) peaks[peaks.length - 1] = c; }
      else peaks.push(c);
    }
    return peaks.map(pk => {
      const A = [], B = [];
      for (const d of [6, 12, 18, 24, 30]){
        const ya = pk.y + d, yb = pk.y - d;
        A.push(ya < y1 ? Math.round(rows[ya - y0] * 1000) / 1000 : null);
        B.push(yb >= y0 ? Math.round(rows[yb - y0] * 1000) / 1000 : null);
      }
      return { y: pk.y, v: Math.round(pk.v * 1000) / 1000, ahead: A, behind: B };
    });
  };

  // ── offshore wave disturbance: luminance profile around each bright band in open sea ──
  out.diag = function(){
    return {
      tiles: typeof foamTiles !== 'undefined' ? foamTiles.length : -1,
      foamTilesOk: !!(typeof foamTiles !== 'undefined' && foamTiles.length && foamTiles[0]),
      SEA_FOAM_TILES: typeof SEA_FOAM_TILES !== 'undefined' ? SEA_FOAM_TILES : -1,
      hasDrawDisturbance: typeof drawDisturbance === 'function',
      DISTURB_A: typeof DISTURB_A !== 'undefined' ? DISTURB_A : -1
    };
  };
  out.profile = function(){
    const y0 = Math.floor(H * 0.06), y1 = Math.floor(H * 0.54);
    const rows = [];
    for (let y = y0; y < y1; y++){
      let mx = 0;
      for (let x = Math.floor(W * 0.2); x < Math.floor(W * 0.8); x++){ const v = lum(x, y); if (v > mx) mx = v; }
      rows.push(Math.round(mx * 1000) / 1000);
    }
    return rows;
  };

  out.offshore = function(){
    const y0 = Math.floor(H * 0.06), y1 = Math.floor(H * 0.50);
    const rows = [];
    for (let y = y0; y < y1; y++){
      let mx = 0;
      for (let x = Math.floor(W * 0.2); x < Math.floor(W * 0.8); x++){ const v = lum(x, y); if (v > mx) mx = v; }
      rows.push(mx);
    }
    const rowMean = rows.reduce((a, b) => a + b, 0) / rows.length;
    const cands = [];
    for (let i = 3; i < rows.length - 3; i++){
      if (rows[i] > rowMean + 0.020 && rows[i] >= rows[i - 1] && rows[i] >= rows[i + 1] &&
          rows[i] >= rows[i - 2] && rows[i] >= rows[i + 2]){
        cands.push({ y: y0 + i, v: rows[i] });
      }
    }
    // de-dup: keep the strongest peak in any 14-row window
    const peaks = [];
    for (const c of cands){
      const prev = peaks[peaks.length - 1];
      if (prev && c.y - prev.y < 14){ if (c.v > prev.v) peaks[peaks.length - 1] = c; }
      else peaks.push(c);
    }
    const profs = peaks.map(pk => {
      const half = 34;
      let ahead = 0, behind = 0, nA = 0, nB = 0;
      for (let d = 3; d <= half; d++){
        const ya = pk.y + d, yb = pk.y - d;
        if (ya < y1){ ahead += rows[ya - y0]; nA++; }
        if (yb >= y0){ behind += rows[yb - y0]; nB++; }
      }
      return { y: pk.y, v: Math.round(pk.v * 1000) / 1000, ahead: Math.round((ahead / nA) * 1000) / 1000, behind: Math.round((behind / nB) * 1000) / 1000 };
    });
    return { rowMean: Math.round(rowMean * 1000) / 1000, peaks: profs };
  };

  return out;
})()
`;

async function measure(cdp, expr){
  return await evaluate(cdp, `(${ANALYZE}).${expr}`);
}

async function main(){
  const [,, cmd, a, b] = process.argv;
  runPrefix = cmd + '_' + (a || '0') + '_' + (b || '') + '_';
  const n = parseInt(a || '60', 10);
  const step = parseInt(b || '1', 10);
  mkdirSync(OUT, { recursive: true });
  const proc = await launch();
  const target = await getTarget();
  const cdp = await CDP.connect(target.webSocketDebuggerUrl);
  try {
    await setup(cdp);
    if (cmd === 'snap'){
      const costs = await drive(cdp, n);
      const name = await screenshot(cdp);
      const avg = (costs.reduce((x, y) => x + y, 0) / costs.length).toFixed(2);
      const mx = Math.max(...costs).toFixed(2);
      console.log(JSON.stringify({ name, avgMs: avg, maxMs: mx, frames: costs.length }));
    } else if (cmd === 'series'){
      const costs = [];
      const names = [];
      for (let k = 0; k < n; k += step){
        const c = await drive(cdp, step);
        costs.push(...c);
        names.push(await screenshot(cdp));
      }
      const avg = (costs.reduce((x, y) => x + y, 0) / costs.length).toFixed(2);
      console.log(JSON.stringify({ names, avgMs: avg, frames: costs.length }));
    } else if (cmd === 'waterline'){
      await drive(cdp, n);
      const wl = await waterline(cdp);
      console.log(JSON.stringify(wl));
    } else if (cmd === 'crop'){
      // crop <n> <x0f> <x1f> <y0f> <y1f> [scale]  (fractions of canvas W/H)
      const nf = parseInt(a || '0', 10);
      const x0f = parseFloat(b || '0'), x1f = parseFloat(process.argv[5] || '1');
      const y0f = parseFloat(process.argv[6] || '0.5'), y1f = parseFloat(process.argv[7] || '0.8');
      const scale = parseFloat(process.argv[8] || '1');
      const info = await evaluate(cdp, `({ W: document.getElementById('stage').width, H: document.getElementById('stage').height })`);
      await drive(cdp, nf);
      const name = await screenshot(cdp, {
        x: Math.floor(info.W * x0f), y: Math.floor(info.H * y0f),
        width: Math.max(2, Math.ceil(info.W * (x1f - x0f))),
        height: Math.max(2, Math.ceil(info.H * (y1f - y0f))),
        scale
      });
      console.log(JSON.stringify({ name, W: info.W, H: info.H }));
    } else if (cmd === 'scan'){
      // drive in chunks, sample mean waterline after each chunk
      const samples = [];
      const costs = [];
      const chunk = parseInt(b || '4', 10);
      for (let k = 0; k < n; k += chunk){
        costs.push(...await drive(cdp, chunk));
        const wl = await waterline(cdp);
        const valid = wl.wl.filter(v => v >= 0);
        const mean = valid.length ? valid.reduce((x, y) => x + y, 0) / valid.length : -1;
        samples.push(Math.round(mean * 10) / 10);
      }
      console.log(JSON.stringify({ samples, avgMs: (costs.reduce((x, y) => x + y, 0) / costs.length).toFixed(2) }));
    } else if (cmd === 'meas'){
      // meas <what> <n> — drive n frames, run one ANALYZE function
      await drive(cdp, parseInt(a || '0', 10));
      const res = await measure(cdp, b);
      console.log(JSON.stringify(res));
    } else if (cmd === 'offscan'){
      // sample offshore asymmetry across n frames; strongest-band ahead/behind per sample
      const samples = [];
      const chunk = parseInt(b || '4', 10);
      for (let k = 0; k < n; k += chunk){
        await drive(cdp, chunk);
        const o = await measure(cdp, 'offshore()');
        let best = null;
        for (const p of o.peaks) if (!best || p.v > best.v) best = p;
        if (best) samples.push({ v: best.v, ratio: Math.round((best.ahead / Math.max(0.001, best.behind)) * 100) / 100 });
        else samples.push({ v: 0, ratio: 0 });
      }
      console.log(JSON.stringify({ samples, avgRatio: Math.round((samples.reduce((s, x) => s + (x.ratio || 0), 0) / Math.max(1, samples.length)) * 100) / 100 }));
    } else if (cmd === 'profscan'){
      // sweep: drive in chunks, record per-frame offshore rowMax profile (y0..y1 at 4px stride)
      // usage: profscan <totalFrames> <chunk>
      const samples = [];
      const chunk = parseInt(b || '8', 10);
      for (let k = 0; k < n; k += chunk){
        await drive(cdp, chunk);
        const p = await measure(cdp, 'profile()');
        // compact: every 4th row
        const slim = [];
        for (let i = 0; i < p.length; i += 4) slim.push(p[i]);
        samples.push({ f: k + chunk, p: slim });
      }
      console.log(JSON.stringify({ samples }));
    } else if (cmd === 'cost'){
      const costs = await drive(cdp, n);
      const avg = (costs.reduce((x, y) => x + y, 0) / costs.length).toFixed(3);
      const mx = Math.max(...costs).toFixed(3);
      console.log(JSON.stringify({ avgMs: avg, maxMs: mx, frames: costs.length }));
    }
  } finally {
    cdp.close();
    proc.kill();
  }
}

main().catch(e => { console.error('ERR', e.message); process.exit(1); });
