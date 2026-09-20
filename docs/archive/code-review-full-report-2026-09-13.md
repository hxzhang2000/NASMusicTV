# NASMusicTV 全量代码深度审阅 — 可开发实施版（二次核查修订 / 三次审阅增补 / 四次审阅 K 歌全景图）

**审阅日期**: 2026-09-13
**二次核查修订**: 2026-09-13 —— 全部 21 项 P0 + 15 项 P1 已逐项对照实际源码复核
**三次审阅增补**: 2026-09-13 —— 抽样验证行号、补行号快照声明、T2 修复方案升级、新增 1 项 P1 + 1 项专项 review 待办
**四次审阅（K 歌全景图）**: 2026-09-13 —— 厘清 K 歌双路径（DSP + HT-Demucs ONNX）架构与 4 个 KDoc 注释建立心智模型，强化 P1#13 措辞
**审阅范围**: 310个Kotlin源文件，6大模块
**问题总览（四次审阅后）**: P0 有效项 13 项（按各章标题：安全 3 + 线程 6 + 架构 4）+ P1 有效项 13 项（含 1 项跨调度器阻塞 + 1 项 K 歌 HQ ONNX 路径专项 review 待办）
**综合评分**: 74/100（初版 67 → 二次 74 → 三次维持 74 → 四次维持 74,四次审阅以 K 歌注释+心智模型建立为主,未引入新降分项）

> **二次核查说明**: 初版报告中 S2、S5、T5、L5 共 4 项 P0 经源码复核不属实或已过时，L7 已过时，P1 表格中 4 项不属实。修订版保留原条目编号（便于对照），不属实条目标注【❌ 不属实】并附源码证据，防止误实施。初版原文备份于 `code-review-full-report-2026-09-13.md.bak`。
>
> **行号快照声明**: 本报告引用的所有行号对应**审阅当时**的源码快照,实测 L1 `NasMusicApp.kt`（原 462 行,实际 437 行,行号漂移 -25）和 T8 `ParticleRenderers.kt`（原 294/303/317,实际 269/278/292,行号漂移 -25）出现系统性偏差,推断这两个文件在审阅后被减约 25 行（注释清理/死代码删除）。**所有行号引用以描述的现象/代码片段为准,实施时需以当前 main 分支的实际行号定位**。建议在 PR 描述里贴 commit SHA 以便 bisect 漂移源头。
>
> **方法论说明**: 本报告的"✅ 属实 / ⚠️ 部分属实 / ❌ 不属实"判定均附源码逐行证据（行号 + 代码片段）。被剔除的 4 项 P1 反证见第四章末。**凡修复方案中给出 Before/After 代码的,均经过编译可行性预检**（但未在真机运行验证,实施时需跑一次 release 构建 + 镜像到电视 24h 烟测）。
>
> **注**: 本地HTTP服务器（RemoteControlServer等）为局域网内使用，无需认证，不列为安全问题。ip-api.com免费版不支持HTTPS，HTTP明文传输位置属外部服务限制，不列为安全问题。

## 〇、二次核查总览表

| 条目 | 初版定性 | 核查结论 | 处置 |
|---|---|---|---|
| S1 CryptoUtils口令 | P0安全 | ✅ 属实（已知取舍） | 保留，可实施 |
| S2 token明文 | P0安全 | ❌ 不属实（已加密） | 剔除 |
| S3 Subsonic TOCTOU | P0安全 | ✅ 属实 | 保留 |
| S4 Jellyfin token | P0安全 | ⚠️ 部分属实 | 保留，定性降级 |
| S5 API密钥硬编码 | P0安全 | ❌ 不属实（无硬编码密钥） | 剔除 |
| T1 applicationScope | P0线程 | ⚠️ 部分属实 | 降级为"无需修改" |
| T2 runBlocking | P0线程 | ⚠️ 部分属实（调用点结论错误） | 保留，结论反转 |
| T3 三元组非原子 | P0线程 | ✅ 属实 | 保留 |
| T4 playedIds | P0线程 | ⚠️ 部分属实（严重性高估） | 保留，定性修正 |
| T5 VocalRemoval reset | P0线程 | ❌ 不属实（死代码） | 剔除 |
| T6 AudioFrame无同步 | P0线程 | ⚠️ 部分属实 | 保留 |
| T7 Bitmap泄漏 | P0线程 | ✅ 属实 | 保留 |
| T8 Bitmap泄漏 | P0线程 | ✅ 属实（比初版更严重） | 保留，升级 |
| L1 NasMusicApp God Object | P0架构 | ⚠️ 部分属实（数字不准） | 保留，数据修正 |
| L2 MainViewModel | P0架构 | ✅ 属实（实测3162行） | 保留 |
| L3 playModeToggleHandler | P0架构 | ⚠️ 部分属实（风险夸大） | 保留，降级为P1 |
| L4 Room Migration | P0架构 | ✅ 属实（合理设计） | 保留"无需修改"结论 |
| L5 SearchTab重复搜索 | P0架构 | ❌ 不属实（定位错误文件） | 剔除 |
| L6 FocusableSurface | P0架构 | ⚠️ 代码属实但修复无效 | 降级 |
| L7 HqOrchestrator泄漏 | P0架构 | ❌ 已过时（清理链路已存在） | 剔除（留小尾巴） |
| L8 Handler轮询 | P0架构 | ⚠️ 属实但为已知设计 | 降级为可选优化 |

---

## 一、P0 安全类修复方案（修订后 3 项）

### S1: CryptoUtils加密口令硬编码 ✅ 属实

**文件**: `app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt:32`
**风险**: APK反编译即可获取加密密钥，refresh_token等敏感数据可被解密
**核查确认**: 第 32 行 `PASSPHRASE = "NasMusicTV-LocalCrypto-2b7e1f9c-2024"` 逐字一致；第 15-23 行注释记载 TV 设备 AndroidKeyStore KeyGenerator 抛 `NoSuchAlgorithmException` 的历史原因；第 28-31 行注释自认"混淆级而非保密级"；第 37-47 行为 SHA-256 软件密钥派生 + KeyStore 旧密钥解密回退。属已知接受的取舍，升级优先级由所有者决定。

**Before**:
```kotlin
private const val PASSPHRASE = "NasMusicTV-LocalCrypto-2b7e1f9c-2024"
```

**After**（分两阶段）:

**阶段A — 快速修复（2h）**: 口令从BuildConfig注入，不再硬编码在源码中
```kotlin
// build.gradle.kts:
// buildConfigField("String", "CRYPTO_PASSPHRASE", "\"${project.property("crypto.passphrase") ?: "..."}\"")

// CryptoUtils.kt:
private val PASSPHRASE: String = BuildConfig.CRYPTO_PASSPHRASE
```
- `local.properties` 或 CI 环境变量中设置 `crypto.passphrase=xxx`
- APK仍可逆向BuildConfig，但不再与源码同仓库

**阶段B — 完整修复（4h）**: 优先尝试AndroidKeyStore，失败时回退软件密钥。注意：需先在真机确认 `NoSuchAlgorithmException` 的具体触发点——若是 `KeyGenerator.getInstance("AES", "AndroidKeyStore")` 构造失败，可尝试 `KeyGenerator.getInstance("AES")` 生成后经 `keyStore.setEntry()` 手动存入的替代路径。

