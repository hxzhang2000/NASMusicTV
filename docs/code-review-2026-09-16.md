# NASMusicTV 代码审查报告（2026-09-16）

**审查基线**：commit `693d43c`（v2.32.5, code 144）
**对比基线**：`97cd181`（2026-09-07 上次全量审查基线），其间 78 个提交、257 文件变更（+32,677 / -9,859 行）
**审查方式**：全量精读主源码约 60k 行 Kotlin（player / backend 全部 / network / net / data / lyrics / util / visualizer 核心 / UI 状态层 / 装配层 / 构建配置），抽查部分 UI screens。

---

## 问题统计总览

| 严重级别 | 数量 | 说明 |
|---------|------|------|
| **P0 — 阻断** | 2 | 功能完全失效或数据必然异常，应立即修复 |
| **P1 — 严重** | 7 | 功能错误 / 并发缺陷 / 数据一致性，建议尽快修复（编号 9 项 → 移出 P1-3 后 8 项 → 撤回 P1-9 后 7 项：P1-3 经产品裁定为有意设计、不修；P1-9 经核验为误报、撤回） |
| **P2 — 建议** | 11 | 值得修复但不阻断核心功能 |
| **P3 — 微调** | 5 | 代码质量 / 可维护性改进 |

---

## P0 — 阻断问题（2 项）

### P0-1 下载链路未注入后端认证头，飞牛后端的下载必失败
- **文件**：`backend/download/SongDownloadManager.kt:236-241`（downloadFile）、`StreamUrlResolver.kt:63-70`
- **问题**：飞牛是第一个需要 `Authorization: <userToken>` 请求头的后端，播放链路（ExoPlayer / Coil）通过 `BaiduHttpDataSourceFactory` 拦截器 + `BackendAuthHeaders.forHost(host)` 注入了认证头（F-2 修复），**但下载链路使用独立的 `OkHttpClient`（`SongDownloadManager` 构造参数默认值自建），没有任何拦截器**。
- **证据**：
  ```kotlin
  // SongDownloadManager.kt 构造参数
  private val client: OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  ```
  `downloadFile()` 直接 `client.newCall(req)`。飞牛的 `track/stream?guid=` 端点要求认证头，无头请求将 401，重试 3 次后失败。`headContentLength()` 同样无头。
- **影响**：飞牛后端的全部手动/自动下载 100% 失败；同链路的 HQ 人声分离下载（`HqSeparationOrchestrator.resolveInputPath`）也用独立裸 client，同样 401。
- **修复建议**：给下载 client 添加与播放链路相同的 host 精确匹配拦截器（复用 `BackendAuthHeaders.forHost`）；或让 `NasMusicApp` 构造一个共享的"下载用 OkHttpClient"注入。

### P0-2 `recoverAfterCrash` 孤儿恢复分支永不命中，上次修复无效
- **文件**：`backend/download/SongDownloadManager.kt:444-452`（recoverAfterCrash 分支 2）
- **问题**：上次审查 P1-5 修复"崩溃在 rename 后、DB 提交前 → 检查最终文件是否已存在"。但恢复代码读的是 `entity.audioPath`，而 **DOWNLOADING 记录在创建时 `audioPath` 恒为 null**（`newDownloadingEntity` 只填 `tmpPath`，`audioPath` 只在 COMPLETED 记录里写）。
- **证据**：
  ```kotlin
  // newDownloadingEntity (DownloadRepository.kt)
  audioPath = null,        // 从未填写
  tmpPath = tmpPath,       // 只有 tmp
  status = DOWNLOADING
  ```
  ```kotlin
  // recoverAfterCrash
  repo.getUnfinished().forEach { entity ->
      val finalPath = entity.audioPath          // 恒为 null → 分支永不命中
      if (finalPath != null && File(finalPath).exists()) { ... }
  ```
- **影响**：孤儿文件恢复（真正下载完成但 DB 未提交的场景）永不生效，这些文件永远留在磁盘上成为不可见孤儿，且用户重新下载会生成 ` (2)` 重复文件。
- **修复建议**：DOWNLOADING 记录增加 `finalPath` 字段（构建路径时已知），或恢复时用 `paths.buildFinalPath(song)` 反推。

---

## P1 — 严重问题（7 项有效；另 2 项移出：P1-3 产品裁定 · P1-9 误报撤回）

