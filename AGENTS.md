# AGENTS.md — NASMusicTV

Compact guide for OpenCode sessions. Every line is something an agent would likely miss without help.

## Project Overview

Android TV music player (Kotlin + Jetpack Compose for TV) that connects to **Jellyfin** or **Navidrome** backends, plus an independent **network music** layer (Meting-API search) and a **weather radio** feature. Single-module Gradle project.

- Package: `com.nasmusic.tv` — Min SDK 22, Target SDK 34, Java 17
- Kotlin 2.2.10; TV Compose `androidx.tv:tv-material` is **alpha** (`ExperimentalTvMaterial3Api` opt-in set in `app/build.gradle.kts`, used throughout)
- GPL v3

## Build (Windows)

JDK comes from Android Studio's bundled JetBrains Runtime. Gradle wrapper points at a **local file URL** (`file:///C:/Users/hxzha/Downloads/gradle-9.5.0-bin.zip`) — intentional for offline builds; do not "fix" it to a network URL.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

./gradlew.bat assembleDebug      # debug APK
./gradlew.bat assembleRelease    # needs ./keystore.properties (gitignored)
./gradlew.bat test               # unit tests (Robolectric + JUnit4)
```

**adb 不在 PATH** — 完整路径：`C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe`。安装到电视（无线连接）：

```powershell
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" connect 192.168.0.114:5555
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 install -r app\build\outputs\apk\release\app-release.apk
```

> 电视上若已装 debug 版（签名不同），`install -r` 会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先 `uninstall com.nasmusic.tv` 再安装。

CI (`.github/workflows/build.yml`) has three jobs: **`build`** runs `assembleRelease` (push to `main`/`develop`, tags `v*`; PR to `main`), **`test`** runs `testDebugUnitTest`, and **`lint`** runs `lintDebug` — lint is now **blocking** (the `continue-on-error` was removed on 2026-09-14 after errors were driven to 0; 256 warnings remain and do not fail it). So a green CI means "compiles + unit tests pass + no new lint errors". Note the CI `build` job generates a throwaway `keystore.properties` (CI signing key) — it must include a `cryptoPassphrase` line, otherwise the `packageRelease` guard in `app/build.gradle.kts` fails the build.

**本地构建必须加 `--no-daemon`**（本机实测，2026-09-14）：Gradle 守护进程 fork 出的子进程会全部失败——AAPT2 报 `Daemon startup failed / Please check if you installed the Windows Universal C Runtime`（即使资源只改一个字符串也会触发）、测试 worker 立刻退出（exit `268435466` = `0x1000000A`，低 16 位是 Windows `ERROR_BAD_ENVIRONMENT`）、Kotlin 编译守护进程报 `AccessDeniedException`。用 `--no-daemon`（Kotlin 侧再叠 `-Pkotlin.compiler.execution.strategy=in-process`）即可全部绕过：

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat assembleDebug lintDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

`--no-daemon` 只影响当前进程，不需要改 `gradle.properties`。注意 `aapt2.exe` 本身能独立运行（`aapt2 version` 正常），问题只出在守护进程模式，所以别被 "Windows Universal C Runtime" 的提示误导。

**单测在本机跑不起来**：即使加了 `--no-daemon`，`testDebugUnitTest` 仍失败（worker JVM 一启动即死，`test-results/` 下 0 个 XML；用 `--tests "*TimeUtilsTest"` 这类纯 JVM 单类隔离验证同样失败）。属环境问题非代码问题。**因此本机对代码改动的验证手段只有「编译 + lint」，不能声称"测试通过"**；单测只能靠 CI。

## Architecture (verified against source)

- **Manual DI container**: `NasMusicApp` constructs and holds the global instances — `backendRegistry`, `appPreferences` (`AppPreferences`), `playerManager` (`PlayerManager`), `networkMusicManager` (`NetworkMusicManager`). There is **no DI framework** and **no `getInstance()` singleton** — these are plain classes instantiated once in `NasMusicApp.onCreate()`. Access them by casting `Application` to `NasMusicApp` (e.g. `MainViewModel` does `app as NasMusicApp`).
- **Backend adapters**: `BackendAdapter` interface; live implementations in `backend/impl/` (`JellyfinAdapter`, `NavidromeAdapter`). Both use **raw OkHttp**, not Retrofit. `BackendRegistry` holds the active adapter in memory only (reconnect on restart reads `AppPreferences`). `testConnection()` builds a throwaway adapter and never mutates current state.
- **Playback**: `PlaybackService` (Media3 `MediaLibraryService`) creates the ExoPlayer and registers it with `PlayerManager`. Progress is driven by a 1000ms `Handler` polling loop in `PlayerManager`, **not** ExoPlayer callbacks. Network songs carry no resolved `streamUrl` until played — `PlayerManager.onNeedResolveStreamUrl` signals `MainViewModel` to resolve then replay.
- **Lyrics**: `LyricsManager` is constructed in `MainViewModel` (takes `context`, `backendRegistry`, `networkMusicManager`). It **does** call `BackendAdapter.getLyrics()` (Jellyfin lyric endpoint). Priority: local cache → backend API / `NetworkMusicManager` → network match (fuzzy title+artist). Older docs describing MP3-embedded / local-LRC-file scanning are stale.
- **Network music**: `backend/network/` — `MetingApiService` + `NetworkMusicManager` (multi-endpoint fallback, 302 redirect resolution, favorites cache LRU 500). Independent of the NAS backend.
- **Weather radio**: `backend/weather/` — `WeatherApi` (OpenWeatherMap) + `WeatherRadioManager` matches songs by mood. `BackendAdapter` was made nullable so weather radio works with no NAS connection.
- **Navigation**: single `com.nasmusic.tv.ui.MainActivity` (entry class is `.ui.MainActivity`, **not** `com.nasmusic.tv.MainActivity` — `am start -n com.nasmusic.tv/.ui.MainActivity`) with a manual `when(currentScreen)` switch (`Screen` enum in `data/model/Screen.kt`) — no Jetpack Navigation despite the `navigation-compose` dependency. Three-level BACK: close dialog → NowPlaying → exit confirm.

## Non-obvious constraints

- **GBK encoding gotcha** (`util/EncodingUtils.kt`): Jellyfin may store GBK ID3 bytes as if UTF-8. `utf8Body()`/`fixEncoding()` attempt GBK fallback when U+FFFD / Greek / Cyrillic chars appear. Some cases are unrecoverable client-side (already-encoded Unicode codepoints) — don't assume you can fully fix artist-name mojibake.
- **Pinyin search** uses TinyPinyin (`com.github.promeg:tinypinyin`), chosen specifically because the min SDK is API 22 and `android.icu.Transliterator` needs API 26+. Do not swap back to ICU-based pinyin.
- **Cleartext traffic** is enabled (`usesCleartextTraffic=true`) for local NAS HTTP. **Leanback required** — app only installs on TV devices; no touch UI, D-Pad only, landscape locked.
- **ProGuard**: release build minifies + shrinks. Rules in `proguard-rules.pro` keep `data.model`, `data.prefs`, `backend`, Gson, ExoPlayer. A prior release crash (v2.5.1) came from Gson type erasure under R8 — keep those `-keep` rules when adding serialized models. `Log.d/v` are stripped in release via `-assumenosideeffects`.
- **Multi-ABI**: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
- **Demucs 人声分离的输出契约**（`player/DemucsSeparator.kt`，2026-09-14 起）：`decodeAudioToTempFile()` 保证**输出恒为 44100Hz 立体声**——单声道源复制成 L/R，非 44100Hz 源由私有类 `LinearResampler` 线性插值归一化。因此 `writeWavHeader()` / `patchWavDataSize()` 无条件用 `SAMPLE_RATE`/`CHANNEL_COUNT` 是安全的；**若日后放开该保证，这两处必须改为接收实际参数**。`totalSamples` 取 `writeFrame()` 的调用次数，不要用「float 数 / 声道数」反推。
- **Demucs 模型完整性**（`player/ModelDownloadManager.kt`）：`EXPECTED_SHA256` 取自 HuggingFace LFS 的 `oid`（对 LFS 对象而言 `oid` 就是 SHA-256）。下载完成后必须通过 SHA-256 才落盘，加载模型前再由 `verifyModelIntegrity()` 校验一次。**该校验对自定义 URL 同样生效**——自定义源定位是「自建镜像/NAS」，要提供字节一致的文件；换权重必须同步改 `EXPECTED_SHA256`。注意代码下载的是 **fp16** 权重（165,612,636 字节），上游另有 316MB 的非 fp16 版本，两者 SHA 不同。`isModelDownloaded()` 保持「快速判定」定位（可能被主线程调用，166MB 哈希会 ANR），**不要在里加哈希**；其大小阈值已收紧到 **±1%**（`163,956,509 ~ 167,268,762` 字节，原来是 `> size * 0.8`，过宽且无上界）。
- **ONNX Runtime Java API 坑**：`OnnxValue.getInfo()` 返回的 `ValueInfo` 是**空接口**，shape 只在 `TensorInfo` 上——`value.info.shape` 编译不过，必须 `(value.info as? TensorInfo)?.shape`。另外 `OrtEnvironment.getEnvironment()` 是**进程级单例，绝不能 close**；`OnnxTensor` / `OrtSession.Result` 持有 native 内存（GC 回收不到），必须显式 close。
- **`onnxruntime-android` 的版本被「minSdk 22」与「16KB 页对齐」双重锁定，不要随手升级**（2026-09-14 实测）：lint 的 `Aligned16KB` 会报这个依赖（1.17.1 的 native 库 `p_align = 4096`）。实测矩阵（`p_align`，AAR 内 4 个 ABI × 2 个库）：**1.17.1** 全 4096 / minSdk 21；**1.20.0** 主库 16384 但 `libonnxruntime4j_jni.so` 仍 4096 / minSdk 21；**1.21.1** 仅 arm64 全对齐 / minSdk 24；**只有 1.29.0 全对齐（16384）/ minSdk 24**。两个必须知道的机制：① lint 的 `PageAlignmentDetector.getIncidentsFromAndroidLibrary` 遍历 AAR 解包目录下**全部 ABI**（不受 `abiFilters` 影响），命中第一个未对齐的库即 `return`，所以**每个依赖只报 1 条**，报告里的「3」是聚合重复；② 1.29.0 的 AAR manifest 要求 **`minSdkVersion 24`**，而本项目是 22——直接改会让 manifest merger 失败，改 minSdk 则砍掉 Android 5.0/5.1/6.0（含创维 5.1.1 开发机，真机回归的基准设备）。**当前有意保持 1.17.1**（是 Warning 非 Error，`targetSdk 34` 下 Play 的 16KB 强制要求尚未触发）。**触发条件**：升 `targetSdk 35` 或上架前必须处理。另外 lint 里那份硬编码的「已知安全依赖」白名单（`isDependencyKnownSafe`）**不含 `com.microsoft.onnxruntime`**，没有绕过路径。完整矩阵与决策记录见 `docs/technical-overview.md` §10.146 的 §七-4 遗留项，依赖声明处的注释在 `app/build.gradle.kts`。
- **lint 输出里的内部异常栈是既有的**：构建输出中会出现 `LintCliClient.analyzeOnly` → UAST visitor 的异常栈（`logs_temp/verify*.log` 中同样存在）。它不影响报告生成与构建结果，不要当成新引入的问题去排查。
- **构建前若报 `fileHashes.lock (拒绝访问)`**：`--no-daemon` 并不保证不起守护进程 —— `gradle.properties` 里的 `org.gradle.jvmargs=-Xmx2048m` 会强制 fork 一个**单次守护进程**。该守护进程若卡在退出序列（`PersistentDaemonRegistry.remove` 的锁竞争），会一直持有 `.gradle/<版本>/fileHashes/fileHashes.lock`，后续所有构建都在 `Could not create service of type FileHasher` 处秒失败（约 26s）。**解法：`./gradlew.bat --stop`**（会打印 `1 Daemon stopped`），再重跑。**不要 `rm` 锁文件** —— 在带 safe-delete 包装的环境里 `rm` 失败会把文件留在 Windows「删除挂起」状态，之后任何 open 都返回 `Permission denied`（权限位显示 666 可写也没用），**反而让后续构建全部失败**；`rm` 报 `Device or resource busy` 就已说明有进程持有，去 `--stop` 而不是硬删。
- **飞牛音乐（fnOS）后端协议**（`backend/impl/FeiniuAdapter.kt` + `FeiniuUrl.kt`，2026-09-14 重写）：唯一权威依据是参考项目 `fn-music-tv`（本地副本 `D:\hxzhang\MyGithubSoftware\NasAudio\fn-music-tv-v1-1-2`），完整提取结果与缺陷对照表见 `docs/feiniu-backend-improvement-plan.md`。**不要再回到第三方逆向文章的猜测端点**。易错点：① 认证头是 **`Authorization: <userToken>`（原始值，无 Bearer）**，不是 `Cookie: music-token=`（后者只在 relay 模式用）；② 登录响应字段是 **`data.userToken`**；③ 分页是 **`page` + `size`**，不是 `limit`；④ ID 全是 **GUID**，参数名是 `albumGUID`/`artistGUID`/`trackGUID`/`playlistGUID`；⑤ 流地址 `track/stream?guid=`（查询参数）、封面 `static/cover?coverId=`（按 **coverId** 而非曲目 ID）；⑥ `duration` **已是毫秒**；⑦ 信封 `{code,msg,data}`，`data` 可为 null；⑧ 默认端口 **5666**（HTTPS 5667），不是 80；⑨ **服务端没有搜索端点**，`searchSongs` 是客户端本地过滤；⑩ 适配器必须在**解析期填充 `Song.streamUrl`**（`FeiniuUrl.streamUrl`）—— 全 app 的 NAS 播放解析只认 `getSongsByIds()` 返回的 streamUrl，置空会让点播「解析失败」（2026-09-15 修复）。
- **`BackendAdapter.streamHeaders` 的注入链路**（2026-09-14 补齐）：此前该属性**定义了但全仓库无消费方**——Jellyfin/Navidrome 把凭据拼在 URL query 上，所以历史上没暴露；飞牛是第一个必须走请求头的后端。现在由 `BackendRegistry` 在连接成功时向 `BackendAuthHeaders` 绑定 **provider**（每次请求实时读取 `adapter.streamHeaders`；快照形态会漏掉静默重登换新令牌 —— 2026-09-15 改为 provider），再由 `BaiduHttpDataSourceFactory` 的拦截器按 **host 精确匹配**注入（同一客户端同时服务 ExoPlayer 播放与 Coil 封面）。**新增后端若要走请求头，只需覆写 `streamHeaders`，不要另起炉灶**；也不要放宽 host 匹配，否则令牌会随 302 泄漏到 CDN。
- **构建环境**：本机 Gradle fork 出的子进程普遍起不来（AAPT2 守护进程、测试 worker、Kotlin 编译守护进程），因此构建必须加 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`；测试 worker 仍不可用（exit `268435466` = `0x1000000A` = Windows `ERROR_BAD_ENVIRONMENT`），**`testDebugUnitTest` 在本机跑不了**，纯逻辑类请用 `kotlin-standalone-verify` 技能绕过 Gradle 做独立 JVM 验证。