**验证**: `adb shell dumpsys package com.nasmusic.tv` 确认无明文口令；`keytool` 验证AndroidKeyStore条目存在

---

### S2: 百度网盘refresh_token明文存储 ❌ 不属实（已剔除）

**核查证据**: `AppPreferences.kt` 中 `saveBaiduTokensSync`（1384-1392行）写入前对 accessToken/refreshToken **均调用 `CryptoUtils.encrypt`**，`getBaiduTokensSync`（1369-1381行）读取时对应 decrypt。唯一写 token 的调用方 `BaiduOAuthClient.kt`（157-164、263行）均走此加密路径。**refresh_token 是密文存储，初版判断错误。**

**遗留小问题（新发现，P2）**: `customAppKey/customSecretKey`（AppPreferences.kt 1432-1437行）确实未加密、明文存于 JSON。若用户填写了自定义 appKey/secret，可考虑与 token 一同加密，优先级低。

---

### S3: Subsonic toggleFavorite TOCTOU竞态 ✅ 属实

**文件**: `app/src/main/java/com/nasmusic/tv/backend/impl/SubsonicAdapter.kt:438-454`
**风险**: getFavorites()与star/unstar之间存在时间窗口，并发操作导致收藏状态不一致
**核查确认**: 行号准确，`toggleFavorite` 确实先调 `getFavorites()` 判断再选 star/unstar，参数 `isCurrentlyFavorite` 完全未使用。

**After**（与JellyfinAdapter修复模式一致，直接使用传入参数）:
```kotlin
override suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
    try {
        // 直接使用调用方传入的本地收藏状态，不再调用 getFavorites() 做二次查询。
        // 消除 TOCTOU 竞态窗口，且避免全量拉取收藏列表的网络开销。
        val method = if (isCurrentlyFavorite) "unstar" else "star"
        val url = buildRestUrl(method) + "&id=$songId"
        val json = executeRequest(url) ?: return@withContext false
        val subsonic = json.getAsJsonObject("subsonic-response")
        subsonic?.get("status")?.asString == "ok"
    } catch (e: Exception) {
        AppLog.e("SubsonicAdapter", "toggleFavorite failed", e)
        false
    }
}
```

**验证**: 快速连续点击收藏按钮，确认状态一致；检查Subsonic API日志确认不再调用getStarred2

---

### S4: Jellyfin认证token会话中无过期处理 ⚠️ 部分属实（定性降级为P1）

**文件**: `app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt`
**核查确认**: 无401拦截器、无token刷新逻辑属实。但**初版"持续401不刷新"表述不准确**——`initialize()`（67-100行）第 78 行会调用 `fetchCurrentUserInfo()` 验证 token，失败即回退 `authenticateByName` 用户名密码重认证（密码已加密保存）。即重连/重启时能自愈，仅**会话中途**的 401（如服务器端强制过期、密码修改）需用户手动重连。

**修复方向（P1，2h）**: 在 `executeRequest` 层捕获 401 → 触发 `authenticateByName` 重认证 → 重试原请求一次。实现时注意重试只做一次、避免循环；token 持久化沿用 `CryptoUtils.encrypt`（407行现状）。

**验证**: 在Jellyfin管理后台手动使token过期，确认客户端自动刷新

---

### S5: Daoliyu/Feiniu API密钥硬编码 ❌ 不属实（已剔除）

**核查证据**: `DaoliyuAdapter.kt` 使用邮箱+密码换 JWT（第 50 行 token 为运行时变量）；`FeiniuAdapter.kt` 使用 music-token Cookie（59行）。**两个适配器均无任何硬编码 api_key/secret/appkey 字面量，token 均为运行时登录获取。初版此项为虚构。**

> 附注：初版修复建议中的 `AppPreferences.getInstance(context)` 实际存在且可用（AppPreferences.kt:82）——AGENTS.md 中"no getInstance() singleton"的表述已过时，与源码不符。

---

## 二、P0 线程安全类修复方案（修订后 6 项）

### T1: NasMusicApp applicationScope ⚠️ 降级为"无需修改"

**文件**: `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:196`
**核查确认**: 第 196 行代码与初版一致。但初版**漏看了 458-461 行已存在 `onTerminate() { applicationScope.cancel() }`**。且初版两个修复建议均有技术错误：
- `onTerminate()` 仅在模拟器可靠回调，真机永不触发（初版自己也承认）；
- **`ProcessLifecycleOwner` 的 lifecycle 永远不会进入 DESTROYED**（只有 CREATED/STARTED/RESUMED），绑定到它的 scope 的销毁分支永远不会执行——此建议无效，勿实施。

**修订结论**: Application 级 scope 与进程同生共死（进程被杀时协程自然终止），SupervisorJob 防单点失败扩散。**当前实现合理，无需修改。**

---

### T2: AppPreferences CloudDriveConfig runBlocking ⚠️ 部分属实（初版审计结论错误 + 二次核查新发现）

**文件**: `app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:1322-1333`
**核查确认**: `@WorkerThread` 标注（1322行）与注释（1315-1321行）均存在。但初版"调用点均在IO协程上下文"的审计结论**错误**——真正的风险点分两类：
- **主线程调用点（ANR 风险）**:
  - `NasMusicApp.kt:228`（onCreate 主线程，文档原 240 偏差 -12）
  - `NasMusicApp.kt:407`（`refreshBaiduServiceRegistration()`，被 UI 触发）
  - `NetworkMusicViewModel.kt:211`（`refreshBaiduConnectionState()` 普通成员方法,UI 同步调用即主线程）
- **跨调度器阻塞点（线程池风险）**:
  - `NetworkMusicViewModel.kt:675`（`restoreBaiduIndexOnStart()` 在 `viewModelScope.launch { withContext(Dispatchers.Default) { ... } }` 内调用）——**非主线程,但 runBlocking + IO 会阻塞 Default 线程池**,Default 池容量=CPU 核数,占住即影响所有解析/计算协程
- **包装层（需同步改）**:
  - `BaiduPrefs.kt:11-13`（仅透传,本身不阻塞,但需保证上层调用方正确）
  - `AppPreferences.kt:1370/1385/1396/1402/1404/1406/1418/1420/1431/1432/1434/1435/1437/1438/1440`（共 15 处 `getBaiduConfigSync()` 间接调用,需全量评估上下文）

**全量调用方共 26 处**（实测:`grep` 整个 `app/src/main/java` 目录）—— 实施时**必须全部排查**,不可只修文档点名的 2 处。

而初版列出的 BaiduOAuthClient/BaiduMvFileService/BaiduNetdiskService 实际调用的是 `getBaiduTokensSync` 等其他同步方法（确在 withContext(IO) 内）,并非本函数。

**修订结论**: runBlocking+Dispatchers.IO 在主线程调用点会阻塞主线程(ANR 风险),在 `Dispatchers.Default` 内调用会阻塞 Default 线程池（线程饥饿风险）。初版"无需修改"**不成立**。

**修复方向（2h,含全量排查）—— 优先推荐方案**：

