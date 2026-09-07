# NASMusicTV 代码审查报告

**审查日期：** 2026-09-07
**审查范围：** 离线下载功能（§15 任务 0-19）+ 未提交 local 改造 + 播放器基础设施 + UI 层适配
**审查基线：** commit `97cd181` (v2.26.32) + 14 个未提交文件

---

## 问题统计总览

| 严重级别 | 数量 | 说明 |
|---------|------|------|
| **P0 — 阻断** | 12 | 功能完全不可用或数据丢失，必须立即修复 |
| **P1 — 严重** | 19 | 影响健壮性/正确性，建议尽快修复 |
| **P2 — 建议** | 33 | 值得修复但不阻断核心功能 |
| **P3 — 微调** | 12 | 代码质量/可维护性改进 |

---

## P0 — 阻断问题（12 项）

### 下载模块（2 项）

#### P0-1 `downloadNow` 绕过串行队列，破坏并发安全
- **文件：** `SongDownloadManager.kt:97,133`
- **问题：** `downloadNow` 在 `withContext(Dispatchers.IO)` 中直接调用 `executeDownload`，而 `loop()` 也调用 `executeDownload`。两者可并发执行同一方法，违反"串行队列 1 个消费者"保证。后果：并发写同一 `.part` 文件、DB 记录覆盖、`_downloadStates` Map 竞态。
- **当前状态：** 死代码（grep 确认无调用方），但是 public API。
- **修复：** 移除 `downloadNow`，或改为内部走 `enqueue` + `Channel` 消费，用 `Mutex` 序列化 `executeDownload`。

#### P0-2 `fullScan()` 遗漏下载目录，手动刷新后 DOWNLOAD 歌曲消失
- **文件：** `LocalMusicRepository.kt:148-156`
- **问题：** `incrementalScan()` 用 `buildScannedList()` 合并了 MediaStore + 下载目录，但 `fullScan()` 直接调用 `scanner.scanAllMusic()`（仅 MediaStore），绕过了 `buildScannedList()`。用户手动刷新后所有 DOWNLOAD 类型歌曲从索引消失。
- **修复：** `fullScan()` 改用 `buildScannedList()` 替代 `scanner.scanAllMusic()`。

### 导出模块（4 项）

#### P0-3 SAF URI 权限未持久化，App 重启后授权丢失
- **文件：** `ExportCoordinator.kt:75-83` / `MainActivity.kt:77-83` / `ExportPermissionHelper.kt:46-56`
- **问题：** `ActivityResultContracts.OpenDocumentTree()` 回调收到 `uri` 后直接调用 `onTreeGranted(uri)`，仅做 `appPreferences.setExportTreeUri(uri.toString())`。全代码库 grep `takePersistableUriPermission` 零命中。App 重启后 `hasPersistedWrite()` 检查 `persistedUriPermissions` 返回 false。
- **修复：** 在 `onTreeGranted` 中保存 URI 前调用 `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`。

#### P0-4 `targetFile()` 恒返回 null，File 根降级导出路径完全不可用
- **文件：** `SongExporter.kt:243-246`
- **问题：** `targetFile(task)` 方法体仅 `return null`。`shouldOverwrite` 因 target=null 恒返回 true（跳过增量），`copyToRoot` 的 File 分支因 target=null 恒返回 false。所有导出到 File 根的文件全部 `failed++`。
- **修复：** 实现 `targetFile`：`File(rootDir, task.relPath)`。

#### P0-5 SAF 嵌套 relPath 不支持多级目录
- **文件：** `SongExporter.kt:195`
- **问题：** `task.relPath` 形如 `"周杰伦/七里香/01 - 七里香.mp3"`（含路径分隔符）。`DocumentFile.findFile(name)` 只匹配直接子文件，`createFile(mimeType, displayName)` 会创建文件名含斜杠的单层文件，而非创建嵌套目录。
- **修复：** 拆分 relPath 为路径段，逐级 `findFile`/`createDirectory` 定位到叶子目录，再 `createFile` 文件名。

