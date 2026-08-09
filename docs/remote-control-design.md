# 手机点歌与队列编辑 -- 功能设计方案

> 状态：已定稿，待开发
>
> 在 K 歌和 MTV 模式下，电视右上角显示二维码，手机扫码后浏览器打开控制页，可查看/调整播放队列、搜索歌曲加入队列。

---

## 一、需求概述

### 1.1 痛点

电视遥控器适合"选"和"确认"，但不适合"输入"。搜一首歌要按几十次方向键，调整队列顺序更是噩梦。

手机有全键盘、有触摸屏、有流畅的列表交互 -- 但手机上没有这个 App。

**解法**：电视跑一个轻量 HTTP 服务器，手机扫码后用浏览器控制。不需要装 App，不需要注册，扫完即用。

### 1.2 已有基础设施

项目中已有多套"手机扫码 -> 浏览器交互 -> TV 接收"的先例：

| 已有功能 | 服务器 | 端口 | 用途 |
|---|---|---|---|
| `LocalInputServer` | NanoHTTPD | 18080 | 手机扫码输入文字（搜索框打字） |
| `BackupTransferServer` | NanoHTTPD | 18081 | 手机扫码上传/下载备份文件 |

依赖也都有：
- `org.nanohttpd:nanohttpd:2.3.1` -- 轻量 HTTP 服务器
- `com.google.zxing:core:3.5.3` -- 二维码生成（`QrCodeGenerator.kt`）

本方案复用同一套技术栈，新增一个 `RemoteControlServer`。

### 1.3 用户流程

```
电视端（K 歌/MTV 模式）
  ├─ 遥控器操作时 -> 右上角显示二维码（5-10 秒后自动隐藏）
  └─ 二维码内容：http://<TV-IP>:18082/#<session-token>

手机端
  ├─ 相机扫码 -> 浏览器打开控制页
  ├─ 页面 1：播放队列（当前歌曲高亮，点击切歌，长按拖拽排序）
  └─ 页面 2：搜索（输入关键词，NAS + 网络同时搜，结果分组显示，点击加入队列）
```

---

## 二、架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────┐
│  TV 端（NASMusicTV App）             │
│                                     │
│  ┌─────────────────────────────┐    │
│  │ RemoteControlServer         │    │
│  │ (NanoHTTPD, port 18082)     │    │
│  │                             │    │
│  │ ├─ GET /  -> 控制页 HTML     │    │
│  │ ├─ GET /api/queue  -> 队列   │    │
│  │ ├─ POST /api/queue/play     │    │
│  │ ├─ POST /api/queue/move     │    │
│  │ ├─ POST /api/queue/add      │    │
│  │ ├─ GET /api/search?q=...    │    │
│  │ └─ GET /api/status  -> 状态  │    │
│  └──────────┬──────────────────┘    │
│             │ 回调                   │
│  ┌──────────▼──────────────────┐    │
│  │ MainViewModel               │    │
│  │ ├─ playerManager.queue      │    │
│  │ ├─ playerManager.playQueue()│    │
│  │ ├─ playerManager.moveTo()   │    │
│  │ ├─ playerManager.addToQueue│    │
│  │ ├─ backendAdapter.search()  │    │
│  │ └─ networkMusicManager.search()│ │
│  └─────────────────────────────┘    │
│                                     │
│  ┌─────────────────────────────┐    │
│  │ QrCodeGenerator (ZXing)     │    │
│  │ 生成 http://IP:18082/#token │    │
│  └─────────────────────────────┘    │
└─────────────────────────────────────┘
               │ WiFi 局域网
               ▼
