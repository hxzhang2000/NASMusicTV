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

`BackendRegistry` is a Kotlin `object` singleton that holds the active adapter. One connection at a time. `testConnection()` creates a throwaway adapter and does NOT change current state.

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
- No DI framework. `PlayerManager.getInstance()`, `BackendRegistry` (object), `AppPreferences.getInstance()` are singletons.
- Progress is updated via 500ms polling loop in `MainViewModel`, not ExoPlayer callbacks.

## File Structure

```
app/src/main/java/com/nasmusic/tv/
├── NasMusicApp.kt          # Application class (DI container)
├── NasMusicVersion.kt      # Version constants
├── DialogBackHandler.kt    # Dialog back button handling
├── backend/
│   ├── BackendAdapter.kt    # Interface
│   ├── BackendRegistry.kt   # Singleton registry
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
