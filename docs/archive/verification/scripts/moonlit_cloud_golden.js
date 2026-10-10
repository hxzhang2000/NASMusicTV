// E44「明月」T7 / G3 的**基准生成器**：从 `docs/moonlit-preview.html` 里**原文抽出**云场函数
// （播种 / 铺斑 / 推进 / 遮挡），跑出一个确定性参考帧，把结果写成 Kotlin 测试基线文件
// `app/src/test/java/…/renderers/MoonlitCloudBaseline.kt`。
//
// ⛔ **不要手抄公式** —— 手抄就等于把哨兵拆了：Kotlin 侧改错一行、基线跟着"错得一致"，
//    `MoonlitTest` 会判绿。基线的唯一来源必须是本脚本从原型抽出的原文。
// ⚠️ 时基：`tSec` 与生产代码同形地由 **125 次 `+= 0.1`** 累加得到 12.5，⛔ 不许直接令 `tSec = 12.5`
//    （绝对时间赋值与增量累加是两条不同的浮点路径，基线会白对一场）。
//
// 用法：`node docs/archive/verification/scripts/moonlit_cloud_golden.js`
// 改完云场（原型侧）后必须重跑本脚本再动 `MoonlitTest`，二者同一提交。
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..', '..', '..', '..');
const lines = fs.readFileSync(path.join(root, 'docs', 'moonlit-preview.html'), 'utf8').split(/\r?\n/);
const grab = (a, b) => lines.slice(a - 1, b).join('\n');
const idx = (needle) => {
  const i = lines.findIndex((l) => l.includes(needle));
  if (i < 0) throw new Error('原型里未找到 ' + needle);
  return i + 1;
};

const ctx = { Math, TAU: Math.PI * 2, PI: Math.PI, console, minDim: 0 };
vm.createContext(ctx);
vm.runInContext(
  [
    grab(idx('const CLOUD_FAR'), idx('const OCCL_PAD')),                 // 数量表 + 时标常量
    'const clamp = (v, a, b) => v < a ? a : v > b ? b : v;',
    'const wrap01 = (v) => { const x = v % 1; return x < 0 ? x + 1 : x; };',
    grab(idx('function makeCloud'), idx('return clamp(sum, 0, 1);') + 1), // 播种 → occlusion 原文
  ].join('\n'),
  ctx
);

