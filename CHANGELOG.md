# Changelog

> 所有显著的版本变更记录在此文件。
>
> 格式基于 [Keep a Changelog](https://keepachangelog.com/)，
> 版本管理遵循 [Semantic Versioning](https://semver.org/)。
>
> 类型：`Added`（新增） | `Changed`（变更） | `Fixed`（修复） | `Removed`（移除）

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