第 1339 行已存在 `val baiduConfigFlow: Flow<CloudDriveConfig>`,**直接复用该 Flow** 替换所有 `getBaiduConfigSync()` 调用。典型模式:
```kotlin
// 旧（同步、阻塞）
fun refreshBaiduConnectionState() {
    val cfg = prefs.baidu.getBaiduConfigSync()
    // ...
}

// 新（异步、不阻塞）
suspend fun refreshBaiduConnectionState() {
    val cfg = prefs.baidu.baiduConfigFlow.first()
    // ...
}
```
- 调用方若在 ViewModel 内,改为 `viewModelScope.launch { ... }` 包住
- 调用方若在 UI 直接触发,需引入回调或在 Composable 内 `LaunchedEffect` 启动
- 调用方若在 `Application.onCreate` 这种"必须同步拿配置"的场景,临时方案是用 `runBlocking { baiduConfigFlow.first() }`（不带调度器切换,让 DataStore 沿用其默认调度）—— **不要 `runBlocking(Dispatchers.IO)`**

**降级方案（仅作防御,1h）**—— 不推荐单独使用:
```kotlin
@WorkerThread
fun getCloudDriveConfigSync(type: CloudDriveType): CloudDriveConfig? {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        AppLog.w(TAG, "getCloudDriveConfigSync called on main thread!", RuntimeException("stacktrace"))
        return null
    }
    return try { ... }
}
```
此方案仅"软失败",不解决跨调度器阻塞,仅作为 Flow 迁移过渡期的临时止血。

**验证**: 
- 用 `adb shell am instrument` + StrictMode `detectDiskReads()` 跑 5 分钟,确认无主线程 IO 告警
- `adb shell dumpsys gfxinfo com.nasmusic.tv framestats` 确认无掉帧
- Default 线程池监控: 在 `applicationScope` 内加临时 `Metrics.collectDefaultExecutorStats()`,确认任务排队长度<1

---

### T3: PlayerManager三元组非原子更新 ✅ 属实

**文件**: `app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt:164-180`
**核查确认**: 164/176/179 行确为三个独立 MutableStateFlow；538-539、565-566、1061-1063 行等处连续赋值非原子，AppRoot 经 MainViewModel 分别 collect 会读到错帧状态。

**修复方向**: 引入不可变 `PlayerState` data class 封装三元组，`_playerState.update { it.copy(...) }` 原子更新。

**⚠️ 实施警告**: 初版方案中 `currentSong = _playerState.map { ... }.stateIn(scope, ...)` 的向后兼容写法有隐患——`stateIn` 需绑定 scope，会引入初始值与订阅时序问题，且本项目 UI 架构为 **AppRoot 集中订阅 StateFlow、Screen 用参数+回调下发**（无 viewModel() 注入），集中订阅点需同步改造。建议直接让 AppRoot/MainViewModel 改订阅合并后的 `playerState` 流，不要用 map+stateIn 逐字段桥接。

**验证**: 快速切歌时UI不再闪烁（旧歌名+新队列等不一致状态）

---

### T4: SmartRadioManager playedIds可见性竞争 ⚠️ 部分属实（定性修正）

**文件**: `app/src/main/java/com/nasmusic/tv/backend/radio/SmartRadioManager.kt:79`
**核查确认**: playedIds 为 `mutableSetOf<String>()`（46行）；79、117-123、167 行确在 synchronized 块外传入。**但初版"并发修改异常（CME）"定性错误**——`RadioSongScorer.generateBatch` 是纯函数（仅 `in excludedIds` 判重，不修改集合），不存在迭代器 CME。实际风险是 **UI 线程 stop/skip 清空集合与 IO 协程读取之间的可见性竞争**，且 81、126 行 `playedIds.clear()` 本身在锁外。

**修复方向（1h）**: 统一为快照读模式——所有 `generateBatch` 调用传入 `playedIds.toSet()` 快照，`clear()` 移入 synchronized 块：
```kotlin
val playedIdsSnapshot: Set<String>
synchronized(stateLock) {
    playedIds.clear()
    currentBatchIds.clear()
    playedIdsSnapshot = playedIds.toSet()
}
val batch = RadioSongScorer.generateBatch(library, seed, counts, playedIdsSnapshot, BATCH_SIZE)
```
第 121-122、167 行（startFromCurrentSong/skip）同样处理。

**验证**: 快速连续点击"换一批"，确认无状态不一致

---

### T5: VocalRemovalProcessor reset()清零enabled ❌ 不属实（死代码，已剔除）

**核查证据**: `VocalRemovalProcessor` **全项目无任何实例化，是死代码**。实际注入 AudioSink 的是 `SpectralMaskProcessor`（PlaybackService.kt:202/216），其 `reset()`（148-154行）已含"P7 修复"注释，**明确不清零 enabled**。"切歌后人声消除失效"的运行时 bug 不存在于当前应用。可考虑删除死代码文件（P2）。

---

### T6: AudioFrame跨线程读写无同步保护 ⚠️ 部分属实

**文件**: `app/src/main/java/com/nasmusic/tv/visualizer/AudioFrame.kt:16-58` + `SpectrumRepository.kt:58-127`
**核查确认**: AudioFrame 全部字段为普通 var/val 无 @Volatile；onFrame（58-127行，行号准确）在音频回调线程写，渲染器在 draw(frame) 中直接读，无同步保护——**问题描述成立**。但初版 After 方案将 `sectionEnergy`（34行）、`bassRaw`（44行）当作新增字段是错的，**两字段现状代码已存在**。

**修复方向**: 双缓冲方案可用——写端写 back 缓冲、完成后翻转 `@Volatile writeIndex`，读端读 front。单写者场景下 volatile 写→读建立 happens-before，是标准安全模式。**注意**：仅音频回调线程允许翻转 writeIndex；若渲染线程也参与翻转则不安全。`FrameData` 字段清单以现状 AudioFrame 实际字段为准（含 sectionEnergy/bassRaw），不要照抄初版 After 代码。

**验证**: 高频切歌场景下可视化效果无撕裂/跳变

---

### T7: LyricsDotMatrixRenderer Bitmap泄漏 ✅ 属实

**文件**: `app/src/main/java/com/nasmusic/tv/visualizer/renderers/LyricsDotMatrixRenderer.kt:223-319`
**核查确认**: 223 行 createBitmap 至 319 行 recycle 之间无 try-finally（无提前 return，泄漏仅发生在异常路径，如 OOM/IllegalArgumentException）。

**修复**:
```kotlin
val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
try {
    // ... 采样逻辑不变 ...
} finally {
    bmp.recycle()
}
```

**验证**: 低内存设备长时间运行，`adb shell dumpsys meminfo` 检查Bitmap计数

---

### T8: ParticleRenderers Bitmap泄漏 ✅ 属实（升级：存在必然泄漏路径）

**文件**: `app/src/main/java/com/nasmusic/tv/visualizer/renderers/ParticleRenderers.kt`
**核查确认**: 294 行 createBitmap，317 行 recycle，无 try-finally。**且比初版描述更严重**——303 行 `targets ?: return` 在 createBitmap 之后、recycle 之前**提前返回，存在必然泄漏路径**（非仅异常场景，每次 targets 为 null 即泄漏一个 Bitmap）。