// ── 参考帧：1920×1080、§3.1 定稿构图、spdMul = 1（T7 的刻意常量）、dtSec = 0.1 × 125 帧
//
// ⚠️ **播种快照必须在任何步进之前**：`x` 是唯一**随时间推进**的播种字段（`stepClouds` 里
//    `b.x = wrap01(b.x + speedK·spdMul·dt)`），把它放在 125 帧循环**之后**取快照，会让基线里的
//    `x` 变成「推进 12.5 s 后」的位置，而其余 97 个字段与时无关 ⇒ `MoonlitTest` ① 表现为
//    「每行只有 [0] 全错」这种极难一眼看穿的形状（2026-10-09 首次跑 G3 即栽在此）。
//
// ⭐ `minDim` 必须赋在**上下文的全局**上（`stepClouds` 读的是全局，不是形参）：
//    局部 `const minDim` 只会遮蔽它 ⇒ `gw/gh = 0` ⇒ 缕包络缩成 `OCCL_PAD·moonR` 一小圈 ⇒
//    `occl` 分布整体量低（`logs_temp/occl_peak.js` 就因此量出过一个**假的** 0.53，
//    并被登记成偏差 D24；现由本脚本单点承载该口径，⛔ 再留一份会漂移的探针）。
const REF = `
(function () {
  const W = 1920, H = 1080, DT = 0.1, N = 125, SCAN = 8000, SEED = 20261008;
  minDim = Math.min(W, H);
  const horizonY = H * 0.640;
  const moonCx = W * 0.280, moonCy = H * 0.200, moonR = minDim * 0.150;
  const transitBand = horizonY * (0.200 / 0.640);
  const snapSeed = (b) => {
    const o = [b.x, b.bandY, b.lenK, b.thickK, b.tilt, b.wavK, b.wavN, b.speedK, b.alphaK, b.wobPhase];
    for (let j = 0; j < BLOB_NEAR.HIGH; j++) {
      const p = b.blobs[j];
      o.push(p.t, p.rK, p.sq, p.off, p.spd, p.aK, p.ph, p.ph2);
    }
    return o;
  };
  const seedFar = clouds.far.map(snapSeed);
  const seedNear = clouds.near.map(snapSeed);
  let tSec = 0;
  for (let k = 0; k < N; k++) {
    tSec += DT;
    stepClouds(clouds.far,  CLOUD_FAR.HIGH,  BLOB_FAR.HIGH,  DT, tSec, 1, W, H, horizonY);
    stepClouds(clouds.near, CLOUD_NEAR.HIGH, BLOB_NEAR.HIGH, DT, tSec, 1, W, H, horizonY);
  }
  const snapB = (b, np) => {
    const o = [b.cx, b.cy, b.gw, b.gh, b.w, b.h];
    for (let j = 0; j < np; j++) {
      const p = b.blobs[j];
      o.push(p.px, p.py, p.rx, p.ry, p.a, p.altT);
    }
    return o;
  };
  // ⚠️ 参考帧的几何与 occl 必须**先落袋**：下面的 scan() 会 reseedClouds 重铺一场云，
  //    顺序颠倒就等于拿「扫描末态」当 125 帧参考帧对账。
  const ref = {
    geomFar: clouds.far.map((b) => snapB(b, BLOB_FAR.HIGH)),
    geomNear: clouds.near.map((b) => snapB(b, BLOB_NEAR.HIGH)),
    occl: occlusion(moonCx, moonCy, moonR, clouds.near, CLOUD_NEAR.HIGH),
    occlFarIgnored: occlusion(moonCx, moonCy, moonR, clouds.far, CLOUD_FAR.HIGH),
  };
  // §6.2 的 occl **分布**扫描（G3 ⑦ 的实测口径）。⛔ 不是「峰值上限」那种手调数：
  // 一次给 peak / mean / 三个占比，Kotlin 侧逐项对账，任何几何漂移都会露形。
  // transit = 近层被钉成过境缕的下标；传 -1 ⇒ 自然态（与 TRANSIT_OFF 同义）。
  const scan = (transit) => {
    reseedClouds(SEED);
    let t = 0, peak = 0, sum = 0, hot = 0, top = 0, over = 0;
    for (let k = 0; k < SCAN; k++) {
      t += DT;
      stepClouds(clouds.far,  CLOUD_FAR.HIGH,  BLOB_FAR.HIGH,  DT, t, 1, W, H, horizonY);
      stepClouds(clouds.near, CLOUD_NEAR.HIGH, BLOB_NEAR.HIGH, DT, t, 1, W, H, horizonY);
      if (transit >= 0) clouds.near[transit].cy = transitBand;
      const o = occlusion(moonCx, moonCy, moonR, clouds.near, CLOUD_NEAR.HIGH);
      if (o > peak) peak = o;
      sum += o;
      if (o > 0.5) hot++;
      if (o >= 0.999) top++;
      if (o > 0.001) over++;
    }
    return { peak: peak, mean: sum / SCAN, hot: hot / SCAN, top: top / SCAN, over: over / SCAN };
  };
  const nat = scan(-1);
  const tra = scan(0);
  return JSON.stringify({
    W: W, H: H, DT: DT, N: N, tSec: tSec, scan: SCAN,
    horizonY: horizonY, moonCx: moonCx, moonCy: moonCy, moonR: moonR, minDim: minDim,
    nFar: CLOUD_FAR.HIGH, npFar: BLOB_FAR.HIGH, nNear: CLOUD_NEAR.HIGH, npNear: BLOB_NEAR.HIGH,
    seedFar: seedFar,
    seedNear: seedNear,
    geomFar: ref.geomFar,
    geomNear: ref.geomNear,
    occl: ref.occl,
    occlFarIgnored: ref.occlFarIgnored,
    nat: nat, tra: tra,
  });
})()
`;
const d = JSON.parse(vm.runInContext(REF, ctx));