### P1-1 `fullScan()` 空扫描保护失效，注释与实现矛盾
- **文件**：`backend/local/LocalMusicRepository.kt:159-175`
- **问题**：注释声称"扫描失败直接抛回空结果，旧索引保持不变"（B3 修复），但实现是 `val scanned = buildScannedList(); dao.deleteAll(); dao.insertAll(scanned)`。`buildScannedList()` 在 MediaStore 查询异常时**吞异常返回空列表**（MusicScanner.kt:88-91 catch 后返回已收集的 songs），于是 `fullScan` 继续走 `deleteAll()` → 插入空 → **整个本地曲库被清空**。上次审查的 P0 修复（B3）在 `fullScan` 重构时丢失了。
- **修复建议**：`scanned.isEmpty()` 时直接返回旧缓存，不执行 deleteAll。

### P1-2 下载失败/空间不足的用户通知被降级为纯日志
- **文件**：`NasMusicApp.kt:355-359`（onNotify lambda）
- **问题**：`SongDownloadManager` 与 `AutoDownloadController` 的 `onNotify` 回调在装配时只做了 `AppLog.d(...)`，注释写着"通过 MainViewModel 的 errorMessage 通道提示"，但**实际从未注册**——仓库中无任何代码把这些消息写入 UI 通道。
- **影响**：下载失败、存储空间不足、配额已满等提示用户全部看不到，表现为"点下载没反应"。
- **接线点确认（2026-09-16）**：`DownloadViewModel.message`（`DownloadViewModel.kt:197-207`）虽然存在，但**全仓库无 UI 消费方**——`message` 的消费方在 `MainActivity` 侧只订阅了 `viewModel.errorMessage`（`MainActivity.kt:205,268`）。因此可靠通道是 `MainViewModel.showError()` → `errorMessage`，`DownloadViewModel.message` 不可用。
- **修复建议**：装配层把 `onNotify` 广播到进程级 `SharedFlow`，由 `MainViewModel` 收集后转 `showError`。
- **本轮处置**：已按上述通道实现（工作区已改、已提交 `ac7bcdb`）——`NasMusicApp.kt:112-113` 新增 `downloadNotifyMessage`（replay=1）、`:365-370` 与 `:424-428` 两处 notify 均转发 → `MainViewModel.kt:2299-2307` 收集后调 `showError`。

### ~~P1-3~~ 手动下载绕过 dedupeKey 去重 —— 已裁定为有意设计，不修
- **文件**：`ui/viewmodel/DownloadViewModel.kt:56-75`（downloadSong）
- **原发现**：手动下载只查内存状态不查 `findCompletedByDedupe`，同一首歌跨平台可重复下载。
- **产品裁定（2026-09-16）**：**有意设计，无需修改**——用户手动点击下载是明确意图，跨平台重复下载是用户自己的选择；dedupe 拦截只应作用于自动下载路径（AutoDownloadController 已有检查），不应拦截手动操作。
- **后续动作**：无。此条目仅留档防止后续审查重复上报。

### P1-4 空间预估公式单位错 1000 倍
- **文件**：`SongDownloadManager.kt:349-354`（estimateFromSong）与 `AutoDownloadController.kt:126-131`
- **问题**：`song.durationMs / 1000 * song.bitrate / 8`——bitrate 单位是 kbps（Jellyfin 除以 1000 存入、Navidrome/Subsonic 直接 kbps），结果单位是 **KB**，却被当字节比较。160kbps × 240s ÷ 8 = 4800（KB）当作 4.8KB → 预检形同虚设。缓解因素：下载中每 8KB 块有 `hasRoomFor(0)` 复查 + Content-Length 校验兜底，不会写爆磁盘，但前置提示与 AutoDownload 空间判断失效。
- **修复建议**：`* 1024L` 补单位。