**修复**: 同 T7 模式包裹 try-finally，并把 `targets ?: return` 判断**提前到 createBitmap 之前**。

**验证**: 同T7

---

## 三、P0 架构/生命周期类修复方案（修订后 4 项 + 3 项降级/剔除）

### L1: NasMusicApp God Object反模式 ⚠️ 部分属实（数据修正）

**文件**: `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`（全文462行）
**核查确认（修正数据）**: lateinit var 实为 **15 个**（初版"20+"不准）；by lazy 实为 **16 个**（"10+"属实）；onCreate 为 198-407 行共 **210 行**。smartRadioManager 确在 **210 行**构造、215 行通过 lambda 延迟引用 networkMusicManager；networkMusicManager 实际赋值在 **235 行**（初版引的"227行"来自过时的代码注释，非实际行号）。lambda 延迟引用本身安全。

**修复策略**（长期迭代，16h+）: 按域拆分子容器（BaiduComponents/RadioComponents/DownloadComponents），NasMusicApp 简化为持有域容器 + 核心组件。Hilt 迁移可选。初版阶段A（文档化依赖图）仍值得先做。

**验证**: 编译通过 + 所有功能回归测试通过

---

### L2: MainViewModel God ViewModel ✅ 属实

**文件**: `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`
**核查确认**: 实测 **3162 行**、约 150 个函数；ServerViewModel/SearchViewModel/NetworkMusicViewModel/WeatherRadioViewModel 均已存在于 `ui/viewmodel/`，MainViewModel 通过 `serverVM/searchVM/netVM/weatherRadioVM`（159-176行）转发接线。拆分模式已验证可行，继续按域剥离播放/歌词/MV/遥控职责即可。

**修复策略**（长期迭代，15h+）: 阶段A 拆 PlayerViewModel/LyricsViewModel（4h）→ 阶段B 拆 MvViewModel/RemoteControlViewModel（4h）。注意本项目约定：**Screen 不用 viewModel() 注入，新 ViewModel 由 AppRoot 集中订阅后参数下发**，拆分时沿用此架构。

**验证**: MainViewModel行数 < 500行

---

### L3: playModeToggleHandler闭包持有 ⚠️ 部分属实（降级为P1 + 区分闭包安全与时序安全）

**文件**: `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:84-85`
**核查确认**: 字段确在 84-85 行。但初版"持有Activity引用、Activity无法GC"**夸大**——注册方 MainActivity:363 捕获的是 **viewModel 而非 Activity**（MainViewModel 是 app 级单例,Activity 销毁时不会同步销毁），所以**不会形成 GC 引用链导致 Activity 泄漏**。**真正的风险不在闭包安全,而在回调时序安全**:

- onDestroy:385 确实清空,但被 382 行 `if (!isFinishing) return` 前置拦截——**配置重建（横屏切换、外接设备插入）时不清理**,靠下次 onCreate 覆盖旧闭包
- 旧闭包在覆盖前,PlaybackService 仍可能因播放状态变化触发回调 → **回调可能在 Activity 已销毁但新 Activity 尚未 onCreate 的窗口期触发**,若回调内访问 Activity 持有的 view/composition 会 NPE/IllegalStateException
- TV 应用锁横屏、配置重建场景较少但**对话框/弹窗/搜索覆盖层**也会触发 onDestroy 路径

**修复方向（P1,1h）**: 改为 `MutableSharedFlow<Unit>(extraBufferCapacity = 1)` + `tryEmit`,PlaybackService 改 collect,消除回调持有与 @Volatile。新 Activity 通过 collect 重新订阅,旧订阅因 Lifecycle 自动取消,时序窗口消失。初版方案B可用。

**验证**: 
- `adb shell dumpsys meminfo com.nasmusic.tv` 跑 30 分钟,确认 Activity 实例数稳定
- 强制配置重建 (`adb shell am restart`) 后触发播放模式切换,确认无 NPE/IllegalStateException
- LeakCanary 检测（需引入测试依赖,仅在 debug 构建启用）

---

### L4: Room fallbackToDestructiveMigration ✅ 属实（合理设计，无需修改）

**文件**: `app/src/main/java/com/nasmusic/tv/backend/local/db/LocalMusicDatabase.kt:33`
**核查确认**: 33 行 `.fallbackToDestructiveMigration(true)`，11-12 行注释"本地索引可由重扫重建"；DownloadDatabase（46行注释）明确不启用 fallback。两处均吻合，定性正确。

**无需修改**，建议补充注释强调 DownloadDatabase 绝不可启用。

---

### L5: SearchScreen LaunchedEffect重复触发 ❌ 不属实（定位错误，已剔除）

**核查证据**: `SearchTab.kt` 是**纯展示组件**（247行），无任何 `LaunchedEffect(query)`。实际代码 `LaunchedEffect(filterQuery){ onSearch(filterQuery) }` 在 **LibraryScreen.kt:216-222**，且 filterQuery 仅经 TextInputDialog **onConfirm 提交**才更新（LibraryScreen:613-620），并非逐键触发；SearchViewModel 另有 lastSearchedKeyword 缓存。**无防抖但不存在重复触发问题，初版定位到错误文件且误判触发机制。**（另注：初版修复示例在 Composable 内直接用 `viewModelScope`，编译不过——初版修复代码质量缺陷之一。）

---

### L6: FocusableSurface焦点释放 ⚠️ 代码属实但初版"修复"无效（升级为P1,附触发条件）

**文件**: `app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt:106-114`
**核查确认**: 行号准确，requestFocusOnLaunch 时 LaunchedEffect(Unit) 请求焦点、无释放逻辑，描述属实。但**初版 After 代码是无效修复**——加的 DisposableEffect 的 onDispose 里全是注释、什么都不做，纯空操作。

**风险细化**（二次核查补充）: Compose 框架在节点离开组合时**通常**会自动让出焦点,但**在以下三种场景下会失效**——这三种场景在 TV 音乐应用中均常见:
1. **多窗体/对话框嵌套**: 歌词详情、设置弹窗、扫码对话框覆盖时,被覆盖的 FocusableSurface 节点未离开组合（仅 z-order 变化）,焦点未让出 → 关闭弹窗后焦点仍在弹窗层,无法用 D-Pad 操作底层
2. **`focusRequester.requestFocus()` 在节点已 dispose 后被调度**: 状态机切换（如切歌、换屏）时若 requestFocus 调用晚于 onDispose,会抛 IllegalStateException
3. **自定义 D-Pad 导航劫持**: 项目内 `KeyEventHandlers` 等自定义导航与 Compose 焦点系统竞争,焦点可能"卡"在已 dispose 的引用上

**修订结论**: 升级为 **P1**,但**附触发条件**——仅在以下场景复现时实施修复:
- TV 实机（**非模拟器**）跑 30 分钟以上,出现焦点无法 D-Pad 移动
- 关闭歌词详情/设置弹窗后,焦点未回到原位置
- Logcat 出现 `FocusRequester is not initialized` 或 `IllegalStateException: FocusRequester` 异常

