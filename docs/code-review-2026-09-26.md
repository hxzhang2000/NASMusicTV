# NASMusicTV 代码审查报告 — 未提交修复批次（v2.37.1）

> 版本：v1.1（2026-09-26）
> 审查对象：当前工作区未提交改动（34 个文件，~981 行增量，versionCode 163 / versionName 2.37.1）
> 审查范围：09-25 完整审查报告（`docs/NASMusicTV-代码审查报告-2026-09-25.html`）所列 17 条 High 的修复批次 + 同批夹带的非报告项修复
> 审查方式：3 路并行代码审查（后端/数据层、播放器/ViewModel、UI/可视化）+ 本机构建与单测门禁

---

## 一、执行摘要

| 维度 | 结论 |
|---|---|
| **编译** | ✅ `assembleDebug` 通过 |
| **单测** | ✅ `testDebugUnitTest --rerun-tasks` 通过：**1177 例 / 0 失败 / 0 错误**（含改动的 PlaylistEnricherTest） |
| **High 修复覆盖** | 17 条 High 中 **15 条已修复且基本正确**；**2 条未达到放行标准** |
| **阻断项** | 2 条：① High #4 修复**引入回归**（本地歌单/最近播放中的 NAS 歌静默不播）；② High #3 **从未修复**（遥控删除队列项仍跨线程崩溃） |
| **建议同批补修** | 3 条：High #6 残留竞态、High #7 同族遗漏（Medium）、其余 Low |

**结论：该批次不得直接发版。** 阻断项 ① 是本次修复引入的新回归，② 是 09-25 报告已点名、本批次却未触碰的遗留 High。两者均为用户高频路径（歌单播放、手机遥控），必须返工。

> ✅ **2026-09-26 补记**：用户确认后，两条阻断项 + 2 处 Medium + 全部 Low/nit 共 12 处已全部返工修复，复验门禁（assembleDebug + 1177 单测）通过，详见「六、修复执行与复验」与 V1.1。

---

## 二、验证证据（本机实测）

- `./gradlew.bat assembleDebug --no-daemon` → **BUILD SUCCESSFUL**
- `./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon` → **1177 tests, 0 failures, 0 errors**
  - 说明：首次执行 `cleanTest testDebugUnitTest` 因 clean 目标与任务名不匹配未真正重跑（报告时间戳停留在 9/25），改用 `--rerun-tasks` 强制重跑后确认全部通过。

---

## 三、修复逐项结论

### 阻断项（发版前必须返工）

#### ❌ High #4 修复引入回归 — 本地歌单/最近播放中的 NAS 歌静默不播（必须返工）

**修复内容**：`AppPreferences.stripVolatileStreamUrl()`（AppPreferences.kt:622-632）由「只清 `isNetworkSong`」改为「非本地且非 imported_ 前缀一律置空 streamUrl」，以清除 NAS 歌凭据（api_key / t=md5 / JWT）落盘——安全收益真实、方向正确。

**回归**：`PlayerViewModel.playQueue` 的 `needsResolve` 谓词（PlayerViewModel.kt:127-130）**只覆盖** `isNetworkSong || isLocalSong || imported_`，**不含 NAS 歌**。NAS 歌被 strip 后 streamUrl=null，走 `needsResolve=false` 分支 → `playerManager.playQueue` 用空 URI 建 MediaItem（PlayerManager.kt:616 `song.streamUrl ?: ""`）→ `onMediaItemTransition` 仅 AUTO/SEEK 才触发解析（:399，首曲为 PLAYLIST_CHANGED 不触发）→ `onPlayerError` 把空 URL 错误当「预期」静默吞掉（:430-433）→ **静默不播、无提示、不跳曲**。

受影响路径（已核实调用链）：
- `MainViewModel.playLocalPlaylist`（:2188-2192）→ playQueue → 首曲为 NAS 歌即不播
- 最近播放列表播放（HomeBranch:83 `playQueue(recentSongsList)`，recent 经 `recordPlayWithSong` 同样被 strip）
- 队列恢复路径不受影响（`updateRestoredQueueStreamUrls` 用 getSongsByIds 重建，✓）

**修法**：`needsResolve` 谓词补 NAS 分支（`!isNetworkSong && !isLocalSong && !imported_ && streamUrl.isNullOrBlank()`），并在 `resolvedFirst` when 块补 NAS 的 `getSongsByIds` 解析（或统一走 `resolveAndPlayByIndex`）。

#### ❌ High #3 — 手机遥控「移除队列项」仍跨线程崩溃（从未修复，必须返工）

