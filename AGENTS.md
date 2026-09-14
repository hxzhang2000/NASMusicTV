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

CI (`.github/workflows/build.yml`) has three jobs: **`build`** runs `assembleRelease` (push to `main`/`develop`, tags `v*`; PR to `main`), **`test`** runs `testDebugUnitTest`, and **`lint`** runs `lintDebug` — but lint is **non-blocking** (`continue-on-error: true`) and just uploads a report, because the project has no lint baseline yet. So a green CI now means "compiles + unit tests pass", not "lint-clean". Note the CI `build` job generates a throwaway `keystore.properties` (CI signing key) — it must include a `cryptoPassphrase` line, otherwise the `packageRelease` guard in `app/build.gradle.kts` fails the build.

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
