/* E43 海边 —— 帧驱动验证 harness（Node + vm，桩掉 DOM/canvas，跑**真实代码**）
 *
 * 目的：量化两项用户投诉，静态审查看不出来：
 *   1) 「海浪有些跳」       -> 每帧水线/浪锋的最大单帧位移（px）
 *   2) 「水线回退要看得见」 -> 每个周期的水线后退幅度(px)与可见时长(ms)
 *
 * 做法：vm 沙箱 + Proxy no-op ctx；rAF 桩捕获回调后手动逐帧喂时间戳；
 *      在 frame() 尾部注入探针，读闭包内的 swashStageNow / swashPos /
 *      shoreYs / waves / retreatT。
 * 不做软件光栅（本 harness 只测几何/时序，不测像素）。
 *
 * ⚠️ 本文件含中文注释：只能用 write/edit 工具改，⛔ 不可用 PowerShell 做
 *    文本往返（Get-Content -Raw | Set-Content 会按默认编码重写而损坏 UTF-8）。
 */
const fs = require('fs');
const vm = require('vm');
const path = require('path');

// 默认 HTML 路径：从 docs/archive/verification/scripts/ 上溯三级到 docs/
const HTML = process.argv[2] ||
  path.join(__dirname, '..', '..', '..', 'seaside-preview.html');
const SECONDS = Number(process.argv[3] || 90);
const DT_MS = 1000 / 60;
// 对照实验：第 4 个参数传 `solo` 则在闭包内打开单浪模式。
// 单浪模式**只应过滤绘制**，模拟状态轨迹必须与关闭时逐字相同。
const SOLO = process.argv[4] === 'solo';

const html = fs.readFileSync(HTML, 'utf8');
const m = html.match(/<script>([\s\S]*?)<\/script>/);
if (!m) { console.error('no inline <script>'); process.exit(2); }
let src = m[1];

// 注入探针：frame() 尾部的 rAF 之前抓状态。
// 行内调用是 2 空格缩进、bootstrap 是 0 空格 => 只匹配 frame() 尾部那一处。
const anchor = '  requestAnimationFrame(frame);';
if (src.split(anchor).length - 1 !== 1) {
  console.error('probe anchor not found exactly once');
  process.exit(3);
}
const probe = `
  // frame() 在 !started 时直接 return（等 gate 点击）。harness 无从点击，
  // 故在闭包内直接调 begin()——它就在同一作用域，可直接引用。
  if (!started) begin();
  if (__SOLO_ON__) soloWave = true;          // 对照实验：单浪模式只应影响绘制
  if (typeof __collect === 'function') {
    var ok = swashPos && shoreYs && swashPos.length > 0;
    __collect({
      ready: !!ok,
      stage: swashStageNow,
      adv: ok ? Array.from(swashPos) : null,
      ys:  ok ? Array.from(shoreYs) : null,
      retreatT: retreatT,
      rd: retreatDur,
      alive: (function(){ var n=0, st=[]; for (var i=0;i<WAVE_POOL;i++){ if(waves[i].state!==W_EMPTY){ n++; st.push(waves[i].state); } } return {n:n, st:st}; })(),
      // 逐浪：槽位/state/y/aliveT/fade/serial/**swashT** —— 定位退水交接跳变
      wv: (function(){ var a=[]; for (var i=0;i<WAVE_POOL;i++){ var w=waves[i]; if(w.state!==W_EMPTY) a.push([i, w.state, w.y, w.aliveT, waveFade(w), w.serial, w.swashT]); } return a; })(),
      leadIdx: (function(){ var L=leadWave(); if(!L) return -1; for (var i=0;i<WAVE_POOL;i++) if(waves[i]===L) return i; return -2; })()
    });
  }
`;
src = src.replace(anchor, probe + anchor);

// ── 第二个探针：drawSwellBands 循环体末尾，记录**每条浪实际被怎么画的** ──
//    owner 反馈「关掉单浪模式白色浪花就没了」「第二条浪走到中间就消失」——
//    需要事实而不是猜测：kA / amp / fade / farLaw / slopeLaw 随 adv 的变化。
// 探针放在 **cull 之前**（紧跟 fade 计算）：否则被 cull 的浪不会留下任何记录，
// 看起来就像「循环体从没执行」，掩盖真正的 cull 原因。
const drawAnchor = '    const fade = waveFade(w);';
if (src.split(drawAnchor).length - 1 !== 1) {
  console.error('drawAnchor count = ' + (src.split(drawAnchor).length - 1) + ' (expected 1)');
  process.exit(4);
}
const drawProbe = `
    if (typeof __waveDraw === 'function') __waveDraw({ ser: w.serial, lead: isLead, adv: adv,
      amp: amp, fade: fade, wi: wi, st: w.state, lane: lane, v: w.v, y: w.y });
`;
// ⚠️ 必须放在 `const fade = ...` **之后**：探针引用了 fade，放在前面会踩 TDZ
//    （Cannot access 'fade' before initialization）→ 帧循环被打断 →
//    仪表本身坏掉，输出「浪数塌成 1」这种假结论。
src = src.replace(drawAnchor, drawAnchor + '\n' + drawProbe);
const drawLog = [];
let drawLoopEntries = 0;
function recordDraw(d){ drawLog.push(Object.assign({ t: tNow }, d)); }

// ── 几何探针：必须放在**逐列循环之后** ────────────────────────────────────
//    bfy[] 是每条浪在逐列循环里逐列写入的共享数组。前面两个探针都挂在
//    `const fade` / `kA` 上 —— 那是逐列循环**之前**，读到的 bfy[48] 还是
//    上一帧领头浪留下的值 ⇒ 量出「间距恒 0.5px」这种假结果（= 水线自己）。
const geoAnchor = '  drawFoamLace(lane, W0, kA * boost, t, dir);';
if (src.split(geoAnchor).length - 1 !== 1) {
  console.error('geoAnchor count = ' + (src.split(geoAnchor).length - 1) + ' (expected 1)');
  process.exit(7);
}
const geoProbe = `
  if (typeof __geoDraw === 'function') __geoDraw({ ser: w.serial, lead: isLead, adv: adv,
    bfy: bfy[48], shore: shoreYs[48], W0: W0, sl: slopeLaw[48], fl: farLaw[48],
    bwj: bwj[48], rag: rag[48], fray: fray[48], d: bfy[48] - shoreYs[48], wi: wi,
    sandTop: sandTexTop,
    stage: swashStageNow, retreating: (retreatT >= 0 && retreatT < retreatDur),
    kA: kA, boost: boost, dir: dir });
`;
src = src.replace(geoAnchor, geoAnchor + '\n' + geoProbe);
const geoLog = [];
function recordGeo(d){ geoLog.push(d); }