**结论确认**：`RemoteControlServer.kt` 不在本次改动列表中；`MainViewModel.kt:2563` `override fun removeFromQueue(index) = playerManager.removeFromQueue(index)` 仍是**直调**，而同文件 playAt（:2540）/ moveQueueItem（:2544）/ addToQueue（:2548）均已 `mainHandler.post`。

**后果**：NanoHTTPD 工作线程 → `PlayerManager.removeFromQueue`（:1109-1134）同步触达 ExoPlayer（`isPlaying` :1114 / `removeMediaItem` :1129 / `seekTo` :1132 / `play` :1133）→ 非主线程必抛 IllegalStateException → NanoHTTPD 2.3.1 不捕获 RuntimeException → **遥控「移除队列项」release 下整体不可用**。

**最小修法（一行级）**：`override fun removeFromQueue(index: Int) { mainHandler.post { playerManager.removeFromQueue(index) } }`，与另外三个回调对齐。

### ⚠️ 主体正确、建议同批补修

#### High #6 — resolve→replay 竞态守卫：id 校验正确，但报告附带的复核发现未一并处理

- ✅ 新加守卫（PlaybackService.kt:846）比对「发起解析的 index 当前是否仍是原歌 id」，插入/删除/整队替换均能丢弃结果；丢弃后回到原歌会再次触发 `requestStreamUrlResolution` 自愈，无「点了不播」。
- ❌ **复核发现未修**：`if (!song.streamUrl.isNullOrBlank()) return false`（:830）仍在 `uiResolveJob?.cancel()`（:832）**之前**。残留竞态：A(index 3 无 URL) 解析在途 → 用户同队列切到 B(index 4 有 URL) → `resolveStreamUrlWithoutUi(4)` 提前 return、A 的 job 不被取消 → A 完成、守卫通过（A 仍在 index 3）→ `replayAt(3)` **强切回 A**。车机跳过场景可复现。
- **修法（1-2 行）**：把 `uiResolveJob?.cancel()` 前移到 streamUrl 提前 return 之前；或守卫加 `playerManager.currentIndex == index` 条件再 `replayAt`。

#### High #7 — playQueue 代数守卫：五处配对正确，一处同族遗漏（Medium）

- ✅ `resolveGeneration` 全部读写点均主线程，无需 @Volatile；playSong/playQueue/resolveAndPlayCurrentSong/resolveAndPlayByIndex/updateRestoredQueueStreamUrls 五处 ++ 与比对配对齐全；逐项 id 比对代价可接受、语义完整。
- ⚠️ **同族遗漏（Medium）**：`MainViewModel.replayCurrentWithQuality`（:2948-2963）异步质量档重解析完成后**无条件**用入口 `song` 快照调 `playerVM.playSong`（:2962）——期间用户切歌则旧歌整队回滚、播放被拽回旧歌，与 High #7 同类。修法：playSong 前重读 currentSong 比对 id。
- Minor：`updateRestoredQueueStreamUrls` 在 `nasSongIds.isEmpty()` 提前 return 前已递增代数（:505 在 :509 前），造成一次无谓失效。

### ✅ 已正确修复（可直接放行）

| 编号 | 修复 | 验证结论 |
|---|---|---|
| High #1 | QueueScreen key → `"q_${index}_${song.id}"` | ✅ addToQueue 确实不去重；key 修复正确；index-key 重组代价对队列操作可接受；其他裸 id key 未证实可产生重复 |
| High #2 | RadioTab spacer → `GridItemSpan(maxLineSpan)` | ✅ 1 列/3 列均合法，与 UnifiedSongGrid 既有写法一致 |
| High #5 | 子 VM 生命周期：dispose() + launcher 置空 | ⚠️ 主体 ✅（dispose 幂等、onCleared 主线程、recreate 不误伤）；两处 Low 遗漏：`dispose()` 未取消 `faceScanObserver`、`exportCoordinator.treePickLauncher` 未对称置空 |
| High #8 | 崩溃恢复改整实体 upsert | ✅ 签名/字段/幂等均正确，与正常完成路径一致 |
| High #9 | 导出状态机转发 | ✅ 所有早期 return 分支均转发、Completed 单发无丢失、线程安全；Low：二次触发时第 2 次 `finally` 清空回调导致在跑进度丢失 |
| High #10 | MineScreen 删除歌单确认 | ✅ 双调用点接入、destructive 焦点落取消、文案去混淆、strings 配对；遗留死串 `mine_remove_song` |
| High #11 | CacheSettings 主线程 IO | ✅ LaunchedEffect+IO 算一次；Low：分区内清理缓存后尺寸不刷新（离开重进才刷新） |
| High #12 | SongList derivedStateOf 加 key | ✅ key 覆盖全部捕获变量，语义正确 |
| High #13 | UnifiedSongRow 封面缓存 + missCache | ✅ remember key 充分、LocalContext hoist 正确、行为等价、missCache 不缓存读取失败、锁/容量正确；Low：首组合帧仍同步读文件头（report 建议的「提取移 IO」未做） |
| High #14 | PlayerControls 手势 seek | ✅ 回调内直读 latest* 修复冻结根因；注释编号笔误（写 #15 实为 #14） |
| High #15 | JamendoTab LazyListState | ✅ rememberLazyListState() 正确 |
| High #16 | MoleculeRenderer 相位 | ✅ dt 累加 + 钳制 + 清零均正确，符合文件内红线；% 100 回绕 100 秒一次、状态机 10-20 秒换分子，不可察觉 |
| High #17 | BaiduCoverProvider ensureAccessToken | ✅ 幂等/兜底/口径一致 |
| H→M | Milkdrop 缓冲 recycle | ✅ onEnter/onExit recycle 时序安全、无悬垂引用；同类遗漏 WaterfallRenderer 仍只置 null（0.2MB，量级小） |