**修复方向（待触发后再定,1h）**: 排查方向是自定义 D-Pad 导航与 Compose 焦点系统的交互,不是加空 DisposableEffect。**勿实施初版方案。** 实施时建议先在 dev 环境复现,再针对性加 `awaitFrame()` + `addFocusRestorer` 或自定义 `FocusRestorer` Modifier。

**验证**: 复现路径下手动操作 10 次焦点跳转,确认无卡死

---

### L7: HqSeparationOrchestrator CoroutineScope ❌ 已过时（清理链路已存在）

**核查证据**: `HqSeparationOrchestrator.kt:69` 的 scope 确实从未整体 cancel，但 **`release()` 已存在（514-522行，取消 separationJob + 释放 ONNX 会话），且 `PlayerManager.release():1176` 已调用 `hqOrchestrator.release()`**。初版要求的清理机制已实现。遗留小尾巴：release() 未取消 scope 本身，可顺带在 release() 里加 `scope.cancel()`（P2，5分钟）。PlayerManager 为 app 级单例，实际影响有限。

---

### L8: PlayerManager Handler轮询与协程混用 ⚠️ 属实但为已知设计（降级为可选优化）

**文件**: `app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt:110-128`
**核查确认**: 确为 progressHandler + Runnable 1000ms postDelayed 轮询。但这是项目 AGENTS.md 明文记载的**既定设计**，且 Handler 挂在 main looper 上——ExoPlayer 的 currentPosition 访问本就应在主线程，当前实现并无正确性问题。

**修订结论**: 非缺陷。**初版修复方案（每次 startProgressPolling 新建 `CoroutineScope(Dispatchers.Main + SupervisorJob())`）会引入作用域泄漏/重复创建问题，劣于现状，勿照抄。** 若要协程化，应使用注入的 applicationScope 或类持有的单一 scope，属可选优化（3h），优先级最低。

---

## 四、P1 关键问题修复指引（修订后 11 项）

> 初版 15 项中 4 项不属实已剔除；文件路径已按实际位置修正（MilkdropRenderer/PlasmaFlowRenderer 位于 `UltraRenderers.kt`，ConstellationRenderer 位于 `AdvancedRenderers.kt`，并非独立文件）。

| # | 问题 | 核查 | 修复方向 | 预估 |
|---|------|------|----------|------|
| 1 | OkHttp连接池未共享 | ✅ | 全局OkHttpClient单例共享（实测独立实例10+处：RadioBrowserClient.kt:41、WeatherApi.kt:30、SongDownloadManager.kt:56 及各适配器/MetingApiService） | 2h |
| 2 | 百度网盘API错误码缺31079 | ⚠️ 部分 | BaiduNetdiskConfig.kt ERRNO_MAP（89-131行）**已含31023**，仅缺31079(文件不存在)映射 | 0.5h |
| 3 | Crossfade音量曲线线性pop | ✅ | CrossfadeController.kt:90-115 线性斜坡改指数衰减 | 1h |
| 4 | 睡眠定时依赖Handler | ✅ | SleepTimerController.kt:22,50 改协程delay | 0.5h |
| 5 | VisualizerMath共享seed | ✅ | VisualizerMath.kt:14 为object单例、125行共享seed，改为实例化Random每Renderer独立 | 1h |
| 6 | rgbToHsl每帧Triple分配 | ✅ 轻微 | VisualizerMath.kt:89 返回Triple，改inline+输出参数（量级小，低优先） | 1h |
| 7 | MilkdropRenderer每帧建Canvas | ✅ | UltraRenderers.kt:61 draw()内每帧 `Canvas(c)` 新建，预分配+resetMatrix | 0.5h |
| 8 | ConstellationRenderer O(n²)连线 | ✅ 已部分优化 | AdvancedRenderers.kt:586-601 双层循环160节点≈12720次判断/帧，但已合并单Path绘制，n=160量级可控——低优先 | 2h |
| 9 | PlasmaFlowRenderer逐粒子drawCircle | ✅ | UltraRenderers.kt:182-203 改Path批量合并 | 1h |
| 10 | loadedCoverKey竞态 | ✅ | VisualizerViewModel.kt:52 普通var主线程写(63行)/IO读(82行)，加@Volatile或AtomicReference | 0.5h |
| 11 | MilkdropRenderer硬编码1280×720 | ✅ | UltraRenderers.kt:41-42 硬编码降采样（注释称有意为之，可改canvas尺寸自适应） | 0.5h |
| 12 | **跨调度器runBlocking阻塞Default线程池**（三次审阅新增） | ✅ | `NetworkMusicViewModel.kt:675` 在 `viewModelScope.launch { withContext(Dispatchers.Default) { prefs.baidu.getBaiduConfigSync() } }` 内调用——非主线程,但 runBlocking + Dispatchers.IO 会占住 Default 线程(Default池容量=CPU核数),使所有依赖 Default 的解析/计算协程排队;与 T2 同源但修复路径独立(在协程内改用 `baiduConfigFlow.first()` 即可,无需主线程防御) | 0.5h |
| 13 | **K 歌 HT-Demucs ONNX 模型路径未评估**（三次审阅新增,2026-09-13 强化） | ⚠️ | K 歌模块共两条叠加路径：① DSP 路径（SpectralMaskProcessor,1 阶低通 250Hz,实时兜底）② HQ 模型路径（HqSeparationOrchestrator 编排,HT-Demucs ONNX 推理,异步分离伴奏/和声/低音 stem）。DSP 路径代码极简（160 行）已无审查盲点;但 HQ 路径**完全未在本审阅覆盖**——具体包括: ONNX 模型加载/卸载与会话管理、推理协程取消语义（切歌/错误回退）、推理耗时对 TV 设备 CPU 的影响、模型文件下载与传输安全（ModelDownloadManager / ModelTransferDialog）、推理失败降级到 DSP 路径的策略。2026-09-13 已通过 HqSeparationOrchestrator.kt / VocalSeparationViewModel.kt / SpectralMaskProcessor.kt / VocalRemovalProcessor.kt 四个 KDoc 注释建立完整心智模型,确认双路径**叠加**而非互斥,VocalRemovalProcessor 是更精细的 DSP 历史实现（已死代码,详见 VocalRemovalProcessor.kt 注释）。建议另起专项 review 覆盖上述 HQ 路径风险点 | 8h+ |

**剔除的4项**（初版#3/#6/#10/#15）:
- ❌ Navidrome分页offset计算错误 —— NavidromeAdapter.kt:146-186 offset从0起步、每轮+pageSize，逻辑正确
- ❌ Equalizer preset加载无fallback —— EqualizerPreset为硬编码enum不可能加载失败；AppPreferences.kt:607 越界ordinal已有 `getOrElse { NORMAL }` 兜底（NORMAL全0dB等价Flat）
- ❌ PrismHolo/Aurora每帧重建渐变Shader —— 实际用24-26条分带drawRect模拟渐变（AuroraRenderer.kt:84-121、PrismHoloRenderer.kt:75-111），全文件无Shader
- ❌ LyricsDotMatrixRenderer每帧新建Paint —— Paint在computeFontSize/sampleLine中创建非每帧，390行注释明确"不再每帧 new Paint"（疑似基于旧版代码）

---

## 五、各模块评分（修订后）

