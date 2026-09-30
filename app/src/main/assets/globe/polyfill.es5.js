/*
 * polyfill.es5.js — E41「世界」在 Android 5.1.1 系统 WebView（Chrome 39）上所需的 API 补齐。
 *
 * ⛔ 必须纯 ES5：目标引擎 Chrome 39 连 `class` / 箭头函数 / `let` / 模板串都解析不了。
 * ⛔ 必须在 three.es5.js / three-globe.es5.js **之前**加载：这两个库转译后仍会调用
 *    Object.assign / Array.from 等（Babel 的 useBuiltIns:false 只降语法，不注入内建 API）。
 *
 * 分组说明：
 *   [实测缺失] = 2026-09-29 在创维 9R54_G8S 真机上用 probe.html 逐个 typeof 测出来确实没有
 *   [防御补齐] = 未在真机上逐一测量，但属同类 ES2015+ 且实现廉价；minified 第三方库常用
 *   [刻意不补] = 见文末说明
 *
 * Chrome 39 **已存在、无需补**（实测）：Symbol 与 Symbol.iterator、Promise、Map、Set、
 * WeakMap、Number.isFinite、Float32Array 等 TypedArray、performance.now、requestAnimationFrame、
 * Number.isInteger 之外的基本 ES5 全集。
 */
