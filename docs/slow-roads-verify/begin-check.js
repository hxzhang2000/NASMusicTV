// =====================================================================
// Slow Roads 方案 · Begin 欢迎屏复测脚本（第二轮）
// schema: sr-begin-check v1   （2026-09-24）
//
// 用途：确认 Begin 阶段到底有没有 canvas（决定 probeBegin/probeReady
//       的判定逻辑是否需要改），并观察点击 Begin 后 canvas 的尺寸变化。
//
// 用法：
//   1) 新标签页打开 https://slow-roads.pages.dev/（地址栏不要留 #hash）
//   2) 页面出 Begin 欢迎屏后立刻 F12 → Console → 粘贴本脚本回车
//   3) 若提示不在 Begin 屏：把地址栏 # 之后全部删掉回车，重跑
//   4) 自动点 Begin → 20 秒采样 → 打印 JSON（自动复制到剪贴板）
//
// 把 JSON 发回对话即可。备用命令：window.__sr_begin
// =====================================================================
(function () {
  var out = { schema: 'sr-begin-check v1', url: location.href, hash: location.hash, at: new Date().toISOString() };

  function canvases() {
    var cs = document.querySelectorAll('canvas'), list = [];
    for (var i = 0; i < cs.length; i++) {
      var c = cs[i], r = null;
      try { r = c.getBoundingClientRect(); } catch (e) {}
      list.push({
        i: i, w: c.width, h: c.height, cssW: c.clientWidth, cssH: c.clientHeight,
        rect: r ? { w: Math.round(r.width), h: Math.round(r.height), top: Math.round(r.top), left: Math.round(r.left) } : null,
        display: getComputedStyle(c).display,
        visible: !!(c.offsetParent || c.getClientRects().length) && getComputedStyle(c).visibility !== 'hidden'
      });
    }
    return list;
  }

  function beginMatches() {
    var els = document.querySelectorAll('button, [role="button"], a, [class*="begin" i], [id*="begin" i]'), list = [];
    for (var i = 0; i < els.length; i++) {
      var t = (els[i].innerText || els[i].textContent || '').trim();
      var cls = els[i].className ? String(els[i].className) : '';
      var id = els[i].id || '';
      var hit = t.toLowerCase().indexOf('begin') >= 0 || /begin/i.test(cls) || /begin/i.test(id);
      if (hit) list.push({ tag: els[i].tagName, text: t.slice(0, 80), cls: cls.slice(0, 40), id: id.slice(0, 40), clickable: !!els[i].click });
    }
    return list;
  }

  function probeBegin() {
    if (document.querySelector('canvas')) return false;
    var t = (document.body.innerText || '').toLowerCase();
    var els = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < els.length; i++) { var x = (els[i].innerText || els[i].textContent || '').toLowerCase(); if (x.indexOf('begin') >= 0) return true; }
    return t.indexOf('begin') >= 0;
  }

  function probeReady() {
    var c = document.querySelector('canvas');
    return !!(c && c.width > 0 && c.height > 0);
  }

  out.state0 = {
    hash: location.hash,
    canvases: canvases(),
    beginMatches: beginMatches(),
    probeBegin_doc: probeBegin(),
    probeReady_doc: probeReady(),
    bodyTextHasBegin: (document.body.innerText || '').toLowerCase().indexOf('begin') >= 0,
    bodyTextPreview: (document.body.innerText || '').slice(0, 300)
  };

  if (!out.state0.probeBegin_doc) {
    console.log('%c[提示] 当前不在 Begin 屏（或已进画面）。想看 Begin 状态：把地址栏 # 之后全部删掉再回车，等出现 Begin 屏后重跑本脚本。', 'color:#c60;font-weight:bold');
    console.log(JSON.stringify(out, null, 2));
    window.__sr_begin = out;
    return;
  }

  function clickBegin() {
    var els = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < els.length; i++) {
      var x = (els[i].innerText || els[i].textContent || '').toLowerCase();
      if (x.indexOf('begin') >= 0) { els[i].click(); return { hit: true, tag: els[i].tagName, text: (els[i].innerText || '').slice(0, 80) }; }
    }
    try { if (document.body && document.body.click) { document.body.click(); return { hit: false, bodyClicked: true }; } } catch (e) {}
    return { hit: false };
  }

  out.clicked = clickBegin();
  out.timeline = [{
    ms: 0, hash: location.hash,
    canvases: canvases().map(function (c) { return c.w + 'x' + c.h; }),
    probeBegin: probeBegin(), probeReady: probeReady()
  }];

  [2000, 5000, 10000, 20000].forEach(function (d, i) {
    setTimeout(function () {
      out.timeline.push({
        ms: d, hash: location.hash,
        canvases: canvases().map(function (c) { return c.w + 'x' + c.h; }),
        probeBegin: probeBegin(), probeReady: probeReady()
      });
      if (i === 3) {
        var s = JSON.stringify(out, null, 2);
        console.log('%c[SlowRoads Begin 复测结果 · 整段复制发回]', 'color:#0a0;font-weight:bold;font-size:13px');
        console.log(s);
        try { copy(s); console.log('[已复制到剪贴板]'); } catch (e) { console.log('[无 copy()，请手动选中复制]'); }
      }
    }, d);
  });
})();