#### P0-6 cover.jpg / artist.jpg 扁平 relPath，多专辑互相覆盖
- **文件：** `SongExporter.kt:83,87`
- **问题：** 所有专辑封面 relPath 都是 `"cover.jpg"`，所有艺术家封面都是 `"artist.jpg"`。增量判定时首个匹配后后续全部跳过；SAF/File 写入时互相覆盖。
- **修复：** relPath 应含 `"$artist/$album/cover.jpg"` 路径前缀，与音频文件同级。

### 本地音乐改造（1 项）

#### P0-7 `scanUsbDevice()` 删除全部 USB 歌曲而非仅目标设备
- **文件：** `LocalMusicRepository.kt:170-190`
- **问题：** 删除条件 `.filter { it.storageType == StorageType.USB.name }` 删除所有 USB 歌曲，而非仅目标设备路径下的。多 USB 设备时对 A 调用 `scanUsbDevice(A.path)` 会删除 A+B 的全部 USB 索引，然后只插入 A 的歌曲，B 的索引丢失。
- **修复：** 删除条件加路径前缀过滤：`.filter { it.path.startsWith(devicePath) }`。

### 播放器模块（3 项）

#### P0-8 AndroidManifest 缺少 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限
- **文件：** `AndroidManifest.xml` / `BatteryOptimizationHelper.kt:47`
- **问题：** 调用 `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 但 Manifest 未声明权限。`startActivity()` 抛 `SecurityException` 被 catch 兜住，电池白名单引导对话框永远无法弹出。
- **修复：** Manifest 添加 `<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />`。

#### P0-9 MediaLibraryTree 可播放项缺少 `setUri()`，Android Auto 无法播放
- **文件：** `MediaLibraryTree.kt:75-88`
- **问题：** `getQueueItems()` 构建的 MediaItem 只设了 `setMediaId` 和 metadata，没有 `setUri()`。Android Auto 用户点击树中歌曲时 ExoPlayer 收到无 URI 的 MediaItem，无法播放。
- **修复：** 添加 `builder.setUri(Uri.parse(song.streamUrl))`（注意网络歌曲 streamUrl 可能为空，需判空）。

#### P0-10 PlaybackService `exported="false"` 导致 Android Auto 无法连接
- **文件：** `AndroidManifest.xml:65`
- **问题：** Android Auto/Wear OS 是独立进程的 companion app，通过 `MediaController` 跨进程绑定服务。`exported="false"` 阻止跨进程绑定，Auto 根本发现不了该媒体服务。
- **修复：** 改为 `android:exported="true"`。

### 存储监听（1 项）

#### P0-11 `registerReceiver` 缺少 `RECEIVER_NOT_EXPORTED`，API 33+ 崩溃
- **文件：** `StorageGuard.kt:101` / `StorageMonitor.kt:75`
- **问题：** targetSdk = 34，Android 13+（API 33+）要求动态注册 Receiver 时指定 `RECEIVER_EXPORTED` 或 `RECEIVER_NOT_EXPORTED`。当前两处 `registerReceiver(receiver, filter)` 均无标志，API 33+ 设备抛 `SecurityException`。StorageGuard 被 `runCatching` 包裹不会崩溃但广播注册失败；StorageMonitor 无保护会直接崩溃。
- **修复：**
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
} else {
    context.registerReceiver(receiver, filter)
}
```

### UI 层（1 项）