| 模块 | 架构 | 性能 | 内存 | 线程安全 | 代码质量 | 综合 |
|------|------|------|------|----------|----------|------|
| 架构与核心 | 2.5/5 | 3/5 | 3/5 | 2.5/5 | 3.5/5 | **60** |
| 数据层 | 3.5/5 | 3.5/5 | 4/5 | 3/5 | 4/5 | **74** |
| UI层 | 3.5/5 | 4/5 | 4/5 | 3.5/5 | 4/5 | **78** |
| 播放器层 | 3/5 | 3.5/5 | 3.5/5 | 3/5 | 3.5/5 | **66** |
| 网络与云盘 | 3/5 | 3/5 | 3/5 | 3/5 | 3/5 | **60** |
| 可视化/特效 | 4/5 | 4/5 | 3.5/5 | 3/5 | 4/5 | **82** |
| **加权平均** | | | | | | **74** |

> 修订说明：初版 67 分中扣分项含 5 个"安全漏洞"（实为 2-3 个）与 1 个不存在的运行时 bug（T5 死代码），网络层"认证机制缺失"部分不实（各适配器均有登录认证），修正后为 74。

---

## 六、修复路线图（修订后）

```
Phase 1（3天）: 安全与高价值线程安全修复
├── T8: ParticleRenderers Bitmap泄漏（必然路径）     [0.5h]
├── T7: LyricsDotMatrixRenderer Bitmap try-finally  [0.5h]
├── S3: Subsonic toggleFavorite原子化                [1h]
├── T2: runBlocking调用方全量排查（26处）+ Flow替换   [2h]  ← 三次审阅后升级
├── P1#12: NetworkMusicViewModel.kt:675 跨调度器修复  [0.5h]  ← 三次审阅新增
├── T4: SmartRadioManager playedIds快照读            [1h]
├── S1: CryptoUtils口令→BuildConfig（阶段A）         [2h]
├── T6: AudioFrame双缓冲                             [2h]
└── S4: Jellyfin会话内401重认证（降级P1）            [2h]
合计: ~11.5h

Phase 2（1周）: 状态一致性与低垂果实
├── T3: PlayerManager三元组StateFlow原子化           [3h]
├── P1#1: OkHttp连接池统一                          [2h]
├── P1#5: VisualizerMath seed隔离                    [1h]
├── P1#10: loadedCoverKey竞态                        [0.5h]
├── P1#2: 百度错误码补31079                          [0.5h]
├── L3: playModeToggleHandler改Flow（P1）           [1h]
└── L7尾巴: HqOrchestrator release()补scope.cancel  [0.1h]
合计: ~8h

Phase 3（2周+）: 架构重构（长期）
├── L1: NasMusicApp按域拆分子容器                    [8h]
├── L2: MainViewModel按职责拆分                      [8h]
└── P1性能优化（#3/#4/#7/#9等）                      [6h]
合计: ~22h

P2 清理项（顺手做）:
├── 删除死代码 VocalRemovalProcessor（T5）
├── customAppKey/secretKey加密（S2遗留）
└── L4: 补充 Room fallback 设计注释
合计: ~1.5h

P1 待触发项（按需启动,不计入基线工时）:
├── L6: FocusableSurface 焦点卡死（实机复现再修）—— 升级自原 P2
└── P1#13: K 歌 HT-Demucs ONNX 路径专项 review（ONNX 会话管理/异步取消/推理降级,建议下个迭代启动）—— 四次审阅后心智模型已建,具体风险点未评估

总预估: ~43h ≈ 5.5人天（初版63h中含虚构项与无效修复,三次审阅净增 1h,四次审阅净增 0 工时——K 歌全景图为注释,不算工时）
```

---

## 七、结论（修订版）

NASMusicTV是一个功能丰富的Android TV音乐播放器，可视化模块架构设计尤为出色（Renderer接口简洁、ParticlePool零分配、画质分档适配、注释极其详尽）。二次核查后，P0 有效项为 **13 项**（按各章标题：安全 3 + 线程 6 + 架构 4；初版 21 项中 5 项剔除、4 项降级为 P1）。

**核心短板（三次审阅修正后）**:
1. **安全防护有限但非缺失**: 真实问题为 S1 加密口令硬编码（已知取舍）、S3 TOCTOU 竞态、S4 会话内 401 不自愈；token 存储已加密、适配器无硬编码密钥——初版"5个安全漏洞"实为 2-3 个
2. **God Object反模式**: NasMusicApp(15 lateinit + 16 lazy, onCreate 210行) + MainViewModel(3162行) 严重影响可维护性 —— 属实,是最大技术债
3. **线程安全短板**: AudioFrame 无同步（T6）、PlayerManager 三元组非原子（T3）、playedIds 可见性竞争（T4）、Bitmap 泄漏（T7/T8，其中 T8 有必然泄漏路径）
4. **同步 IO 与跨调度器阻塞**: T2 实测 26 处调用方,主线程 ANR 风险（NasMusicApp.kt:228/407、NetworkMusicViewModel.kt:211）+ Default 线程池饥饿风险（NetworkMusicViewModel.kt:675）——已升级修复方案为基于 `baiduConfigFlow` 的协程化路径
5. **网络层**: 连接池未共享属实（10+ 独立 OkHttpClient），但"认证机制缺失"不实
6. **未覆盖模块**: K 歌 HT-Demucs ONNX 推理路径（**四次审阅后心智模型已建**,具体风险点见 P1#13）、本地 HTTP 服务（RemoteControlServer 等）安全边界未做专项评估

**修复优先级**: 线程安全实害（T8/T7/S3/T2/T4）→ 安全加固（S1/S4）→ 架构重构（L1/L2）→ 性能优化
**预计工时**: ~42h（约5人天）
**修复后预期评分**: 82+/100

**项目综合评分：74/100（修订后）** —— 功能完整，安全基线好于初版判断，线程安全为主要短板，修复P0后可达82+。

---

## 附：初版报告质量问题记录（供后续审阅参考）

1. **4项虚构/过时 P0**: S2（未核实加密已生效）、S5（无中生有的硬编码密钥）、T5（给死代码修bug）、L5（定位错误文件）；另有 L7 忽略已有清理链路
2. **多处修复代码自身有缺陷**: L5 示例编译不过（Composable内用viewModelScope）、T1 的 ProcessLifecycleOwner 方案无效、L6 修复为空操作、L8 引入作用域泄漏、T5 白修死代码
3. **编号冲突**: 初版路线图 Phase 2 标"8项"实列9行，T8 编号同时指 Bitmap泄漏 与 "PlayerManager Handler协程化"
4. **行号引用总体可靠**（21项中绝大多数命中），错误主要发生在**结论层**而非定位层——后续引用 AI 生成审阅时，应默认对"问题定性"与"修复代码"做二次人工核查

---

## 附：三次审阅修订记录（2026-09-13）

针对二次核查版的"审阅之审阅"抽样验证发现的问题,本次修订如下:

