# 电台 & Jamendo 音乐源开发方案

> 版本：v1.0
> 最后更新：2026-08-22
> 状态：开发提案（待评审）
> 适用范围：NASMusicTV v2.21.0 起

---

## 1. 背景与目标

NASMusicTV 现有内容源：NAS（Jellyfin/Navidrome/Subsonic）、网络音乐（Meting-API 多端点，网易云为主）、百度网盘。本方案新增两个"纯公共 API、零自建后台"音源：

1. **互联网电台（radio-browser.info）**：全球公开广播目录（含中文电台），实时直播流。
2. **Jamendo（CC 独立音乐）**：50 万+ 知识共享授权的独立音乐曲库，官方开放 API。

### 设计原则（硬约束）

- **不自建任何后台服务**：所有功能依赖公共 API / 官方 API，客户端直连。
- 与现有架构一致：复用 `NetworkMusicService` 接口、`NetworkMusicManager` 路由、`Song` 统一模型、`PlayerManager` 播放、`WeatherSubTab` 式子页布局。
- TV 与手机同 APK：新页面均需同时支持遥控器焦点（D-Pad）与触摸。

---

## 2. 音源一：互联网电台（radio-browser.info）

### 2.1 公共 API 事实

| 项 | 内容 |
|---|---|
| 服务 | 免费开源、无 key、多服务器容灾（`all.api.radio-browser.info` DNS 解析出服务器列表，随机选取，如 `de1.api.radio-browser.info`） |
| 协议 | HTTP(S) + JSON |
| User-Agent | 必须携带可识别 UA（如 `NASMusicTV/2.21`），否则可能被拒 |
| 关键端点 | 见附录 A；本站无需覆盖全部，按 §2.3 最小集实现 |
| 统计义务 | 用户点击播放某台时上报 `GET /json/url?uuid=...`（无 body），帮助维持数据库良性 |
| 数据贡献 | 站表由社区提交，`countrycode` 遵循 ISO 3166-1 alpha-2，`tags` 逗号分隔 |

国内可达性：多服务器解析，一般可直连；实现层需做"换服务器重试"（与服务端结构天然契合——见 §2.3）。

### 2.2 数据模型

新增 `data/model/RadioStation.kt`：

```kotlin
data class RadioStation(
    val uuid: String,             // 跨服务器稳定 ID（勿用自增 id）
    val name: String,
    val urlResolved: String,      // 真实流地址（m3u8/mp3/aac）
    val faviconUrl: String?,      // 台标（可作封面）
    val countryCode: String,      // 如 "CN"
    val country: String,          // 中文化显示名可单独映射
    val tags: List<String>,       // 类别标签
    val votes: Int,               // 热度排序
    val bitrate: Int,             // 码率，用于台标角标
    val codec: String             // "MP3"/"AAC"/"HLS"
)
```

### 2.3 架构接入

**新增 `backend/radio/RadioBrowserClient.kt`**（独立轻量模块，不实现 `NetworkMusicService`——电台不是"单曲"）：

```kotlin
class RadioBrowserClient(
    private val prefs: AppPreferences,          // 读自定义服务器列表（可选）
    @IoDispatcher private val io: CoroutineDispatcher
) {
    // 服务器列表解析 + 随机选一台，失败换下一台
    suspend fun resolveServers(): List<String>

    // 中文电台：GET {server}/json/stations/search?countrycode=CN&order=votes&reverse=true&hidebroken=true&limit=50
    suspend fun searchStations(query: String?, countryCode: String?, tag: String?,
                               limit: Int = 50): List<RadioStation>

    // 台标分类（预置常用 tag：流行/摇滚/古典/轻音乐/新闻/中国），GET {server}/json/tags?order=stationcount&reverse=true
    suspend fun popularTags(limit: Int = 20): List<Pair<String, Int>>

    // 播放上报（义务）：GET {server}/json/url?uuid={uuid}
    suspend fun reportClick(station: RadioStation)

    // 直连校验：HEAD urlResolved 检查可达性（可选，用于灰显不可达台）
    suspend fun checkReachable(url: String): Boolean
}
```

**播放方案（关键设计）——电台映射为 `Song` 复用播放链路**：

```kotlin
fun RadioStation.toSong(): Song = Song(
    id = "radio_${uuid}",
    title = name,
    artist = "电台 · ${country}",
    coverUrl = faviconUrl,
    streamUrl = urlResolved,          // 直链，无需 resolvePlayUrl
    isNetworkSong = true,
    networkSource = "radio",
    durationMs = Long.MAX_VALUE       // 无限流；进度条显示"直播"态
)
```