### ✅ 非报告项修复（正确，无回归）

- **MvPersistentCache saveLock**：✅ 覆盖 tmp+rename 全序列、无递归死锁；Low：`clear()` 未纳入锁，与并发 save 竞争会复活被清条目（概率低）。
- **SmartRadioManager toSet() 快照**：✅ 报告点名三处全部改到位，全文件无遗漏锁外读点、无死锁。
- **PortraitInfoOverlay + RegisterDialogBackHandler**：✅ 与 PortraitMoreMenu 约定一致、两覆盖层互斥、无 BACK 冲突。
- **SettingsScreen LaunchedEffect(displaySection)**：✅ 竖屏/横屏语义统一、不重复触发。
- **TextInputDialog masked**：✅ 系统 IME 与自制键盘两形态均生效、非 masked 零回归。

---

## 四、新增问题清单（修复引入或同批暴露）

| 严重度 | 位置 | 问题 | 是否本次引入 |
|---|---|---|---|
| **High** | PlayerViewModel.kt:127-130 | High #4 回归：本地歌单/最近播放中的 NAS 歌空 URI 静默不播 | ✅ 本次引入，**必修** |
| **High** | MainViewModel.kt:2563 | High #3 未修复：遥控删除队列项跨线程崩溃 | ❌ 09-25 遗留，**必修** |
| **Med** | MainViewModel.kt:2962 | replayCurrentWithQuality 旧快照回滚（High #7 同族） | ❌ 既有缺陷，建议同批修 |
| Med | PlaybackService.kt:830 | streamUrl 非空提前 return 跳过 cancel → 同队列跳过仍强切回旧歌（High #6 残留） | ❌ 建议同批修 |
| Low | VisualizerViewModel.kt:673-683 | dispose() 未取消 faceScanObserver | ❌ 建议补 |
| Low | MainActivity.kt:230-232 | exportCoordinator.treePickLauncher 未对称置空 | ❌ 建议补 |
| Low | ExportCoordinator.kt:157-163 | 二次触发时第 2 次 finally 清空回调，在跑导出进度丢失 | ✅ 本次相关 |
| Low | MvPersistentCache.kt:76-84 | clear() 未纳入 saveLock | ❌ 既有 |
| Low | AdvancedRenderers.kt:217 | WaterfallRenderer onExit 不 recycle | ❌ 既有 |
| Low | CacheSettingsSection.kt:54-66 | 清理缓存后尺寸不刷新 | ✅ 本次相关 |
| Nit | strings.xml:548 / values-en:540 | `mine_remove_song` 死串 | ❌ 建议删 |

---

## 五、放行结论

**本次修复批次不得发版**，两条阻断项：

1. **High #4 回归**（本次引入）：本地歌单/最近播放含 NAS 歌 → 静默不播。修 `needsResolve` 谓词 + `resolvedFirst` 补 NAS 解析。
2. **High #3 未修**（09-25 遗留）：遥控删除队列项跨线程崩溃。包一层 `mainHandler.post` 即毕。

其余 15 条 High 修复 + 非报告项修复均可放行（含少量 Low 建议项）。建议上述两条阻断项与 2 条 Medium（replayCurrentWithQuality、PlaybackService cancel 前移）一并返工后，重跑 `assembleDebug` + `testDebugUnitTest` 确认，再走发版流程。

---

## 六、修复执行与复验（2026-09-26 补记）

用户确认「全部修复」后，将本报告第五节两条阻断项、两处 Medium 及全部 Low/nit 建议项一并返工。拆两条 @fixer lane 并行实施，随后本机构建/单测门禁复验。以下为逐项执行结果：