### P1-5 `cancelAll` 重启 loop 造成新旧 loop 并存，破坏串行队列保证
- **文件**：`backend/download/SongDownloadManager.kt:432-450`（cancelAll）、`:111-114`（loop 启动）
- **问题（2026-09-16 复核，定性修正）**：原描述"与新入队存在竞态窗口"不够精确。真正的缺陷在 `cancelAll` 末尾的 `startLoop()`：它先 `currentDownloadJob?.cancel()` 再**立即重启**，而旧 loop 协程此刻通常正**阻塞在 OkHttp 的 socket 读上**——协程 cancel 是协作式的、不会中断阻塞 IO，于是旧 loop 尚未退出、新 loop 已开始消费，**两个 `executeDownload` 短暂并存**，直接破坏"串行队列只有一个消费者"的保证（两个 .part 并发写、`currentCall` 互相覆盖）。原先担心的"enqueue 落在标记 FAILED 与 startLoop 之间被误杀"只是同一处代码的次要表现。
- **修复建议（已定稿）**：改为 `call.cancel()`（中断阻塞 socket 读）+ `cancelRequested` 标志（进行中任务在重试判定处直接落 FAILED、不再重试）+ drain 两条排队队列；**loop 常驻唯一实例、不再重启**。
- **本轮处置**：已按此实现（工作区已改、已提交 `ac7bcdb`）——`cancelRequested` @Volatile（`:100-101`）、loop 在 `init` 常驻启动且 `startLoop()` 已删除（`:111-114`）、重试前判 `cancelRequested`（`:188-193`）、`cancelAll` 四步改造（`:432-450`）。

### P1-6 SAF 增量导出判定失效
- **文件**：`backend/export/SongExporter.kt:222-227` + `targetFile():313-319`
- **问题**：`shouldOverwrite` 里 `targetFile` 仅在 `ExportRoot.File` 分支返回非 null，SAF 分支恒 null → `shouldOverwrite` 恒 true → SAF 路径的"目标文件已存在且同长则跳过"逻辑失效，只剩 export_records 过滤。
- **影响**：清掉 export_records 后重新导出，SAF 路径上同名不同长文件被无条件覆盖。
- **修复建议**：SAF 分支用 `resolveChildDoc` 反查目标 DocumentFile 长度对比。

### P1-7 `next()` REPEAT_ONE / 队尾 REPEAT_ALL 分支绕过 T3 三元组同帧发布
- **文件**：`player/PlayerManager.kt:774-796`（next REPEAT_ONE）、`onPlaybackEnded`
- **问题**：REPEAT_ONE 分支只 `update { copy(currentIndex = nextIndex) }`，不更新 currentSong，依赖后续 onMediaItemTransition 补齐——队列快照与 ExoPlayer 内部队列短暂不一致时 UI 读到"新索引 + 旧歌名"中间态。`onPlaybackEnded` 的 REPEAT_ALL 用 `playQueue(queue, 0)` 整队重放，queue 已被改动时会回滚用户操作。
- **修复建议**：update 中同时写 currentSong；REPEAT_ALL 重放改为 seekTo(0)。

### P1-8 `RemoteControlServer.handleAdd` 反序列化不可信 JSON 直接入队
- **文件**：`net/RemoteControlServer.kt:170-176`
- **问题**：`/api/queue/add` 把手机提交的任意 JSON 反序列化为 Song 后直接入队，无字段校验。Song 含 `streamUrl`/`path`——LAN 内任何设备可注入任意 URI。服务器绑定 0.0.0.0。
- **修复建议**：校验必填字段与 URI scheme 白名单。

### ~~P1-9~~ `BackupTransferServer` 上传路径内存安全 —— 已核验为误报，撤回
- **文件**：`net/BackupTransferServer.kt:343-397`（handleUpload）
- **原发现**：担心上传路径仍走 `parseBody(files)`（NanoHTTPD 全量进内存），32MB 上限判定发生在读入之后，大 body 仍会先撑内存。
- **核验结论（2026-09-16）**：**误报，撤回**。`handleUpload` 并未使用 `parseBody`（注释明确说明为规避部分 ROM 的 `Charset.defaultCharset()` 非 UTF-8 才改为直读 `inputStream`）。它先按 `content-length` 预检（`:348-356`，`> MAX_UPLOAD_BYTES` 直接拒绝），再以 16KB 分块累积读取、`totalRead > MAX_UPLOAD_BYTES` 时**立即中止**（`:359-371`）——上限判定发生在读入过程中而非之后；且刻意不按 Content-Length 预分配数组（防客户端谎报导致巨型 `ByteArray` OOM）。内存安全已达标，无需修复。
- **后续动作**：无。本条留档防止后续审查重复上报。