(function (global) {
  "use strict";

  // 特性检测已存在则不覆盖——手机等现代 WebView 上本文件全是 no-op
  function has(obj, name) {
    return Object.prototype.hasOwnProperty.call(obj, name) && typeof obj[name] !== "undefined";
  }
  function define(obj, name, value) {
    try {
      Object.defineProperty(obj, name, {
        value: value, writable: true, configurable: true, enumerable: false
      });
    } catch (e) {
      obj[name] = value; // 极老引擎不允许在内建对象上 defineProperty
    }
  }
  function toStr(o) { return Object.prototype.toString.call(o); }
  function isArr(o) { return toStr(o) === "[object Array]"; }
  function isFn(f) { return typeof f === "function"; }

  // ---------------------------------------------------------------- [实测缺失]

  // globalThis（ES2020，Chrome 71+ 才有）
  // ⛔ 严格说**不补也能工作**：两个库的 UMD 前导段都是
  //    `e = "undefined"!=typeof globalThis ? globalThis : e||self`，
  //    自带 `e||self` 回退，而 `e` 是传入的 this —— 经典脚本里就是 window。
  //    这里仍显式补上，是为了让正确性不依赖那条回退路径：一旦某个构建改成只用
  //    globalThis，缺失就是 ReferenceError，且炸在 UMD 最前面，排查成本很高。
  //    这个实现是**正确**的（指向全局对象本身），与 Proxy 那种假实现不同。
  if (typeof globalThis === "undefined") {
    define(global, "globalThis", global);
  }

  // Object.assign：three ×28、three-globe ×58
  if (!has(Object, "assign")) {
    define(Object, "assign", function assign(target) {
      if (target == null) throw new TypeError("Object.assign target must not be null or undefined");
      var out = Object(target);
      for (var i = 1; i < arguments.length; i++) {
        var src = arguments[i];
        if (src == null) continue;
        var keys = Object.keys(Object(src));
        for (var k = 0; k < keys.length; k++) {
          var key = keys[k];
          var d = Object.getOwnPropertyDescriptor(src, key);
          if (d && d.enumerable) out[key] = src[key];
        }
      }
      return out;
    });
  }

  // Array.from：three ×3、three-globe ×33，且 Babel 的 toConsumableArray 助手会调它
  if (!has(Array, "from")) {
    define(Array, "from", function from(items, mapFn, thisArg) {
      if (items == null) throw new TypeError("Array.from requires an array-like or iterable");
      if (mapFn !== undefined && mapFn !== null && !isFn(mapFn)) {
        throw new TypeError("Array.from mapFn must be a function");
      }
      var out = [];
      var iterator = typeof Symbol !== "undefined" && Symbol.iterator ? items[Symbol.iterator] : null;
      if (!isArr(items) && isFn(iterator)) {
        var step, it = iterator.call(items);
        while (!(step = it.next()).done) {
          out.push(isFn(mapFn) ? mapFn.call(thisArg, step.value, out.length) : step.value);
        }
      } else {
        var len = Number(items.length) || 0;
        for (var i = 0; i < len; i++) {
          out.push(isFn(mapFn) ? mapFn.call(thisArg, items[i], i) : items[i]);
        }
      }
      return out;
    });
  }

  // Object.values：three-globe ×13
  if (!has(Object, "values")) {
    define(Object, "values", function values(obj) {
      if (obj == null) throw new TypeError("Object.values called on null or undefined");
      var out = [], o = Object(obj), keys = Object.keys(o);
      for (var i = 0; i < keys.length; i++) out.push(o[keys[i]]);
      return out;
    });
  }

  // Object.entries：three-globe ×5
  if (!has(Object, "entries")) {
    define(Object, "entries", function entries(obj) {
      if (obj == null) throw new TypeError("Object.entries called on null or undefined");
      var out = [], o = Object(obj), keys = Object.keys(o);
      for (var i = 0; i < keys.length; i++) out.push([keys[i], o[keys[i]]]);
      return out;
    });
  }

  // String.prototype.includes：three-globe ×22
  if (!has(String.prototype, "includes")) {
    define(String.prototype, "includes", function includes(search, start) {
      if (search === undefined || search === null) {
        throw new TypeError("String.prototype.includes search must not be null or undefined");
      }
      var pos = start === undefined ? 0 : Number(start);
      if (isNaN(pos)) pos = 0;
      if (pos < 0) pos = 0;
      if (pos > this.length) return false;
      return this.indexOf(String(search), pos) !== -1;
    });
  }

  // String.prototype.startsWith：three-globe ×10
  if (!has(String.prototype, "startsWith")) {
    define(String.prototype, "startsWith", function startsWith(search, pos) {
      if (search === undefined || search === null) {
        throw new TypeError("String.prototype.startsWith search must not be null or undefined");
      }
      var start = pos === undefined ? 0 : Number(pos);
      if (isNaN(start)) start = 0;
      if (start < 0) start = 0;
      if (start > this.length) return false;
      return this.slice(start, start + String(search).length) === String(search);
    });
  }

  // Array.prototype.includes：实测缺失
  if (!has(Array.prototype, "includes")) {
    define(Array.prototype, "includes", function includes(search, from) {
      var len = this.length >>> 0;
      if (len === 0) return false;
      var i = from === undefined ? 0 : Number(from);
      if (isNaN(i)) i = 0;
      if (i < 0) i = Math.max(len + i, 0);
      for (; i < len; i++) {
        var v = this[i];
        // 用相等性比较而非 ===，以正确处理 NaN
        if (v === search || (v !== v && search !== search)) return true;
      }
      return false;
    });
  }

  // Array.prototype.find：three ×1、three-globe ×3
  if (!has(Array.prototype, "find")) {
    define(Array.prototype, "find", function find(predicate, thisArg) {
      if (!isFn(predicate)) throw new TypeError("Array.prototype.find predicate must be a function");
      var len = this.length >>> 0;
      for (var i = 0; i < len; i++) {
        if (predicate.call(thisArg, this[i], i, this)) return this[i];
      }
      return undefined;
    });
  }

  // ---------------------------------------------------------------- [防御补齐]
  // 以下未在真机逐一 typeof 测量，但同属 ES2015+ 且实现廉价；minified 第三方库常用。
  // 全部特性检测，存在的引擎不受影响。

  if (!has(Array.prototype, "findIndex")) {
    define(Array.prototype, "findIndex", function findIndex(predicate, thisArg) {
      if (!isFn(predicate)) throw new TypeError("Array.prototype.findIndex predicate must be a function");
      var len = this.length >>> 0;
      for (var i = 0; i < len; i++) {
        if (predicate.call(thisArg, this[i], i, this)) return i;
      }
      return -1;
    });
  }

  if (!has(String.prototype, "endsWith")) {
    define(String.prototype, "endsWith", function endsWith(search, end) {
      if (search === undefined || search === null) {
        throw new TypeError("String.prototype.endsWith search must not be null or undefined");
      }
      var s = String(search);
      var e = end === undefined ? this.length : Number(end);
      if (isNaN(e)) e = this.length;
      if (e < 0) e = 0;
      if (e > this.length) e = this.length;
      if (s.length > e) return false;
      return this.slice(e - s.length, e) === s;
    });
  }

  if (!has(String.prototype, "trimStart")) {
    // JS 正则的 \s 已含 ﻿ 与 \xA0，无需额外字符类
    define(String.prototype, "trimStart", function trimStart() {
      return this.replace(/^\s+/, "");
    });
    define(String.prototype, "trimLeft", String.prototype.trimStart);
  }
  if (!has(String.prototype, "trimEnd")) {
    define(String.prototype, "trimEnd", function trimEnd() {
      return this.replace(/\s+$/, "");
    });
    define(String.prototype, "trimRight", String.prototype.trimEnd);
  }

  if (!has(String.prototype, "padStart")) {
    define(String.prototype, "padStart", function padStart(len, fill) {
      var s = String(this);
      var target = len - s.length;
      if (target <= 0) return s;
      var pad = String(fill === undefined ? " " : fill);
      if (pad === "") return s;
      var out = "";
      while (out.length < target) out += pad;
      return out.slice(0, target) + s;
    });
  }
  if (!has(String.prototype, "padEnd")) {
    define(String.prototype, "padEnd", function padEnd(len, fill) {
      var s = String(this);
      var target = len - s.length;
      if (target <= 0) return s;
      var pad = String(fill === undefined ? " " : fill);
      if (pad === "") return s;
      var out = "";
      while (out.length < target) out += pad;
      return s + out.slice(0, target);
    });
  }

  if (!has(Array.prototype, "fill")) {
    define(Array.prototype, "fill", function fill(value, start, end) {
      var len = this.length >>> 0;
      var a = start === undefined ? 0 : (Number(start) || 0);
      var b = end === undefined ? len : (Number(end) || 0);
      if (a < 0) a = 0;
      if (b > len) b = len;
      for (var i = a; i < b; i++) this[i] = value;
      return this;
    });
  }

  if (!has(Object, "fromEntries")) {
    define(Object, "fromEntries", function fromEntries(entries) {
      if (entries == null) throw new TypeError("Object.fromEntries requires an iterable");
      var out = {};
      var it = typeof Symbol !== "undefined" && Symbol.iterator ? entries[Symbol.iterator] : null;
      if (isFn(it)) {
        var step, cursor = it.call(entries);
        while (!(step = cursor.next()).done) {
          var pair = step.value;
          if (pair != null && pair.length > 0) out[pair[0]] = pair[1];
        }
      } else {
        for (var i = 0; i < entries.length; i++) {
          if (entries[i] != null) out[entries[i][0]] = entries[i][1];
        }
      }
      return out;
    });
  }

  if (!has(Number, "isNaN")) {
    define(Number, "isNaN", function isNaN(n) { return typeof n === "number" && n !== n; });
  }
  if (!has(Number, "isInteger")) {
    define(Number, "isInteger", function isInteger(n) {
      return typeof n === "number" && isFinite(n) && Math.floor(n) === n;
    });
  }
  if (!has(Math, "trunc")) {
    define(Math, "trunc", function trunc(n) {
      n = Number(n);
      if (isNaN(n) || n === 0 || !isFinite(n)) return n;
      return n < 0 ? Math.ceil(n) : Math.floor(n);
    });
  }

  if (!has(Array.prototype, "flat")) {
    define(Array.prototype, "flat", function flat(depth) {
      var d = depth === undefined ? 1 : Number(depth);
      var out = [];
      for (var i = 0; i < this.length; i++) {
        var v = this[i];
        if (d > 0 && isArr(v)) {
          var sub = Array.prototype.flat.call(v, d - 1);
          for (var j = 0; j < sub.length; j++) out.push(sub[j]);
        } else {
          out.push(v);
        }
      }
      return out;
    });
  }
  if (!has(Array.prototype, "flatMap")) {
    define(Array.prototype, "flatMap", function flatMap(fn, thisArg) {
      if (!isFn(fn)) throw new TypeError("Array.prototype.flatMap mapper must be a function");
      var mapped = [];
      for (var i = 0; i < this.length; i++) mapped.push(fn.call(thisArg, this[i], i, this));
      return Array.prototype.flat.call(mapped, 1);
    });
  }

  // ---------------------------------------------------------------- [刻意不补]
  // Proxy：无法真正实现。core-js 那种空壳会让 `typeof Proxy === "function"` 的特性检测
  //       误判为「支持」，随后 `new Proxy(...)` 拿到的却是坏对象，行为比「不支持」更糟。
  //       实测 three r160 / three-globe 2.45.2 均未使用 Proxy，故不补。
  // 若将来某个库真的需要，只能走能力分流（老 WebView 换渲染路径），不能靠 polyfill 糊。
})(this);