- 播放：`viewModel.playQueue(listOf(song))` → 进入 NowPlaying，使用现有播放器，零新增播放代码。
- **无限流问题**：`durationMs=Long.MAX_VALUE` 会使进度条/countdown 异常。需在 `PlayerManager`/`PlayerControls` 对 `song.networkSource=="radio"` 分支：进度条置灰、点击播放不动 seek、不显示剩余时长（显示"直播"）。改动点：`PlayerControls.ProgressSection` 增加 `isLive: Boolean` 参数（由 `NowPlayingScreen` 依 `currentSong` 判定传入）。
- 歌词/收藏/队列：电台默认无歌词（`resolveLyrics` 直返 null）；可收藏（复用网络收藏，按 `id` 持久化 LRU）；不加载 MTV/K歌（`song.isNetworkSong` 但 `networkSource!="radio"` 时主流程自然不触发）。

### 2.4 UI 设计：`RadioSubTab`（网络音乐页新子 Tab）

新增 `NetworkSubTab.RADIO`，仿 `WeatherSubTab` 的 `LazyVerticalGrid`（2 列）布局：

```
┌──────────────────────────────────────────┐
│ 电台                                      │
│ [中文电台] [流行] [摇滚] [古典] [新闻] ...  │ ← tag 快捷筛选（可横向滑动）
│ ┌──────────┐ ┌──────────┐                │
│ │ 台标/名称 │ │ 台标/名称 │               │ ← 电台卡片（台标大图+名+码率角标）
│ │ 国家·标签 │ │ 国家·标签 │               │
│ └──────────┘ └──────────┘                │
│ ...                                       │
└──────────────────────────────────────────┘
```

- 顶部搜索框（复用 `SearchBar` 模式）→ 全局关键词搜索。
- 默认视图：`countryCode=CN` 按 `votes` 排序（中文电台优先体验）。
- 卡片点击 → 即点即播（`onPlayStation` → `playQueue`），回填"最近播放"。
- 收藏：`networkFavoriteIds` 通用机制（台标缩略化处理）。
- 空态/错误态：服务器不可达时显示"换服务器重试"按钮。

---

## 3. 音源二：Jamendo（CC 独立音乐）

### 3.1 公共 API 事实

| 项 | 内容 |
|---|---|
| 服务 | 官方开放 API v3.0（`https://api.jamendo.com/v3.0/`）；需开发者账号注册应用拿 `client_id`（免费） |
| 限流 | 非商业应用每月 ≤ 35,000 次 API 请求（个人使用足够；搜索/列表页采用 LRU 缓存控制配额） |
| 认证 | 所有读接口：`?client_id={id}&format=json`；无需 OAuth（OAuth 仅写操作用） |
| 内容 | **CC 授权**（CC-BY / CC-BY-NC 等），官方电台/歌单/榜单，完全合法 |
| 客户端集成 | 开发期内接口改动频繁 → `ApiClient` 层独立，参考百度 `ApiProbe` 做字段指纹兜底（可选） |
| 连通性 | ⚠️ 国内直连需实测（站点走 Cloudflare）；若不稳，设置页提供"自定义 API 镜像/代理"（用户自填，非我方后台） |

### 3.2 架构接入

**新增 `backend/network/JamendoService.kt`，实现现有 `NetworkMusicService` 接口**（sourceId = `"jamendo"`）：

```kotlin
class JamendoService(
    private val clientIdProvider: () -> String,     // 从 AppPreferences 读
    private val baseUrl: String = "https://api.jamendo.com/v3.0",
    private val client: OkHttpClient
) : NetworkMusicService {

    override val sourceId = "jamendo"

    // GET /tracks/?client_id=&format=json&search={kw}&limit={n}&include=musicinfo
    //   → 粉丝/标签/时长 → 转 Song（title/artist/coverUrl=image, streamUrl=audio）
    override suspend fun search(keyword: String, limit: Int): List<Song>

    // 搜索时已含直链 audio URL → 直接返回 song.streamUrl
    override suspend fun resolvePlayUrl(song: Song): String? = song.streamUrl

    // Jamendo 提供歌词字段（部分曲目）→ GET /tracks/?id={id}&include=lyrics
    override suspend fun resolveLyrics(song: Song): String?

    // 搜索结果已含封面 → 返回 null 用 song.coverUrl
    override suspend fun resolveCoverUrl(song: Song): String? = null

    // 热门/风格列表（非接口强制）：
    suspend fun hotTracks(limit: Int): List<Song>            // GET /tracks/?order=popularity_total
    suspend fun tracksByTag(tag: String, limit: Int): List<Song>  // GET /tracks/?tags={tag}
    suspend fun popularTags(): List<String>                  // GET /tracks/?groupby=tag … 或预置清单
}
```