### 阻断项与建议项修复清单

| 项 | 位置 | 修复内容 | 复验 |
|---|---|---|---|
| **High #4 回归** | PlayerViewModel.kt `playQueue` | `needsResolve` 谓词补 NAS 分支（`!isNetworkSong && !isLocalSong && !imported_ && streamUrl.isNullOrBlank()`）；`resolvedFirst` 的 `when` 在 `else` 前新增 NAS 分支，照 `resolveAndPlayCurrentSong` 的 NAS 写法经 `backendRegistry.getAdapter()?.getSongsByIds(...)` 重建 streamUrl，失败返回原歌并 AppLog.w | ✅ 四类歌曲（imported_/网络/本地/NAS）解析路径全覆盖，`else` 兜底仍为有 URL 直接播；代数守卫与 imported_ 持久化写回未动 |
| **High #3 未修** | MainViewModel.kt:2563 `removeFromQueue` | 直调改 `mainHandler.post { playerManager.removeFromQueue(index) }`，与 playAt/moveQueueItem/addToQueue 对齐 | ✅ 与另三个回调写法一致 |
| **High #6 残留** | PlaybackService.kt `resolveStreamUrlWithoutUi` | `uiResolveJob?.cancel()` 前移到 streamUrl 非空提前 return 之前（任何新解析请求先取消在途 job）；`else` 补 isActive 守卫（job 被 cancel 后 runCatching 吞 CancellationException 不再回落旧 index 重解析） | ✅ cancel 语义干净；id 守卫与 replayAt 未动 |
| **High #7 同族（Medium）** | MainViewModel.kt `replayCurrentWithQuality` | 回写 `playSong` 前重读 `playerState.value.currentSong` 比对 id，不一致（或 null）则 AppLog.d 丢弃并 return；回写基座用重读后的 `current`（id 一致、字段更新鲜） | ✅ 异步重解析期间切歌不再被旧歌整队回滚 |
| Low | VisualizerViewModel.kt `dispose` | 补 `faceScanObserver?.cancel(); faceScanObserver = null`（Job? 幂等安全） | ✅ |
| Low | MainActivity.kt `onDestroy` | runCatching 块内补 `app.exportCoordinator.treePickLauncher = null` 对称置空 | ✅ 字段为 var (() -> Unit)?，类型安全 |
| Low | ExportCoordinator.kt / SongExporter.kt | `SongExporter.export()` 返回 Boolean（tryLock 失败返回 false）；ExportCoordinator 只在 `exportStarted=true` 时 finally 清回调，被 mutex 拒绝的分支不触碰在跑导出的回调 | ✅ Running 进度不再被二次触发清空 |
| Low | MvPersistentCache.kt `clear` | 清 map 与删文件整体纳入 `synchronized(saveLock)` | ✅ 消除 save toMap 快照竞态复活；save 内不调 clear，无死锁 |
| Low | AdvancedRenderers.kt WaterfallRenderer | 新增 `releaseBuffers()`（`prev/curr?.asAndroidBitmap()?.recycle()` + 置空，同 Milkdrop 模式），`onExit` 调用 | ✅ onEnter 重新分配逻辑未动 |
| Low | CacheSettingsSection.kt | 尺寸计算 `LaunchedEffect(Unit)` 改 `LaunchedEffect(refreshKey)`，清理按钮 onClick 触发 `refreshKey++` + `delay(300)` 等落盘后 IO 重算 | ✅ 分区内清理后即时刷新 |
| Nit | strings.xml / values-en | 删除死串 `mine_remove_song`（grep 确认无 `R.string` 引用） | ✅ |
| Nit | PlayerControls.kt / JamendoTab.kt | 注释编号 #15→#14、#17→#15 | ✅ |

### 复验门禁（2026-09-26）

- `./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks --no-daemon` → **BUILD SUCCESSFUL（5m 50s）**
- **1177 tests / 0 failures / 0 errors**

---

## V1.0 (2026-09-26)

- **变更内容**：初版——对 v2.37.1 未提交修复批次（09-25 审查 17 条 High 的修复）进行三路并行代码审查 + 构建/单测门禁，产出逐项结论与阻断项。
- **变更原因**：确认修复批次是否可放行。

## V1.1 (2026-09-26)

- **变更内容**：追加「六、修复执行与复验」——两条阻断项（High #4 回归、High #3 未修）、High #6 残留、High #7 同族及全部 Low/nit 建议项共 12 处已全部返工修复，复验门禁（assembleDebug + 1177 单测）通过。
- **变更原因**：记录阻断项返工结果，供发版放行依据。