---

## P2 — 建议修复（11 项）

| # | 文件 | 问题 | 建议 |
|---|------|------|------|
| P2-1 | `SongDownloadManager.kt:214-219` | onProgress 每 8KB 块全 Map 拷贝 + StateFlow 发布，与"每 512KB"注释不符 | 按 PROGRESS_STEP 节流 |
| P2-2 | `LocalMusicRepository.kt:199-217` | scanUsbDevice 删除判定 `path.startsWith(devicePath)`，但 path 存 `file://` URI → 前缀永不匹配，删除集恒空，陈旧条目残留 | 前缀补 `file://` |
| P2-3 | `NasMusicApp.kt:404-407` | 下载入库 `albumId = mediaStoreId`（路径哈希当专辑 ID）→ albumart 封面必 404 | albumId 置 0 走内嵌封面 |
| P2-4 | `StorageMonitor.kt:52-70` | IntentFilter 对 USB ATTACHED 广播也加了 `addDataScheme("file")`，extras-only 广播可能被过滤 | scheme 只加给 MEDIA_* 动作 |
| P2-5 | `SongDownloadManager` `_downloadStates` | Map read-modify-write 非原子，与 cancelAll 并发可能丢状态 | 改 `_downloadStates.update { }` |
| P2-6 | `SmartRadioManager` stop/skip | stopInternal 清空集合后，在跑的 generateJob 以旧引用继续 emitBatch 回写 | 协程内校验自身是否仍是 currentJob |
| P2-7 | `FeiniuAdapter.fetchAllPages` | 以 `items.size() < size` 判断末页，服务端单页少发则静默截断 | 按 data.total 计算页数 |
| P2-8 | `FeiniuAdapter.loadAllTracks` | 缓存 miss 并发调用重复全量拉取（无单飞） | 加 refresh Mutex |
| P2-9 | `RemoteControlServer.handleSearch` | runBlocking + 10s 超时阻塞 worker 线程 | 记录慢查询，评估并发上限 |
| P2-10 | `PlayerManager.removeFromQueue` | 移除末尾当前项时 ENDED → next(mode) 可能跳到不该跳的歌 | 边界补测试 |
| P2-11 | `ModelTransferServer` | 上传无 SHA-256 校验（下载路径有）；part headers 无大小上限 | 补校验 + 限制 |

---

## P3 — 微调（5 项）

| # | 文件 | 问题 |
|---|------|------|
| P3-1 | `HqSeparationOrchestrator.finally` | saveOriginalFile(copy) 后立即 cleanupTempFile——当前安全，若未来改 rename 语义会破坏，建议注释锁死约定 |
| P3-2 | `DemucsSeparator.emit` | 每帧闭包调用 + 边界检查约 4200 万次/4min 曲目，`gi >= totalSamples` 可前置 |
| P3-3 | `CoverUrlPersistentCache.evictOldest` | ConcurrentHashMap 无序迭代"近似 LRU"实为随机淘汰 |
| P3-4 | `LocalMusicDatabase` v3 + 破坏性迁移 | 索引可重建可接受，建议 bump 时 schema JSON 入库留档 |
| P3-5 | `WeatherApi` WMO code 映射 | 51-77 段映射有重叠，极端 code 落错分支，对照 WMO 表修正 |

---

## 上次报告（2026-09-07）修复情况核对

| 上次编号 | 状态 |
|---------|------|
| P0-1 downloadNow 并发 | ✅ 已移除，统一走 enqueue |
| P0-2 fullScan 漏下载目录 | ✅ 已改 buildScannedList()（但引入本次 P1-1 退化） |
| P0-3 SAF 权限持久化 | ✅ takePersistableUriPermission 已加 |
| P0-4 targetFile 恒 null | ⚠️ File 路径已修；SAF 缺口仍在（本次 P1-6） |
| P0-5 SAF 嵌套 relPath | ✅ resolveChildDoc 逐级定位 |
| P0-6 封面扁平 relPath | ✅ 已带 artist/album 前缀 |
| P0-7 scanUsbDevice 误删 | ⚠️ 已加过滤但前缀不匹配（本次 P2-2，实际仍 no-op） |
| P0-8 电池优化权限 | ✅ Manifest 已声明 |
| P0-9 MediaLibraryTree 缺 setUri | ✅ 已设（null 守卫） |
| P0-10 PlaybackService exported | ✅ true + intent-filter |
| P0-11 RECEIVER_NOT_EXPORTED | ✅ 三处均已适配 |
| P0-12 下载按钮不显示 | ✅ fallback 已实现 |
| P1-6 jaudiotagger keep | ✅ proguard 已加 |
| P1-7 AutoDownload 5s 确认 | ✅ delay + currentSong 比对 |
| P1-8 cancelRequested @Volatile | ✅ 已加 |

