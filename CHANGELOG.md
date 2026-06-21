# Changelog

> 所有显著的版本变更记录在此文件。
>
> 格式基于 [Keep a Changelog](https://keepachangelog.com/)，
> 版本管理遵循 [Semantic Versioning](https://semver.org/)。
>
> 类型：`Added`（新增） | `Changed`（变更） | `Fixed`（修复） | `Removed`（移除）

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