#### P0-12 下载按钮对新可下载歌曲永不显示
- **文件：** `UnifiedSongRow.kt:99-100,267`
- **问题：** 所有 16 处调用点统一用 `downloadStates[song.downloadKey] ?: DownloadState.None` 作为 fallback。`SongDownloadManager._downloadStates` 初始为空 Map，仅从 DB 恢复 `Completed` 记录，从不预填充 `Idle` 状态。未下载过的歌曲在 Map 中无 key → fallback 到 `None` → `if (onDownload != null && downloadState !is DownloadState.None)` 条件为 false → ⬇ 按钮不渲染。`DownloadState.Idle` 从未被任何代码路径写入。
- **修复：** 在各调用点将 fallback 改为 `?: if (isDownloadableSong(song)) DownloadState.Idle else DownloadState.None`。

---

## P1 — 严重问题（19 项）

### 下载模块（7 项）

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| P1-1 | `SongDownloadManager.kt:151` | `catch (e: Exception)` 吞掉 `CancellationException`，违反结构化并发 | 通用 catch 前加 `catch (e: CancellationException) { throw e }` |
| P1-2 | `SongDownloadManager.kt:77-81,140-198` | enqueue 路径无去重/幂等检查，重复入队产生重复文件 | `executeDownload` 入口加 COMPLETED/DOWNLOADING/dedupe 检查 |
| P1-3 | `SongDownloadManager.kt:162` | 404 检测用字符串 `contains("404")`，`NO_RETRY_CODES` 为死代码，403 仍重试 | 传递 HTTP 状态码做精确判定 |
| P1-4 | `SongDownloadManager.kt:331-339` | `cancelAll` 仅更新 DB 状态，不取消进行中的下载协程 | 用 Job 跟踪当前下载，`cancelAll` 中 `currentDownloadJob?.cancel()` |
| P1-5 | `SongDownloadManager.kt:230-238` | 崩溃在 rename 后/DB 更新前 → 最终文件变孤儿 | `recoverAfterCrash` 扫描 DOWNLOADING/FAILED 记录，检查最终文件是否已存在 |
| P1-6 | `proguard-rules.pro:42-45` | jaudiotagger 缺少 `-keep` 规则，release 构建可能运行时崩溃 | 添加 `-keep class org.jaudiotagger.** { *; }` |
| P1-7 | `AutoDownloadController.kt:40` | 触发时机为"切歌即触发"，缺少"播放≥5s"前置条件 | 在 `onSongChanged` 中 `delay(5000)` 后检查 `currentSong == song` |

### 导出模块（1 项）

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| P1-8 | `SongExporter.kt:44-45` | `cancelRequested`/`isActive` 非线程安全 | 加 `@Volatile` |

### 本地音乐改造（2 项）

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| P1-9 | `CoverUrlPersistentCache.kt:56,103-109` | 每次 `put()` 全量写文件，无批量/防抖 | 暴露 `putAll` 批量接口，或用 debounce 协程延迟落盘 |
| P1-10 | `CoverUrlPersistentCache.kt:103-109` | `save()` 非线程安全，并发写可损坏文件 | `save()` 加 `synchronized` 或原子写（`.tmp` → rename） |

### 播放器模块（4 项）

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| P1-11 | `PlayerManager.kt:1240,994,1080` | `restoreQueue`/`syncAndPlayCurrent`/`addToQueue(单个)` 漏改 `buildMediaItem` | 三处统一改为 `buildMediaItem(song, song.streamUrl ?: "")` |
| P1-12 | `PlayerManager.kt:746` | `buildMediaItem` 用 `streamUrl` 作 mediaId，与 MediaLibraryTree 的 `song.id` 不一致 | `buildMediaItem` 改用 `song.id` 作 mediaId |
| P1-13 | `CoilBitmapLoader.kt:32-33` | `drawable.toBitmap()` 在主线程执行，无尺寸约束，大封面 OOM 风险 | ImageRequest.Builder 加 `.size(96, 96)` |
| P1-14 | `PlaybackService.kt:150-161` | `onGetChildren` 忽略分页参数，大队列触发 `TransactionTooLargeException` | `getChildren` 增加分页参数 |

### UI 层（5 项）

