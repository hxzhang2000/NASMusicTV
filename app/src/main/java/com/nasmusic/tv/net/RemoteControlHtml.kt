package com.nasmusic.tv.net

/**
 * 手机遥控控制页 HTML（单文件内嵌，零外部依赖）
 *
 * 功能：播放队列（当前歌曲高亮 + 点击条目播放 + 上下移排序 + 删除）、
 *       搜索（NAS + 网络并发，分组显示，加入队列）、5 秒轮询。
 */
internal val CONTROL_PAGE_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<title>NASMusicTV 遥控</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0f0f1e;color:#e0e0e0;min-height:100vh}
.header{background:#162032;padding:16px 20px;text-align:center;font-size:18px;font-weight:700;color:#2DD4BF;border-bottom:1px solid #1e2d42}
.tabs{display:flex;background:#162032;border-bottom:1px solid #1e2d42}
.tab{flex:1;padding:14px;text-align:center;font-size:15px;color:#888;cursor:pointer;border-bottom:2px solid transparent;transition:.2s}
.tab.active{color:#2DD4BF;border-bottom-color:#2DD4BF}
.tab-content{display:none;padding:16px}
.tab-content.active{display:block}
/* 队列 */
.now-playing{background:#1e2d42;border-radius:10px;padding:14px;margin-bottom:14px;text-align:center}
.now-playing .label{font-size:12px;color:#888;margin-bottom:4px}
.now-playing .title{font-size:16px;font-weight:600}
.now-playing .artist{font-size:13px;color #aaa;margin-top:2px}
.queue-item{display:flex;align-items:center;background:#162032;border-radius:8px;padding:12px;margin-bottom:8px;transition:.15s}
.queue-item.current{background:#1a3a3a;border:1px solid #2DD4BF}
.queue-item .info{flex:1;min-width:0}
.queue-item .title{font-size:14px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.queue-item .artist{font-size:12px;color:#888;margin-top:2px}
.queue-item .current-icon{color:#2DD4BF;margin-right:8px;font-size:16px}
.queue-item .net-badge{font-size:10px;background:#e94560;color:#fff;padding:1px 5px;border-radius:3px;margin-left:6px}
.queue-item .actions{display:flex;gap:4px;margin-left:8px}
.queue-item .actions button{background:#1e2d42;border:none;color:#aaa;width:32px;height:32px;border-radius:6px;font-size:14px;cursor:pointer;transition:.15s}
.queue-item .actions button:active{background:#2DD4BF;color:#000}
.queue-item.dragging{opacity:.5;z-index:100;box-shadow:0 4px 12px rgba(0,0,0,.4);transition:none}
.queue-item.drop-target{border:2px solid #2DD4BF!important}
.queue-item .info{cursor:pointer}
.queue-item .actions button.del-btn{background:#3a1620;color:#e94560}
.empty{text-align:center;color:#666;padding:40px 20px;font-size:14px}
/* 搜索 */
.search-bar{display:flex;gap:8px;margin-bottom:16px}
.search-bar input{flex:1;padding:14px;font-size:16px;border:2px solid #1e2d42;border-radius:10px;background:#0a0a1a;color:#eee;outline:none}
.search-bar input:focus{border-color:#2DD4BF}
.search-bar button{padding:14px 24px;font-size:16px;background:#2DD4BF;color:#000;border:none;border-radius:10px;font-weight:600;cursor:pointer;transition:.15s}
.search-bar button:active{transform:scale(.96)}
.result-group{margin-bottom:20px}
.result-group h3{font-size:14px;color:#888;margin-bottom:10px;display:flex;align-items:center;gap:6px}
.result-group h3 .count{font-size:12px;color:#555}
.search-item{display:flex;align-items:center;background:#162032;border-radius:8px;padding:12px;margin-bottom:8px}
.search-item .info{flex:1;min-width:0}
.search-item .title{font-size:14px;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.search-item .artist{font-size:12px;color:#888;margin-top:2px}
.search-item .album{font-size:11px;color:#666;margin-top:1px}
.search-item button{background:#1e2d42;border:none;color:#2DD4BF;padding:8px 14px;border-radius:6px;font-size:13px;cursor:pointer;white-space:nowrap;transition:.15s}
.search-item button:active{background:#2DD4BF;color:#000}
.loading{text-align:center;color:#888;padding:20px;font-size:14px}
/* Toast */
.toast{position:fixed;bottom:30px;left:50%;transform:translateX(-50%);background:#2DD4BF;color:#000;padding:10px 24px;border-radius:20px;font-size:14px;font-weight:600;opacity:0;transition:.3s;z-index:999}
.toast.show{opacity:1}
</style>
</head>
<body>
<div class="header">🎵 NASMusicTV 遥控</div>
<div class="tabs">
  <div class="tab active" onclick="showTab('queue')">播放队列</div>
  <div class="tab" onclick="showTab('search')">搜索</div>
</div>

<!-- Tab 1: 队列 -->
<div id="tab-queue" class="tab-content active">
  <div id="now-playing" class="now-playing" style="display:none">
    <div class="label">正在播放</div>
    <div class="title" id="np-title"></div>
    <div class="artist" id="np-artist"></div>
  </div>
  <div id="queue-list"></div>
</div>

<!-- Tab 2: 搜索 -->
<div id="tab-search" class="tab-content">
  <div class="search-bar">
    <input type="text" id="search-input" placeholder="搜索歌曲/歌手..." onkeydown="if(event.key==='Enter')doSearch()">
    <button onclick="doSearch()">搜索</button>
  </div>
  <div id="search-results"></div>
</div>

<div class="toast" id="toast"></div>

<script>
const BASE = location.origin;
var queueData = null;

// Tab 切换
function showTab(name) {
  document.querySelectorAll('.tab-content').forEach(el => el.classList.remove('active'));
  document.querySelectorAll('.tab').forEach(el => el.classList.remove('active'));
  document.getElementById('tab-' + name).classList.add('active');
  event.target.classList.add('active');
}

// Toast
function showToast(msg) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 2000);
}

// 格式化时长
function fmtDuration(ms) {
  if (!ms || ms <= 0) return '';
  var s = Math.floor(ms / 1000);
  var m = Math.floor(s / 60);
  s = s % 60;
  return m + ':' + (s < 10 ? '0' : '') + s;
}

// 获取队列
function fetchQueue() {
  fetch(BASE + '/api/queue')
    .then(r => r.json())
    .then(data => {
      queueData = data;
      renderQueue(data);
    })
    .catch(() => {});
}

// 渲染队列
function renderQueue(data) {
  var list = document.getElementById('queue-list');
  var np = document.getElementById('now-playing');

  if (!data.songs || data.songs.length === 0) {
    list.innerHTML = '<div class="empty">队列为空</div>';
    np.style.display = 'none';
    return;
  }

  // 当前播放信息
  var current = data.songs[data.currentIndex];
  if (current) {
    np.style.display = 'block';
    document.getElementById('np-title').textContent = current.title;
    document.getElementById('np-artist').textContent = current.artist + (current.isNetworkSong ? ' (网络)' : '');
  }

  // 队列列表
  var html = '';
  data.songs.forEach(function(song, i) {
    var isCurrent = i === data.currentIndex;
    var netBadge = song.isNetworkSong ? '<span class="net-badge">NET</span>' : '';
    var dur = fmtDuration(song.durationMs);
    html += '<div class="queue-item' + (isCurrent ? ' current' : '') + '" ontouchstart="onTouchStart(event,' + i + ')">';
    html += isCurrent ? '<span class="current-icon">▶</span>' : '';
    html += '<div class="info" onclick="if(!dragMoved)playAt(' + i + ')"><div class="title">' + song.title + netBadge + '</div>';
    html += '<div class="artist">' + song.artist + (dur ? ' · ' + dur : '') + '</div></div>';
    html += '<div class="actions">';
    if (i > 0) html += '<button onclick="moveItem(' + i + ',' + (i-1) + ')" title="上移">↑</button>';
    if (i < data.songs.length - 1) html += '<button onclick="moveItem(' + i + ',' + (i+1) + ')" title="下移">↓</button>';
    html += '<button class="del-btn" onclick="removeItem(' + i + ')" title="删除">✕</button>';
    html += '</div></div>';
  });
  list.innerHTML = html;
}

// 播放指定歌曲
function playAt(index) {
  fetch(BASE + '/api/queue/play', {
    method: 'POST',
    body: JSON.stringify({index: index})
  }).then(r => r.json()).then(d => {
    if (d.ok) { showToast('已切换'); setTimeout(fetchQueue, 500); }
  });
}

// 移动队列顺序
function moveItem(from, to) {
  fetch(BASE + '/api/queue/move', {
    method: 'POST',
    body: JSON.stringify({from: from, to: to})
  }).then(r => r.json()).then(d => {
    if (d.ok) { setTimeout(fetchQueue, 300); }
  });
}

// 搜索
function doSearch() {
  var q = document.getElementById('search-input').value.trim();
  if (!q) return;
  var results = document.getElementById('search-results');
  results.innerHTML = '<div class="loading">搜索中...</div>';
  fetch(BASE + '/api/search?q=' + encodeURIComponent(q))
    .then(r => r.json())
    .then(data => {
      var html = '';
      // NAS 结果
      if (data.nasResults && data.nasResults.length > 0) {
        html += '<div class="result-group"><h3>📁 NAS 曲库 <span class="count">(' + data.nasResults.length + ')</span></h3>';
        data.nasResults.forEach(function(song) {
          html += renderSearchItem(song);
        });
        html += '</div>';
      }
      // 网络结果
      if (data.networkResults && data.networkResults.length > 0) {
        html += '<div class="result-group"><h3>🌐 网络搜索 <span class="count">(' + data.networkResults.length + ')</span></h3>';
        data.networkResults.forEach(function(song) {
          html += renderSearchItem(song);
        });
        html += '</div>';
      }
      if (!html) html = '<div class="empty">未找到结果</div>';
      results.innerHTML = html;
    })
    .catch(() => { results.innerHTML = '<div class="empty">搜索失败，请重试</div>'; });
}

function renderSearchItem(song) {
  var netBadge = song.isNetworkSong ? '<span class="net-badge">NET</span>' : '';
  var album = song.album ? '<div class="album">' + song.album + '</div>' : '';
  var dur = fmtDuration(song.durationMs);
  var songJson = JSON.stringify(song).replace(/'/g, "\\'");
  return '<div class="search-item">' +
    '<div class="info"><div class="title">' + song.title + netBadge + '</div>' +
    '<div class="artist">' + song.artist + (dur ? ' · ' + dur : '') + '</div>' +
    album + '</div>' +
    '<button onclick=\'addToQueue(' + songJson + ')\'>加入队列</button>' +
    '</div>';
}

// 加入队列
function addToQueue(song) {
  fetch(BASE + '/api/queue/add', {
    method: 'POST',
    body: JSON.stringify({song: song})
  }).then(r => r.json()).then(d => {
    if (d.ok) showToast('已加入队列');
  });
}

// 从队列删除歌曲
function removeItem(index) {
  fetch(BASE + '/api/queue/remove', {
    method: 'POST',
    body: JSON.stringify({index: index})
  }).then(r => r.json()).then(d => {
    if (d.ok) { showToast('已删除'); setTimeout(fetchQueue, 300); }
  });
}

// 触摸拖拽排序（长按 500ms 激活，短滑放行页面滚动）
var dragState = null;
var dragMoved = false;
var dragTimer = null;

function onTouchStart(e, index) {
    var touch = e.touches[0];
    dragState = { index: index, startY: touch.clientY, moved: false, item: e.currentTarget, targetIndex: -1, dragMode: false };
    dragTimer = setTimeout(function() {
        if (dragState && !dragState.moved) {
            dragState.dragMode = true;
            dragState.item.classList.add('dragging');
            if (navigator.vibrate) navigator.vibrate(50);
        }
    }, 500);
}

function onTouchMove(e) {
    if (!dragState) return;
    var touch = e.touches[0];
    var deltaY = touch.clientY - dragState.startY;
    if (!dragState.dragMode) {
        if (Math.abs(deltaY) > 10) { clearTimeout(dragTimer); dragState.moved = true; }
        return;
    }
    e.preventDefault();
    dragMoved = true;
    dragState.item.style.transform = 'translateY(' + deltaY + 'px)';
    var items = document.querySelectorAll('.queue-item');
    var targetIndex = -1;
    for (var i = 0; i < items.length; i++) {
        if (i === dragState.index) continue;
        var rect = items[i].getBoundingClientRect();
        if (touch.clientY >= rect.top && touch.clientY < rect.bottom) { targetIndex = i; break; }
    }
    items.forEach(function(item, i) { if (i !== dragState.index) item.classList.remove('drop-target'); });
    if (targetIndex >= 0) items[targetIndex].classList.add('drop-target');
    dragState.targetIndex = targetIndex;
}

function onTouchEnd(e) {
    if (!dragState) return;
    clearTimeout(dragTimer);
    if (dragState.dragMode) {
        dragState.item.classList.remove('dragging');
        dragState.item.style.transform = '';
        document.querySelectorAll('.queue-item').forEach(function(item) { item.classList.remove('drop-target'); });
        if (dragState.targetIndex >= 0 && dragState.targetIndex !== dragState.index) {
            moveItem(dragState.index, dragState.targetIndex);
        }
        setTimeout(function() { dragMoved = false; }, 300);
    }
    dragState = null;
}

document.addEventListener('touchmove', onTouchMove, { passive: false });
document.addEventListener('touchend', onTouchEnd);

// 启动轮询
fetchQueue();
setInterval(fetchQueue, 5000);
</script>
</body>
</html>
""".trimIndent()
