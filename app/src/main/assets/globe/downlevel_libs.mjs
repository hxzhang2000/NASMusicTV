/*
 * downlevel_globe_libs.mjs — 把 E41「世界」用的两个 ES6 库降级为 ES5。
 *
 * 为什么要降级（实测于 2026-09-29，创维 9R54_G8S / Android 5.1.1 / 电视）：
 *   该机系统 WebView 是 **Chrome 39**（无 Play WebView provider，不可更新）。
 *   Chrome 39 的 ES6 支持是**残缺**的，实测矩阵：
 *     class / 箭头函数 / let / 模板串 / 解构 / 默认参数 / rest / 简写属性 / ?. / ??  → 全部 SyntaxError
 *     const / for...of / generator                                                            → 可用
 *   three.min.js（class×207、let/const×2750、模板串×44）与
 *   three-globe.min.js（class×373、箭头×780、模板串×717）在第一处 class 就 SyntaxError，
 *   导致 window.THREE / window.ThreeGlobe 永不定义 → globe.js 提前 return →
 *   window.WorldGlobe 永不赋值 → 电视端纯黑屏。
 *   ⛔ globe.js 本身已是纯 ES5（24 个反引号全在注释里），不参与转译。
 *
 * 转译目标 chrome 39，与上面实测矩阵逐项对齐；Babel 的 compat-data 与实测一致
 * （它把 const/for-of/generator 判为已支持，把 class/箭头/let/模板串判为需转换）。
 *
 * 同时补 polyfill.es5.js（9 个 Chrome 39 缺失的 API），因为 preset-env 默认
 * `useBuiltIns: false` 只转语法、不注入内建 API。
 *
 * 用法（node_modules 不入库，需先装一次依赖；本脚本与被处理的资产同目录）：
 *   cd app/src/main/assets/globe
 *   npm install --no-save @babel/core@7.26.0 @babel/preset-env@7.26.0 terser@5.37.0 acorn@8.14.0
 *   node downlevel_libs.mjs [assetsDir]      # assetsDir 缺省 = 脚本所在目录
 *   装完记得清理：rm -rf node_modules package.json package-lock.json（或用 --no-save 并手工删）
 *
 * 产物：three.es5.js / three-globe.es5.js（原版 .min.js **保留**，便于逐字节比对）
 */

import { transformAsync } from "@babel/core";
import { minify } from "terser";
import { parse as acornParse } from "acorn";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
// 脚本与被处理的资产同目录，默认就地处理；传参可指向别处
//（便于把脚本复制到临时目录、配合那里的 node_modules 跑，避免污染工作区）
const ASSETS =
  process.argv[2] && process.argv[2].length > 0 ? resolve(process.argv[2]) : SCRIPT_DIR;

/** 上游版本备注，写进产物头部便于回溯 */
const JOBS = [
  { src: "three.min.js", out: "three.es5.js", pkg: "three.js", ver: "r160 UMD (build/three.min.js)" },
  { src: "three-globe.min.js", out: "three-globe.es5.js", pkg: "three-globe", ver: "2.45.2 UMD" },
];

const BABEL_OPTIONS = {
  babelrc: false,
  configFile: false,
  // 压缩交给后面的 terser；这里只负责降级语法
  compact: true,
  presets: [
    [
      "@babel/preset-env",
      {
        targets: { chrome: "39" },
        // ⛔ 必须 false：这两个库是 UMD 包装，preset-env 若转 CommonJS 会破坏
        //    <script src> 直接挂 window.THREE / window.ThreeGlobe 的方式。
        modules: false,
        // ⛔ 必须 false：polyfill.es5.js 是手写的 9 个 API，不引入 core-js
        useBuiltIns: false,
        // ⛔ 必须 false：loose 模式会把 class 转成原型赋值但**跳过** _classCallCheck
        //    之类的语义检查，改用规范行为以免 three 的继承链被误优化
        loose: false,
        bugfixes: false,
      },
    ],
  ],
};

const TERSER_OPTIONS = {
  ecma: 5, // ⛔ 关键：terser 的解析器只认 ES5，能解析通过本身就是「产物是 ES5」的证明
  compress: { passes: 2 },
  mangle: true,
  // 保留 @license / @preserve（three.js 的 MIT 声明必须留）
  format: { comments: /@license|@preserve|^!/ },
};

