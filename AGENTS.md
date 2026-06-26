# AGENTS.md — NASMusicTV

## Project Overview

Android TV music player that connects to Jellyfin or Navidrome backends. Single-module Gradle project.

- Package: `com.nasmusic.tv`
- Min SDK 22, Target SDK 34, Java 17
- Kotlin 2.2.10 + Jetpack Compose for TV (experimental `androidx.tv:tv-material`)
- Architecture: `ViewModel` → `BackendAdapter` (interface) → `JellyfinAdapter` / `NavidromeAdapter`

## Build Environment (Windows)

### Paths

| Component | Path |
|-----------|------|
| JDK (JetBrains Runtime 21) | `C:\Program Files\Android\Android Studio\jbr` |
| Android SDK | `C:\Users\hxzha\AppData\Local\Android\Sdk` |
| adb | `C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| keystore.properties | `./keystore.properties` (项目根目录) |
| Release APK 输出 | `app/build/outputs/apk/release/app-release.apk` |

### Build Commands

```powershell
# 设置 JAVA_HOME（PowerShell）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# Debug APK
./gradlew.bat assembleDebug

# Release APK（需要 keystore.properties 签名配置）
./gradlew.bat assembleRelease

# Clean build
./gradlew.bat clean assembleRelease

# 运行测试
./gradlew.bat test
```

### GitHub

| 配置 | 值 |
|------|-----|
| 仓库地址 | `https://github.com/hxzhang2000/NASMusicTV.git` |
| 分支 | `main` |
| CI | `.github/workflows/build.yml`（push/PR to main/develop → assembleDebug） |

### 推送到电视

```powershell
$ADB = "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 查看已连接设备
& $ADB devices

# 安装 APK 到电视（已知电视 IP：192.168.0.116）
& $ADB -s 192.168.0.116:5555 install -r app\build\outputs\apk\release\app-release.apk

# 卸载旧版本
& $ADB -s 192.168.0.116:5555 uninstall com.nasmusic.tv

# 查看日志（调试用）
& $ADB -s 192.168.0.116:5555 logcat -s NASMusic AppLog PlayerManager
```

### 签名配置

Release 签名使用 `keystore.properties` 文件（已在 `.gitignore` 中排除）：
```
storeFile=path/to/keystore.jks
storePassword=xxx
keyAlias=xxx
keyPassword=xxx
```

No lint checks configured. CI 只跑 assembleDebug，不跑测试。

## Key Architecture

### Backend Adapters

`BackendAdapter` interface (`backend/BackendAdapter.kt`) defines the contract. Two implementations in `backend/impl/`:
- `JellyfinAdapter` — Jellyfin REST API with `X-Emby-Token` auth
- `NavidromeAdapter` — Subsonic-compatible API (`/rest/*` endpoints) with token+salt auth

**Both impl adapters use raw OkHttp** (not Retrofit). The `backend/jellyfin/` and `backend/navidrome/` directories contain older Retrofit-based implementations that are **dead code** — unused by anything.

`BackendRegistry` is a regular class instantiated in `NasMusicApp.onCreate()` (not a Kotlin `object` singleton) that holds the active adapter. One connection at a time. `testConnection()` creates a throwaway adapter and does NOT change current state.

### Playback Stack

- `PlaybackService` — Media3 `MediaLibraryService`, creates ExoPlayer, registers with `PlayerManager`
- `PlayerManager` — singleton (double-checked locking) managing queue, play modes (sequential/repeat-one/repeat-all/shuffle), progress tracking
- `MainViewModel` — bridges UI to PlayerManager and BackendAdapter, handles lyrics loading

**Critical wiring**: `PlaybackService.onCreate()` calls `PlayerManager.getInstance().setPlayer(player)` — this is how the singleton gets the ExoPlayer reference.

### Lyrics System

`LyricsManager` is **not** a singleton — instantiated per `MainViewModel` with `LyricsManager(app)`.

Priority order in `LyricsManager.getLyrics()`:
1. MP3 embedded lyrics (extracted from stream URL)
2. Local cache (`app.cacheDir/lyrics/`)
3. Local LRC files (scans `/storage/emulated/0/Music`, `Download`, `getExternalFilesDir/lyrics`, `filesDir/lyrics`)
4. Network provider (fetches from external lyrics API)