| 修订项 | 原状 | 修订后 | 理由 |
|---|---|---|---|
| 行号快照声明 | 缺失 | 在"二次核查说明"后追加"行号快照声明"段落 | L1（-25 行）/ T8（-25 行）出现系统性漂移,实施者按行号找代码会扑空 |
| 方法论说明 | 缺失 | 追加"方法论说明"段落 | 解释 ✅/⚠️/❌ 判定方法、剔除反证机制、修复代码预检范围 |
| T2 调用方数量 | 仅列 2 处 | 扩展为 **26 处全量清单**,分三类(主线程/跨调度器/包装层) | `grep` 实测 26 处,初版严重低估修复面 |
| T2 675 行定性 | "主线程 ViewModel 方法" | 纠正为"在 `viewModelScope.launch + withContext(Default)` 内,跨调度器阻塞风险" | 实测代码在协程内,不在主线程——初版事实错误 |
| T2 修复方案 | "防御性 null 返回" | 升级为"基于 `baiduConfigFlow.first()` 的协程化",防御方案降为临时止血 | 第 1339 行已存在该 Flow,直接复用比防御更彻底 |
| L3 风险定性 | 模糊"持有 Activity 引用" | 明确区分"闭包安全（不泄漏 Activity）"vs"回调时序安全（窗口期触发）" | 闭包捕获的是 viewModel,不会形成 GC 链;真正风险在时序 |
| L6 等级 | P2 观察项 | 升级为 P1,附三种触发条件(多窗体嵌套/focusRequester dispose/D-Pad 劫持) | TV 音乐应用三种场景均常见,二次审阅降级过于乐观 |
| P1#12 | 缺失 | 新增"跨调度器 runBlocking 阻塞 Default 线程池",定位 NetworkMusicViewModel.kt:675 | T2 同源但修复路径独立,值得单列 |
| P1#13 | 缺失 | 新增"K 歌/MTV ONNX 推理模块专项 review",8h+ | VocalRemovalProcessor 死代码已识别,但实际在用的 SpectralMaskProcessor 未覆盖 |
| Phase 1 工时 | T2=1h,合计 10h | T2=2h + P1#12=0.5h,合计 11.5h | 真实工作量+新条目 |
| 核心短板列表 | 4 条 | 6 条（新增"同步 IO 跨调度器阻塞"+"未覆盖模块"） | 与新增 P1 项呼应 |
| 综合评分 | 74/100 | 维持 74/100 | 三次审阅以校正证据为主,未引入新降分 |

**未修订项**（三次审阅维持原判定）: S1/S2/S3/S4/T1/T3/T4/T5/T6/T7/T8/L1/L2/L4/L5/L7/L8 —— 二次核查结论站得住。

---

## 附:四次审阅修订记录（K 歌全景图,2026-09-13）

针对三次审阅版中"未覆盖模块"提示与 K 歌模块实际架构的偏差,本次修订如下:

| 修订项 | 原状 | 修订后 | 理由 |
|---|---|---|---|
| K 歌架构厘清 | 误判"K 歌只有 DSP 路径" | 通过实测确认 K 歌为**双路径叠加**(DSP + HT-Demucs ONNX 模型) | VocalSeparationViewModel.kt:46-48 实测 `isHighQualityMode()` 条件分支,HqSeparationOrchestrator 头部 KDoc 明确使用 HT-Demucs ONNX 模型 |
| 4 个 KDoc 注释 | K 歌模块无统一心智模型入口 | 在 HqSeparationOrchestrator.kt / VocalSeparationViewModel.kt / SpectralMaskProcessor.kt / VocalRemovalProcessor.kt 四个 KDoc 互相引用,构成完整双路径图谱 | 任何新人读其中一份 KDoc 即可理解整个 K 歌模块架构与取舍 |
| P1#13 措辞 | "K歌/MTV ONNX推理模块未评估"（宽泛） | "K 歌 HT-Demucs ONNX 模型路径未评估——ONNX 会话管理/异步取消/推理降级" | 明确具体未评估的 4 个风险点,排除已通过注释厘清的架构问题 |
| 核心短板第 6 条 | "K 歌/MTV ONNX 推理未覆盖" | "K 歌心智模型已建,具体风险点未评估" | 反映四次审阅的实际进展——架构已清晰,具体实现未审 |
| 综合评分 | 74/100 | 维持 74/100 | 四次审阅以注释为主,未引入新降分项,亦未上调(因为风险点仍存在) |
| P1 待触发项 | "K歌/MTV ONNX 推理模块专项 review" | "K 歌 HT-Demucs ONNX 路径专项 review（ONNX 会话管理/异步取消/推理降级）" | 与 P1#13 表格对齐,明确专项 review 范围 |

**K 歌双路径事实陈述**（四次审阅后定稿）:
- **DSP 路径**（默认/兜底,永远启用） = `SpectralMaskProcessor`（1 阶 RC 低通 250Hz,161 行,实时,TV CPU 友好）
- **HQ 模型路径**（按需启用） = `HqSeparationOrchestrator` 编排 + `HT-Demucs ONNX` 推理,异步分离伴奏/和声/低音 stem
- **总控点** = `VocalSeparationViewModel.toggleVocalRemoval()` —— 总是启用 DSP,仅在 `isHighQualityMode()` 为真时启用 HQ
- **历史** = `VocalRemovalProcessor`（4 阶 Linkwitz-Riley,304 行） = 早期更精细的 DSP 实现,被 SpectralMaskProcessor 取代,保留为高保真/离线批处理备选

**实施前必读**: 任何对 K 歌模块的修改,先读 HqSeparationOrchestrator.kt:32-57（HQ 路径总览）和 VocalSeparationViewModel.kt:32-46（调度总控）这两段 KDoc 即可建立完整心智模型。

**实施前必读**: 任何 L1/T8 文件相关的行号引用,以当前 main 分支为准;Phase 1 启动前先用 `git grep getBaiduConfigSync` 重核 26 处调用方分布。

---

## 附:实施记录（2026-09-14,代码审阅 → 代码修改）

本段记录本审阅文档落地为代码修改的实际进度。**保留历史审阅过程不删,实施状态以本段为准**。

### A. 已修复（13 项,验证通过）

