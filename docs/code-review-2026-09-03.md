# NASMusicTV 代码审阅报告

> 审阅日期：2026-09-03
> 审阅范围：整个 `app/src/main/java`（177 个 Kotlin 文件，44,430 行）+ 构建配置（`build.gradle.kts` / `AndroidManifest.xml` / `proguard-rules.pro` / CI workflow）
> 上次完整审阅：2026-06-30（之后有 25 次提交，本次为时隔 2 个月的复审）
> 审阅方式：6 个模块并行深度审阅（4 个由子代理完成 + 安全/工具/构建/UI 抽查由主代理亲自完成），并对核心结论做交叉验证。
> 文档版本：v1.0（更新版）｜修订 2026-09-03 定型。安全部分（§三.0 / §二 S1–S3 / §四 路线项 9）依用户决定保持不变，本版仅做版本归档，未改动任何安全相关结论与建议。

---

## 一、整体健康度

| 维度 | 评价 |
|---|---|
| 架构分层 | 有意识但没守住：手动 DI 容器、适配器层 IO 收口、DataStore 统一都对；但 `MainViewModel` 4613 行承担 17 个职责域，跨职责调用失去边界 |
| 安全 | **最大短板**：3 个本地 HTTP server 无认证、4 处 trust-all SSL、加密失败降级明文。凭据密钥管理本身做得好（无硬编码） |
| 稳定性 | 多处真实 P0：HQ 人声分离 100% 失败、数据丢失模式、主线程加载 166MB 模型 ANR、快速切歌队列回滚 |
| 性能 | 主线程同步 IO 集群、整树 20fps 重组、`resolveCovers` 串行无并发、Navidrome 末页 N+1 遍历 |
| 可维护性 | 巨型文件（ViewModel 4613 / Settings 2186 / Library 1604）、死代码（VocalRemovalProcessor 304 行 / checkPreSeparation）、版本一致性良好 |
| 测试 | CI 跑 `assembleRelease` 但不跑 test/lint，单测覆盖有限 |

**一句话**：功能面很广且能跑，但「写完没充分验证」的痕迹明显——多处在注释/commit 里声称已修复的问题，实际代码仍存在。建议优先处理安全与稳定性类 P0，再拆 ViewModel。

---

## 二、P0 问题总览（必须修）

| # | 模块 | 问题 | 关键位置 |
|---|---|---|---|
| S1 | 安全 | 3 个本地 HTTP server 无认证、绑 0.0.0.0、明文 HTTP | RemoteControlServer:28 / ModelTransferServer:27 / BackupTransferServer:38 |
| S2 | 安全 | 4 处 trust-all SSL，百度 OAuth/播放链路可被 MITM 窃取长期凭据 | BaiduOAuthClient:236 / BaiduHttpDataSourceFactory:57 / MetingApiService:71 / BilibiliMvService:54 |
| S3 | 安全 | 加密失败静默降级为明文存储 | CryptoUtils.kt:50 |
| C2 | 核心 | AppPreferences「解析失败→空集合→无条件回写」抹除用户历史数据 | AppPreferences.kt:969-983 |
| P1 | 播放 | Demucs 段读取 skip 把指针推到 EOF → HQ 分离 100% 失败 | DemucsSeparator.kt:236-239 |
| P2 | 播放 | 输出流不在 finally 关闭 → 残缺 WAV 被缓存判为有效（缓存投毒） | DemucsSeparator.kt:212-215 |
| P3 | 播放 | `with(Dispatchers.IO)` 误用 → 166MB 模型主线程加载 ANR | PlayerManager.kt:388 |
| P4 | 播放 | 异步解析用旧队列快照回写，无 generation/去重 → 快速切歌队列回滚 | MainViewModel.kt:2963-2991 |
| B1 | 后端 | 飞牛/道理鱼全部 Response 从不 close → 连接池耗尽挂起 | FeiniuAdapter.kt:540 / DaoliyuAdapter.kt:469 |
| B2 | 后端 | 飞牛/道理鱼 trust-all SSL + 关主机名校验 | FeiniuAdapter / DaoliyuAdapter |
| B3 | 后端 | 本地音乐 fullScan 非原子，异常即清空用户曲库 | LocalMusicRepository.kt:67-72 |
| B4 | 后端 | 每次启动删除全部 USB 音乐索引 | LocalMusicRepository.kt:36-50 |
| B5 | 后端 | `IN (:paths)` 超 999 变量上限 → 3000 首 USB 崩溃 | LocalMusicDao.kt:29-30 |
| B6 | 后端 | 同一首本地歌两个 ID → 收藏/队列跨会话失效 | LocalMusicRepository vs LocalMusicDao |
| N2 | 网盘 | 索引扫描异常分支 save(partial) 覆盖好索引 → 刷新一次曲库退化残片 | BaiduFileIndexCache.kt:216-224 |
| N3 | 网盘 | 索引缓存非原子写入，写一半崩溃即损坏 | BaiduFileIndexCache.kt:68-77 |