`BackendAdapter.getLyrics()` exists on the interface but is **never called** by LyricsManager. The code comment mentions "Backend API" as a 5th source but it is not implemented.

LRC naming patterns tried: `title.lrc`, `artist - title.lrc`, `artist_title.lrc`

### Navigation

Single `MainActivity` with **manual `when(currentScreen)` switch** — no Jetpack Navigation component despite the `navigation-compose` dependency. `Screen` enum defined in `MainViewModel.kt`.

Three-level BACK key handling: close dialog → navigate to NowPlaying → show exit confirm.

### Data Models

All in `data/model/`: `Song`, `Album`, `Artist`, `Lyrics`, `LyricsLine`, `LyricsSource`, `ServerConfig`, `AppSettings`, `PlayMode`.

## Non-Obvious Details

- `gradle-wrapper.properties` points to a local file path (`file:///C:/...`). This is intentional for offline builds.
- Navidrome uses Subsonic API v1.16.1 with token+salt authentication (not basic auth).
- `usesCleartextTraffic=true` in manifest — needed for local NAS HTTP connections.
- `android.software.leanback` required=true — app only runs on TV devices.
- `ExperimentalTvMaterial3Api` is used throughout — TV Compose APIs are alpha.
- `BackendRegistry` holds connection state in memory only. Reconnection on app restart reads from `AppPreferences` (DataStore).
- No DI framework. `PlayerManager.getInstance()`, `BackendRegistry` (instantiated in `NasMusicApp`), `AppPreferences.getInstance()` are singletons.
- Progress is updated via 1000ms polling loop in `PlayerManager` (v2.2.0 changed from 500ms to reduce CPU usage), not ExoPlayer callbacks.

## File Structure

```
app/src/main/java/com/nasmusic/tv/
├── NasMusicApp.kt          # Application class (DI container)
├── NasMusicVersion.kt      # Version constants
├── DialogBackHandler.kt    # Dialog back button handling
├── backend/
│   ├── BackendAdapter.kt    # Interface
│   ├── BackendRegistry.kt   # Registry (instantiated in NasMusicApp, not object singleton)
│   ├── impl/                # JellyfinAdapter, NavidromeAdapter (raw OkHttp)
│   ├── jellyfin/            # DEAD CODE — unused older Retrofit impl
│   └── navidrome/           # DEAD CODE — unused older Retrofit impl
├── data/
│   ├── model/               # Song, Album, Artist, Lyrics, LyricsSource, etc.
│   ├── prefs/               # AppPreferences (DataStore)
│   └── repository/          # (empty — removed unused ServerRepository, MusicRepository)
├── lyrics/                  # LyricsManager, LrcParser, network provider, Mp3MetadataExtractor
├── player/                  # PlayerManager, PlaybackService
├── ui/
│   ├── MainActivity.kt      # Single activity, manual nav
│   ├── components/          # AppRoot, LyricsView, PlayerControls, ConnectPromptDialog, CommonComponents, FocusableSurface
│   ├── screens/             # NowPlaying, Library, Queue, Settings, ServerConnect, ExitConfirmDialog, TextInputDialog, AlbumDetail, ArtistDetail, Equalizer, PlaylistManagement
│   ├── theme/               # Theme, Color
│   └── viewmodel/           # MainViewModel
└── util/                    # AppLog, CryptoUtils, EncodingUtils, MediaKeyHandler, NetworkMonitor, PinyinUtils, RetryUtil, TimeUtils, ArtistSplitter
```

## Technical Documentation

Implementation details and change records are maintained in `docs/technical-overview.md` (Section 10 — 修改记录).
**Rule**: Only record features/ fixes that have been tested and verified.
New entries go to `docs/technical-overview.md` (Section 10 — 修改记录), not scattered files.

## 歌唱家乱码问题

**问题**：曲库中部分艺术家名字显示为乱码（如 `κ��(����)`、`³Â»ØÏÐ` 等）。

