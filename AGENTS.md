# AGENTS.md — NASMusicTV

## Project Overview

Android TV music player that connects to Jellyfin or Navidrome backends. Single-module Gradle project.

- Package: `com.nasmusic.tv`
- Min SDK 25, Target SDK 34, Java 17
- Kotlin 2.2.10 + Jetpack Compose for TV (experimental `androidx.tv:tv-material`)
- Architecture: `ViewModel` → `BackendAdapter` (interface) → `JellyfinAdapter` / `NavidromeAdapter`

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (minified, shrunk)
./gradlew assembleRelease

# Clean build
./gradlew clean assembleDebug
```

No tests exist. No CI. No lint checks configured.

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
├── NasMusicApp.kt          # Application class (empty init)
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
├── player/                  # PlayerManager, PlaybackService, CoverArtManager
├── ui/
│   ├── MainActivity.kt      # Single activity, manual nav
│   ├── components/          # LyricsView, PlayerControls
│   ├── screens/             # NowPlaying, Library, Queue, Settings, ServerConnect, ExitConfirmDialog, TextInputDialog
│   ├── theme/               # Theme, Color, Type
│   └── viewmodel/           # MainViewModel
└── util/                    # TimeUtils
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