// ── 第四个探针：kA 计算**之后** ────────────────────────────────────────────
//    问题 1「关掉单浪模式白沫就没了」的真正判据是外海泡沫/扰动函数内部的
//    `if (kA < 0.12) return` —— 上面那个探针在 kA 之前，抓不到它。
// ⚠️ 锚点必须落在**完整语句之后**。kA 现在是跨两行的三元表达式，锚在第一行
//    会把 `if (probe)` 插进 `: clamp(...)` 之前 —— 语句被劈开，直接语法错误。
const kAnchor = '                      : clamp(amp * 0.55 * fade, 0, 1);';
if (src.split(kAnchor).length - 1 !== 1) {
  console.error('kAnchor count = ' + (src.split(kAnchor).length - 1) + ' (expected 1)');
  process.exit(6);
}
const kProbe = `
    if (typeof __kDraw === 'function') __kDraw({ ser: w.serial, lead: isLead, adv: adv, kA: kA,
      bfy: bfy[48], shore: shoreYs[48] });
`;
src = src.replace(kAnchor, kAnchor + '\n' + kProbe);
const kLog = [];
function recordK(d){ kLog.push(d); }

// ── 第三个探针：spawnWave 内部，记录 T / front 的输入 ──────────────────────
const spawnAnchor = '  w.v      = 1 / T;';
if (src.split(spawnAnchor).length - 1 !== 1) {
  console.error('spawnAnchor count = ' + (src.split(spawnAnchor).length - 1) + ' (expected 1)');
  process.exit(5);
}
const spawnProbe = `
  if (typeof __spawnLog === 'function') __spawnLog({ ser: w.serial, T: T, v: w.v,
    pool: (function(){ var a = []; for (var i = 0; i < WAVE_POOL; i++) a.push(waves[i].state + ':' + waves[i].v.toExponential(1)); return a.join(' '); })() });
`;
src = src.replace(spawnAnchor, spawnAnchor + '\n' + spawnProbe);
const spawnLog = [];
function recordSpawn(d){ if (spawnLog.length < 40) spawnLog.push(Object.assign({ t: tNow }, d)); }

// ── canvas / DOM 桩 ───────────────────────────────────────────────────────
const NOOP = () => {};
function makeCtx() {
  const grad = { addColorStop: NOOP };
  const target = {
    canvas: null,
    save: NOOP, restore: NOOP, beginPath: NOOP, closePath: NOOP,
    moveTo: NOOP, lineTo: NOOP, quadraticCurveTo: NOOP, bezierCurveTo: NOOP,
    arc: NOOP, arcTo: NOOP, rect: NOOP, clip: NOOP, fill: NOOP, stroke: NOOP,
    fillRect: NOOP, strokeRect: NOOP, clearRect: NOOP,
    translate: NOOP, scale: NOOP, rotate: NOOP, setTransform: NOOP,
    resetTransform: NOOP, drawImage: NOOP, putImageData: NOOP,
    createLinearGradient: () => grad,
    createRadialGradient: () => grad,
    createPattern: () => null,
    getImageData: (x, y, w, h) => ({ data: new Uint8ClampedArray(Math.max(4, w * h * 4)), width: w, height: h }),
    createImageData: (w, h) => ({ data: new Uint8ClampedArray(Math.max(4, w * h * 4)), width: w, height: h }),
    measureText: () => ({ width: 10 }),
  };
  return new Proxy(target, {
    get(t, k) { if (k in t) return t[k]; return NOOP; },
    set(t, k, v) { t[k] = v; return true; },
  });
}
function makeCanvas() {
  const c = { width: 1600, height: 900, style: {}, nodeType: 1,
              clientWidth: 1600, clientHeight: 900,
              getBoundingClientRect: () => ({ left: 0, top: 0, width: 1600, height: 900 }),
              getContext: () => c._ctx, addEventListener: NOOP, removeEventListener: NOOP };
  c._ctx = makeCtx(); c._ctx.canvas = c;
  return c;
}
function inert() {
  const style = new Proxy({ setProperty: NOOP, removeProperty: NOOP, getPropertyValue: () => '' },
                          { get: (t, k) => (k in t ? t[k] : ''), set: () => true });
  return { style, textContent: '', innerHTML: '',
           classList: { add: NOOP, remove: NOOP, toggle: NOOP, contains: () => false },
           appendChild: NOOP, removeChild: NOOP, addEventListener: NOOP, removeEventListener: NOOP,
           getAttribute: () => null, setAttribute: NOOP, querySelectorAll: () => [] };
}

const samples = [];
let rafCb = null;
let tNow = 0;
const mainCanvas = makeCanvas();

const win = {
  innerWidth: 1600, innerHeight: 900, devicePixelRatio: 1,
  addEventListener: NOOP, removeEventListener: NOOP,
  matchMedia: () => ({ matches: false, addListener: NOOP, removeListener: NOOP,
                       addEventListener: NOOP, removeEventListener: NOOP }),
  requestAnimationFrame: (cb) => { if (!rafCb) rafCb = cb; return 1; },
  cancelAnimationFrame: NOOP,
  performance: { now: () => tNow },
  setTimeout: NOOP, clearTimeout: NOOP, setInterval: NOOP, clearInterval: NOOP,
};

const sandbox = {
  window: win,
  document: {
    createElement: (tag) => (tag === 'canvas' ? makeCanvas() : inert()),
    getElementById: (id) => (id === 'stage' ? mainCanvas : inert()),
    querySelector: () => inert(), querySelectorAll: () => [], addEventListener: NOOP,
    body: inert(), documentElement: inert(),
  },
  navigator: { userAgent: 'node', maxTouchPoints: 0,
               mediaDevices: { getUserMedia: () => Promise.reject(new Error('no mic')) } },
  location: { href: 'http://x/', protocol: 'http:' },
  performance: win.performance,
  requestAnimationFrame: win.requestAnimationFrame,
  cancelAnimationFrame: NOOP, setTimeout: NOOP, clearTimeout: NOOP,
  setInterval: NOOP, clearInterval: NOOP, console,
  // 页面直接调裸全局（不带 window. 前缀）
  addEventListener: NOOP, removeEventListener: NOOP, dispatchEvent: () => true,
  innerWidth: 1600, innerHeight: 900, devicePixelRatio: 1,
  alert: NOOP, confirm: () => true, prompt: () => null,
  Math, JSON, Date, Array, Object, String, Number, Boolean,
  Float32Array, Uint8Array, Uint8ClampedArray, Int32Array, Uint32Array,
  Map, Set, WeakMap, Error, isNaN, isFinite, parseFloat, parseInt,
  Infinity, NaN, undefined,
  // 页面用 Path2D 构造浪形路径；harness 只关心时序/几何，不关心像素。
  // 用 Proxy 兜底任意方法名（quadraticCurveTo/arcTo/rect/... 页面用到哪些都有）。
  Path2D: new Proxy(function Path2D() {}, {
    construct() { return new Proxy({}, { get: () => NOOP }); },
    get() { return NOOP; },
  }),
  __SOLO_ON__: SOLO,
  __waveDraw: (d) => recordDraw(d),
  __kDraw: (d) => recordK(d),
  __geoDraw: (d) => recordGeo(d),
  __spawnLog: (d) => recordSpawn(d),
  __collect: (s) => samples.push({ t: tNow, ready: s.ready, stage: s.stage,
                                   adv: s.adv, ys: s.ys, retreatT: s.retreatT,
                                   alive: s.alive, wv: s.wv, leadIdx: s.leadIdx }),
};
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