> 注：原核心模块 agent 将 `updateMergedData↔resolveAlbumCoversAsync` 互调列为 P0「无限死循环」，经主代理核查 `AlbumCoverResolver`（已解析封面被跳过、全失败时 `processed==0` 即停）**确认非字面死循环**，降为 P1（见 §三.2 与 §五修正项 1）。

---

## 三、分模块发现

### 0. 安全（本地 HTTP 服务器 / TLS / 密钥）

**三个本地 server 的共同根因：无认证 + 绑 0.0.0.0 + 明文 HTTP**

- **BackupTransferServer（端口 18081）——风险最高**：`handleList`/`handleDownload` 可下载用户备份（含 NAS 地址、账号、加密 Token）；`handleUpload`+`handleRestore` 可上传并恢复恶意备份，实现**配置劫持**（把 TV 指向攻击者 NAS）。`name` 参数用精确匹配 `find{displayName==name}` 防路径遍历 ✅，但无认证是核心问题。
- **ModelTransferServer（端口 18082）**：`handleUpload` 写入**固定路径**（忽略客户端 filename），故无路径遍历 ✅；但**无认证 + 仅校验大小下限（不校验 ONNX 内容/类型）** → 局域网内可未授权覆盖模型文件或填满磁盘（DoS）。注意端口 18082 与 RemoteControlServer 相同，端口复用混乱。
- **RemoteControlServer（端口 18082）**：`handleAdd` 反序列化任意 `Song` 入队，局域网任意设备可操控播放队列（添加/移除/跳转/搜索），无认证。该 server 不碰文件系统，无路径遍历。

**TLS 全信任（S2）**：`MetingApiService`/`BaiduOAuthClient`/`BaiduHttpDataSourceFactory`/`BilibiliMvService` 四处内联 `checkServerTrusted{}` 空实现 + `HostnameVerifier{_,_->true}`。其中百度 OAuth 用该 client POST `client_secret`，播放链路 URL 带 `access_token` → 同局域网攻击者透明窃取网盘 refresh_token（长期凭据）。

**加密降级（S3）**：`CryptoUtils.encrypt()` 异常分支 `return plainText`，`decrypt()` 异常分支 `return encryptedText`。Keystore 不可用时 Token 明文落盘且无告警。

**亮点（务必保留）**：**未发现任何硬编码密钥**。凭据走「本地 keystore.properties → BuildConfig → 运行时覆盖」三层，`BAIDU_APP_ID/SECRET` 经 gitignored 文件注入；Token 用 AndroidKeystore + AES-GCM 加密，`refresh_token` 有 Mutex 加锁的正确轮换。唯一架构性残留：`client_secret` 经 BuildConfig 编入 `classes.dex`，拿到 release APK 即可 `strings` 提取——OAuth 设备码模式在纯客户端的固有问题，正解是把 token 交换放自建后端。

### 1. 核心架构与状态管理（MainViewModel 4613 行）

**P0**
- **C2 AppPreferences 数据丢失模式**：`addPlayRecord` 等所有 JSON 型偏好 `catch { PlayRecordsData() }` 把解析失败转空集合，随后 `gson.toJson(updated)` 无条件回写覆盖。一次 JSON 异常（写入截断/字段变更）后即把 500 条播放记录/收藏/歌单抹成 1 条，且日志仅一行 `AppLog.w`。修复：解析失败禁止回写，另存 `.corrupt` 副本并明确报错。