**注册**：`NasMusicApp.onCreate` → `networkMusicManager.registerService(jamendoService)`（仿百度网盘 `BaiduNetdiskService` 注册方式；若设置未填 client_id 则不注册或置灰）。

**Song 映射**：

```kotlin
Song(
    id = "jamendo_${track.id}",
    title = track.name,
    artist = track.artistName,
    album = track.albumName,
    coverUrl = track.image,          // 已有/较大图
    streamUrl = track.audio,         // 直链 mp3/m4a
    isNetworkSong = true,
    networkSource = "jamendo",
    durationMs = track.duration,
    networkTags = track.tags          // 若模型有
)
```

### 3.3 配置项

- `AppPreferences` 新增 `keyJamendoClientId`（复用 `getWeatherApiKeySync` 同步读取模式）：
  ```kotlin
  suspend fun setJamendoClientId(id: String) / fun getJamendoClientIdSync(): String
  ```
- 设置页「网络音乐」分区新增：**Jamendo Client ID**（TextInputDialog 输入，与天气 API Key 同交互）+「获取 Client ID」引导文案（devportal.jamendo.com 注册步骤）。未配置时 Jamendo Tab 显示引导卡。

### 3.4 UI 设计：`JamendoSubTab`（网络音乐页新子 Tab）

新增 `NetworkSubTab.JAMENDO`，布局同 `RadioSubTab` 风格：

```
┌────────────────────────────────────────────────┐
│ 独立音乐 (Jamendo)                               │
│ [搜索框...]                                      │
│ 热门榜 ▾   风格: [氛围] [电子] [爵士] [电影配乐]... │ ← 可滑动 tag
│ ┌──────────┐ ┌──────────┐                      │
│ │ 封面      │ │ 封面      │                     │ ← 专辑封面卡片（SongRow 已有样式）
│ │ 歌名·歌手 │ │ 歌名·歌手 │                     │
│ └──────────┘ └──────────┘                      │
└────────────────────────────────────────────────┘
```

- 复用 `SongRow` / `SongCard` 组件（收藏/加入队列/播放）——与现有网络音乐列表一致。
- 默认视图：官方热度榜（popularity_total）+ 预置风格 tag 横排筛选。
- 无 client_id 时显示配置引导（不可见列表）。

---

## 4. TV / 手机双端适配注意

| 点 | 处理 |
|---|---|
| 子 Tab 数量 | 网络音乐页由 4 增到 6（发现/天气/搜索/浏览/电台/独立音乐）——已有 `horizontalScroll` tab 行（v2.20.0 已加），双端均可滑动 |
| 焦点 vs 触摸 | 电台卡片、Jamendo 卡片全用 `FocusableSurface`（v2.20.1 已双兼容） |
| 电台直播态进度条 | `PlayerControls.ProgressSection` 需支持 `isLive`：TV/手机一致显示"直播"、禁 seek |
| 封面 | 电台台标 favicon 可能为小图/坏图：`CoverCarousel` 已有加载失败 fallback，无需改 |
| 设置项 | Jamendo Client ID 输入框 TV 用 `TextInputDialog`（已支持遥控器），手机自动软键盘 |
| 断网重试 | 电台服务器多实例 + 重试按钮；与 `NetworkMonitor` 现有提示联动即可 |

---

## 5. 涉及文件清单（预估）

| 文件 | 动作 | 说明 |
|---|---|---|
| `data/model/RadioStation.kt` | 新增 | 电台数据模型 + `toSong()` |
| `data/model/NetworkSubTab.kt` | 修改 | 新增 `RADIO`、`JAMENDO` 枚举（含 displayNameResId） |
| `res/values/strings.xml` | 修改 | 新 tab/页面文案 + Jamendo 引导文案 |
| `backend/radio/RadioBrowserClient.kt` | 新增 | radio-browser API 客户端（多服务器容灾） |
| `backend/network/JamendoService.kt` | 新增 | 实现 `NetworkMusicService` |
| `backend/network/JamendoModels.kt` | 新增 | API DTO（track/artist/tag） |
| `ui/screens/network/RadioSubTab.kt` | 新增 | 电台子页（仿 WeatherSubTab） |
| `ui/screens/network/JamendoSubTab.kt` | 新增 | Jamendo 子页 |
| `ui/screens/network/NetworkMusicContainer.kt` | 修改 | sub-tab 分支 + 参数透传 |
| `data/prefs/AppPreferences.kt` | 修改 | jamendoClientId + 可选 radio 自定义服务器列表 |
| `ui/screens/SettingsScreen.kt` | 修改 | Jamendo Client ID 配置入口 |
| `ui/viewmodel/MainViewModel.kt` | 修改 | 电台/Jamendo 状态与回调、`selectNetworkSubTab` 分支 |
| `NasMusicApp.kt` | 修改 | 注册 JamendoService |
| `ui/components/PlayerControls.kt` | 修改 | `isLive` 直播态进度条 |
| `ui/screens/NowPlayingScreen.kt` | 修改 | 依 `networkSource=="radio"` 传 `isLive` |