// ── 运行 + 逐帧驱动 ───────────────────────────────────────────────────────
try { vm.runInContext(src, sandbox, { filename: 'seaside.js' }); }
catch (e) { console.error('RUNTIME ERROR at load: ' + e.message); process.exit(1); }
if (!rafCb) { console.error('rAF callback never captured'); process.exit(1); }

const wall0 = Date.now();
const N = Math.round(SECONDS * 1000 / DT_MS);
let ran = 0;
for (let n = 0; n < N; n++) {
  tNow = n * DT_MS;
  try { rafCb(tNow); ran++; }
  catch (e) { console.error('frame ' + n + ' threw: ' + e.message); break; }
}
const wall = Date.now() - wall0;

// 只统计真正渲染了 shore 的帧（首帧 ensureShoreArrays() 前无数据）
const S = samples.filter(f => f.ready && f.adv && f.ys && f.ys.length);
if (S.length < 10) {
  console.error('only %d ready samples (of %d) — cannot conclude', S.length, samples.length);
  process.exit(1);
}

const mean = a => a.reduce((s, v) => s + v, 0) / a.length;
const H = 900;
const REACH_PX = 0.105 * H;      // SWASH_REACH 满程后退量

// 1) 逐帧跳变
let maxJumpPx = 0, jumpAt = null, jumpIdx = -1;
for (let i = 1; i < S.length; i++) {
  const d = Math.abs(mean(S[i].ys) - mean(S[i - 1].ys));
  if (d > maxJumpPx) { maxJumpPx = d; jumpAt = S[i].t; jumpIdx = i; }
}

// 1b) 定位那一帧的前后状态 —— 退水交接跳变的根因在这里
if (jumpIdx > 0) {
  const f = S[jumpIdx], p = S[jumpIdx - 1];
  console.log('');
  console.log('1b) 最大跳帧的前后状态 (jump=%s px)', maxJumpPx.toFixed(2));
  const dump = (tag, fr) => {
    const waves = fr.wv.map(w => 'ser' + w[5] + ':st' + w[1] + ':y' + w[2].toFixed(3) +
                                ':sw' + (typeof w[6] === 'number' ? w[6].toFixed(0) : '?')).join('  ');
    console.log('   %s t=%s ms  retreatT=%s/%s  stage=%s', tag, Math.round(fr.t),
      typeof fr.retreatT === 'number' ? fr.retreatT.toFixed(1) : '?',
      typeof fr.rd === 'number' ? fr.rd.toFixed(0) : '?', fr.stage);
    console.log('        meanY=%s  waves: %s', mean(fr.ys).toFixed(2), waves || '(none)');
  };
  dump('prev', p);
  dump('THIS', f);
  // 逐帧推进量（push = clamp(mean(swashPos))）在跳变前后各是多少
  console.log('        meanAdv(prev)=%s  meanAdv(this)=%s  Δ=%s',
    mean(p.adv).toFixed(4), mean(f.adv).toFixed(4),
    (mean(f.adv) - mean(p.adv)).toFixed(4));
}
let maxColJump = 0, colJumpAt = null;
for (let i = 1; i < S.length; i++) {
  const p = S[i - 1].ys, q = S[i].ys;
  for (let c = 0; c < Math.min(p.length, q.length); c++) {
    const d = Math.abs(q[c] - p[c]);
    if (d > maxColJump) { maxColJump = d; colJumpAt = S[i].t; }
  }
}
let maxAdvJump = 0;
for (let i = 1; i < S.length; i++) {
  let d = 0;
  for (let c = 0; c < S[i].adv.length; c++)
    d = Math.max(d, Math.abs(S[i].adv[c] - S[i - 1].adv[c]));
  if (d > maxAdvJump) maxAdvJump = d;
}

// 2) 退水可见性：stage===1 视为退水段，量每段的后退幅度与时长
const cycles = [];
let cur = null;
for (let i = 0; i < S.length; i++) {
  const f = S[i];
  if (f.stage === 1) {
    if (!cur) cur = { t0: f.t, t1: f.t, minAdv: 1, maxAdv: 0, len: 0 };
    cur.t1 = f.t; cur.len++;
    const a = mean(f.adv);
    if (a < cur.minAdv) cur.minAdv = a;
    if (a > cur.maxAdv) cur.maxAdv = a;
  } else if (cur) { cycles.push(cur); cur = null; }
}
if (cur) cycles.push(cur);

const durs = cycles.map(c => c.t1 - c.t0).sort((a, b) => a - b);
const drops = cycles.map(c => (c.maxAdv - c.minAdv) * REACH_PX).sort((a, b) => a - b);
const pick = (arr, q) => arr.length ? arr[Math.min(arr.length - 1, Math.floor(arr.length * q))] : NaN;

const stageHist = {}, aliveHist = {};
S.forEach(f => { stageHist[f.stage] = (stageHist[f.stage] || 0) + 1; });
S.forEach(f => { aliveHist[f.alive.n] = (aliveHist[f.alive.n] || 0) + 1; });

console.log('=== E43 seaside frame harness (real code, stubbed canvas) ===');
console.log('frames run         : %d   probe samples: %d   wall %d ms (%.2f ms/frame)', ran, S.length, wall, wall / Math.max(1, ran));
console.log('');
console.log('1) 跳变  (owner: 海浪有些跳)');
console.log('   max |delta| waterline mean y / frame : %s px  @ t=%s ms',
  maxJumpPx.toFixed(2), jumpAt);
console.log('   max |delta| any column y     / frame : %s px  @ t=%s ms',
  maxColJump.toFixed(2), colJumpAt);
console.log('   max |delta| per-column adv    / frame : %s', maxAdvJump.toFixed(4));
console.log('   dt = %s ms   (verdict: >8px/frame reads as a jump)', DT_MS.toFixed(2));
console.log('');
console.log('2) 退水可见性  (owner: 水要回退)');
console.log('   stage histogram  : %s   (0=uprush/beach  1=retreat  2=exposed+drying)', JSON.stringify(stageHist));
console.log('   retreat segments : %d over %ss', cycles.length, (S.length * DT_MS / 1000).toFixed(0));
console.log('   duration  ms     : min %s / median %s / max %s',
  pick(durs, 0).toFixed(0), pick(durs, 0.5).toFixed(0), pick(durs, 0.99).toFixed(0));