| # | 文件 | 问题 | 修复建议 |
|---|------|------|---------|
| P1-15 | `MainViewModel.kt:4228-4241` | `downloadSong()` 绕过 `downloadNow()` 的安全检查（总开关/可下载性/去重） | 改为调用 `downloadNow()` 或在 `enqueue()` 入队前补做检查 |
| P1-16 | `UnifiedSongRow.kt:273,278` | Completed ✓ 按钮是死按钮，`onDownload` 对 Completed 状态直接 return | 对 Completed 状态弹出 `ConfirmDialog` → `deleteDownload()` |
| P1-17 | `MainViewModel.kt:4252,4300` | `clearAllDownloads()` / `deleteDownload()` 无 UI 入口，ConfirmDialog 全局零调用 | SettingsScreen 增加"清空所有下载"按钮 |
| P1-18 | `ConfirmDialog.kt:39-113` | 已实现但全局零调用，二次确认设计未接通 | 接入删除下载流程 |
| P1-19 | `MainViewModel.kt:962-972` | 下载完成监听协程在 `Downloading(progress)` 高频更新时无谓 count 遍历 | 加 `.map { count }.distinctUntilChanged()` |

---

## P2 — 建议改进（33 项，按模块分组）

### 下载模块（18 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P2-1 | `SongDownloadManager.kt:170` | 重试耗尽后不清理 `.part` 临时文件 |
| P2-2 | `SongDownloadManager.kt:204` | 空间复检用 `hasRoomFor(0)` 而非剩余下载量 |
| P2-3 | `SongDownloadManager.kt:272` | `downloadFile` 未设置 User-Agent |
| P2-4 | `SongDownloadManager.kt:232-238` | rename 失败后的 copyTo 非原子，崩溃可留半截文件 |
| P2-5 | `SongDownloadManager.kt:278` | Content-Length 校验缺失 |
| P2-6 | `DownloadRepository.kt:84` | `reindexFromDisk` 将所有 COMPLETED 路径加载到内存 Set |
| P2-7 | `DownloadRepository.kt:97-98` | reindex 的 songKey 与 `Song.downloadKey` 不在同一键空间 |
| P2-8 | `StorageGuard.kt:49` | `lastFullNotifyAt` 非线程安全 |
| P2-9 | `StorageGuard.kt:83` | `start()` 中 `availableBytes()` 在主线程同步执行 |
| P2-10 | `DownloadPathBuilder.kt:70-78` | `uniqueFile` 存在 TOCTOU 竞态 |
| P2-11 | `StreamUrlResolver.kt:27` | `RADIO_SOURCE` 常量重复定义 |
| P2-12 | `StreamUrlResolver.kt:35-37` | 百度源与网络源使用相同的解析函数 |
| P2-13 | `MediaTagWriter.kt:116` | `scaleToMaxEdge` 创建新 Bitmap 后未回收源 Bitmap |
| P2-14 | `MediaTagWriter.kt:42` | `setId3v23DefaultTextEncoding(1.toByte())` 使用魔法数字 |
| P2-15 | `CoverFileWriter.kt:76-78` | `resolveArtistCoverUrl` 可能阻塞 IO 线程 |
| P2-16 | `DownloadDatabase.kt:29` | `exportSchema = false` 不利于迁移验证 |
| P2-17 | `DownloadSongDao.kt` | SQL 查询中硬编码状态字符串 |
| P2-18 | `DownloadSongEntity.kt:22` | `songKey` 同时为 PrimaryKey 和唯一 Index，索引冗余 |

### 导出模块（1 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P2-19 | `SongExporter.kt:36` | 8KB 复制缓冲偏小，建议 64KB |