┌─────────────────────────────────────┐
│  手机端（浏览器，无需安装 App）       │
│                                     │
│  ┌── 控制页（单页 HTML） ──────────┐ │
│  │                               │ │
│  │  [播放队列]  [搜索]           │ │
│  │                               │ │
│  │  Tab 1: 队列                   │ │
│  │  ▸ ♪ 晴天 (周杰伦) ← 当前     │ │
│  │    ♪ 七里香 (周杰伦)          │ │
│  │    ♪ 稻香 (周杰伦)            │ │
│  │    [拖拽排序]                 │ │
│  │                               │ │
│  │  Tab 2: 搜索                   │ │
│  │  [输入框: 晴天________] [搜]  │ │
│  │                               │ │
│  │  📁 NAS 曲库 (3)              │ │
│  │    ♪ 晴天 (周杰伦) [加入队列] │ │
│  │    ♪ 晴天 (孙燕姿) [加入队列] │ │
│  │                               │ │
│  │  🌐 网络搜索 (5)              │ │
│  │    ♪ 晴天 (周杰伦) [加入队列] │ │
│  │    ♪ 晴天 (纯音乐) [加入队列] │ │
│  └───────────────────────────────┘ │
└─────────────────────────────────────┘
```

### 2.2 TV 端组件

#### RemoteControlServer

```kotlin
class RemoteControlServer(
    private val port: Int = DEFAULT_PORT
) {
    companion object {
        const val DEFAULT_PORT = 18082
    }

    private var server: Impl? = null
    private var sessionToken: String = ""

    /**
     * 启动服务器
     * @param callbacks 操作回调（播放/移动/添加/搜索）
     * @return 服务器 URL（含 token），用于生成二维码；null 启动失败
     */
    fun start(callbacks: RemoteCallbacks): String? {
        sessionToken = UUID.randomUUID().toString().take(8)
        // ... 启动 NanoHTTPD
        val ip = getLocalIpAddress()  // WifiManager 获取 WiFi IP
        return "http://$ip:$port/#$sessionToken"
    }

    fun stop() { ... }

    /** 供 QrCodeGenerator 使用的 URL */
    fun getUrl(): String? { ... }
}

interface RemoteCallbacks {
    fun getQueue(): List<Song>
    fun getCurrentIndex(): Int
    fun playAt(index: Int)
    fun moveQueue(from: Int, to: Int)
    fun addToQueue(song: Song)
    suspend fun search(keyword: String): SearchResult  // NAS + 网络并发搜索
}

data class SearchResult(
    val nasResults: List<Song>,
    val networkResults: List<Song>
)
```

#### 生命周期

- **启动时机**：App 启动时（`NasMusicApp.onCreate`），与 `networkMusicManager` 等同时初始化
- **常驻运行**：NanoHTTPD 极轻量，常驻不耗资源；二维码只在 K 歌/MTV 模式显示
- **停止时机**：App 退出时

#### 二维码显示

```kotlin
// MainViewModel
val remoteControlUrl: StateFlow<String?>  // server.getUrl()，启动后就有值

// AppRoot / KaraokePlaybackScreen / MvPlaybackScreen
// 复用 controlsVisible 逻辑（5 秒无操作虚化）
// controlsVisible = true 时，右上角显示二维码
// controlsVisible = false 时，二维码也虚化隐藏
```

二维码尺寸约 80-100dp，右上角，跟随控制条虚化逻辑（`controlsAlpha`）。

### 2.3 手机端 Web 页面

**零依赖**：纯 HTML + CSS + JavaScript，不需要 React/Vue/jQuery。单文件内嵌，由 `GET /` 直接返回。

```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>NASMusicTV 遥控</title>
    <style>
        /* 暗色主题，适配手机深色模式 */
        /* Tab 切换、列表卡片、搜索框、加入队列按钮 */
    </style>
