// =====================================================================
// Slow Roads 方案 · 页面行为验证脚本（A 组）
// schema: sr-page-check v1   （2026-09-24）
//
// 用法：
//   1) 打开 https://slow-roads.pages.dev/（桌面 Chrome/Edge 最省事）
//   2) 按 F12 打开 DevTools，切到 Console（首次粘贴会要求输入 allow pasting）
//   3) 整段粘贴本脚本，回车（控制台支持多行粘贴）
//   4) 脚本会自动：判定 Begin 屏 →（是则点击 Begin）→ 15 秒时间轴采样
//   5) 结束后控制台打印一份 JSON；若环境支持 copy() 会自动复制到剪贴板
//
// 把整段 JSON 发回对话即可。备用命令见文件末尾 window.__sr。
// 注意：本脚本会替你点一次 Begin（不点就无法验证「Begin→画面」过程）。
//       桌面 Chromium 与电视/手机内核可能不同，设备侧结论仍须真机复核。
// =====================================================================
(function () {
  var out = {
    schema: 'sr-page-check v1',
    url: location.href,
    startedAt: new Date().toISOString(),
    t0: Math.round(performance.now())
  };

  function safe(fn, d) {
    try { return fn(); } catch (e) { return { err: String((e && e.message) || e) }; }
  }

  // ---- 复刻文档 §6.3 N3 / §6.4.2 的探测逻辑 ----
  function canvasInfo() {
    var c = document.querySelector('canvas');
    if (!c) return { exists: false };
    var gl = null, glErr = null;
    try { gl = c.getContext('webgl2') || c.getContext('webgl') || c.getContext('experimental-webgl'); }
    catch (e) { glErr = String((e && e.message) || e); }
    return {
      exists: true, width: c.width, height: c.height,
      cssW: c.clientWidth, cssH: c.clientHeight,
      gl: !!gl, glErr: glErr
    };
  }

  function beginElements() {
    var els = document.querySelectorAll('button, [role="button"], a');
    var list = [];
    for (var i = 0; i < els.length && i < 8; i++) {
      var t = (els[i].innerText || els[i].textContent || '').trim();
      list.push({
        tag: els[i].tagName,
        text: t.slice(0, 60),
        hasBegin: t.toLowerCase().indexOf('begin') >= 0
      });
    }
    return { count: els.length, samples: list };
  }

  function probeBegin() {
    if (document.querySelector('canvas')) return false;
    var t = (document.body.innerText || '').toLowerCase();
    var els = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < els.length; i++) {
      var txt = (els[i].innerText || els[i].textContent || '').toLowerCase();
      if (txt.indexOf('begin') >= 0) return true;
    }
    return t.indexOf('begin') >= 0;
  }

  function clickBegin() {
    var els = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < els.length; i++) {
      var txt = (els[i].innerText || els[i].textContent || '').toLowerCase();
      if (txt.indexOf('begin') >= 0) {
        els[i].click();
        return { hit: true, tag: els[i].tagName, text: (els[i].innerText || '').slice(0, 60) };
      }
    }
    try {
      if (document.body && document.body.click) {
        document.body.click();
        return { hit: false, bodyClicked: true };
      }
    } catch (e) { }
    return { hit: false, bodyClicked: false };
  }

  function audioInfo() {
    var m = document.querySelectorAll('audio, video');
    var arr = [];
    for (var i = 0; i < m.length; i++) {
      arr.push({ tag: m[i].tagName, muted: m[i].muted, vol: m[i].volume, paused: m[i].paused });
    }
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return { media: arr, AC: { supported: false } };
    try {
      var ac = new AC();
      var st = ac.state;
      if (ac.close) ac.close();
      return { media: arr, AC: { supported: true, state: st } };
    } catch (e) {
      return { media: arr, AC: { supported: true, err: String((e && e.message) || e) } };
    }
  }

  // 验证 §6.3 N3 的关键断言：KeyboardEvent 构造器传的 keyCode 是否恒为 0
  function keyEventCtorTest() {
    try {
      var e1 = new KeyboardEvent('keydown', { key: 'f', code: 'KeyF', keyCode: 70, which: 70 });
      var e2 = new KeyboardEvent('keydown', { key: 'f', code: 'KeyF' });
      Object.defineProperty(e2, 'keyCode', { get: function () { return 70; } });
      Object.defineProperty(e2, 'which', { get: function () { return 70; } });
      return {
        ctor: { keyCode: e1.keyCode, which: e1.which, key: e1.key, code: e1.code, isTrusted: e1.isTrusted },
        afterDefineProperty: { keyCode: e2.keyCode, which: e2.which }
      };
    } catch (e) { return { err: String((e && e.message) || e) }; }
  }

  // 派发到 window/document/activeElement 是否报错（用无绑定键 X，避免误触发 F）
  function dispatchTest() {
    try {
      var e = new KeyboardEvent('keydown', { key: 'X', code: 'KeyX', bubbles: true, cancelable: true });
      Object.defineProperty(e, 'keyCode', { get: function () { return 88; } });
      var r = { window: 'ok', document: 'ok', activeElement: null };
      window.dispatchEvent(e);
      document.dispatchEvent(e);
      var a = document.activeElement;
      r.activeElement = a ? (a.tagName + (a.id ? '#' + a.id : '')) : null;
      if (a && a !== document.body && a.dispatchEvent) a.dispatchEvent(e);
      return r;
    } catch (e) { return { err: String((e && e.message) || e) }; }
  }

  out.inspect = {
    readyState: document.readyState,
    hash: location.hash,
    canvas: safe(canvasInfo),
    probeBegin: safe(probeBegin),
    beginElements: safe(beginElements),
    bodyTextHasBegin: safe(function () {
      return ((document.body.innerText || '').toLowerCase().indexOf('begin') >= 0);
    }),
    bodyTextPreview: (document.body.innerText || '').slice(0, 200),
    audio: safe(audioInfo),
    keyEvent: safe(keyEventCtorTest),
    dispatch: safe(dispatchTest)
  };

  if (out.inspect.probeBegin) {
    out.autoClickBegin = safe(clickBegin);
    out.clickedAtMs = Math.round(performance.now());
  }

  // ---- 时间轴采样：Begin → 画面就绪的耗时（近似，会低估真实 onPageFinished→ready）----
  var timeline = [];
  function snap(tag) {
    var c = document.querySelector('canvas');
    timeline.push({
      tag: tag,
      ms: Math.round(performance.now()),
      canvas: c ? (c.width + 'x' + c.height) : null,
      probeBegin: safe(probeBegin),
      hash: location.hash
    });
  }
  function finish() {
    out.timeline = timeline;
    var first = null;
    for (var i = 0; i < timeline.length; i++) {
      if (timeline[i].canvas) { first = timeline[i]; break; }
    }
    out.derived = {
      firstCanvasSample: first ? first.tag : null,
      note: 'canvas 就绪耗时从「脚本粘贴时刻」起算，会低估页面 onPageFinished→ready 的真实耗时'
    };
    window.__SR_RESULT__ = out;
    var s = JSON.stringify(out, null, 2);
    console.log('%c[SlowRoads 验证结果 · 请整段复制下面 JSON 发回]', 'color:#0a0;font-weight:bold;font-size:13px');
    console.log(s);
    try {
      copy(s);
      console.log('%c[已自动复制到剪贴板，直接粘贴发回即可]', 'color:#0a0;font-weight:bold');
    } catch (e) {
      console.log('[当前环境没有 copy()，请手动选中上面 JSON 后复制]');
    }
  }

  snap('T0');
  [2000, 5000, 10000, 15000].forEach(function (d, i) {
    setTimeout(function () {
      snap('T' + (i + 1));
      if (i === 3) finish();
    }, d);
  });

  // ---- 备用命令（想单独重跑某项时用）----
  function sendKeyJS(key, code, kc) {
    function mk(t) {
      var e = new KeyboardEvent(t, { bubbles: true, cancelable: true, key: key, code: code });
      Object.defineProperty(e, 'keyCode', { get: function () { return kc; } });
      Object.defineProperty(e, 'which', { get: function () { return kc; } });
      return e;
    }
    var d = mk('keydown'), u = mk('keyup');
    window.dispatchEvent(d); window.dispatchEvent(u);
    document.dispatchEvent(d); document.dispatchEvent(u);
    var a = document.activeElement;
    if (a && a !== document.body && a.dispatchEvent) { a.dispatchEvent(d); a.dispatchEvent(u); }
    return true;
  }
  var KEYMAP = { f: ['f', 'KeyF', 70], e: ['e', 'KeyE', 69], q: ['q', 'KeyQ', 81], c: ['c', 'KeyC', 67], m: ['m', 'KeyM', 77] };
  window.__sr = {
    result: function () { console.log(JSON.stringify(window.__SR_RESULT__, null, 2)); },
    inspect: function () { console.log(JSON.stringify(out.inspect, null, 2)); },
    clickBegin: function () { console.log(JSON.stringify(clickBegin(), null, 2)); },
    sendKey: function (k) { var s = KEYMAP[k] || KEYMAP.f; return sendKeyJS(s[0], s[1], s[2]); },
    muteTest: function () {
      var r = { note: '跑完听一下游戏是否真的没声了；刷新页面可恢复声音' };
      var m = document.querySelectorAll('audio, video'), arr = [];
      for (var i = 0; i < m.length; i++) { m[i].muted = true; m[i].volume = 0; arr.push({ tag: m[i].tagName, muted: m[i].muted, vol: m[i].volume }); }
      r.media = arr;
      var AC = window.AudioContext || window.webkitAudioContext;
      r.AC = null;
      if (AC) {
        try {
          var ac = new AC();
          r.AC = { state: ac.state };
          if (ac.suspend) { ac.suspend(); r.AC.afterSuspend = ac.state; }
          if (ac.close) ac.close();
        } catch (e) { r.AC = { err: String((e && e.message) || e) }; }
      }
      console.log(JSON.stringify(r, null, 2));
      return r;
    }
  };
  console.log('[slow-roads-page-check] 已启动；15 秒后输出结果。备用命令：__sr.result() / __sr.inspect() / __sr.clickBegin() / __sr.sendKey("f"|"e"|"q"|"c"|"m") / __sr.muteTest()');
})();