**P1**
- **C3 主线程同步 IO**：`fetchWeather`/`setBaiduEnabled`/`triggerBaiduIndexScanIfNeeded` 内 `…Sync()` 是 `runBlocking(Dispatchers.IO)` 却跑在 `viewModelScope`（Main）；`AppRoot.kt:679/713` 在组合期直接 `getBaiduConfigSync()`；`MainViewModel` 构造期两次 `…BaseUrlSync()`；`MainActivity:172` 退出确认里 `runBlocking` 断连。全部阻塞主线程。
- **C4 StateFlow 读改写竞态**：`_songsPaging`/`_mergedAlbums`/`_mergedArtists`/`_songArtistMap` 多处 `_x.value = f(_x.value)` 并发写入无 CAS；`updateMergedData` 被本地缓存加载、增量扫描、USB 挂载、`_albums.collect`、`_artists.collect`、刷新百度、重建索引等 7 处并发触发，last-write-wins 持续丢更新。改用 `update{}`。
- **C5 playQueue 串行全量解析**：与 `playNetworkBatch` 注释明示的「懒解析」设计自相矛盾，`onPlayAllSongs`/`playLocalPlaylist` 等走 `songs.map{resolvePlayUrl}` 严格串行，最多 30×RTT 才开始播。
- **C6 AppRoot 顶层 collect 35 个 StateFlow**，`spectrumData` 以 20fps 发射 → 整棵树每秒重组 20 次（含 Library 数百卡片）。高频流应下推到叶子组件。
- **C7 NowPlaying 直读 `_favoriteIds.value`** 不建立订阅，点收藏后星标不刷新（要到切歌才更新）。
- **C8 MainActivity.onDestroy 不区分配置变更**：未声明 `configChanges`，`screenOrientation=fullSensor` 下旋转即重建，`onDestroy` 一律 `stopService`+`playerManager.release()` → 旋转一下正在播的歌就没了；未注册 `MediaButtonReceiver` → 通知播放/暂停/上下首按钮全部失效。
- **C9 `refreshApiVersions()` 被连续调用两次**（连接成功路径，多一次网络请求）。
- **C10 `playRadioStation` 空 `catch` 吞异常**（连日志都没有，上报问题无法排查）。

**P2**
- **C11 MainViewModel 4613 行、17 职责域、init 203 行、168 个 public 函数**；`onCleared()` 未把 `playerManager.onNeedResolveStreamUrl` 置空（被 Application 单例强引用）。
- **C12 备份/恢复字段不完整**（漏 language/fontAdjustment/separationMode 等）；`AppSettings.modelDownloadUrl` 死字段；`showError` 多次错误叠加多个 5s 定时器；`UiState.Error` 持 `retry` lambda 致 `UiState` 不可稳定。

### 2. 播放与音频处理（PlayerManager 1511 行 / DemucsSeparator 509 行）

> 原「`updateMergedData↔resolveAlbumCoversAsync` 无限死循环 P0」经主代理核查**降为 P1**（见 §五修正项 1）：`AlbumCoverResolver` 跳过已解析封面、全失败时 `processed==0` 即停，非字面死循环；但无短路回环设计 + `artworkUrl.replace("100x100","600x600")` 结果被丢弃（永远返回 100×100 低清图，AlbumCoverResolver:170）+ `query.replace(" ","+")` 后再 `URLEncoder.encode` 双重编码破坏中文搜索词（156-157 行）+ `MAX_CONCURRENT` 实际是串行循环，仍是 P1 严重集群。

**P0**
- **P1 Demucs 段读取 skip 逻辑**：`skipFloats=(totalSamples-startSample-segLen)*2L` 把文件指针一次跳到 EOF，第 2 段 `readFloat()` 抛 EOFException 被吞 → HQ 人声分离对 >7.8s 的歌曲 100% 失败。删掉 235-239 行 skip 即可。
- **P2 输出流不在 finally 关闭**：失败时遗留「44 字节头 + 7.8s PCM」残缺文件，`AccompanimentCache.hasAccompaniment()` 只判 `exists()&&length()>0` → 缓存投毒，二次开启 HQ 直接塞残缺 WAV 给 ExoPlayer。
- **P3 `with(Dispatchers.IO)` 误用**：`PlayerManager:388` 该是 `withContext`，却在 `scope=Main` 内联执行 166MB 模型加载 → ANR/黑屏。
- **P4 resolveAndPlayByIndex 竞态**：入口快照旧队列，挂起解析后无条件 `playQueue(updatedQueue, targetIndex)` 整体重建播放列表。快速切歌 → A 的解析结果（旧快照）回滚整个队列，N 次切歌 N 个在飞解析，最后一次响应者获胜但内容可能最旧。需 `resolveGeneration` + 按 `songId` 去重取消。

