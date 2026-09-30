/*
 * downlevel_libs.mjs — 把 E41「世界」用的两个 ES6 库降级为 ES5。
 *
 * 降级器是 **TypeScript 编译器（tsc）**，不是 Babel。原因见下方「⛔ 为什么不用 Babel」。
 *
 * ---------------------------------------------------------------------------
 * 为什么要降级（实测于 2026-09-29，创维 9R54_G8S / Android 5.1.1 / 电视）
 * ---------------------------------------------------------------------------
 *   该机系统 WebView 是 **Chrome 39**（无 Play WebView provider，`dumpsys webviewupdate`
 *   为空，用 AOSP 5.1.1 自带 WebView，不可更新）。它的 ES6 支持是**残缺**的，
 *   逐项 eval 实测：
 *     SyntaxError —— class / 箭头函数 / let / 模板串 / 解构 / 默认参数 / rest /
 *                   简写属性 / ?. / ??
 *     可用        —— const / for-of / generator
 *   three.min.js（class×207、let×892、const×1858、模板串×44）与
 *   three-globe.min.js（class×373、arrow×780、let×1113、const×2643、模板串×717、
 *   spread×142）在第一处 class 就 SyntaxError → window.THREE / window.ThreeGlobe
 *   永不定义 → globe.js:35 提前 return → window.WorldGlobe 永不赋值 → 电视端纯黑屏。
 *
 *   globe.js 本身**已是纯 ES5**（24 个反引号全在注释里），不参与转译。
 *
 *   Chrome 39 另缺这些 API（真机 typeof 实测），由 polyfill.es5.js 补：
 *     Object.assign / Array.from / Object.values / Object.entries /
 *     String.prototype.includes / String.prototype.startsWith /
 *     Array.prototype.includes / Array.prototype.find
 *   已存在、无需补：Symbol 与 Symbol.iterator、Promise、Map、Set、WeakMap、
 *   Number.isFinite、TypedArray、performance.now、requestAnimationFrame。
 *
 *   两处曾疑似风险、实测无害，**不需要**处理：
 *     · globalThis ×2 —— 只出现在 UMD 前导段 `e="undefined"!=typeof globalThis?
 *       globalThis:e||self`，自带回退；Chrome 39 走 `e||self`（传入的 this = window）。
 *     · new Proxy ×3 —— 全在 three 的 TSL / node material 子系统
 *       （'TSL: "Fn()" was declared but not invoked'）。globe.js 只用
 *       MeshPhongMaterial，不走 node material，这些代码不会执行。
 *
 * ---------------------------------------------------------------------------
 * ⛔ 为什么不用 Babel（2026-09-29 实测失败，勿重蹈）
 * ---------------------------------------------------------------------------
 *   @babel/preset-env targets:{chrome:"39"} 产出的 three-globe 代码**运行时初始化即崩**：
 *     TypeError: e.Box3 is not a constructor
 *   已逐项排除：
 *     · 不是 terser —— mangle / compress 全关、甚至**完全不压缩**（Babel 原始输出），
 *       5 组配置全部同样报错；
 *     · 不是 UMD 实参 —— 探针把 `t(e.THREE)` 改写为 `t(__farg=e.THREE, e.THREE)` 后证实：
 *       ES6 原版与 Babel 产物传给工厂的是**同一个对象**（REVISION=160、Box3=function、
 *       416 个键）；
 *     · 只在 three-globe 侧 —— tsc 产物 + Babel 版 three 混搭可正常加载。
 *   → 缺陷在 Babel 的 class 降级语义本身（class ... extends THREE.InstancedBufferGeometry
 *     被转成继承辅助调用后，工厂体内 `e` 的引用语义变了）。
 *
 * ⚠️ 教训：「产物能被 ES5 解析器解析」**不等于**「产物能正确运行」。
 *    acorn ecmaVersion=5 只能证明**语法**合法，证明不了运行时语义。当时只做了语法
 *    校验就汇报「验证通过」，结论下早了。**必须配 vm 加载测试**（见文件末尾说明）。
 *
 * ---------------------------------------------------------------------------
 * 本目录文件清单与保留策略（改动前先读这段）
 * ---------------------------------------------------------------------------
 *   three.min.js            上游 ES6 原版，r160 UMD。        ⛔ 永久保留，永不删除，永不修改
 *   three-globe.min.js      上游 ES6 原版，2.45.2 UMD。      ⛔ 永久保留，永不删除，永不修改
 *   three.es5.js            本脚本产物（654KB → 987KB）。     运行时装载
 *   three-globe.es5.js      本脚本产物（1248KB → 2007KB）。   运行时装载
 *   polyfill.es5.js         手写，Chrome 39 缺失 API。        运行时装载，必须最先
 *   globe.js                本项目渲染逻辑，纯 ES5，**不参与转译**
 *   cities.json             城市坐标数据
 *   earth*.jpg / moon.jpg   贴图（earth.jpg 为 4096×2048）
 *   index.html              装配页
 *   downlevel_libs.mjs      本脚本
 *
 *   ⛔ **两个 ES6 原版永久保留、不得删除、不得修改。** 它们是 ES5 产物的比对基线：
 *      产物出问题时必须能逐字节 diff 回上游，确认差异只来自降级，而非库的版本漂移
 *      或误改。删掉就永久丧失该能力；改内容同样失效。代价约 1.9MB 源资源，换可回溯性。
 *
 *   ⚠️ 不做压缩。tsc 产物本身已是合法 ES5，再过 terser 只是为了省体积，不值得引入
 *      额外变量（此前 Babel 的失败曾把嫌疑指向压缩链）。如需压缩，在此追加并复测。
 * ---------------------------------------------------------------------------
 *
 * 用法（node_modules 不入库，需先装一次依赖；本脚本与被处理的资产同目录）：
 *   cd app/src/main/assets/globe
 *   npm install --no-save typescript@5.7.2 acorn@8.14.0
 *   node downlevel_libs.mjs [assetsDir]      # assetsDir 缺省 = 脚本所在目录
 *   装完记得清理：rm -rf node_modules package.json package-lock.json
 *
 * 本脚本只做「转译 + ES5 语法校验」。**运行时验证需另跑 vm 加载测试**——
 * 在 Node vm 沙箱里删掉 Chrome 39 缺失的 API，再按 index.html 的顺序加载
 * polyfill → three → three-globe → globe.js，断言 THREE / ThreeGlobe 均已定义。
 */

