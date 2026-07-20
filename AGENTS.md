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
$env:JAVA_HOME = "C:\Program Files\Android\Studio\jbr"

./gradlew.bat assembleDebug      # debug APK
./gradlew.bat assembleRelease    # needs ./keystore.properties (gitignored)
./gradlew.bat test               # unit tests (Robolectric + JUnit4)
```

CI (`.github/workflows/build.yml`) runs **only `assembleDebug`** on push/PR to `main`/`develop` — it does **not** run tests or lint. No lint is configured. Treat a green CI as "compiles", not "verified".

## Architecture (verified against source)

- **Manual DI container**: `NasMusicApp` constructs and holds the global instances — `backendRegistry`, `appPreferences` (`AppPreferences`), `playerManager` (`PlayerManager`), `networkMusicManager` (`NetworkMusicManager`). There is **no DI framework** and **no `getInstance()` singleton** — these are plain classes instantiated once in `NasMusicApp.onCreate()`. Access them by casting `Application` to `NasMusicApp` (e.g. `MainViewModel` does `app as NasMusicApp`).
- **Backend adapters**: `BackendAdapter` interface; live implementations in `backend/impl/` (`JellyfinAdapter`, `NavidromeAdapter`). Both use **raw OkHttp**, not Retrofit. `BackendRegistry` holds the active adapter in memory only (reconnect on restart reads `AppPreferences`). `testConnection()` builds a throwaway adapter and never mutates current state.
- **Playback**: `PlaybackService` (Media3 `MediaLibraryService`) creates the ExoPlayer and registers it with `PlayerManager`. Progress is driven by a 1000ms `Handler` polling loop in `PlayerManager`, **not** ExoPlayer callbacks. Network songs carry no resolved `streamUrl` until played — `PlayerManager.onNeedResolveStreamUrl` signals `MainViewModel` to resolve then replay.
- **Lyrics**: `LyricsManager` is constructed in `MainViewModel` (takes `context`, `backendRegistry`, `networkMusicManager`). It **does** call `BackendAdapter.getLyrics()` (Jellyfin lyric endpoint). Priority: local cache → backend API / `NetworkMusicManager` → network match (fuzzy title+artist). Older docs describing MP3-embedded / local-LRC-file scanning are stale.
- **Network music**: `backend/network/` — `MetingApiService` + `NetworkMusicManager` (multi-endpoint fallback, 302 redirect resolution, favorites cache LRU 500). Independent of the NAS backend.
- **Weather radio**: `backend/weather/` — `WeatherApi` (OpenWeatherMap) + `WeatherRadioManager` matches songs by mood. `BackendAdapter` was made nullable so weather radio works with no NAS connection.
- **Navigation**: single `MainActivity` with a manual `when(currentScreen)` switch (`Screen` enum in `data/model/Screen.kt`) — no Jetpack Navigation despite the `navigation-compose` dependency. Three-level BACK: close dialog → NowPlaying → exit confirm.

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
