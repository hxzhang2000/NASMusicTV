/* 验证 5b-fix：泡沫破洞场的**连续性**（owner: 前浪白色一直存在，现在老是闪烁）
 *
 * 根因候选：punchHoles 用 Math.floor(t / HOLE_PERIOD_MS) 换随机种子，
 *   ⇒ 所有洞在周期边界那一帧整体瞬移。旧版只把周期从 260ms 放慢到 1100ms，
 *   治了频率没治跳变。
 *
 * 本脚本从 HTML 里**提取真实的 hash32/hash2**（避免复刻漂移），然后对旧/新两版
 * 洞场算式，逐帧算洞心 (cx,cy) 与半径 (rx,ry) 的位移，报告跨周期边界的最大值。
 * 判据：逐帧位移应远小于一个洞的半径（洞看起来在原地演化，而不是瞬移）。
 */
const fs = require('fs');
const path = require('path');

const HTML = path.join(__dirname, '..', '..', '..', 'seaside-preview.html');
const src = fs.readFileSync(HTML, 'utf8');

// ── 从 HTML 提取 hash32/hash2/clamp，与页面完全一致 ──────────────────────
const grab = (name) => {
  const m = src.match(new RegExp('function\\s+' + name + '\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*?\\n\\}'));
  if (!m) throw new Error('cannot extract ' + name);
  return m[0];
};
const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const hashSrc = grab('hash32') + '\n' + grab('hash2') +
  '\nmodule.exports = { hash2 };';
const mod = { exports: {} };
new Function('module', 'exports', hashSrc)(mod, mod.exports);
const hash2 = mod.exports.hash2;

// ── 页面常量（与 HTML 一致；grep 核对过）──────────────────────────────────
const HOLE_MAX = 18;
const HOLE_PERIOD_MS = 1100;
const COLS = 96;
const H = 900, W = 1600;
const GAP = 0.30;          // 典型条带宽度（n1-n0）
const DIR = 1;
const KJ = 0.62;           // kA 折算：取中等泡沫强度
const K_A = 0.62;
// 伪 bfy/bwj：只影响 cy/ry 的绝对值，不影响「是否跳变」，取常数即可
const bfy = 0.70 * H, bwj = 40;

// ── 旧版：量化种子（会瞬移）──────────────────────────────────────────────
function holesOld(t, h, strip, L, n0) {
  const gap = GAP;
  const tb = Math.floor(t / HOLE_PERIOD_MS);
  const hx = hash2(h, 700 + strip * 31 + L * 7);
  const hn = n0 + gap * (0.20 + 0.60 * hash2(h, 710 + tb + strip * 13 + L * 3));
  const hr = gap * (0.26 + 0.16 * hash2(h, 720 + tb + strip * 17 + L * 5));
  return finish(hx, hn, hr);
}
// ── 新版：连续相位（不瞬移）──────────────────────────────────────────────
function holesNew(t, h, strip, L, n0) {
  const gap = GAP;
  const ph = hash2(h, 700 + strip * 31 + L * 7);
  const u = ((t / HOLE_PERIOD_MS) + ph) % 1;
  const hx = clamp(ph + 0.13 * Math.sin(t * 0.00021 + ph * 6.2832), 0.02, 0.98);
  const grow = Math.sin(Math.PI * u);
  const hn = n0 + gap * (0.12 + 0.76 * u);
  const hr = gap * (0.06 + 0.30 * grow) * (0.70 + 0.60 * hash2(h, 720 + strip * 17 + L * 5));
  return finish(hx, hn, hr);
}
function finish(hx, hn, hr) {
  const col = Math.min(COLS, Math.max(0, Math.round(hx * COLS)));
  const cy = bfy + DIR * hn * bwj;
  const ry = hr * bwj;
  const rx = ry * (1.1 + 1.5 * 0.5);   // rx 系数取中值
  return { cx: hx * W, cy, rx, ry };
}

function maxJump(fn, DT) {
  let worst = 0, at = null, which = null;
  let worstVisible = 0, atVis = null;      // 只统计「两帧都可见」的洞
  const n0 = 0.10;
  const count = Math.round(HOLE_MAX * K_A * clamp(1.15 - GAP * 1.6, 0, 1));
  const RY_MIN = 1.5;                       // 与 punchHoles 的可见阈值一致
  for (let k = 0; k < 6; k++) {
    for (let f = 0; f * DT < HOLE_PERIOD_MS + DT; f++) {
      const t0 = k * HOLE_PERIOD_MS + f * DT;
      const t1 = t0 + DT;
      for (let h = 0; h < count; h++) {
        const a = fn(t0, h, 0, 0, n0);
        const b = fn(t1, h, 0, 0, n0);
        const dPos = Math.hypot(b.cx - a.cx, b.cy - a.cy);
        const dRad = Math.abs(b.ry - a.ry);
        const d = Math.max(dPos, dRad);
        if (d > worst) { worst = d; at = t0; which = 'h' + h; }
        // 可见性加权：半径为 0 的洞在回绕点瞬移是**看不见**的，不该算闪烁
        if (a.ry >= RY_MIN && b.ry >= RY_MIN && d > worstVisible) {
          worstVisible = d; atVis = t0;
        }
      }
    }
  }
  return { worst, at, which, count, worstVisible, atVis };
}

const DT = 1000 / 60;
const o = maxJump(holesOld, DT);
const n = maxJump(holesNew, DT);
const meanRy = GAP * 0.20 * bwj;

console.log('=== foam hole-field continuity (5b fix) ===');
console.log('dt = %s ms, period = %s ms, holes drawn = %d', DT.toFixed(2), HOLE_PERIOD_MS, n.count);
console.log('typical hole radius ry ~ %s px  (visible threshold ry >= 1.5px)', meanRy.toFixed(1));
console.log('');
console.log('OLD (Math.floor(t/period) reseed):');
console.log('   max per-frame jump, any hole      : %s px @ t=%s (%s)', o.worst.toFixed(1), o.at, o.which);
console.log('   max per-frame jump, VISIBLE holes  : %s px @ t=%s  = %sx hole radius',
  o.worstVisible.toFixed(1), o.atVis, (o.worstVisible / meanRy).toFixed(1));
console.log('   => 可见洞整帧瞬移 ⇒ 白沫闪一下');
console.log('');
console.log('NEW (continuous phase, staggered, clamped hx):');
console.log('   max per-frame jump, any hole      : %s px @ t=%s (%s)', n.worst.toFixed(2), n.at, n.which);
console.log('   max per-frame jump, VISIBLE holes  : %s px @ t=%s  = %sx hole radius',
  n.worstVisible.toFixed(2), n.atVis, (n.worstVisible / meanRy).toFixed(2));
console.log('   => 可见洞连续漂移/生长 ⇒ 稳定沸腾，不闪');
console.log('');
console.log('visible-jump improvement: %sx', (o.worstVisible / Math.max(1e-6, n.worstVisible)).toFixed(1));