**P1**
- **P5 seekPending 吞 onIsPlayingChanged**：`seekPending==true` 时直接 `return`，既不更新 `_isPlaying` 也不移除轮询回调。seek 窗口内暂停 → UI 永久「播放中」+ 下次 `onIsPlayingChanged(true)` 再 post 一个 Runnable → 多个并行轮询循环叠加，进度翻倍。
- **P6 switchToAccompaniment/switchToOriginal 用 `setMediaItem`**：把整个播放队列替换成单曲，开一次伴唱后 `seekToNextMediaItem()` 无目标 → K 歌后无法切歌。改 `replaceMediaItem` 或 `setMediaItems(items, idx, pos)`。
- **P7 SpectralMaskProcessor.reset() 置 `enabled=false`**：Media3 切歌/重建 AudioSink 时调用，伴唱静默失效，但 `MainViewModel._vocalRemovalEnabled` 仍为 true，UI 与真实状态不一致且无法自愈。（`VocalRemovalProcessor:198` 同写法）
- **P8 Demucs MediaCodec/Extractor 无 try/finally**：解码失败路径不释放硬件解码器，TV 上解码器实例有限，泄漏几次后所有播放/分离失败。
- **P9 Demucs 字节序**：读取侧 `outputBuffer` 未设 `LITTLE_ENDIAN`（写入侧 `bb` 设了），输入 PCM 每样本高低字节互换 → 分离输入即噪声。
- **P10 overlap-add 未实现**（`OVERLAP_SAMPLES`/`TOTAL_SAMPLES_PER_SEG` 定义后零引用）→ 段边界咔哒声；**WAV 头硬编码 44100**（忽略真实采样率，48kHz 源播放慢 8.8%、音低 1.4 半音）；**DemucsSeparator.release() 从未被任何代码调用** → 166MB ONNX 会话进程级泄漏；`separate()` 无 `ensureActive()` 取消检查点。
- **P11 shuffleModeEnabled 与 playRandom 双轨并存** → `updateCurrentSongFromPlayer` 用未打乱索引映射到 `_queue` 得到错歌。
- **P12/P13** = §三.1 的 C8/C8（onDestroy 中断播放、未注册 MediaButtonReceiver）。
- **P14 AccompanimentCache LRU 淘汰只在预分离路径触发**，HQ 主路径与 `saveOriginalFile` 永不淘汰 → 缓存无限增长写满 TV 内置存储。
- **P15 Demucs 解码循环每样本 `ByteBuffer.allocate(8)` + 无缓冲 `write`** → 4 分钟歌千万次系统调用，分离慢到分钟级。

**P2**：`checkPreSeparation` 死代码；`VocalRemovalProcessor` 304 行死代码（`SpectralMaskProcessor` 才是注入的）；`SpectrumAnalyzer` 用常量 44100 忽略回调真实采样率；每 50ms 3 次 `String.format` 常驻主线程；`Uri.parse("file://$path")` 中文/空格未编码；`disableHighQualityRemoval` TODO 未实现；`clearQueue` 不清播放状态；`WAKE_LOCK` 权限冗余声明。

### 3. 后端适配器与本地音乐（5 个 impl / local / radio）

**P0**：见 §二 B1–B6（飞牛/道理鱼 Response 泄漏 + trust-all、本地音乐 fullScan 非原子、USB 索引每启动被删、`IN` 变量上限崩溃、本地歌双 ID、MediaMetadataRetriever 未释放）。

