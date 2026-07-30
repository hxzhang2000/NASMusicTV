# Changelog

> 所有显著的版本变更记录在此文件。
>
> 格式基于 [Keep a Changelog](https://keepachangelog.com/)，
> 版本管理遵循 [Semantic Versioning](https://semver.org/)。
>
> 类型：`Added`（新增） | `Changed`（变更） | `Fixed`（修复） | `Removed`（移除）

## [v2.10.6] - 2026-07-30

### Fixed

- **Jellyfin 艺术家详情页仅返回 1 首歌**：`getArtistSongs()` 用 `ArtistIds` 查 ID 与 `AlbumArtist` ID 不一致，改为按名称查 `Artists`，合作/关联歌曲全部返回
- **`utf8Body()` 过量日志拖慢电视**：每次 API 响应打 3 行 hex/状态日志，Android TV logd 开销累加显著，全部移除

### Changed

- **Navidrome 合作歌曲支持**：保留原始艺术家列表，艺术家详情页从所有相关原始 ID（含合作条目）联合查询后合并去重，Album Artist 级合作歌曲不再丢失
- **移除 `loadArtistSongsMap` 预加载**：之前启动时对所有 5000+ 艺术家逐一查歌曲（1000 批串行请求跑数分钟），改为由歌曲 Tab 全量加载后自动填充数量

---

## [v2.10.5] - 2026-07-30

### Fixed

- **合作歌曲艺术家详情页仅显示 2 首歌**：`jsonObjectToSong()` 中 `Artists` 数组只取 `[0]`（首位艺术家），无法覆盖林子祥等合作曲居多的歌手。改为拼接全部艺术家，合作曲不再丢失
- **布局 Tab 过多挤压右侧按钮**：9 个曲库 Tab 占满 Row 宽度，导致"搜索"/"播放全部"按钮被压缩到不可用。缩小 Tab 间距 + 搜索输入框 `weight(1f)` 优先压缩，按钮设 `widthIn(min)` 保护
- **ButtonChip 编译器歧义**：新增 `modifier` 参数后尾随 lambda 导致 4 处调用编译失败，全部改为显式命名参数

### Changed

- **`loadArtistSongs()` 缓存策略**：进入详情页时清理当前歌手的缓存，确保每次打开都用最新格式重新拉取后端数据

---

## [v2.10.4] - 2026-07-27

### Fixed

- **网络音乐榜单点击无反应**：榜单卡片点击时缺少 `loadPlaylistDetail` 调用，跳转到详情页后无歌曲数据
- **天气电台封面与歌曲列表分离**：移除独立的封面墙，`SongRow` 增加封面缩略图，封面融入歌曲行中

### Changed

- **SongRow 增加封面缩略图**：每行歌曲前显示 36dp 圆角封面（有封面时显示，无封面时回退轨号）

---

## [v2.10.3] - 2026-07-27

### Added

- **首页随心听**：首页新增"随心听"区块，展示 20 首随机歌曲（NAS 后端 + 网络歌曲混合），点击即播，队列剩 5 首时自动续播，无限畅听

### Fixed

- **网络歌曲歌词来源显示错误**：网络歌曲的歌词来源从"内嵌"修正为"网络歌词"，同时 `LyricsAvailability` 中网络歌曲歌词从 `backend` 字段改为 `network` 字段，确保"网络"按钮标记为可用并亮起
- **天气电台无天气数据时不显示歌曲**：天气获取失败时仍按默认心情（阳光）加载歌曲，列表不再为空
- **随心听只加载少量歌曲或消失**：NAS 拉取量从 20 增至 50，网络歌单从随机抽 1 个改为打乱逐个尝试直到凑满 20 首；刷新失败时保留已有数据，区块不消失

### Changed

- **PlayerManager 新增 addToQueue()**：支持向播放队列末尾追加歌曲，用于随心听自动续播

---

## [v2.10.2] - 2026-07-27

### Fixed

- **后台加载线程安全**：`_isBackgroundLoadingAll` 从普通 `var` 改为 `AtomicBoolean` + `compareAndSet` 原子操作，消除协程间竞态条件
- **Navidrome 歌词编码乱码**：`getLyrics()` 中 artist/title 增加 `EncodingUtils.fixEncoding()` 处理，避免 GBK 编码导致歌词搜索失败
- **艺术家歌曲去重**：`loadArtistSongsMap()` 合并到 `artistSongsMap` 时按 `song.id` 去重，避免与 `buildArtistMapsIncremental` 的歌曲重复

### Changed

- **fallbackGetSongs 增加日志**：降级时输出专辑数量，便于排查大曲库加载性能问题
- **Gradle wrapper 本地/CI 双路径**：`gradle-wrapper.properties` 恢复本地文件 URL，CI 通过 `sed` 覆盖为网络 URL，本地开发和 CI 构建均正常工作

---

## [v2.10.1] - 2026-07-27

### Fixed

- **Navidrome 歌曲 Tab 为空**：`getSongs()` 新增响应格式兜底（兼容 `subsonic-response > song[]` 直接数组格式），空时自动降级到专辑遍历 fallback，确保歌曲可获取
- **Navidrome 内嵌歌词无法解析**：实现 Subsonic `getLyrics` 端点调用，通过 `getSong` 获取 artist+title 后搜索歌词，正确返回 ID3 USLT 帧内嵌歌词
- **艺术家歌曲数量显示为 0**：新增 `loadArtistSongsMap()` 独立从后端获取每个艺术家的歌曲填充 `artistSongsMap`，不依赖歌曲 Tab 加载
- **全部播放无反应**：`loadLibrary()` 启动 `loadAllSongsBackground()` 后台全量加载，分页渐进式拉取，每页加载后立即生效，播放全部按钮即刻可用

---

## [v2.10.0] - 2026-07-27

### Added

- **曲库播放列表 Tab**：在专辑与歌曲之间新增"播放列表"Tab，左侧列列表（创建/删除/播放），右侧选中列表的歌曲明细（移除）
- **`BackendAdapter.getPlaylistSongs()` 专用接口**：Jellyfin 使用 `GET /Playlists/{id}/Items`，Navidrome 使用 Subsonic `getPlaylist` 端点，替代之前复用 `getAlbumSongs()` 的语义错误

### Fixed

- **播放列表歌曲加载使用错误 API**：`selectPlaylist()` 和 `playPlaylist()` 从 `adapter.getAlbumSongs(playlist.id)` 改为 `adapter.getPlaylistSongs(playlist.id)`，Navidrome 端播放列表不再返回空结果
- **Jellyfin 播放列表限制 200 个**：`getPlaylists()` 的 `Limit=200` 提升至 `Limit=10000`，全量加载

---

## [v2.9.0] - 2026-07-26

### Added

- **回到播放页自动聚焦播放/暂停**：进入 NowPlaying 页时自动将焦点置于播放/暂停按钮，电视遥控器可直接操作，不再需要额外导航
- **曲库子Tab跨导航记忆**：专辑/艺术家/歌曲等子 Tab 切换页面后返回保留选中状态，由 ViewModel 驱动 `StateFlow`
- **曲库播放全部按钮按 Tab 动态计算**：ALBUMS 搜索时"播放全部"只播搜索到的专辑内的歌曲；ARTISTS 按显示的艺术家聚合歌曲；各 Tab 均尊重当前搜索过滤

### Fixed

- **搜索后播放全部按钮消失**：ALBUMS/ARTISTS 搜索时因全量歌曲未加载导致 `playAllSongs` 为空，增加 `searchResults` 兜底，按钮正常显示
- **Play/Pause 焦点被进度条抢占**：`ProgressSection` 的 `LaunchedEffect(currentSongId)` 在初始化时自动请求焦点覆盖了播放按钮，通过 `withFrameNanos` 延迟一帧后请求焦点解决

---

## [v2.8.1] - 2026-07-26

### Fixed

- **合作歌曲艺术家拆分不全**：`ArtistSplitter` 分隔符正则追加 `，`（全角逗号）、`＆`（全角 and 符）、`,`（半角逗号），覆盖 `"杨宗纬，宝石Gam"`、`"窦唯 & 不一定"` 等中英文混排场景，这些合作曲目现在能正确拆分为独立艺术家条目
- **拆分艺术家详情页歌曲为空**：`loadArtistSongs()` 在按拆分后艺术家名（如 `"不一定"`）查找时，从合成 ID（`原ID|名称`）提取原始后端 ID 请求歌曲列表，然后通过 `ArtistSplitter.split()` 过滤出包含该艺术家的歌曲，详情页不再空白

### Changed

- **艺术家列表提前加载**：`loadArtists()` 从推迟到 ARTISTS Tab 首次激活时加载改为在 `loadLibrary()` 中与专辑/流派/收藏并行提前加载，ARTISTS Tab 无需等待加载状态
- **拆分后的艺术家合并去重**：`loadArtists()` 对拆分结果按 `name` 分组，合并歌曲数和专辑数，确保同一艺术家不会因拆分与后端独立条目并存导致重复

---

## [v2.8.0] - 2026-07-21

### Added

- **SpectrumAnalyzer (频谱分析器)**：全新 Android Visualizer FFT 引擎，代替旧版随机动画。
  - 512 个 FFT 复数 → 幅值计算 → 自适应噪声基底（P-1）→ 分段密集感知映射（32柱）→ 战区增益（鼓点×2.2/人声×1.8/高频×0.3）
  - 归一化锚定低频区（柱子5~19）峰值，`max(..., 0.01f)` 防除零保护
  - 链式增强：`sqrt + pow(1.5)` 对比度压扩，小信号被压低、大信号保留
  - 自适应 runningPeak 衰减（×0.94/帧 ≈ 3s 归零）
- **VisualEqualizer 完整重写**：从 3 种静态视觉主题改为实时 FFT 频谱渲染。
  - 频域三角平滑 [0.25, 0.5, 0.25] 消除柱间锯齿
  - 动态噪声门限（低于帧均值 15% 置零）
  - 每根柱子独立 Attack/Release 系数（鼓点区 0.96/0.12，人声区 0.80/0.20，边缘区 0.60/0.40）
  - 帽子（峰值指示线）：金色横杠，柱子超过时瞬间跳顶，反之 ×0.993/帧缓慢下落
  - 零间隙 Canvas 绘制 + 差异化柱宽（鼓点区 1.3× 加宽，高频区 0.7× 缩窄）+ 分区渐变色（翠绿→青→蓝→靛蓝）
  - 帧率从 60fps 调整为 30fps，画面更沉稳
- **PlayerManager 频谱联动**：
  - 在 `setPlayer()`、`onPlaybackStateChanged(STATE_READY)`、`initEqualizer()` 三个时机自动初始化 SpectrumAnalyzer
  - `audioSessionId` 延迟就绪时每秒重试，最多 5 次
  - 释放时自动清理 Visualizer 资源

### Changed

- 数据层到 UI 层的完整频谱数据流：SpectrumAnalyzer (50ms) → StateFlow → VisualEqualizer (33ms 30fps) → Ω Canvas
- 柱子布局从 96 柱对数映射改为 32 柱感知频率翘曲映射：鼓点区 15 根 / 人声区 8 根 / 高频区 4 根 / 极低频 5 根
- `app/build.gradle.kts` versionCode 递增至 18，versionName 升级至 v2.8.0

### Fixed

- **静音/间隙底噪乱跳**（P-1 自适应静音门限）：RMS 自适应跟踪噪声基底，低于 `noiseFloor × 3` 时强制全灭并重置峰值
- **歌曲未开始柱子狂跳**（绝对门限 + 归一化锚定）：帧最大值低于自适应门限直接返回空数组
- **柱子初始化时不跳**：SpectrumAnalyzer 在 `STATE_READY` 时自动绑定音频会话，不再依赖进入均衡器界面
- **Release 时间常数描述**：从 ≈150ms 修正为 ≈450ms，匹配数学计算 `ln(0.01)/ln(0.85) × 16ms`

### Removed

- VisualEqualizer 的 ColorFlow/NeonPulse/ClassicalWave 三种静态主题（改为实时 FFT 频谱渲染）
- SpectumAnalyzer 旧版对数映射（`log10`）替换为分段密集映射

---

## [v2.7.0] - 2026-07-20

### Added

- **首页仪表盘 (HomeDashboard)**：新增 HomeScreen 展示当前播放、最近播放、天气与均衡器动态预览；`HomeDashboardData` 聚合数据模型驱动首页卡片布局
- **歌曲详情面板 (SongInfoPanel)**：当前播放歌曲的码率、采样率、格式等技术参数悬浮展示；`SongTechnicalInfo` 通过 MediaExtractor 实时获取
- **可视化均衡器 (VisualEqualizer)**：实时频谱动画，支持 ColorFlow/NeonPulse/ClassicalWave 三种视觉主题；Canvas 2D 绘制，256 点 FFT 数据密度
- **天气电台增强**：Open-Meteo + OpenWeatherMap 双源自动 fallback；未来 5 天天气预报；`WeatherForecast` 数据模型；中文 WMO 天气描述
- **播放统计 (PlayRecord)**：记录播放次数与最后播放时间，首页"最近播放"列表基于统计数据展示

### Changed

- AGENTS.md 重写为紧凑版本，同步最新架构与约束
- 版本号升级至 v2.7.0，versionCode 递增至 17

### Fixed

- `WeatherApi.kt`：`return@try null` 改正为 `return null`（try 不是函数作用域，`return@label` 不可用）
- `HomeScreen.kt`：移除 `import androidx.compose.foundation.layout.weight`（RowScope/ColumnScope 成员扩展无需显式导入）
- `VisualEqualizer.kt`：频谱数学改为 Float，`toPx()` 移入 Canvas 绘制作用域
- `LibraryScreen.kt`：补充 `FontWeight` 导入
- `MainViewModel.kt`：`_progress.value` 改为 `progress.value`

---

## [v2.6.2] - 2026-07-03

### Fixed

- **天气 API Key 编辑按钮文案错误**：SettingsScreen 中天气 API Key 编辑按钮从 `settings_meting_api_url_edit`（"修改端点"）改为新建的 `settings_weather_api_key_edit`（"编辑"），语义正确
- **天气 API Key 输入未掩码**：TextInputDialog 添加 `masked = true`，输入时显示 `*` 遮掩，防止泄露 API Key
- **`isDay` 白天检测逻辑错误**：仅检查 `now < sunset`，导致日出前（凌晨 3:00–6:00）被错误标记为白天。改为 `now in sunrise..sunset` 同时检查日出和日落时间
- **`getWeatherOpenWeatherMap` 冗余 `withContext(Dispatchers.IO)`**：函数已被 `getWeather()` 的 `withContext(Dispatchers.IO)` 包裹，外层再次切换调度器无意义，已移除
- **`getWeatherApiKeySync()` 空 catch 隐藏异常**：`catch { "" }` 改为 `catch { AppLog.w(TAG, "Failed to read weather API key", e); "" }`，异常可追溯
- **OpenCodeReview 全量代码审查**：8 个文件通过 OpenCodeReview (OCR) 自动审查，修复 6 项逻辑错误、空异常捕获、冗余代码和安全隐患

---

## [v2.6.1] - 2026-07-03

### Fixed

- **天气电台无后端连接时不显示歌曲**：`WeatherRadioManager` 构造函数中 `BackendAdapter` 改为可空类型。无 NAS 后端连接时（纯网络音乐使用场景），天气电台也能从网络端搜索匹配心情的歌曲并正确显示列表。`fetchWeather()` 和 `switchWeatherMood()` 不再因 adapter 为 null 跳过初始化。
- **Open-Meteo 在国内网络被阻断时天气不可用**：`WeatherApi` 新增 OpenWeatherMap 作为 fallback 源。当 Open-Meteo 请求失败或无数据时自动切换到 OpenWeatherMap（需用户配置 API Key）。

### Added

- **OpenWeatherMap API Key 配置界面**：设置页 → 网络分区新增"天气 API Key"配置项，支持输入和修改 OpenWeatherMap API Key，输入后显示遮掩后 6 位。错误提示引导用户前往设置页配置。
- **`common_not_set` 字符串资源**：统一"未设置"显示文案

---

## [v2.6.0] - 2026-07-03

### Added

- **天气电台 (Weather Radio)**：新增 OpenWeatherMap 天气获取（经纬度→城市→实时天气+5日预报），按天气心情 SUNNY/RAINY/SNOWY/WINDY/CLOUDY/NIGHT 自动匹配 NAS 曲库和网络歌曲，生成混排电台队列。新增 `WeatherApi.kt`/`WeatherRadioManager.kt`/`WeatherSubTab.kt` 及 `WeatherData`/`WeatherMood`/`WeatherRadioQueue` 数据模型。网络音乐 Tab 增加"天气"子 Tab 和发现页天气入口
- **榜单改版**：从简单列表改为双列卡片网格（140dp×140dp），每张卡片显示封面轮播 + 榜单名称，新增"换一批"按钮随机刷新，预置歌单扩展至 20+ 个
- **歌词字体缩放**：播放页歌词区域新增字号 +/- 按钮，范围 0.7x–1.6x，设置持久化
- **封面滤镜设置**：设置页新增 COVER 分区，支持封面高斯模糊强度调节（0–25dp）和暗色遮罩透明度调节（0–100%），实时应用到播放页封面

### Changed

- `AppPreferences.kt`：`floatPreferencesKey` 改为 `doublePreferencesKey`（标准 DataStore 无 float key），涉及封面滤镜模糊/遮罩参数和 lyricsFontScale
- 封面滤镜状态提升至 AppRoot 级别，跨 NowPlaying/Settings 页面共享

### Fixed

- `MainViewModel.prefs` 从 `private` 改为 `val` 公开访问，允许 AppRoot 直接读写偏好设置
- `WeatherRadioManager.songId` → `song.id`（Song 数据类无 songId 字段）
- `WeatherSubTab` 移除 `FocusableSurface` 不支持的 `enabled` 参数
- `android.R.string.refresh` 改为直接硬编码"刷新"（TV SDK 无此资源）
- `MainViewModel` 移除重复的 Screen/SongsPagingState import 和 TAG 引用
- 版本号升级至 v2.6.0，`versionCode` 递增至 14

---

## [v2.5.1] - 2026-07-01

### Fixed

- **网络音乐端点 429 限流**：默认端点从 `meting.mikus.ink`（429 Too Many Requests）切换为 `meting.api.redcha.cn`；`getPlaylist()` 和 `resolvePlayUrl()` 增加多端点自动 fallback 机制，与 `search()` 保持一致
- **全端点失败用户提示**：所有预置端点均连接失败时，界面显示红色提示「网络音乐端点连接失败，请在设置中检查端点配置」
- **playQueue 网络歌曲播放修复**：播放网络歌曲前正确解析 `streamUrl`，不再卡在「播放中」状态
- **ProGuard Gson 类型擦除导致 TV 启动崩溃**：`AppPreferences$LastQueueData.songs: List<Song>` 被 R8 剥离泛型签名，Gson 反序列化为 `LinkedTreeMap` 而非 `Song`；`proguard-rules.pro` 添加 `-keep class com.nasmusic.tv.data.prefs.**` 保留泛型信息
- **restoreLastQueue 空安全**：`lastQueue.songs` 增加 `isNullOrEmpty()` 检查，防止残留损坏数据导致 NPE

### Added

- **歌单详情页 Play All 按钮**：一键播放全部歌单歌曲
- **队列开关按钮**：歌单详情页点击切换将歌曲加入/移出播放队列
- **榜单卡片并排双列显示**：热歌榜/新歌榜/飙升榜等卡片从单列改为 2 列网格
- **搜索框和平台切换同行布局**：搜索输入框与网易云/QQ/酷狗切换按钮置于同一行

### Changed

- 版本号升级至 v2.5.1，`versionCode` 递增至 13

---

## [v2.5.0] - 2026-07-01

### Added

- 网络音乐顶级 Tab：新增独立「网络音乐」导航项，从曲库子 Tab 提升为顶级页面
- 推荐歌单卡片行：横向滚动 LazyRow，7 个预置网易云歌单（热歌榜/新歌榜/飙升榜/华语流行/欧美流行/抖音热门/经典老歌）
- 歌单封面轮播：取前 3 首歌封面 URL，CoverCarousel 自动循环（autoCycle 模式，不受播放状态影响）
- 歌单详情页：点击推荐歌单卡片进入独立详情页（NetworkPlaylistDetailScreen），显示全部歌曲列表
- 搜索平台切换：搜索框下方增加平台切换按钮（网易云 / QQ 音乐 / 酷狗），歌词来源标签样式
- Playlist.kt 数据模型：新增网络歌单实体，支持多封面轮播列表
- CoverCarousel.kt autoCycle 参数：解耦轮播节奏与播放状态

### Changed

- 版本号升级至 v2.5.0，versionCode 递增至 12
- 网络音乐从 LibraryScreen 中移除，LibraryTab 从 8 个减少为 7 个
- 搜索歌曲的 album 字段从硬编码空字符串改为解析 API 返回的专辑名
- NetworkMusicService 接口新增 getPlaylist() 方法

### Removed

- LibraryScreen 中的 NetworkTab 组件及相关参数（170+ 行代码已迁移到 NetworkScreen）
- strings.xml 中的 7 个 library_network* 字符串（替换为新的 network_* 字符串）

---

## [v2.4.4] - 2026-07-01

### Fixed

- LyricsSource.SERVER 死代码删除：该枚举值自 v2.4.0 后从未被使用
- Mp3MetadataExtractor magic number 26 → 命名常量 `METADATA_KEY_LYRICS`；移除未使用的 `context` 参数及 import
- RecentSong 数据类移除无用默认参数（id=0/playCount=0/playedAt=0L），新增 `createNew()` 工厂方法确保新记录有正确的默认值
- CommonComponents.BackButton 接受 `modifier: Modifier` 参数，方便调用方自定义间距/位置；硬编码 `"←"` 改为 `stringResource(R.string.common_back_arrow)` 支持国际化
- PlayerControls Compose 动画 `shadow()` → `border()`（避免 TV 端阴影性能开销）；`LaunchedEffect(Unit)` 改为 `LaunchedEffect(currentSongId)` 消除重启后焦点请求竞争；移除未使用的 `currentSongId` 参数
- 空安全：移除 AppRoot、NowPlayingScreen、QueueScreen 中的 3 处 `currentSong!!` 强制解包，改用 `?.let{}` / `?: ""` / 安全分支
- FocusableSurface 动画竞争：移除 `scope.launch` + `delay` 手动时间控制，改用声明式 `LaunchedEffect(isFocused)` 驱动焦点缩放的入场/出场动画；`catch (_: Exception)` → `catch (e: Exception) + AppLog.w()`；移除重复的缩放系数
- CoverCarousel 永久失败标志：新增 `permanentlyFailed` 状态字段，避免 `onAllFailed()` 因 recomposition 循环触发；音频切换时重置 `fallbackOffset`
- EqualizerScreen 每 recomposition 重新分配 bandLabels 问题：提升为顶层 `val` 编译期常量

### Changed

- `NetworkMusicService.search()` 新增 `limit: Int = 0` 参数（0 表示使用默认值）；接口方法添加完整 KDoc `@param`/`@return`/`@throws` 错误契约；新增 `searchCoverUrl()` 默认方法
- `NetworkMusicManager.searchCoverUrl` 移除 `if (svc !is MetingApiService) continue` 硬编码类型判断，所有实现类均调用接口默认 `searchCoverUrl()`
- `AppSettings.defaultNetworkSource` 类型从 `String` 改为 `NetworkSource` 枚举（METING / ALAPI / JIOSAAVN），编译期类型安全，消除运行时字符串拼写错误；AppPreferences 新增 `fromKey()` / `fromName()` 转换器 + `NetworkSource` 类型 setter，DataStore 仍存储 key 字符串向后兼容
- EqualizerScreen 波段 -9~-1 不可达验证：当前循环逻辑已正确处理所有 10 个波段值，无需修改（code review 标记已关闭）
- SettingsScreen 的 IO 线程 `MutableState` 写入包裹 `withContext(Dispatchers.Main)` 确保 Compose 状态更新发生在主线程
- 版本号升级至 v2.4.4，`versionCode` 递增至 11

---

## [v2.4.3] - 2026-06-30

### Fixed

- OkHttp Response 泄漏：MetingApiService 3 处 `response.execute()` 未关闭（`searchWithEndpoint`/`resolvePlayUrl`/`resolveLyrics`），LyricsNetworkProvider 5 处 Response 未关闭（Kugou 搜索/歌词、Netease 搜索/歌词、parseKugouLyrics），全部改用 `response.use {}` 确保 Response 自动关闭
- BackendRegistry adapter 泄漏：`initialize()` 异常时 adapter 未释放；重复初始化时旧 adapter 未断开连接；添加 `releaseAdapter()` 辅助方法确保异常路径和替换路径均正确释放
- NasMusicApp `applicationScope` 泄漏：添加 `onTerminate()` 调用 `applicationScope.cancel()` 释放协程；移除废弃的 `companion object { lateinit var instance }`
- LyricsNetworkProvider 线程池泄漏：`daemonExecutor` 从实例变量改为 `companion object` 静态变量，避免每个实例创建新线程池
- JellyfinAdapter `addToPlaylist` API 参数错误：`Ids` 字段改为 JSON 数组 `gson.toJsonTree(listOf(...))`，修复原 `addProperty("Ids", string)` 导致 API 400 的问题
- JellyfinAdapter `setRating` API 参数错误：rating 改为 query param `?rating=N`，移除无效的 request body
- JellyfinAdapter `getPlaylists` API 路径错误：从 `/Playlists` 改为 `/Items?IncludeItemTypes=Playlist`（`/Playlists` 为创建端点，非查询端点）
- JellyfinAdapter `utf8Body()` 回退过宽：移除希腊/西里尔字母触发 GBK 回退的逻辑，仅当出现 U+FFFD 时回退，避免破坏合法的希腊/西里尔音乐元数据
- PlaybackService `onDestroy()` 释放顺序：交换 `session.release()` 与 `player.release()` 顺序，先释放 Session 再释放 Player，避免资源竞争
- ArtistSplitter 正则匹配不完整：`feat\.` 改为 `feat\.?`，支持 "feat" 无句点变体；拆分逻辑改为迭代拆分（`for(delim).flatMap{part.split(delim)}`），支持多分隔符级联匹配
- EqualizerScreen 波段 -9~-1 不可达：原循环逻辑 `band <= -10f -> 0f` 跳过负值区间，改为 `if (band >= 10f) -10f else band + 1f` 使所有波段值可循环递增
- BackendRegistry 并发安全：`getAdapter()`/`getConfig()`/`getServerDisplayName()`/`isConnected()`/`disconnect()`/`initialize()` 全部使用 `synchronized(lock)` 保护状态读写
- AppPreferences DataStore 阻塞主线程：`getDefaultNetworkSourceSync()`/`getMetingApiBaseUrlSync()` 的 `runBlocking` 改为 `runBlocking(Dispatchers.IO)`

### Changed

- 版本号升级至 v2.4.3，`versionCode` 递增至 10
- 废弃的 `NasMusicApp.instance` 静态引用移除

---

## [v2.4.1] - 2026-06-26

### Added

- 逐字歌词高频刷新：LyricsView 内部独立高频时钟（50ms / 20fps），基于 1 秒进度锚点 + 流逝时间插值估算当前进度，逐字高亮平滑过渡；仅 `WORD_BY_WORD` 模式且播放时启动，进度条等其它 UI 仍用 1000ms 刷新
- 封面多图轮播：新建 `CoverCarousel` 组件，多张封面时每 10 秒切换一张，仅播放时轮播，暂停定格；单张封面静态显示；当前 URL 加载失败自动 fallback 到候选列表下一项
- 后端候选封面列表：`BackendAdapter` 新增 `getCoverUrlCandidates(song)` 接口，按优先级返回歌曲→专辑→艺术家封面 URL
- Jellyfin 艺术家封面：`jsonObjectToSong` 解析 `ArtistItems.Id` 填充 `artistId`，请求 fields 添加 `ArtistItems`
- 网络歌词联动网络封面：NAS 歌曲切换到"在线歌词"来源时，用标题+艺术家调 `searchCoverUrl()` 搜索网络封面，加入轮播候选列表；切回"内嵌"时清除网络封面
- 统一封面候选入口：`MainViewModel.getCoverCandidates(song)` 统一组装候选列表（NAS 歌曲后端 3 类 + 网络封面；网络歌曲 1 张 pic）

### Fixed

- NowPlayingScreen 封面 fallback 重复 bug：原 attempt 1 和 2 都替换为 Backdrop，等于只有 2 级 fallback；统一替换为 `CoverCarousel` 候选列表方案
- Navidrome 封面无 fallback：`coverArt` 为空时直接返回 null，无任何兜底；`getCoverUrlCandidates` 增加 albumId/artistId fallback
- 网络歌曲 EMBEDDED 歌词路径错误：`LyricsManager.getLyricsFromSource()` 的 `EMBEDDED` 分支对网络歌曲走后端 `adapter.getLyrics()`（必然失败）；改为走 `NetworkMusicManager.resolveLyrics()`
- 设置页左侧导航栏在模拟器上显示不全且无法用遥控器上下键滚动：原用普通 `Column`（不可滚动），6 个分区项超出屏幕高度被裁切；添加 `.verticalScroll(rememberScrollState())`，焦点移动时 Compose `BringIntoView` 自动把焦点项滚入可视区域
- 关于页版本号显示滞后：`NasMusicVersion.kt` 硬编码 `VERSION_NAME`/`VERSION_CODE` 与 `build.gradle.kts` 的 `versionName`/`versionCode` 不一致（漏改）；改为从 `BuildConfig` 读取，`build.gradle.kts` 成为唯一来源
- 切换页面后歌词高亮模式丢失：`NowPlayingScreen` 用 `remember`/`rememberSaveable` 保存 `highlightMode`，由于 AppRoot 用 `when (currentScreen)` 切换页面、离开的页面离开 composition，状态丢失，返回后重置为逐行；将 `lyricsHighlightMode` 提升到 `MainViewModel` StateFlow，跨页面切换保留用户选择，含逐字时间戳的歌词仍自动切到逐字模式

### Changed

- 封面加载策略：从 NowPlayingScreen 内联的 3 级 fallback（含重复 Backdrop）改为 `CoverCarousel` 组件统一管理候选列表 + 轮播 + fallback
- 网络封面生命周期：网络封面只在"在线歌词"来源时存在，与歌词来源保持语义一致；`_networkCoverUrl` StateFlow 驱动候选列表自动刷新
- 版本号唯一来源：`NasMusicVersion.VERSION_NAME`/`VERSION_CODE` 从 `const val` 改为 `val get() = BuildConfig.*`，发布前只需修改 `app/build.gradle.kts` 一处，代码侧自动同步

---

## [v2.4.2] - 2026-06-26

### Fixed

- 线程安全：`PlayerManager.seekPending` 添加 `@Volatile`（seekTo 主线程与 ExoPlayer 回调线程可见性）
- DataStore 阻塞主线程：`AppPreferences` 的 `getRecentSongIdsSync`/`getNetworkFavoritesSync`/`getLastQueueSync` 3 处 `runBlocking` 改为 `suspend`（调用方已在协程中），避免主线程 ANR；`restoreLastQueue()` 改为 suspend 并在 `viewModelScope.launch` 中调用；保留 `getDefaultNetworkSourceSync`/`getMetingApiBaseUrlSync`（被 lambda 同步调用无法改）
- Jellyfin 分页缺失导致数据丢失：`getAlbums`/`getFavorites`/`getSongsByGenre`/`getSongsByYearRange` 4 处硬编码 `Limit=1000` 改为分页循环，参照 `getArtists` 模式，超过 1000 项时不再截断

### Changed

- 网络歌曲播放链接缓存线程安全：`NetworkMusicManager.playUrlCache` 从 `mutableMapOf` 改为 `ConcurrentHashMap`（IO 线程并发读写）
- 日志统一：全项目 11 个文件 166 处 `android.util.Log` 调用统一替换为 `AppLog`（仅 Debug 构建输出，错误日志始终输出），仅保留 `AppLog.kt` 自身的 4 处封装实现
- Kotlin 1.9+ API：`PlayMode.values()` 改为 `PlayMode.entries`（避免每次创建新数组）
- 文件结构：`Screen`/`SongsPagingState` 从 `MainViewModel.kt` 移到 `data/model/` 独立文件，便于复用和维护
- 文档对齐：`AGENTS.md` 修正 `BackendRegistry` 描述（实际是 `NasMusicApp` 实例化的普通类，非 `object` singleton）；进度轮询间隔从 500ms 修正为 1000ms（v2.2.0 已调整）

---

## [v2.4.0] - 2026-06-25

### Added

- 回归测试执行：25 项通过 / 1 项缺陷 / 6 项跳过
- 队列删除按钮：每首歌曲右侧添加 ✕ 按钮，焦点导航修复（按钮移至 FocusableSurface 外部）
- 艺术家封面图片：`getArtists()` 添加 `Fields=ImageTags`，`ArtistCard`/`ArtistDetailScreen` 添加 `AsyncImage`
- 歌词来源标签增强：后端/网络歌词同时获取，标签均亮起，点击切换来源
- 拼音搜索（TinyPinyin）：重写 `PinyinUtils` 使用 `com.github.promeg:tinypinyin:2.0.3`，兼容 API 22+
- 网络音乐搜索（Meting-API）：独立于 NAS 后端的在线歌曲搜索，支持网易云源
- 网络歌曲播放：302 重定向解析真实 mp3 URL，播放链接实时解析不缓存
- 网络歌词获取：Meting-API lrc 端点返回 LRC 文本，失败回退到 LyricsNetworkProvider
- 网络封面显示：Coil 自动跟随 302 重定向，无需额外解析
- 网络歌曲收藏：DataStore + Gson 持久化，NetworkFavoriteItem 数据类，收藏列表展示
- 收藏按钮通用化：FavoriteButton 组件（Box + focusable + clickable），本地/网络收藏共用
- 全局收藏按钮：所有歌曲列表页面（SongsTab、RecentTab、AlbumDetailScreen、ArtistDetailScreen、FavoritesTab）统一添加收藏按钮
- Meting-API 端点选择器：设置页 NETWORK 分区，3 个预设端点（Mikus/Redcha/Qijieya）+ 自定义输入
- 搜索端点自动 fallback：当前端点失败/无结果时自动尝试其他预设端点，用户无感切换
- 搜索输入支持中文：TextInputDialog 新增「中文输入」按钮，切换系统 IME 输入中文
- 搜索状态持久化：搜索关键词移至 ViewModel StateFlow，跨页面导航保留搜索结果
- 加入队列功能：所有歌曲列表页面的 SongRow 添加队列切换按钮（亮/暗状态）
- 诊断日志体系：MetingDiag TAG 全链路日志，Release 包可见，便于网络问题排查
- 播放队列持久化：DataStore 保存上次播放队列（streamUrl 置空避免过期链接），应用启动自动恢复队列和当前索引（不自动播放）
- 网络歌曲播放链接缓存：NetworkMusicManager 5 分钟 TTL 缓存，避免短时间内重复请求解析
- 网络收藏 LRU 上限：最多 500 条，超出自动清理最旧收藏，防止 DataStore 膨胀
- NowPlayingScreen 网络歌曲来源标识：标题下方显示 "NET" 标签
- 歌词来源标签文案优化："网络匹配" → "在线歌词"
- LyricsNetworkProvider 改造：OkHttp 使用守护线程池（`LyricsNetwork-OkHttp`），日志切换为 AppLog，JSON 解析迁移到 Gson

### Fixed

- MP3 流 seek 修复：启用 `FLAG_ENABLE_INDEX_SEEKING` + `FLAG_ENABLE_CONSTANT_BITRATE_SEEKING`，解决进度条跳回 0 的问题
- seekPending 保护：seek 后 2 秒内阻止 progressHandler 覆盖进度
- seek 期间播放按钮闪烁：`onIsPlayingChanged` 在 seekPending 期间跳过
- 进度条 OK 键误触发：移除 Surface onClick，OK 键不再跳到歌曲中间
- 专辑/艺术家详情页歌曲列表：响应式 StateFlow 按需加载
- 编码修复增强：`EncodingUtils.fixEncoding()` 检测字符串中间的 U+FFFD，尝试 GBK 回退
- 清空队列歌词未清除：`clearQueue()` 同时清除 `_currentLyrics`
- 后端/网络歌词同时获取：`checkAvailability()` 不再跳过网络获取
- 自动切歌歌词加载：`currentSong.collect` 统一触发歌词加载，移除重复调用
- 艺术家分页加载：取消 1000 个艺术家限制，支持分页获取全部
- 退出时 Jellyfin session 注销：`runBlocking` 确保 HTTP 请求完成后再杀进程
- Meting-API 字段映射错误：`parseSongs()` 兼容 `title`/`author`（Mikus/Redcha）和 `name`/`artist`（Qijieya）两套字段名
- SSL 证书信任失败：老 TV 设备缺少 Let's Encrypt 根 CA，新增信任所有证书的 TrustManager + 宽松 HostnameVerifier
- API base URL 包含反引号：`baseUrl` getter 和 `setMetingApiBaseUrl()` 清理反引号/引号/空格
- 收藏页面 NAS 歌曲无收藏按钮：FavoritesTab 的 NAS 歌曲 `onToggleFavorite` 从 `null` 改为可取消收藏
- 收藏页面依赖 NAS 连接：FAVORITES Tab 与 NETWORK Tab 同等处理，不依赖 NAS 连接状态，始终可用
- 收藏的网络歌曲不在收藏列表：FavoritesTab 合并本地收藏 + 网络收藏
- NowPlayingScreen 网络歌曲收藏按钮无效：`toggleFavorite`/`isFavorite` 增加 `isNetworkSong` 分支路由
- 队列按钮无法聚焦：QueueToggleButton 从嵌套 Surface 改为 Box + focusable + clickable 独立焦点节点
- 队列页面样式不统一：QueueScreen 歌曲行统一为 SongRow 的紧凑样式 + 焦点行为
- 网络搜索输入框被列表覆盖：TextInputDialog 内容包裹到 `Dialog`（系统级窗口），确保显示在歌曲列表之上
- TextInputDialog BACK 键失效：Dialog 拦截 BACK 事件，改用 Compose `BackHandler` 在 Dialog 内部处理（先隐藏系统 IME，再关闭对话框）
- 网络歌曲标题/作者编码乱码：`MetingApiService.parseSongs()` 对 title/author 字段调用 `EncodingUtils.fixEncoding()`，解决 GBK/Latin-1 误解码
- 恢复队列后无法播放：`PlayerManager.restoreQueue()` 原先只更新 UI 状态，未加载 MediaItems 到 ExoPlayer；改为调用 `setMediaItems` + `prepare()`（不 play），并在 `playPause()`/`next()`/`previous()` 中检测 streamUrl 为空时先解析再播放
- 恢复队列后网络歌曲无法播放：`restoreQueue` 为空 streamUrl 歌曲创建空 URI MediaItem，ExoPlayer prepare 出错并触发 `onPlayerError` 级联跳歌；改为当前歌曲 streamUrl 为空时跳过 prepare，由 `resolveAndPlayCurrentSong()` 在用户按播放时解析
- 自动切歌到网络歌曲播放失败：ExoPlayer 自动过渡（`MEDIA_ITEM_TRANSITION_REASON_AUTO`）到 streamUrl 为空的歌曲会出错；`onMediaItemTransition` 拦截此场景，暂停并触发 `onNeedResolveStreamUrl` 回调，由 MainViewModel 解析 streamUrl 后重新播放
- `onPlayerError` 级联跳歌：当前歌曲 streamUrl 为空时不自动跳下一首，避免下一首也可能为空导致循环错误
- 歌词加载误报"加载歌词失败"：`loadLyricsForCurrentSong` 的 `catch (e: Exception)` 错误捕获了协程 `CancellationException`（切歌时 `lyricsLoadJob.cancel()` 触发）；新增 `catch (CancellationException) { throw e }` 重新抛出取消异常，不当作错误提示

### Changed

- "歌唱家"改名为"艺术家"（strings.xml + UI 标题）
- 队列删除/移动按钮移至 `FocusableSurface` 外部（兄弟级），支持 D-Pad 焦点导航
- `EncodingUtils.fixEncoding()` 新增 U+FFFD 检测逻辑，处理 GBK→UTF-8 误解码
- SongRow 焦点架构重构：Box(focusGroup) + 兄弟级 Row(weight(1f)+clickable) + Box(focusable+clickable)，解决嵌套焦点问题
- 收藏页面 NAS 歌曲也可取消收藏（原方案仅网络歌曲可取消）
- Phase 3 方案调整：从"多源（AlAPI/JioSaavn）"调整为"多端点 fallback"，Meting 3 端点已足够容错
- 队列持久化策略：streamUrl 字段不持久化（时效性链接），NAS 歌曲在后端连接后通过 `adapter.getSongsByIds()` 刷新，网络歌曲在播放时由 `resolvePlayUrl()` 实时解析
- 清空队列同步清除持久化数据：`clearQueue()` 调用 `prefs.clearLastQueue()` 维持状态一致

---

## [v2.2.0] - 2026-06-22

### Added

- 编码处理修复：自动检测并修复 GB2312/GBK 编码被当作 Latin-1 解码的问题
- 分批加载歌曲：每批 500 首，最多 50000 首，避免内存溢出
- 加载进度显示：加载时显示 "已加载 X 首歌曲"，实时更新
- TV 桌面图标显示修复：添加 `LAUNCHER` 类别，确保应用图标在桌面显示
- 字符串资源化（B-3/B-8）：创建 `strings.xml`，替换 6+ 个屏幕中所有硬编码中文 UI 字符串
- DI 容器（B-9）：`NasMusicApp` 作为控制反转容器，移除 `getInstance()` 静态方法
- Activity + ViewModel 拆分（B-10）：MainActivity 从 678 行精简至 ~275 行，抽取 `AppRoot`/`NetworkMonitor`/`MediaKeyHandler`
- 统一异步状态（B-12）：新增 `UiState<T>` 密封类（Loading/Success/Error）+ `RetryUtil` 指数退避重试
- 播放模式迁移（B-13）：`_playMode` 从 PlayerManager 迁移到 MainViewModel，新增 `derivePlayMode()`
- 单元测试补充（B-5）：UiStateTest、TimeUtilsTest、RetryUtilTest、MediaKeyHandlerTest、NetworkMonitorTest
- CI 搭建（B-6）：GitHub Actions 工作流，push/PR 自动构建并上传 APK
- 歌曲分页加载：`SongsPagingState` 每页 200 首，滚动到底部触发下一页，显示 "已加载 N / 共 M 首"
- 按需加载 API：`getSongsTotalCount()` / `getSongsByIds()` / `getYears()` / `searchSongs()` 替代全量加载
- 增量构建艺术家映射：`buildArtistMapsIncremental()` 仅处理新批次，避免全量重建
- Navidrome 并发加载：专辑/演唱者/歌曲三个请求使用 `async + awaitAll` 并行执行
- 密码加密存储（CryptoUtils）：基于 Android Keystore 的 AES-256-GCM 加密，保护 DataStore 中的 password 和 apiToken
- 日志统一管理（AppLog）：Debug 构建输出 d/i/w 级别，Release 构建空操作，e 级别始终输出
- 编码修复工具抽取（EncodingUtils）：从 Adapter 中抽取公共 `fixEncoding()` 逻辑
- 公共可聚焦 Surface 组件（FocusableSurface）：统一封装焦点动画 + 边框 + FocusRequester，消除 30+ 处样板代码
- 回归测试文档：`docs/regression-test.md`，19 章节 248 个测试项，覆盖单元/集成/UI/专项验证
- `PlayerManager.release()`：释放 Handler、listener、Equalizer 资源
- `PlayerManager.setEqualizerBands(gains)`：批量设置所有频段增益
- `PlayerManager.moveItem(from, to)`：队列重排，同步 ExoPlayer 队列与 currentIndex
- `PlayerManager.clearError()`：清除播放错误状态
- `playerError` StateFlow：播放错误信息，用于 UI 错误展示与自动跳下一首

### Fixed

- 歌曲时长获取修复：扩展 Jellyfin API `fields` 参数，包含 `Album`、`AlbumArtist` 等字段
- 进度条 D-Pad seek 修复：从歌唱家详情页等入口进入时，进度条 seek 正常工作
- 编码修复逻辑优化：只对明确的乱码模式（末尾 `�?`）进行移除，避免破坏正常 UTF-8 字符串
- 分批加载逻辑修复：正确限制歌曲数量，避免内存溢出和应用崩溃
- PlaybackService Media3 1.2.1 API 不兼容修复：改用 `ACTION_MEDIA_BUTTON` + `KeyEvent` 方式构建 PendingIntent，替代不存在的 `MediaButtonReceiver.buildMediaButtonPendingIntent` 和 `Player.COMMAND_PLAY/PAUSE`
- 进程退出残留修复：OkHttp Dispatcher 使用守护线程池（`isDaemon = true`）+ 退出时 `finishAffinity()` + `Process.killProcess()` 双保险，解决 Android Studio stop 按钮常亮问题
- PlaybackService 退出清理增强：`onDestroy()` 新增 `PlayerManager.release()` + `ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)`；`onTaskRemoved()` 简化为直接 `stopSelf()`
- Jellyfin 歌词端点 404 修复：`/Items/{id}/Lyrics` 改为 `/Audio/{id}/Lyrics`
- Jellyfin 收藏端点 404 修复：`/Items/{id}/Favorite` 改为 `/UserFavoriteItems/{id}`
- Jellyfin 流派过滤修复：`/Genres` 端点添加 `IncludeItemTypes=Audio`，只返回音乐流派
- Jellyfin 流派 songCount 字段修复：`MovieCount` 改为 `SongCount`
- 全量加载歌曲导致内存溢出：改为分页加载（每页 200 首）

### Changed

- 移除 Debug/Release 歌曲数量限制，统一使用分批加载（最多 50000 首）
- 版本号升级至 v2.2.0，`versionCode` 递增至 5
- 进度更新频率从 500ms 调整为 1000ms，减少 CPU 占用
- PlayerManager 的 `next()` / `previous()` / `onPlaybackEnded()` 改为接收/推导 `playMode` 参数
- `applyPlayMode()` 不再存储状态，只应用 ExoPlayer 设置
- OkHttpClient 使用守护线程池，防止阻止进程退出（JellyfinAdapter 线程命名 `Jellyfin-OkHttp`，NavidromeAdapter 命名 `Navidrome-OkHttp`）
- 退出确认流程：`playerManager.release()` → `stopService()` → `finishAffinity()` → `Process.killProcess()`

---

## [v2.1.0] - 2026-06-21

### Added

- NowPlaying UI 改版（Task 1-3）：播放控制按钮下移 → 控制按钮在封面下方进度条上方；进度条横向占满底部全宽；专辑名移至封面上方，封面下方只显示艺术家
- `ProgressSection` / `ControlButtonsRow` 独立组件：PlayerControls.kt 提取为两个顶层 Composable，方便复用
- Jellyfin 连接泄漏修复：`logout()` 调用 `POST /Sessions/Logout`，`testConnection()` 和 `disconnect()` 均释放 session
- 应用退出时连接资源释放：`BackendAdapter.close()` 关闭 OkHttp 连接池，`MainActivity.onDestroy()` 和退出确认时调用
- 演唱者详情页导航修复：点击歌唱家卡片打开详情页（而非直接播放）
- 流派过滤修复：Jellyfin `/Genres` 端点添加 `IncludeItemTypes=Audio`，只返回音乐流派
- 多歌唱家拆分展示修复：`allArtists` 从 `artistSongsMap.keys` 获取（已拆分），而非原始 artist 字段
- 进度条 D-Pad seek 统一修复：所有播放路径统一使用 `playQueue`，`ProgressSection` 使用 `LaunchedEffect(currentSongId)` 请求焦点
- 收藏功能修复：Jellyfin 收藏 API 端点从 `/Items/{id}/Favorite` 改为 `/UserFavoriteItems/{id}`
- 播放次数显示：`SongRow` 新增 `playCount` 参数，最近页面显示播放次数
- 歌词高亮模式增强：新增 `LyricsHighlightMode` 枚举（逐行/逐字），支持手动切换，标准 LRC 格式支持逐字估算
- 全屏封面模糊效果：沉浸模式封面图添加 `blur(30.dp)` 模糊效果
- 均衡器导航修复：设置页"均衡器"按钮可正常打开均衡器页面

### Fixed

- 进度条 D-Pad seek 修复：从其他页面返回时焦点状态正确同步
- 连接资源泄漏修复：应用退出时 OkHttp 连接池正确释放，不再需要重启 Jellyfin

### Changed

- Debug 编译歌曲加载限制从 10 改为 100
- 版本号升级至 v2.1.0，`versionCode` 递增至 4

### Added

- 版本控制系统：`NasMusicVersion` 统一管理版本号，设置页"关于"显示版本信息
- 技术方案文档：`docs/features-plan.md` 记录未来功能规划
- 技术架构文档：`docs/technical-overview.md` 记录当前完整的架构与实现细节
- Git / GitHub 版本管理：`.gitignore`、`.gitattributes`、`.opencode/rules.md`
- 文档记录：`docs/technical-overview.md` 第 8.5 节 Git 配置说明、第 10.2 节修改记录
- B-5 沉浸模式：点击封面图切换全屏封面背景 + 歌词叠加布局，BACK 键恢复常规模式
- C-2 无间断播放：ExoPlayer 启用 CrossfadeMediaSource，曲目切换时淡入淡出过渡
- C-1 队列排序增强：每首曲目右侧增加「↑↓」移动按钮，支持 D-pad 焦点操作
- A-1 专辑详情页：点击专辑卡片进入详情页，展示专辑封面、曲目列表、播放全部
- A-2 演唱者详情页：点击演唱者进入详情页，展示该演唱者所有歌曲、播放全部
- A-3 流派与年代浏览：LibraryScreen 增加 GENRES / YEARS 标签页，按流派和出版年份筛选
- A-4 多演唱者拆分：ArtistSplitter 支持 feat./ft./with/ &//×/vs 多分隔符拆分，详情页按独立演唱者展示
- B-1 歌曲收藏：NowPlayingScreen 增加收藏按钮，LibraryScreen 增加 FAVORITES 标签页，数据持久化
- B-2 最近播放与播放次数：LibraryScreen 增加 RECENT 标签页，AppPreferences 记录最近 50 首播放历史
- B-3 卡拉 OK 逐字高亮：LrcParser 解析词级时间戳（`<mm:ss.ff>word`），LyricsView 逐字变色
- B-4 均衡器：创建 EqualizerScreen，预设选择 + 频段增益调节，PlayerManager 绑定 AudioFX
- D-1 前台通知：PlaybackService 启动时创建媒体播放通知栏，支持 play/pause/next/previous
- D-2 网络监控：MainActivity 注册 ConnectivityManager 回调，自动重连（最多 3 次）
- D-3 错误提示：ViewModel 全局 catch 块增加 showError() 用户可见错误消息（5 秒自动消失）
- E-4 缓存管理：SettingsScreen 增加 CACHE 区域，显示缓存大小，支持清除歌词/封面缓存
- F-1 播放列表：PlaylistManagementScreen 支持增删查播，Jellyfin/Navidrome 双后端实现
- G-1 HDMI-CEC：MainActivity.onKeyDown 映射媒体键（播放/暂停/上/下一曲/停止）
- E-2 单元测试：ArtistSplitterTest、PinyinUtilsTest、LrcParserTest

### Fixed

- Jellyfin 封面图 fallback 逻辑：当 `ImageTags.Primary` 为 null 时自动回退到无 tag 的封面 URL
- D-pad 左右键跳转修复：处理 `KeyDown` → `KeyUp` 事件类型适配不同 Android TV 固件

### Changed

- Debug 编译下歌曲加载数量限制为 10 首，Release 下为 100,000 首
- "播放全部"按钮从仅在专辑 tab 显示改为常驻显示
- 设置页"关于"区域的版本号从硬编码改为读取 `NasMusicVersion`
- 版本号升级至 v2.0.0，`versionCode` 递增至 3
- BackendAdapter 接口扩展：新增 13 个方法（播放列表 CRUD、收藏、流派、评分、随机歌曲等）
- JellyfinAdapter / NavidromeAdapter：完全重写以支持所有新接口方法

### Removed

- 废弃代码清理：删除 `backend/jellyfin/` 和 `backend/navidrome/` 旧 Retrofit 实现（共 6 个文件，~500 行死代码）
- Retrofit 依赖移除：`com.squareup.retrofit2:retrofit` 和 `com.squareup.retrofit2:converter-gson`（不再需要）

---

## [1.0.0] - 初始发布

### Added

- Jellyfin 后端连接与音乐浏览（专辑、歌曲、演唱者）
- Navidrome 后端连接与音乐浏览（通过 Subsonic API）
- ExoPlayer 音频播放引擎（Media3）
- 播放模式支持：顺序播放、单曲循环、列表循环、随机播放
- 播放队列管理：添加、移除、清空
- 歌词系统：LRC 解析、多来源获取（MP3 内嵌、本地缓存、本地文件、网络匹配）、来源切换
- 封面图显示：MP3 内嵌元数据提取 + 后端 URL + fallback 继承
- 曲库浏览：专辑网格、演唱者网格、歌曲列表
- 搜索功能：拼音首字母匹配 + 子串匹配
- 启动连接提示对话框
- 设置：暗色主题、界面动画、自动播放下一首、播放模式、歌词缓存、封面缓存、歌词偏移
- 服务器连接管理与配置持久化（DataStore）
- 后台播放服务（MediaLibraryService）
- 三层 BACK 键处理（关闭弹窗 → 回到播放页 → 退出确认）
- Android TV D-pad 完整导航支持