console.log('   drop      px     : min %s / median %s / max %s   (full travel = %s px)',
  pick(drops, 0).toFixed(1), pick(drops, 0.5).toFixed(1), pick(drops, 0.99).toFixed(1), REACH_PX);
console.log('');
console.log('3) 海面浪数  (owner: 1 条排队 + 1 条在最前)');
console.log('   alive histogram : %s', JSON.stringify(aliveHist));
console.log('');

// 4) 闪烁诊断（owner: 前浪白色浪花一直存在，现在老是闪烁）
//    两个候选成因：领头浪身份逐帧跳变（泡沫剖面随之整帧切换）、
//    或某条浪的 y 在帧间高频抖动。
let leadSwitches = 0, leadFrames = 0, prevLead = null;
const slotY = {};                       // 槽位 -> 最近一次 y
let maxSlotJump = 0, maxSlotJumpAt = null;
for (let i = 1; i < S.length; i++) {
  const a = S[i - 1], b = S[i];
  if (a.leadIdx !== b.leadIdx) leadSwitches++;
  prevLead = b.leadIdx;
  // 逐槽位比较 y（同一槽位在相邻帧都在场时才可比）
  const prev = {};
  a.wv.forEach(w => { prev[w[0]] = w; });
  b.wv.forEach(w => {
    const p = prev[w[0]];
    if (!p) return;
    const d = Math.abs(w[2] - p[2]);
    if (d > maxSlotJump) { maxSlotJump = d; maxSlotJumpAt = b.t; }
  });
}
leadFrames = S.length;
// 领头浪在位期间（非 -1）的帧数
const leadPresent = S.filter(s => s.leadIdx >= 0).length;
console.log('4) 闪烁诊断  (owner: 前浪白色一直存在，现在老是闪烁)');
console.log('   lead-wave identity switches : %d 次 / %d 帧 (%.2f Hz)  — 泡沫剖面随之整帧切换',
  leadSwitches, leadFrames, leadSwitches / (S.length * DT_MS / 1000));
console.log('   lead present frames         : %d / %d', leadPresent, leadFrames);
console.log('   max per-slot |delta| y / frame : %s  @ t=%s ms', maxSlotJump.toFixed(4), maxSlotJumpAt);
console.log('   (expected v*dt = ~%.4f for v=1/5000ms; 值远大于它 => y 在抖)',
  (1 / 5000 * DT_MS).toFixed(4));
// 泡沫可见度 waveFade 的帧间最大跳变（最直接的「白闪一下」指标）
let maxFadeJump = 0, fadeJumpAt = null;
for (let i = 1; i < S.length; i++) {
  const prev = {};
  S[i - 1].wv.forEach(w => { prev[w[0]] = w; });
  S[i].wv.forEach(w => {
    const p = prev[w[0]];
    if (!p) return;
    const d = Math.abs(w[4] - p[4]);
    if (d > maxFadeJump) { maxFadeJump = d; fadeJumpAt = S[i].t; }
  });
}
console.log('   max per-slot |delta| waveFade / frame : %s  @ t=%s ms  (0 = 白色强度连续)',
  maxFadeJump.toFixed(4), fadeJumpAt);
console.log('');
if (cycles.length) {
  const show = cycles.slice(0, 5).map(c => ({
    durMs: Math.round(c.t1 - c.t0),
    dropPx: Math.round((c.maxAdv - c.minAdv) * REACH_PX),
  }));
  console.log('sample retreats   : %s', JSON.stringify(show));
}