---

## 本轮修复核对（2026-09-16）

> 本节记录本报告定稿后**同一轮落地的修复**。状态均经逐条对照源码核验（非仅凭提交信息）。改动已提交为 `ac7bcdb`，随 **v2.32.6**（versionCode 145）发布。
> 未列入者即未修复：`P2-7 / P2-8 / P2-9 / P2-10 / P2-11`、`P3-1 ~ P3-5`。
>
> **第二批（同日续做）**：上列 10 项已全部补齐，见下方表格下半部分。至此除 `P1-3`（产品裁定不修）
> 与 `P1-9`（误报撤回）外，本报告全部发现项均已处置。
>
> ⚠️ **附注（第二批开工时发现）**：首次执行 `assembleDebug` 时，暴露出**上一批（第一批）修复里的一处编译错误**——
> `SongDownloadManager.kt:530` 写作 `val baseName = if (entity.title.isBlank()) return null`，
> 把缺 `else` 的 `if` 当表达式用（Kotlin 编译错误）。已改为
> `if (entity.title.isBlank()) return null` + `val baseName = entity.title`。
> 这说明第一批修复当时**没有经过编译验证**，其"已修"状态应以本轮编译结果为准。
>
> ✅ **验证结果**
>
> ⚠️ **勘误（2026-09-16 提交前实测）**：本节初稿写「本机单测不可用、验证手段只有编译 + lint」，
> **该结论已过时且被实测推翻**。`testDebugUnitTest` 在本机**可以正常运行**：
>
> - `testDebugUnitTest --tests "*QueueRemovalTest"` → 6 例全绿（含「移除末尾当前项」核心场景）
> - `testDebugUnitTest`（全量）→ **518 例 / 0 失败 / 0 错误 / 0 跳过**（512 基线 + 本轮新增 6 例）
>
> 根因推断：此前的「test worker JVM 一启动即死（exit `268435466` = Windows `ERROR_BAD_ENVIRONMENT`）」
> 更可能是**卡死的 Gradle 守护进程持锁**所致（同日另一次提交记录里已出现「本机首次完整跑通
> `testDebugUnitTest`，512 例 0 失败」），而非本机永久性限制。`AGENTS.md` 中「单测在本机跑不起来」
> 的表述同样已过时，已同步更正。
>
> **因此本轮验证强度为「编译 + lint + 全量单测」，而非仅前两者。**
>
> 其余验证证据：
> - `assembleDebug lintDebug` → **BUILD SUCCESSFUL**，lint **0 error** / 257 warning
>   （基线 256，差值来自 `NewerVersionAvailable`/`GradleDependency` 这类网络相关的依赖版本告警波动），
>   改动文件上无新增告警。
> - `compileDebugUnitTestKotlin` 通过。注意 `assembleDebug` **不编译 test 源码**，
>   新增的 `QueueRemovalTest.kt` 必须靠这一步（或直接跑 `testDebugUnitTest`）才能覆盖到。
> - `app/schemas/` 下已落盘 `…LocalMusicDatabase/3.json`（version 3 / `local_songs` 17 字段 + 1 索引），需随代码入库。
> - 产物：`app/build/outputs/apk/debug/NASMusicTV-debug-v2-32-5.apk`。