**P1**：Navidrome `fallbackGetSongs` 末页空触发全库 N+1 遍历（B8）；Subsonic `getStarred2` 却解析 `starred` → 收藏整体失效、只能加不能取消（B9）；专辑列表硬上限 500 无分页（B10）；Navidrome `buildCoverUrl` 每次重建 salt+token 致封面缓存失效（B11）；Subsonic 静态 salt 使 token 成永久口令（B12）；Subsonic `getSongsByIds` 串行 N+1（B13）；飞牛/道理鱼 offset/limit 可除零（B14）；StorageMonitor 主线程反射 StatFs + 隐藏 API 无 try/catch → ANR/崩溃，拔盘误删其他盘索引（B15）；AlbumCoverResolver 双重编码 + 串行无并发 + 不校验名称匹配（B16）；SearchAggregator `withTimeoutOrNull` 对阻塞 OkHttp 无效、失败与无结果不可区分（B17）；BackendRegistry 切换后端竞态（B18）；Jellyfin 多处硬编码上限无分页、LocalCoverExtractor `content://` 处理错误（B19）；MusicMerger 合并后只留 NAS id、USB albumId 恒 0（B20）。

**P2**：5 个 impl 各自新建 OkHttpClient；可变集合无同步；`LocalMusicDatabase` 无 migration 路径（升级到 v2 会 `IllegalStateException`）；`path.hashCode()` 作主键碰撞；Jellyfin `Limit=0` 语义风险；Subsonic 依赖非标准字段；SearchAggregator 英文歌名误判拼音；MusicScanner 无深度限制；O(N²) 列表复制/拼音重复计算；RadioBrowserClient 上报失败静默。

**5 个后端适配器能力矩阵**（✅完整 / ⚠️降级 / ❌未实现）：

| 接口方法 | Jellyfin | Navidrome | Subsonic | 飞牛 | 道理鱼 |
|---|:--:|:--:|:--:|:--:|:--:|
| getAlbums | ✅分页 | ⚠️上限500 | ⚠️上限500 | ⚠️单页 | ⚠️单页 |
| getAlbumSongs | ⚠️上限500 | ✅ | ✅ | ⚠️单页 | ⚠️单页 |
| getArtists/Songs | ✅ | ⚠️上限500/N+1 | ⚠️上限500/N+1 | ⚠️反推 | ⚠️INFERRED |
| getSongs(分页) | ✅ | ⚠️末页N+1 | ✅ | ⚠️除零 | ⚠️除零 |
| getSongsByIds | ✅批量 | ❌未实现 | ⚠️串行N+1 | ⚠️回退 | ⚠️INFERRED |
| getYears | ✅ | ❌ | ⚠️上限1万 | ❌ | ❌ |
| searchSongs | ✅ | ⚠️N+1 | ⚠️N+1 | ⚠️UNCONFIRMED | ⚠️INFERRED |
| getLyrics | ✅ | ✅ | ❌参数非规范 | ⚠️未接 | ⚠️INFERRED |
| getFavorites | ✅ | ✅ | ❌恒空(starred/starred2) | ⚠️UNCONFIRMED | ⚠️INFERRED |
| toggleFavorite | ✅ | ✅ | ❌只能加 | ⚠️试错 | ⚠️无状态 |
| 播放/封面 | ✅ | ✅ | ✅ | ⚠️逆向 | ⚠️INFERRED |
| OkHttp 关闭 | ✅17/17 | ✅3/3 | ✅3/3 | ❌0/4 | ❌0/5 |
| SSL 校验 | ✅ | ✅ | ✅ | ❌全信任 | ❌全信任 |

结论：Jellyfin 最完整；Navidrome 次之（缺批量/聚合方法）；Subsonic 有 1 个致命功能 bug + 6 个未实现；**飞牛/道理鱼基本是「半成品 + 逆向猜测」**，20+ 方法为 UNCONFIRMED/INFERRED，且共享最严重的安全问题。

### 4. 网络音乐 / 百度网盘 / MV（详见 §三.0 安全，补充功能缺陷）

**P1**：`BaiduCoverProvider.ensureAccessToken` 拼空 token → 侧车封面 100% 失败（N4）；内嵌封面/歌词 Range 请求用裸 dlink（未补 token）→ 大概率 403（N5）；`MetingApiService` 302 解析只认 302、不处理相对 Location（N6）；`resolveLyrics` 不校验 HTTP 状态码，错误页被当歌词（N7）；`searchByDirectory` 根目录文件 `k.contains("")` 恒真 → 返回整个曲库（N8）；MV 持久缓存主线程 IO（N9）；Bilibili wbi 搜索未实现签名 → 在线 MV 基本搜不到（N10）；Meting fallback 串行无退避最坏 60s（N11）；refresh 失败保留作废 refresh_token → 百度源永久卡死无提示（N12）；封面批量解析串行（N13）。