// 5) 状态轨迹指纹 —— 用来证明「单浪模式只过滤绘制，不动模拟」。
//    soloWave 开/关跑同一份代码，这串指纹必须**逐字相同**。
let h = 2166136261;
const trace = S.map(f => f.wv.map(w => w[5] + ':' + w[1] + ':' + w[2].toFixed(9) + ':' + w[3].toFixed(6)).join(',') + '|' + f.stage + '|' + f.retreatT.toFixed(6)).join(';');
for (let i = 0; i < trace.length; i++) {
  const s = trace[i];
  for (let j = 0; j < s.length; j++) { h ^= s.charCodeAt(j); h = Math.imul(h, 16777619); }
}
console.log('');
console.log('5) 泳道稳定性（lane 变了 = 那条浪的 fray/tears/蕾丝/贴图整套噪声换了一套）');
if (!drawLog.length) {
  console.log('   (no draw records)');
} else {
  const laneSer = {};
  drawLog.forEach(d => { (laneSer[d.ser] = laneSer[d.ser] || []).push(d.lane); });
  let flipTotal = 0, wavesWithFlip = 0;
  const samples = [];
  Object.keys(laneSer).forEach(s => {
    const a = laneSer[s];
    let flips = 0;
    for (let i = 1; i < a.length; i++) if (a[i] !== a[i - 1]) flips++;
    if (flips) { wavesWithFlip++; flipTotal += flips; if (samples.length < 6) samples.push({ s, flips, seq: a.slice(0, 12) }); }
  });
  console.log('   波总数 %d   出现过 lane 跳变的波 %d   跳变总次数 %d',
    Object.keys(laneSer).length, wavesWithFlip, flipTotal);
  samples.forEach(x => console.log('     ser %s flips=%s  lane 序列 %s', x.s, x.flips, x.seq.join(',')));
  console.log('   => %s', flipTotal === 0
    ? '每条浪的 lane 全程固定 —— 排除泳道跳变'
    : '⚠️ lane 会变 ⇒ 该浪的泡沫噪声实现中途切换 ⇒ 画面跳变');

  // 5b) amp 里仍然泄漏着绘制次序 wi（非领头浪读 bandAmp(1 + (wi % 2))）。
  //     wi 随别的浪出生/回收而变 ⇒ 换频段 ⇒ amp/W0/kA 跳。同一类缺陷。
  console.log('');
  console.log('5b) amp 的绘制次序泄漏（wi 变了 ⇒ 换频段 ⇒ amp/W0/kA 跳）');
  if (!drawLog.length) {
    console.log('   (no draw records)');
  } else {
    const wiSer = {};
    drawLog.forEach(d => { (wiSer[d.ser] = wiSer[d.ser] || []).push(d); });
    let wiFlips = 0, ampJumpAtWi = 0, worstAmpJump = 0, wavesWithWi = 0;
    const samp = [];
    Object.keys(wiSer).forEach(s => {
      const a = wiSer[s];
      let flips = 0;
      for (let i = 1; i < a.length; i++) {
        if (a[i].wi !== a[i - 1].wi) {
          flips++; wiFlips++;
          const d = Math.abs(a[i].amp - a[i - 1].amp);
          if (d > worstAmpJump) { worstAmpJump = d; ampJumpAtWi = s; }
        }
      }
      if (flips) { wavesWithWi++; if (samp.length < 5) samp.push({ s, flips, wi: Array.from(new Set(a.map(d => d.wi))) }); }
    });
    console.log('   波总数 %d   wi 发生过变化的波 %d   wi 变化总次数 %d',
      Object.keys(wiSer).length, wavesWithWi, wiFlips);
    console.log('   wi 变化时 amp 的最大跳变 : %s (ser %s)', worstAmpJump.toFixed(4), ampJumpAtWi);
    samp.forEach(x => console.log('     ser %s wi 变化 %s 次, 取值集合 %s', x.s, x.flips, x.wi.join(',')));
    console.log('   => %s', wiFlips === 0
      ? 'wi 全程固定 —— 排除这一条'
      : '⚠️ wi 会变 ⇒ amp/W0/kA 中途跳变 ⇒ 画面跳变');
  }

  // 5d) 沙子纹理是否覆盖水线的完整摆动范围（owner：「露出海水底色」「退的都是沙色」）
  console.log('');
  console.log('5d) 沙纹理上沿 vs 水线摆动范围（#2 与「退水停住」的共同根因）');
  if (!geoLog.length) {
    console.log('   (no geometry records)');
  } else {
    const shores = geoLog.filter(d => typeof d.shore === 'number').map(d => d.shore);
    const sandTop = geoLog.find(d => typeof d.sandTop === 'number').sandTop;
    const sMin = Math.min(...shores), sMax = Math.max(...shores);
    console.log('   H=%d  sandTexTop=%s px  (SAND_TEX_TOP=%s)',
      H, sandTop.toFixed(1), (sandTop / H).toFixed(3));
    console.log('   水线 shoreYs 实际范围 : %s .. %s px', sMin.toFixed(1), sMax.toFixed(1));
    console.log('   纹理是否覆盖最小 y   : %s (需 sandTexTop <= %s)',
      sandTop <= sMin ? 'YES ✔' : 'NO ✘ 差 ' + (sandTop - sMin).toFixed(1) + 'px', sMin.toFixed(1));
  }

  // 5c) 关键量：wi 变化那一帧，**带宽 bwj** 跳了多少。修复前 env 的 hash 种子
  //     含 wi（8100 + wi*53）⇒ wi 翻转时 env 换成完全不同的噪声实现 ⇒ bwj 整片换掉。
  console.log('');
  console.log('5c) wi 变化帧的带宽跳变（bwj）—— 修复前这里是主因');
  if (!geoLog.length || typeof geoLog[0].wi === 'undefined') {
    console.log('   (geo probe lacks wi)');
  } else {
    const g2 = {};
    geoLog.forEach(d => { (g2[d.ser] = g2[d.ser] || []).push(d); });
    let nWi = 0, sumBwj = 0, maxBwj = 0, sumK = 0, maxK = 0;
    Object.keys(g2).forEach(s => {
      const a = g2[s];
      for (let i = 1; i < a.length; i++) {
        if (a[i].wi === a[i - 1].wi) continue;
        if (typeof a[i].bwj !== 'number' || typeof a[i - 1].bwj !== 'number') continue;
        const d = Math.abs(a[i].bwj - a[i - 1].bwj);
        const dk = Math.abs(a[i].kA - a[i - 1].kA);
        nWi++; sumBwj += d; if (d > maxBwj) maxBwj = d;
        sumK += dk; if (dk > maxK) maxK = dk;
      }
    });
    console.log('   wi 变化帧数 %d   |delta| bwj: 均值 %s / 最大 %s   |delta| kA: 均值 %s / 最大 %s',
      nWi, nWi ? (sumBwj / nWi).toFixed(4) : '-', maxBwj.toFixed(4),
      nWi ? (sumK / nWi).toFixed(4) : '-', maxK.toFixed(4));
    console.log('   => %s', maxBwj < 0.02
      ? 'wi 变化不再引起带宽跳变 ✔'
      : '⚠️ 带宽仍在跳');
  }
}
console.log('   frames hashed : %d', S.length);
console.log('   trace hash    : %s >>> 0x%s', h >>> 0, (h >>> 0).toString(16).padStart(8, '0'));

// 6) 绘制侧诊断（探针在 cull 之前）—— 为什么「关掉单浪模式白沫就没了」
console.log('');
console.log('6) 发浪日志（前 6 条）—— T 是算出来的行程，front 是它据以抬高 T 的那条浪');
if (!spawnLog.length) console.log('   (no spawns at all!)');
spawnLog.slice(0, 6).forEach(s => {
  console.log('   t=%s ser=%s T=%s v=%s front=%s', String(Math.round(s.t)).padStart(6),
    String(s.ser).padStart(3), s.T === Infinity ? 'Infinity' : s.T.toFixed(0),
    s.v === 0 ? '0' : s.v.toExponential(2),
    s.front ? `ser${s.front.ser} y=${s.front.y.toFixed(3)} v=${s.front.v.toExponential(1)}` : 'null');
});
if (spawnLog.length) {
  console.log('   pool states @last spawn: %s', spawnLog[spawnLog.length - 1].pool);
  const bad = spawnLog.filter(s => !isFinite(s.T)).length;
  console.log('   spawns with non-finite T : %d / %d %s', bad, spawnLog.length,
    bad ? '  <-- 行程为 Infinity ⇒ v=0 ⇒ 浪永远不动' : '');
}