**根因**：Jellyfin 服务端在读取 MP3 文件的 ID3 标签时，如果标签使用 GBK 编码，Jellyfin 会直接把原始字节当作 UTF-8 存储。当生成 JSON API 响应时：
- **部分情况**：Jellyfin 将 GBK 字节编码为 Unicode 码点（如 `\u03BA` = `κ`），客户端收到的是合法 UTF-8 JSON，但内容已是乱码 → **客户端无法修复**
- **另一部分情况**：Jellyfin 直接输出原始 GBK 字节，客户端收到乱码 UTF-8 → **`utf8Body()` 可以通过 GBK 回退修复**

**当前客户端修复**：
- `utf8Body()`：检测 U+FFFD / 希腊字母 / 西里尔字母时尝试 GBK 回退
- `fixEncoding()`：检测 U+FFFD 时用 UTF-8 字节回退 GBK

**服务端修复**：
- 设置环境变量 `DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=0`（允许 .NET 使用系统全球化数据）
- Windows：`[Environment]::SetEnvironmentVariable("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT", "0", "User")`
- 重启 Jellyfin 服务后重新扫描音乐库

**彻底解决**：需要在 Jellyfin 服务端重新扫描 MP3 文件的 ID3 标签，将编码改为 UTF-8（如用 MusicBrainz Picard）。

## 拼音搜索在低版本设备上失效（已修复）

**问题**：Android TV（API 22，Android 5.1）上拼音搜索完全不起作用。

**根因**：`PinyinUtils.getInitials()` 中有保护判断 `Build.VERSION.SDK_INT < 24`，API < 24 的设备直接返回空字符串，导致所有拼音首字母匹配失败。`toPinyin()` 也需要 API 26+（Android 8.0）的 `Transliterator` 支持。

**影响**：曲库中搜索"ayq"找不到"安又琪"、"wf"找不到"王菲"等。

**修复**：重写 `PinyinUtils` 使用 `com.github.promeg:tinypinyin:2.0.3`（TinyPinyin），纯 Java 实现，不依赖 `android.icu`，兼容 API 22+。编译需配置代理（中国大陆网络需通过 `127.0.0.1:7890` 或 Aliyun Maven 镜像下载依赖）。

**使用方式**：
```kotlin
// settings.gradle.kts - 添加阿里云 Maven 镜像和 JitPack
maven { url = uri("https://maven.aliyun.com/repository/public") }
maven { url = uri("https://jitpack.io") }

// app/build.gradle.kts - 添加依赖
implementation("com.github.promeg:tinypinyin:2.0.3")

// PinyinUtils - 使用 API
import com.github.promeg.pinyinhelper.Pinyin
val py = Pinyin.toPinyin(c) // 返回拼音字符串，如 "zhong"

## 播放页 vs 艺术家列表数据来源差异

**现象**：播放页封面下方显示的艺术家名正确，但艺术家列表中同一艺术家显示为乱码。

**根因**：Jellyfin 内部对同一个艺术家存了两份数据：
- `getArtists()` API 返回的是 Jellyfin 数据库里艺术家实体的 `Name` 字段（从 ID3 标签的 `Artist` 字段读取并存储，WAV 文件的 GBK 编码标签导致乱码）
- `getSongs()` API 返回的是歌曲的 `Artists` 数组（可能重新解析或使用不同编码）

**结论**：这是 Jellyfin 服务端的已知问题（参见 GitHub issue #11411），客户端无法完全修复。

## Constraints

- This is an Android TV app — no touch UI, D-Pad navigation only
- Landscape orientation locked
- Multi-ABI: arm64-v8a, armeabi-v7a, x86_64
- ProGuard rules keep data models, backend classes, Retrofit, Gson, ExoPlayer

## Testing

Tests are in `app/src/test/`. Currently 55 tests across 8 files:
- `UiStateTest.kt` (17 tests)
- `TimeUtilsTest.kt` (11 tests)
- `RetryUtilTest.kt` (8 tests)
- `MediaKeyHandlerTest.kt` (11 tests)
- `NetworkMonitorTest.kt` (8 tests)
- `LrcParserTest.kt`, `PinyinUtilsTest.kt`, `ArtistSplitterTest.kt`

Run with: `./gradlew.bat test`