## Conventions

- Git/branch/commit rules live in `.opencode/rules.md` (conventional-commit prefixes, `main`/`dev`/`feat/*`, and the rule to update `CHANGELOG.md` + `docs/technical-overview.md` §10 after verified changes). Follow that file; don't duplicate here.
- Implementation/change history is recorded in `docs/technical-overview.md` §10 — only verified changes. For change context, `docs/code-review-*.md` and `CHANGELOG.md` are more current than prose elsewhere.
- Tests: `app/src/test/`, Robolectric JUnit4. Run targeted: `./gradlew.bat test --tests "*PinyinUtilsTest"`.

## Key directories

```
backend/impl/          active Jellyfin/Navidrome adapters (raw OkHttp)
backend/network/       Meting-API network music (independent of NAS)
backend/weather/       OpenWeatherMap + weather radio
lyrics/                LyricsManager, LrcParser, LyricsNetworkProvider
player/                PlayerManager, PlaybackService
ui/screens/            NowPlaying, Library, Queue, Settings, ServerConnect,
                       NetworkScreen, Equalizer, *Detail, network/ subpackage
util/                  AppLog, EncodingUtils, PinyinUtils, ArtistSplitter, ...
data/model/            all data classes (Song, Album, Artist, Playlist, Genre,
                       Lyrics*, UiState, Screen, Weather*, Network*)
```


## 2026-09-09 深度审查与修复纪要

- 全量审查报告（15 项发现 + 修复明细）由审查会话产出：NASMusicTV-代码审查报告.html。本文只补正最关键的滞后信息：
- 后端已不止 Jellyfin/Navidrome：共 5 个（+Subsonic/道理鱼/飞牛）；另有百度网盘源、离线下载、K 歌（ONNX 人声分离）、MTV、天气电台、手机遥控/扫码传输等大模块
- 安全基线：TLS 已恢复系统默认证书校验（2026-09-09 移除 6 处 trust-all，勿再引入）；凭据 AES-GCM 加密存储；本地 HTTP 服务无鉴权是所有者接受的取舍（家庭局域网场景）
- 已确认的设计取舍（勿"修复"）：gradle wrapper 指向本地 file://（离线构建）、全局 usesCleartextTraffic（用户自填任意 NAS http 地址所需）、连接页默认预填开发者局域网 IP
- ServerConnectScreen 的账号密码硬编码已清除；历史提交中的密码已泄露，必须轮换
