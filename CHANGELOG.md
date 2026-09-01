# Changelog

> 所有显著的版本变更记录在此文件。
>
> 格式基于 [Keep a Changelog](https://keepachangelog.com/)，
> 版本管理遵循 [Semantic Versioning](https://semver.org/)。
>
> 类型：`Added`（新增） | `Changed`（变更） | `Fixed`（修复） | `Removed`（移除）

## [v2.25.4] - 2026-09-01

### Added

- **中英文语言切换功能**：设置页新增语言选择器，支持「跟随系统 / 中文 / English」三种模式，运行时切换无需重启应用。实现方式：`AppCompatDelegate.setApplicationLocales(LocaleListCompat)` + DataStore 持久化
- **英文翻译资源文件** `values-en/strings.xml`：完整覆盖所有用户可见 UI 字符串（~870 行），含 Compose UI、Web 页面 HTML、播放器错误信息等
- **Web 页面 HTML 国际化**：BackupTransferServer / ModelTransferServer / RemoteControlHtml 三个 HTTP 服务器的静态 HTML 常量改为动态生成函数（`buildXxxHtml(context)`），所有文本走 `context.getString()`，JS 字符串通过注入 `var STR = {...}` 对象实现多语言

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

### Changed

- **深度review修复**：搜索源硬编码抽 DEFAULT_SEARCH_SOURCES 常量；BaiduNetdiskService 提取 searchInternal 共用方法；recordPlayWithSong 合并为单次 DataStore edit；refreshBrowseSongs 在 produce 外构造 aggregator

## [v2.22.0] - 2026-08-24

### Added

- **歌曲列表设备自适应**：TV 保持两列网格、手机端自动切换为单列（一行一个歌曲条目），覆盖搜索结果、浏览筛选、歌单详情、网盘歌曲、天气电台、发现页推荐及曲库 SongsTab/RecentTab 共 8 处
- **我的页面手机端整体滚动**：手机端"我的"页面改为单个 LazyColumn 统一承载收藏 + 歌单 + 展开歌曲，整个页面一起滑动；歌单展开的歌曲作为独立 item 渲染，支持滚动到底
- **搜索框统一**：电台、独立音乐 tab 的搜索框统一为网盘样式（胶囊形、无独立搜索按钮、点击弹出输入窗口、内嵌 ✕ 清除按钮）
- **键盘输入窗口可滑动**：TextInputDialog 支持 `BoxWithConstraints` + `heightIn` + `verticalScroll`，小屏显示不全时可上下滚动查看全部键盘和按钮
- **启动崩溃保护**：`WindowInsetsControllerCompat.hide()` 加 `try-catch` 保护，避免部分设备兼容性问题导致启动闪退

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

### Changed

- **服务器连接页**：新增 Subsonic 服务器类型选项，URL 占位符根据类型动态切换
- **设置页**：支持后端列表更新为 "Jellyfin / Navidrome / Subsonic"
- **README**：项目简介和功能说明更新，纳入 Subsonic 支持

## [v2.18.1] - 2026-08-21

### Added

- **MV 持久缓存清除**：设置页"缓存管理"新增"清除 MV 缓存"按钮，可手动清理 bvid 持久缓存（不自动重新缓存，关机后清空）
- **网盘设置分组**：设置页"网盘"分区新增"百度网盘"/"其他网盘"分组，阿里云盘/123 网盘/夸克网盘灰显"敬请期待"占位

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

### Changed

- **歌词优先级**：自动加载时按 `缓存 → 内嵌 → 网络` 优先级选择

## [v2.17.3] - 2026-08-17

### Added

- **播放页歌手/歌名可聚焦跳转网络搜索**：播放页歌手名和歌曲名改为 `FocusableSurface`，D-Pad 可选中，按下确定键自动跳转到网络音乐搜索页并填入搜索词
- **网络歌词持久化缓存**：新增 `LyricsPersistentCache`，参照 `MvPersistentCache` 模式——`lyrics_cache.json`（索引）+ `{songId}.lrc`（独立文件），LRU 2000 条；用户切到网络歌词时暂存（pending），歌曲播放完成时提交（commit）；下次播放时自动读取缓存并显示"缓存"来源标签，可选中高亮和切换

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

### Changed

- **遥控 URL 去除 token**：家庭局域网场景下省去扫码后手动输入 token 的操作，URL 简化为 `http://<ip>:18082`（`RemoteControlServer` 删除 `sessionToken` 校验与 URL 拼接；`RemoteControlHtml` 删除 `TOKEN` 变量及全部 `?token=` 拼接）——家庭局域网信任环境，风险可接受
- **遥控页移除播放按钮**：队列条目点击本身即播放，冗余的 `play-btn` 按钮行与样式删除，页面更简洁

### Fixed

- **K歌页二维码被覆盖不显示**：`KaraokePlaybackScreen` 的二维码 `Image` 加 `.zIndex(10f)`（补 `import androidx.compose.ui.zIndex`）。根因：Compose Box 中后声明元素绘制在上层，K歌页二维码先声明、被后声明的全屏背景 + 暗色遮罩覆盖；MTV 页二维码因在 Box 末尾声明正常
- **遥控页长按拖拽超时失效**：`fetchQueue` 增加 `if (dragState) return;` 守卫 + 补 `touchcancel` 监听（复用 `onTouchEnd` 清理状态）。根因：队列每 5 秒轮询 `renderQueue` 会用 `innerHTML` 整表重建 DOM，长按激活拖拽后若按住超过一个轮询周期，被拖拽元素变成游离节点，移动状态在没有松手的情况下失效；守卫保证触摸/拖拽期间不重建队列 DOM，松手后轮询自动恢复

## [v2.17.0] - 2026-08-10

### Added

- **手机遥控（扫码控制）**：K歌/MTV 全屏页右上角显示二维码（`QrCodeGenerator` 生成，含 token 的 URL），手机扫码打开遥控页（`RemoteControlServer` NanoHTTPD 自建服务，端口 18082 + token 鉴权 + `Connection: close`），可查看当前队列、播放/移动/添加歌曲、搜索 NAS 与网络音乐（`/api/queue`、`/api/queue/play`、`/api/queue/move`、`/api/queue/add`、`/api/search`、`/api/status`；`PlayerManager.playAt` / `moveQueueItem`）；遥控页 HTML 内嵌于 `RemoteControlHtml`，队列每 5 秒轮询刷新

### Changed

- **遥控服务器按需启动**：`MainViewModel` 不再在 `init` 时启动遥控服务器（原常驻），改为 `ensureRemoteControlStarted()` 在进入 K歌（`onEnterKaraokeMode`）或 MTV（`enterMvMode`）模式时按需启动，`onCleared` 统一停止——降低 TV 资源受限设备上的常驻端口/线程开销（排查 TV WiFi/ADB 断连诱因时发现的最高嫌疑项）
- **遥控页轮询间隔 3s → 5s**：降低手机端连接频率，减少 TV 端 NanoHTTPD 线程创建/销毁压力

## [v2.16.0] - 2026-08-09

### Added

- **MV 持久缓存（跨会话复用）**：新增 `MvPersistentCache` 存 `songId -> bvid` 映射到 JSON 文件，只存 bvid（稳定不变）不存直链（小时级过期）；三层查询：内存缓存（45min TTL 含直链）-> 持久缓存（bvid 不过期，`resolveMv` 拿新鲜直链）-> B站 API 搜索；LRU 淘汰上限 5000 条；MV 播完时 `markCompleted` 写入 `playCount++` + `lastPlayedAt`，用户切换后播完覆盖旧 bvid（追踪用户认可的版本）
- **MTV「切换」按钮状态机**：始终常驻；有候选时切换（2 轮循环），2 轮后或无候选时触发 `researchMv` 重搜（`excludeBvids` 排除已展示 bvid + `minSimilarity` 递降 0.5->0.3->0.1 获取更多结果）；`switchMv` 失败显示"切换失败"提示而非静默；重搜不打断当前播放（后台搜索，成功才切换，失败提示"未找到更多视频"）；重搜上限 2 次防无限循环
- **备份/恢复补全**：`BackupData` 新增 MV 持久缓存条目（`mvCacheEntries`）+ 8 项遗漏设置（天气开关/手动城市/自动刷新、封面滤镜开关/模糊半径/暗色遮罩、音乐源、歌词字号）；天气 API Key 敏感不备份；旧版备份文件恢复时新字段用默认值，向后兼容

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

### Changed

- **K 歌逐字高亮改为"前快后慢"覆盖节奏**：新增内建幂曲线 `progress^0.6`（`karaokePacingFraction`），模拟卡拉OK 每字实际耗时不均——行内时间过半时已覆盖约 2/3 的字（句首唱得快），剩余的字在后半段慢慢亮起（句尾拖音感）；不依赖每字时间戳，K 歌页与播放页逐字模式共用（`KaraokeLyricsView` / `KaraokeLineText`）

### Fixed

- **K 歌页进度细线初始位置错误**：细线 Box 作为歌词框父 Box 的第二子项，默认 `TopStart` 对齐被叠到歌词框顶部 → 加 `.align(Alignment.BottomCenter)` 贴到框下沿上方 8dp、与歌词底部 padding 不重叠（`KaraokePlaybackScreen`）

## [v2.13.4] - 2026-08-08

### Changed

- **人声消除（方案 B）算法参数调整，修复"人声没了、音乐也没了"**：Mid vocal 频段由"完全挖空"改为"深度衰减保留 15%"——完全归零会把与人声同频段的居中乐器（主旋律/吉他等）一并抹掉，参考 Audacity 官方"伴奏变薄就降低 Strength"思路；Side vocal 频段保留系数 0.12→0.5（只轻度削减，保住立体声宽度/混响伴奏）；高通截止 6kHz→8kHz（保留镲片/空气感，Audacity 建议 High Cut ≥ 8000Hz）；补偿增益 1.6x→1.25x（衰减式处理后电平掉落小，避免削波与噪声放大）（`VocalRemovalProcessor`）

## [v2.13.3] - 2026-08-08

### Changed

- **播放页 / 全屏沉浸页逐字模式改为平滑进度**：普通播放页与全屏沉浸页的逐字（卡拉OK）歌词不再按字跳变，复用 `KaraokeLineText` 双色渲染（白色底 + 黄色按行内进度连续推进，边界可落在半个字上），与 K 歌页效果一致（`LyricsView`）
- **`KaraokeLineText` 参数化**：新增 `baseColor` / `highlightColor` 参数，供 `KaraokeLyricsView`（默认白 / 黄）与普通播放页逐字模式复用同一声明式组件（`KaraokeLyricsView`）

### Fixed

- **`resolveAndPlayByIndex` 加固**：补强索引边界与空集合防护，避免逐字歌词连带异常（`MainViewModel`）
- **修复 16 条单元测试失败**：`LrcParserTest` 补挂 Robolectric Runner（`android.util.Log` 不再抛 not mocked）；`NetworkMonitor` 支持注入 `NetworkRequest`、测试改用 `@Config(sdk=[30])` 规避 Robolectric 4.11.1 缺失的 `registerNetworkCallback` shadow（`NetworkMonitor` / 测试类）

## [v2.13.2] - 2026-08-08

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

### Changed

- **全应用文字统一放大 +5sp**：将所有 UI 文件中的 `fontSize` 值固定增加 5sp（非倍数缩放），小字获得更大相对提升（9sp->14sp），大字不过度膨胀（36sp->41sp），共修改 28 个文件 348 处字号
- **操作按钮放大**：收藏/队列/歌单按钮 `.size` 28dp->44dp（3 个共享组件，覆盖曲库/专辑/艺术家/收藏/网络等页面），移除歌曲按钮 28dp->44dp，队列移动按钮宽度 36dp->48dp + 内边距加大
- **歌曲列表封面再放大**：SongRow 封面 72dp->92dp（行高 120dp 内仅留 2dp 边缘），PlaylistSongRow 封面 68dp->88dp
- **主导航 Tab 文字放大**：`NavItem` 选中态 16sp->21sp，非选中 14sp->19sp（此前条件表达式被批量脚本遗漏）
- **"我的"页面歌单列表项与歌曲行对齐**：`PlaylistCard` 增加固定行高 100dp，歌单名 19sp->23sp（与歌名一致），歌曲数 16sp->20sp（与歌手一致），操作按钮 16sp->21sp + 内边距加大
- **NowPlaying 收藏按钮**：内边距 6dp->10dp

## [v2.12.7] - 2026-08-07

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

### Changed

- **搜索历史范围收窄**：仅搜索类弹窗（曲库搜索 / 网络搜索）显示搜索历史，其余基本输入型弹窗不显示（`showHistory` 仍默认 `false`）

### Removed

- **旧网络音乐入口 `NetworkScreen`**：无任何调用者（已被 `NetworkMusicContainer` + `SearchSubTab` 取代），整个文件死代码移除；相关注释同步更新

## [v2.12.3] - 2026-08-03

### Fixed

- **搜索历史记录时机**：此前曲库搜索（`searchSongsOnServer`）与网络音乐搜索（`searchNetworkSongs`）在入口处即记录关键词，失败搜索（后端未连接、网络错误）也会污染「热门」榜计数。改为仅在搜索成功返回后记录（空结果仍记录，反映用户实际搜过的词）；「换一批」（`shuffleNetworkSearch`）走独立路径不经过 `doNetworkSearch`，不受影响
- **扫码传输备份 `runBlocking` 代码坏味道**：`BackupTransferServer` 的 `onRestore` 回调从 `suspend (String) -> Boolean` 改为非挂起 `(String) -> Boolean`，server 不再依赖协程库；`runBlocking` 桥接职责集中到 `MainViewModel.restoreBackupFromJsonBlocking`（在 NanoHTTPD 工作线程上执行，非主线程，安全）
- **搜索历史「填入」死状态**：`TextInputDialog` 历史项选中回调中的 `text = query` 写入在弹窗立即关闭后不可见，属死状态；已移除，由调用方 `onHistorySelect` 直接执行搜索 + 关闭弹窗

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

### Changed

- **TextInputDialog 新增可选参数**：`showQrCode`/`showHistory`/`historyItems`/`onHistorySelect`，默认不传则行为不变（服务器地址、歌单命名等非搜索入口不受影响）

## [v2.12.0] - 2026-08-01

### Added

- **「我的」页面**：底部导航新增「我的」入口，双栏布局——左栏收藏（本地 + 网络收藏合并、按 id 去重，支持播放 / 取消收藏 / 加入队列 / 加入歌单），右栏本地歌单管理（新建 / 播放 / 重命名 / 删除 / 移除歌曲）
- **本地歌单**：DataStore JSON 持久化，独立于 NAS 后端歌单，可混装 NAS 歌曲与网络歌曲；网络歌曲 `streamUrl` 持久化前置空，播放时按 `isNetworkSong` 自动路由解析
- **歌曲行「＋加入歌单」**：`SongRow` 新增加入歌单按钮（曲库 / 我的页 / 网络音乐页通用），弹出 `PlaylistPickerDialog` 选择目标歌单，支持直接新建
- **数据备份 / 恢复**：设置页新增「数据管理」分区——导出全部可持久化数据（服务器配置 / 设置 / 收藏 / 歌单 / 队列 / 播放统计 / 均衡器等）到 `Downloads/NASMusic/`（API 29+ 走 MediaStore 免权限），支持备份文件列表浏览与从文件恢复；**敏感字段（密码 / API Token / 天气 API Key）一律不导出**，恢复后需重新输入密码连接服务器

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

### Changed

- **NowPlaying info 面板改为占用封面区域**：信息按钮触发后复用封面空间显示 SongInfoPanel，按钮文字同步切换"信息"/"封面"
- **QueueScreen 控制按钮居中**：`Arrangement.spacedBy` 改为 `Arrangement.Center` + 显式 Spacer

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