### 本地音乐改造（4 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P2-20 | `LocalMusicRepository.kt:97-121` | contentUri 去重无法消除 MediaStore ↔ Download 重复 |
| P2-21 | `LocalMusicRepository.kt:116-120` | DOWNLOAD→INTERNAL 去重 filter 逻辑反直觉 |
| P2-22 | `StorageMonitor.kt:59-61` | 挂载事件对所有 USB 设备发射而非仅新挂载设备 |
| P2-23 | `CoverUrlPersistentCache.kt:82-87` | `evictOldest()` 非真正 LRU，接近随机淘汰 |

### 播放器模块（4 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P2-24 | `CoilBitmapLoader.kt:39` | Future 取消时 Coil 请求未取消 |
| P2-25 | `MediaLibraryTree.kt:71-108` | `getQueueItems()` 和 `findInQueue()` 存在重复代码 |
| P2-26 | `MediaLibraryTree.kt:18` | TAG 常量声明但从未使用 |
| P2-27 | `PlaybackService.kt:211-224` | `onTaskRemoved` 暂停态下 swiped 即停服务 |

### UI 层（5 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P2-28 | `UnifiedSongRow.kt:271` | Downloading 状态不显示进度百分比 |
| P2-29 | `NetdiskScreen.kt:77-79` | 缩进丢失（4 空格 → 0 空格） |
| P2-30 | `MineScreen.kt:426-500` | `RecentPane` 是死代码 |
| P2-31 | `AppRoot.kt:807` | `collectAsState()` 嵌入参数未提升 |
| P2-32 | `DownloadKeys.kt:36` | `dedupeKey` 用 `\|` 连接，标题含 `\|` 产生歧义碰撞 |

### 数据层（1 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P2-33 | `DownloadKeys.kt:39` | `RADIO_SOURCE` 常量重复（同 P2-11） |

---

## P3 — 微调（12 项，按模块分组）

### 下载模块（2 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P3-1 | `ExportRecordEntity.kt:47-48` | `@Insert(onConflict = REPLACE)` 与 `@Upsert` 风格不一致 |
| P3-2 | `AutoDownloadController.kt:71-76` | `estimateFromSong` 与 SongDownloadManager 重复代码 |

### 本地音乐改造（5 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P3-3 | `LocalMusicRepository.kt:116-122` | `mounted` 判断存在死分支（else 不可达） |
| P3-4 | `MusicScanner.kt:135` | 每个目录 `.nomedia` 检查产生额外 I/O |
| P3-5 | `ArtistCoverResolver.kt:228,261` | `replace("http://", "https://")` 替换所有出现而非仅前缀 |
| P3-6 | `ArtistCoverResolver.kt:104-108` | 每次更新 O(n) 查找 + 列表拷贝 |
| P3-7 | `MusicMerger.kt:57,136` | `lowercase()` 未指定 `Locale.ROOT` |

### 播放器模块（3 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P3-8 | `PlayerManager.kt:743` | `setRecordingYear` 可同时设 `setReleaseYear` 提升兼容性 |
| P3-9 | `BatteryOptimizationHelper.kt:87` | 文件末尾无换行符 |
| P3-10 | `PlaybackService.kt:126-163` | Session Callback 未校验 controller 来源 |

### UI 层（2 项）

| # | 文件:行号 | 问题 |
|---|----------|------|
| P3-11 | `UnifiedSongRow.kt:268-276` | `Triple<String, Color, Boolean>` 语义不明，应用 data class |
| P3-12 | `UnifiedSongRow.kt:104-131` | MODE_CARD / MODE_COMPACT 静默丢弃 downloadState 参数 |

---

## AGENTS.md 约束检查