**P2**：`Id3v2Parser` 未处理扩展头/反同步（v2.3 帧长可能负）、APIC 受 256KB 窗口截断（N14）；`BaiduMvFileService` 重复 `fileMetas`、死变量（N15）；`CryptoUtils` 降级明文（同 S3）；每次请求 Keystore 加载 + 双解密（性能）；`pollDeviceToken` 忽略注入 `tokenUrl`；MV 缓存淘汰全量排序非原子；`usesCleartextTraffic` 全局应改 `networkSecurityConfig`；`BilibiliMvService.stripHtml` 每次编译 Regex 且不解 HTML 实体；`RadioBrowserClient.reportClick` 失败静默。

**亮点**：密钥管理是模块最强项（见 §三.0）；`Gson` 解析有 try/catch 防御，未发现因 null 解析崩溃；Range 拖动进度条交给 Media3 原生支持，实现正确。

### 5. 工具类 / 歌词 / 构建配置（主代理亲自审阅）

- **版本一致性 ✅**：`build.gradle.kts` versionName=2.25.7 / versionCode=77，`NasMusicVersion` 取自 BuildConfig，`CHANGELOG` 最新 `[v2.25.7]`，三方一致。
- **minSdk 22 合规 ✅**：Grep 未发现 `List.of/Stream/java.time/android.icu/Path.of/CompletableFuture` 等 API 24+ 调用，TinyPinyin 替代 ICU 的约定被遵守。
- **CI ✅（已纠正旧报告）**：`.github/workflows/build.yml:47` 实际跑 **`assembleRelease`**（含 minify/shrink），质量门禁比 6 月旧文档描述的「只 assembleDebug」更严；但**仍不跑 test/lint**（无测试步骤）。
- **ProGuard ✅**：覆盖 `data.model`/`data.prefs`/`backend`/Gson/ExoPlayer/ZXing/NanoHTTPD/Coroutines/ONNX，未发现严重遗漏（v2.5.1 Gson 崩溃风险点已防御）。
- **EncodingUtils（P2）**：启发式修复（U+FFFD GBK 回退、Latin-1→GBK 转换），有 try/catch 保护无崩溃，但第三步 30% 阈值可能漏转、可能误伤正常中文。AGENTS.md 已说明「部分情况不可恢复」。
- **LrcParser ✅**：稳健，`coerceAtLeast(0)` 防负数时间、正则约束数字、排序后二分查找，未发现严重问题。
- **AndroidManifest（P2/中）**：`allowBackup=true`（ADB 可备份，但 Token 加密且 Keystore 密钥不在备份内，影响有限）、`usesCleartextTraffic=true`（应改 networkSecurityConfig 单域名放行）、`screenOrientation=fullSensor` 无 `configChanges`（与 C8 呼应）。
- **依赖版本（非安全）**：Media3 1.2.1 较旧（当前 1.4.x），可择机升级；其余（OkHttp 4.12、Gson 2.10.1、ONNX 1.17.1、Room 2.7.1）合理。

### 6. UI 层（主代理抽查，专项 agent 因限流未执行）

- **UI 字符串外部化完成度高**：项目做过 3 轮「中文字符串 → strings.xml」重构。精准 Grep 仅发现 **2 处明确遗漏**：`LibraryScreen.kt:630` 操作提示文案（`"◀ ▶ 导航 | ◀◀ ▶▶ 切歌 ..."`）、`BaiduAuthDialog.kt:201` `text = "复制"`。大量 Grep 命中为 KDoc 注释与数据字符串（netease 榜单名、搜索变体词），非 UI 文案。
- **TV 焦点可用性需专项人工测试**：静态审阅无法完全覆盖 D-Pad 焦点陷阱/丢失，建议补一次真机遥控遍历（这是 TV 应用最高频的「遥控器按不到按钮」类缺陷来源）。
- **巨型 Composable**：`SettingsScreen` 2186 行、`LibraryScreen` 1604 行、`AppRoot` 999 行；`AppRoot` 顶层 35 个 `collectAsState`（同 C6）使重组粒度退化到整棵树。

---

## 四、修复优先级路线图（按「收益/风险 × 改动量」排序）