function banner(job) {
  return (
    `/*! NASMusicTV ES5 down-level build — NOT upstream, DO NOT hand-edit.\n` +
    ` *  source : ${job.src}  (${job.pkg} ${job.ver})\n` +
    ` *  target : Chrome 39 — Android 5.1.1 系统 WebView（实测 ES6 仅部分支持，见 docs/technical-overview.md §10.201）\n` +
    ` *  regen  : node downlevel_globe_libs.mjs\n` +
    ` */\n`
  );
}

/**
 * 权威的 ES5 证明：用 acorn 以 ecmaVersion: 5 真正解析一次产物。
 *
 * ⛔ 不用正则扫产物。minified 代码里 "..." / "class a" / 反引号大量出现在
 *    **字符串与正则字面量**中（GLSL shader chunk 源码、加载文案），正则无法区分
 *    语法与字面量，必然误报。acorn 是完整词法+语法分析，任何 ES6 语法都会抛错并给出位置。
 *
 * 下面的 ES6_PATTERNS 只用于**统计输入**里各语法出现多少次（留档，说明转译的必要性）。
 */
const ES6_PATTERNS = [
  ["class", /(?<![A-Za-z0-9_$.])class\s+[A-Za-z_$]/g],
  ["arrow", /=>/g],
  ["let", /(?<![A-Za-z0-9_$.])let\s+[A-Za-z_$]/g],
  ["const", /(?<![A-Za-z0-9_$.])const\s+[A-Za-z_$]/g],
  ["template", /`/g],
  ["optional-chain", /[A-Za-z0-9_$)\]]\?\./g],
  ["nullish", /[A-Za-z0-9_$)\]]\?\?/g],
  ["spread-rest", /\.\.\./g],
];

function countEs6(src) {
  const parts = [];
  for (const [name, re] of ES6_PATTERNS) {
    const m = src.match(re);
    if (m && m.length > 0) parts.push(`${name}×${m.length}`);
  }
  return parts.length > 0 ? parts.join(" ") : "（无）";
}

function assertEs5(code, label) {
  try {
    acornParse(code, { ecmaVersion: 5, sourceType: "script" });
    console.log(`  ✓ ${label} 通过 acorn ecmaVersion=5 解析（合法 ES5）`);
    return true;
  } catch (e) {
    console.error(`  ⛔ ${label} 不是合法 ES5：${e.message}`);
    return false;
  }
}

function report(label, bytes) {
  const kb = (bytes / 1024).toFixed(0);
  console.log(`  ${label.padEnd(24)} ${kb.padStart(7)} KB`);
  return bytes;
}

let failed = false;

for (const job of JOBS) {
  const srcPath = resolve(ASSETS, job.src);
  const outPath = resolve(ASSETS, job.out);
  const original = readFileSync(srcPath, "utf8");
  console.log(`\n=== ${job.src} -> ${job.out} ===`);

  const t0 = Date.now();
  const downleveled = await transformAsync(original, BABEL_OPTIONS);
  if (!downleveled || downleveled.code == null) {
    console.error("  Babel 未产出代码");
    failed = true;
    continue;
  }
  const tBabel = Date.now() - t0;

  // terser 解析器只接受 ES5：这里抛错就说明 Babel 漏了语法
  const minified = await minify(downleveled.code, TERSER_OPTIONS);
  if (typeof minified.code !== "string") {
    console.error("  terser 未产出代码");
    failed = true;
    continue;
  }
  const tTerser = Date.now() - t0 - tBabel;

  const bannerText = banner(job);
  const finalCode = bannerText + minified.code;

  console.log(`  babel ${tBabel}ms -> terser ${tTerser}ms`);
  console.log(`  输入 ES6 语法统计: ${countEs6(original)}`);
  report("原始 (ES6)", original.length);
  report("产物 (ES5)", finalCode.length);

  // 关键：先证明产物是合法 ES5，再落盘。证明失败就不写，避免半成品进仓库
  if (!assertEs5(finalCode, job.out)) {
    failed = true;
    continue;
  }

  writeFileSync(outPath, finalCode, "utf8");
  console.log(`  ✓ 已写入 ${outPath}`);
}

if (failed) {
  console.error("\n⛔ 转译失败，未全部写入产物");
  process.exit(1);
}
console.log("\n✓ 全部完成");