// ⛔ 防"minDim 又被局部 const 遮蔽"这类**静默量低**：`minDim` 若没赋到上下文全局，
//    `gw/gh` 会全成 0、包络缩成 `OCCL_PAD·moonR` 一小圈、`occl` 分布整体偏低却**不报错**。
//    几何恒等式 `w ≥ gw`（斑最远就在脊端）⇒ 用参考帧的 `w` 直接自检。
if (d.minDim !== Math.min(d.W, d.H)) throw new Error('minDim 没赋到上下文全局 ⇒ 缕尺寸会按 0 算');
if (d.geomNear.every((r) => r[4] === 0)) throw new Error('包络半轴 w 全为 0 ⇒ 云几何已退化');

// JS 的 Number → string 是**最短可回读**表示，Kotlin 侧 `toDouble()` 正确舍入 ⇒ 逐位相同，
// 所以播种（只有 + × /）可以按**精确相等**断言；含 sin/pow 的几何只能按相对容差比。
const lit = (v) => (Number.isInteger(v) && Math.abs(v) < 1e15 ? v + '.0' : String(v));
const row = (arr) => 'doubleArrayOf(' + arr.map(lit).join(', ') + ')';

const k = [];
k.push('package com.nasmusic.tv.visualizer.renderers');
k.push('');
k.push('// ⚠️⚠️ **本文件由脚本生成，⛔ 不要手工编辑**（改云场请先改原型，再重跑：）');
k.push('//     node docs/archive/verification/scripts/moonlit_cloud_golden.js');
k.push('// 判据的成立与否**完全取决于这些数字来自原型原文**（见该脚本头部的「⛔ 不要手抄公式」）。');
k.push('');
k.push('/**');
k.push(' * E44 §六 云场的**原型基准**（G3 `MoonlitTest` 的对账数据）。');
k.push(' *');
k.push(' * 参考帧：' + d.W + '×' + d.H + '、§3.1 定稿构图、`spdMul = 1`、`dtSec = ' + d.DT + '` 累加 ' + d.N + ' 帧');
k.push(' * ⇒ `tSec = ' + lit(d.tSec) + '`（⚠️ 与生产同形的**增量累加**，不是直接赋值）。');
k.push(' */');
k.push('internal object MoonlitCloudBaseline {');
k.push('');
k.push('    const val W = ' + lit(d.W));
k.push('    const val H = ' + lit(d.H));
k.push('    const val MIN_DIM = ' + lit(d.minDim));
k.push('    const val HORIZON_Y = ' + lit(d.horizonY));
k.push('    const val MOON_CX = ' + lit(d.moonCx));
k.push('    const val MOON_CY = ' + lit(d.moonCy));
k.push('    const val MOON_R = ' + lit(d.moonR));
k.push('    const val DT_SEC = ' + lit(d.DT));
k.push('    const val FRAMES = ' + d.N);
k.push('    const val T_SEC = ' + lit(d.tSec));
k.push('    const val N_FAR = ' + d.nFar);
k.push('    const val NP_FAR = ' + d.npFar);
k.push('    const val N_NEAR = ' + d.nNear);
k.push('    const val NP_NEAR = ' + d.npNear);
k.push('');
k.push('    /** 播种：每缕 = `x bandY lenK thickK tilt wavK wavN speedK alphaK wobPhase` + ' + d.npNear + ' 斑 × `t rK sq off spd aK ph ph2`。⚠️ 快照取在**任何步进之前**（`x` 是唯一随时间推进的播种字段）。 */');
k.push('    val SEED_FAR: Array<DoubleArray> = arrayOf(');
d.seedFar.forEach((r, i) => k.push('        ' + row(r) + (i === d.seedFar.length - 1 ? '' : ',')));
k.push('    )');
k.push('');
k.push('    val SEED_NEAR: Array<DoubleArray> = arrayOf(');
d.seedNear.forEach((r, i) => k.push('        ' + row(r) + (i === d.seedNear.length - 1 ? '' : ',')));
k.push('    )');
k.push('');
k.push('    /** 一帧几何：每缕 = `cx cy gw gh w h` + `np` 斑 × `px py rx ry a altT`。 */');
k.push('    val GEOM_FAR: Array<DoubleArray> = arrayOf(');
d.geomFar.forEach((r, i) => k.push('        ' + row(r) + (i === d.geomFar.length - 1 ? '' : ',')));
k.push('    )');
k.push('');
k.push('    val GEOM_NEAR: Array<DoubleArray> = arrayOf(');
d.geomNear.forEach((r, i) => k.push('        ' + row(r) + (i === d.geomNear.length - 1 ? '' : ',')));
k.push('    )');
k.push('');
k.push('    /** §6.2 参考帧的 `occl`（**只由近层**算出）。 */');
k.push('    const val OCCL = ' + lit(d.occl));
k.push('');
k.push('    /**');
k.push('     * 同一帧**误用远层**去算会得到 ' + lit(d.occlFarIgnored) + ' —— 本效果 ⛔ 远层不参与遮挡');
k.push('     * （原型 `:953` 只喂 `clouds.near`）。留这个数是为了让 `MoonlitTest` 能**反向**钉住：');
k.push('     * 一旦有人把远层接进 `occl`，画面上多出来的那一口变暗就有数可对。');
k.push('     */');
k.push('    const val OCCL_FAR_IF_MISTAKENLY_USED = ' + lit(d.occlFarIgnored));
k.push('');
k.push('    /** §6.2 的 `occl` **分布**实测口径（G3 ⑦ 对账用，⛔ 不是手调上限）。 */');
k.push('    const val SCAN_FRAMES = ' + d.scan);
k.push('');
k.push('    /**');
k.push('     * 自然态（⛔ 不钉过境缕）：峰值 ' + lit(d.nat.peak) + '、均值 ' + lit(d.nat.mean) + '、');
k.push('     * 占比 `occl>0.5` = ' + lit(d.nat.hot) + '、`occl≥0.999` = ' + lit(d.nat.top) + '、');
k.push('     * `occl>0.001` = ' + lit(d.nat.over) + '。');
k.push('     * ⚠️ 自然场**能**把盘遮满（`peak` 会顶到 clamp 的 1）⇒ §6.6 的确定性过境提供的不是');
k.push('     * "能不能吞月"，而是**时长与周期性**（`TRA_TOP` 对 `NAT_TOP` 的倍数）。');
k.push('     */');
k.push('    const val NAT_PEAK = ' + lit(d.nat.peak));
k.push('    const val NAT_MEAN = ' + lit(d.nat.mean));
k.push('    const val NAT_HOT = ' + lit(d.nat.hot));
k.push('    const val NAT_TOP = ' + lit(d.nat.top));
k.push('    const val NAT_OVER = ' + lit(d.nat.over));
k.push('');
k.push('    /** 过境态（§6.6 把近层 ' + '0' + ' 号缕的带位钉到月的高度）同口径五项。 */');
k.push('    const val TRA_PEAK = ' + lit(d.tra.peak));
k.push('    const val TRA_MEAN = ' + lit(d.tra.mean));
k.push('    const val TRA_HOT = ' + lit(d.tra.hot));
k.push('    const val TRA_TOP = ' + lit(d.tra.top));
k.push('    const val TRA_OVER = ' + lit(d.tra.over));
k.push('');
k.push('    /** 缕的播种标量数 / 几何标量数（数组解包的口径，⛔ 两处各写一份字面量）。 */');
k.push('    const val SEED_SCALARS = 10');
k.push('    const val SEED_PUFF_FIELDS = 8');
k.push('    const val GEOM_SCALARS = 6');
k.push('    const val GEOM_PUFF_FIELDS = 6');
k.push('}');
k.push('');

const outPath = path.join(
  root, 'app', 'src', 'test', 'java', 'com', 'nasmusic', 'tv', 'visualizer', 'renderers',
  'MoonlitCloudBaseline.kt'
);
fs.writeFileSync(outPath, k.join('\n'), 'utf8');
console.log('已写出 ' + path.relative(root, outPath) + '（' + k.length + ' 行）');
console.log('参考帧 occl = ' + d.occl + '，远层误用值 = ' + d.occlFarIgnored + '，tSec = ' + d.tSec);
console.log('occl 分布（' + d.scan + ' 帧）自然态 peak/mean/hot/top/over = ' +
  [d.nat.peak, d.nat.mean, d.nat.hot, d.nat.top, d.nat.over].join(' / '));
console.log('                        过境态 peak/mean/hot/top/over = ' +
  [d.tra.peak, d.tra.mean, d.tra.hot, d.tra.top, d.tra.over].join(' / '));