| 序 | 修复项 | 级别 | 改动量 | 收益 |
|---|---|---|---|---|
| 1 | Demucs 段读取删 skip + 修复字节序（P1/P9） | P0 | ~10 行 | 解锁 166MB 模型链路价值，HQ 分离从「100% 失败」变可用 |
| 2 | PlayerManager:388 `with`→`withContext` | P0 | 1 行 | 消除开伴唱即 ANR |
| 3 | AppPreferences 数据丢失三连（catch 不回写 + 原子 fullScan + 统一本地歌 ID） | P0 | 中 | 决定用户历史数据是否还在 |
| 4 | 百度 OAuth/DataSource 恢复默认 TLS 校验（S2） | P0 | 中 | 堵住长期网盘凭据 MITM 通道 |
| 5 | 飞牛/道理鱼 Response.close + SSL（B1/B2） | P0 | 小 | 消除连接池耗尽 + 凭据截获 |
| 6 | resolveAndPlayByIndex generation 去重 + switchToAccompaniment 改 replaceMediaItem（P4/P6） | P0 | 中 | 消除快速切歌队列回滚/切不了歌 |
| 7 | updateMergedData 加短路 + AlbumCoverResolver 双重编码/串行修复（C1 集群） | P1 | 中 | 消除主线程反复重建 + 中文封面可用 |
| 8 | 主线程 runBlocking 全部迁移 IO（C3 集群） | P1 | 中 | 冷启动/设置页卡顿消除 |
| 9 | 本地 HTTP server 加一次性 token 认证（S1） | 安全 | 中 | 消除局域网未授权访问/配置劫持 |
| 10 | Navidrome fallbackGetSongs 引信 + Subsonic starred2（B8/B9） | P1 | 小 | 消除大曲库末页卡死 + 收藏可取消 |

**次优先**：StateFlow 竞态改 `update{}`（C4）、AppRoot 高频流下推（C6）、SpectralMaskProcessor.reset 不置 enabled（P7）、Demucs release 调用 + 取消检查点（P10/P3 泄漏）、MV 缓存/ModelTransfer 主线程 IO 迁移（N9）、备份字段补全（C12）。

**长期架构**：拆分 `MainViewModel`（4613 行 → Repository/UseCase 层）、清理死代码（VocalRemovalProcessor / checkPreSeparation）、补 CI test/lint 步骤、升级 Media3。

---

## 五、主代理交叉验证修正项

1. **「无限死循环 P0」→ 降为 P1**：核心模块 agent 将 `updateMergedData()↔resolveAlbumCoversAsync()` 互调标为 P0「永不终止的后台死循环」。主代理读取 `AlbumCoverResolver.resolveCovers`（44-118 行）确认：已解析封面 `if(coverUrl!=null) continue` 被跳过，且全部失败时 `processed==0` 不再 `onUpdated`，循环收敛。故非字面死循环，但无短路回环设计 + 双重编码/串行/低清图三个真实 bug 仍构成 P1 严重集群。
2. **「CI 只 assembleDebug」→ 已纠正**：核心模块 agent 沿用 6 月旧文档称 CI 仅 `assembleDebug`。主代理读取 `.github/workflows/build.yml:47` 确认当前跑 **`assembleRelease`**（含 minify/shrink），质量门禁更严；但确认 CI 流程**确实不跑 test/lint** 这一点仍成立。

---

## 六、审阅结论

代码实现了非常广的功能面（6 大后端 + 本地音乐 + 百度网盘 + 网络音乐 + MV + 天气电台 + K 歌人声分离 + 手机遥控 + 备份迁移），分层意图清晰、凭据密钥管理规范、版本/ProGuard/minSdk 合规都做得对——**基础扎实**。

但问题集中在「**改完没充分验证**」：HQ 分离 100% 失败却仍有完整 commit、主线程加载模型、缓存投毒、数据丢失模式、无认证 server、trust-all SSL——这些都不是「做不到」，而是「改过一遍但没端到端验证」。建议按 §四 路线图先消 P0（稳定性与安全），再启动 MainViewModel 拆分重构。

> 本报告基于静态代码审阅，未运行应用。TV 焦点可用性、真实网络环境下的竞态、HQ 分离端到端效果，建议结合真机测试进一步确认。