</head>
<body>
    <!-- Tab 栏 -->
    <div class="tabs">
        <button onclick="showTab('queue')">播放队列</button>
        <button onclick="showTab('search')">搜索</button>
    </div>

    <!-- Tab 1: 队列 -->
    <div id="queue-tab">
        <div id="now-playing"></div>
        <ul id="queue-list"></ul>
    </div>

    <!-- Tab 2: 搜索 -->
    <div id="search-tab">
        <input type="text" id="search-input" placeholder="搜索歌曲...">
        <button onclick="doSearch()">搜索</button>
        <div id="nas-results"></div>
        <div id="network-results"></div>
    </div>

    <script>
        const TOKEN = window.location.hash.slice(1);
        const BASE = window.location.origin;

        // 轮询队列状态（每 3 秒）
        async function pollQueue() { ... }

        // 搜索（NAS + 网络同时）
        async function doSearch() {
            const q = document.getElementById('search-input').value;
            const res = await fetch(`${BASE}/api/search?q=${q}&token=${TOKEN}`);
            const data = await res.json();
            renderResults(data.nasResults, data.networkResults);
        }

        // 播放指定歌曲
        async function playAt(index) { ... }

        // 移动队列顺序（拖拽）
        async function moveQueue(from, to) { ... }

        // 加入队列
        async function addToQueue(song) { ... }
    </script>
</body>
</html>
```

---

## 三、API 设计

所有 API 需带 `token` 参数（URL query 或 header），与二维码中的 sessionToken 匹配。

### 3.1 获取队列

```
GET /api/queue?token=xxx

Response:
{
  "currentIndex": 2,
  "isPlaying": true,
  "songs": [
    { "id": "s1", "title": "晴天", "artist": "周杰伦", "durationMs": 240000, "isNetworkSong": false },
    { "id": "s2", "title": "七里香", "artist": "周杰伦", "durationMs": 300000, "isNetworkSong": false },
    ...
  ]
}
```

### 3.2 播放指定歌曲

```
POST /api/queue/play?token=xxx
Body: { "index": 2 }

Response: { "ok": true }
```

### 3.3 移动队列顺序

```
POST /api/queue/move?token=xxx
Body: { "from": 0, "to": 3 }

Response: { "ok": true }
```

### 3.4 加入队列

```
POST /api/queue/add?token=xxx
Body: {
  "song": {
    "id": "ntwk_meting_xxx",
    "title": "晴天",
    "artist": "周杰伦",
    "isNetworkSong": true,
    "networkSource": "meting",
    "networkId": "xxx"
  }
}

Response: { "ok": true, "queueSize": 15 }
```

### 3.5 搜索（NAS + 网络并发）

```
GET /api/search?q=晴天&token=xxx

Response:
{
  "nasResults": [
    { "id": "jellyfin-123", "title": "晴天", "artist": "周杰伦", "album": "叶惠美", "durationMs": 240000 },
    ...
  ],
  "networkResults": [
    { "id": "ntwk_meting_xxx", "title": "晴天", "artist": "周杰伦", "isNetworkSong": true, "networkSource": "meting" },
    ...
  ]
}
```

TV 端实现：
```kotlin
suspend fun search(keyword: String): SearchResult = coroutineScope {
    val nasDeferred = async { backendAdapter?.searchSongs(keyword) ?: emptyList() }
    val netDeferred = async { networkMusicManager.search(keyword) }
    SearchResult(nasDeferred.await(), netDeferred.await())
}
```

### 3.6 播放状态（轻量轮询）

```
GET /api/status?token=xxx

Response:
{
  "currentIndex": 2,
  "isPlaying": true,
  "title": "晴天",
  "artist": "周杰伦",
  "positionMs": 45000,
  "durationMs": 240000
}
```

---

## 四、UI 设计

### 4.1 电视端 -- 二维码显示

**位置**：屏幕右上角，距上边缘 24dp、距右边缘 24dp

**尺寸**：96dp × 96dp（足够手机扫码，不过大挡画面）

**显示逻辑**：

```kotlin
// 复用 MvPlaybackScreen / KaraokePlaybackScreen 的 controlsVisible 逻辑
// controlsVisible = true -> 二维码可见
// controlsVisible = false -> 二维码虚化至 0.15 alpha（与控制条同步）