| 约束 | 状态 | 说明 |
|------|------|------|
| GBK 编码处理 | ⚠️ 间接相关 | `DownloadPathBuilder.sanitize` 不处理 mojibake 字符，但属数据源问题 |
| TinyPinyin（非 ICU） | ✅ 未受影响 | 本期改动未涉及拼音搜索 |
| Cleartext traffic | ✅ 合规 | 下载模块通过 `usesCleartextTraffic=true` 支持 NAS HTTP |
| Leanback required | ✅ 合规 | UI 层 D-Pad focusable/clickable 处理正确 |
| ProGuard R8 规则 | ⚠️ 需补充 | `com.nasmusic.tv.backend.**` 已覆盖 Entity/State；**jaudiotagger 缺 `-keep`（P1-6）** |
| 多 ABI | ✅ 合规 | arm64-v8a / armeabi-v7a / x86_64 配置正确 |
| 手动 DI 模式 | ✅ 合规 | 所有组件在 `NasMusicApp.onCreate` 实例化，无 DI 框架 |
| BackendAdapter 接口 | ✅ 合规 | `StreamUrlResolver` 用 `adapter: () -> BackendAdapter?` 适配 nullable 后端 |
| 无 Jetpack Navigation | ✅ 合规 | 仍为手动 `when(Screen)` 路由 |
| Media3 ExoPlayer | ✅ 合规 | MediaLibraryService + 1000ms Handler 轮询不变 |

---

## 开发方案一致性核对

| 任务 | 实现状态 | 审查发现 |
|------|---------|---------|
| 0 Spike jaudiotagger | ✅ | ⚠️ P1-6：ProGuard `-keep` 缺失 |
| 1 DownloadDatabase + Entity + DAO + Repository | ✅ | P2 小问题：exportSchema=false、DAO 硬编码状态字符串 |
| 2 StorageUtils + StorageMonitor | ✅ | ⚠️ P0-11：registerReceiver 缺 RECEIVER_NOT_EXPORTED |
| 3 StorageGuard | ✅ | P2：lastFullNotifyAt 非线程安全、start() 主线程 StatFs |
| 4 DownloadPathBuilder | ✅ PASS | 25 个测试覆盖，清洗/截断/冲突逻辑正确 |
| 5 StreamUrlResolver | ✅ | P2：RADIO_SOURCE 重复、百度源无差异化 |
| 6 MediaTagWriter | ✅ | ⚠️ P1-6：jaudiotagger 缺 ProGuard keep |
| 7 CoverFileWriter | ✅ PASS | 优先级链合理，失败不阻断下载 |
| 8 SongDownloadManager | ✅ | ⚠️ **P0-1 + 5 个 P1**：并发安全、CancellationException、去重缺失、404 检测、cancelAll、崩溃恢复 |
| 9 设置项 AppSettings + AppPreferences | ✅ PASS | — |
| 10 设置页 UI + AppRoot 参数链 | ✅ | P2：collectAsState 未提升；**P1-17：缺少"清空所有下载"入口** |
| 11 AutoDownloadController | ✅ | ⚠️ **P1-7：缺少"播放≥5s"前置条件** |
| 12 UnifiedSongRow 改造 | ✅ | ⚠️ **P0-12：Idle 状态从未写入，⬇ 按钮不显示；P1-16：Completed ✓ 死按钮** |
| 13 本地曲库统一改造 | ✅ | ⚠️ **P0-2：fullScan 遗漏下载目录；P0-7：scanUsbDevice 删全部 USB 歌** |
| 14 下载完成即时入库 + 清除/删除 | ⚠️ | ConfirmDialog 已建但零调用，clearAllDownloads/deleteDownload 无 UI 入口 |
| 15 导出到外接设备 | ⚠️ | **P0-3/4/5/6：SAF 权限未持久化、targetFile 空实现、嵌套路径不支持、扁平封面覆盖** |
| 16 ProGuard / release 构建验证 | ✅ | 构建通过，但 jaudiotagger keep 规则缺失 |
| 17 单元 + 集成测试 | ✅ | 38 个测试通过 |
| 18 设备测试与调优 | ❌ | 需实机硬件，代码环境无法执行 |
| 19 文档更新 | ✅ | technical-overview §10.102 + CHANGELOG v2.26.32 |

---

## 修复优先级建议

### 立即修复（P0，阻断功能）

按影响范围分组：