| 编号 | 状态 | 核验证据（file:line） |
|------|------|----------------------|
| P0-1 下载链路未注入认证头 | ✅ 已修 | `SongDownloadManager.kt:62-75` 下载 client 加 `BackendAuthHeaders.forHost(host)` 拦截器（host 精确匹配，令牌不随 302 泄漏到第三方域） |
| P0-2 孤儿恢复分支永不命中 | ✅ 已修 | `SongDownloadManager.kt:525-548` 新增 `recoverFinalPathOrNull()`，按 artist/album/title 反推最终路径（含 ` (2..10)` 去重序号）；`:562` 改为 `entity.audioPath ?: recoverFinalPathOrNull(entity)` |
| P1-1 fullScan 空扫描清库 | ✅ 已修 | `LocalMusicRepository.kt:187-192` `scanned.isEmpty()` 时保留旧索引并 `loadFromCache()` |
| P1-2 下载通知不可见 | ✅ 已修 | `NasMusicApp.kt:112-113,365-370,424-428` → `MainViewModel.kt:2299-2307`（详见上文 P1-2 处置） |
| P1-4 空间预估差 1000 倍 | ✅ 已修 | `SongDownloadManager.kt:408-411`、`AutoDownloadController.kt:88-92` 两处均 `* 1024L` |
| P1-5 cancelAll 重启 loop | ✅ 已修 | `SongDownloadManager.kt:100-101,111-114,188-193,432-450`（详见上文 P1-5 处置） |
| P1-6 SAF 增量判定失效 | ✅ 已修 | `SongExporter.kt:222-232` `shouldOverwrite` 改为按 `currentRoot` 分支，SAF 用 `resolveChildDoc` 取长度比对；失效的 `targetFile()` 已删除 |
| P1-7 三元组不同帧发布 | ✅ 已修 | `PlayerManager.kt:826-856`（REPEAT_ONE 回卷/末首）、`:1107-1118`（`onPlaybackEnded` 的 REPEAT_ALL 改 `seekTo(0)`）均同帧写 `currentSong` |
| P1-8 `/api/queue/add` 注入 | ✅ 已修 | `RemoteControlServer.kt:153-172` 反序列化加 try/catch + title 非空 + URI scheme 白名单（http/https/content/file） |
| P2-1 onProgress 未按 512KB 节流 | ✅ 已修 | `SongDownloadManager.kt:241-250` 按 `PROGRESS_STEP` 节流，空间复查保持原节奏 |
| P2-2 scanUsbDevice 前缀不匹配 | ✅ 已修 | `LocalMusicRepository.kt:210-220` 同时匹配 `devicePath` 与 `file://$devicePath` 两种形态 |
| P2-3 下载入库 albumId 用路径哈希 | ✅ 已修 | `NasMusicApp.kt:382-385` `albumId = 0L` → `coverUrl` 返回 null → 走本地内嵌封面 |
| P2-4 USB 广播被 scheme 过滤 | ✅ 已修 | `StorageMonitor.kt:44-58,80-90` 拆成 media/usb 两个 `IntentFilter`（`file` scheme 只约束 MEDIA_*）；`stopListening` 相应 unregister 两次 |
| P2-5 `_downloadStates` 非原子写 | ✅ 已修 | 全文件 `_downloadStates.value = ...` 统一改为 `_downloadStates.update { }`（含 `clearAllStates`） |
| P2-6 SmartRadio 陈旧任务回写 | ✅ 已修 | `SmartRadioManager.kt:222-236` 以 `currentCoroutineContext()[Job].isActive` 为唯一准入判据（锁内二次确认；未用身份比较以避开 `generateJob` 赋值的时序竞态） |
| P2-7 Feiniu 分页静默截断 | ✅ 已修 | `FeiniuAdapter.kt:790`（`fetchAllPages`）改取整个 `data` 信封，末页按 `data.total` 判定（`total` 缺失才回退旧判据）；新增 `fetchPageEnvelope()`（`:835`），删掉只回 list 的 `fetchPageRaw()` |
| P2-8 Feiniu 全量拉取无单飞 | ✅ 已修 | `FeiniuAdapter.kt:152` 新增 `allTracksRefreshMutex`；`:488` `loadAllTracks()` 加锁 + **锁内重查缓存**（否则单飞退化为串行 N 次全量拉取） |
| P2-9 handleSearch 阻塞 worker | ✅ 已修 | `RemoteControlServer.kt:36,39` 新增 `MAX_CONCURRENT_SEARCHES=2` / `SLOW_SEARCH_MS=3000`；`:92` Semaphore；`:190` `handleSearch` 拿不到槽位直接 **503**（快速失败不排队）+ 慢查询耗时日志 |
| P2-10 removeFromQueue 末尾当前项 | ✅ 已修 | `PlayerManager.kt:961` `removeFromQueue` 抽纯函数 `computeQueueRemoval()`（`:1271`，结果类型 `QueueRemovalResult` `:1252`）+ 移除当前项时**显式 `seekTo` 对齐 ExoPlayer**（否则末尾项被移除会进 `STATE_ENDED`，被 `onPlaybackEnded` 的 REPEAT_ALL 误判成"到队尾"而 `seekTo(0)` 跳歌）；新增单测 `app/src/test/java/com/nasmusic/tv/player/QueueRemovalTest.kt`（6 例边界，**实测全绿**） |
| P2-11 模型上传缺完整性校验 | ✅ 已修 | `ModelTransferServer.kt:165-180` 上传后补 SHA-256 校验，复用 `ModelDownloadManager.EXPECTED_SHA256`（由 private 放宽为 internal）与同一份 `sha256Of()`（`:75`，从实例方法移到 companion）；`readPartHeaders`/`readLine` 加 8KB 总量 + 2KB 单行上限（常量 `:42,45`，实现 `:223,237`） |
| P3-1 saveOriginalFile(copy) 约定 | ✅ 已修 | `HqSeparationOrchestrator.kt:460-475` 就地锁死约定注释：`saveOriginalFile` 必须保持 copy 语义（否则紧随的 `cleanupTempFile` 会删掉已缓存的原唱） |
| P3-2 Demucs 逐帧边界检查 | ✅ 已修 | `DemucsSeparator.kt:365-377` 删掉 `emit` 内 `if (gi >= totalSamples) return`——三个循环上界已保证恒不命中（死分支，约 4200 万次/4min），`gi` 参数随之移除；末尾 pending 冲刷循环保留 `break` 守卫 |
| P3-3 封面缓存"随机淘汰" | ✅ 已修 | `CoverUrlPersistentCache.kt:42` 新增 `writtenAt` 写入时间表；`:147` `evictOldest()` 改为按写入时间排序淘汰（原 `ConcurrentHashMap.keys.take(n)` 迭代序与插入序无关＝随机淘汰）；`load`（`:161`）/ `importAll` / `clear` 同步维护 |
| P3-4 schema 未留档 | ✅ 已修 | `app/build.gradle.kts` 末尾新增 `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`——此前 `exportSchema = true` 但未配目录，Room 只打警告且**一个 schema 都不导出**；现落盘 `app/schemas/<DB>/<version>.json`，需随代码入库 |
| P3-5 WMO 映射错乱 | ✅ 已修 | `WeatherApi.kt:240` OWM→WMO 改回真 WMO 代码（雾霾 `20→45`、毛毛雨 `50→51`、雨 `60→61`、雪 `70→71`）——原先这四个值**落不进任何 `WeatherMood` 区间**，OpenWeatherMap 路径的天气电台一律回退 CLOUDY；`:298` `describeWeatherCode` 对照 WMO 表重写（原为自造区间，51~77 段被拉平、4~44 段全是死分支） |