if (remoteControlUrl != null) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(24.dp)
            .size(96.dp)
            .alpha(controlsAlpha)  // 跟随控制条虚化
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        Image(
            bitmap = qrCodeBitmap.asImageBitmap(),
            contentDescription = "扫码遥控"
        )
    }
}
```

**只在 K 歌 / MTV 模式显示**：普通播放页不显示（遥控器在普通播放页操作足够方便）。

### 4.2 手机端 -- 控制页布局

```
┌─────────────────────────────┐
│  🎵 NASMusicTV 遥控          │
│                             │
│  [  播放队列  ] [  搜索  ]   │
│ ─────────────────────────── │
│                             │
│  ▶ ♪ 晴天 - 周杰伦  04:00   │  <- 当前播放（高亮）
│    ♪ 七里香 - 周杰伦 05:00  │
│    ♪ 稻香 - 周杰伦  03:30   │
│    ♪ NET 晴天 - 周杰伦      │  <- 网络歌曲带 NET 标识
│                             │
│  ┄┄┄ 拖拽排序 ┄┄┄           │
│                             │
└─────────────────────────────┘

搜索 Tab:
┌─────────────────────────────┐
│  [晴天_______________] [🔍] │
│                             │
│  📁 NAS 曲库 (3)            │
│  ♪ 晴天 - 周杰伦 [叶惠美]   │
│  ┊ [加入队列]               │
│  ♪ 晴天 - 孙燕姿 [我的爱]   │
│  ┊ [加入队列]               │
│                             │
│  🌐 网络搜索 (5)            │
│  ♪ 晴天 - 周杰伦 (NET)      │
│  ┊ [加入队列]               │
│  ♪ 晴天 - 纯音乐 (NET)      │
│  ┊ [加入队列]               │
│                             │
└─────────────────────────────┘
```

**交互细节**：
- 队列列表项点击 -> 播放该歌曲
- 队列列表项长按拖拽 -> 调整顺序
- 搜索结果点击「加入队列」-> POST `/api/queue/add` -> 提示"已加入"
- 每 3 秒轮询 `/api/queue` 刷新队列和当前播放状态
- 搜索结果不轮询（静态）

---

## 五、数据流

### 5.1 手机 -> TV（控制指令）

```
手机浏览器
  -> fetch('http://TV-IP:18082/api/queue/play?token=xxx', {method:'POST', body:{index:2}})
  -> NanoHTTPD 收到请求
  -> 校验 token
  -> callbacks.playAt(2)
  -> MainViewModel.playQueue(existingQueue, 2)
  -> PlayerManager.seekTo(2, 0) + play()
  -> 返回 {ok: true}
```

### 5.2 TV -> 手机（状态同步）

```
手机浏览器每 3 秒
  -> fetch('http://TV-IP:18082/api/queue?token=xxx')
  -> NanoHTTPD 收到请求
  -> 校验 token
  -> callbacks.getQueue() + callbacks.getCurrentIndex()
  -> 返回 JSON
  -> 浏览器更新 DOM
```

### 5.3 搜索流程

```
手机输入"晴天" -> 点击搜索
  -> fetch('/api/search?q=晴天&token=xxx')
  -> TV 端并发：
     ├─ async { backendAdapter.searchSongs("晴天") }   // NAS 搜索
     └─ async { networkMusicManager.search("晴天") }    // 网络搜索
  -> 等两个都返回（或超时 5 秒）
  -> 合并结果 JSON 返回
  -> 手机分组渲染
```

---

## 六、安全考虑

| 风险 | 对策 |
|---|---|
| 局域网内其他设备恶意控制 | session token（8 位随机），二维码 URL 含 token，所有 API 校验 |
| 局域网外访问 | NanoHTTPD 绑定 WiFi IP，不绑定 0.0.0.0，仅局域网可达 |
| 敏感数据泄露 | 服务器不返回密码/Token 等敏感字段；歌曲信息不含 streamUrl |
| 端口冲突 | 18082 被占时自动尝试 18083-18090 |

### Token 生命周期

- App 启动时生成新 token（`UUID.randomUUID().toString().take(8)`）
- 二维码 URL 包含 token：`http://IP:18082/#abc12345`
- 浏览器从 URL hash 中提取 token，所有 API 请求带上
- App 重启后 token 变化，旧二维码失效，需重新扫码