console.log('');
console.log('7) 绘制侧：领头浪 vs 第二条浪（按 adv 分桶，solo=%s）', SOLO ? 'ON' : 'off');
const SILENCE_FLOOR = 0.012;      // 与 HTML 一致：amp 的 cull 阈值
const FADE_CULL = 0.02;           // 与 HTML 一致：fade 的 cull 阈值
if (!drawLog.length) {
  console.log('   (no draw records — drawSwellBands 的循环体从未进入)');
} else {
  const BUCKETS = [0, .2, .4, .6, .8, 1.01];
  console.log('   adv%    who    n     amp    fade   culled%   lane');
  for (let b = 0; b < BUCKETS.length - 1; b++) {
    const lo = BUCKETS[b], hi = BUCKETS[b + 1];
    for (const lead of [true, false]) {
      const sel = drawLog.filter(d => d.lead === lead && d.adv >= lo && d.adv < hi);
      const tag = `${(lo * 100) | 0}-${((hi * 100) | 0) | 0}`;
      if (!sel.length) { console.log('   %s  %s  %s', tag.padStart(5), (lead ? 'lead' : '2nd').padEnd(5), '-'); continue; }
      const avg = f => sel.reduce((s, d) => s + f(d), 0) / sel.length;
      const culled = sel.filter(d => d.amp <= SILENCE_FLOOR || d.fade <= FADE_CULL).length;
      const lanes = Array.from(new Set(sel.map(d => d.lane))).sort().join(',');
      console.log('   %s  %s  %s  %s  %s  %s  %s', tag.padStart(5), (lead ? 'lead' : '2nd').padEnd(5),
        String(sel.length).padStart(5), avg(d => d.amp).toFixed(3).padStart(6),
        avg(d => d.fade).toFixed(3).padStart(6),
        ((100 * culled / sel.length).toFixed(1) + '%').padStart(7), '  ' + lanes);
    }
  }
  // 每条浪的 adv 覆盖：验证「第二条浪能不能走到沙滩」
  const bySer = {};
  drawLog.forEach(d => {
    const e = bySer[d.ser] = bySer[d.ser] || { maxAdv: 0, lead: d.lead, n: 0, v: d.v, y: d.y };
    e.maxAdv = Math.max(e.maxAdv, d.adv); e.n++; e.v = d.v; e.y = d.y;
  });
  const advs = Object.values(bySer).map(v => v.maxAdv);
  const secondWave = Object.entries(bySer).filter(([, v]) => !v.lead).map(([, v]) => v.maxAdv);
  console.log('');
  console.log('   waves observed: %d', Object.keys(bySer).length);
  Object.entries(bySer).forEach(([ser, v]) => {
    console.log('     ser %s  %s  n=%s  maxAdv=%s  y=%s  v=%s  (1/v=%s ms)',
      String(ser).padStart(3), (v.lead ? 'lead' : '2nd ').padEnd(5), String(v.n).padStart(4),
      v.maxAdv.toFixed(3), v.y.toFixed(4), v.v.toExponential(3),
      v.v > 0 ? Math.round(1 / v.v) : 'Infinity');
  });
  console.log('   2nd-wave maxAdv (0=从未画, 1=到达滩上): %s',
    secondWave.length ? secondWave.map(v => v.toFixed(2)).join(', ') : '(none)');
  console.log('   -> %s', secondWave.length && Math.max(...secondWave) >= 0.99
    ? '第二条浪能走到滩上 OK'
    : (secondWave.length ? '第二条浪到不了滩上 !!' : '只有一条浪在场（后浪从未出生）'));

  // 8) 问题 1 的真正判据：外海泡沫 / 扰动函数内部的 `if (kA < 0.12) return`
  console.log('');
  const CULL_KA = 0.12;
  console.log('8) kA < %s 的比例（外海泡沫 drawOpenSeaFoam / 扰动 drawDisturbance 的下限）', CULL_KA);
  if (!kLog.length) {
    console.log('   (no kA records)');
  } else {
    const B2 = [0, .2, .4, .6, .8, 1.01];
    console.log('   adv%    who      n     kA    kAmin   below%');
    for (let b = 0; b < B2.length - 1; b++) {
      const lo = B2[b], hi = B2[b + 1];
      for (const lead of [true, false]) {
        const sel = kLog.filter(d => d.lead === lead && d.adv >= lo && d.adv < hi);
        const tag = `${(lo * 100) | 0}-${((hi * 100) | 0) | 0}`;
        if (!sel.length) { console.log('   %s  %s  %s', tag.padStart(5), (lead ? 'lead' : '2nd').padEnd(5), '-'); continue; }
        const avg = sel.reduce((s, d) => s + d.kA, 0) / sel.length;
        const below = sel.filter(d => d.kA < CULL_KA).length;
        console.log('   %s  %s  %s  %s  %s  %s', tag.padStart(5), (lead ? 'lead' : '2nd').padEnd(5),
          String(sel.length).padStart(5), avg.toFixed(3).padStart(6),
          Math.min(...sel.map(d => d.kA)).toFixed(3).padStart(6),
          ((100 * below / sel.length).toFixed(1) + '%').padStart(6));
      }
    }
    for (const lead of [false, true]) {
      const sel = kLog.filter(d => d.lead === lead);
      if (!sel.length) continue;
      const below = sel.filter(d => d.kA < CULL_KA).length;
      console.log('   TOTAL %s : %d / %d below threshold (%s%%)',
        lead ? 'lead' : '2nd ', below, sel.length,
        (100 * below / sel.length).toFixed(1));
    }

    // 9) 「第二条浪靠近前浪时突然消失」——逐浪看 kA / lead 身份在行程末段的连续性。
    //    若 kA 连续 ⇒ 消失不是绘制强度问题，而是**被后画的贴岸层盖住**（遮挡）。
    console.log('');
    console.log('9) 逐浪 kA 连续性（找行程末段的跳变）');
    const byS = {};
    kLog.forEach(d => { (byS[d.ser] = byS[d.ser] || []).push(d); });
    let worstOverall = { jump: 0 };
    Object.keys(byS).forEach(s => {
      const arr = byS[s];
      if (arr.length < 20) return;
      for (let i = 1; i < arr.length; i++) {
        const j = Math.abs(arr[i].kA - arr[i - 1].kA);
        if (j > worstOverall.jump) {
          worstOverall = { jump: j, ser: s, adv: arr[i].adv, kA: arr[i].kA,
                           prev: arr[i - 1].kA, switched: arr[i].lead !== arr[i - 1].lead };
        }
      }
    });
    console.log('   worst per-frame kA jump: %s  (ser %s, adv %s, %s -> %s%s)',
      worstOverall.jump.toFixed(3), worstOverall.ser, (worstOverall.adv || 0).toFixed(3),
      (worstOverall.prev || 0).toFixed(3), (worstOverall.kA || 0).toFixed(3),
      worstOverall.switched ? ', 伴随 lead 身份切换' : '');
    // lead 身份切换次数（切换 = 绘制路径整体改变：boost/dir/ladder/bfy 来源）
    let switches = 0;
    Object.keys(byS).forEach(s => {
      const arr = byS[s];
      for (let i = 1; i < arr.length; i++) if (arr[i].lead !== arr[i - 1].lead) switches++;
    });
    console.log('   lead identity switches across all waves: %d', switches);
    // 非领头浪在接近滩上时的 kA（判断是否随 adv 衰减）
    const tail = kLog.filter(d => !d.lead && d.adv > 0.85);
    if (tail.length) {
      const avg = tail.reduce((s, d) => s + d.kA, 0) / tail.length;
      console.log('   non-lead kA at adv>0.85 : avg %s  min %s  (n=%d)',
        avg.toFixed(3), Math.min(...tail.map(d => d.kA)).toFixed(3), tail.length);
    } else {
      console.log('   non-lead kA at adv>0.85 : (no samples)');
    }

    // 10) 遮挡验证：第二条浪的前缘 y 与**水线** y 的间距。
    //     frame() 里 drawSwellBands 之后还画了 6 层贴岸物件（swashFingers /
    //     residualStreaks / wetLine / splash / ripples / residue）⇒ 若两者间距
    //     小于那几层的覆盖范围，第二条浪就是被盖住而不是被削弱。
    console.log('');
    console.log('10) 第二条浪前缘 vs 水线的间距（px）—— 用**逐列循环之后**的几何探针');
    const B3 = [0, .5, .8, .9, .95, 1.01];
    console.log('    adv%    n     gap_avg   gap_min');
    if (!geoLog.length) console.log('    (no geometry records)');
    for (let b = 0; b < B3.length - 1; b++) {
      const lo = B3[b], hi = B3[b + 1];
      const sel = geoLog.filter(d => !d.lead && d.adv >= lo && d.adv < hi);
      const tag = `${(lo * 100) | 0}-${((hi * 100) | 0) | 0}`;
      if (!sel.length) { console.log('    %s  %s', tag.padStart(5), '-'); continue; }
      const gaps = sel.map(d => Math.abs(d.shore - d.bfy));
      console.log('    %s  %s  %s  %s', tag.padStart(5), String(sel.length).padStart(5),
        (gaps.reduce((s, v) => s + v, 0) / gaps.length).toFixed(1).padStart(8),
        Math.min(...gaps).toFixed(1).padStart(8));
    }
    console.log('    (H = 900；浪带宽 SWELL_BAND_W 0.042h ≈ 38px，'
              + '贴岸各层覆盖范围约 ±20~40px)');

    // 12) 「靠近前浪时消失」的合并验证：order.sort 按 adv 升序 ⇒ **领头浪永远最后
    //     画**（adv=1 最大）。所以只要后浪的泡沫带与领头浪的带重叠，它就被盖在
    //     里面 —— 不是没被画，是失去可辨识性。
    //     d = bfy - shoreYs（带符号）：负 = 后浪在领头浪的海侧（正常），正 = 已越过。
    //     判据：|d| 明显小于两条带的半宽之和 ⇒ 两者重叠。
    console.log('');
    console.log('12) 后浪带与领头浪带的间距（带符号；|d| < 两条半宽之和 ⇒ 重叠合并）');
    if (!geoLog.length) console.log('    (no geometry records)');
    else {
      const nd2 = geoLog.filter(d => !d.lead && typeof d.d === 'number');
      const B5 = [0, .5, .8, .9, .95, 1.01];
      console.log('    adv%    n      d_avg    |d|_avg   |d|_min');
      for (let b = 0; b < B5.length - 1; b++) {
        const lo = B5[b], hi = B5[b + 1];
        const sel = nd2.filter(d => d.adv >= lo && d.adv < hi);
        const tag = `${(lo * 100) | 0}-${((hi * 100) | 0) | 0}`;
        if (!sel.length) { console.log('    ' + tag.padStart(5) + '  -'); continue; }
        const ds = sel.map(d => d.d), abs = ds.map(Math.abs);
        console.log('    ' + tag.padStart(5) + '  ' + String(sel.length).padEnd(6) +
          '  ' + (ds.reduce((s, v) => s + v, 0) / ds.length).toFixed(1).padStart(7) +
          '  ' + (abs.reduce((s, v) => s + v, 0) / abs.length).toFixed(1).padStart(8) +
          '  ' + Math.min(...abs).toFixed(1).padStart(8));
      }
      // 领头浪半宽 ~1.19 × W0，后浪半宽 ~0.35 × W0 ⇒ 重叠阈值 ≈ (1.19+0.35)×W0/2
      const W0s = nd2.map(d => d.W0).filter(v => typeof v === 'number');
      const W0m = W0s.length ? W0s.reduce((s, v) => s + v, 0) / W0s.length : 40;
      const thr = 0.5 * (1.19 + 0.35) * W0m;
      const near = nd2.filter(d => d.adv > 0.8);
      const ov = near.filter(d => Math.abs(d.d) < thr);
      console.log('    平均半宽和 ≈ ' + thr.toFixed(0) + 'px  (W0≈' + W0m.toFixed(0) + 'px)');
      console.log('    adv>0.8 的后浪帧中，重叠的: ' + ov.length + ' / ' + near.length +
        ' (' + (near.length ? (100 * ov.length / near.length).toFixed(1) : '0') + '%)');
    }

    // 13) owner：「前浪一开始回退，第二条浪就消失了，然后下一条浪就出现了」
    //     逐帧看**回退窗口内**后浪的 kA / bwj / 与水线的间距，以及场上浪数。
    console.log('');
    console.log('13) 回退窗口内（非领头浪）的状态');
    if (!geoLog.length || typeof geoLog[0].retreating === 'undefined') {
      console.log('    (geo probe lacks retreating flag)');
    } else {
      const retFrames = geoLog.filter(d => d.retreating);
      const nd = retFrames.filter(d => !d.lead);
      const ld = retFrames.filter(d => d.lead);
      console.log('    回退帧总数 ' + retFrames.length + '  其中 非领头浪 ' + nd.length +
        ' / 领头浪 ' + ld.length);
      if (nd.length) {
        const g = (arr, f) => arr.reduce((s, d) => s + f(d), 0) / arr.length;
        console.log('    非领头浪 @回退: kA %s (min %s)  bwj %s (min %s)  adv %s  |d| %s (min %s)',
          g(nd, d => d.kA).toFixed(3), Math.min(...nd.map(d => d.kA)).toFixed(3),
          g(nd, d => d.bwj).toFixed(3), Math.min(...nd.map(d => d.bwj)).toFixed(3),
          g(nd, d => d.adv).toFixed(3),
          g(nd, d => Math.abs(d.d)).toFixed(1), Math.min(...nd.map(d => Math.abs(d.d))).toFixed(1));
        // 有多少回退帧里非领头浪低于 kA 阈值
        const lowK = nd.filter(d => d.kA < 0.12).length;
        const lowB = nd.filter(d => d.bwj <= 0.01).length;
        console.log('    其中 kA<0.12 : %d (%s%%)   bwj<=0.01 : %d (%s%%)',
          lowK, (100 * lowK / nd.length).toFixed(1), lowB, (100 * lowB / nd.length).toFixed(1));
      } else {
        console.log('    ⛔ 回退窗口内**没有任何非领头浪被绘制** —— 后浪整段退潮期都不在场上');
      }

      // 14) 交接跳变：非领头浪 → 领头浪那一帧，它自己的前缘 bfy 会不会**瞬移**。
      //     因为 bfy 的两条来源完全不同：
      //       非领头浪 = waveFrontY(x, t, H*(SHORE_K − waveDepthK(w,adv)), …)
      //       领头浪   = shoreYs[i]（水线本身）
      //     若两者在同一 adv 上给出不同的 y，交接那一帧整条带就会「跳」。
      console.log('');
      console.log('14) 交接帧的前缘跳变（非领头浪 → 领头浪）');
      const bySer2 = {};
      geoLog.forEach(d => { (bySer2[d.ser] = bySer2[d.ser] || []).push(d); });
      const jumps = [];
      Object.keys(bySer2).forEach(s => {
        const a = bySer2[s];
        for (let i = 1; i < a.length; i++) {
          if (a[i - 1].lead === false && a[i].lead === true) {
            jumps.push({ ser: s, adv: a[i].adv, before: a[i - 1].bfy, after: a[i].bfy,
                         dBefore: a[i - 1].d, dAfter: a[i].d });
          }
        }
      });
      if (!jumps.length) {
        console.log('    (未捕获到交接事件)');
      } else {
        const deltas = jumps.map(j => Math.abs(j.after - j.before));
        const dd = jumps.map(j => Math.abs(j.dAfter - j.dBefore));
        const mx = Math.max(...deltas), md = deltas.reduce((s, v) => s + v, 0) / deltas.length;
        console.log('    交接事件 ' + jumps.length + ' 次');
        console.log('    |bfy 跳变| : 中位 ' + md.toFixed(1) + 'px  最大 ' + mx.toFixed(1) + 'px');
        console.log('    |d 跳变|   : 中位 ' + (dd.reduce((s, v) => s + v, 0) / dd.length).toFixed(1) + 'px');
        jumps.slice(0, 5).forEach(j => console.log('      ser ' + j.ser + ' adv ' + j.adv.toFixed(3) +
          '  bfy ' + j.before.toFixed(1) + ' -> ' + j.after.toFixed(1) +
          '  (d ' + j.dBefore.toFixed(1) + ' -> ' + j.dAfter.toFixed(1) + ')'));
      }
    }

    //     若 slopeLaw 局部趋 0 ⇒ bwj 塌成 0 ⇒ 整条带在一帧内消失（无宽度）。
    console.log('');
    console.log('11) 非领头浪的 slopeLaw / bwj（塌陷 ⇒ 整条带瞬间消失）');
    if (!geoLog.length) {
      console.log('    (no geometry records)');
    } else {
      const nd = geoLog.filter(d => !d.lead && typeof d.bwj === 'number');
      const sl = nd.map(d => d.sl).filter(v => typeof v === 'number');
      const bw = nd.map(d => d.bwj).filter(v => typeof v === 'number');
      const stat = (name, arr) => {
        if (!arr.length) { console.log('    %s : (none)', name); return; }
        const s = arr.slice().sort((a, b) => a - b);
        // ⚠️ Node 的 util.format **不支持** '-' 标志（%-5s 不会被替换，会整体错位）
        console.log('    ' + name.padEnd(8) + ' n=' + String(arr.length).padEnd(6) +
          ' min ' + s[0].toFixed(3) + '  p05 ' + s[Math.floor(s.length * 0.05)].toFixed(3) +
          '  median ' + s[Math.floor(s.length * 0.5)].toFixed(3) +
          '  max ' + s[s.length - 1].toFixed(3));
      };
      stat('slopeLaw', sl);
      stat('farLaw', nd.map(d => d.fl).filter(v => typeof v === 'number'));
      stat('bwj 2nd', bw);
      // 下限现在对**两条浪**都生效，所以领头浪也要看
      const ld = geoLog.filter(d => d.lead && typeof d.bwj === 'number');
      stat('bwj lead', ld.map(d => d.bwj));
      const ldDead = ld.filter(d => d.bwj <= 0.01).length;
      console.log('    lead  bwj <= 0.01 : %d / %d (%s%%)',
        ldDead, ld.length, ld.length ? (100 * ldDead / ld.length).toFixed(1) : '0');
      const collapsed = bw.filter(v => v < 1).length;
      const zero = bw.filter(v => v <= 0.01).length;
      console.log('    bwj < 1px : %d / %d (%s%%)   bwj <= 0.01px : %d (%s%%)',
        collapsed, bw.length, (100 * collapsed / bw.length).toFixed(1),
        zero, (100 * zero / bw.length).toFixed(1));
      const slZero = sl.filter(v => v < 0.05).length;
      console.log('    slopeLaw < 0.05 : %d / %d (%s%%)',
        slZero, sl.length, (100 * slZero / sl.length).toFixed(1));

      // bwj = (0.84..1.28) * rag[i] * (1 + fray*FRAY_WIDTH) —— 塌陷只能来自 rag
      // 或 fray。定位到具体因子，并看塌陷是否集中在「靠近前浪」的时段。
      const rag = nd.map(d => d.rag).filter(v => typeof v === 'number');
      const fr = nd.map(d => d.fray).filter(v => typeof v === 'number');
      stat('rag', rag);
      stat('fray', fr);
      console.log('');
      console.log('    塌陷定位：哪些因子在 bwj<=0.01 的帧上接近 0');
      const dead = nd.filter(d => typeof d.bwj === 'number' && d.bwj <= 0.01);
      const live = nd.filter(d => typeof d.bwj === 'number' && d.bwj > 0.01);
      const mean = (arr, f) => arr.length ? arr.reduce((s, d) => s + f(d), 0) / arr.length : NaN;
      console.log('      塌陷帧 n=%d : rag %s  fray %s  adv %s',
        dead.length, mean(dead, d => d.rag).toFixed(4), mean(dead, d => d.fray).toFixed(4),
        mean(dead, d => d.adv).toFixed(3));
      console.log('      正常帧 n=%d : rag %s  fray %s  adv %s',
        live.length, mean(live, d => d.rag).toFixed(4), mean(live, d => d.fray).toFixed(4),
        mean(live, d => d.adv).toFixed(3));
      console.log('');
      console.log('    塌陷率 vs adv（确认是否集中在靠近前浪时）');
      const B4 = [0, .5, .8, .9, .95, 1.01];
      for (let b = 0; b < B4.length - 1; b++) {
        const lo = B4[b], hi = B4[b + 1];
        const sel = nd.filter(d => d.adv >= lo && d.adv < hi);
        const tag = `${(lo * 100) | 0}-${((hi * 100) | 0) | 0}`;
        if (!sel.length) { console.log('      ' + tag.padStart(5) + '  -'); continue; }
        const c = sel.filter(d => d.bwj <= 0.01).length;
        console.log('      ' + tag.padStart(5) + '  n=' + String(sel.length).padEnd(6) +
          ' 塌陷 ' + (100 * c / sel.length).toFixed(1).padStart(5) + '%' +
          '   平均 bwj ' + (sel.reduce((s, d) => s + d.bwj, 0) / sel.length).toFixed(3));
      }
    }
  }
}