import ts from "typescript";
import { parse as acornParse } from "acorn";
import { readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const ASSETS =
  process.argv[2] && process.argv[2].length > 0 ? resolve(process.argv[2]) : SCRIPT_DIR;

const JOBS = [
  { src: "three.min.js", out: "three.es5.js", pkg: "three.js", ver: "r160 UMD (build/three.min.js)" },
  { src: "three-globe.min.js", out: "three-globe.es5.js", pkg: "three-globe", ver: "2.45.2 UMD" },
];

const COMPILER_OPTIONS = {
  target: ts.ScriptTarget.ES5,
  module: ts.ModuleKind.None,
  allowJs: true,
  checkJs: false,
  removeComments: false,
  // 只需数组的 for..of；置 true 会引入 __values/__read 辅助，徒增风险
  downlevelIteration: false,
  // 辅助函数（__extends 等）内联，不引 tslib
  importHelpers: false,
  noEmitHelpers: false,
  useDefineForClassFields: false,
  isolatedModules: true,
};

function banner(job) {
  return (
    `/*! NASMusicTV ES5 down-level build — NOT upstream, DO NOT hand-edit.\n` +
    ` *  source  : ${job.src}  (${job.pkg} ${job.ver})\n` +
    ` *  downlevel: TypeScript ${ts.version}  (⛔ NOT Babel — Babel 的 class 降级会让本库初始化崩溃)\n` +
    ` *  target  : Chrome 39 — Android 5.1.1 系统 WebView（实测 ES6 仅部分支持，见 docs/technical-overview.md §10.201）\n` +
    ` *  regen   : node downlevel_libs.mjs\n` +
    ` */\n`
  );
}

const ES6_PATTERNS = [
  ["class", /(?<![A-Za-z0-9_$.])class\s+[A-Za-z_$]/g],
  ["arrow", /=>/g],
  ["let", /(?<![A-Za-z0-9_$.])let\s+[A-Za-z_$]/g],
  ["const", /(?<![A-Za-z0-9_$.])const\s+[A-Za-z_$]/g],
  ["template", /`/g],
  ["optional-chain", /[A-Za-z0-9_$)\]]\?\./g],
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

/** 语法层证明：能被 acorn 以 ecmaVersion=5 解析。⚠️ 这**不能**证明运行时正确。 */
function assertEs5(code, label) {
  try {
    acornParse(code, { ecmaVersion: 5, sourceType: "script" });
    console.log(`  ✓ ${label} 通过 acorn ecmaVersion=5 解析（合法 ES5 语法）`);
    console.log(`    ⚠️ 语法合法 ≠ 运行时正确，仍需 vm 加载测试`);
    return true;
  } catch (e) {
    console.error(`  ⛔ ${label} 不是合法 ES5：${e.message}`);
    return false;
  }
}

let failed = false;

for (const job of JOBS) {
  const original = readFileSync(resolve(ASSETS, job.src), "utf8");
  console.log(`\n=== ${job.src} -> ${job.out} ===`);
  console.log(`  输入 ES6 语法统计: ${countEs6(original)}`);

  const t0 = Date.now();
  const { outputText } = ts.transpileModule(original, {
    compilerOptions: COMPILER_OPTIONS,
    fileName: job.src,
  });
  const finalCode = banner(job) + outputText;
  console.log(`  tsc 转译 ${Date.now() - t0}ms`);
  console.log(`  原始 ${(original.length / 1024).toFixed(0)} KB -> 产物 ${(finalCode.length / 1024).toFixed(0)} KB`);

  if (!assertEs5(finalCode, job.out)) {
    failed = true;
    continue;
  }
  writeFileSync(resolve(ASSETS, job.out), finalCode, "utf8");
  console.log(`  ✓ 已写入 ${resolve(ASSETS, job.out)}`);
}

if (failed) {
  console.error("\n⛔ 转译失败，未全部写入产物");
  process.exit(1);
}
console.log("\n✓ 全部完成。下一步必须跑 vm 加载测试（删掉 Chrome 39 缺失的 API 后加载全链路）。");