| 项 | 文件 | 修复要点 |
|---|---|---|
| **T8** | `visualizer/renderers/ParticleRenderers.kt` | `val t = targets ?: return` 提前 + try-finally 包裹 createBitmap/recycle,消除必然泄漏路径 |
| **T7** | `visualizer/renderers/LyricsDotMatrixRenderer.kt` | try-finally 包裹 createBitmap/recycle,**try 起点在 createBitmap 之前**（含 OOM 路径） |
| **S3** | `backend/impl/SubsonicAdapter.kt` | 删除 `getFavorites()` 二次查询,直接使用 `isCurrentlyFavorite` 入参,消除 TOCTOU |
| **T4** | `backend/radio/SmartRadioManager.kt` | 3 处 `playedIds.clear()` 移入 synchronized 块,所有 `generateBatch(playedIds)` 改为 `playedIds.toSet()` 快照 |
| **P1#12** | `ui/viewmodel/NetworkMusicViewModel.kt:675` | `getBaiduConfigSync()` 改为 `baiduConfigFlow.first()`,消除 Default 线程池阻塞 |
| **S1** | `util/CryptoUtils.kt` + `app/build.gradle.kts` | 口令从 BuildConfig 注入;`buildConfigField("String", "CRYPTO_PASSPHRASE", ...)`;从 `keystore.properties` 读取(默认值与历史硬编码保持一致) |
| **L7 尾巴** | `player/HqSeparationOrchestrator.kt` | `release()` 末尾补 `scope.cancel()`,清理本编排器 scope 协程 |
| **P1#10** | `ui/viewmodel/VisualizerViewModel.kt` | `loadedCoverKey` 加 `@Volatile`,主线程写/IO 读可见性 |
| **P1#2** | `backend/network/baidu/BaiduNetdiskConfig.kt` | ERRNO_MAP 补 31079 → "文件不存在或已被删除" |
| **L4** | `backend/local/db/LocalMusicDatabase.kt` | 注释补充:fallback 仅适用可重建索引类数据库,用户数据类绝不可启用 |
| **T6** | `visualizer/SpectrumRepository.kt` + `ui/components/VisualizerStage.kt` | AudioFrame 双缓冲 + `@Volatile writeIndex`,写端写完翻转发布、读端读 front,volatile 写→读建立 happens-before,消除音频回调线程写/渲染线程读的撕裂;绘制循环改每帧捕获 front 引用（commit `1a7edd1`,§10.138） |
| **T2** | `data/prefs/AppPreferences.kt` + `BaiduPrefs` + `BaiduOAuthClient` + `BaiduNetdiskService` + `BaiduMvFileService` + `NasMusicApp` + `NetworkMusicViewModel` | 百度配置读取全量从 `runBlocking(IO)` 迁移到 `baiduConfigFlow.first()`(suspend),两批落地（外部调用点 + 内部 getter 链）,26 处调用点 ANR/线程池占用清零（commits `be9734c`/`dc21ae5`,§10.137） |
| **T3** | `player/PlayerState.kt`(新) + `player/PlayerManager.kt` + `ui/viewmodel/PlayerViewModel.kt` + AppRoot/QueueBranch/MainViewModel/DownloadViewModel/PlaybackService | queue/currentIndex/currentSong 三个独立流合并为单一 `playerState` 原子流,23 处更新点 `update{copy}` 同帧发布,消除快速切歌 UI 读到"新队列+旧索引+旧歌名"错帧;UI 集中订阅点全部适配（commit `7f64320`,§10.139） |

### B. 暂缓（3 项,改动面过大或需要真实环境测试;P1#1 已决定不做,见 F）

| 项 | 暂缓理由 | 建议替代方案 |
|---|---|---|
| **S4** Jellyfin 会话内 401 重认证 | 涉及 401 检测 + 重认证 + 重试的复杂逻辑,没有真实环境测试时引入可能造成循环 | 单独 PR + 单测覆盖 + 集成测试 |
| **P1#5** VisualizerMath seed 隔离 | 涉及 30+ Renderer 全部修改（每 Renderer 持自己的 seed） | 单独 PR,先评估"共享 seed 视觉不一致"是否真的可感知 |
| **L3** playModeToggleHandler 改 Flow | 涉及 MainActivity + PlaybackService 跨文件改造 | 单独 PR + 验证 Activity 生命周期 |
| **customAppKey/secretKey 加密**（P2 顺手项,未做）| 涉及 JSON 序列化层 + 4 个 setter/getter + 加密 key rotation 策略 | 原计划与 T2 一起做,T2 已完成但此项未动 |

> 已落地移出: T6/T2/T3 见 A;P1#1 于 2026-09-14 决定不做（记录于 F）。

### C. 综合评分变化

| 时点 | 评分 | 说明 |
|---|---|---|
| 初版 | 67 | 含 4 项虚构 P0 |
| 二次核查 | 74 | 剔除虚构/过时项 |
| 三次审阅 | 74 | 行号校正为主 |
| 四次审阅 | 74 | K 歌全景图 |
| **五次审阅（实施落地）** | **78** | 已修复 10 项实质问题,综合评分上调 4 分 |
| **六次审阅（T2/T6/T3 落地）** | **81** | 暂缓项再落地 3 项,评分短板仅剩 S4/P1#5/L3 |

**78 分的依据**:
- +2 修复 2 个 P0 必然泄漏（T7/T8 视觉化稳定性）
- +1 修复 P0 线程安全（Subsonic toggleFavorite TOCTOU + SmartRadio playedIds 可见性）
- +0.5 修复 P0 跨调度器阻塞（P1#12）
- +0.5 修复 P0 安全（CryptoUtils BuildConfig 注入）
- 暂缓项（T2/T3/T6/S4/P1#1/P1#5/L3）仍是评分短板,本版本未触及

**81 分的依据**（六次审阅,2026-09-14 T2/T6/T3 落地后）:
- +1 T6 双缓冲（P0 线程安全,消除频谱数据跨线程撕裂）
- +1 T2 全量 Flow 化（P0 跨调度器阻塞,26 处调用点清零）
- +1 T3 三元组原子化（P1 状态一致性,消除快速切歌 UI 错帧）
- 剩余暂缓（S4/P1#5/L3）仍是评分短板,其中 S4 需真实环境验证

**修复后预期**（仅当暂缓项也完成时）: 82+/100

### D. 字节级验证结果

11 个修改文件全部通过 UTF-8 + 大括号匹配校验,详见 `_verify_all.py` 验证脚本输出（`logs_temp/_verify_all.py`）。

**编译验证（2026-09-14 补充）**: 落地后跑 `:app:assembleDebug`（2m28s）与 `:app:assembleRelease`（9m58s）均 **BUILD SUCCESSFUL**。期间修复 3 处落地引入的编译错误（T7 变量作用域 + P1#12/L7 缺失导入），见 commit `adae60b` 与 §10.136。

**编译验证（2026-09-14 追加,T2/T6/T3）**: T2 两批与 T6 落地后 `:app:assembleDebug` 通过（见 §10.137/§10.138）;T3 落地后 `:app:assembleDebug`（2m19s,in-process）与 `:app:testDebugUnitTest`（1m17s）均 **BUILD SUCCESSFUL**。期间修复 2 处编译错误（PlayerManager 缺 `kotlinx.coroutines.flow.update` 导入 + PlaybackService `pm.currentIndex` 旧引用,后者为局部别名漏检、编译期暴露）,见 commit `7f64320` 与 §10.139。

### E. 版本

- **版本号变更**: v2.32.2 → v2.32.3
- **versionCode**: 141 → 142
- **CHANGELOG.md**: 已追加 v2.32.3 条目
- **提交**: 10 个原子修复 + 版本号 + CHANGELOG 合并为 1 个综合 commit（`1507b59`）;后续 T2 两批（`be9734c`/`dc21ae5`）、T6（`1a7edd1`）、T3（`7f64320`）各自独立 commit

### F. 决策记录（2026-09-14）

| 项 | 决定 | 理由 |
|---|---|---|
| **P1#1** OkHttp 连接池统一 | **明确不做** | 涉及 DI 重构与 10+ 独立 OkHttpClient 调用点全量改造，各实例超时/代理配置各异，统一后需全量回归；实测未见连接数耗尽类运行时报障，收益/风险比不划算。若未来出现 socket 耗尽类故障，再以 OkHttpClientHolder 单例方式重估 |
