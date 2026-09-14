# Changelog

> 所有显著的版本变更记录在此文件。
>
> 格式基于 [Keep a Changelog](https://keepachangelog.com/)，
> 版本管理遵循 [Semantic Versioning](https://semver.org/)。
>
> 类型：`Added`（新增） | `Changed`（变更） | `Fixed`（修复） | `Removed`（移除）

## [v2.32.3] - 2026-09-14

> 代码质量修复批次（基于 code-review-full-report-2026-09-13.md 审阅落地，实施记录综合评分 74 → 78 → 81）。本版本不引入新功能，仅修复 7 项 P0 线程安全问题 + 1 项 P0 安全问题 + 2 项 P1 状态一致性问题 + 1 项 P2 文档补充。落地后经编译验证补丁修复 3 处编译错误（T7 变量作用域 + P1#12/L7 缺失导入），`:app:assembleDebug` 与 `:app:assembleRelease` 均构建通过，详见 §10.136。
> 另含 T2（分批 Flow 化）：百度配置读取路径全部由 `runBlocking(IO)` 迁移到 `baiduConfigFlow.first()`（suspend），分两批落地（第一批外部 UI/App 调用点；第二批 AppPreferences 15 个便捷方法 + BaiduPrefs 透传 + BaiduOAuthClient/Netdisk/MvFileService/ViewModel 调用方），详见 §10.137。
> 另含 T6（AudioFrame 双缓冲）：频谱数据仓库由单实例改为双缓冲 + `@Volatile writeIndex` 发布，消除音频回调线程写/渲染线程读的无同步撕裂；VisualizerStage 绘制循环改为每帧捕获 front 引用，详见 §10.138。
> 另含 T3（PlayerManager 三元组原子化）：queue/currentIndex/currentSong 由三个独立 MutableStateFlow 合并为单一 `playerState` 原子流（`update{copy}` 同帧发布），消除快速切歌时 UI 读到"新队列+旧索引+旧歌名"的错帧状态；PlayerViewModel/AppRoot/QueueBranch/MainViewModel/DownloadViewModel/PlaybackService 订阅点全部适配，详见 §10.139。

### Fixed
- **T6 修复（visualizer）**: `SpectrumRepository.kt` AudioFrame 双缓冲——2 个预分配实例 + `@Volatile writeIndex`，写端（仅 onFrame/reset）写完翻转发布、读端读 front，volatile 写→读建立 happens-before，消除音频回调线程写/渲染线程读的无同步撕裂；帧序号改仓库级全局计数器；`reset()` 双实例同时清零避免波形跨歌残留。`VisualizerStage.kt` 绘制循环改每帧捕获 front 引用（原持有重组期快照引用会被写端轮询覆盖，双缓冲形同虚设）
- **T8 修复（visualizer）**: `ParticleRenderers.kt` `val t = targets ?: return` 提前到 createBitmap 之前，消除 Bitmap 必然泄漏路径（targets 为 null 时每帧泄漏 220x660x4B=580KB 内存）
- **T7 修复（visualizer）**: `LyricsDotMatrixRenderer.kt` try-finally 包裹 createBitmap/recycle，异常路径不再泄漏 Bitmap
- **S3 修复（backend）**: `SubsonicAdapter.kt` `toggleFavorite` 删除冗余的 `getFavorites()` 二次查询，直接使用入参 `isCurrentlyFavorite`，消除 TOCTOU 竞态窗口与多余网络请求
- **T4 修复（backend）**: `SmartRadioManager.kt` 3 处 `playedIds.clear()` 移入 synchronized 块，所有 `generateBatch(playedIds)` 改为 `playedIds.toSet()` 快照，消除 UI 线程 stop/skip 与 IO 协程读取之间的可见性竞争
- **P1#12 修复（viewmodel）**: `NetworkMusicViewModel.kt:675` `restoreBaiduIndexOnStart` 中 `getBaiduConfigSync()` 改为 `baiduConfigFlow.first()`，消除 runBlocking+IO 在 `Dispatchers.Default` 线程池上的阻塞
- **S1 修复（security，收益有限）**: `CryptoUtils.kt` AES-256 派生口令改从 `BuildConfig.CRYPTO_PASSPHRASE` 注入，值由 `app/build.gradle.kts` 从 `keystore.properties.cryptoPassphrase` 读取。**注意**：为兼容既有加密数据，`app/build.gradle.kts` 保留了与历史硬编码一致的默认值，口令字面量仍存在于受版本控制的仓库中（只是从 `CryptoUtils.kt` 移到构建脚本）；仅当用户在 `keystore.properties` 中覆盖后，运行期口令才不再取自仓库默认值。此项仍属「混淆级」而非「保密级」
- **L7 尾巴修复（player）**: `HqSeparationOrchestrator.kt` `release()` 末尾补 `scope.cancel()`，清理本编排器 scope 内协程
- **P1#10 修复（viewmodel）**: `VisualizerViewModel.kt` `loadedCoverKey` 加 `@Volatile`，主线程写(63行)/IO 读(82行)跨线程可见性
- **P1#2 修复（baidu）**: `BaiduNetdiskConfig.kt` ERRNO_MAP 补 31079 到"文件不存在或已被删除"

### Docs
- **L4 补充（db）**: `LocalMusicDatabase.kt` 注释强化：`fallbackToDestructiveMigration` 仅适用可由其他数据源重建的本地索引，**未来承载用户数据（下载/收藏/播放列表）的数据库绝不可启用**，必须维护 Migration 类
- **审阅文档**: `logs_temp/code-review-full-report-2026-09-13.md` 追加"实施记录"段并随 T2/T6/T3 落地持续同步，标注 13 项已修复 + 3 项暂缓（理由）+ 综合评分 74 → 78 → 81

### Changed
- **T3 改造（player）**: `PlayerManager.kt` 的 queue/currentIndex/currentSong 三个独立 `MutableStateFlow` 合并为单一 `PlayerState`（新文件 `player/PlayerState.kt`）原子流，全部状态更新点改 `_playerState.update { it.copy(...) }` 同帧发布；`PlayerViewModel` 移除原 3 个转发流改透传 `playerState`；订阅点适配（AppRoot/QueueBranch 收集 `playerState` 派生、MainViewModel 5 处流派生 + 8 处取值、DownloadViewModel 删除下载暂停判定、PlaybackService 通知"下一首"标题），详见 §10.139
- 版本 v2.32.2 → **v2.32.3**（versionCode 141 → 142）

### 暂缓（3 项，需独立 PR）
- S4 Jellyfin 会话内 401 重认证（需真实环境测试重试逻辑）
- P1#5 VisualizerMath seed 隔离（30+ Renderer 全部修改）
- L3 playModeToggleHandler 改 Flow（跨文件改造）

> P1#1 OkHttp 连接池统一：已决定**不做**（见审阅报告实施记录 F 决策记录）

### P2 顺手项（未做）
- `customAppKey/secretKey` 加密（原计划与 T2 一起做，T2 已完成但此项未动）

## [v2.32.2] - 2026-09-13

> 全局统一播放控制按钮（返回/上一曲/下一曲/播放顺序/播放暂停）的图标色与聚焦反馈规范：**未聚焦时图标恒为亮白（TextPrimary）不依赖主题默认色兜底；聚焦时整体按钮背景变化、图标色保持不变**。此前各页 Icon 均不传 `tint`，实际取 tv-material3 主题 `LocalContentColor`（恰好为亮白但未显式保证），且 `FocusableSurface` 下发的自定义 `LocalFocusableContentColor` 并非 Icon 消费的通道——聚焦色参数形同虚设。另有两处不一致：QueueScreen 播放/暂停按钮聚焦反而**变暗**（Primary 70%），NowPlaying/K 歌主按钮聚焦背景完全**不变**（与"聚焦有整体变化"的交互预期不符）。

### Changed
- **播放控制按钮图标显式亮白**：`PlayerControls`（NowPlaying/MTV 控制行的上一曲/播放暂停/下一曲/播放顺序）、K 歌/MTV/队列页 `MiniIconButton` 内 Icon 全部显式 `tint = TextPrimary`，不再依赖主题 `LocalContentColor` 兜底
- **主按钮（播放/暂停）聚焦变亮**：NowPlaying/K 歌聚焦背景由不变（Primary→Primary）改为 `NasMusicColors.PrimaryBright`（新增强调色 #5EEAD4）；队列页由变暗（Primary 70%）统一改为变亮
- 非主按钮（返回/上一曲/下一曲/播放顺序）聚焦背景维持 Surface → Primary 30% 变化；图标色任何状态均不变
- `Theme.kt` 新增 `NasMusicColors.PrimaryBright`（#5EEAD4），`HighContrastColors.PrimaryBright` 改为引用同一值
- 版本 v2.32.1 → **v2.32.2**（versionCode 140 → 141）

## [v2.32.1] - 2026-09-13

> 修复 TV 端 K 歌与 MTV 全屏页面手机遥控二维码不显示/无法通过遥控器按键重新唤醒的问题。**根因一（主因）**：AppRoot 的 `isTV` 仅检查 `android.software.leanback` 特性，而实测电视（`pm list features`）只上报 `android.hardware.type.television` 无 leanback——TV 被误判为手机，`remoteControlUrl` 被 `if (isTV)` 守卫强制置 null，二维码完全无法生成（v2.20.0 手机端支持引入的回归，当时 MainActivity/NasMusicApp/TextInputDialog 均用双特性判断、唯 AppRoot 漏检）。**根因二**：页面级按键预览未建立稳定焦点域，内部 TV 按钮或 MTV 的 AndroidView 视频层获得焦点后，按键不保证经过外层监听。现 isTV 对齐全库统一的双特性判断，并为两页建立可聚焦的页面焦点域主动请求焦点、仅在 KeyDown 刷新显隐计时；二维码在进入页面及任意遥控器按键后立即显示，约 5 秒无操作后完全隐藏，同时保留原有 D-Pad、播放控制、焦点导航和 BACK 行为。

### Fixed
- **K 歌/MTV 手机遥控二维码不显示（根因修复）**：`AppRoot` 的 `isTV` 补上 `android.hardware.type.television` 检测（与 MainActivity setContent 内、NasMusicApp、TextInputDialog 的既有判断对齐），TV 不再被误判为手机；`MainActivity.onCreate` 的系统栏隐藏判断同步对齐
- **K 歌/MTV 手机遥控二维码按键后不显示**：两页外层增加 `FocusRequester + focusable()` 页面焦点域，页面进入时主动请求焦点，确保内部按钮与 MTV 视频层场景下仍能收到按键预览
- **二维码显隐计时重复刷新**：仅在 `KeyEventType.KeyDown` 时刷新 5 秒计时，避免同一次按键的 KeyUp 再次延长显示时间

### Changed
- **K 歌页控制按钮对齐 MTV 定时隐藏**：底部栏（返回/上一首/播放暂停/下一首/升降调/变速/原唱伴唱/质量切换）5 秒无操作虚化至 0.15 透明度，任意遥控器按键或焦点变化恢复；歌词与歌曲信息保持常显
- 版本 v2.32.0 → **v2.32.1**（versionCode 139 → 140）

## [v2.32.0] - 2026-09-13

> 可视化效果库一次补齐 11 套极简几何风格效果（E26–E36）：E26「声弦」线性声波（平行细线阵随波形起伏，高频叠加细密锯齿）、E27「几何环」动态几何环（pulse 心跳缩放 + treble 积分旋转 + 顶点频谱断点闪烁）、E28「构成」包豪斯拼贴（莫兰迪色系扁平几何体，低音放大/高频翻面/中频漂移）、E29「轨道」环绕轨道（倾斜椭圆轨道 + 光球拖尾环形缓冲，倾角低通晃动防筛子）、E30「雷达」极坐标（雷达绿单色，低音向心收缩 + 扫掠角余弦亮起 + 频谱余辉弧）、E31「折纸」低多边形（三角形 cos 投影翻折，过零交换明暗面；treble 驱动冷暖色相插值）、E32「阶梯」方波（刻意不做缓动的量化方块立面，高频 2×2 碎裂）、E33「齿轮」同心齿轮（齿轮 Path 预生成，beat 棘轮一个齿距 120ms 缓动，小齿轮 treble 疯转）、E34「分形」分形树（拓扑 onEnter 拍平存数组不递归，bass 驱动展开深度，末梢确定性电弧）、E35「光轴」旋转光轴（宽淡辉光+细亮芯线双层，高频手动分段虚线避开 dashPathEffect 每帧分配）、E36「螺旋」费马螺旋（黄金角预计算点位，内圈组整体旋转规避逐点变换）。全部 Tier.BASIC（三档画质全开，内部按画质分档细节量），draw 内零分配（E30 SweepGradient 预分配、E26 单 Path 双描边、E29 环形历史缓冲零 arraycopy）。音频分析层零改动，舞台/指示器/切换零改动（自动遍历 selectable）；均衡器页小预览按 ordinal%3 归类样式天然兼容。

### Added
- **11 套极简几何可视化效果（E26–E36）**：`VectorWavesRenderer` / `PulsingPolygonsRenderer` / `BauhausShapesRenderer` / `OrbitalRingsRenderer` / `RadarGridRenderer` / `OrigamiPolyRenderer` / `StaircaseWaveRenderer` / `ConcentricGearsRenderer` / `FractalTreeRenderer` / `LightBeamsRenderer` / `FermatSpiralRenderer`（4 个新文件），枚举 `VisualizerTheme` 编号 26–36 全 Tier.BASIC；`VisualizerRendererFactory` 注册 11 分支
- **画质内部分档**：全部效果按 LOW/MEDIUM/HIGH 缩放元素量（线数/形状数/轨道数/网格密度/分形深度/光束数/点数），LOW 档亦可流畅

### Changed
- `VisualizerThemeTest` 数量断言 23 → 34；版本 v2.31.4 → **v2.32.0**（versionCode 138 → 139）

## [v2.31.4] - 2026-09-13

> 修复设置页内容区无法用遥控器**左键**把焦点移回左侧导航栏的问题（用户报告：仅播放设置分区复现，其他分区偶发/正常）。根因：设置页为「左栏分区导航（verticalScroll Column）+ 右栏内容（LazyColumn）」双滚动容器布局，焦点跨容器移动依赖系统几何查找；播放设置内容最长（整段为单个 LazyColumn item，含多组横排按钮/开关/路径文本），焦点在内容区内部左右移动时几何查找无法"走出"到左栏，表现为按左键焦点卡住。修复采用 Compose TV 标准的焦点越界重定向：右栏声明 `focusGroup` 并挂 `focusProperties { exit }`，向左越界（`FocusDirection.Left`）时强制聚焦左栏**当前分区项**（每个分区项持有独立 `FocusRequester`，`activeSection` 驱动）。对全部分区生效，且不影响内容区内部（横排按钮组之间）的正常左右移动；其他方向（上/下/右）越界维持系统默认行为。用户确认的交互预期：导航栏选中分区 → 右键进入内容修改查看 → 左键逐步移回导航栏 → 继续选其他分区。

### Fixed
- **设置页内容区按左键无法移回导航栏**：`SettingsScreen` 右栏 LazyColumn 增加 `focusGroup` + 左向 `exit` 重定向到左栏当前分区项；分区导航项挂 `FocusRequester`

### Changed
- 版本 v2.31.3 → **v2.31.4**（versionCode 137 → 138）

## [v2.31.3] - 2026-09-13

> 修复设置域子页面遥控器返回键无法回到设置主菜单的问题。BACK 键 Level 2 导航分发表（`AppRoot`）为「白名单 + `else -> navigateHome` 兜底」模式：从设置进入的均衡器（`Screen.Equalizer`）与播放统计（`Screen.PlayStats`）没有专门分支，静默落入兜底直接回首页——页面左上角返回按钮点击可回设置，但遥控器返回键行为与之不一致，用户只能移动焦点"点"按钮返回。现按入口来源补齐分发：均衡器/播放统计/服务器连接 → 回设置，网盘（入口唯一在"我的"）→ 回我的页；天气电台入口在首页，由兜底回首页保持正确。设置页内部弹窗（备份/模型传输/文本输入/目录选择/导出设备）的返回键关闭均正常，无需改动。

### Fixed
- **均衡器/播放统计按返回键回不到设置**：Level 2 分发表补 `Screen.Equalizer` / `Screen.PlayStats` → `navSettings` 分支，与页面返回按钮行为一致
- **网盘页按返回键回不到"我的"**：补 `Screen.Netdisk` → `navigateMine` 分支（入口唯一：MineBranch）

### Changed
- 版本 v2.31.2 → **v2.31.3**（versionCode 136 → 137）

## [v2.31.2] - 2026-09-13

> 统一歌曲/歌词来源标签体系。修复播放页来源标识硬编码 `"NET"`：无论百度网盘、Meting 网络曲、Jamendo 还是电台，只要 `isNetworkSong=true` 一律显示写死的 "NET"（`NowPlayingScreen` 从未接入 `MusicSourceType` 体系）。现改用统一 `SourceBadge`（百度→"百度"☁ 橙 / Meting→"网络"🌐 绿 / Jamendo→"Jamendo"♪ 粉 / 电台→"电台"📻 紫，NAS→"NAS"🎵 蓝也补齐显示）。歌曲信息面板「网络来源」行原显示原始标识大写（BAIDU/METING），改为统一 `sourceType.displayName` 且全来源显示；`LyricsSource`（歌词来源）补齐 `icon`/`color` 字段与歌曲来源定义结构对齐，播放页歌词来源切换标签文案由 strings.xml 独立维护改为统一取枚举 `displayName`（内嵌/本地/在线/缓存），两套文案不再漂移；发现页专辑角标沿用 `MusicSourceType` 文案与颜色（样式为封面角标紧凑版，维持现状）。

### Fixed
- **播放页来源标签硬编码 "NET"**：`NowPlayingScreen` 来源标识改用统一 `SourceBadge(song)`，按 `MusicSourceType` 正确显示「百度 / 网络 / Jamendo / 电台 / 天气电台 / NAS / 本地 / 已下载」及对应主题色
- **歌曲信息面板来源文案不统一**：`SongInfoPanel` 「网络来源」行由 `networkSource?.uppercase()`（BAIDU/METING 原始标识）改为 `sourceType.displayName`，行名改「歌曲来源」，全部来源均显示
- **歌词来源两套文案漂移**：删除 strings.xml 的 `player_highlight_backend/local/network/cached`（中英双语），播放页歌词来源切换标签统一取 `LyricsSource.displayName`

### Changed
- **`LyricsSource` 与 `MusicSourceType` 结构对齐**：新增 `icon` / `color` 字段（颜色语义对齐：内嵌→蓝 / 本地→橙 / 在线→绿 / 缓存→青），`displayName` 统一为短版文案
- 清理无引用资源 `song_info_unknown_source`（中英）；版本 v2.31.1 → **v2.31.2**（versionCode 135 → 136）

## [v2.31.1] - 2026-09-13

> 修复「下载到本地的歌曲无法播放」。本地/已下载歌曲的 `file://` URI 永久有效，但持久化层（上次队列/最近播放/本地歌单/备份恢复）此前对所有歌曲统一置空 `streamUrl`，恢复后播放地址丢失；而播放解析链（`resolveStreamUrl` / `resolveAndPlayCurrentSong`）只认「网络歌曲 / NAS 歌曲」两种来源，本地歌曲（id 为 `local_*`）被误当 NAS 歌曲去后端查询必然失败，部分入口还会因空 URI 被 `onPlayerError` 静默吞掉——表现为点播无反应或自动跳歌。现改为仅网络歌曲置空 `streamUrl`（本地歌曲保留）、解析链补全本地分支（`streamUrl` 缺失时回退 `path`），并新增「已下载优先」：网络歌曲播放/切歌/批量播放前先查下载记录，已下载且文件存在直接播本地文件（支持离线播放）。

### Fixed
- **恢复队列/最近播放/本地歌单中的本地歌曲无法播放**：`AppPreferences` 6 处持久化（`saveLastQueue` / `recordRecentSongObject` / `recordPlay` / `addSongToPlaylist` / 备份恢复×2）改为经 `stripVolatileStreamUrl()` 仅对 `isNetworkSong` 置空 `streamUrl`，本地歌曲保留 `file://` URI
- **播放解析链误把本地歌曲当 NAS 歌曲解析**：`PlayerViewModel.resolveStreamUrl` / `resolveAndPlayCurrentSong` 由二分支（网络 / NAS）扩为三分支，`isLocalSong` 直接用本地 URI（`streamUrl` 缺失回退 `path`），不再拿 `local_*` id 去 `adapter.getSongsByIds()` 查询失败
- **`updateRestoredQueueStreamUrls` 排除本地歌曲**：`nasSongIds` 筛选与合并条件增加 `!it.isLocalSong`，不再向 NAS 后端发起无意义的 `local_*` 查询
- **`playQueue` 空 URL 静默失败**：`needsResolve` 与首曲回填覆盖 `isLocalSong`（历史持久化数据 `streamUrl` 已被置空，用 `path` 回填），避免空 URI 触发 `onPlayerError` 被静默吞掉

### Added
- **已下载优先（离线播放）**：`DownloadRepository.playableLocalUri(song)`（仅 COMPLETED 且本地文件存在非空才返回 `file://` URI）；`NetworkMusicViewModel.playNetworkSong` / `PlayerViewModel.resolveStreamUrl`（网络分支）/ `MainViewModel.playNetworkBatch`（首曲）在解析直链前先查下载记录，已下载直接播本地文件——断网也能播已下载歌曲

### Changed
- 版本：v2.31.0 → **v2.31.1**（versionCode 134 → 135）

## [v2.31.0] - 2026-09-13

> 新增「催眠」（E25）可视化效果：数学函数图像动画。缓慢描线（8s）画出带坐标轴/刻度/标签的数学函数图像，静止凝视 3s 后图像溃散蒸发（2.4s）、空场 0.5s 再随机换下一张——53 条函数（笛卡尔 27 + 参数曲线 8 + 极坐标 8 + 彩蛋 10）一次性全部实现，Fisher-Yates + soft 加权洗牌保证单周期不重复、柔和曲线偏早出现、跨周期交界不重图。屏幕右侧独立"公式带"（18% 屏宽、垂直居中）经自研 `FormulaLayout` 渲染**真数学样式**（嵌套上标 `e^{-x^2}`、真分式、根号横线；上标转普通字形绘制，完全绕开 U+2070 区字体覆盖问题），并随曲线共用同一溃散时间轴按 run 碎散——上标飞离宿主、分式线跟着分子断开。**与歌曲完全解耦**：渲染器结构上不读 `songId`，切歌/暂停/静音/后台返回均不打断当前图像的"描线→静止→溃散"（超时保护推进 DRAW 前先补满描线进度，杜绝后台返回后"只画 1% 就静止"）。溃散双档：LOW/MED 逐点抖动蒸发（点数按画质减半），HIGH 粒子化坍缩（切线初速 + 重力 + 节拍脉冲 ×1.35）。音频联动克制（描线变速 ≤1.15×、辉光/线宽微调），静音时效果完整可看。`FormulaLayout` / `FunctionLibrary` 纯 JVM 可单测，6 个新测试文件 34 个用例；`app/build.gradle.kts` 新增 `testOptions.unitTests.isReturnDefaultValues` 支持渲染器构造的纯 JVM 测试。

### Added
- **「催眠」（E25）数学函数图像动画**：`HypnoticFunctionRenderer`（四态状态机 `8000/3000/2400/500`ms）+ 枚举 `VisualizerTheme.HYPNOTIC_FUNCTION`（"催眠"，Tier.BASIC，编号 25，三档画质全开）；`VisualizerRendererFactory` 注册分支
- **`FormulaLayout`**：数学公式源标记 → 排版 run 的自绘排版引擎（纯 JVM）。支持嵌套上标 `^{}`、下标预留、真分式 `frac{}{}`、根号 `√{}` + 横线、`^2`/`²` 等价归一；花括号不配对降级为字面绘制；3 行断行 + 缩字号兜底（下限 26f）；词/运算符边界断行，`+`/`-` 粘后块保持负数项完整
- **`FunctionLibrary`**：53 条函数定义（`FunctionDef` 支持 `uGap` 断点双段域）+ 三类采样器（归一化输出、自动 y 范围、NaN/Inf/跳变断笔、渐近线大值尾巴剔除）+ 三步加权洗牌（Fisher-Yates → 全局 soft 加权降序 → 跨周期防重交换）
- **坐标轴语义**：主轴画在数学 x=0 / y=0 的真实位置（归一化 ±1.05 内可见、越界钳制），π 域函数用 π 刻度、其余整数刻度、参数/极坐标只画网格不打数字
- **测试**：`FunctionLibraryTest` / `FormulaLayoutTest` / `HypnoticPhaseTest` / `HypnoticScheduleTest` / `HypnoticLayoutTest` / `HypnoticDissolveTest`（34 用例：53 条采样有限性、间断分段、闭合曲线、洗牌覆盖/防重/确定性/songId 无关、排版几何、状态机时长、溃散阈值带）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **`app/build.gradle.kts`**：新增 `testOptions { unitTests.isReturnDefaultValues = true }`（渲染器构造含 `Paint`，纯 JVM 单测需要）
- `VisualizerThemeTest`：枚举总数断言 21 → 23（该文件此前已滞后于源码：E23/E24 加入后实际为 22，未同步），并补 `HYPNOTIC_FUNCTION` 三档支持断言；头部注释同步

## [v2.30.5] - 2026-09-13

> 新增「心跳」可视化效果（E24）：心电图式滚动频谱。一条连续折线从屏幕最右侧生成扫描点，已绘制的波形冻结并整体向左匀速平移，最左端超出屏幕被裁掉。**只取鼓点**——不由宽频时域波形驱动，仅在鼓点命中时注入一个完整的 **P-QRS-T 心搏复合波**，人声/中高频背景不参与；相邻鼓点之间贴中心基线走平。基线固定在屏幕垂直中线，心搏波相对基线上下都有振幅（P/R 波向上，Q/S 波下探）。鼓点判定走**双通道**：`frame.beat` 官方标志 **或** `frame.pulse` 上升沿——后者用于补回「节拍检测跑 50Hz／渲染数据仅 30fps（`EMIT_INTERVAL_MS=33`）」采样下被跳过的单帧标志，解决"有鼓点却不出波形"。幅度取新增的 `AudioFrame.bassRaw`（**未**峰值归一化的低频原始能量）除以自身慢速均值——不能用 `bass`，它经 `boost()` 峰值跟随后在鼓点瞬间恒为 1.0，会让每次鼓点长得一模一样；映射到 0.45–1 保留强弱差异。心搏波拉长到约 0.50s，并设 800ms 最短渲染间隔（≈75 BPM 上限）**丢掉过密的连续鼓点**，保证每个心搏完整、彼此不粘连；主峰高度由 0.40 半屏压到 0.26。复古 CRT 绿线风格，峰顶无任何圆点。环形历史缓冲零分配、零 arraycopy，滚动按列虚拟时间匀速；三档画质全可用。舞台/交互/入口零改动（自动遍历 selectable）。

### Added
- **「心跳」（E24）心电图式滚动频谱**：新增 `EcgWaveRenderer` 与枚举 `VisualizerTheme.ECG_WAVE`（Tier.BASIC，三档全开）；`VisualizerRendererFactory` 注册分支

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **`AudioFrame` 新增 `bassRaw`**（20–250Hz 未归一化原始线性能量）：`bass` 走 `boost()` 峰值跟随归一化，鼓点瞬间恒为 1.0，无法表达鼓点强弱；需要绝对强弱对比的效果改用本字段

## [v2.30.4] - 2026-09-12

> 针对最近三次提交（737ae0c / 1cda585 / ddaef2d）的代码审查结果集中修复：万花筒几何错误、歌词点阵切歌不更新、可视化封面取色 403、Path 批处理丢失的逐元素 alpha、控制行按钮高度不齐、歌词信息每帧重复计算。

### Fixed
- **万花筒（E10）8 扇区塌陷**：把 `withTransform` 手写成显式旋转时，线段**内端点**误用只含 `rotation` 的角（`rotCos/rotSin`），外端点用 `k*45°+rotation`（`baseCos/baseSin`），两端角度不一致 → 8 个扇区的内端全塌到同一方向，只剩 k=0 一瓣正确，画面呈“章鱼”状。现内外端统一用扇区角
- **歌词点阵（E23）切歌后永远停在上一首**：`swapper = remember { RendererSwapper() }` 无 key，切歌不会重建渲染器、不走 `onEnter`；`displayedLineIndex` 仅在 `< 0` 时初始化，且行切换只认 `diff >= 1`，新歌 idx 从 0 开始使 `diff` 为负 → 永不触发。现 `RenderContext` 新增 `songId`，渲染器比对到歌曲变化即回到初始态（顺带解决 seek 回退时 `diff < 0` 无分支的问题）
- **可视化封面取色在百度网盘源失败（403）**：`coil.ImageLoader(app)` 新建的是**无配置**实例，绕过 `NasMusicApp.newImageLoader()` 注入的百度 dlink UA 拦截器 → 封面加载失败、配色长期停在 `CoverPalette.Fallback`；且该实例从不 `shutdown()`，泄漏线程池与缓存。改用 `coil.Coil.imageLoader(app)` 全局单例（与 `MainViewModel` 已修过的同一处教训一致）
- **封面加载快慢请求竞态**：快速切歌时先发出的任务可能后返回，用上一首的封面/配色覆盖当前歌曲。协程内增加 `requestedKey == loadedCoverKey` 校验后再写回
- **Path 批处理抹平了逐元素 alpha**：合并 Path 的前提是“同色同 alpha”，上一版有两处违反该前提
  - 「隧道穿越」（E03）：原 `alpha = fade * 0.7f`（逐环深度衰减）被统一为 0.7f → 远处环不再淡出，纵深感丢失；新增的 `BlendMode.Plus` 还会让重叠处过曝。改为按 z 深度分 3 桶，并恢复原来的 SrcOver
  - 「径向星芒」（E06）：原 `alpha = 0.6f + v * 0.4f`（随频谱明暗）被统一为 0.8f → 端点不再随音乐呼吸。改为按 v 分 3 桶
- **频谱环（E05）峰值帽色阶断层**：按 hue 分 4 桶时与所在条最大色差约 16.9°，肉眼可见 4 段色阶。桶数 4 → 8，最大色差降到约 8°
- **数字雨（E16）保持 0/1 二进制雨**：设计即二进制字符雨（仅 0/1），撤销上一轮"扩充为 0-9"的改动，字符集恢复为 0/1，字形缓存由 40 张缩回 8 张（2 字符 × 4 档绿）。一次性预渲染 + 每帧 blit 的架构不变。`onExit` 补 `recycle()`
- **播放控制行按钮高度不齐**：v2.30.3 把 `IconButton` 紧凑尺寸 48→40dp 时未同步 `VocalToggleButton`（仍 48dp），同一行图标按钮 40dp、文字按钮 48dp。现统一为 40dp

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **歌词信息每帧计算量减半以上**：`computeLyricInfo` 原先在两个 Canvas 内各调一次（新层 + 交叉淡出的旧层），且内含 O(N) 全量扫描求最长行。现提到外层每帧只算一次，并新增 `LyricMetrics` / `computeLyricMetrics`，由 `remember(lyrics)` 缓存歌曲级常量
- `LyricTopBar` 去掉 `remember(lyrics, progressMs) { derivedStateOf { ... } }`：key 含 `progressMs` 会导致每帧重建 State 对象，等于没缓存；二分查找本身 O(log n)，直接调用即可
- **恢复可视化入口按钮的 i18n**：v2.30.3 为解决截断临时硬编码中文「幻」，使 `values-en` 失效。新增 `player_visualizer_short`（中「频谱」/ 英「Viz」），宽度与「K歌」同为 52dp
- 删除死参数：自动导演移除后 `VisualizerStage` 的 `crossfade` 恒为 false，UI 层参数一并移除（`RendererSwapper` 能力及其单测保留）
- 删除 `BloomRenderer` 中只有 `super.onEnter(ctx)` 的空覆写
- `AppSettings` 类注释由“18 套 + 1 自动档”更正为“21 套，无自动档”；`LEGACY_MAP` 显式收录 `AUTO_DIRECTOR`（与既有 fallback 行为一致，用于固化迁移意图）
- 删除 `LyricsDotMatrixRenderer` 采样函数中遗留的诊断 `println`（在每帧热路径上）

### Fixed（二次审阅补充）
- **数字雨字形缓存仅在 `onExit` 释放**：画质变化触发 `onEnter` 重建缓存时会丢弃旧 Bitmap 而不回收。统一收敛到 `releaseGlyphs()`，`onEnter` / `onExit` 走同一释放路径
- **歌词点阵切歌判定在无 id 歌曲上失效**：`songId` 为 null 时（本地扫描歌曲可能没有 id）两首歌 id 都是 null，不会触发重置。现 `songId` 与歌曲标题任一变化即重置

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed（二次审阅补充）
- `VisualizerStage` 删除随 `crossfade` 参数一起失效的 KDoc；数字雨的释放注释合并进 `releaseGlyphs()`
- **歌词点阵（E23）字号逐帧重算**：`LyricsDotMatrixRenderer.computeFontSize` 每帧 `new Paint` + `measureText` 推导字号，但结果只依赖最长行（整曲常量）与画布尺寸，整首歌恒定。现加 `cachedFontW`/`cachedFontH`/`cachedLongest` 变化检测，仅尺寸或歌词变化时算一次，消除每帧对象分配（最长行本身已由 `remember(lyrics)` 缓存，每首歌只扫描一次）

### Tests
- `SpectrumAnalyzerTest`：把 v2.30.2 放宽的 `actual in 0.2f..0.31f` 收窄为 `0.235f..0.275f`（±0.02），恢复对“bass 落在线性分支而非 gamma 分支”的回归探测力

## [v2.30.3] - 2026-09-12

> 播放页 MTV 按钮文字不再被裁切；数字雨改为字形预渲染 Bitmap 大幅降低每帧开销；移除「自动导演」随机选特效功能（遥控器选哪个就恒定显示哪个）；批量将高频小图元（频谱环峰值、万花筒线段、径向星芒、隧道环点、烟花频谱、Bloom 光斑）合并为 Path 单次绘制以提升弱 GPU 流畅度。

### Removed
- **移除「自动导演」随机选特效功能（`AUTO_DIRECTOR`）**：删除 `AutoDirector.kt` 及枚举 `Tier.MODE` / `isAutoDirector` / `selectable` 中的自动档；全屏舞台去掉 500ms 低频重估与 600ms 交叉淡入分支，`VisualizerViewModel` 移除 `director` / `resolveTheme`。现遥控器选中哪个效果就恒定显示哪个（21 套手动效果）。老数据若存过 `AUTO_DIRECTOR` 会自动回落到默认「频谱环」

### Fixed
- **播放页 MTV 按钮文字显示不全（只看到 "MT"）**：根因是紧凑控制行总宽（约 438dp）超出固定 380dp 左栏，单独加宽 MTV 只会把它进一步推出屏外被歌词栏盖住。改为整体收紧整行——图标按钮 40/52dp、文字按钮「幻」40dp「K歌」52dp「MTV」64dp、间距 8→6dp，合计约 364dp，MTV 完整落进左栏单行显示
- **「数字雨」（E16）TV 上卡顿**：逐字符 `drawText` 的文本排布度量开销大。改为进入时一次性预渲染 2 数字 × 4 档绿共 8 张字形 Bitmap，每帧用 `nativeCanvas.drawBitmap` 快速 blit；每列高度 20→14、列数随画质档位收窄，观感一致但每帧开销显著下降

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **高频小图元合并为 Path 批处理（降 draw 次数）**（此前逐 draw 逼近 Android 5.1 单帧 ≈200 次独立指令预算，弱 GPU 上易掉帧）：
  - 「频谱环」（E05）：约 64 次峰值帽 `drawCircle` → 按 hue 分 4 桶的 4 条 Path
  - 「万花筒」（E10）：160 条线段 + 160 个端点光点 → 2 条 Path（长度/端点半径仍随频谱变化）
  - 「径向星芒」（E06）：约 128 次 `drawCircle` → 1 条 Path
  - 「隧道穿越」（E03）：约 192 次环内 `drawCircle` → 1 条 Path
  - 「烟花」（E14）：约 128 次背景频谱 `drawRect` → 1 条 Path
  - 「Bloom」（E01）：约 768 次 `drawRoundRect` → 6 条 Path
- `VisualizerThemeTest` 同步更新：21 套效果、无自动档、`Tier` 门控断言去掉 `MODE`

## [v2.30.2] - 2026-09-12

> 可视化效果库补全：新增「棱镜彩虹」效果并纳入效果库；「极光」重构为写实自然星空版（墨绿→午夜蓝→近黑渐变 + 漫天星尘 + 十字星芒 + 右侧蜿蜒青绿丝带）；「歌词点阵」新增按歌曲最长行自适应字号、粒子按节奏/鼓点浮动、两行滚动凝聚放缓。

### Added
- **新增效果「棱镜彩虹」（E21 `PRISM_HOLO`）**：返回效果库可选列表。全息虹彩流体风格——莫兰迪低饱和打底（灰褐→米色→浅棕灰）+ 三主带（粉紫/淡蓝/鹅黄）与两条反射残影次带（紫/青）的流体色带，内部色相随高度流动构成全息渐变，三维隧道式卷曲营造纵深；底部一段棱镜色散彩虹光晕 + 随时间滑动的柔光；四边莫兰迪暗角聚焦中央
- **「歌词点阵」自适应字号（E23）**：`RenderContext` 新增 `lyricMaxLineChars`（全曲最长句字符数），渲染器据此换算让最长行宽度恰好约占屏宽 80% 的字号，并以「行高不超过屏高 40%（两行预留）」约束，最终夹在屏高 5%~16% 区间——长句歌字号随行缩短而放大、且不再因字号过大导致粒子稀疏空洞
- **「歌词点阵」粒子律动（E23）**：粒子浮动由随机改为节奏驱动——所有粒子按低音（bass）同步上下起伏，叠加依粒子横向位置的固定波相位形成整行规整波浪，鼓点（低音峰值）时幅度增大，节奏感强且不发散
- **「歌词点阵」两行滚动凝聚放缓（E23）**：初始两行凝聚 700ms→1200ms、单字凝聚到达 700ms→1200ms、每字凝聚延迟 110ms→160ms、行上移 600ms→700ms，使新一行歌词凝聚过程清晰可见

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **「极光」（E22）重构为写实自然星空版**：背景由三段平滑渐变（底部深海墨绿→中部午夜蓝→顶部近黑）；新增漫天星尘（HIGH 150 颗，明暗交错、随高频闪烁）与少量带十字星芒高亮星（随低频旋转缩放）；极光由中部等距竖光幕改为集中在画面右半侧——5 条丝带从右下蜿蜒向中央、顶部向右上卷曲，底部贴近地平线；新增底部地平线柔和青绿光晕（与极光根部交融）。动态联动：低频→光带变浓变亮变厚 + 整体呼吸胀缩；高频→光带边缘轻纱流动波纹 + 星尘/星芒加速闪烁；情绪爆发（高频+能量高）→ 极光短暂从荧光绿幻化紫红/冰蓝

### Fixed
- 「歌词点阵」上一版字号刻度漏乘屏高导致粒子文字整段消失（字号被钳在 0.05~0.16 像素）——改为 `coerceIn(h*min, h*max)` 换算真实像素

## [v2.30.1] - 2026-09-12

> 可视化 P6 收尾：自动导演的场景切换由硬切改为 600ms 交叉淡入；新增 PCM 降级通道，解决部分国产 TV `Visualizer` 绑定成功却恒返回全 0 导致频谱全平的问题。

### Added
- **PCM 降级通道（P6）**：新增 `player/` 下 `PcmFallbackChannel` / `PcmTapProcessor` / `PcmRingBuffer` / `Radix2Fft` / `PcmSpectrumTap`。系统 `Visualizer` 连续静音 ≥2s 且正在播放时自动切到 AudioSink 的 PCM 自算频谱：`PcmTapProcessor` 挂在处理器链**最前**（取人声消除之前的原始信号），`queueInput()` 只做降混 + memcpy（< 20µs，**严禁在此 FFT**）；FFT 由专用 `HandlerThread`（`THREAD_PRIORITY_BACKGROUND`）每 40ms 执行，自实现 radix-2（N=1024，**不引入 JTransforms**）
- **降级仲裁**：`SpectrumAnalyzer` 三态仲裁（Visualizer → PCM 探测 → 回滚）；两条通道**共用同一条分析链** `analyze()`，AGC / 感知加权柱映射 / 双通道输出只有一份实现，保证观感一致；PCM 激活后 3s 内无有效信号则回滚并抑制 5 分钟
- 测试：`Radix2FftTest`(7) / `PcmRingBufferTest`(7) / `SpectrumAnalyzerPcmTest`(5) / `RendererSwapperTest`(7)

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **`AUTO_DIRECTOR` 场景切换改为 600ms 交叉淡入**（开发方案 §4.21 的"禁止硬切"红线）：新增 `visualizer/RendererSwapper.kt` —— 切换时同时持有新旧两个渲染器，分别以 `t` / `1-t` 透明度叠绘两层 Canvas，600ms 后释放旧层。**手动切换（`←/→`、设置页选效果、切画质档）仍是硬切**，用户按键后需要即时反馈
- `VisualizerStage` 渲染器生命周期改由 `RendererSwapper` 驱动：透明度经 `graphicsLayer { alpha = ... }` 延迟读取 → 淡入过程**只重绘不重组**；旧层存在性用 State，每次过渡最多重组两次
- 画质档位变化改为**对现有渲染器重新 `onEnter`**（`Terrain` / `LiquidGrid` / `MatrixRain` / 粒子类均在 `onEnter` 里按 `VisualQuality` 预分配缓冲），不再重建实例

### Fixed
- 降级切换瞬间两条通道可能并发写入共享缓冲 → `SpectrumAnalyzer.analyze()` 加 `@Synchronized`，并在 `usingPcm` 时丢弃 Visualizer 通道的残留帧（`enabled = false` 可能失败或回调在途），避免全 0 帧覆盖 PCM 结果造成画面闪烁

## [v2.30.0] - 2026-09-12

> 音乐可视化升级：修复「频谱不好看、画面不呼吸」的数据层根因，新增独立全屏可视化舞台（20 套效果 + 自动导演模式），并把频谱数据链路收敛为单一 `AudioFrame` 契约。

### Added
- **全屏可视化舞台**（`ui/components/VisualizerStage.kt` + `ui/viewmodel/VisualizerViewModel.kt`）：三层结构（封面背景 / 效果 Canvas / 歌词 + 指示器 + 控制栏），复用 K 歌页 `showKaraoke` 覆盖层模式，**不新增 Screen 枚举**。TV `←/→`、手机左右滑动（阈值 80dp）切换效果；顶部歌词行（二分查找 + `derivedStateOf` 隔离）；效果名 2.5s 淡出 Toast；底部 21 档指示器（不支持档位置灰）；控制栏 3s 自动隐藏（隐藏态才拦截方向键，避免与焦点导航冲突）；手机左上返回按钮；BACK 走 AppRoot 仲裁（优先级仅次于沉浸模式）
- **入口按钮**：`PlayerControls` 新增「频谱」，位于播放模式与 K 歌之间（`player_visualizer`，非电台且有当前歌曲时显示）
- **20 套效果 + 自动导演模式**：`visualizer/renderers/` 下 `BasicRenderers`(E01–E07) / `AdvancedRenderers`(E08–E17) / `ParticleRenderers` / `UltraRenderers`(E18–E20)；`AutoDirector` 按能量调度场景（8s 最小驻留 + 升档 0.80 / 降档 0.65 回差 + 500ms 评估周期）
- **`AudioFrame` 统一契约**（`visualizer/AudioFrame.kt`）：单例复用、渲染层只读、**渲染侧不再自行指定柱数**（结构上根治 96 vs 32 错位复发）；`SpectrumRepository` 为唯一写入方，只发布 `frameSeq` 帧序号
- **`BeatDetector`**：43 帧低频历史 + 方差自适应阈值（系数 c ∈ [1.15, 1.9]）+ 240ms 冷却（上限 250 BPM）+ 快起慢落 `pulse`（decay 0.90）；`SectionEnergyTracker`（8s 滑动均值）、`PeakHoldTracker`、`ParticlePool`
- 新增依赖 `androidx.palette:palette-ktx:1.0.0`（封面取色 T5）
- ProGuard：`-keep class com.nasmusic.tv.visualizer.**` + `VisualizerTheme` / `VisualQuality` / `VisualizerTheme$Tier` 枚举 keep（枚举名持久化到 DataStore，R8 重命名会导致主题解析失败）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- **`VisualizerTheme` 重写为 21 值**（20 效果 + `AUTO_DIRECTOR`，带 `Tier` 分级 BASIC/ADV/ULTRA/MODE）；新增 `VisualQuality` 画质档位（HIGH 64 柱/3 层辉光/200 粒子/32×18 网格/允许帧缓冲、MEDIUM 默认、LOW 32 柱禁用粒子）与 `supports(theme)` 门控
- **`SpectrumAnalyzer` 数据层重构**：
  - 归一化分母由「当前帧低频峰值」改为「低频区运行峰值」`lowRunningPeak`（每帧 ×0.995 ≈ 3s 时间常数）——原实现分子分母同步缩放使低频柱**恒为 1.0**，轻/重鼓点长得一样，画面永不呼吸（BUG ⑨）
  - **双通道输出**：`spectrum[]` 走 gamma `^0.75` 保证小信号可见；`bass`/`mid`/`treble`/`energy` 走**线性**值保留动态范围（前者会把 5:1 的轻重比压到 3.3:1，BUG ⑨-b）
  - `captureSize` 取设备最大值（原写死 1024，浪费支持 2048 的设备，bass 区 bin 数 5→11）；回调周期 50000µs → 20000µs（20Hz → 50Hz，原每周期漏掉 54% 音频，鼓点 attack 5–10ms 极易整拍漏掉）
  - 零分配：预分配 `magnitudeBuf` / `barBuf` / `displayBuf` / `linearBuf` / `waveBuf`，删除每帧 `FloatArray(n)` 与 `sliceArray(5..19)` 分配（原 ≈110KB/s）
  - 渲染节流（33ms）下沉到 `SpectrumRepository`（节拍检测仍用全部 50Hz 帧）
- 柱数 32 → **64**（`SpectrumContract.BAR_COUNT`），频段边界 Bass `0–39` / Mid `40–55` / Treble `56–63`
- 设置页移除频谱开关与主题选择器（入口按钮 + 舞台内切换已覆盖）；NowPlaying 48dp 小频谱条一并移除
- 补齐多语言清理：`values-en/strings.xml` 中残留的 `settings_spectrum` / `settings_spectrum_desc` / `settings_spectrum_theme` 一并删除（默认 `values/` 已删而译文未删，release 构建报 `removing resource ... without required default value` 且译文静默失效）
- `AppRoot`：BACK 仲裁链、导航栏隐藏条件、`LaunchedEffect` 依赖数组三处接入 `showVisualizer`
- `AudioFrame.reset()` 明确契约：归零全部分析字段，但 `timeMs` / `seq` 刻意保留（`AutoDirector` 的最小驻留依赖 `timeMs` 单调性，归零会让切歌后调度器冻结约 8s）；换歌时的干净起点由 `SpectrumRepository.reset()` 显式清零

### Fixed
- **⑬ 每帧强制重组**：删除 `SpectrumAnalyzer` 的 `StateFlow<FloatArray>` 兼容通道——它在 33ms 节流点 `emit(displayBuf.copyOf())`，`FloatArray` 按引用比较，订阅方每 33ms 必然重组一次。该数据的唯一潜在消费方（NowPlaying 48dp 小条）已随舞台上线移除，均衡器页从未传入 `spectrumData`，故整条链路（`SpectrumAnalyzer` → `PlayerEqualizer` → `PlayerManager` → `PlayerViewModel` → `NowPlayingBranch` → `NowPlayingScreen`）连同死参数一并删除
- **⑭ 静音时频谱整体消失**：静音不再返回 `FloatArray(0)`（会让渲染层柱数归零），改为写入**长度正确的全 0 数组**；波形一并归零（避免静音期波形类效果显示上一帧残影）；不再把 `runningPeak` 压到 1f，恢复播放后前 1~2s 不再被压制
- **⑮ 老用户主题失效**：`VisualizerTheme.fromKey` 增加 `LEGACY_MAP`（`COLOR_FLOW`→`CIRCULAR_RING`、`NEON_PULSE`→`IMMERSIVE_BLOOM`、`CLASSICAL_WAVE`→`SONIC_TERRAIN`）+ 大小写容错 + 默认兜底，DataStore 旧值平滑迁移（无需显式 migration）
- `SpectrumAnalyzer.processFft` 增加 FFT bins 越界防御（回调携带的 bins 多于 `attach` 预分配时补分配，不再 ArrayIndexOutOfBounds）
- **单元测试**：新增 6 个测试类共 47 例——`BeatDetectorTest`(7) / `AudioFrameTest`(5) / `SpectrumRepositoryTest`(6) / `SpectrumAnalyzerTest`(10，覆盖 ⑨ ⑨-b ⑭ 与柱数契约) / `VisualizerThemeTest`(11，覆盖 ⑮) / `FindCurrentLyricLineTest`(8)；全量 38 类 357 例通过
- **构建验证**：`assembleRelease`（R8 + `shrinkResources` + 签名）通过，新增 visualizer keep 规则无回归；APK 42.4MB → **21.8MB**（`NASMusicTV-release-v2-30-0.apk`）

## [v2.29.3] - 2026-09-11

> 依据《NASMusicTV 代码审查报告（2026-09-11）》（110 项发现，覆盖全部 275 个 Kotlin 源文件）分阶段实施的全量修复，P0/P1/P2/P3 四阶段全部落地。

### Fixed
- **P0 阻断性（13 项 High）**：`CryptoUtils.encrypt` 加密失败不再静默返回明文（改为抛异常，消除凭据明文落盘）；Daoliyu/Feiniu 适配器全部 OkHttp 调用（含 `testConnection`）补 `use{}` 关闭 Response；`MusicScanner` 改 `getColumnIndex` + SDK 版本守卫（消除列序假设崩溃）；下载增加 `Content-Length` 完整性校验（截断文件不再被当作成品）；`EmbeddedCoverExtractor` 对 `content://` 改 `MediaMetadataRetriever.setDataSource(Context, Uri)`；播放引擎 H1–H5（overlap-add 段缓冲清零、crossfade `release()`、人声分离单飞锁、不再关闭进程级 `OrtEnvironment`）；`AppRoot` 补 `showKaraoke` BACK 分支 + `KaraokeStepPickerDialog` BackHandler（K 歌页 BACK 不再误触退出确认）；`LyricsManager` 在线歌词变体轮询补边界守卫；Bilibili MV 实现 WBI 签名
- **P1 用户可见正确性**：歌词系统四修——网络歌词编码统一走 `EncodingUtils`（GBK 兜底）、网络候选加相关性校验（标题互含 + 歌手加分，杜绝搜到同名异曲）、后端歌词优先于持久化缓存（尊重用户显式选择，`userNetworkLyricsOverride` 记录手动切换）、候选拉取主线程 I/O 移入 IO 作用域并加 `Mutex` 防惊群；`LyricsPersistentCache` 补真 LRU（命中刷新 `lastPlayedAt` + 节流落盘）；导出修复目标设备错配（`onTreeGranted` 回到发起授权的设备）并加并发锁（`exportMutex.tryLock`）；百度网盘 token 日志脱敏（去掉前 10 字符）、服务端令牌失效（errno -6/31045）统一强制刷新重试一次、内嵌封面按 ID3 标签总长两阶段读取；Subsonic 用户名 URL 编码 + 歌曲总数修复；`BackupTransferServer` 上传改分块累积 + 32MB 上限（防 Content-Length 预分配 OOM DoS）；`RemoteControlHtml` 补 `esc`/`escAttr` 转义（修反射型 XSS）
- **P2 健壮性与可观测性**：下载失败清理孤儿文件（成品 + `.lrc`/`.jpg` 旁路）并在 `cancelAll()` 真正取消 OkHttp `Call`；本地库跨通道去重改按**真实文件路径**（原 contentUri 去重漏掉同文件多通道收录）+ `LIKE ... ESCAPE '\'` 转义；`MediaTagWriter.compressCover` 补 `Bitmap.recycle()`（native 内存泄漏）；`CoilBitmapLoader` future 写入 + 取消转发守卫；`WeatherApi` 四处 Response 补 `use{}`、WMO 码表修正（85/86 阵雪）、forecast `cnt` 5→40；`BaiduFileIndexCache.setCoverUrl` 整体加锁（消除读-改-写丢失更新）
- **P3 清理与一致性**：统一 Dialog BACK 注册入口 `RegisterDialogBackHandler`（`rememberUpdatedState` + `DisposableEffect(Unit)`，消除父重组瞬间注销导致的 BACK 穿透到退出确认的竞态），5 处弹窗改造；QR 位图移出组合期改后台线程生成；退出流程 `runBlocking` 改 IO 协程（原主线程最长冻结 1.5s）；`ModelTransferServer` 日志端口修正（18082→18083，硬编码改常量）+ `/api/status` 不再返回内部绝对路径；`RemoteControlServer` 队列索引补 `0 ≤ idx < size` 校验；手写 multipart 边界匹配重写为 KMP（`MultipartBoundaryStreamer`，修自重叠 boundary 漏判 + 8 项单元测试）；`NasMusicApp` 下载设置 lambda 合并为单次快照（原 4 次 `appSettings.first()` 可能不一致）；`NasMusicApp:139` 百度 OkHttpClient 误导性注释修正（描述"信任所有证书"与实现不符）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- `modelDownloadUrl` 死字段补齐：新增 DataStore key `settings_model_download_url` + `appSettings` Flow 映射 + `@Volatile` 内存镜像 + `setModelDownloadUrl`/`getModelDownloadUrlSync` + 备份导入回填；`ModelDownloadManager` 支持自定义 URL 优先于内置候选列表（可用于自建镜像/NAS 绕过 CDN 限制）
- `AppSettings` 补充 **Gson 前向兼容约束**文档（新增字段必须带默认值，否则新旧版本互反序列化会失败）

### Removed
- 死代码清理：`lyrics/Mp3MetadataExtractor.kt`、`player/VocalSeparationController.kt`（均零调用点，功能已由 `HqSeparationOrchestrator` 承接）、`AccompanimentCache` 的 `startPreSeparation`/`cancelPreSeparation`/`PreSeparationState` 整条未接线通路（含构造参数 `externalScope`）；相关文档注释中的失效类引用同步修正

## [v2.29.2] - 2026-09-11

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- 智能电台多源化（F2-3 演进）：推荐池从 NAS 单源扩展为 NAS + 本地（含下载）+ Meting 网络歌单采样（随机抽 3 个歌单、每单 30 首，tagged isNetworkSong），distinctBy 去重后统一打分——网络歌曲无 genre/albumId 时相应加分项自然失效，仍可入池推荐；SmartRadioManager 新增 startFromScratch 无种子启动（以 play_counts 最高歌曲为偏好种子，纯加权随机）
- 智能电台入口迁移（F2-3 演进）：NowPlaying 控制按钮行入口移除，改为首页独立区块（天气电台卡片上方，随心听下方）；与当前播放歌曲解耦，不依赖 NAS 连接（多源池至少有本地+网络兜底）
- 智能电台交互列表化（F2-3 手测反馈）：首页区块从"点卡片直接播放"（天气电台式）改为随心听式浏览列表——进入首页自动生成 20 首推荐批次（SmartRadioManager 新增 generateOnly：只生成不播放），区块展示 SectionHeader + 歌曲卡片横滑行，点任意卡片整批入队从该首播起，header 右侧"换一批"重新生成（保留电台种子，排除已推荐歌曲）；移除 SmartRadioCard 与 startSmartRadio/startSmartRadioFromCurrent 死代码

## [v2.29.1] - 2026-09-11

### Fixed
- 播放统计"本月"Tab 恒为空（F2-1 缺陷，release 专属）：ProGuard/R8 缺少 `data.stats` 包 keep 规则——`PlayStatsRepository` 的 Gson `TypeToken` 匿名类泛型签名被擦除后，`fromJson` 返回原始 `LinkedTreeMap`，`+1` 触发 `ClassCastException` 被 catch 静默吞掉，月度键（play_stats_monthly）从未写入；读取端同理解析失败降级空表。单测全绿是因为 Robolectric 不跑 R8。修复：proguard-rules.pro 补 `-keep class com.nasmusic.tv.data.stats.** { *; }`（与 v2.5.1 Gson 类型擦除崩溃同类坑，AGENTS.md 已有警示"新增序列化模型时勿删 keep 规则"）。mapping.txt 验证：data.stats 类恢复 identity 映射
- 睡眠定时弹窗布局优化（F2-2b 手测反馈）：定时按钮从 NowPlaying 顶栏独占行移至歌词来源标签行（A+ 字号按钮右侧）；弹窗改紧凑布局——标题"定时关闭"、中间 -/[N 分钟]/+ 步进（5 分钟步长，5-300）、下方两行（15/30 快捷档 + OK/取消定时），去掉按钮内重复的"定时"字样（新增 np_sleep_timer_min 短格式字符串）
- 通知栏按钮不刷新/锁屏无自定义按钮（F2-2 遗留缺陷）：media3 `MediaLibraryService` 自带默认通知 Provider，与自建多按钮通知共用 ID=1 互相覆盖，导致下拉通知栏样式漂移、状态不同步。改为 `setMediaNotificationProvider` 接管，通知统一由本服务 `buildNotification` 渲染 5 按钮；Android 13+ 锁屏/超级岛系统媒体卡片不读通知 action、由 MediaSession custom layout 渲染，新增 `setCustomLayout` + `SessionCommand`（onConnect/onCustomCommand）注入播放模式/睡眠定时两个自定义键；compact view 索引修复为 (0,1,2)（原 (1,2,3) 实际显示 播放/暂停、下一首、播放模式，漏掉上一首）
- 睡眠定时器入口隐蔽（F2-2b）：NowPlaying 顶栏右侧新增常驻小按钮（未启动显示"定时 -"、运行中橙色显示"定时 N 分钟"），点击弹出档位选择窗（15/30/60/90 分钟 + 运行中可取消），手机触摸/TV D-Pad 通用；原"仅运行中显示"状态条移除

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- 重构：AppRoot（1155 行）`when(currentScreen)` 14 个 Screen 分支全部提取至 `ui/components/branches/` 包（Home/NowPlaying/Library/Mine/Queue/Settings/ServerConnect/AlbumDetail/ArtistDetail/Equalizer/PlaylistManagement/Netdisk/WeatherRadio/PlayStats Branch），AppRoot 精简为路由壳（391 行），外层共享状态参数化注入、`pickerSong` 弹窗状态保留在宿主经回调上抛。根除 JVM 单方法 64KB 上限（MethodTooLargeException）——此前 F2-2b 仅新增 3 个参数即触顶；实测 AppRoot 方法字节码 8303 条指令、最大分支 SettingsBranch 10437 条（上限 65535）。后续新增屏幕必须新建独立 Branch 文件

## [v2.29.0] - 2026-09-10

### Added
- 播放统计面板（F2-1）：新增月度播放统计（play_stats_monthly 键，recordPlayWithSong 同次 DataStore edit 原子写入，滚动保留 12 个月）+ 统计页（本月/累计 Tab、播放次数/歌曲数 KPI、最爱歌手 Top10、流派分布原生 Canvas 条形图）；入口在设置 → 数据管理 → 播放统计；聚合器纯函数可单测（PlayStatsAggregatorTest 8 用例）
- 睡眠定时器（F2-2）：SleepTimerController（不持久化，档位 15/30/60/90 分钟，时间源可注入）+ 到期自动暂停；通知栏按钮显示剩余分钟（每分钟刷新）+ NowPlaying 顶栏状态条（点击快速设置 30 分钟）
- 通知栏增强（F2-2）：按钮 3→5（新增播放模式循环切换、定时关闭，自定义 action 广播经 RECEIVER_NOT_EXPORTED 注册，API 22/33+ 双兼容）；下一首 subText（队尾不显示）；compact view 仍为核心 3 键
- 智能电台（F2-3）：基于当前歌曲流派+歌手生成相似随机流——RadioSongScorer 打分纯函数（同歌手+50/同流派+30/同专辑+10/同年代+5/播放偏好加权/已播强排除，加权随机采样）+ SmartRadioManager 状态机（两级曲库加载：流派先筛≥50 否则全量分页 5000 硬上限，曲库耗尽自动换批）；入口在 NowPlaying 控制按钮行（NAS 已连接且非网络歌曲时显示）；首期仅 NAS 曲库（网络歌曲无 genre）
- 断线续播（F2-4）：断网时冻结错误跳歌（networkLost 标志，onPlayerError 不跳歌不重解析，记录断点 ResumePoint），网络恢复后 2 秒去抖重解析当前歌续播（wasPlaying=false 只加载不播）；MainViewModel.onNetworkAvailable/Last 接线，既有 3 次重连逻辑不变
- 跨曲交叉淡入淡出（F2-5）：CrossfadeController 双实例方案——切歌窗口主 player 旧歌淡出、crossfadePlayer 新歌淡入（50ms 步进线性斜坡），窗口结束 transitionToIndex 完成切歌；设置开关+时长档位（2/4/6/8/12s，默认关闭）；K歌/MTV 模式（suppressPlayback）、单曲循环、队列仅 1 首、下一首 streamUrl 为空均不触发；手动切歌立即中断；crossfadePlayer 不持音频焦点
- 音质分级（F2-6）：Meting 源 br 参数 + 降级链（无损999→320→128，AUTO 不传 br）；BandwidthEstimator 滑动窗口带宽估计（30s/32样本，>10Mbps→999 / 2-10Mbps→320 / <2Mbps→128）；设置页 4 档选择（自动/无损/320k/128k）；NAS 原品质直传不受影响

## [v2.28.1] - 2026-09-10

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- 重构（N 系列，2026-09-10，经所有者确认实施）：MainViewModel 兼容转发层消除——12 个子 VM 公开为只读属性（手动 DI，不引入 Hilt），删除 121 个纯透传转发、保留 12 个胶水转发，AppRoot/NetdiskScreen/MainActivity/MediaKeyHandler 改经子 VM 直调，MainViewModel 3186→3055 行（N-1）
- 重构：DomainPrefs.kt（179 行 10 类）拆为 10 个独立子 Prefs 文件，data/prefs/ 达 14 文件单类单文件（N-2）
- 重构：AppRoot 17 个单分支独占 collectAsState 订阅下沉至 when 分支内，顶层订阅 35→18 处（≤20 达标），未组合的 Screen 不再收集不重组（N-3）
- 重构：PlayerManager（64.1KB）拆分——HQ 人声分离编排提取 HqSeparationOrchestrator（PlayerHost 窄接口，延续 R-5 模式）、均衡器/频谱提取 PlayerEqualizer，PlayerManager 精简为播放核心+队列状态机（41.5KB），外部调用面零改动（N-4；MediaSession/音频焦点实为 PlaybackService 职责，不涉及）

### Fixed
- 封面缓存清除不生效：`clearCoverCache()` 用 `coil.ImageLoader(context)` 工厂函数创建全新无配置实例，其 `memoryCache`/`diskCache` 与 UI 实际使用的全局 ImageLoader（`NasMusicApp.newImageLoader()`，已注入百度 dlink UA 拦截器）不是同一个，清除操作打在了无人使用的实例上、磁盘 100MB 缓存目录也未被清理。改为 `Coil.imageLoader(context)` 取全局单例，与 `PlaybackService`/`CoilBitmapLoader` 同一实例。顺带消除该处 `ExperimentalCoilApi` 未 opt-in 告警
- CI：`keystore.properties` 缺失时不再阻塞单测任务——`signingConfigs` 仅在配置存在时创建 release 签名，`signingConfig` 改用 `findByName`（返回 null）替代 `getByName`（配置期抛 `NoSuchElementException`）；此前 `file("")` 在配置期抛 `IllegalArgumentException`，导致 CI 的 `testDebugUnitTest` 整体失败。test job 同步补显式 Android SDK 安装步骤，不再依赖 runner 预装的隐含状态。
- 网络歌曲队列播放中断：某首歌链接过期失败跳过后，下一首不自动播放（需手动点播放恢复）。根因：出错后 ExoPlayer 处于 IDLE 状态，`next()` 的 `seekToNextMediaItem()` 既不触发 `onMediaItemTransition`（索引不同步、空 URL 懒解析检测失效）也不重新 `prepare`，播放器静默停住。修复：新增 `transitionToIndex()` 手动恢复路径（同步索引 + 空 streamUrl 触发 `onNeedResolveStreamUrl` 解析 + seekTo/prepare/play），`next()`/`previous()` 检测到 IDLE 时统一走该路径（随机模式同策略排除已播历史）；`onMediaItemTransition` 空 URL 检测从仅 AUTO 放宽到 AUTO|SEEK；`onIsPlayingChanged(true)` 时重置 `lastErrorRetryIndex`，同一首歌成功起播后若链接再次过期仍可自动重解析一次（原仅切歌时重置）

## [v2.28.0] - 2026-09-10

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed
- 重构：MainViewModel（5451 行）按 W0 冻结清单拆分为 13 个领域子 ViewModel（WeatherRadio/Backup/Playlist/Download/MvSearch/VocalSeparation/Server/Search/NetworkMusic/Player/Navigation/PlayHistory + 事件契约 ViewModelEvents），MainViewModel 精简为协调者 + 兼容转发层（3186 行），AppRoot 引用保持不变；详见 docs/codebase-refactoring-plan-2026-09.md R-1
- 重构：BaiduConnectionState 归属从 MainViewModel 迁移至 NetworkMusicViewModel
- 重构：SettingsScreen 拆分为 9 个 Section（ui/screens/settings/，State/Actions 分组签名），主文件 2529→924 行（R-2）
- 重构：LibraryScreen 五个 NAS 浏览 Tab 迁至 ui/screens/library/browse/，主文件 1695→647 行，详情页复用现役实现（R-3）
- 重构：5 个后端适配器注入 BackendRegistry 共享 OkHttp 连接池/Dispatcher，close() 不再 shutdown 全局线程池——连续切换后端不再累积线程池（R-6，用户可感知：切后端后封面/播放请求更稳定）
- 重构：人声分离 SeparationMode 上提 player 层 + 新增 VocalSeparationController（R-5）
- 重构：AppPreferences 按领域拆分为 12 个子 Prefs 门面（server/player/lyrics/network/baidu/download/weather/visualizer/history/playlist/queue/languagePrefs/backup），全库调用点迁移至 prefs.<domain>.xxx（R-4）
- 重构：Subsonic 系公共层下沉 SubsonicRestClient（Navidrome 全量委托；Subsonic 因 URL 格式差异保留自有 buildRestUrl）（R-10 部分）
- 重构：ExportCoordinator/AccompanimentCache 统一注入 applicationScope；歌词持久缓存写互斥（F-4/F-5）

### Fixed
- 安全：五个后端适配器（Jellyfin/Navidrome/Subsonic/道理鱼/飞牛）的 w/e 级错误日志统一经 UrlSanitizer 脱敏，release 包 logcat 不再泄露 api_key / Subsonic 密码令牌 / 登录 token（重构方案 F-1）；新增 UrlSanitizerTest 单测 8 项
- 安全：天气 OpenWeatherMap API Key 改 AES-GCM 加密存储，旧明文值一次性迁移清除（F-7）
- ANR：消除主线程 runBlocking——语言设置改 SharedPreferences 双写镜像，8 个 provider 键改 @Volatile 内存镜像（设置改动 ≤1s 生效，R-7 + F-3）；新增 ProviderMirrorTest 6 项
- 性能：AppRoot 顶层 progress/duration 收集下沉至播放页分支，播放期间不再每秒驱动全树重组（F-2）
- 修复：TV 双网卡（有线+无线）场景扫码 IP 可能取错网段——NetworkUtils 改接口优先级排序，有线/在用接口优先（F-6）
> 说明：本轮按 docs/codebase-refactoring-plan-2026-09.md v1.4 实施 R-1~R-7、R-9、R-10（Subsonic 公共层）与 F-1~F-7；R-8（Hilt 迁移）与 R-10 的 Jellyfin 域拆分经所有者确认定案不实施（兼容性风险 / 实例状态耦合深）。

## [v2.27.0] - 2026-09-09

### Fixed
- 安全：移除全部 6 处 trust-all TLS 配置，恢复系统默认证书校验（主播放器数据源 / Coil 图片 / 百度 OAuth / Meting / 道理鱼 / 飞牛 / B 站 MV）
- 安全：清除连接页硬编码的开发者 NAS 账号密码（该凭据已随历史提交公开，需轮换）
- 修复：高质量人声分离临时目录改用应用私有 cacheDir，原 /data/local/tmp 不可写导致 HQ 分离下载必败
- 修复：扫码传模型服务器改用独立端口 18083，不再与手机遥控服务器冲突
- 修复：网盘文件索引原子写盘（临时文件 + rename），写盘中断不再丢失全库索引
- 修复：百度 token 被服务端判定失效时自动强制刷新重试一次（errno -6/31045）
- 修复：网盘搜索补传 start 分页参数；listAll 索引游标停滞保护；清库同步清目录倒排
- 修复：Range 请求仅接受 206，防服务器忽略 Range 时整文件读入内存
- 修复：退出确认 disconnect 限时 1.5s，消除主线程 ANR 风险
- 修复：Android 13+ 通知运行时权限请求（媒体通知此前可能不显示）
- 修复：「全部加入队列」改为只增不删，不再反向移除已入队歌曲
- 修复：Activity 重建（旋转/主题切换）不再误杀后台播放（onDestroy 仅 isFinishing 时清理）
- 修复：Meting API 的 id 参数补 URL 编码；BaiduPanApi / 道理鱼日志 token 脱敏；网盘歌词侧车 dlink 补 access_token
- 性能：频谱 20fps 状态流下沉至播放页收集；设置页组合内同步读改 Flow 订阅
- 杂项：CI 增加单元测试步骤；EncodingUtils 空 catch 补日志；AGENTS.md 同步最新架构事实

## [v2.26.41] - 2026-09-09

多源融合大改版，终于告一段落了！

### Fixed

- **播放页点击艺术家/歌名未跳转到搜索页**：`AppRoot.kt` 的 NowPlaying `onSearchArtist`/`onSearchSong` 接线错误地调用 `viewModel.searchNetworkSongs(keyword)`，该方法只更新独立的网络音乐搜索数据流 `_networkSearchResults`，既不导航也不设置曲库搜索关键词，导致点击后界面停留播放页、无任何跳转。修复：改为沿用已有的 `onNavigateToSearch` 模式（`selectLibraryTab(LibraryTab.SEARCH)` + `setLibrarySearchKeyword(keyword)` + `navigateTo(Screen.Library)`），跳转到曲库 SEARCH Tab 并触发跨源融合搜索（NAS + 网络 + 百度 + Jamendo + 本地）。

## [v2.26.40] - 2026-09-09

### Fixed

- **曲库搜索/发现/歌曲页面的加入歌单（`+`）无反应**：`AppRoot.kt` 的 `pickerSong` 状态被声明了两次——L128 顶层（供加入歌单弹窗 `PlaylistPickerDialog` 读取）与 L454 的 `Screen.Library` 分支内（遮蔽了顶层）。曲库页 `onAddToPlaylist = { song -> pickerSong = song }` 设置的是 L454 的遮蔽变量，而弹窗渲染读取的是 L128 的顶层变量，两者不是同一个 → 设置后弹窗读不到 → 永不弹出。修复：删除 L454 的冗余遮蔽变量，让曲库页的 `pickerSong` 引用顶层变量，与专辑/艺术家详情页、我的页、网盘页的加入歌单逻辑一致。

## [v2.26.39] - 2026-09-09

### Fixed

- **搜索页 / NowPlaying / 我的页的本地/下载歌曲无法收藏**：`AppRoot.kt` 三处收藏接线（NowPlaying / LibraryScreen / MineScreen）只判断 `song.isNetworkSong`，漏判 `song.isLocalSong`，导致本地/下载歌曲落入 NAS-only 的 `viewModel.toggleFavorite()`——NAS adapter 收到 `local_xxx` ID 必然失败，收藏静默无效。同时 `MainViewModel.toggleFavorite` 与 `toggleNetworkFavorite` 的 NAS 分支逻辑完全相同，属冗余函数。修复：统一改为 `viewModel.toggleNetworkFavorite(song)`（内部已按 `isNetworkSong || isLocalSong` 分流：NAS→adapter，其他→DataStore），删除冗余的 `toggleFavorite`。
- **专辑/艺术家详情页的本地歌曲收藏后爱心不亮**：`AppRoot.kt` 的 AlbumDetail / ArtistDetail 传入 `viewModel.favoriteIds`（NAS-only），未与 `networkFavoriteIds` 合并；而 `toggleNetworkFavorite` 已将本地收藏保存到 DataStore `networkFavoriteIds`。结果收藏实际已持久化，但 `favoriteIds` 集合不含该 ID，`isFavorited = song.id in favoriteIds` 恒为 false，爱心永不亮。修复：`MainViewModel.favoriteIds` 改为 `combine(_favoriteIds, networkFavoriteIds)` 的统一合并集合，AppRoot 各屏幕统一读取（LibraryScreen 移除 `+ networkFavoriteIds` 散点拼接），NowPlaying 的 `isFavorite` 显示同步修正。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **收藏架构收敛为单一路径**：NAS 歌曲走服务端 adapter，网络/本地/下载歌曲走本机 DataStore `NetworkFavoriteItem`；UI 侧统一订阅合并后的 `favoriteIds`，不再在各屏幕自行按歌曲类型分流判断。

## [v2.26.38] - 2026-09-08

### Fixed

- **下载歌曲封面/歌词永远不内嵌**：`SongDownloadManager.singleAttempt()` 中 `tagWriter.embed()` 传入的是临时文件 `p.tmpFile`，其扩展名为 `.part`，而 `MediaTagWriter.supportsEmbedding()` 检查 `file.extension` 是否在 `EMBEDDABLE` 集合中——`.part` 不在其中，导致 `embed()` 第一行就返回 false，封面和歌词永远走旁路 `.jpg`/`.lrc` 文件。修复：将原子 rename 移到 embed 调用之前，对 `p.finalFile`（有正确 `.mp3`/`.flac` 扩展名）执行内嵌。
- **已下载歌曲在搜索/列表中不使用本地封面和歌词**：`UnifiedSongRow` 直接用 `song.coverUrl`（后端 URL），不检查本地旁路封面；`getCoverCandidates()` 不查本地下载记录；`loadLyricsForCurrentSong()` 不读本地 `.lrc` 文件。修复：`DownloadState.Completed` 扩展携带 `coverPath`/`lyricPath`/`embedded`；`UnifiedSongRow` 和 `getCoverCandidates()` 优先使用本地 `file://` 封面；`loadLyricsForCurrentSong()` 优先通过 `LocalLyricsProvider.getLyricsFromPath()` 读取本地歌词。
- **扫码传输备份上传失败**：`BackupTransferServer` 的 HTML 模板中，`strings.xml` 的 `html_backup_confirm_restore` 含 `\n` 换行符，经 `getString()` 解析后变为真实换行，插入 JS 单引号字符串导致语法错误，整个 `<script>` 块不执行，页面 JS 全部失效（无 `GET /api/list`、无 alert、无 POST）。改用 `gson.toJson()` 序列化 STR 对象，自动转义特殊字符。同时前端 `readAsText` 改为 `readAsArrayBuffer` + `Blob` 避免文本编码问题。
- **扫码传输备份导入后中文乱码**：`handleUpload` 使用 `session.parseBody()` 读取 POST body，NanoHTTPD 内部用 `Charset.defaultCharset()` 解码，MIUI ROM 上默认字符集非 UTF-8，导致中文 UTF-8 字节被按错误字符集解码为乱码后写入备份文件。改为直接从 `session.inputStream` 读取原始字节并显式用 `Charsets.UTF_8` 解码，同时处理 UTF-8 BOM。
- **下载按钮点击无反应**：`SongDownloadManager.loop()` 中 `manualQueue.tryReceive() ?: autoQueue.receive()` 有竞态条件——启动时 `tryReceive()` 返回 null 后阻塞在 `autoQueue.receive()` 上，此后不再检查 `manualQueue`，导致手动下载入队后永远不被消费。改用 `select` 同时监听两个 Channel，按 clause 顺序保证手动优先，两个队列都能正常消费。自动下载走同一个 `loop()`，修复同时覆盖。
- **已下载歌曲以 LOCAL 源出现时封面不显示、歌词走网络**：下载完成后歌曲经 MediaStore 扫描以 LOCAL 源重新出现在搜索结果中，其 `downloadKey`（`local_local_xxx`）与下载记录 key（`ntwk_meting_xxx`）不匹配，`downloadStates` 查询返回 null，本地封面/歌词分支全被跳过。同时 LOCAL 源歌曲的 `song.path` 是 URL 编码的（如 `%E8%B5%B5%E4%BC%A0`），而 `EmbeddedCoverExtractor` 和 `LocalLyricsProvider` 只做 `removePrefix("file://")` 未做 URL 解码，`File(encodedPath).exists()` 返回 false。修复：`UnifiedSongRow`、`loadLyricsForCurrentSong`、`getCoverCandidates` 增加 `song.isLocalSong` 兜底，`downloadStates` 匹配不到时直接从 `song.path` 提取内嵌封面/歌词；三处路径解析均加 `android.net.Uri.decode()` 解码 percent-encoding。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **统一所有扫码页面 STR 序列化方式**：`RemoteControlHtml.kt`（遥控页）和 `ModelTransferServer.kt`（模型上传页）的 STR 对象从手工 `replace("'", "\\'")` / `esc()` 拼接改为 `gson.toJson()` 序列化，与备份页保持一致。防御性修复：未来在 `strings.xml` 中添加 `\n` 等特殊字符时不再导致 JS 语法错误使整个页面脚本失效。`LocalInputServer.kt`（文字输入页）纯硬编码 HTML，无需修改。

## [v2.26.37] - 2026-09-08

### Fixed

- **艺术家/专辑封面大量缺失**：`JellyfinAdapter.getArtists()` 和 `getAlbums()` 中 `buildCoverUrl(id, imageTag) ?: getCoverUrl(id)` 的 `getCoverUrl(id)` 对无图艺术家也返回 URL（Jellyfin 返回 404），导致 `ArtistCoverResolver` 因 `coverUrl != null` 跳过所有缺图艺术家，在线源和歌曲封面兜底从未被触发。改为 Primary tag → Backdrop → null，无图时 `coverUrl = null`，交给 `ArtistCoverResolver` 处理。
- **P4 歌曲封面兜底性能优化**：`ArtistCoverResolver.resolveCovers()` 中 `findArtistSongCover()` 对每个艺术家遍历全量歌曲（O(artists × songs)），3 万首歌 + 5000 艺术家时极慢。改为预构建 `normalizeKey(artistName) → coverUrl` 索引（O(songs) 一次构建），P4 查找降为 O(1)。
- **在线源耗尽后无法补充封面**：`artistCoverMaxAttempts = 2` 耗尽后不再解析，但 NAS 全量歌曲需 12-13 分钟才加载完，P4 歌曲封面兜底在歌曲未加载完时无效。新增 `resolveSongCoversOnly()` 方法，在线源尝试次数耗尽后，每次歌曲库更新仍重新用 P4 匹配新加载的歌曲封面，不消耗在线源尝试次数。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **封面多级回退链完善**：完整链路为 Jellyfin Primary tag → Jellyfin Backdrop → 网易云 → 酜狗 → iTunes → 歌曲封面(P4) → P4-only 补充通道（歌曲库更新后持续重试） → 首字母占位(UI)。

## [v2.26.36] - 2026-09-08

### Fixed

- **搜索艺术家名无法返回对应歌曲**：Jellyfin 的 `SearchTerm` 参数只搜索 `Name`/`SortName` 字段（歌曲标题），不搜索 `Artists` 字段。搜"赵传"时只能搜到歌名含"赵传"的歌，艺术家为"赵传"的歌不出现。`JellyfinAdapter.searchSongs()` 增加按 `Artists=` 参数并行查询，与 `SearchTerm=` 结果合并去重。
- **首页一直显示"加载中"**：`loadLibrary()` 中 `albumsDeferred.await()` 阻塞整个协程，大曲库（3 万+首）下 `getAlbums()` 每页 1000 条耗时 30-40 秒，多页合计数分钟，`_isLibraryLoading` 一直为 `true`。改为所有加载任务（专辑/流派/收藏/艺术家/歌曲/随心听）独立 `launch` 异步执行，首页立即就绪。
- **艺术家详情页无法列出 NAS 歌曲**：`loadArtists()` 在 `await()` 之后才执行，`_rawArtistList` 为空导致 NAS 分支跳过。随 `loadLibrary` 异步化修复一并解决。
- **搜索 NAS 结果被超时截断**：`SearchAggregator` 的 `NAS_TIMEOUT` 从 5s 增至 15s，防止大曲库搜索被过早截断返回空结果。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **全量歌曲分页大小**：`pageSize` 从 200 增至 500，3 万首歌的 HTTP 请求次数从约 150 次降至约 60 次，减少网络开销。

## [v2.26.35] - 2026-09-08

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **百度索引全量加载瓶颈消除**：排查所有调用 `baiduIndexCache.allSongs()`（38000+ Song 对象全量创建后再客户端过滤）的热路径，替换为 entry 级预过滤方法：
  - 新增 `BaiduFileIndexCache.songsByAlbumName()` / `searchSongs()`：在 raw entry 级别过滤，只对匹配项创建 Song 对象。
  - `loadAlbumSongs` / `playAlbumMultiSource`：`filterSongsByAlbumName(allSongs())` → `songsByAlbumName()`，38000→~20。
  - `searchSongsOnServer` 拼音搜索：`allSongs()` → `searchSongs(query, pinyinMatch=true)`。
  - `updateMergedData`：`allSongs()` 从 5 次重复调用收敛为 1 次加载 + 参数传递。

### Fixed

- **发现页 `searchByDirectory` 性能**：双轮 O(N) 全量扫描改为预建目录索引 O(D) 查找（D=唯一目录数，典型 100-500），目录命中后直接按 key 取值。
- **百度搜索本地索引+API 串行**：`BaiduNetdiskService.searchInternal` 中本地索引和 API 从串行改为 `async` 并行，总耗时从 `本地索引 + API` 降为 `max(本地索引, API)`。
- **歌曲列表两列布局歌名被压缩**：歌曲条目信息多（标题+艺术家+专辑+时长+操作按钮），TV 两列布局下歌名被压缩不可读。`songGridColumns()` 从 TV=2列/手机=1列 改为始终 1 列，影响曲库/发现/搜索/网盘页面。"我的"页面不受影响（其 TV/手机版式差异保持不变）。

## [v2.26.34] - 2026-09-08

### Fixed

- **网络歌词搜索失败**：酷狗搜索端点 `mobilecdn.kugou.com` 的 HTTPS 证书 hostname 不匹配，OkHttp 拒绝连接导致搜索结果为空。改为使用 HTTP（应用已开启 `usesCleartextTraffic`），歌词下载端点 `krcs.kugou.com` 保持 HTTPS（证书正常）。
- **网易云歌词搜索失败**：`/api/search/get/web`（GET）已废弃返回 HTTP 405，改为 POST `/api/search/get`（`FormBody` 传参）。
- **LyricsManager 构造参数错误**：`kugouLrcUrl` 误用 `kugouBaseUrl` 赋值，导致自定义酷狗搜索端点时歌词下载端点也被错误覆盖。修正为独立使用 `DEFAULT_KUGOU_LRC_URL`。

### Added

- **无歌词时自动搜索网络歌词**：歌曲无内嵌歌词和后端歌词时，自动触发网络歌词搜索并显示，歌词来源正确标记为 `NETWORK`。自动搜索到的网络歌词暂存到 `pendingNetworkLyrics`，播放完成后持久化缓存。
- **无封面时自动搜索网络封面**：所有源歌曲（NAS/本地/百度网盘/网络音乐）在无封面时自动调用 `networkMusicManager.searchCoverUrl` 搜索网络封面。切歌时重置 `_networkCoverUrl` 避免残留。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **艺术家详情页加载性能优化**：`loadArtistSongs` 五个音乐源从串行改为真正并行（`coroutineScope` + `async`），慢源（NAS API、Meting 搜索）不再互相阻塞。
- **百度索引过滤优化**：新增 `BaiduFileIndexCache.songsByArtist()`，在 raw entry 上直接按艺术家过滤，只对匹配项创建 Song 对象（从 38837 个降至 ~12 个），避免全量 `allSongs()` + 客户端 filter 的开销。
- **ArtistSplitter 新增 `containsArtistWithKey`**：接受预计算的归一化 key，避免在循环内对同一艺术家名重复执行 NFKC 归一化 + 正则拆分。
- `getCoverCandidates` 网络歌曲也纳入 `_networkCoverUrl` 候选（原先只对 NAS 歌曲）。
- 实测赵传详情页加载从 ~53s 降至 ~9s。

---

## [v2.26.33] - 2026-09-08

### Fixed

- **艺术家详情页歌曲列表过一会变空**：`loadArtistSongs` 在加载开始时立即删除 `_artistDetailSongsCache` 中对应 key，2-3 秒后才写入新数据；当该方法被二次触发时（TV 遥控器焦点变化或 Compose 重组），缓存被清空导致 UI 立即显示空列表。改为不在加载开始时清空缓存，旧数据保持显示直到新数据就绪后直接覆盖。
  - `_artistSongsMap` 的清理保留（仅被 LibraryScreen 使用，ArtistDetail 页时该屏幕未组合，不会看到空窗）
- **百度网盘 TV 端登录 errno=-6 根因修复**：`CryptoUtils.encrypt()` 在 AES-GCM 加密时未显式传入 IV，部分 Android TV ROM 的 `cipher.iv` 返回空数组导致密文缺少 IV 前缀，解密失败后密文被当作 token 发给百度，百度返回 `errno=-6`。改为用 `SecureRandom` 显式生成 12 字节 IV 并通过 `GCMParameterSpec` 传入。
  - 修复后 TV 端日志确认 token 格式正确（`126.xxx` 前缀），`listAllAudioPaged` 成功返回 31,251 个文件
- **ERRNO_MAP 对照百度官方文档全面修正**：`-1` 改为"权益已过期"，`-6` 去除混入的 20013 语义，移除不在官方表中的 `-111`/`-118`
- **本地错误码与百度 errno 分离**：新增 `LOCAL_ERRNO_NO_TOKEN=-100` / `LOCAL_ERRNO_NETWORK=-101`，本地生成的错误不再伪装为百度 `-6`
- **scope 缺少 netdisk 时阻断保存**：`pollDeviceToken()` 中检测到授权 scope 不含 `netdisk` 时返回 Failed 而非仅警告
- **createDir 移除未定义参数**：移除百度文档未定义的 `size` 参数
- **BaiduAuthDialog 错误文案区分**：Failed 状态根据实际失败原因动态显示（授权范围不足/用户拒绝/超时/授权失败）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- `getBaiduTokensSync()` 增加解密后 token 前缀诊断日志
- `CryptoUtils.tryDecrypt()` / `decrypt()` 增加解密失败诊断日志（text prefix、length）
- `BaiduPanApi.executeWithErrno()` 增加完整 response body 日志

---

## [v2.26.32] - 2026-09-06

### Added

- **歌曲离线下载**：搜索可下载源歌曲后点击下载按钮，文件保存到 `Music/<歌手>/<专辑>/` 目录，支持 MP3/FLAC/M4A/OGG/WAV 格式，重启后仍可离线播放。
  - 下载后端基础设施：`DownloadDatabase`（独立 Room 数据库）、`DownloadPathBuilder`（目录构造）、`DownloadRepository`（索引 CRUD）、`SongDownloadManager`（执行器）、`StorageGuard`（空间守护）
  - ID3/FLAC 内嵌元数据：自动写入封面（artist.jpg / cover.jpg）和歌词（MP3 USLT / FLAC VORBIS_COMMENT）
  - 去重机制：`dedupeKey`（title+artist 规范化）防止同一首歌重复下载
  - 空间检测：双 StatFs 取 min、100MB 预留、下载中空间跌破阈值自动中断并清理临时文件

- **自动下载**：设置页开启后，播放歌曲 ≥5s 自动触发下载，配额限制（默认50首），达到上限自动停止并提示。
  - `AutoDownloadController`：播放状态监听、配额管理、5分钟提示节流
  - 仅自动下载受限，手动下载不受限制

- **下载状态 UI**：歌曲行内显示下载按钮，支持 5 种状态：
  - Idle（⬇）、Queued（⋯）、Downloading（⇣ 进度%）、Completed（✓）、Failed（✕）
  - 已下载歌曲点击删除弹出二次确认对话框

- **设置页下载管理**：DOWNLOAD 区块显示自动下载开关、最大下载数量滑块、存储空间、已下载统计、清空下载按钮

- **导出到外接设备**：设置页新增"导出到外接设备"入口，支持导出到 U 盘/SD 卡。
  - SAF（Storage Access Framework）优先 + 应用专属目录降级（TV 兼容性）
  - `ExportCoordinator`：SAF 授权、设备探测、导出编排
  - `SongExporter`：字节复制、增量过滤（已导出不重复）、可取消/可续跑
  - 导出结构：`NASMusic/<歌手>/<专辑>/`（含音频、封面、歌词）
  - 导出状态机：Idle → Preparing → Running → Completed/Failed/Cancelled
  - SAF 持久化授权：重启 App 后无需重新授权

- **单元测试**：新增 38 个测试用例
  - `DownloadPathBuilderTest`（25 个）：覆盖 sanitize()、extOf()、build()、cleanupEmptyDirs()
  - `ExportStateTest`（13 个）：覆盖状态创建、属性、错误枚举

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **UnifiedSongRow 重构**：重构为 16 个调用点，新增 `SongRowModeRow` 模式支持下载按钮，歌曲行操作按钮行内排列

### Fixed

- **下载数据库独立**：`DownloadDatabase` 独立于 `LocalMusicDatabase`（避免 destructive migration 丢下载记录）
- **SAF TV 兼容性**：探测 TV 桩实现（`com.google.android.tv.frameworkpackagestubs`），无 SAF 时自动降级到应用专属目录
- **导出空间检测**：每 50MB 复检目标设备空间，跌破 100MB 预留自动中止

## [v2.26.31] - 2026-09-05

### Added

- **手机端媒体播放功能**：实现 `docs/phone-media-display-plan.md` 阶段1（P0），解决蓝牙车机无歌曲信息、切后台停歌、锁屏无控制键三大问题。
  - `PlayerManager.buildMediaItem()`：播放歌曲时填充完整 `MediaMetadata`（title/artist/album/artworkUri/trackNumber/year/genre），蓝牙 AVRCP 自动读取元数据。
  - `CoilBitmapLoader`：实现 `MediaSession.BitmapLoader`，通过 Coil 加载封面（复用百度 dlink UA 拦截器）。
  - `PlaybackService`：注入 `CoilBitmapLoader`，MediaSession 自动驱动系统锁屏/SMSC/蓝牙/厂商实况窗。
  - `onTaskRemoved`：播放中移除任务栏 → 继续播放；已暂停 → 停止服务（与主流音乐 App 一致）。
  - `BatteryOptimizationHelper`：首次播放检测电池优化白名单，未加入时引导用户加入（解决厂商省电杀后台）。
  - `MediaLibraryTree`：为 Android Auto/Wear OS 实现媒体浏览树（`onGetLibraryRoot`/`onGetItem`/`onGetChildren`），暴露当前播放队列为可浏览媒体树。

### Fixed

- **锁屏媒体按钮不响应**：`buildMediaButtonPendingIntent` 从 `PendingIntent.getBroadcast` 改为 `PendingIntent.getService`，`MediaLibraryService.onStartCommand` 自动路由到 `MediaSession`。
- **通知栏截断文字**：`contentText` 从"已暂停/正在播放"改为显示歌手名，播放状态由 MediaStyle 图标隐含表示。
- **PlaybackService.onTaskRemoved 切后台停歌**：原实现直接 `stopSelf()` 导致服务销毁，改为播放中保留服务。

## [v2.26.30] - 2026-09-05

### Added

- **专辑/艺术家封面持久化**：新增 `CoverUrlPersistentCache`（`app filesDir/cover_url_cache.json`，JSON Map + LRU 10000）。专辑 key `album:{id}`、艺术家 key `artist:{id}`，只存稳定 HTTP 封面 URL（不存动态 dlink / data URI）。
  - 写入：`resolveAlbumCoversAsync` / `resolveArtistCoversAsync` 解析到 HTTP 封面时同步写持久缓存。
  - 读取：App 启动后 `resolvedAlbumCovers` / `resolvedArtistCovers` 懒加载从持久缓存预填充，已有封面不再重复网络解析（配合 maxAttempts 收敛护栏）。
  - 百度专辑封面原本已通过 `baiduIndexCache`（fsId→url）持久化，统一缓存对其双写无害。

### Fixed

- **艺术家详情页左侧封面不显示**：`AppRoot` 查 `selectedArtist` 用的是原始 `_artists`（NAS 未合并列表），其 coverUrl 未应用 `resolvedArtistCovers` 解析缓存，且百度/本地艺术家不在其中。改用 `viewModel.mergedArtists`（已应用封面缓存）查找，详情页左侧封面正常显示。

## [v2.26.29] - 2026-09-05

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **艺术家封面来源优先级链**：调整为 网易云音乐 → 酷狗音乐 → iTunes → 该艺术家歌曲封面 → 首字母占位。
  - `ArtistCoverResolver` 新增 P1 网易云（`music.163.com/api/search/get/web?type=100` 取 `result.artists[0].img1v1Url`）和 P2 酷狗（`msearch.kugou.com/api/v3/search/singer` 取 `data.info[0].img`），iTunes 降为 P3。
  - 新增 P4 `findArtistSongCover`：在线源全失败时，从全量歌曲（NAS+本地+百度）找该艺术家第一首有封面的歌曲封面兜底。
  - `resolveCovers` 增加 `allSongs` 参数，`resolveArtistCoversAsync` 传入全量歌曲。
- **百度艺术家改回走在线补全**：`MusicMerger.buildBaiduArtists` 由 `useSongCover=true` 改回 `false`，保证百度艺术家优先尝试网易云/酷狗/iTunes 拿歌手本人照片，拿不到才由 P4 歌曲封面兜底。`buildLocalArtists` 保留 `useSongCover=true`（本地曲库大，全量在线请求会限流，直接用歌曲封面）。
- **移除已下线的百度音乐 API**：`musicapi.taihe.com` DNS 解析失败，已从 `ArtistCoverResolver` 移除 P2 百度音乐搜索。

## [v2.26.28] - 2026-09-05

### Fixed

- **百度网盘艺术家封面不解析**：艺术家封面解析 `resolveArtistCoversAsync()` 此前只在 `loadArtists()`（NAS 连接）末尾触发，而百度艺术家由 `updateMergedData()` 从索引聚合生成，仅连百度网盘时其封面永远空白。现将 `resolveArtistCoversAsync()` 移到 `updateMergedData()` 末尾与专辑封面解析并列，并改读合并后的 `_mergedArtists`（NAS + 本地 + 百度），无 NAS 时百度艺术家也能被补全。
- **本地艺术家封面恒空**：`MusicMerger.buildLocalArtists` 生成的艺术家 coverUrl 恒为 null 且被 `ArtistCoverResolver` 跳过。现 `buildArtistsFromSongs` 新增 `useSongCover` 参数，本地艺术家取名下第一首有封面的歌曲封面兜底（侧车 cover.jpg / 内嵌 ID3 APIC）。百度艺术家同样用歌曲封面兜底（APIC 提取后即有封面）。
- **移除已下线的百度音乐 API**：`musicapi.taihe.com` DNS 解析失败已不可用，从 `ArtistCoverResolver` 移除 P2 百度音乐搜索兜底，避免对 4 万+ 艺术家发无效请求触发限流。在线补全仅保留 iTunes。
- **艺术家封面解析收敛护栏**：为 `resolveArtistCoversAsync` 新增 `artistCoverAttempts` / `artistCoverMaxAttempts`，与专辑封面解析一致，切断 `updateMergedData ↔ resolveArtistCoversAsync` 无成果时的重复请求循环。

## [v2.26.27] - 2026-09-05

### Fixed

- **百度网盘索引只扫到约 4493 首（实际 4 万+）**：`BaiduPanApi.listAllAudioPaged` 误用 `FILE_BASE`（`xpan/file`）端点调用 `listall`，百度静默降级为 `list` 语义不递归，只拿到根目录第一层文件。修正为 `MULTIMEDIA_BASE`（`xpan/multimedia`），与百度官方文档 `listall` 接口规范一致，递归分页（`has_more`/`cursor`）正常工作。
- **索引后不提取封面**：`rebuildBaiduIndex` 此前误以为 `listall+web=1` 返回的 `thumbs` 能覆盖音频封面，实际百度只为图片/视频生成缩略图，音频文件 `coverThumb` 几乎全为 null，`startApicExtraction()` 从未被调用。现扫描完成后统计 `coverUrl == null` 的音频条目，如有则自动触发 APIC 后台提取内嵌 ID3 封面。

## [v2.26.26] - 2026-09-05

### Fixed

- **百度网盘目录不存在不再误报"授权失败"**：`onApiError` 回调仅对认证错误（errno=-6）设 Failed，其他错误（如目录不存在 errno=-9）仅记日志，不再翻转连接状态
- **新增 DirMissing 状态**：`BaiduConnectionState.DirMissing` — 已登录但配置的音乐根目录不存在，设置页和网盘页显示醒目提示引导用户重新设置目录
- **验证流程两步化**：`verifyBaiduTokenAsync()` 先验 APP_DIR（自动创建缺失的沙盒目录），再 `checkMusicRootDirAfterVerify()` 检查用户音乐根目录是否存在
- **设置目录后自动恢复**：`DirMissing` 状态下修改音乐根目录后自动重新验证，通过则恢复 LoggedIn
- **百度网盘旧配置自动纠正**：`AppPreferences.getBaiduMusicRootDirSync()` / `getBaiduMvDirSync()` 读取时检测路径是否在沙盒 `/apps/NASMusicTV` 下，不在则自动纠正并回写
- **设置页目录默认描述更新**：音乐根目录和 MV 目录的默认描述从旧路径 `/音乐` 改为百度沙盒路径 `/apps/NASMusicTV`（中/英双语）

## [v2.26.25] - 2026-09-05

### Fixed

- **百度网盘设置页目录默认描述更新**：音乐根目录和 MV 目录的默认描述从旧路径 `/音乐` 改为百度沙盒路径 `/apps/NASMusicTV`，与实际权限和代码默认值一致（中/英双语）
- **百度网盘旧配置自动纠正**：`AppPreferences.getBaiduMusicRootDirSync()` / `getBaiduMvDirSync()` 读取时检测路径是否在沙盒 `/apps/NASMusicTV` 下，不在则自动纠正为沙盒目录并回写，解决 DataStore 中残留旧路径 `/音乐` 导致设置页仍显示无权限路径的问题

## [v2.26.24] - 2026-09-05

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **百度网盘封面提取改为扫描后后台渐进执行**：扫描阶段（`scanDirTree`/`fullScan`）恢复为纯目录遍历，不再提取 APIC 封面，扫描速度提升数倍。扫描完成后自动启动 `extractApicInBackground()`，并发数 5、每批 20 条写入索引，避免百度限流。设置页新增封面提取进度条（Box 进度条 + 百分比文字），提取完成后进度条自动消失。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 102 → 103，versionName 2.26.23 → 2.26.24**

## [v2.26.23] - 2026-09-04

### Fixed

- **`path.hashCode()` 作主键碰撞丢 USB 歌（复审 P2，方案 A）**：USB / 文件遍历扫描（`MusicScanner.scanFile`）原用 `file.absolutePath.hashCode().toLong()` 作 `LocalSongEntity.mediaStoreId` 主键。32-bit 哈希在约 7.7 万文件时碰撞概率≈50%，`@Insert(REPLACE)` 下碰撞条目互相覆盖、静默丢歌。改为 `HashUtils.stablePathHash64()`（FNV-1a 64-bit），分布均匀、碰撞概率可忽略，且对同一 path 跨进程/启动/设备完全确定。因 id 取值整体变化，同步将 `LocalMusicDatabase` 版本 1→2（`fallbackToDestructiveMigration(true)` 已配，升级即破坏性重建），避免旧 32-bit id 行残留造成重复条目。MediaStore 通道（`scanAllMusic`）始终用真实 MediaStore ID，不受影响。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 101 → 102，versionName 2.26.22 → 2.26.23**

## [v2.26.22] - 2026-09-04

### Fixed

- **LocalMusicDatabase 无破坏性迁移（方案 A）**：`LocalMusicDatabase` 原仅配 `.fallbackToDestructiveMigrationOnDowngrade()`，即 schema 版本**升级**时不会触发破坏性重建、且无任何 `Migration` 实现——一旦后续给实体加字段/索引导致 `version` 提升，Room 会抛 `IllegalStateException: A migration from 1 to 2 was required but not found`，本地音乐库直接崩溃不可用。改为 `.fallbackToDestructiveMigration(true)`（含 `dropAllTables` 的重载，避免 no-arg 版本在新 Room 中的 deprecation 警告），升级与降级均走破坏性重建。本地索引可由重扫重建，无需维护 `Migration` 类，消除版本演进时的迁移代码负担与崩溃风险。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 100 → 101，versionName 2.26.21 → 2.26.22**

## [v2.26.21] - 2026-09-04

### Fixed

- **B20 续：播放整张合并专辑仍只取 NAS 歌（同根因的播放路径）**：`AppRoot` 的 `onPlayAlbum`（HomeScreen / LibraryScreen）原用 `songs.filter { it.albumId == album.id }`，合并专辑 `id` 是 NAS id，本地/百度同名歌整张播放时仍被漏掉。改为调用 `MainViewModel.playAlbumMultiSource(album)`——复用 `loadAlbumSongs` 的 `filterSongsByAlbumName` 多源取数（NAS → `adapter.getAlbumSongs`；本地/百度 → 按专辑名匹配），按 `title|artist|durationMs` 跨源去重后整张播放。方案 B 对 B20 的覆盖现已完整（详情页显示 + 整张播放）。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 99 → 100，versionName 2.26.20 → 2.26.21**

## [v2.26.20] - 2026-09-04

### Fixed

- **B20：专辑合并后「只留 NAS id」导致详情页丢本地歌（方案 B，从根上解决）**：`MusicMerger.mergeAlbums` 同名碰撞时保留 NAS 专辑的 `id`，本地/百度同名专辑的 `id` 被丢弃，而 `MainViewModel.loadAlbumSongs` 按 `id` 前缀路由——合并专辑 `id` 是 NAS id，点进去只返回 NAS 歌曲，本地/百度同名歌在详情页不可见、不可播。现给 `Album` 增加 `sourceIds: List<String>` 字段，`mergeAlbums` 在碰撞/新建时把 NAS、本地、百度的原始来源 id 全部收集进 `sourceIds`；`loadAlbumSongs` 改为读取 `album.sourceIds`（单源 album 回退到 `albumId`，向后兼容），对每个来源分别取数——NAS 走 `adapter.getAlbumSongs`、本地/百度按专辑名匹配——再拼接并跨源去重（按 `title|artist|durationMs`）后写入缓存。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 98 → 99，versionName 2.26.19 → 2.26.20**

## [v2.26.19] - 2026-09-04

### Fixed

- **艺术家同名出现多个卡片（用户反馈·古天乐）**：ARTISTS Tab 搜索时，艺术家块来自「多源搜索结果按 `it.artist` 原样分组」，
  未做合唱拆分、去重键也只是 `name.lowercase()`（不 trim、不做全角归一化）。于是 `古天乐` 与 `古天乐 `（尾部空白）
  被判成两个 key，显示成两块同名卡片、点进去是同一批歌。现改为：搜索结果走 `MusicMerger.buildArtistsFromSongs`
  先按 `ArtistSplitter` 拆分，去重键统一为 `ArtistSplitter.normalizeKey`（NFKC 全角转半角 + trim + 折叠内部空白 + 小写）。
- **合唱艺术家被当成独立艺术家（用户反馈·古天乐/萱萱）**：同上，搜索结果未按分隔符拆分，`古天乐/萱萱` 整串成为一个
  艺术家块；详情页用整串去匹配 `song.artist`，任何歌都匹配不上，详情页一片空白。现拆分后两位艺术家各自成块、
  合唱歌同时计入两人；详情页 `loadArtistSongs` 另加入「本地已加载歌曲（NAS 分页 + 本地设备 + 百度）按拆分名过滤」
  兜底，后端返回为空时也不再空白。
- **`buildLocalArtists` / `buildBaiduArtists` 去重键不一致**：本地与百度两路用 `name.lowercase().trim()`、
  与 NAS 路的归一化键不同源，`MusicMerger.mergeArtists` 两侧同理。三路现已统一走 `normalizeKey`，
  并把重复实现合并为 `MusicMerger.buildArtistsFromSongs(songs, idPrefix)`。

### Added

- `ArtistSplitter.normalizeKey(name)`：艺术家同名判定的唯一权威实现（NFKC + trim + 折叠空白 + 小写）。
- `ArtistSplitter.containsArtist(rawArtistField, artistName)`：替代原先 `artistName in ArtistSplitter.split(...)` 的裸串比较。
- 回归测试 `ArtistSplitterTest`（15 例）与 `MusicMergerTest`（4 例），覆盖合唱拆分与同名不同写法合并。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 97 → 98，versionName 2.26.18 → 2.26.19**

## [v2.26.16] - 2026-09-04

### Fixed

- **MusicScanner 递归无深度限制（P2）**：`scanPath` 用 `walkTopDown()` 递归整个目录树、无上限，深目录或符号链接循环会无限递归、主线程 IO 卡死。已加 `.maxDepth(8)` 防护。
- **Bilibili MV 标题解析每次编译正则（P2）**：`stripHtml` 每次调用都 `Regex("<[^>]+>")` 重新编译，已将正则提为 companion object 预编译常量 `HTML_TAG_REGEX`。

## [v2.26.18] - 2026-09-04

### Fixed

- **跨线程可变集合无同步（P2）**：`FeiniuAdapter.cookieStore`（`mutableMapOf`）被 OkHttp `CookieJar` 回调在 dispatcher 线程池并发读写，HashMap 非线程安全，并发 `put` 可能结构损坏；`NavidromeAdapter._favoriteIds`（`mutableSetOf`）在 `Dispatchers.IO` 的多个 suspend 函数里并发读写收藏状态。二者均改为 `java.util.Collections.synchronizedMap` / `synchronizedSet` 包装，零行为变更。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 96 → 97，versionName 2.26.17 → 2.26.18**

## [v2.26.17] - 2026-09-04

### Fixed

- **本地专辑 id 塌缩为 `local_album_0`（B20）**：`MusicMerger.buildLocalAlbums` 用 `id = "local_album_${first.albumId ?: first.id}"`，而本地歌曲 `albumId` 来自 MediaStore 且未知专辑恒为 `0L`（`albumId.toString()` 得字符串 `"0"`），`?:` 不触发，导致所有 `albumId=0` 的本地专辑共享同一 id，详情页 `albumSongsCache`（以 id 为键）互相覆盖。已改为基于专辑名（去重键）派生稳定唯一 id `local_album_<name>`。详情页 `loadAlbumSongs` 本就用 `_selectedAlbum.name` 反查，不受 id 格式影响。
- **RadioBrowser 播放上报失败静默（P2）**：`reportClick` 失败仅 `AppLog.w`，而 release 构建 `AppLog.w` 是 no-op，上报失败不可见。已改 `AppLog.e`，使失败在 release 也可观测。
- **拼音重复计算（P2 / O(N²) 列表复制·拼音重复计算）**：`PinyinUtils.toPinyin`/`toPinyinInitials` 是无缓存纯函数，LibraryScreen 过滤每次按键都对全量歌名/歌手重算，SearchAggregator 虽自建缓存但其它调用方仍裸调。已在 `PinyinUtils` 内加有界 LRU 缓存（上限 4096，syncedMap 线程安全），零行为变更、覆盖全部调用方。
- **Jellyfin 曲库计数 `Limit=0` 语义风险（P2）**：`getSongsTotalCount` 意图「只取 1 条拿 `TotalRecordCount`」，但 `Limit=0` 在 Jellyfin 表示「无限制返回全部」，会拉全量曲库。已改 `Limit=1` 澄清意图（返回值不变）。

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **versionCode 95 → 96，versionName 2.26.16 → 2.26.17**

## [v2.26.15] - 2026-09-04

### Fixed

- **频谱分析采样率硬编码 44100（P2）**：`SpectrumAnalyzer.processFft` 收到 Visualizer 回调的真实 `samplingRate`，但频率映射硬用常量 `SAMPLING_RATE=44100`，设备实际采样率非 44100（如 48000）时 `freqPerBin` 算错、频谱柱频率映射整体漂移。已改用回调真实采样率（异常时回退 44100）。

### Removed

- **冗余 `WAKE_LOCK` 权限声明（P2）**：全代码库无任何 `WakeLock`/`PowerManager` 引用，Media3 `MediaLibraryService` 自行管理唤醒锁，应用层声明属冗余，已删除。

## [v2.26.14] - 2026-09-04

### Fixed

- **seek 窗口内暂停/播放状态失真（P5）**：`onIsPlayingChanged` 在 `seekPending` 时直接 `return`，seek 期间（≤1 秒）的暂停/播放事件被吞，`_isPlaying` 不更新，播放按钮卡在错误状态直到下次回调。已改为 seek 期间仍同步 `_isPlaying`（纯状态记录），仅跳过进度轮询启停等有副作用的操作。

## [v2.26.13] - 2026-09-04

### Fixed

- **飞牛/道理鱼 getSongs 除零崩溃（B14）**：`getSongs(limit, offset)` 里 `(offset / limit)` 在 `limit <= 0` 时抛 `ArithmeticException`。已改为 `safeLimit = if (limit > 0) limit else PAGE_SIZE`，两个 adapter 同步修复。

## [v2.26.12] - 2026-09-04

### Fixed

- **Navidrome 封面 URL 每次重建 salt+token 致缓存失效（B11）**：`buildRestUrl` 每次调用都 `System.currentTimeMillis()` 生成新 salt，导致 `buildCoverUrl` 产出的封面 URL 每次都不同，Coil 缓存 key 失效、同一封面反复下载。已改为在 `initialize` 时固定 salt（与 Subsonic 一致），`buildRestUrl` 复用，封面/流 URL 稳定可缓存。

- **Navidrome/Subsonic 专辑列表硬上限 500 无分页（B10）**：`getAlbums` 硬编码 `size=500`，超过 500 张专辑的用户会丢失专辑。已改为按页循环拉取（每页 500，`offset` 递增），直到返回不足一页或达到安全上限（100 页 / 5 万张）。

## [v2.26.11] - 2026-09-04

### Fixed

- **Demucs 解码临时文件字节序不匹配导致分离输入即噪声（P9）**：`decodeAudioToTempFile` 用 `ByteBuffer.order(LITTLE_ENDIAN)` 写临时文件，但 `separate()` 读回用 `DataInputStream.readFloat()`（JVM 默认 BIG_ENDIAN），字节序相反导致读回的 float 全部错乱，人声分离输入即噪声。已统一为 BIG_ENDIAN 写入（与 `readFloat()` 一致）。

- **Demucs 解码逐样本分配 ByteBuffer + 无缓冲 IO 拖慢分离（P15）**：原实现每 2 个采样就 `ByteBuffer.allocate(8)` + 逐次 `fos.write`，全程无缓冲、频繁分配与系统调用。已改为复用 64KB 预分配缓冲批量写入（写满即 flush，结束冲刷余量）。

- **Demucs 解码器/抽取器异常路径泄漏（P8）**：`decodeAudioToTempFile` 的 `MediaCodec`/`MediaExtractor` 仅在正常路径 `release()`，catch 分支未释放，解码异常时资源泄漏。已将二者提升为函数级变量，`finally` 块统一 `stop()`/`release()`。

## [v2.26.10] - 2026-09-04

### Fixed

- **iTunes 封面搜索双重编码破坏中文搜索词（B16）**：`resolveItunesCover` 先 `query.replace(" ", "+")` 再用 `URLEncoder.encode`，`+` 被二次编码为 `%2B`，导致中文搜索词被破坏（iTunes 收到「字面加号」而非空格分隔的词），中文专辑封面命中率低。已移除多余的 `replace(" ", "+")`（`URLEncoder.encode` 本身就把空格编码为 `+`、中文编码为 `%XX`）。

- **iTunes 封面永远返回低清图（B16）**：`artworkUrl.replace("100x100", "600x600")` 的返回值被丢弃（未 `return`），实际永远返回 100×100 低清封面。已改为返回替换后的 600×600 高清图。

## [v2.26.9] - 2026-09-04

### Fixed

- **StorageMonitor 隐藏 API 反射崩溃（B15）**：`refreshStorageDevices()` 在 API 24–29 用 `volume.javaClass.getMethod("getPath").invoke(volume)` 反射取卷路径，无 try/catch；个别 ROM 隐藏该 API 时 `BroadcastReceiver.onReceive`（主线程）直接崩溃。已改为捕获异常跳过该卷。

- **伴奏/原唱文件中文/空格路径无法播放**：`switchToAccompaniment`/`switchToOriginal` 用 `Uri.parse("file://$path")` 构造本地文件 URI，遇中文/空格路径产生非法 URI、ExoPlayer 无法播放。改为 `Uri.fromFile(File(path))` 正确编码路径。

### Removed

- **删除 `checkPreSeparation` 死代码**：预分离触发函数无任何调用点（`AccompanimentCache.startPreSeparation` 也因此无外部调用），且注释「进度 > 50%」与实现「阈值 5%」矛盾。已删除该函数及其独占的 `PRE_SEPARATION_THRESHOLD` 常量。

## [v2.26.8] - 2026-09-04

### Fixed

- **NowPlaying 收藏星标不刷新（C7）**：NowPlaying 页 `isFavorite` 用 `isFavorite(song.id)` 直读 `_favoriteIds.value`、不建立订阅，点收藏后星标要到切歌/重组才刷新。现改为在 NowPlaying 分支 `collectAsState` 订阅 `favoriteIds` 与 `networkFavoriteIds`，收藏状态即时刷新。

- **伴唱 DSP 切歌后静默失效（P7）**：`SpectralMaskProcessor.reset()`（Media3 在切歌/重建 AudioSink 时调用）错误地 `enabled = false`，导致切歌后伴唱失效，但 `MainViewModel._vocalRemovalEnabled` 仍为 true、UI 与真实状态不一致且无法自愈。`enabled` 是用户意图状态、应由 `setEnabled()` 管理，已从 `reset()` 中移除该行，只重置内部音频状态。

- **Demucs ONNX 会话进程级泄漏（P10）**：`DemucsSeparator.release()`（关闭 166MB 模型的 `modelSession`/`ortEnv`）从未被任何代码调用，播放服务销毁后模型会话泄漏、多次启停内存持续增长。已在 `PlayerManager.release()` 中调用 `demucsSeparator?.release()`。

- **伴奏缓存无限增长写满存储（P14）**：`cleanupCache()`（LRU 淘汰，500MB/10 首上限）原先只在预分离路径触发；HQ 主路径分离 + `saveOriginalFile` 保存原唱文件永不淘汰。已在 `saveOriginalFile` 成功保存后触发 LRU 淘汰。

- **`refreshApiVersions()` 重复调用（C9）**：`connectToSavedServer` 成功路径连续调用两次（多一次网络请求），已删除重复调用。

## [v2.26.7] - 2026-09-04

### Fixed

- **快速切歌队列回滚（P4）**：`resolveAndPlayByIndex` 入口取旧队列快照，挂起解析后无条件 `playQueue(updatedQueue, targetIndex)` 整体重建播放列表；快速连续切歌时，在飞的多次解析最后响应者获胜但内容可能是最旧的旧快照，导致队列回滚。现引入 `resolveGeneration` 代数计数器：每次发起解析 +1，解析完成回写前比对代数，过期即丢弃；且回写改为基于「当前最新队列」更新目标歌曲的 streamUrl，而非入口旧快照。

- **K 歌伴奏/原唱切换后无法切歌（P6）**：`switchToAccompaniment`/`switchToOriginal` 用 `setMediaItem(newItem)` 把整个播放队列替换成单曲，开一次伴唱后 `seekToNextMediaItem()` 无目标、K 歌后无法切歌。改用 Media3 `replaceMediaItem(index, newItem)` 只替换当前索引的 item，保留队列其余部分与播放位置。

- **Subsonic 收藏整体失效（B9）**：`getFavorites()` 调 `getStarred2` 端点却解析 `subsonic-response > starred` 节点（应为 `starred2`），导致收藏列表恒空、`toggleFavorite` 永远判定「未收藏」只能加不能取消。改为优先解析 `starred2`、兼容 `starred`。

- **Subsonic `getSongsByIds` 串行 N+1（B13）**：逐个 `getSong` 串行请求，队列恢复数十首歌时 RTT 累加成秒级卡顿。改为并发请求（`supervisorScope` + `async` + 8 路信号量限流），失败单曲不影响整体。

## [v2.26.6] - 2026-09-04

### Fixed

- **本地音乐 5 项 P0 稳定性修复（后端本地音乐模块）**：
  - **全量扫描非原子（B3）**：`fullScan()` 原实现「先 `deleteAll()` 再 `scanAllMusic()`」，一旦扫描抛异常（MediaStore 查询失败 / 权限变更），用户曲库被清空。改为「先扫描成功，再重建索引」，扫描失败时旧索引保持不变。
  - **USB 索引每次启动被误删（B4）**：`incrementalScan()` 原做 `cachedKeys - scannedKeys` 全量差集，USB 拔出后 MediaStore 不再返回该卷条目，导致所有 USB 歌被判定为「已删除」、每次启动丢失 USB 索引。改为按 `storageType`/`volumeName` 区分：仅对「本次扫描覆盖到的卷」做删除比对，未挂载的 USB/外部 SD 条目保留，挂载时由 `scanUsbDevice` 定向更新。
  - **`IN (:paths)` 超变量上限崩溃（B5）**：`deleteByPaths` 一次性传参超 SQLite 999 变量上限时抛「too many SQL variables」。新增 `deleteByPathsChunked` 按 500 条分批删除，覆盖增量/全量/USB 扫描三条路径。
  - **本地歌双 ID 跨会话失效（B6）**：`ScannedSong.toSong()` 用 `contentUri.hashCode()`、`LocalSongEntity.toSong()` 用 `mediaStoreId`，同一首歌扫描时与从缓存加载时 ID 不一致，导致收藏/播放记录/队列跨会话失效。统一为 `local_$mediaStoreId`。
  - **MediaMetadataRetriever 异常路径泄漏**：`MusicScanner.scanFile` 原仅在成功后 `release()`，`setDataSource`/`extractMetadata` 抛异常时泄漏。改为 `try/finally` 确保无论成功/异常都释放。
  - 删除死代码 `LocalMusicDao.getAllPaths()`（改用 `getAllSongs()` 后无调用点）。

- **BackendRegistry `releaseAdapter` 主线程 runBlocking（C3）**：`releaseAdapter()` 原用 `runBlocking { adapter.logout() }`，从 `disconnect()`（`viewModelScope.launch`，Main dispatcher）调用时在主线程阻塞等待 logout 完成。改为 `suspend` 函数并用 `withContext(Dispatchers.IO)` 包裹 logout + close，彻底消除主线程阻塞。

- **Navidrome `getSongs` 末页 N+1 遍历（B8）**：翻页到末页时，服务端正常返回空 `songs` 数组，原实现把「空数组」误判为端点异常，每次都触发 `fallbackGetSongs` 遍历全部专辑逐个 `getAlbumSongs`（N+1），大曲库下卡死。改为仅当 `songs` 字段**完全缺失**（格式不兼容）才走 fallback；空数组直接返回空列表（正常末页）。

## [v2.26.5] - 2026-09-04

### Fixed

- **AppPreferences JSON 偏好「解析失败→回写空」数据丢失模式（P0）**：所有 JSON 型偏好的「读-改-写」写入点（播放记录、最近播放 id、播放次数、最近歌曲对象、网络收藏、本地歌单增删改、搜索历史、均衡器 bands）原先都是 `catch (e) { 空集合 }` 把 JSON 解析失败转成空数据，随后**无条件 `gson.toJson(updated)` 回写覆盖** —— 一次 JSON 异常（写入截断/字段变更）就会把用户积累的数百条播放记录/收藏/歌单抹成空。现引入 `safeParseJson()` 统一安全解析：解析失败记日志并返回 `null`，各写入点改为「解析失败即 `return@edit` 跳过回写，保留原数据」，从根上杜绝覆盖式数据丢失。`gson.fromJson` 调用统一补显式泛型实参以消除平台类型推断问题。

## [v2.26.4] - 2026-09-04

### Fixed

- **HQ 人声分离 >7.8s 歌曲 100% 失败（Demucs 段读取 skip 误跳）**：`DemucsSeparator.separate()` 逐段从临时文件读 float32 时，误加的 `skipBytes((totalSamples - startSample - segLen) * 2 * 4)` 会把指针一次性跳到剩余段末尾，第 2 段起 `readFloat()` 抛 EOFException 被 catch 吞掉，导致分离结果只有第一段（约 7.8s）。根因：临时文件是**连续交织 float32**（`L0,R0,L1,R1,...`，无段间填充），逐采样连续 `readFloat()` 即为正确读取，本无需任何 skip —— 该 skip 是 temp-file streaming 重构（`096b3d7`）时误引入。已删除 skip 块。
- **PlayerManager `with` 误用致主线程加载 166MB 模型 ANR**：`enableHighQualityRemoval` 中 `separator.initialize(modelPath)`（166MB 模型加载）与 `separator.separate(...)` 两处误用 `with(Dispatchers.IO)`（Kotlin 作用域函数，接收者传入但仍在当前线程内联执行），应为 `withContext(Dispatchers.IO)` 真正切到 IO 线程。已改为 `withContext`，消除开伴唱即 ANR/黑屏。
- **Demucs 输出流失败不关闭 + 残缺 WAV 缓存投毒**：`separate()` 的输出流在异常路径不关闭、失败时残留「44 字节 WAV 头 + 部分 PCM」的残缺文件。已把输出流/输出文件提升到 `try` 外，`finally` 统一关闭流，并在失败（`success=false`）时删除残缺 WAV，避免伴奏缓存误判为有效。

## [v2.26.3] - 2026-09-04

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **网络封面（Meting/网易云）检索改为多候选回退，提升百度网盘专辑封面命中率**：原实现只用「代表歌曲标题 + 艺术家」检索一次，失败即放弃；而百度歌曲常缺艺术家标签，标题也可能不规范（`01.mp3`、乱码）。现按「信息可靠度」依次尝试最多 4 组检索词：**标题+艺术家 → 仅标题 → 目录名推断的专辑名+艺术家 → 仅专辑名**，取首个非空结果即停。要点：百度专辑名是从**目录名**推断的（`MusicMerger.buildBaiduAlbums` 取 path 倒数第二段），常为「周杰伦」「新建文件夹」之类，直接当检索词命中率极低，故降级为兜底候选；歌曲标题来自文件名/ID3，可靠度更高，优先使用。`MetingApiService.searchCoverUrl` 对空艺术家有正确处理（按纯标题检索），传空串安全。

## [v2.26.2] - 2026-09-04

### Fixed

- **封面解析循环收敛（消除 CPU/电量/流量开销）**：`updateMergedData()` 末尾无条件调用 `resolveAlbumCoversAsync()`，而后者解析结束后**不判断是否有成果**又无条件回调 `updateMergedData()`，形成 `updateMergedData → resolveAlbumCoversAsync → updateMergedData → …` 的**无中断条件**后台循环 —— 持续重复 merge 专辑/艺术家、统计 songCount，并对解析不出的专辑反复发网络请求（iTunes/百度）。现加两道收敛护栏：① 只对「仍缺封面且尝试次数未达上限」的专辑发起解析（每专辑最多 2 次，保留 1 次重试以容忍瞬时网络失败），无待解析项时直接返回；② 仅在**确实解析出新封面**时才回调 `updateMergedData()` 重建 UI。新出现的专辑不在尝试表中，仍会被正常解析，不影响首次封面获取。
- **艺术家封面解析去除冗余重建**：`resolveArtistCoversAsync()` 原先无论有无成果都回调 `updateMergedData()` 触发全量 merge；改为仅在解析出新封面时重建（该方法由 `loadArtists` 触发，不与 `updateMergedData` 互调，本身不构成循环，此项为消除多余开销）。

## [v2.26.1] - 2026-09-04

### Fixed

- **百度网盘侧车封面 dlink 缺少 access_token（封面一律 403）**：`BaiduCoverProvider.ensureAccessToken()` 原先只拼接**空的** `access_token=`（从未获取 token 值），导致所有不含 token 的侧车封面 dlink 请求被百度拒绝、Coil 加载失败；且该类构造时未注入 `BaiduOAuthClient`，根本无从取 token。现注入 `BaiduOAuthClient`（`NasMusicApp`），改为真正调用 `getValidAccessToken()` 并做 URL 编码（与 `BaiduStreamFactory.resolveStreamUrl` 处理一致）；取不到 token 时返回 `null`，交由后续「内嵌 APIC → 网络封面」fallback 继续处理，不再产生无效 URL。

## [v2.26.0] - 2026-09-03

### Added

- **专辑/艺术家详情页歌曲「加入歌单」按钮**：`AlbumDetailScreen`/`ArtistDetailScreen` 的歌曲行新增 `onAddToPlaylist` 回调，复用 `UnifiedSongRow` 已有的 `+` 按钮；`AppRoot` 将 `pickerSong` 提升到顶层，使加入歌单弹窗在曲库/专辑详情/艺术家详情三处共用

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **曲库字母索引条焦点导航对称化**：`SideLetterIndex` 新增 `contentFocusRequester`/`letterFocusRequester`，显式处理「内容区按右→字母条」「字母条按左→内容区」的焦点交接；`AlbumsTab`/`ArtistsTab` 均加 `onKeyEvent`（最右列右移聚焦字母条）与逐条 `onFocusChanged` 跟踪，艺术家页与专辑页行为一致
- **播放队列歌曲行显示来源标签**：`QueueScreen` 队列行新增 `SourceBadge(song)`，展示 NAS/百度网盘等来源
- **百度网盘歌曲封面来源扩展**：`AlbumCoverResolver` 新增网络封面回退（按歌曲标题+艺术家检索 Meting/网易云），`BaiduNetdiskService.resolveCoverUrl` 在侧车/内嵌 APIC 失败后回退到网络封面；专辑详情页改用 `mergedAlbums` 实时专辑（含异步解析封面），修复「openAlbumDetail 冻结快照无封面」问题

### Removed

- **「我的」页移除「最近播放」分区**：首页已有最近播放，移除以避免三栏过窄；保留收藏 + 本地歌单两栏

## [v2.25.8] - 2026-09-03

### Fixed

- **曲库专辑/艺术家页面空白**：`LibraryScreen.kt` 的 `AlbumsTab`/`ArtistsTab` 原用 `Row(fillMaxSize) + Box(weight(1f)) + LazyVerticalGrid(fillMaxSize)`，网格高度被塌缩为 0，只剩中间 A‑Z 字母条可见。改为与可用的 `SongsTab` 同构：`Box(fillMaxSize)` 父容器 + 内部 `Box(fillMaxSize)` 放网格 + `SideLetterIndex`。真机验证：内容正常显示 ✅
- **曲库字母索引条位置错误（居中而非右边缘）**：`SideLetterIndex` 内部 `Column` 的子项用 `Box(fillMaxWidth())`，在父 `Box(align(CenterEnd))` 中把整列撑成全宽，导致 `align(CenterEnd)` 失效、字母靠 `Column(CenterHorizontally)` 居中。给 `Column` 加固定窄宽 `.width(letterSize + 8.dp)`，让 `align(CenterEnd)` 把整条钉在右边缘。`AlbumsTab`/`ArtistsTab` 共用该 Composable，一处修复两处生效。真机验证：字母条已归位到右边缘 ✅
- **百度网盘歌曲无法播放（根因：TV/盒子 ROM 的 AndroidKeyStore 不支持 AES KeyGenerator）**：`util/CryptoUtils.kt` 的 `getOrCreateKey()` 用 `KeyGenerator.getInstance("AES", "AndroidKeyStore")`，在电视 ROM 上抛 `NoSuchAlgorithmException`，导致百度 access_token 解密失败 → `resolveStreamUrl` 取不到 dlink → 播放失败。改为由固定口令 SHA‑256 派生软件密钥（`SecretKeySpec`，不依赖 KeyGenerator/KeyStore），`decrypt` 先试软件密钥、回退旧 AndroidKeyStore 密钥以兼容手机端存量加密数据。`AppPreferences` 的百度 token、NAS 的 apiToken/password 均走此工具，一并变稳。代码已推电视、运行无崩溃；**行为验证待用户在电视上重新授权（重登）百度网盘一次**——旧 token 由旧密钥加密、电视解不开，须重登用新密钥重新加密，之后点歌复测

## [v2.25.7] - 2026-09-02

### Added

- **搜索页拼音匹配（TV 端）**：曲库搜索页输入拼音首字母（如 "zjl"）或全拼（如 "zhoujielun"）即可匹配中文歌曲，支持多词搜索（如 "zjl 周杰"）。仅 TV 端启用（手机端触屏输入汉字方便，无需拼音），中文输入完全保留原有子串匹配行为
- **`PinyinMatcher` 工具类**：统一搜索匹配逻辑（子串 OR 拼音全拼 OR 拼音首字母），`isTVDevice` 参数控制是否启用拼音匹配
- **`SongWithPinyin` 拼音缓存**：搜索过滤阶段一次性生成拼音缓存，避免重复计算；使用 `lazy` 延迟计算，仅访问到的字段才生成
- **`PinyinUtils.toPinyin()` 全拼方法**：新增完整拼音转换（"周杰伦" → "zhoujielun"），原有 `getInitials()` 重命名为 `toPinyinInitials()`（保留兼容别名）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **`SearchAggregator` 新增 `isTVDevice` 构造参数**：PRECISE 过滤阶段根据设备类型决定是否启用拼音匹配，手机端零额外开销
- **`NasMusicApp` 构造 `SearchAggregator` 时传入 `isTVDevice`**：通过 `packageManager.hasSystemFeature("android.software.leanback")` 检测

## [v2.25.6] - 2026-09-01

### Added

- **关于页新增「API 版本号」展示区**：集中展示所有已接入后端/服务的 API 版本号。后端（Jellyfin / Navidrome / Subsonic / 道理鱼 / 飞牛）运行时从各自端点获取真实版本；百度网盘 / Jamendo / Open-Meteo / OpenWeatherMap 展示静态常量版本；Meting-API / Bilibili MV 无版本号仅展示服务名
- **`VersionInfo` 数据模型**：新增密封接口 `Static` / `Runtime` / `NoVersion` / `Disconnected` 四种状态，统一描述各后端的版本号来源与展示方式
- **`BackendAdapter.getApiVersion()` 接口**：各后端适配器实现该方法，返回结构化版本信息（`apiVersion` 旧字段标记为 deprecated）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **设置页关于页分段渲染**：后端连接段 + API 版本号段 + 外部服务段，按钮文字显式指定颜色（未选中态亮色，遵循 SettingsScreen 修复规范）
- **`MainViewModel` 版本聚合**：新增 `apiVersions` StateFlow + `refreshApiVersions()`，在初始化 / 连接成功 / 断开连接时刷新

## [v2.25.5] - 2026-09-01

### Fixed

- **语言切换不生效**：`MainActivity` 从 `ComponentActivity` 改为 `AppCompatActivity`，使 `AppCompatDelegate.setApplicationLocales()` 生效。之前 `ComponentActivity` 不触发 `AppCompatDelegate` 的 locale 切换和 Activity 重建，导致切换语言后界面无变化
- **语言选择器按钮对比度不足**：设置页语言按钮未选中时改为亮色文字（`TextPrimary`），背景透明；选中时背景使用 `Primary.copy(alpha=0.18f)`，文字加粗（`FontWeight.Medium`），与歌词来源按钮风格对齐
- **语言切换方式修正**：放弃 `AppCompatActivity` + `AppCompatDelegate.setApplicationLocales()` 方案（与 Leanback Theme 冲突），改用 `attachBaseContext()` + `createConfigurationContext()` 原生方案，`MainViewModel` 增加 `suspend fun updateLanguage()` 协程调用，`AppRoot` 通过 `finish() + newIntent + exit(0)` 实现冷重启切换
- **API 24 以下设备 StorageMonitor 崩溃**：`StorageMonitor.refreshStorageDevices()` 增加 `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return` 守卫，防止在低版本设备上调用 `getStorageVolume()` 导致崩溃
- **DataStore 双实例冷启动崩溃**：`AppPreferences` 改为单例模式（`companion object getInstance()`），DataStore 使用 `PreferenceDataStoreFactory.create()` 替代 `preferencesDataStore` 委托，避免 Application 创建阶段多个协程同时初始化 DataStore 导致 `IllegalStateException`
- **DataStore 冲突导致切换语言闪退**：`AppRoot` 中 `onChangeLanguage` 从 `recreate()` 改为 `finish() + newIntent + exit(0)` 三连，避免 `recreate()` 触发 DataStore 并发写入冲突
- **K歌变速弹窗文字折行**：`KaraokeStepPickerDialog` 弹窗宽度从 360dp 拉宽至 420dp，当前值按钮宽度从 160dp 拉宽至 200dp，防止 "Original speed" 等较长文本折行

## [v2.25.4] - 2026-09-01

### Added

- **中英文语言切换功能**：设置页新增语言选择器，支持「跟随系统 / 中文 / English」三种模式，运行时切换无需重启应用。实现方式：`AppCompatDelegate.setApplicationLocales(LocaleListCompat)` + DataStore 持久化
- **英文翻译资源文件** `values-en/strings.xml`：完整覆盖所有用户可见 UI 字符串（~870 行），含 Compose UI、Web 页面 HTML、播放器错误信息等
- **Web 页面 HTML 国际化**：BackupTransferServer / ModelTransferServer / RemoteControlHtml 三个 HTTP 服务器的静态 HTML 常量改为动态生成函数（`buildXxxHtml(context)`），所有文本走 `context.getString()`，JS 字符串通过注入 `var STR = {...}` 对象实现多语言

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **Settings 新增语言设置项**：通用设置区块顶部新增语言选择器，三按钮横向排列（跟随系统 / 中文 / English），选中态高亮
- **数据层扩展**：`AppSettings` 新增 `language` 字段，`AppPreferences` 新增 `setLanguage()` / `getLanguageSync()` / `language` Flow
- **应用启动流程**：`NasMusicApp.onCreate()` 调用 `applyLocale()` 在初始化阶段恢复用户语言偏好
- **依赖新增**：`androidx.appcompat:appcompat:1.6.1`（AppCompatDelegate）、`androidx.core:core-ktx:1.12.0`（LocaleListCompat）

### Notes

- RemoteControlServer 构造函数新增 `context` 参数，Impl 内部类接收 context 传给 `buildControlPageHtml(context)`
- 未迁移的硬编码字符串：数据常量（天气描述/枚举标签/过滤关键词/错误码映射）、AppLog 日志、代码注释
- 所有 `html_backup_*` / `html_model_*` / `html_remote_*` / `html_common_*` 字符串资源已添加到 `values/strings.xml` 和 `values-en/strings.xml`

## [v2.25.3] - 2026-09-01

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **UI 字符串外部化（第三批 — PlayerManager + DemucsSeparator）**：将播放引擎层剩余的 ~35 处用户可见硬编码中文字符串迁移至 `res/values/strings.xml`，使用 `applicationContext.getString()` / `context.getString()` 模式。覆盖范围：
  - PlayerManager：下载错误（无文件/HTTP失败/超时/网络错误/异常）、高质量分离错误（组件未就绪/模型未下载/模型路径不可用/初始化失败/分离失败/OOM/异常）、分离进度（下载音频/加载模型/预下载/完成/分离完成）、播放错误
  - DemucsSeparator：模型错误（文件不存在/OOM/初始化失败/未初始化/内存不足）、分离错误（OOM/异常/无音轨/解码OOM/解码失败）、分离进度（解码音频/分段处理/分离中/完成）
  - NasMusicApp：`PlayerManager()` → `PlayerManager(this)` 传入 applicationContext
- **strings.xml 新增 39 行字符串资源**，覆盖 player_error_*、hq_error_*、hq_progress_*、hq_success_*、demucs_error_*、demucs_progress_* 等

### Notes

- PlayerManager 和 DemucsSeparator 的构造函数已接受 Context 参数
- 剩余中文字符串仅存在于：Web 页面 HTML（BackupTransferServer/ModelTransferServer/RemoteControlHtml）、数据常量（天气描述/枚举标签/过滤关键词/错误码映射）、代码注释

## [v2.25.2] - 2026-08-31

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **UI 字符串外部化（第二批 — MainViewModel）**：将 MainViewModel.kt 中剩余的 ~60 处用户可见硬编码中文字符串迁移至 `res/values/strings.xml`，使用 `getApplication<Application>().getString()` 模式。覆盖范围：
  - 连接状态消息（成功/失败/检查设置）
  - 错误提示（加载失败、搜索失败、播放失败、收藏失败、刷新失败等）
  - 播放列表操作（创建/删除/重命名/添加/移除）
  - 备份操作（导出/恢复/删除）
  - 网盘操作（加载目录/搜索/索引扫描）
  - MV 消息（未找到视频/切换搜索源/搜索更多）
  - 歌词操作（加载/切换来源/缓存清除）
  - 电台/Jamendo 加载失败
  - 天气心情切换失败
  - 百度网盘认证（获取设备码/用户拒绝/授权超时）
- **strings.xml 新增 18 行字符串资源**，覆盖 weather_switch_mood_error、network_search_failed、browse_search_failed、resolve_url_*、play_failed_with_msg、local_music_refreshed、backup_* 等

### Notes

- MainViewModel.kt 中的运行时错误/Toast 消息已全部外部化
- 代码注释、过滤关键词、电台预设列表、AppLog 消息中的中文保持原样（非用户可见 UI 字符串）

## [v2.25.1] - 2026-08-31

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **UI 字符串外部化（第一批）**：将所有用户可见的硬编码中文字符串迁移至 `res/values/strings.xml`，实现文本与代码分离，为后续多语言适配奠定基础。迁移覆盖范围：
  - **播放页**：NowPlayingScreen、KaraokePlaybackScreen、SongInfoPanel、PlayerControls
  - **曲库页**：LibraryScreen、AlbumDetailScreen、QueueScreen、library/DiscoverTab、library/SearchTab
  - **设置页**：SettingsScreen（含网络测试、缓存管理、网盘配置、备份等子模块）、LyricsSettingsDialog
  - **对话框**：ModelTransferDialog、BackupTransferDialog、TextInputDialog、PlaylistPickerDialog、ExitConfirmDialog
  - **连接页**：ServerConnectScreen（Jellyfin/Navidrome/Subsonic/道理鱼/飞牛 五种后端类型名、地址提示、测试结果）
  - **网盘页**：netdisk/NetdiskScreen、netdisk/BaiduAuthDialog
  - **其他**：WeatherRadioScreen、MvPlaybackScreen、KaraokeLyricsView、VocalToggleButton
  - **通用组件**：common/ActionBar、common/SectionHeader、common/ListStateIndicators（LoadingIndicator/ErrorDisplay/EmptyState 默认参数改用 stringResource 解析）、playlist/UnifiedPlaylistCard、song/UnifiedSongGrid
- **strings.xml 新增 258 行字符串资源**，覆盖 26 个文件共 493 处替换（含 `stringResource` Composable 调用与 `context.getString()` 用于非 Composable 作用域）
- **build.gradle.kts**：versionCode 70→71，versionName 2.25.0→2.25.1

### Notes

- ~~MainViewModel.kt 中的 138 个运行时错误/Toast 消息（含字符串插值）~~ → 已在 v2.25.2 中完成迁移
- 代码注释中的中文保持原样（非用户可见 UI 字符串）

## [v2.25.0] - 2026-08-30

### Added

- **本地音乐扫描播放**：自动扫描设备本地音频文件，支持 USB/SD 卡/内部存储多路径扫描，Room 本地索引持久化（首次扫描毫秒级加载）
- **NAS + 本地音乐合并**：同一视图无感浏览 NAS 与本地音乐，按来源优先级排序（NAS > 本地），支持跨源并行搜索
- **存储设备插拔监听**：实时监听 USB/SD 卡设备插拔，自动更新存储列表并触发增量扫描
- **本地歌词匹配**：同目录同名 LRC 歌词文件自动匹配，支持 GBK/UTF-8 编码自适应
- **本地封面提取**：从音频文件内嵌元数据提取专辑封面
- **本地音乐合并去重**：标题+艺术家+时长三字段合并键，自动去重本地重复扫描结果
- **存储权限处理**：自动引导用户授予存储权限，支持 Android 13+ 分级权限

### Fixed

- **Room 2.6.1 与 KSP 2.3.10 不兼容**：升级 Room 至 2.7.1，修复 `unexpected jvm signature V` 编译错误
- **StorageVolume API 兼容性**：`getPath()`/`isMounted()` 改用反射兼容 API 22+
- **GBK 编码支持**：`Charsets.GBK` 改用 `Charset.forName("GBK")` 兼容 Kotlin stdlib

## [v2.24.5] - 2026-08-30

### Fixed

- **人声分离 OOM 崩溃**：`DemucsSeparator.separate()` 重构为逐段流式写入，不再累积全长度 vocals 数组（~60MB），改为边推理边写入 WAV 文件；新增内存预检（低于 200MB 可用空间时拒绝执行），避免被 Android lowmemorykiller 杀掉前台进程

## [v2.24.4] - 2026-08-30

### Added

- **天气电台页面**：首页天气卡片点击可跳转至独立天气电台页面，显示天气信息、心情选择器（FlowRow 自动换行两行排列）和电台歌曲列表；点击单曲即播并跳转播放页
- **关于页版权说明**：设置→关于页底部新增网络音乐版权免责声明，明确本应用仅为技术聚合工具，不存储不分发音乐文件

## [v2.24.3] - 2026-08-30

### Fixed

- **百度网盘授权对话框中文乱码**：`BaiduAuthDialog.kt` 在 `face859` 重构（fontSize `XX.sp` → `FontSize.xx()`）时文件编码从 UTF-8 损坏为 GBK+U+FFFD 混杂，导致 KDoc 注释与「复制」按钮文字、「百度网盘设备码」剪贴板标签等中文字符串在编译后显示为乱码。从 `9c44159` 版本恢复中文字符，保留 `face859` 的 `FontSize.xx()` 调用与 `65a912b` 的 TV +6sp 字号
- **APK 文件名格式统一**：本地 `assembleRelease` 输出与 GitHub Actions CI 上传/发布均使用 `NASMusicTV-release-v{版本号点转横线}.apk` 格式（如 `NASMusicTV-release-v2-24-3.apk`），CI 从 `build.gradle.kts` 读取 `versionName` 自动生成文件名

## [v2.24.2] - 2026-08-29

### Fixed

- **DemucsSeparator 初始化 OOM 崩溃**：`initialize()` 从 `modelFile.readBytes()` + `createSession(bytes)` 改为 `createSession(modelPath)`，ONNX Runtime 底层 mmap 加载 166MB 模型，不再占用 JVM 堆内存，避免电视设备堆内存不足被系统 SIGKILL

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **TV 全局字号 -6sp**：`FontSize` 所有 `*Tv` 常量减小 6sp（Caption 24→18, Small 26→20, Body 29→23, Button 31→25, Subtitle 35→29, Title 39→33, Display 45→39, DisplayLarge 53→47），界面文字整体更紧凑
- **曲库歌曲条目文字统一**：`UnifiedSongRow` 歌曲标题从 `FontSize.subtitle()` 改为 `FontSize.button()`，与歌手名、时长、按钮文字大小一致

## [v2.24.1] - 2026-08-29

### Fixed

- **模型扫码上传失败（HTTP 400/500）**：`ModelTransferServer` 重写上传处理，绕过 NanoHTTPD `parseBody()` 对 166MB 大文件的限制，改用流式 multipart 解析直接从 InputStream 读取 boundary，边读边写文件避免 OOM
- **上传后 `FileNotFoundException: models/htdemucs_ft_vocals.onnx`**：电视 `getExternalFilesDir(null)` 返回 null 时回退到 `context.filesDir`，避免 `File(null, "models")` 变成相对路径；`ModelDownloadManager.getModelsDir()` 同步修复，保证上传路径与下载路径一致
- **上传速度极慢**：`streamToFile` 改用 KMP 思路 + `ByteArrayOutputStream` 批量写入，复杂度从 O(n×bLen) 降至 O(n)；`BufferedInputStream` 缓冲区从 8KB 增至 256KB，读取缓冲从 64KB 增至 128KB
- **上传错误信息不透明**：前端 JS 在非 200 响应时解析 JSON 显示后端返回的具体 `message`，而非仅显示 "HTTP 500"
- **设置页"扫码上传模型"按钮不显示**：模型下载区按钮从 `Row + fillMaxWidth` 改为 `Box(weight(1f))` 分两列并排，修复按钮在 Row 内互相挤压导致不渲染的问题

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **`ModelTransferServer` 路径管理统一**：新增 `getModelFile(context)` 静态工厂方法与 `create(context, onModelUploaded)` 工厂构造，`ModelTransferDialog` 不再自行构造 `modelFile`，避免路径不一致
- **`ModelTransferServer` API 重命名**：`start()`/`stop()` → `startServer()`/`stopServer()`，避免遮蔽 `NanoHTTPD` 父类方法需要 `override` 修饰符

## [v2.24.0] - 2026-08-29

### Added

- **模型扫码上传**：新增 `ModelTransferServer`（NanoHTTPD，端口 18082）+ `ModelTransferDialog`（QR 码弹窗），手机扫码后浏览器上传模型文件到 TV，解决中国大陆 HuggingFace CDN 不可达导致模型无法下载的问题
- **设置页模型路径显示**：设置页模型下载区显示当前模型文件路径及大小
- **"扫码上传模型"入口**：设置页模型下载区在已下载/未下载状态下均提供"扫码上传模型"按钮，点击弹出 QR 码弹窗

## [v2.23.0] - 2026-08-28

### Added

- **高质量分离模型下载管理**：新增 `ModelDownloadManager`，从 HuggingFace 下载 HT-Demucs FT 人声分离模型（约 166MB）到外部存储 `models/` 目录，带下载进度条 / 速度 / 百分比
- **中国大陆镜像下载**：优先 `hf-mirror.com`（国内加速），失败后回退 `huggingface.co`，解决大陆 TV 盒子无法下载模型的问题
- **HT-Demucs FT 高质量分离器**：新增 `DemucsSeparator` 替代原 `SpleeterSeparator`，人声 SDR 从 6.9dB 提升至 9.19dB（开源最高），输入立体声 PCM 分段推理（overlap-add），内部 STFT 免外部 DSP 层
- **设置页模型管理 UI**：新增"高质量分离模型"区块——显示下载状态 / 文件大小 / 下载进度，提供"下载模型"/"删除模型"按钮，未下载时显示下载引导
- **K歌页模型状态感知**："质量"按钮在模型未下载时显示 🔒 锁图标，转换中显示"转换中"并禁用点击
- **K歌页分离进度提示**：高质量模式转换伴奏时显示"正在转换伴奏…"浮层（含进度百分比和阶段描述），转换期间原始音频正常播放

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **模型与 APK 分离**：APK 不再内置 Spleeter 模型文件，release APK 体积从 ~186MB 降至 ~20MB；高质量模式需在设置页独立下载模型后才能启用
- **高质量模式门控**：`MainViewModel.toggleSeparationMode` / `setSeparationMode` 在切换到高质量模式前检查模型是否已下载，未下载时拒绝切换并回退快速模式
- **`SettingSwitch` 支持禁用态**：新增 `enabled` 参数，未下载模型时高质量开关置灰不可点
- **伴唱/原唱切换逻辑重构**：`toggleVocalRemoval` 同时协调 DSP（快速模式）和文件切换（高质量模式），修复快速模式伴唱无声和切换模式后状态错乱
- **高质量模式模型加载异步化**：`separator.initialize()` 移到 IO 线程，避免加载 166MB 模型阻塞主线程导致 ANR 崩溃
- **模式切换状态协调**：新增 `applySeparationMode()` 统一模式切换逻辑，正确处理伴唱中的快速↔高质量切换（DSP 与文件切换同步）

### Fixed

- **快速模式伴唱无声**：`toggleVocalRemoval` 原来只走高质量或只走 DSP 路径，快速模式下 DSP 状态与播放文件不同步，导致伴唱无声音
- **高质量模式 ANR 崩溃**：`enableHighQualityRemoval()` 在主线程加载 166MB ONNX 模型 + 创建 Session，阻塞 >5s 触发系统 ANR 杀进程
- **K歌"质量"按钮文字截断**：按钮宽度从 72dp 加宽至 84dp，label 从"质"改为"质量"
- **高质量模式切换伴奏文件时 DSP 冲突**：切换到伴奏文件时自动关闭 SpectralMaskProcessor（伴奏已无主唱不需要再处理），切回原唱时恢复 DSP 状态

### Removed

- **Spleeter 模型与 DSP 层**：删除 `SpleeterSeparator.kt`（Spleeter ONNX）与 `SpleeterDsp.kt`（STFT/iSTFT/Wiener），由 HT-Demucs FT `DemucsSeparator` 取代

## [v2.22.2] - 2026-08-27

### Added

- **K歌页面升降调控制**：新增"调"按钮，支持 -12 ~ +12 半音步进调节（步长 1），持久化到 DataStore，重启后恢复上次设置
- **K歌页面变速控制**：新增"速"按钮，支持 0.5x ~ 1.5x 速度调节（步长 0.1），持久化到 DataStore，重启后恢复上次设置
- **频谱遮罩人声消除处理器**：新增 `SpectralMaskProcessor`（STFT + 自适应频谱遮罩），替代原有 `VocalRemovalProcessor`（Mid/Side DSP），人声消除效果从 ⭐⭐ 提升至 ⭐⭐⭐
- **PlayerManager 升降调/变速 API**：新增 `setPitch(semitones)` / `setSpeed(speed)` / `resetPitch()` / `resetSpeed()` 方法
- **Spleeter ONNX 高质量人声分离**：新增 `SpleeterSeparator`（ONNX Runtime 推理）+ `SpleeterDsp`（STFT/iSTFT/Wiener），支持 FP16 量化模型，人声消除效果从 ⭐⭐⭐ 提升至 ⭐⭐⭐⭐
- **伴奏文件缓存**：新增 `AccompanimentCache`（LRU 500MB），避免重复分离；支持预分离队列（播放进度 >50% 时预分离下一首）
- **分离模式切换**：K歌页面新增"质"按钮，快速/高质量模式一键切换；设置页新增默认分离模式选项

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **人声消除算法升级**：`PlaybackService` 注入 `SpectralMaskProcessor` 替代 `VocalRemovalProcessor`，频域处理精度更高
- **K歌页面 UI 适配**：解决 TV Material3 Surface 无 `onClick` 参数问题，改用 Box+Column+clickable 模式

### Fixed

- **PlaybackParameters.withPitch() 编译错误**：改为使用 `PlaybackParameters(speed, pitch)` 构造函数（ExoPlayer API 差异）
- **TV Surface onClick 编译错误**：`Surface` 无 `onClick` 参数，改用 `Box` + `Modifier.clickable`

## [v2.22.1] - 2026-08-27

### Added

- **统一数据源架构**：网络音乐/电台/Jamendo 并入曲库页，移除独立网络音乐 Screen；新增 SearchAggregator 跨源搜索聚合器（NAS+网络+百度+Jamendo 并行搜索，合并去重）
- **搜索来源点亮模式**：搜索页新增来源点亮栏，可点亮/熄灭 NAS/网络/百度/Jamendo 四个源，切换即按新范围重搜
- **发现页多源聚合**：发现页 `refreshBrowseSongs` 改用 SearchAggregator 聚合多源结果，复用点亮来源逻辑
- **网盘目录感知搜索**：`NetworkMusicService` 接口新增 `searchByDirectory` 契约（目录名命中返回整目录歌曲），百度网盘实现；所有网盘模式通用
- **搜索页精确过滤**：搜索结果精细过滤（标题/歌手/文件名包含关键词），不含搜索词的全部过滤
- **发现页宽泛过滤**：各源返回什么就展示，只做同名同歌手去重
- **不同源不同关键词**：发现页网络/NAS/Jamendo 用展开词（支持换一批多样性），百度用维度标签（目录+API效果更好）
- **最近播放含网络歌曲**：持久化完整 Song 对象（含网络歌曲），不依赖 NAS 连接；我的页进入时刷新
- **首页搜索按钮**：失效的"网络音乐"按钮改为"搜索"，跳转曲库搜索 Tab
- **歌曲行点击播放**：UnifiedSongRow 左侧内容区加 `.clickable`，各页面歌曲条目统一可点击播放
- **K歌手机端歌词字号缩小**：50sp→34sp，两行可放下
- **发现页新增「主题」维度**：旅行/驾车/咖啡/运动/雨天/居家

### Fixed

- **播放按钮懒加载**：去掉 `!isPlaying` 条件，网络歌曲 streamUrl 为空时无论 isPlaying 状态都先解析
- **网络歌曲 URL 过期重解析**：playPause 检查 ExoPlayer IDLE/ENDED 状态，强制重新解析过期直链
- **发现页自动播放 bug**：切页不再自动操作队列（LaunchedEffect 改用 `onDiscoverShuffle` 加载不播放）
- **百度搜索只返回索引 2 首**：本地索引 + API 合并去重（索引不完整不再短路）
- **搜索结果跨页暂存**：搜索关键词提升到 ViewModel，切页回来不重搜（缓存命中跳过，空结果允许重试）
- **发现页主tab切回不重搜**：ensureBrowseLoaded 幂等加载（与搜索页缓存逻辑一致）
- **播放页进度条手机触摸可拖动**：pointerInput key 改用 Unit + rememberUpdatedState，修复 progressMs 每秒刷新导致手势重启
- **发现页维度按钮选中态暗色文字**：选中背景亮色时文字改暗色
- **发现页歌曲条目缺加入歌单按钮**：DiscoverTab 补齐 onAddToPlaylist
- **加入队列语义修正**：SearchTab/DiscoverTab 新增"加入队列"按钮，仅入队不播放
- **我的页歌单 ? 按钮改为行内删除图标**：UnifiedSongRow 新增 onDelete，移除右上角叠加

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **深度review修复**：搜索源硬编码抽 DEFAULT_SEARCH_SOURCES 常量；BaiduNetdiskService 提取 searchInternal 共用方法；recordPlayWithSong 合并为单次 DataStore edit；refreshBrowseSongs 在 produce 外构造 aggregator

## [v2.22.0] - 2026-08-24

### Added

- **歌曲列表设备自适应**：TV 保持两列网格、手机端自动切换为单列（一行一个歌曲条目），覆盖搜索结果、浏览筛选、歌单详情、网盘歌曲、天气电台、发现页推荐及曲库 SongsTab/RecentTab 共 8 处
- **我的页面手机端整体滚动**：手机端"我的"页面改为单个 LazyColumn 统一承载收藏 + 歌单 + 展开歌曲，整个页面一起滑动；歌单展开的歌曲作为独立 item 渲染，支持滚动到底
- **搜索框统一**：电台、独立音乐 tab 的搜索框统一为网盘样式（胶囊形、无独立搜索按钮、点击弹出输入窗口、内嵌 ✕ 清除按钮）
- **键盘输入窗口可滑动**：TextInputDialog 支持 `BoxWithConstraints` + `heightIn` + `verticalScroll`，小屏显示不全时可上下滚动查看全部键盘和按钮
- **启动崩溃保护**：`WindowInsetsControllerCompat.hide()` 加 `try-catch` 保护，避免部分设备兼容性问题导致启动闪退

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **全面按钮文字亮色**：15 个文件中所有 `FocusableSurface`/`clickable` 内的 `Text` 显式指定 `color`（tv-material 的 `Text` 不读取自定义 `LocalFocusableContentColor`），包括：
  - 键盘按钮（KeyButton、ActionButton、搜索历史项）
  - 对话框按钮（ConnectPromptDialog、ExitConfirmDialog、BaiduDirPickerDialog、BaiduAuthDialog、PlaylistPickerDialog、LyricsSettingsDialog）
  - 设置页按钮（播放模式、频谱主题、调节按钮）
  - 播放页歌词来源标签（SourceTag）、信息按钮
  - 首页查看全部、返回按钮、ButtonChip/ButtonChipSmall
  - 歌单管理、队列操作按钮
  - 网络音乐各 tab 按钮/提示文字（BrowseSubTab、RadioSubTab、JamendoSubTab、WeatherSubTab）
- **进度条聚焦反馈**：播放页进度条聚焦时滑块圆点放大变黄、背景变亮、新增光晕效果
- **主 tab 右对齐**：顶部导航栏主 tab 改为右对齐，手机窄屏仍可横向滚动
- **搜索栏统一**：曲库页、网络音乐搜索页的搜索框统一为 `SearchField` 共享组件（胶囊形、无独立搜索按钮）
- **表格列跨度自适应**：所有 `GridItemSpan(2)` 改为 `GridItemSpan(maxLineSpan)`，自动适配 TV 双列/手机单列

### Fixed

- **播放页左侧滚动修复**：NowPlayingScreen 左侧 Column 移除无效的 `weight(1f)` Box，恢复 `verticalScroll`（手机端可滑动查看完整内容）
- **信息按钮崩溃**：移除 `SongInfoPanel` 内层 `verticalScroll`（嵌套滚动容器导致 `IllegalStateException`）
- **备份弹窗返回键**：`BackHandler` 移入 Dialog 内部（Dialog 独立窗口吞掉系统 BACK 事件）
- **备份弹窗手机可滑动**：`BoxWithConstraints` + `heightIn` + `verticalScroll` 支持手机横屏
- **二维码弹窗 URL 可点击**：备份弹窗和百度登录弹窗的 URL 支持点击打开浏览器
- **设备码复制功能**：百度登录弹窗设备码旁新增亮色【复制】按钮，复制后 Toast 提示
- **手机端全屏白条**：`WindowInsetsControllerCompat` 隐藏系统栏，支持滑动临时唤醒
- **曲库页 tab 标签亮色**：LibraryTab 标签文字显式指定亮色

## [v2.21.0] - 2026-08-23

### Added

- **电台（radio-browser.info）**：网络音乐页新增"电台"子 Tab——全球公开电台目录（含中文电台，默认热度排序），支持标签快捷筛选与关键词搜索，点击即点即播直播流（纯公共 API，无 key、不建后台）
- **电台直播态**：播放页进度条新增"直播"态（● LIVE）——隐藏进度填充与滑块、禁用 seek（TV 左右键与手机触摸均禁用），电台播放时显示
- **Jamendo（CC 独立音乐）**：网络音乐页新增"独立音乐"子 Tab——50 万+ 知识共享授权音库（官方开放 API），热门榜 + 风格标签筛选（氛围/电子/爵士/电影配乐等）+ 搜索；搜索/播放/歌词/封面完整复用 `NetworkMusicService` 路由
- **Jamendo 配置**：设置页"网络音乐"分区新增 Jamendo Client ID 配置（devportal.jamendo.com 免费注册）；未配置时该 Tab 显示引导卡，配置后运行时动态注册服务
- **Jamendo 结果缓存**：LRU（30 条 / 10 分钟）控制官方 API 月度配额（35,000 次）

### Fixed

- **NetworkMonitorTest 断言与防抖设计对齐**：`onCapabilitiesChanged without internet` / `onLost` 两个用例修正为当前防抖策略语义（WiFi 抖动不误报断网、onLost 仅已连接后回调），并新增"capabilities 抖动序列"专项测试——209 个单元测试全部通过

## [v2.20.0] - 2026-08-22

### Added

- **手机端支持**：同一 APK 同时支持 TV 与手机/平板，运行时自动检测设备类型（`hasSystemFeature("android.software.leanback")`）；Manifest 移除 leanback / landscape 强制要求，手机可正常安装启动
- **手机底部导航栏**：手机端改为底部导航（首页 / 曲库 / 网络音乐 / 我的），TV 保持原有顶部导航；设置 / 队列 / 网盘等入口不变
- **MiniPlayer 迷你播放条**：手机端非播放页底部显示迷你播放条（专辑封面 / 歌名 / 播放暂停 / 下一首 / 细进度条），点击进入播放页
- **触摸进度条**：播放页进度条支持触摸点击与拖拽 seek（TV 遥控器左右键 seek 保持不变）
- **手机端默认横屏**：手机端全界面横屏使用（SENSOR_LANDSCAPE），贴近 TV 布局

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **曲库响应式网格**：专辑/艺术家/歌曲/流派/年代网格按屏幕宽度自动调整列数（TV 大屏保留原列数，手机横屏减列，竖屏更少）
- **TV 专属功能按设备隐藏**：手机端隐藏"手机遥控"二维码（K歌/MTV/播放页），HDMI-CEC 等 TV 硬件功能不影响手机
- **tab 栏横向滑动**：曲库页与网络音乐页的 tab 栏支持左右滑动浏览全部 tab（TV 遥控器操作不变）
- **"我的"页新增功能入口**：队列 / 网盘 / 设置（手机端底部导航仅 4 项，未覆盖的页面从"我的"页进入）
- **README**：项目简介纳入手机/平板支持，新增"手机适配"章节

### Fixed

- **手机端点击失效**：`FocusableSurface` 原基于 `androidx.tv.material3.Surface`（onClick 绑定 D-Pad 焦点与 OK 键，不响应触摸），已重写为 `Box + combinedClickable`（触摸点击/长按 + 遥控器 OK 键双兼容，焦点边框仅 TV 显示）——修复手机端所有页面无法点击的问题
- **手机端默认竖屏**：改为手机端默认横屏使用

## [v2.19.0] - 2026-08-21

### Added

- **Subsonic 后端支持**：新增 Subsonic 协议适配器（`SubsonicAdapter`），支持 lx-server、Navidrome、Airsonic 等 Subsonic 兼容服务器
- **Subsonic 认证**：标准 token+salt 认证方式（`md5(password + salt)`），兼容所有 Subsonic 实现
- **Subsonic 完整 API**：专辑/歌手/歌曲/搜索/收藏/播放列表/流派/随机歌曲/歌词/封面流等全部接口
- **Subsonic 连接测试**：ping 端点验证连通性
- **Subsonic 单元测试**：13 个测试覆盖认证逻辑和 API 调用

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **服务器连接页**：新增 Subsonic 服务器类型选项，URL 占位符根据类型动态切换
- **设置页**：支持后端列表更新为 "Jellyfin / Navidrome / Subsonic"
- **README**：项目简介和功能说明更新，纳入 Subsonic 支持

## [v2.18.1] - 2026-08-21

### Added

- **MV 持久缓存清除**：设置页"缓存管理"新增"清除 MV 缓存"按钮，可手动清理 bvid 持久缓存（不自动重新缓存，关机后清空）
- **网盘设置分组**：设置页"网盘"分区新增"百度网盘"/"其他网盘"分组，阿里云盘/123 网盘/夸克网盘灰显"敬请期待"占位

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **服务器设置移入设置页**：导航栏移除"服务器"入口，设置页新增"服务器"分区（连接状态/配置入口/断开按钮）
- **封面设置移入播放 tab**：独立"封面" tab 移除，封面滤镜设置并入"播放" tab 作为子分组
- **歌词与缓存管理合并**：独立"歌词" tab 移除，歌词/封面缓存开关并入"缓存管理" tab 的"缓存开关"分组
- **网盘搜索输入**：BasicTextField 改为弹窗 `TextInputDialog`（TV 遥控器可操作），支持扫码输入
- **网盘搜索结果**：改为 2 列 `LazyVerticalGrid` + 共享 `SongRow` 组件，支持收藏/加入队列
- **网盘目录歌曲列表**：改用 `SongRow` 组件（2 列网格，文件夹跨列），支持收藏/加入队列
- **网盘"播放全部"支持子目录**：BFS 递归收集目录下所有音频后批量播放
- **网盘浏览位置保留**：切换页面不重置当前目录，`refreshBaiduConnectionState` 不再重置目录
- **目录选择器**：固定窗口高度 + 上级按钮始终可见 + 返回键可关闭
- **百度授权对话框**：二维码改为稳定验证页（`verification_url`），弃用不可靠的 `qrcode_url` 一次性 token 链接；返回键可关闭；新增分步操作说明
- **缓存目录大小置顶**：缓存管理 tab 顶部显示

### Fixed

- **启动不再强制跳转设置页**：无服务器配置时保持首页，不自动导航
- **百度授权返回键失效**：`dismissOnBackPress=true` + `onDismissRequest=onCancel` 修复
- **目录选择器返回键失效**：同上方案修复

## [v2.18.0] - 2026-08-20

### Added

- **百度网盘音乐播放**：设备码 OAuth 鉴权，连接百度网盘播放音乐（无需 NAS 后端）
- **文件列表与搜索**：目录浏览 + 关键词搜索（参数名 `key`），支持递归 BFS 扫描
- **音乐串流**：dlink 直链播放（补 `access_token` + `Referer` + `User-Agent`）
- **歌词与封面**：侧车 LRC + 内嵌 ID3 USLT/APIC，网络匹配 fallback
- **MV 索引搜索**：索引扫描时同步收录 MV 目录视频文件，播放时按同目录同名/歌手歌名在索引中搜索，零网络调用切换 MV
- **本地索引缓存**：BFS 逐目录扫描 + 60ms 节流，增量更新，首次扫描后毫秒级搜索
- **API 版本探测**：字段指纹 SHA-256 检测百度 API 静默升级，异常时一次性提示用户
- **网盘设置页**：总开关、设备码登录、根目录/MV 目录配置、自填 AppKey/SecretKey
- **独立网盘 Tab**：目录浏览 + 搜索 UI
- **搜B站按钮**：百度 MV 搜索结果不理想时，一键切到 B 站搜索

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **MV 搜索架构**：从实时 API 查询改为本地索引搜索，`BaiduIndexEntry` 新增 `category` 字段区分音频/视频
- **BaiduOAuthClient**：`tokenUrl` 参数可注入，支持 MockWebServer 测试

### Technical

- 新增 7 个测试文件（53 个单测）：BaiduPanApiTest、BaiduMvFileServiceTest、CloudDriveConfigTest、BaiduDirPickerTest、ApiProbeTest、ApiDriftNotifyTest、BaiduFilenameParserTest
- 编译通过：`BUILD SUCCESSFUL`，`testDebugUnitTest` 191 tests, 189 passed
- ProGuard：显式 keep 百度 DTO 类

## [v2.17.4] - 2026-08-17

### Added

- **网络歌词候选缓存 + 换一批**：`getLyricsFromSource(NETWORK)` 首次请求后缓存候选列表，切换索引时只读缓存不重新请求；候选耗尽时自动用变异后缀（`歌词`、`完整版`、`原唱`、`歌曲`、`lyrics`）重新搜索并追加新候选
- **Kugou/Netease 歌词端点可配置**：`LyricsNetworkProvider` 接收可配置端点参数；设置页"网络搜索"分区新增"歌词端点"子分区，支持酷狗和网易云两个端点独立配置
- **歌词加载性能优化**：缓存命中时立即显示歌词，不等待后端/网络请求

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **歌词优先级**：自动加载时按 `缓存 → 内嵌 → 网络` 优先级选择

## [v2.17.3] - 2026-08-17

### Added

- **播放页歌手/歌名可聚焦跳转网络搜索**：播放页歌手名和歌曲名改为 `FocusableSurface`，D-Pad 可选中，按下确定键自动跳转到网络音乐搜索页并填入搜索词
- **网络歌词持久化缓存**：新增 `LyricsPersistentCache`，参照 `MvPersistentCache` 模式——`lyrics_cache.json`（索引）+ `{songId}.lrc`（独立文件），LRU 2000 条；用户切到网络歌词时暂存（pending），歌曲播放完成时提交（commit）；下次播放时自动读取缓存并显示"缓存"来源标签，可选中高亮和切换

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **批量播放网络歌曲性能优化**：`playNetworkBatch` 不再预先串行解析全部 30 首歌的播放链接（延迟从 30×RTT 降至 1×RTT），改为只即时解析第一首后立即更新队列并开始播放，后续歌曲沿用现有的 `onNeedResolveStreamUrl` 懒加载机制

## [v2.17.2] - 2026-08-11

### Fixed

- **电视 WiFi 频繁掉线（核心修复）**：`NetworkMonitor.onCapabilitiesChanged` 在 WiFi 信号波动时高频误触发 `onNetworkLost`/`onNetworkAvailable`，每次"恢复"都重新连接 NAS 并创建新的 `OkHttpClient`，累积多套连接池/线程池拖垮电视网络栈。改为仅在状态真正转换（无 internet → 有 internet）时回调 `onNetworkAvailable`，`onNetworkLost` 只由 `onLost` 触发——实测 1 小时 16 分钟 MV 连播零掉线（修复前频繁掉线）
- **MTV 页 ExoPlayer 每次 videoUrl 变化重建导致泄漏**：`remember(mv.videoUrl)` 改为 `remember(context)`，页面生命周期内复用同一个 ExoPlayer 实例，切歌通过 `stop()+setMediaItem()` 完成（实测 45 次切歌仅创建 1 个 ExoPlayer，零播放错误）
- **PlayerManager 1000ms Handler 轮询健壮性**：`postDelayed` 移入 `player` 非空分支内（player 释放后自动停止轮询）；`onPositionDiscontinuity(SEEK)` 立即清除 `seekPending`（原代码漏了这步导致 2s 进度停滞）；seek 兜底 timeout 从 2s 缩短到 1s 且用独立 Runnable（避免重复清除）
- **空 URI 传入 ExoPlayer 制造错误噪声**：`onPlayerError` 中对 `streamUrl` 为空的预期错误提前 return，不再设 `_playerError`（不污染错误 UI）+ 不打 ERROR 日志
- **MetingApiService.resolveLyrics 不 fallback**：与 `search`/`resolvePlayUrl`/`getPlaylist` 对齐，采用 `buildEndpointFallbackOrder` 多端点 fallback
- **MetingApiService.parseSongs 逐条打日志刷屏**：改为汇总日志（一次请求只打一条 INFO），首项 keySet 降为 DEBUG 级
- **extractIdFromUrl URI 解析失败后正则 fallback**：改用 `android.net.Uri.parse`（更宽容不抛异常），正则降为兜底
- **HttpLoggingInterceptor 在 release 未关闭**：`JellyfinAdapter`/`NavidromeAdapter`/`LyricsNetworkProvider` 三个 OkHttpClient 均用 `BuildConfig.DEBUG` 包裹日志拦截器，避免 release 中 URL（含 Jellyfin token、酷狗 hash）写入 logcat
- **JellyfinAdapter utf8Body GBK 回退无日志**：GBK 回退触发时打 DEBUG 日志标记哪些响应触发了回退，便于排查编码问题
- **NavidromeAdapter API 版本硬编码**：`v=1.16.1` 和 `c=NASMusicTV` 提取为 `companion object` 常量（`API_VERSION`/`CLIENT_NAME`），注释说明这是 Subsonic 协议版本

## [v2.17.1] - 2026-08-10

### Added

- **遥控页队列删除**：`RemoteControlHtml` 队列行新增 ✕ 删除按钮 + `removeItem(index)`，遥控服务器新增 `/api/queue/remove` 路由（`handleRemove` -> `RemoteCallbacks.removeFromQueue` -> `MainViewModel.removeFromQueue` -> `PlayerManager.removeFromQueue`），与 TV 端队列页删除语义一致

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **遥控 URL 去除 token**：家庭局域网场景下省去扫码后手动输入 token 的操作，URL 简化为 `http://<ip>:18082`（`RemoteControlServer` 删除 `sessionToken` 校验与 URL 拼接；`RemoteControlHtml` 删除 `TOKEN` 变量及全部 `?token=` 拼接）——家庭局域网信任环境，风险可接受
- **遥控页移除播放按钮**：队列条目点击本身即播放，冗余的 `play-btn` 按钮行与样式删除，页面更简洁

### Fixed

- **K歌页二维码被覆盖不显示**：`KaraokePlaybackScreen` 的二维码 `Image` 加 `.zIndex(10f)`（补 `import androidx.compose.ui.zIndex`）。根因：Compose Box 中后声明元素绘制在上层，K歌页二维码先声明、被后声明的全屏背景 + 暗色遮罩覆盖；MTV 页二维码因在 Box 末尾声明正常
- **遥控页长按拖拽超时失效**：`fetchQueue` 增加 `if (dragState) return;` 守卫 + 补 `touchcancel` 监听（复用 `onTouchEnd` 清理状态）。根因：队列每 5 秒轮询 `renderQueue` 会用 `innerHTML` 整表重建 DOM，长按激活拖拽后若按住超过一个轮询周期，被拖拽元素变成游离节点，移动状态在没有松手的情况下失效；守卫保证触摸/拖拽期间不重建队列 DOM，松手后轮询自动恢复

## [v2.17.0] - 2026-08-10

### Added

- **手机遥控（扫码控制）**：K歌/MTV 全屏页右上角显示二维码（`QrCodeGenerator` 生成，含 token 的 URL），手机扫码打开遥控页（`RemoteControlServer` NanoHTTPD 自建服务，端口 18082 + token 鉴权 + `Connection: close`），可查看当前队列、播放/移动/添加歌曲、搜索 NAS 与网络音乐（`/api/queue`、`/api/queue/play`、`/api/queue/move`、`/api/queue/add`、`/api/search`、`/api/status`；`PlayerManager.playAt` / `moveQueueItem`）；遥控页 HTML 内嵌于 `RemoteControlHtml`，队列每 5 秒轮询刷新

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **遥控服务器按需启动**：`MainViewModel` 不再在 `init` 时启动遥控服务器（原常驻），改为 `ensureRemoteControlStarted()` 在进入 K歌（`onEnterKaraokeMode`）或 MTV（`enterMvMode`）模式时按需启动，`onCleared` 统一停止——降低 TV 资源受限设备上的常驻端口/线程开销（排查 TV WiFi/ADB 断连诱因时发现的最高嫌疑项）
- **遥控页轮询间隔 3s → 5s**：降低手机端连接频率，减少 TV 端 NanoHTTPD 线程创建/销毁压力

## [v2.16.0] - 2026-08-09

### Added

- **MV 持久缓存（跨会话复用）**：新增 `MvPersistentCache` 存 `songId -> bvid` 映射到 JSON 文件，只存 bvid（稳定不变）不存直链（小时级过期）；三层查询：内存缓存（45min TTL 含直链）-> 持久缓存（bvid 不过期，`resolveMv` 拿新鲜直链）-> B站 API 搜索；LRU 淘汰上限 5000 条；MV 播完时 `markCompleted` 写入 `playCount++` + `lastPlayedAt`，用户切换后播完覆盖旧 bvid（追踪用户认可的版本）
- **MTV「切换」按钮状态机**：始终常驻；有候选时切换（2 轮循环），2 轮后或无候选时触发 `researchMv` 重搜（`excludeBvids` 排除已展示 bvid + `minSimilarity` 递降 0.5->0.3->0.1 获取更多结果）；`switchMv` 失败显示"切换失败"提示而非静默；重搜不打断当前播放（后台搜索，成功才切换，失败提示"未找到更多视频"）；重搜上限 2 次防无限循环
- **备份/恢复补全**：`BackupData` 新增 MV 持久缓存条目（`mvCacheEntries`）+ 8 项遗漏设置（天气开关/手动城市/自动刷新、封面滤镜开关/模糊半径/暗色遮罩、音乐源、歌词字号）；天气 API Key 敏感不备份；旧版备份文件恢复时新字段用默认值，向后兼容

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **MTV 控制条自动虚化**：5 秒无遥控器操作 -> 控制条 + 底部渐变遮罩虚化至 0.15 alpha（几乎透明不挡视频）；任意按钮点击或 D-pad 焦点切换 -> 完全显化并重新计时
- **MV 搜索候选上限 5 条**：`parseCandidatesFromSearch` 按相似度排序后 `take(5)`，避免候选太多切换轮次过长

### Fixed

- **MTV 连播几首后停在最后一帧**：`endedHandled`/`errorReported` 从 `remember` 改为 `remember(mv.videoUrl)`，无缝切歌时新 URL 触发标志重置，否则第一首 MV 设 `true` 后后续 `STATE_ENDED` 被忽略
- **MTV 模式下网络抖动频繁弹提示**：`onNetworkAvailable` 加去重守卫（已可用时跳过）；MTV 模式（`showMv=true`）下抑制"网络已恢复/断开"弹窗，网络状态仍追踪、自动重连仍运行

## [v2.15.0] - 2026-08-09

### Added

- **MTV 音乐视频搜索与播放**：播放页「K歌」按钮旁新增 MTV 按钮，切歌时后台自动搜索 B 站 MV（三步取流：搜索 bvid -> view 拿 cid -> playurl 拿直链，wbi/legacy 双路径回退 + 标题相似度排序），搜到则按钮亮起可点击进入全屏视频页（`MvPlaybackScreen`，独立 ExoPlayer + 暗色渐变遮罩 + 可开关 K 歌逐字歌词），未搜到则置暗不可点；进入时暂停主播放器、退出时恢复
- **MTV 连播模式（预搜 + 无缝切换）**：当前 MV 播放时后台预搜下一首的 MV（`peekNextSong` + `preSearchNextMv`），MV 播完后用预搜结果直接设 `Ready`（`advanceIndexSilently` 静默推进队列索引，不触发 `Searching` 状态、不触碰 ExoPlayer），无闪烁无混音；预搜未就绪则同步搜索，搜不到自动退出回播放页
- **MTV 页面上一首/下一首**：`MvPlaybackScreen` 底部控制条新增上一首（SkipPrevious）和下一首（SkipNext）按钮，`onMvPrevious` 回退队列索引 + 搜索 MV，`onMvNext` 有预搜则无缝切换、无则同步搜索
- **多 MV 结果 + 切换**：搜索返回 `MvSearchResult`（最佳匹配 `MvInfo` + 候选列表 `List<MvCandidate>`），MTV 页面「切换」按钮按需 `resolveMv(bvid)` 懒加载直链切换不同视频，旧 MV 变为候选
- **MTV 搜索单元测试**：`MvSearchManagerTest` 12 例覆盖缓存命中/多源 fallback/单源异常不阻断/空结果不缓存/TTL 过期重搜/`clearCache`/缓存 key 归一化/`resolveMv`；`BilibiliMvServiceTest` 13 例覆盖 B 站搜索结果解析（候选列表/非 video 过滤/HTML 去标签/相似度排序/封面 URL 补全）与直链提取（durl/dash 回退/code 错误/空值跳过/非法 JSON），用本地 JSON fixture 不联网（Robolectric）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **K歌/MTV/歌词按钮配色统一**：`VocalToggleButton` 从红色（`#FD3359`）改为与播放控制按钮一致的 Surface 底色 + 聚焦 Primary 高亮，Text 颜色继承 Surface contentColor（聚焦时自动变黑）
- **MTV 按钮始终显示**：不再受 `currentSong != null` 条件控制，无 MV 时半透明（`dimmed`）不可点击；播放页左列宽度 300dp -> 380dp 容纳全部 6 个控制按钮

### Fixed

- **MTV 模式混音**：`pause()` 改为无条件执行（不再检查 `isPlaying`，避免 ExoPlayer BUFFERING 时 `isPlaying=false` 跳过暂停导致缓冲后自动恢复）；新增 `suppressPlayback` 标志封堵 `resume()`/`playQueue()`/`next()` 中的 `play()` 调用，防止异步 URL 解析路径在 MTV 模式下意外恢复主播放器
- **退出 MTV 后队列错乱**：`syncAndPlayCurrent` 改用 `setMediaItems`（完整队列 + 起始索引）代替 `setMediaItem`（单曲），确保 ExoPlayer 内部 `currentMediaItemIndex` 与 `_currentIndex` 一致，`next()`/`previous()` 的 `seekToNextMediaItem`/`seekToPreviousMediaItem` 正常工作
- **MV 播放失败**：直链过期播放失败时自动清缓存重搜一次（`onMvPlaybackError` + `mvRetryDone` 防死循环）
- **切歌后卡在无导航栏播放页**：`AppRoot` 监听 `mvState` 变 `NotFound` 且 `showMv=true` 时自动 `exitMvMode()`

## [v2.14.0] - 2026-08-08

### Added

- **设置页新增视频端点配置（MTV 音乐视频搜索端点）**：网络搜索分区新增「视频端点」小节，预设端点单选（B站官方 API `https://api.bilibili.com`）+ 自定义端点输入框（校验 `http://`/`https://` 前缀，空串恢复默认），选中端点打 ✓ 高亮；数据链路完整 —— `AppSettings.mvApiBaseUrl` → `AppPreferences`（`keyMvApiBaseUrl` + `setMvApiBaseUrl` + `getMvApiBaseUrlSync()` 同步读 + 备份恢复）→ `MainViewModel.updateMvApiBaseUrl()` → `SettingsScreen`；新文件 `backend/network/mv/BilibiliMvService.kt` 作为 MTV 搜索端点常量宿主（`DEFAULT_BASE_URL` / `PRESET_ENDPOINTS`），供后续 MTV 搜索实现复用

## [v2.13.5] - 2026-08-08

### Added

- **K 歌页整曲进度细线**：歌词半透明框下缘新增 2dp 青色→蓝色渐变进度线（复用 `NasMusicBrushes.progressBar`），由 `durationMs` 实时指示整曲进度；纯视觉指示、不参与焦点与 seek（`KaraokePlaybackScreen` 新增 `durationMs` 参数，`NowPlayingScreen` 传入）
- **K 歌逐字节奏单元测试**：`KaraokePacingFractionTest` 5 例覆盖 0/1 边界、半程覆盖 > 0.5、90% 仍 < 1、全程单调不减（`app/src/test/.../ui/components/`）

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **K 歌逐字高亮改为"前快后慢"覆盖节奏**：新增内建幂曲线 `progress^0.6`（`karaokePacingFraction`），模拟卡拉OK 每字实际耗时不均——行内时间过半时已覆盖约 2/3 的字（句首唱得快），剩余的字在后半段慢慢亮起（句尾拖音感）；不依赖每字时间戳，K 歌页与播放页逐字模式共用（`KaraokeLyricsView` / `KaraokeLineText`）

### Fixed

- **K 歌页进度细线初始位置错误**：细线 Box 作为歌词框父 Box 的第二子项，默认 `TopStart` 对齐被叠到歌词框顶部 → 加 `.align(Alignment.BottomCenter)` 贴到框下沿上方 8dp、与歌词底部 padding 不重叠（`KaraokePlaybackScreen`）

## [v2.13.4] - 2026-08-08

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **人声消除（方案 B）算法参数调整，修复"人声没了、音乐也没了"**：Mid vocal 频段由"完全挖空"改为"深度衰减保留 15%"——完全归零会把与人声同频段的居中乐器（主旋律/吉他等）一并抹掉，参考 Audacity 官方"伴奏变薄就降低 Strength"思路；Side vocal 频段保留系数 0.12→0.5（只轻度削减，保住立体声宽度/混响伴奏）；高通截止 6kHz→8kHz（保留镲片/空气感，Audacity 建议 High Cut ≥ 8000Hz）；补偿增益 1.6x→1.25x（衰减式处理后电平掉落小，避免削波与噪声放大）（`VocalRemovalProcessor`）

## [v2.13.3] - 2026-08-08

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **播放页 / 全屏沉浸页逐字模式改为平滑进度**：普通播放页与全屏沉浸页的逐字（卡拉OK）歌词不再按字跳变，复用 `KaraokeLineText` 双色渲染（白色底 + 黄色按行内进度连续推进，边界可落在半个字上），与 K 歌页效果一致（`LyricsView`）
- **`KaraokeLineText` 参数化**：新增 `baseColor` / `highlightColor` 参数，供 `KaraokeLyricsView`（默认白 / 黄）与普通播放页逐字模式复用同一声明式组件（`KaraokeLyricsView`）

### Fixed

- **`resolveAndPlayByIndex` 加固**：补强索引边界与空集合防护，避免逐字歌词连带异常（`MainViewModel`）
- **修复 16 条单元测试失败**：`LrcParserTest` 补挂 Robolectric Runner（`android.util.Log` 不再抛 not mocked）；`NetworkMonitor` 支持注入 `NetworkRequest`、测试改用 `@Config(sdk=[30])` 规避 Robolectric 4.11.1 缺失的 `registerNetworkCallback` shadow（`NetworkMonitor` / 测试类）

## [v2.13.2] - 2026-08-08

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **K 歌歌词改为滚动窗口逐行推进**：两行槽位固定（偶数句在顶部、奇数句在底部）不再整组跳动替换，当前句播完进入下一句时，另一槽位内容换成再下一句（句2 开始播放时顶行换为句3，句3 播放时底行换为句4），下一句始终在另一槽位白色预览、轮到时原地变黄（`KaraokeLyricsView`）

## [v2.13.1] - 2026-08-08

### Fixed

- **K 歌歌词两行颜色统一**：第二行预览不再使用暗灰（`TextSecondary`），两行统一白色底 + 黄色进度，视觉一致
- **K 歌歌词逐字高亮改为平滑进度**：不再按字跳变，黄色进度按行时长比例连续推进，边界可落在半个字上（`TextLayoutResult` 像素级插值裁剪）
- **K 歌模式自动切歌停留在 K 歌页**：移除 `showKaraoke` 对 `currentSong` 的 remember key 依赖，唱完自动下一首不再跳回普通播放页（`NowPlayingScreen`）

## [v2.13.0] - 2026-08-08

### Added

- **人声消除（K 歌伴奏模式）**：基于 Mid-Side 编码 + 分频段处理（方案 B，实时 DSP），在播放页新增"伴奏"按钮，点击后实时消除人声并自动切换到全屏 K 歌页面（封面全屏 + 歌词逐字高亮 + 精简控制栏），"原唱"一键切回
  - `VocalRemovalProcessor`（新增，AudioProcessor）：四阶 Linkwitz-Riley 滤波，Mid 低通 120Hz + 高通 6kHz 保留贝斯/镲片、消除居中 vocal 频段；Side 声道对 vocal 频段额外衰减 88%；补偿增益 1.6x
  - `KaraokePlaybackScreen` / `KaraokeLyricsView` / `VocalToggleButton`（新增）：全屏伴奏播放页、固定 2 行逐字高亮歌词、红 色"伴奏/原唱"切换按钮
  - `PlaybackService`（自定义 RenderersFactory 注入 AudioProcessor）、`PlayerManager`（开关方法）、`MainViewModel`（`vocalRemovalEnabled` 状态）、`NowPlayingScreen` / `PlayerControls` / `AppRoot`（条件渲染 + 入口按钮）

## [v2.12.8] - 2026-08-07

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **全应用文字统一放大 +5sp**：将所有 UI 文件中的 `fontSize` 值固定增加 5sp（非倍数缩放），小字获得更大相对提升（9sp->14sp），大字不过度膨胀（36sp->41sp），共修改 28 个文件 348 处字号
- **操作按钮放大**：收藏/队列/歌单按钮 `.size` 28dp->44dp（3 个共享组件，覆盖曲库/专辑/艺术家/收藏/网络等页面），移除歌曲按钮 28dp->44dp，队列移动按钮宽度 36dp->48dp + 内边距加大
- **歌曲列表封面再放大**：SongRow 封面 72dp->92dp（行高 120dp 内仅留 2dp 边缘），PlaylistSongRow 封面 68dp->88dp
- **主导航 Tab 文字放大**：`NavItem` 选中态 16sp->21sp，非选中 14sp->19sp（此前条件表达式被批量脚本遗漏）
- **"我的"页面歌单列表项与歌曲行对齐**：`PlaylistCard` 增加固定行高 100dp，歌单名 19sp->23sp（与歌名一致），歌曲数 16sp->20sp（与歌手一致），操作按钮 16sp->21sp + 内边距加大
- **NowPlaying 收藏按钮**：内边距 6dp->10dp

## [v2.12.7] - 2026-08-07

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **歌曲列表行高加倍 & 文字放大**：全应用所有歌曲列表（曲库、专辑详情、艺术家详情、播放队列、歌单管理、我的收藏/歌单、网络搜索、天气电台、网络歌单详情、继续听等）的行高增加一倍，文字字号同步加大，改善电视大屏远距离观看的可读性
  - `SongRow`（共享组件，覆盖曲库/网络搜索/天气电台/继续听/收藏列表等 7+ 页面）：行高 100dp，封面 56dp，歌名 18sp，歌手 15sp，序号 16sp，时长 15sp
  - `AlbumDetailScreen` / `ArtistDetailScreen` / `QueueScreen` 内联行：行高 80dp，歌名 18sp，歌手/专辑 15sp，序号 16sp，时长 15sp
  - `PlaylistManagementScreen` 行：行高 80dp，歌名 18sp，歌手 15sp，时长 15sp
  - `PlaylistSongRow`（MineScreen 歌单内歌曲）：行高 96dp，封面 52dp，歌名 18sp，歌手 15sp，时长 15sp

## [v2.12.6] - 2026-08-05

### Added

- **网络歌词候选切换**：再次按下"在线歌词"按钮时，会重新搜索酷狗/网易云并取下一个候选歌词，解决歌词匹配错误时无法换一个的问题。
  涉及 `LyricsNetworkProvider`（`fetchFromKugou`/`fetchFromNetease` 返回多条候选）、`LyricsManager`（`getLyricsFromSource` 支持 `candidateIndex`）、`MainViewModel`（`switchLyricsSource` 递增索引）

## [v2.12.5] - 2026-08-04

### Added

- **「我的」页面收藏列表新增「播放全部」按钮**：左栏「收藏」标题行右侧新增 `ButtonChip`（复用 `common_play_all` 文案），收藏列表非空时显示；点击后将合并后的 NAS + 网络收藏歌曲（按 id 去重）整队加入播放队列并从第一首开始播放（`playQueue` 内部自动异步解析网络歌曲的 streamUrl），随后跳转播放页

## [v2.12.4] - 2026-08-03

### Added

- **输入弹窗全面支持二维码扫码输入**：`TextInputDialog` 的 `showQrCode` 默认值改为 `true`，所有输入弹窗（服务器连接、天气 API Key、Meting 端点、歌单新建/重命名等）默认显示右侧二维码，手机扫码即可远程输入，与搜索窗口体验统一

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **搜索历史范围收窄**：仅搜索类弹窗（曲库搜索 / 网络搜索）显示搜索历史，其余基本输入型弹窗不显示（`showHistory` 仍默认 `false`）

### Removed

- **旧网络音乐入口 `NetworkScreen`**：无任何调用者（已被 `NetworkMusicContainer` + `SearchSubTab` 取代），整个文件死代码移除；相关注释同步更新

## [v2.12.3] - 2026-08-03

### Fixed

- **搜索历史记录时机**：此前曲库搜索（`searchSongsOnServer`）与网络音乐搜索（`searchNetworkSongs`）在入口处即记录关键词，失败搜索（后端未连接、网络错误）也会污染「热门」榜计数。改为仅在搜索成功返回后记录（空结果仍记录，反映用户实际搜过的词）；「换一批」（`shuffleNetworkSearch`）走独立路径不经过 `doNetworkSearch`，不受影响
- **扫码传输备份 `runBlocking` 代码坏味道**：`BackupTransferServer` 的 `onRestore` 回调从 `suspend (String) -> Boolean` 改为非挂起 `(String) -> Boolean`，server 不再依赖协程库；`runBlocking` 桥接职责集中到 `MainViewModel.restoreBackupFromJsonBlocking`（在 NanoHTTPD 工作线程上执行，非主线程，安全）
- **搜索历史「填入」死状态**：`TextInputDialog` 历史项选中回调中的 `text = query` 写入在弹窗立即关闭后不可见，属死状态；已移除，由调用方 `onHistorySelect` 直接执行搜索 + 关闭弹窗

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **`docs/technical-overview.md` §10.33** 修正笔误：v2.12.1 修改文件列表中 `versionName 2.13.0` -> `2.12.1`（与标题版本一致）

### Removed

- **`AppPreferences.clearSearchHistory()`** 死代码：已定义但从未被任何 UI 调用，移除

## [v2.12.2] - 2026-08-02

### Added

- **扫码传输备份**：设置页「数据管理」分区新增「扫码传输备份」按钮，TV 端弹出二维码（端口 18081），手机扫码后浏览器打开备份管理页，支持：下载 TV 备份到手机（卸载 app 后备份不丢）、上传手机备份到 TV、直接在手机上点「恢复」按钮远程恢复备份到 TV。解决此前备份仅存于电视本地、卸载即清空的问题

## [v2.12.1] - 2026-08-02

### Added

- **搜索窗口二维码扫码输入**：曲库搜索与网络音乐搜索的输入窗口右侧新增二维码，手机扫码后浏览器打开输入页，输入中英文文字点"发送到电视"即可推送到 TV 输入框；支持连续输入多次，无需反复扫码。TV 端在输入窗口打开时启动轻量 HTTP server（NanoHTTPD，端口 18080），关闭时自动停止，不常驻后台
- **搜索历史建议**：搜索输入框下方显示历史搜索，分两行--「最近」按时间倒序取 5 条、「热门」按搜索次数倒序取 5 条；遥控器 D-Pad 选中历史项后直接填入并执行搜索
- **搜索历史记录**：自动记录搜索关键词与次数（同名合并计数），30 天 TTL 自动清理 + 200 条上限裁剪，应用启动时清理过期条目；已纳入数据备份/恢复

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **TextInputDialog 新增可选参数**：`showQrCode`/`showHistory`/`historyItems`/`onHistorySelect`，默认不传则行为不变（服务器地址、歌单命名等非搜索入口不受影响）

## [v2.12.0] - 2026-08-01

### Added

- **「我的」页面**：底部导航新增「我的」入口，双栏布局——左栏收藏（本地 + 网络收藏合并、按 id 去重，支持播放 / 取消收藏 / 加入队列 / 加入歌单），右栏本地歌单管理（新建 / 播放 / 重命名 / 删除 / 移除歌曲）
- **本地歌单**：DataStore JSON 持久化，独立于 NAS 后端歌单，可混装 NAS 歌曲与网络歌曲；网络歌曲 `streamUrl` 持久化前置空，播放时按 `isNetworkSong` 自动路由解析
- **歌曲行「＋加入歌单」**：`SongRow` 新增加入歌单按钮（曲库 / 我的页 / 网络音乐页通用），弹出 `PlaylistPickerDialog` 选择目标歌单，支持直接新建
- **数据备份 / 恢复**：设置页新增「数据管理」分区——导出全部可持久化数据（服务器配置 / 设置 / 收藏 / 歌单 / 队列 / 播放统计 / 均衡器等）到 `Downloads/NASMusic/`（API 29+ 走 MediaStore 免权限），支持备份文件列表浏览与从文件恢复；**敏感字段（密码 / API Token / 天气 API Key）一律不导出**，恢复后需重新输入密码连接服务器

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **收藏 / 播放列表入口迁移**：曲库页移除「收藏」Tab 与「播放列表」Tab（功能迁至「我的」页），曲库页聚焦专辑 / 艺术家 / 歌曲 / 流派 / 年代 / 最近播放 / 统计

### Fixed

- **备份文件断电丢失（API < 29）**：部分电视 ROM（如创维 Android 5.1.1）的外部存储 `/mnt/sdcard` 是 RAM-backed rootfs 目录，非真实挂载点，断电即清空，导出到 `Downloads/NASMusic/` 的备份重启后消失。改为主备份写入应用内部存储 `filesDir/NASMusic/`（`/data` 真闪存，断电不丢），另尽力写一份到公共 Downloads 供文件管理器访问；列表合并两处按名称去重，删除时同步删两份副本
- **网络音乐「收藏」按钮跳转失效**：此前点击网络音乐页的「收藏」入口仅切换到 DISCOVER Tab（no-op），现正确跳转「我的」页收藏列表

## [v2.11.0] - 2026-08-01

### Added

- 网络音乐搜索"全部播放"：搜索结果按歌手+歌名去重后批量加入播放队列（上限 30 首），完成后自动进入播放页
- 搜索结果列表改用统一 SongRow 组件，内嵌"加入队列"按钮，支持逐首快捷入队
- 网络音乐搜索"换一批"：用未用过的变异后缀（翻唱/Live/现场/伴奏/纯音乐/串烧等 24 种）重新搜索，突破单次 30 首上限；跨批次去重，已展示过的歌曲自动过滤，只出新歌
- 网络音乐搜索"全部加入列表"：将当前搜索结果与队列按歌手+歌名去重后追加到队列末尾（不替换队列），可反复"换一批 → 全部加入列表"持续扩充队列
- **"换一批"跨批次去重逻辑统一到全部页面**：多维度浏览（浏览 Tab）与天气电台的"换一批"与搜索页共用同一套 `pickBestFreshBatch` 逻辑——最多尝试 6 个随机候选、在候选中挑选新歌最多的批次展示、新歌达 5 首即停、全部无新歌时重置集合从头再来；筛选条件 / mood / 天气变化时重置对应已见集合
- 天气电台构建引入随机化（NAS 匹配与网络搜索结果打乱），支持同一 mood 下反复"换一批"持续出新歌（此前结果确定性重复）
- 网络音乐子 Tab 歌曲列表双列化：发现（继续听/我的收藏）、天气电台、搜索结果、多维度浏览的歌曲列表由全宽单列改为双列网格（`LazyVerticalGrid(Fixed(2))`），充分利用 TV 大屏宽度，跨列区块（标题/操作栏/歌单卡片行等）自动占满两列

### Removed

- **网络音乐"榜单"子 Tab**：榜单页与发现页顶部"推荐歌单"数据源重合（均为预配置网易云歌单轮换），移除榜单 Tab 及 `ChartsContent`/`ChartsCard` UI、"换一批"按钮与 `refreshCharts()` 逻辑；发现页保留"推荐歌单"入口，歌单数据加载（`loadNetworkPlaylists`）不受影响

### Fixed

- **歌曲列表序号全部显示 "00"**：`SongRow` 此前显示的是歌曲内嵌的 `trackNumber` 元数据（网络歌曲与多数本地文件为 0），现改为显示列表序号 `index + 1`；非列表场景（发现页"正在播放"单曲）显示播放图标「▶」。涉及曲库、网络音乐、歌单详情、天气电台、搜索、浏览等全部使用 `SongRow` 的页面
- **网络歌曲播放约 5 首后后续歌曲无法播放**：入队时预解析的播放直链有时效，URL 过期后 `onPlayerError` 仅因链接非空就直接跳下一首，导致级联失败。改为出错时自动重新解析当前歌曲链接并重试一次（同一首仅重试一次，防死循环），仍失败才跳下一首

## [v2.10.9] - 2026-07-30

### Added
- 多维度浏览（网络音乐 > 浏览 Tab）：语种（粤/国/英/日/韩）、纯音乐（萨克斯/笛子/吉他/钢琴/古筝/二胡/小提琴）、年代（70/80/90/00后）、情怀（红歌/草原/民歌）、风格（民谣/摇滚/古风/说唱）五维度组合筛选
- 多维度自由组合搜索：选中维度选项后自动拼接关键词搜索，支持"换一批"随机切换关键词、"播放全部"批量播放

## [v2.10.8] - 2026-07-30

### Added
- 频谱可视化主题：ColorFlow（渐变流光）、NeonPulse（霓虹脉冲）、ClassicalWave（古典波形）三种视觉主题
- 设置页新增"频谱主题"选择器，主题选择持久化到 DataStore

## [v2.10.7] - 2026-07-30

### Fixed

- **沉浸模式封面背景不显示**：`Modifier.blur()` 在部分 TV GPU 驱动下导致整图渲染失败，改为 `rememberAsyncImagePainter` + `Image` 组合渲染，模糊仅在用户主动开启封面滤镜时应用
- **歌单信息面板不可滚动**：`Column` 添加 `verticalScroll`，超出面板高度的信息项可遥控器滚动查看
- **QueueScreen 封面不显示**：用 `CoverCarousel(coverCandidates)` 替代裸 `AsyncImage`，使用多候选封面轮播
- **QueueScreen 播放/暂停按钮无焦点**：中间 PlayPause Box 缺少 `.focusable()` 和 `.clickable`，导致遥控器无法聚焦操作

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **NowPlaying info 面板改为占用封面区域**：信息按钮触发后复用封面空间显示 SongInfoPanel，按钮文字同步切换"信息"/"封面"
- **QueueScreen 控制按钮居中**：`Arrangement.spacedBy` 改为 `Arrangement.Center` + 显式 Spacer

## [v2.10.6] - 2026-07-30

### Fixed

- **Jellyfin 艺术家详情页仅返回 1 首歌**：`getArtistSongs()` 用 `ArtistIds` 查 ID 与 `AlbumArtist` ID 不一致，改为按名称查 `Artists`，合作/关联歌曲全部返回
- **`utf8Body()` 过量日志拖慢电视**：每次 API 响应打 3 行 hex/状态日志，Android TV logd 开销累加显著，全部移除

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **Navidrome 合作歌曲支持**：保留原始艺术家列表，艺术家详情页从所有相关原始 ID（含合作条目）联合查询后合并去重，Album Artist 级合作歌曲不再丢失
- **移除 `loadArtistSongsMap` 预加载**：之前启动时对所有 5000+ 艺术家逐一查歌曲（1000 批串行请求跑数分钟），改为由歌曲 Tab 全量加载后自动填充数量

---

## [v2.10.5] - 2026-07-30

### Fixed

- **合作歌曲艺术家详情页仅显示 2 首歌**：`jsonObjectToSong()` 中 `Artists` 数组只取 `[0]`（首位艺术家），无法覆盖林子祥等合作曲居多的歌手。改为拼接全部艺术家，合作曲不再丢失
- **布局 Tab 过多挤压右侧按钮**：9 个曲库 Tab 占满 Row 宽度，导致"搜索"/"播放全部"按钮被压缩到不可用。缩小 Tab 间距 + 搜索输入框 `weight(1f)` 优先压缩，按钮设 `widthIn(min)` 保护
- **ButtonChip 编译器歧义**：新增 `modifier` 参数后尾随 lambda 导致 4 处调用编译失败，全部改为显式命名参数

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **`loadArtistSongs()` 缓存策略**：进入详情页时清理当前歌手的缓存，确保每次打开都用最新格式重新拉取后端数据

---

## [v2.10.4] - 2026-07-27

### Fixed

- **网络音乐榜单点击无反应**：榜单卡片点击时缺少 `loadPlaylistDetail` 调用，跳转到详情页后无歌曲数据
- **天气电台封面与歌曲列表分离**：移除独立的封面墙，`SongRow` 增加封面缩略图，封面融入歌曲行中

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

### Changed

- **PlayerManager 新增 addToQueue()**：支持向播放队列末尾追加歌曲，用于随心听自动续播

---

## [v2.10.2] - 2026-07-27

### Fixed

- **后台加载线程安全**：`_isBackgroundLoadingAll` 从普通 `var` 改为 `AtomicBoolean` + `compareAndSet` 原子操作，消除协程间竞态条件
- **Navidrome 歌词编码乱码**：`getLyrics()` 中 artist/title 增加 `EncodingUtils.fixEncoding()` 处理，避免 GBK 编码导致歌词搜索失败
- **艺术家歌曲去重**：`loadArtistSongsMap()` 合并到 `artistSongsMap` 时按 `song.id` 去重，避免与 `buildArtistMapsIncremental` 的歌曲重复

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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

### Fixed
- **只有第一张图有描线过程，后续函数直接出全图**：`GAP → DRAW` 迁移时未清零 `drawAccumulator`——首图靠 `onEnter` 归零，之后每个周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。现迁移时归零

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