---

## 总体评价

v2.26.32 → v2.32.5 这批变更（飞牛重写、认证头 provider 化、Demucs P0/P1 修复、T2/T3/L3 重构、lint 清零）整体质量明显高于上一批：**上次 12 个 P0 中 11 个已正确修复**，并发修复（Demucs 释放竞态、重采样与声道拆分）都有详细推理注释且逻辑正确，测试补充方向正确。

本轮新引入的风险集中在**跨链路一致性**：播放链路的认证头、空扫描保护、SAF 增量都存在"主链路修好了、旁路链路漏了"的模式（下载链路、导出链路）——手动下载不去重（P1-3）曾按同一模式上报，但已裁定为有意设计，不属于该风险。（建议把"新增全局机制（认证头/去重/扫描保护）时列出全部消费链路"作为 review checklist。）

优先修复顺序：**P0-1（下载 401）→ P0-2（孤儿恢复）→ P1-1（曲库清空风险）→ P1-2（通知不可见）**。（P1-3 经产品裁定为有意设计、不修；P1-9 经核验为误报、撤回——两条均已移出修复清单）

本轮 **P0（2 项）、P1（7 项）、P2（11 项）、P3（5 项）** 的修复已落地（见上文「本轮修复核对」），提交 `ac7bcdb`，随 **v2.32.6**（versionCode 145）发布。