---

## 七、技术依赖

| 依赖 | 状态 | 用途 |
|---|---|---|
| NanoHTTPD 2.3.1 | **已有** | HTTP 服务器 |
| ZXing 3.5.3 | **已有** | 二维码生成 |
| QrCodeGenerator.kt | **已有** | 二维码 Bitmap 生成 |
| getLocalIpAddress() | **需新增** | 获取 WiFi IP（参考 LocalInputServer 的实现） |
| RemoteControlServer.kt | **需新增** | ~200 行，HTTP 路由 + 回调 |
| 控制页 HTML | **需新增** | ~300 行单文件 HTML+CSS+JS |
| 二维码 UI 组件 | **需新增** | ~30 行 Composable，右上角 Image |

**零新依赖**，全部复用已有库。

---

## 八、实施计划

### Step 1：RemoteControlServer + API

新增 `net/RemoteControlServer.kt`：
- NanoHTTPD 子类，端口 18082
- 路由：`GET /` 返回内嵌 HTML；`GET/POST /api/*` 返回 JSON
- `RemoteCallbacks` 接口，由 MainViewModel 实现
- token 校验

### Step 2：MainViewModel 接入

- `NasMusicApp` 构造 `RemoteControlServer`，`start(callbacks)`
- `MainViewModel` 实现 `RemoteCallbacks`：
  - `getQueue()` -> `playerManager.queue.value`
  - `playAt(index)` -> `playerManager.playQueue(queue, index)`
  - `moveQueue(from, to)` -> 操作 `playerManager.queue` + 重新 `playQueue`
  - `addToQueue(song)` -> `playerManager.addToQueue(song)`
  - `search(keyword)` -> 并发调 `backendAdapter.searchSongs` + `networkMusicManager.search`

### Step 3：二维码 UI

- `MainViewModel.remoteControlUrl: StateFlow<String?>`
- `MvPlaybackScreen` + `KaraokePlaybackScreen` 右上角加二维码 `Image`
- 复用 `controlsAlpha` 虚化逻辑
- `QrCodeGenerator.generateQrBitmap(url, 256)` 生成 Bitmap

### Step 4：手机端控制页

单文件 HTML，内嵌在 `RemoteControlServer` 的 `GET /` 响应中：
- Tab 切换（队列 / 搜索）
- 队列列表：点击播放、拖拽排序
- 搜索框：输入关键词，NAS + 网络分组显示
- 加入队列按钮
- 3 秒轮询队列状态

### Step 5：测试

- 真机：TV 开启 K 歌/MTV -> 手机扫码 -> 浏览器打开 -> 操作队列和搜索
- 边界：端口冲突、token 不匹配、搜索超时、网络歌曲加入队列后 streamUrl 解析

---

## 九、设计决策

1. **普通播放页不显示二维码**。二维码仅在 K 歌 / MTV 模式显示，不额外加设置开关。普通播放页遥控器操作已足够方便，不需要手机介入。

2. **搜索结果不支持试听**。v1 只支持「加入队列」，不支持在手机端试听片段。省去音频流转发的复杂度，后续版本如有需求再考虑。

3. **允许多设备同时连接**。NanoHTTPD 天然支持多连接，多台手机可同时扫码使用，共享同一个 session token。队列操作在后端串行处理（NanoHTTPD 单线程响应），无并发冲突。

4. **网络歌曲 streamUrl 解析复用现有机制**。手机端加入队列的网络歌曲 `streamUrl` 为空，TV 端播放时由 `PlayerManager.onNeedResolveStreamUrl` -> `MainViewModel.resolveAndPlayByIndex` 实时解析，无需额外处理。

5. **二维码尺寸固定 96dp**。大多数 1080p / 4K 电视上手机扫码无压力，不做可配置。如有反馈再调整。