---

## 6. 分阶段实施步骤

**Phase A — 电台（约 2-3 天）**
1. `RadioBrowserClient`：服务器解析 + 中文电台列表 + 搜索
2. `RadioStation.toSong()` + `RadioSubTab`（默认 CN 热门 + tag 筛选 + 搜索）
3. `isLive` 进度条改造（PlayerControls + NowPlaying）
4. MainViewModel 状态/回调接线
5. 编译 + 真机验证（TV 焦点 / 手机触摸 / 直播流播放）

**Phase B — Jamendo（约 2-3 天）**
1. 注册 devportal 账号 → 评审用 client_id 预埋（或用户自助）
2. `JamendoService`（search/hot/tags/lyrics）+ DTO
3. 注册进 `NetworkMusicManager` + 设置页 Client ID 配置
4. `JamendoSubTab`（热门榜 + 风格筛选 + 搜索）
5. 编译 + 真机验证（配额控制：列表 LRU 缓存）

**Phase C — 打磨（约 1 天）**
- 双端回归；Jamendo 国内连通性实测（不稳则提供用户自定义镜像）；电台收藏持久化
- 更新 CHANGELOG / README / technical-overview §10

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| radio-browser 某台流失效（社区数据） | 中 | 播放失败 → 现有 `onPlaybackError` 提示 + 自动尝试同 tag 下一台（可选） |
| Jamendo 国内直连不稳 | 中 | 设置页"自定义 API 镜像"（用户自填，非我方后台）；`ApiProbe` 式字段指纹兜底 |
| Jamendo 每月 35k 配额 | 低 | 搜索结果 LRU 缓存（同一关键词 10 分钟复用）；列表页分页拉取控制单次条数 |
| 电台无限流导致播放器状态异常 | 中 | `isLive` 分支完整走查：进度条/下一首/队列持久化（电台不写入"上次播放队列"恢复） |
| 公共端点 UGC 数据质量（乱码台名） | 低 | 展示层 `EncodingUtils.fixEncoding` 已具备 GBK 兜底 |

---

## 附录 A：radio-browser API 端点参考（最小集）

| 用途 | 端点 | 说明 |
|---|---|---|
| 服务器列表 | `https://all.api.radio-browser.info/json/servers` 或 DNS SRV | 返回服务器 JSON 数组 |
| 搜索(含国家/标签/关键词) | `GET {srv}/json/stations/search?countrycode=CN&order=votes&reverse=true&hidebroken=true&limit=50` | 也支持 `name={kw}&tag={tag}` |
| 类别标签 | `GET {srv}/json/tags?order=stationcount&reverse=true&limit=20` | 返回 `{name, stationcount}` |
| 点击上报（义务） | `GET {srv}/json/url?uuid={uuid}` | 播放时调用，无 body |
| 台详情 | `GET {srv}/json/stations/byuuid?uuids={uuid}` | 按 uuid 查单台（收藏恢复用） |

核心字段：`stationuuid / name / url_resolved / favicon / countrycode / tags / votes / bitrate / codec`。

---

## 附录 B：Jamendo API 参考

| 用途 | 端点 |
|---|---|
| 搜索曲目 | `GET /tracks/?client_id={id}&format=json&search={kw}&limit=20&include=musicinfo` |
| 热度榜 | `GET /tracks/?client_id={id}&format=json&order=popularity_total&limit=20&include=musicinfo` |
| 按风格 | `GET /tracks/?client_id={id}&format=json&tags={tag}&limit=20` |
| 单曲详情(含歌词) | `GET /tracks/?client_id={id}&format=json&id={tid}&include=lyrics` |
| 风格列表 | `GET /tracks/?client_id={id}&format=json&limit=1&groupby=tag`（或预置清单） |

核心字段：`track.id / name / artist_name / album_name / audio / image / duration / tags / lyrics`。

---

## 版本说明

## V1.0 (2026-08-22)
- **变更类型**: 新增
- **变更内容**: 建立电台（radio-browser.info）与 Jamendo（CC 独立音乐）双音源开发方案；含公共 API 调研事实、架构接入（RadioBrowserClient / JamendoService 实现 NetworkMusicService）、播放映射（电台 → Song 直播态）、TV/手机双端适配、文件清单、分阶段实施计划与风险缓解。