**组 A — 下载/本地曲库数据完整性（3 项）**
1. P0-2：`fullScan()` 改用 `buildScannedList()`
2. P0-7：`scanUsbDevice()` 加路径前缀过滤
3. P0-12：UnifiedSongRow fallback 改为区分可下载性

**组 B — 导出功能可用性（4 项）**
4. P0-3：SAF `takePersistableUriPermission` 调用
5. P0-4：`targetFile()` 实现
6. P0-5：SAF 嵌套路径逐级创建目录
7. P0-6：cover.jpg/artist.jpg relPath 加路径前缀

**组 C — 播放器/Android Auto 端到端可用性（3 项）**
8. P0-8：Manifest 添加 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限
9. P0-9：MediaLibraryTree 添加 `setUri()`
10. P0-10：PlaybackService 改 `exported="true"`

**组 D — API 33+ 崩溃（2 项，可合并修复）**
11. P0-11：StorageGuard + StorageMonitor `registerReceiver` 加 `RECEIVER_NOT_EXPORTED`
12. P0-1：移除或重构 `downloadNow`（当前死代码，优先级可降）

### 尽快修复（P1，影响健壮性）

13. P1-7：AutoDownloadController 加"播放≥5s"前置条件
14. P1-1~5：SongDownloadManager 并发安全修复
15. P1-6：proguard-rules.pro 添加 jaudiotagger keep 规则
16. P1-15~18：UI 删除下载流程接通（Completed ✓ → ConfirmDialog → deleteDownload）
17. P1-11~14：播放器元数据/mediaId/分页修复

---

## 整体评价

**下载模块核心（任务 0-8）：** 架构设计扎实——串行队列 + 状态机 + 崩溃恢复 + 空间守护的设计思路正确，DownloadPathBuilder 的 25 个测试覆盖到位。但 `SongDownloadManager` 有多个并发安全缺陷（P0-1 + P1-1~5），在单消费者保证被破坏时会产生竞态。`AutoDownloadController` 缺少方案要求的"播放≥5s"前置条件。

**导出模块（任务 15）：** 分层设计合理（Coordinator → Exporter → PermissionHelper），SAF→File 降级链思路正确，但 `SongExporter` 有 3 个 P0 实现缺陷使 SAF 和 File 路径都不可用。**模块在当前状态下无法完成一次成功的导出**。

**本地音乐改造（任务 13）：** 增量扫描的防护逻辑到位（空结果保护、USB 未挂载不删除、distinctBy 去重、分批删除），但 `fullScan()` 遗漏下载目录和 `scanUsbDevice()` 全量删 USB 是两个严重数据丢失路径。`StorageMonitor` 在 API 34+ 会崩溃。

**播放器模块（任务 7ba3c31/b852586/1fc7f7a）：** 功能方向正确（MediaMetadata 填充 → 系统级显示、电池保活 → 后台稳定性、媒体树 → Android Auto），但 3 个 P0 均属"写了功能但关键配置/参数遗漏导致无法工作"——Manifest 权限缺失、mediaItem 缺 URI、Service 未 exported。**端到端验证不足**。

**UI 层（任务 10/12/14）：** 参数链传递完整（14 文件 16 处调用点一致），TV D-Pad 适配合规。但 `DownloadState.Idle` 从未被写入导致下载按钮不显示是阻断性缺陷，Completed ✓ 死按钮和 ConfirmDialog 零调用使删除流程完全未接通。

**共同特征：** 代码层面 18/19 任务"完成"的判定基于编译通过 + 单元测试通过，但缺少集成层面验证。多个 P0 属于"代码写了但接线没通"类型（Idle 未写入、ConfirmDialog 零调用、SAF 权限未持久化、Manifest 缺权限/缺 exported）。**建议在修复 P0 后，优先在真机或 Android Auto Desktop Head Unit 上做一次端到端验证，再进入任务 18 的 16 个用例测试。**
