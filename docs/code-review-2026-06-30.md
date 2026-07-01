
─── app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt:222-229 ───
Resource leak: OkHttp Response is never closed or not guarded against exceptions. In
searchWithEndpoint, resolvePlayUrl, and the other method, `response` is not closed in all code
paths. Use `response.use { ... }` to ensure automatic closure.

  val response = client.newCall(request).execute()
- val body = response.body?.string()
- AppLog.i(DIAG, "searchWithEndpoint: response code=${response.code} bodyLen=${body?.length ?: 0}")
- if (!response.isSuccessful || body.isNullOrBlank()) {
-     AppLog.w(DIAG, "searchWithEndpoint: failed code=${response.code} bodyEmpty=${body.isNullOrBlank()}")
-     return emptyList()
+ return response.use { resp ->
+     val body = resp.body?.string()
+     AppLog.i(DIAG, "searchWithEndpoint: response code=${resp.code} bodyLen=${body?.length ?: 0}")
+     if (!resp.isSuccessful || body.isNullOrBlank()) {
+         AppLog.w(DIAG, "searchWithEndpoint: failed code=${resp.code} bodyEmpty=${body.isNullOrBlank()}")
+         return@use emptyList()
  }
  parseSongs(body)
+ }


─── app/src/main/java/com/nasmusic/tv/NasMusicVersion.kt:66-68 ───
Single-expression function can be simplified with `=` syntax, removing unnecessary braces and
`return`.

- fun isFileFormatCompatible(version: Int): Boolean {
-         return version == FILE_FORMAT_VERSION
-     }
+ fun isFileFormatCompatible(version: Int) = version == FILE_FORMAT_VERSION


─── app/src/main/java/com/nasmusic/tv/backend/BackendRegistry.kt:39-53 ───
Resource leak if `adapter.initialize()` throws an exception: the newly created adapter (and its HTTP
client) is never cleaned up. Catch exceptions and close the adapter on failure.

-         val success = adapter.initialize(
+         val success = try {
+             adapter.initialize(
              baseUrl = config.baseUrl,
              apiToken = config.apiToken,
              username = config.username,
              password = config.password
          )
-         AppLog.d("BackendRegistry", "initialize: result=$success")
-
-         if (success) {
-             currentAdapter = adapter
-             currentConfig = config
-             serverDisplayName = adapter.serverName
+         } catch (e: Exception) {
+             AppLog.w("BackendRegistry", "initialize: exception during adapter.initialize", e)
+             try { adapter.close() } catch (_: Exception) {}
+             return@withContext false
          }
-
-         success


─── app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:256-264 ───
ANR risk due to `runBlocking` on main thread for synchronous DataStore read. Replace with cached
value or suspend function to avoid blocking the UI thread.

- fun getDefaultNetworkSourceSync(): String {
-         return runBlocking {
-             try {
-                 context.dataStore.data.first()[keyDefaultNetworkSource] ?: "meting"
-             } catch (e: Exception) {
-                 "meting"
-             }
-         }
+ // Option 1: Cache the value
+ @Volatile
+ private var cachedDefaultNetworkSource: String = "meting"
+
+ suspend fun setDefaultNetworkSource(source: String) {
+     context.dataStore.edit { it[keyDefaultNetworkSource] = source }
+     cachedDefaultNetworkSource = source
      }
+
+ fun getDefaultNetworkSourceSync(): String = cachedDefaultNetworkSource


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:92-92 ───
Silent exception swallowing: caught exceptions are completely ignored without logging, making
debugging impossible. At minimum, log the exception.

- } catch (_: Exception) { null }
+ } catch (e: Exception) {
+     AppLog.w("LyricsManager", "${source} failed: ${e.message}")
+     null
+ }


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:24-34 ───
Performance: Regex objects recreated on each call to `parse()` and `isValidLrc()`. Extract as
`companion object` constants to avoid repeated compilation.

- val offsetRegex = Regex("\\[offset:(-?\\d+)\\]")
-         offsetRegex.find(cleanedText)?.let { match ->
-             globalOffset += match.groupValues[1].toLong()
+ // Move to companion object or top-level private val
+ private companion object {
+     private val OFFSET_REGEX = Regex("\\[offset:(-?\\d+)\\]")
+     private val LINE_REGEX = Regex("(\\[\\d{1,2}:\\d{2}\\.\\d{2,3}\\])+(.+)")
+     private val TIME_REGEX = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\]")
+     private val WORD_TIMESTAMP_REGEX = Regex("<(\\d{1,2}):(\\d{2})\\.(\\d{2,3})>([^<]+)")
          }
-
-         // Parse each line
-         val lineRegex = Regex("(\\[\\d{1,2}:\\d{2}\\.\\d{2,3}\\])+(.+)")
-         val timeRegex = Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\]")
-
-         // Karaoke word-timestamp pattern: <mm:ss.ff>word
-         val wordTimestampRegex = Regex("<(\\d{1,2}):(\\d{2})\\.(\\d{2,3})>([^<]+)")


─── app/src/main/java/com/nasmusic/tv/ui/DialogBackHandler.kt:20-22 ───
`staticCompositionLocalOf` used for dynamically-changing values. Use `compositionLocalOf` so
consumers recompose when the value changes.

- val LocalListBackHandler = staticCompositionLocalOf<MutableState<(() -> Boolean)?>> {
+ val LocalListBackHandler = compositionLocalOf<MutableState<(() -> Boolean)?>> {
      mutableStateOf(null)
  }


─── app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt:130-144 ───
Unsafe `!!` after null check: the null check does not narrow the type for delegated properties. Use
`?.let { }` or capture the value in a local variable to avoid NPE.

- if (errorMessage != null) {
+ errorMessage?.let { msg ->
                              Box(
                                  modifier = Modifier
                                      .fillMaxWidth()
                                      .align(Alignment.TopCenter)
                                      .padding(top = 80.dp)
                                      .background(
                                          color = NasMusicColors.Danger.copy(alpha = 0.9f),
                                          shape = RoundedCornerShape(12.dp)
                                      )
                                      .padding(horizontal = 32.dp, vertical = 16.dp),
                                  contentAlignment = Alignment.Center
                              ) {
                                  Text(
-                                     text = errorMessage!!,
+             text = msg,


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:108-108 ───
`toLrcText` loses data: does not serialize word timestamps (karaoke) or the `offset` header. Include
these in the output to preserve round-trip fidelity.

- sb.append(String.format("[%02d:%02d.%02d]%s\n", minutes, seconds, millis / 10, line.text))
+ val text = if (line.wordTimestamps.isNotEmpty()) {
+     line.wordTimestamps.joinToString("") { wt ->
+         val wMin = wt.startMs / 60000
+         val wSec = (wt.startMs % 60000) / 1000
+         val wMs = wt.startMs % 1000
+         "<${wMin.toInt()}:${wSec.toInt()}.${(wMs / 10).toInt()}>${wt.word}"
+     }
+ } else {
+     line.text
+ }
+ sb.append(String.format("[%02d:%02d.%02d]%s\n", minutes, seconds, millis / 10, text))


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:183-190 ───
Concurrency protection missing for cache file operations: `cacheLyrics()`, `getCachedLyrics()`, and
`clearCache()` are not synchronized, leading to possible file corruption or read failures. Use
atomic temp-file + rename pattern or locks.

+ private val cacheMutex = kotlinx.coroutines.sync.Mutex()
+
  fun cacheLyrics(song: Song, lrcText: String) {
+     kotlinx.coroutines.runBlocking {
+         cacheMutex.withLock {
          try {
              val cacheFile = getCacheFile(song)
-             cacheFile.writeText(lrcText)
+                 // Write to temp file then rename for atomicity
+                 val tempFile = File(cacheDir, "${cacheFile.name}.tmp")
+                 tempFile.writeText(lrcText)
+                 tempFile.renameTo(cacheFile)
          } catch (e: Exception) {
-             e.printStackTrace()
+                 AppLog.w("LyricsManager", "cacheLyrics failed: ${e.message}")
+             }
+         }
          }
      }


─── app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt:39-39 ───
Unused imports: remove these imports to keep the codebase clean and avoid confusion.



─── app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt:403-403 ───
**Regex object created on each invocation in fallback path.**

The `Regex("[?&]id=([^&]+)")` is instantiated every time the URI parsing throws an exception (inside
`catch`). Since the pattern is fixed and never changes, it should be extracted as a `companion
object` constant to avoid repeated compilation of the regex pattern.

**Suggestion:** Move the regex to a companion object property.

- val regex = Regex("[?&]id=([^&]+)")
+ // In companion object:
+ private val ID_REGEX = Regex("[?&]id=([^&]+)")
+
+ // Usage:
+ val matched = ID_REGEX.find(url)?.groupValues?.getOrNull(1)


─── app/src/main/java/com/nasmusic/tv/NasMusicVersion.kt:35-35 ───
Hardcoded BUILD_TYPE may mismatch actual build type.

BUILD_TYPE is hardcoded as "RELEASE" but AGP generates BuildConfig.BUILD_TYPE (via buildFeatures {
buildConfig = true }), which correctly reflects the actual build variant ("debug" or "release").
When running a debug build, DISPLAY_FULL and ABOUT_STRING will incorrectly display "RELEASE" as the
build type. This can cause confusion during testing/debugging where developers expect to see "DEBUG"
indicated.

Suggestion: Source BUILD_TYPE from BuildConfig instead so it dynamically reflects the actual
variant.

- const val BUILD_TYPE = "RELEASE"
+ val BUILD_TYPE: String get() = BuildConfig.BUILD_TYPE


─── app/src/main/java/com/nasmusic/tv/backend/BackendRegistry.kt:31-53 ───
**Race condition (concurrency hazard):** Both `initialize()` (line 31) and `testConnection()` (line
99) run on `Dispatchers.IO`, and `initialize()` mutates shared mutable state (`currentAdapter`,
`currentConfig`, `serverDisplayName`) without synchronization. If multiple threads call
`initialize()` concurrently, or `initialize()` and `disconnect()` race, the adapter state can become
inconsistent, and `currentAdapter` could leak or be left in a partially-initialized state.

+     @Volatile
+     private var currentAdapter: BackendAdapter? = null
+     @Volatile
+     private var currentConfig: ServerConfig? = null
+     @Volatile
+     private var serverDisplayName: String = ""
+
+     private val lock = Any()
+
      suspend fun initialize(config: ServerConfig): Boolean = withContext(Dispatchers.IO) {
          val adapter = when (config.backendType) {
              TYPE_JELLYFIN -> JellyfinAdapter()
              TYPE_NAVIDROME -> NavidromeAdapter()
              else -> return@withContext false
          }

          AppLog.d("BackendRegistry", "initialize: type=${config.backendType}, baseUrl=${config.baseUrl}, username=${config.username}, hasPw=${config.password.isNotEmpty()}, hasToken=${config.apiToken.isNotEmpty()}")
          val success = adapter.initialize(
              baseUrl = config.baseUrl,
              apiToken = config.apiToken,
              username = config.username,
              password = config.password
          )
          AppLog.d("BackendRegistry", "initialize: result=$success")

          if (success) {
+             synchronized(lock) {
+                 // Release previous adapter before replacing
+                 currentAdapter?.let {
+                     try { it.logout() } catch (_: Exception) {}
+                     try { it.close() } catch (_: Exception) {}
+                 }
              currentAdapter = adapter
              currentConfig = config
              serverDisplayName = adapter.serverName
+             }
+         } else {
+             // Release the new adapter if initialization failed
+             try { adapter.close() } catch (_: Exception) {}
          }

          success
+     }


─── app/src/main/java/com/nasmusic/tv/backend/BackendRegistry.kt:47-51 ───
**Resource leak on re-initialization:** When `initialize()` is called while already connected (i.e.,
`currentAdapter` is non-null), the method simply overwrites `currentAdapter` without calling
`logout()` or `close()` on the previous adapter. This leaks the previous HTTP client connection pool
and server-side session.

          if (success) {
+             // Release previous adapter resources before replacing
+             currentAdapter?.let { old ->
+                 try { old.logout() } catch (_: Exception) {}
+                 try { old.close() } catch (_: Exception) {}
+             }
              currentAdapter = adapter
              currentConfig = config
              serverDisplayName = adapter.serverName
+         } else {
+             // Clean up failed adapter to prevent resource leak
+             try { adapter.close() } catch (_: Exception) {}
          }


─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt:42-43 ───
**Memory Leak: Play URL cache never evicts expired entries.**

The `playUrlCache` (ConcurrentHashMap) stores play URLs with a 5-minute TTL check at read time (line
98), but expired entries are **never removed** from the map. Over the lifetime of the app, every
unique `song.id` ever resolved will accumulate in this map, leading to unbounded memory growth. Even
after the 5-minute TTL expires, the entry remains in the map forever, consuming memory.

**Suggestion:** Add a periodic cleanup mechanism, or clean expired entries on access by removing
stale ones before/after checking. Better yet, consider using a dedicated cache library (e.g.,
Caffeine) with built-in time-based eviction.

-     /** 播放链接内存缓存：songId → CachedPlayUrl（线程安全，resolvePlayUrl 在 IO 线程并发访问） */
-     private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()
+ // Option 1: Clean expired entries on each access
+ // In resolvePlayUrl, before returning cached URL:
+     playUrlCache.entries.removeAll { (_, v) -> now - v.timestamp >= PLAY_URL_CACHE_TTL_MS }
+
+ // Option 2: Use Caffeine (recommended)
+ // import com.github.benmanes.caffeine.cache.Caffeine
+ // private val playUrlCache = Caffeine.newBuilder()
+ //     .expireAfterWrite(PLAY_URL_CACHE_TTL_MS, TimeUnit.MILLISECONDS)
+ //     .build<String, String>()


─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt:95-110 ───
**Race Condition: Cache check and write are not atomic, causing redundant network requests.**

The TTL check (line 97-98) and cache write (line 107) are separate non-atomic operations across
multiple lines of code. Two concurrent calls to `resolvePlayUrl` for the same song can both miss the
cache (both pass the TTL check before either writes), resulting in duplicate network requests. One
request's result will silently overwrite the other's.

**Suggestion:** Protect the check-then-act sequence with key-level synchronization or use a proper
async cache (e.g., Caffeine's `LoadingCache` with `get()` which is atomic). Avoid using
`String.intern()` for synchronization as it can cause issues with the string pool.

-         // 检查缓存：未过期则直接返回
-         val now = System.currentTimeMillis()
-         val cached = playUrlCache[song.id]
-         if (cached != null && now - cached.timestamp < PLAY_URL_CACHE_TTL_MS) {
-             AppLog.d(TAG, "resolvePlayUrl: cache hit for songId=${song.id}, age=${now - cached.timestamp}ms")
-             return cached.url
-         }
+ // Option: Use a dedicated cache library (e.g., Caffeine) that handles atomic load
+ // val playUrlCache: Cache<String, String> = Caffeine.newBuilder()
+ //     .expireAfterWrite(PLAY_URL_CACHE_TTL_MS, TimeUnit.MILLISECONDS)
+ //     .build()
+ //
+ // Then in resolvePlayUrl:
+ // return try {
+ //     playUrlCache.get(song.id) { k -> svc.resolvePlayUrl(song) ?: "" }.ifEmpty { null }
+ // } ...
+ // (adjust null handling as needed)

-         return try {
-             val url = svc.resolvePlayUrl(song)
-             if (url != null) {
-                 // 写入缓存
-                 playUrlCache[song.id] = CachedPlayUrl(url, now)
-                 AppLog.d(TAG, "resolvePlayUrl: cached new url for songId=${song.id}")
-             }
-             url
+ // Simpler fix: synchronize on a dedicated lock per key
+ // private val cacheLocks = ConcurrentHashMap<String, Any>()
+ //
+ // val lock = cacheLocks.getOrPut(song.id) { Any() }
+ // synchronized(lock) {
+ //     // check cache again inside synchronized block
+ //     ...
+ // }


─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt:158-162 ───
**Design flaw: `searchCoverUrl` hard-codes concrete class `MetingApiService` check, breaking the
Open-Closed Principle.**

The method iterates through all services but explicitly filters by `if (svc !is MetingApiService)
continue`. This means:
1. Any future `NetworkMusicService` implementation (e.g., AlapiService, JioSaavnService) that
provides a `searchCoverUrl` method will be silently ignored.
2. The `NetworkMusicManager` is coupled to a concrete implementation class (`MetingApiService`)
rather than the `NetworkMusicService` interface, violating the routing abstraction.

**Suggestion:** Add `searchCoverUrl` to the `NetworkMusicService` interface with a default
implementation returning `null`, and remove the hard-coded `is MetingApiService` type check. This
maintains backward compatibility while allowing any service to participate.

-         // 依次尝试各服务，第一个返回非 null 即采用
-         for (svc in orderedServices()) {
-             if (svc !is MetingApiService) continue
-             return try {
-                 svc.searchCoverUrl(title, artist)
+ // In NetworkMusicService interface:
+ // suspend fun searchCoverUrl(title: String, artist: String): String? = null
+ //
+ // Then in NetworkMusicManager.searchCoverUrl:
+ //     for (svc in orderedServices()) {
+ //         return try {
+ //             svc.searchCoverUrl(title, artist)
+ //         } catch (e: Exception) {
+ //             AppLog.w(TAG, "searchCoverUrl error: ${e.message}", e)
+ //             null
+ //         } ?: continue
+ //     }
+ //     return null


─── app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:32-32 ───
**Coroutine leak: applicationScope never cancelled**

The `applicationScope` is created at class-initialization time using `SupervisorJob()` but is never
cancelled anywhere in the application lifecycle. The comment on line 28 explicitly states it is used
for "onDestroy 等生命周期之后的异步操作", yet there is no cancellation mechanism.

In `MainActivity.onDestroy()` (line 250), a coroutine is launched in this scope after Activity
destruction. While process death eventually cleans up, during the app's lifetime any launched
coroutine that does not complete will run indefinitely, holding references and potentially leaking
resources (e.g., OkHttp connections, View references passed through closures).

**Suggestion:** Override `onTerminate()` to cancel the scope:
```kotlin
override fun onTerminate() {
    super.onTerminate()
    applicationScope.cancel()
}
```
Alternatively, expose a `cancel()` method and let the calling code (e.g., MainActivity) cancel it at
the appropriate point.

  val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
+
+ override fun onTerminate() {
+     super.onTerminate()
+     applicationScope.cancel()
+ }


─── app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:41-49 ───
**runBlocking in provider lambdas may block calling thread**

Both `baseUrlProvider` (line 41) and `defaultSourceProvider` (line 32) call `AppPreferences` methods
— `getMetingApiBaseUrlSync()` and `getDefaultNetworkSourceSync()` — that internally use
`runBlocking` to read from DataStore synchronously.

- `runBlocking` blocks the **current thread** unconditionally. If these providers are ever invoked
from the main thread (e.g., the `defaultSource` property getter in `NetworkMusicManager` is just a
`get() = defaultSourceProvider()`, which could be accessed from UI code), the app will ANR.
- Even when called on IO dispatcher threads (as in `MetingApiService.search` / `resolvePlayUrl`),
`runBlocking` still **blocks an IO thread**, reducing thread pool availability and potentially
leading to thread starvation under concurrent load.

**Suggestion:** Replace synchronous providers with suspend-based providers (e.g., `suspend () ->
String`) so the read can be awaited without blocking any thread, and use `Dispatchers.IO` for the
DataStore read directly instead of nested `runBlocking`.

+ // Change NetworkMusicManager to accept suspend providers, then use:
  val services = mapOf(
      "meting" to MetingApiService(
-         baseUrlProvider = { appPreferences.getMetingApiBaseUrlSync() }
-     )
+         baseUrlProvider = suspend { withContext(Dispatchers.IO) { appPreferences.getMetingApiBaseUrl() } }
  )
- networkMusicManager = NetworkMusicManager(
-     services = services,
-     defaultSourceProvider = { appPreferences.getDefaultNetworkSourceSync() }
  )


─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt:140-141 ───
**Inconsistent `resolveCoverUrl` return logic for non-network songs.**

`resolveCoverUrl` (line 141) returns `song.coverUrl` when `!song.isNetworkSong`, while the doc says
"若服务返回 null，调用方应使用 song.coverUrl". However, `resolveLyrics` (line 123) returns `null` for
non-network songs. This inconsistency means a NAS/local song calling `resolveCoverUrl` will get a
`coverUrl` returned directly, while calling `resolveLyrics` will get `null`. The KDoc for
`resolveCoverUrl` says "若服务返回 null，调用方应使用 song.coverUrl" — so the return of the existing coverUrl
duplicates the caller's fallback logic and deviates from the pattern established by `resolveLyrics`
and `resolvePlayUrl`. Consider returning `null` consistently and letting the caller handle the
fallback to `song.coverUrl`.

      suspend fun resolveCoverUrl(song: Song): String? {
-         if (!song.isNetworkSong) return song.coverUrl
+         if (!song.isNetworkSong) return null  // consistent with resolveLyrics; caller falls back to song.coverUrl


─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt:55-59 ───
**`defaultSourceProvider()` is called multiple times in `search()` with no guarantee of
consistency.**

In `search()` (line 56), `defaultSourceProvider()` is called once for the log message, and then
again inside `orderedServices()` (line 59→180). If the provider lambda returns different values at
different calls (e.g., due to user changing settings concurrently), the log line could show a
different default source than what `orderedServices()` actually used, making debugging confusing.

**Suggestion:** Capture the default source into a local variable at the top of `search()` for
consistent usage in both logging and `orderedServices()`.

      suspend fun search(keyword: String): List<Song> = withContext(Dispatchers.IO) {
-         AppLog.i("MetingDiag", "=== NetworkMusicManager.search === keyword='$keyword' defaultSource='${defaultSourceProvider()}'")
+         val currentDefault = defaultSource  // or defaultSourceProvider()
+         AppLog.i("MetingDiag", "=== NetworkMusicManager.search === keyword='$keyword' defaultSource='$currentDefault'")
          if (keyword.isBlank()) return@withContext emptyList()

-         val ordered = orderedServices()
+         val ordered = orderedServicesWithDefault(currentDefault)


─── app/src/main/java/com/nasmusic/tv/data/model/EqualizerPreset.kt:6-6 ───
`bandGains` is typed as `FloatArray`, which is a mutable array. Since enum constants are singletons,
external code can modify the array contents (e.g., `EqualizerPreset.NORMAL.bandGains[0] = 10f`),
permanently corrupting the preset's gain values. This breaks immutability and can lead to
hard-to-debug state corruption at runtime. **Suggestion**: Replace `FloatArray` with an immutable
type such as `List<Float>` or return a defensive copy (`bandGains.copyOf()`) from a getter.

- enum class EqualizerPreset(val displayName: String, val bandGains: FloatArray) {
+ enum class EqualizerPreset(val displayName: String, val bandGains: List<Float>) {
+     NORMAL("自然", List(5) { 0f }),
+     POP("流行", listOf(-1f, 2f, 4f, 2f, -1f)),
+     ROCK("摇滚", listOf(4f, 2f, -1f, 2f, 4f)),
+     CLASSICAL("古典", listOf(4f, 2f, 0f, 2f, 3f)),
+     JAZZ("爵士", listOf(3f, 2f, -1f, 2f, 3f)),
+     CUSTOM("自定义", List(5) { 0f });


─── app/src/main/java/com/nasmusic/tv/data/model/EqualizerPreset.kt:15-16 ───
`fromName` silently defaults to `NORMAL` when the input name does not match any enum constant. This
can hide configuration errors or misspellings, making bugs harder to diagnose. Consider at least
logging a warning, or returning a nullable/Result type so callers are aware of the failure.

- fun fromName(name: String): EqualizerPreset =
-             values().find { it.name == name } ?: NORMAL
+ fun fromName(name: String): EqualizerPreset? =
+             values().find { it.name == name }


─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt:16-46 ───
The interface lacks an explicit error handling contract. Currently, `search` returns `List<Song>`
(non-nullable), `resolvePlayUrl` and `resolveLyrics` return `String?`, and `resolveCoverUrl`
defaults to `null`. However, there is no specification about whether implementations should return
empty/null on failure or throw exceptions. Looking at `MetingApiService`, exceptions are caught
internally and empty/null is returned, but other implementations (e.g., future AlAPI/JioSaavn
adapters) might let exceptions propagate, leading to inconsistent behavior at the call site
(`NetworkMusicManager` catches all exceptions defensively, but the contract is implicit).
**Suggestion**: Add explicit KDoc contract specifying that failures should be communicated via
return values (empty list for search, null for resolvers) and that implementations MUST NOT throw
exceptions to the caller, OR consider using Kotlin's `Result<T>` type for more explicit error
signaling.



─── app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt:25-25 ───
The `search()` method has no `limit` parameter, leaving result size control entirely to each
implementation. The KDoc even acknowledges this: "limit 由各实现自行控制，接口层不暴露". This causes two problems:
(1) `MetingApiService` sends no limit parameter to the API, potentially returning a large default
page (dozens of results), wasting bandwidth and memory; (2) callers like `NetworkMusicManager` have
no way to request a specific number of results per source, leading to inconsistent UX (some sources
could return 5 results, another 50). **Suggestion**: Add an optional `limit` parameter (e.g.,
`limit: Int = 20`) to the `search()` method so callers can control page size, and implementations
can pass it to their upstream API. This also enables future lazy-loading/pagination scenarios.

-     suspend fun search(keyword: String): List<Song>
+     suspend fun search(keyword: String, limit: Int = 20): List<Song>


─── app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:52-55 ───
**Dead code: Companion `instance` is set but never read**

The `companion object` property `instance` is initialized in `onCreate()` (`instance = this`) but is
never referenced anywhere in the entire codebase. All consumers access the Application singleton via
`(application as NasMusicApp)` (e.g., `PlaybackService.kt`, `MainActivity.kt`, `MainViewModel.kt`)
or `(appContext.applicationContext as NasMusicApp)` (`ServerConnectScreen.kt`).

This unused property should be removed to eliminate dead code and avoid confusion about the
canonical way to access the Application instance.

- companion object {
-     lateinit var instance: NasMusicApp
-         private set
- }
+ // Remove entirely; the companion object is no longer needed.


─── app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt:0-0 ───
Raw string 'meting' used instead of an enum or sealed class for network source. The comment on line
14 documents three valid values ("meting" / "alapi" / "jiosaavn"), but there is no compile-time
safety — any string value can be assigned, leading to runtime errors from typos or invalid values.
Consider defining an enum class (e.g., `NetworkSource`) with the supported sources and using it here
for type safety and exhaustive `when` branching.

-     // 网络音乐默认源（"meting" / "alapi" / "jiosaavn"）
-     val defaultNetworkSource: String = "meting"
+     val defaultNetworkSource: NetworkSource = NetworkSource.METING


─── app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt:17-17 ───
The default value `"https://meting.mikus.ink/api"` duplicates the constant
`MetingApiService.DEFAULT_BASE_URL` defined in `MetingApiService.kt` (line 127). This introduces a
maintenance risk — if the default URL ever changes, it must be updated in two places. Reference the
existing constant instead to keep a single source of truth.

-     val metingApiBaseUrl: String = "https://meting.mikus.ink/api"
+     val metingApiBaseUrl: String = MetingApiService.DEFAULT_BASE_URL


─── app/src/main/java/com/nasmusic/tv/data/model/LyricsSource.kt:11-11 ───
The `SERVER` enum constant is defined but never referenced anywhere in the codebase. If it has no
planned near-term use, consider removing it to reduce dead code and confusion. Alternatively, if it
is intended for future use, consider adding a brief comment explaining the intended purpose.

-     SERVER("服务器")
+     // SERVER("服务器")


─── app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:48-48 ───
**Resource leak risk**: Default empty `close()` body means subclasses that forget to override it
will leak OkHttp connection pool resources (file descriptors, sockets). Both existing adapters
(JellyfinAdapter, NavidromeAdapter) override this to release OkHttp resources, proving the
implementation is essential. A future adapter implementation (e.g., Plex, Subsonic) that forgets to
override `close()` could cause connection leaks and eventual app crashes. Consider making `close()`
abstract (no default) so the compiler forces subclasses to implement it, or add an explicit KDoc
warning like 'MUST override to release resources, otherwise connections will leak'.

- fun close() {}
+ abstract fun close()
+
+ // Or with warning doc:
+ /**
+  * 释放底层网络资源（OkHttp 连接池）。
+  * **必须覆盖**，否则网络连接将泄漏。
+  */
+ fun close() {
+     // Ensure subclasses don't forget to override
+     throw NotImplementedError("Subclasses must override close() to release network resources")
+ }


─── app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:0-0 ───
**Silent failure risk**: Methods returning `Boolean` with default `false` (toggleFavorite,
deletePlaylist, addToPlaylist, removeFromPlaylist, setRating, scrobblePlay) will silently no-op when
a backend doesn't support them. If callers don't check the return value, users may believe an action
succeeded when it didn't (e.g., 'favorite' toggled in UI but unfavorited on next refresh). The
default `false` return is indistinguishable from a genuine failure. Consider using a sealed result
type (e.g., `Result<Boolean>` or a custom sealed class like `sealed class BackendResult<T> { class
Success<T>(val data: T) : BackendResult<T>(); class Unsupported<T> : BackendResult<T>() }`) so
callers can explicitly handle unsupported operations.



─── app/src/main/java/com/nasmusic/tv/data/model/NetworkFavoriteItem.kt:27-27 ───
Default `System.currentTimeMillis()` for `addedAtMs` can silently produce incorrect timestamps when
this object is reconstructed via reflection-based deserialization (Room, Gson, Moshi,
kotlinx.serialization, etc.) and the `addedAtMs` field is missing from the stored data (e.g., after
schema migration, or restoring old serialized data).

For example, if old favorites were persisted without the `addedAtMs` column, deserialization will
set the timestamp to the current wall-clock time instead of the actual add time, breaking
chronological ordering.

**Suggestion**:
- Remove the default and make `addedAtMs` a constructor-required parameter, so callers always
provide an explicit timestamp.
- Or, keep the default for new creations but add a `@Transient`/`@Ignored` annotation so persistence
frameworks skip it (if that's the intent).
- Alternatively, if the persistence framework supports it, use a framework-specific default (e.g.,
`@Default` in kotlinx.serialization or `@ColumnInfo(defaultValue = ...)` in Room) to make the
semantics explicit.

+ // Option A: Make it required (no default)
+ val addedAtMs: Long
+
+ // Option B: Keep default but document/annotate
+ @get:JvmName("getAddedAtMs")
  val addedAtMs: Long = System.currentTimeMillis()
+
+ // Option C: Use a sentinel default to detect uninitialized values
+ val addedAtMs: Long = Long.MIN_VALUE


─── app/src/main/java/com/nasmusic/tv/data/model/LyricsLine.kt:14-18 ───
**Dead Code**: The `durationMs` property in `WordTimestamp` is declared with a default value of `0L`
but is never read or referenced anywhere in the entire codebase. No callers access
`wordTimestamps[...].durationMs`, making this field unused dead code that increases object size
unnecessarily.

**Suggestion**: Remove `durationMs` from `WordTimestamp` entirely unless there is a planned future
use for per-word duration tracking. If per-word duration is needed in the future, it should be added
at that time.

  data class WordTimestamp(
      val word: String,
-     val startMs: Long,
-     val durationMs: Long = 0L
+     val startMs: Long
  )


─── app/src/main/java/com/nasmusic/tv/data/model/LyricsLine.kt:17-17 ───
**Potential Silent Bug**: `durationMs` defaults to `0L`, which can cause invisible or broken
behavior (division by zero, zero-length highlights) if any future code uses this field. As noted in
the focus area, this default value provides no way to distinguish "intentionally zero" from "not
set."

- val durationMs: Long = 0L
+ // Remove the field entirely (see dead code note above), or if kept:
+ val durationMs: Long // no default, force explicit value


─── app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:113-113 ───
**Threading ambiguity**: `getStreamUrl` and `getCoverUrl` are declared as non-suspend functions.
While the current implementations (JellyfinAdapter, NavidromeAdapter) only construct URL strings
without I/O, the interface contract does not enforce non-blocking behavior. A future adapter
implementation could perform network requests inside these methods (e.g., fetching a signed URL from
a server), which would block the calling thread. Since these methods are likely called from within
coroutines (possibly on Dispatchers.Main via data mapping), this could cause UI jank or ANR. Either
mark them as `suspend` to enforce async semantics, or add explicit KDoc documenting that
implementations MUST NOT perform blocking I/O.

- fun getCoverUrl(songId: String): String
+ // Option A: Make them suspend
+ suspend fun getStreamUrl(songId: String): String
+ suspend fun getCoverUrl(songId: String): String
+
+ // Option B: Document the constraint
+ /**
+  * 获取歌曲流地址。
+  * 注意：此方法不是 suspend 函数，实现类必须确保不执行网络 I/O，
+  * 仅构造 URL 字符串。
+  */
+ fun getStreamUrl(songId: String): String


─── app/src/main/java/com/nasmusic/tv/data/model/PlayMode.kt:12-16 ───
Missing @JvmStatic annotation on companion object method. Without it, Java callers must use
`PlayMode.Companion.fromOrdinal(...)` instead of the more natural `PlayMode.fromOrdinal(...)`. Add
`@JvmStatic` to expose this as a proper static method for Java interop.

      companion object {
+         @JvmStatic
          fun fromOrdinal(ordinal: Int): PlayMode {
              return values().getOrNull(ordinal) ?: SEQUENTIAL
          }
      }


─── app/src/main/java/com/nasmusic/tv/data/model/PlayMode.kt:13-15 ───
Single-expression function can be simplified with `=` syntax for conciseness, removing unnecessary
braces and return statement.

-         fun fromOrdinal(ordinal: Int): PlayMode {
-             return values().getOrNull(ordinal) ?: SEQUENTIAL
-         }
+         fun fromOrdinal(ordinal: Int): PlayMode = values().getOrNull(ordinal) ?: SEQUENTIAL


─── app/src/main/java/com/nasmusic/tv/data/model/PlayMode.kt:14-14 ───
Silently falling back to SEQUENTIAL for out-of-range ordinals may mask data corruption or logic
errors. If the ordinal value represents persisted state that could become stale due to enum
reordering, the fallback is reasonable. However, if the ordinal always comes from in-process
serialization, consider throwing IllegalArgumentException to surface invalid state early rather than
silently degrading behavior.

-             return values().getOrNull(ordinal) ?: SEQUENTIAL
+             return values().getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid PlayMode ordinal: $ordinal")


─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:452-473 ───
**Race condition & inefficiency in toggleFavorite**: This method first fetches ALL starred songs via
`getStarred2` just to check if one specific song is already starred, then issues `star`/`unstar`
accordingly. This is not atomic — between the check and the action, another operation (e.g., from a
different UI component) could change the starred state, causing the toggle to invert. Additionally,
fetching all starred songs is wasteful for large libraries. Consider either: (1) tracking the
starred state client-side and calling the appropriate method directly, or (2) using separate
`star`/`unstar` methods instead of a generic toggle, or (3) using a server-side toggle if the
Subsonic API/Navidrome supports it.



─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:572-583 ───
**getSongsByYearRange loads all songs into memory and filters client-side**: This method calls
`getSongs(10000)` which requests 10,000 songs from the server via a single Subsonic API call with
`size=10000`, then filters them by year range on the client side. This is extremely inefficient for
large libraries — it may exceed server-imposed limits, consume significant memory, and cause slow
responses. Consider one of: (1) leveraging the `getAlbumList2` endpoint with `type=byYear` (if
Navidrome supports it), (2) implementing paginated loading with incremental year filtering, or (3)
requesting a reasonable page size (e.g., 500) and iterating through pages while filtering.

  override suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = withContext(Dispatchers.IO) {
          try {
-             // Subsonic doesn't have a direct year-range endpoint; fallback to album-based
-             val allSongs = getSongs(10000)
-             allSongs.filter { song ->
-                 song.year != null && song.year in fromYear..toYear
+             // Try album-based approach with getAlbumList2 byYear type
+             // Fallback: paginate with moderate page sizes
+             val allSongs = mutableListOf<Song>()
+             var offset = 0
+             val pageSize = 500
+             while (true) {
+                 val batch = getSongs(pageSize, offset)
+                 if (batch.isEmpty()) break
+                 allSongs.addAll(batch.filter { it.year != null && it.year in fromYear..toYear })
+                 offset += pageSize
              }
+             allSongs
          } catch (e: Exception) {
              AppLog.e("NavidromeAdapter", "getSongsByYearRange failed", e)
              emptyList()
          }
      }


─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:219-225 ───
**async/awaitAll without supervisorScope may cancel sibling coroutines on failure**: In both
`getArtistSongs` and `getRecentSongs`, multiple `async` blocks are launched inside
`withContext(Dispatchers.IO)` (which uses a plain `Job`, not `SupervisorJob`). If any single `async`
throws an exception, `awaitAll()` will propagate it, cancelling all other sibling async coroutines —
even though they may have completed successfully. Currently this is partially mitigated because
`getAlbumSongs` catches exceptions internally, but future changes or unexpected cancellation
exceptions could still cause data loss. Wrap the async block with `supervisorScope` to isolate
failures.



─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:36-38 ───
**Password stored as plain String in memory for the lifetime of the adapter**: The `password` field
is retained as a plain `String` throughout the adapter's lifecycle, and is used on every API call in
`buildRestUrl()` to generate the MD5 token. While the Subsonic authentication protocol requires the
password for token generation, this increases the risk surface if the app's memory is dumped.
Consider (1) clearing the password after the first successful API call and storing the pre-computed
token if the auth scheme allows it, or (2) at minimum zeroing it out in `close()` and documenting
the security implication.



─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:346-350 ───
`getCoverUrl` and `buildCoverUrl` have almost identical logic. The public `getCoverUrl` differs from
`buildCoverUrl` only by an additional logging call. Consider simplifying `getCoverUrl` to delegate
to `buildCoverUrl` with a logging side-effect, reducing code duplication.

- override fun getCoverUrl(songId: String): String {
-         val url = buildRestUrl("getCoverArt") + "&id=$songId&size=512"
-         AppLog.d("NavidromeAdapter", "getCoverUrl: $url")
-         return url
+ override fun getCoverUrl(songId: String) = buildCoverUrl(songId).also {
+         AppLog.d("NavidromeAdapter", "getCoverUrl: $it")
      }


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:534-536 ───
**Bug**: `addToPlaylist` sends `Ids` as a single string property instead of a JSON array. The
Jellyfin API endpoint expects `Ids` as a `Guid[]` type (JSON array). Sending `"Ids":"songId"` as a
plain string will cause the API call to fail or be silently ignored.

              val body = JsonObject().apply {
-                 addProperty("Ids", songId)
+                 val idsArray = com.google.gson.JsonArray().apply { add(songId) }
+                 add("Ids", idsArray)
              }.toString()


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:594-595 ───
**Unused cache**: `_favoriteIdsCache` is maintained across `toggleFavorite()` and `getFavorites()`
but is never read by any method. `toggleFavorite()` queries the server via `queryFavoriteStatus()`
instead of using the cache. This creates unnecessary complexity with locking overhead.

-     private val _favoriteIdsCache = mutableSetOf<String>()
-     private val favoriteCacheLock = Any()
+     // Remove _favoriteIdsCache and favoriteCacheLock entirely if not consumed by superclass/interface


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:889-892 ───
**Encoding fallback is too broad**: The `utf8Body()` heuristic treats ANY Greek or Cyrillic
character as evidence of GBK mis-encoding and triggers a GBK fallback decode. This will corrupt
legitimate UTF-8 metadata from songs with Greek or Cyrillic text, since re-decoding correct UTF-8
bytes as GBK produces garbage.

-         val hasGreek = utf8.any { it.code in 0x0370..0x03FF }
-         val hasCyrillic = utf8.any { it.code in 0x0400..0x04FF }
-
-         val needsGbkFallback = hasReplacement || hasGreek || hasCyrillic
+         // Only fall back to GBK if actual replacement characters (U+FFFD) are present.
+         // Greek/Cyrillic alone are valid UTF-8 characters found in legitimate metadata.
+         val needsGbkFallback = hasReplacement


─── app/src/main/java/com/nasmusic/tv/data/model/RecentSong.kt:6-10 ───
**Default value semantics risk**: The `lastPlayedAt` default is `System.currentTimeMillis()` and
`playCount` defaults to `1`. When deserializing from a remote API, local persistence (e.g., Room,
SharedPreferences), or JSON parsing libraries (like Moshi/Gson), if the source data is missing these
fields, the defaults will be silently applied instead of failing with a clear error. This can
corrupt historical data:
- A previously saved `lastPlayedAt` timestamp may be overwritten with the current time, making it
appear as if a song was just played.
- An existing `playCount` could be reset to `1`, losing actual play count history.

**Suggestion**: Remove the defaults and require explicit values from the caller, or validate the
deserialized data to ensure integrity. If defaults are truly intended only for "new play at this
moment" creation, consider a factory function or secondary constructor that makes the intent
explicit.

  data class RecentSong(
      val songId: String,
-     val lastPlayedAt: Long = System.currentTimeMillis(),
-     val playCount: Int = 1
+     val lastPlayedAt: Long,
+     val playCount: Int
+ ) {
+     companion object {
+         fun createNew(songId: String) = RecentSong(
+             songId = songId,
+             lastPlayedAt = System.currentTimeMillis(),
+             playCount = 1
  )
+     }
+ }


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:0-0 ───
**Infinite loop risk**: The paginated `while(true)` loops rely solely on `items.size() < pageSize`
to break. If the Jellyfin API returns exactly `pageSize` entries with `StartIndex` beyond total
count, the loop could run indefinitely with no upper bound protection.

-             while (true) {
-                 val url = "$baseUrl/Items?" +
-                         ...
-                         "StartIndex=$startIndex&Limit=$pageSize"
-
-                 val json = executeJsonRequest(url) ?: break
-                 val items = json.getAsJsonArray("Items") ?: break
+             var maxPages = 1000  // safe upper bound
+             while (maxPages-- > 0) {
                  ...
                  if (items.size() < pageSize) break
                  startIndex += pageSize
              }


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:617-623 ───
**Race condition in `toggleFavorite` cache update**: `toggleFavorite()` reads server state via
`queryFavoriteStatus()`, then after a successful toggle HTTP call, updates the local cache inside a
`synchronized` block. However, `_favoriteIdsCache` is also completely overwritten by
`getFavorites()`. If `getFavorites()` runs concurrently with `toggleFavorite()`, the cache could end
up in an inconsistent state.

-             synchronized(favoriteCacheLock) {
-                 if (isCurrentlyFavorite) {
-                     _favoriteIdsCache.remove(songId)
-                 } else {
-                     _favoriteIdsCache.add(songId)
-                 }
-             }
+ This code is dead/unused — consider removing entirely, or use an atomic compare-and-swap pattern if truly needed.


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:1040-1042 ───
**`fetchCurrentUserInfo()` bypasses encoding detection**: uses `response.body?.string()` instead of
the custom `response.utf8Body()` extension used everywhere else. The GBK fallback encoding logic is
not applied to the user info response.

-                 val body = response.body?.string() ?: return@use null
+                 val body = response.utf8Body() ?: return@use null
                  if (!response.isSuccessful) return@use null
                  val json = gson.fromJson(body, JsonObject::class.java)


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:367-371 ───
**API token leakage via URL**: `getCoverUrl()`, `getStreamUrl()`, and `getCoverUrlCandidates()`
embed the API token in URLs as query parameter (`api_key=$apiToken`). These URLs are logged in
`getCoverUrl()` and returned to callers who may log them further — a security risk.

      override fun getCoverUrl(songId: String): String {
          val url = "$baseUrl/Items/$songId/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken"
-         AppLog.d("JellyfinAdapter", "getCoverUrl: $url")
+         // Avoid logging token: AppLog.d("JellyfinAdapter", "getCoverUrl: item=$songId")
          return url
      }


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:698-705 ───
**`setRating` sends wrong API parameter**: Jellyfin API expects rating as a query parameter, not as
JSON body field. Sending `{"Rating":3}` in the request body is incorrect and will be ignored by the
server.

-             val body = JsonObject().apply {
-                 addProperty("Rating", rating.coerceIn(1, 5))
-             }.toString()
              val request = Request.Builder()
-                 .url("$baseUrl/Users/$userId/Items/$songId/Rating?api_key=$apiToken")
+                 .url("$baseUrl/Users/$userId/Items/$songId/Rating?rating=${rating.coerceIn(1, 5)}")
                  .header("X-Emby-Authorization", buildAuthHeader())
-                 .post(body.toRequestBody(jsonMediaType))
+                 .post("".toRequestBody(null))
                  .build()


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:485-485 ───
**`getPlaylists()` fetches incorrect `owner` field**: reads the playlist owner from `AlbumArtist`,
which is a music album-specific field and will always be null for playlists.

-                     owner = EncodingUtils.fixEncoding(obj.get("AlbumArtist")?.asString) ?: ""
+                     owner = EncodingUtils.fixEncoding(obj.get("UserName")?.asString) ?: ""


─── app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt:475-475 ───
**`getPlaylists()` does not implement pagination**: Unlike other list-fetching methods,
`getPlaylists()` sends a single request without `StartIndex` and `Limit` parameters. Could miss
playlists on servers with many playlists.

-             val url = "$baseUrl/Playlists?UserId=$userId"
+             val url = "$baseUrl/Playlists?UserId=$userId&StartIndex=0&Limit=100"


─── app/src/main/java/com/nasmusic/tv/data/model/Playlist.kt:6-9 ───
Data integrity: `id` and `name` are non-nullable Strings but lack validation for empty or blank
values. Downstream code uses `playlist.id` as a Compose `LazyColumn` key
(PlaylistManagementScreen.kt:163) and for API operations (MainViewModel.kt:1388,1427,1448). An
empty/blank `id` would cause key collisions in the lazy list and malformed API requests. Likewise,
an empty `name` would cause silent display issues in the UI. Consider adding an `init` block with
`require` validation to fail fast on invalid data.

  data class Playlist(
      val id: String,
      val name: String,
      val songCount: Int = 0,
+     val durationMs: Long = 0L,
+     val coverUrl: String? = null,
+     val owner: String = ""
+ ) {
+     init {
+         require(id.isNotBlank()) { "Playlist id must not be blank" }
+         require(name.isNotBlank()) { "Playlist name must not be blank" }
+     }
+ }


─── app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:88-88 ───
**NavidromeAdapter will silently return empty list**: `getSongsByIds` defaults to an empty list, and
NavidromeAdapter does NOT override this method. Callers like `loadRecentSongs()` (MainViewModel:663)
and `updateRestoredQueueStreamUrls()` (MainViewModel:320) will get `emptyList()`, causing recent
songs to silently fail to load on Navidrome. This is a data integrity bug — the UI shows no error
but shows no songs either. `emptyList()` is indistinguishable from 'no songs found'; consider
throwing `NotImplementedError`, using a nullable `List<Song>?` return, or making it abstract to
force implementations.

- suspend fun getSongsByIds(ids: List<String>): List<Song> = emptyList()
+ suspend fun getSongsByIds(ids: List<String>): List<Song>
+ // Remove default, force all adapters to implement


─── app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt:37-38 ───
**Implementation details leaking into interface documentation**: The KDoc for `logout()` references
specific adapter implementations ('Jellyfin：POST /Sessions/Logout', 'Navidrome：无状态认证'). Interface
documentation should describe the contract (what the method does, expected behavior, constraints)
not how specific implementations handle it. This couples the interface to current implementations
and will be misleading when future adapters (e.g., Plex, Emby) are added with different logout
mechanisms.

-  * Jellyfin：POST /Sessions/Logout 使 token 失效。
-  * Navidrome：无状态认证，不需要显式登出。
+  /**
+   * 断开后端会话连接，释放服务端 session 资源。
+   * 对于无状态认证的后端，默认空实现适用。
+   * 对于有状态会话（如 Jellyfin），子类必须覆盖以发送登出请求使 token 失效。
+   */


─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:37-37 ───
**apiToken is stored but never used**: The `apiToken` parameter is received in `initialize()`,
stored as a field, but never referenced anywhere else in this file. The `buildRestUrl()` method only
uses `username` and `password` for authentication. This is dead code — either the field should be
removed, or it should be used (e.g., as a token-based auth alternative if needed).

- private var apiToken: String = ""
+ // Remove the unused apiToken field, or document its intended usage


─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:362-364 ───
**song.artistId is never populated in any Song returned from Navidrome**: The
`getCoverUrlCandidates` method provides a fallback to the artist cover URL via
`song.artistId?.takeIf { it.isNotBlank() }`. However, `artistId` is never set in any of the Song
objects constructed in this file — all `Song(...)` calls omit `artistId`, leaving it as the default
`null`. This means the artist cover fallback path is effectively dead code. Either populate
`artistId` from Subsonic's `artistId` field (available in most endpoints like `getAlbum`, `getSong`,
etc.) or remove the dead fallback.



─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:329-329 ───
**Redundant `.take(20)` in getRecentSongs**: The Subsonic API call already limits results to 20 via
`&size=20`, so `albums.take(20)` on the parsed result is redundant. The `albums` list will never
contain more than 20 items, making the `.take(20)` call a no-op that adds unnecessary clutter.



─── app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:202-213 ───
**Unbounded array growth in setEqualizerBand**

The `while (bands.size <= index)` loop at line 184 has no upper bound check. A caller passing a
large index (e.g., `1000000`) would force the list to grow to that size, filling it with zeros. This
could consume significant memory and produce a huge serialized JSON string, potentially causing OOM
or severely degrading performance on disk writes.

**Suggestion**: Add a reasonable upper bound (e.g., typical equalizer band counts are 5–31). If the
index exceeds the bound, either reject the write or throw an `IllegalArgumentException`.

+ private const val MAX_EQUALIZER_BANDS = 31
+
  suspend fun setEqualizerBand(index: Int, value: Float) {
+     require(index in 0 until MAX_EQUALIZER_BANDS) {
+         "Equalizer band index $index out of range (max ${MAX_EQUALIZER_BANDS - 1})"
+     }
          context.dataStore.edit { prefs ->
              val json = prefs[keyEqualizerBands] ?: "[]"
              val bands: MutableList<Float> = try {
                  val list: List<Float> = gson.fromJson(json, object : TypeToken<List<Float>>() {}.type)
                  list.toMutableList()
              } catch (e: Exception) { mutableListOf() }
              while (bands.size <= index) bands.add(0f)
              bands[index] = value
              prefs[keyEqualizerBands] = gson.toJson(bands)
          }
      }


─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:439-441 ───
**Non-standard Subsonic API parameter in removeFromPlaylist**: The Subsonic `updatePlaylist`
endpoint uses `songIndexToRemove` (an integer index) to remove songs from a playlist, not
`songIdToRemove` (a song ID string). The current implementation uses `songIdToRemove=$songId`, which
is not part of the standard Subsonic protocol. If Navidrome does not support this non-standard
parameter, the remove operation will silently fail. Consider using `songIndexToRemove` with the
actual index of the song in the playlist, or verify that Navidrome supports the `songIdToRemove`
extension.



─── app/src/main/java/com/nasmusic/tv/data/model/ServerConfig.kt:0-0 ───
**Security: Data class `toString()` exposes plaintext password and apiToken**

`ServerConfig` is a `data class`, so the auto-generated `toString()` includes all properties in
plaintext, including `password`, `apiToken`, `username`, and `baseUrl`. If this object is ever
logged (e.g., for debugging, crash reports, or error logs), credentials would be exposed in logcat
or error output.

**Suggestion:** Override `toString()` to mask or exclude sensitive fields, or annotate them with
`@ToString.Exclude` if using a library that supports it (or simply implement a custom `toString()`).

  data class ServerConfig(
-     val id: String = UUID.randomUUID().toString(),
+     val id: String = "",
      val backendType: String,
      val baseUrl: String,
      val apiToken: String = "",
      val username: String = "",
      val password: String = "",
      val isConnected: Boolean = false,
      val displayName: String = ""
- )
+ ) {
+     override fun toString(): String =
+         "ServerConfig(backendType=$backendType, baseUrl=$baseUrl, username=$username, hasToken=${apiToken.isNotEmpty()}, hasPassword=${password.isNotEmpty()})"
+ }


─── app/src/main/java/com/nasmusic/tv/data/model/ServerConfig.kt:31-32 ───
**`isValid` validation is incomplete — missing credential checks**

The current `isValid` only checks `baseUrl.isNotBlank()` and `backendType.isNotBlank()`. However, a
usable server configuration must also provide authentication credentials (`apiToken` for Jellyfin,
or `username`/`password` for both Jellyfin and Navidrome). A config with a non-blank URL but empty
credentials will pass validation but fail at connection time, causing a poor user experience (silent
failure or a confusing error).

**Suggestion:** Add credential validation to `isValid`. At minimum, require at least one
authentication field to be non-empty.

  val isValid: Boolean
-     get() = baseUrl.isNotBlank() && backendType.isNotBlank()
+     get() = baseUrl.isNotBlank() && backendType.isNotBlank() &&
+         (apiToken.isNotBlank() || (username.isNotBlank() && password.isNotBlank()))


─── app/src/main/java/com/nasmusic/tv/data/model/ServerConfig.kt:8-9 ───
**Dead code: `id` field with random UUID is never used**

The `id` field is declared with a default value of `UUID.randomUUID().toString()`, but it is never
read, referenced, compared, or persisted anywhere in the codebase (verified by searching all `.kt`
files). Every time a new `ServerConfig()` is created via the primary constructor (without explicit
`id`), a new random UUID is generated, which also breaks structural equality between two otherwise
identical configs. This field adds unnecessary memory overhead and complexity.

**Suggestion:** Remove the `id` field entirely, or replace it with a stable default (e.g., `""`) and
only add it back when a concrete use case requires it.

  data class ServerConfig(
-     val id: String = UUID.randomUUID().toString(),
+     val backendType: String,


─── app/src/main/java/com/nasmusic/tv/data/model/Song.kt:12-31 ───
**Nullable invariant violation for networkSong fields**

Per the class KDoc, when `isNetworkSong=true`, both `networkSource` and `networkId` are semantically
required — they are used downstream for routing to the correct network service
(`NetworkMusicManager.resolvePlayUrl()` reads `song.networkSource`,
`MetingApiService.resolvePlayUrl()` reads `song.networkId`). However, both fields are declared
nullable (`String?`).

This creates a contract gap: callers constructing a `Song` with `isNetworkSong=true` may forget to
supply these fields (or supply `null`), leading to silent failures at runtime (e.g.,
`NetworkMusicManager.resolvePlayUrl()` returns `null` when `networkSource` is null at line 89).

**Suggestion:** Enforce the invariant via a factory function or a secondary constructor that
requires `networkSource` and `networkId` when `isNetworkSong=true`. Alternatively, separate the
types entirely using a sealed class hierarchy (e.g., `NasSong` / `NetworkSong`) to make the
distinction type-safe at compile time.



─── app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt:235-235 ───
**Potential incorrect API parameter in getSongs (line 235)**: The Subsonic `getSongs` endpoint does
not accept a `type` parameter — it uses `since` (a song ID) for pagination. The `type` parameter
belongs to `getAlbumList`/`getAlbumList2`. The current URL
`getSongs&type=alphabeticalByName&size=500&offset=0` may be silently ignored by Navidrome or produce
unexpected results. Verify Navidrome's behavior or use the correct Subsonic pagination approach.



─── app/src/main/java/com/nasmusic/tv/data/model/Song.kt:0-0 ───
**ID format documented but not enforced**

The class KDoc specifies that network song IDs must follow the pattern
`"ntwk_${source}_${sourceId}"`. However, the data class constructor does not enforce this format,
relying entirely on callers to comply. Any code that constructs a `Song` with `isNetworkSong=true`
but uses an arbitrary `id` format will silently break lookup/deduplication logic that depends on the
prefix pattern.

While `MetingApiService` (line 349) happens to follow the convention, there's no structural
guarantee across other call sites.

**Suggestion:** Consider either:
1. Making the `id` parameter private and providing a factory function that constructs the ID from
source/sourceId for network songs.
2. Using a sealed class hierarchy where the network song variant auto-generates the ID from
`networkSource` and `networkId`.



─── app/src/main/java/com/nasmusic/tv/data/model/Song.kt:12-31 ───
**Missing `@JvmOverloads` annotation for Java interop**

The data class uses default parameter values (e.g., `isNetworkSong: Boolean = false`,
`networkSource: String? = null`). If this class is ever called from Java code, all parameters must
be explicitly provided since Java does not recognize Kotlin default values. While no `.java` files
are currently found in the project, this data class is part of a public `data.model` package and
could be consumed by Java code in the future or by library users.

**Suggestion:** Add `@JvmOverloads` to the constructor to generate overloaded constructors for Java
callers.



─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:130-151 ───
**Missing fallback when networkMusicManager is null for network songs in
getLyricsFromSource(EMBEDDED)** — When `song.isNetworkSong` is true and `networkMusicManager` is
null, the code falls through to the `else` branch which calls `adapter.getLyrics(song.id)`. For a
network song (ID format "ntwk_..."), the backend adapter will almost certainly return null, but the
fuzzy match via `networkProvider.fetchLyrics()` is never tried. This differs from
`checkAvailability()` which does offer a fuzzy fallback.

  LyricsSource.EMBEDDED -> {
-     if (song.isNetworkSong && networkMusicManager != null) {
-         // 网络歌曲的"内嵌"歌词走 NetworkMusicManager（实际为在线歌词接口）
+     val primaryLyrics = if (song.isNetworkSong && networkMusicManager != null) {
+         try {
          val text = networkMusicManager.resolveLyrics(song)
          if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
              cacheLyrics(song, text)
              LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
          } else null
+         } catch (e: Exception) {
+             AppLog.w("LyricsManager", "network resolveLyrics failed: ${e.message}")
+             null
+         }
      } else {
-         // NAS 歌曲从后端API获取
          val adapter = backendRegistry.getAdapter()
          if (adapter != null) {
              try {
                  val text = adapter.getLyrics(song.id)
                  if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
                      cacheLyrics(song, text)
                      LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
                  } else null
-             } catch (e: Exception) { null }
+             } catch (e: Exception) {
+                 AppLog.w("LyricsManager", "backend getLyrics failed: ${e.message}")
+                 null
+             }
+         } else null
+     }
+     // Fallback to fuzzy match if primary fails
+     primaryLyrics ?: try {
+         val text = networkProvider.fetchLyrics(song.title, song.artist)
+         if (text != null) {
+             cacheLyrics(song, text)
+             LrcParser.parse(text, song.id).copy(source = LyricsSource.NETWORK)
          } else null
+     } catch (e: Exception) {
+         AppLog.w("LyricsManager", "network fallback fetchLyrics failed: ${e.message}")
+         null
      }
  }


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:0-0 ───
**Hardcoded external storage paths** — Hardcoding `/storage/emulated/0/Music` and
`/storage/emulated/0/Download` is not portable across devices. On many devices (e.g., Samsung,
Huawei, devices with adoptable storage, or different Android API levels) the external storage path
may differ. Consider using
`Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)` for Music and
`Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)` for Download
directories.

- File("/storage/emulated/0/Music"),
-             File("/storage/emulated/0/Download")
+ File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path),
+             File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:77-78 ───
**Redundant `cacheLyrics()` call + `LrcParser.parse()` pattern duplicated across 3 methods** — The
same `cacheLyrics(song, text)` + `LrcParser.parse(text, song.id).copy(source=...)` block appears in:
1. `checkAvailability()` — network song path (lines 82-84, 89-91)
2. `checkAvailability()` — NAS song path (lines 98-100, 103-105)
3. `getLyricsFromSource(EMBEDDED)` (lines 137-139, 144-146)
4. `getLyricsFromSource(NETWORK)` (lines 155-157)

This violates DRY. Any change to caching or parsing logic requires updating 4 places, risking
inconsistencies.

+ Consider extracting a private helper:
+
+ private suspend fun fetchAndCacheLyrics(
+     song: Song,
+     fetcher: suspend () -> String?,
+     source: LyricsSource
+ ): Lyrics? {
+     return try {
+         val text = fetcher()
+         if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
  cacheLyrics(song, text)
-                     LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
+             LrcParser.parse(text, song.id).copy(source = source)
+         } else null
+     } catch (e: Exception) {
+         AppLog.w("LyricsManager", "fetchAndCacheLyrics failed: ${e.message}")
+         null
+     }
+ }


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:187-189 ───
**`e.printStackTrace()` in production code** — Line 178 uses `e.printStackTrace()` which writes to
`System.err`. In Android production builds, this output is typically discarded and provides no
insight to app diagnostics. Use `AppLog` consistently.

  } catch (e: Exception) {
-             e.printStackTrace()
+             AppLog.w("LyricsManager", "cacheLyrics failed: ${e.message}")
          }


─── app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:68-69 ───
Runtime constants that should be compile-time const

`recentSongsMaxSize` and `networkFavoritesMaxSize` are defined as instance-level `val` properties
(lines 52-53), but their values (50 and 500) are known at compile time. Declaring them as `const
val` inside a `companion object` would allow the compiler to inline them, avoiding field access
overhead and unnecessary object references.

This is a minor performance improvement, but aligns with Kotlin best practices for compile-time
constants.

- private val recentSongsMaxSize = 50
-     private val networkFavoritesMaxSize = 500
+ companion object {
+         private const val RECENT_SONGS_MAX_SIZE = 50
+         private const val NETWORK_FAVORITES_MAX_SIZE = 500
+     }


─── app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt:326-329 ───
Inefficient while loop instead of if when at most one element needs removal

In `toggleNetworkFavorite`, after adding one new item, the list size can exceed
`networkFavoritesMaxSize` by at most 1. Using a `while` loop is misleading — it suggests multiple
iterations are possible. The code elsewhere in this same file (`recordPlay` at line 149) correctly
uses an `if` for the same pattern.

**Suggestion**: Replace `while` with `if` for consistency and clarity.

- // LRU 上限：超过 500 条时移除最旧的（列表末尾）
-                 while (mutable.size > networkFavoritesMaxSize) {
-                     mutable.removeAt(mutable.size - 1)
+ if (mutable.size > networkFavoritesMaxSize) {
+                     mutable.removeAt(mutable.lastIndex)
                  }


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:25-27 ───
**Potential NumberFormatException risk (Pre-scan Focus #2): Unprotected `toLong()` on offset capture
group.**

While the regex ensures `\d+` (at least one digit), an extremely long digit string (e.g., a value
exceeding `Long.MAX_VALUE`) would throw `NumberFormatException`. The offset comes from user-provided
LRC content, so defensive handling with `try-catch` or `toLongOrNull()` is recommended.

  offsetRegex.find(cleanedText)?.let { match ->
-             globalOffset += match.groupValues[1].toLong()
+             globalOffset += match.groupValues[1].toLongOrNull() ?: 0L
          }


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:53-56 ───
**WordTimestamp millis handling gap (Pre-scan Focus #3): Karaoke word timestamps ignore the global
offset for `durationMs`.**

The `WordTimestamp` data class has a `durationMs: Long = 0L` field that is never populated by the
parser. For the UI's word-by-word highlighting mode to function correctly, each word timestamp
should ideally include a computed duration (difference to next word's startMs). Without it,
word-level highlighting may not work as intended.

- WordTimestamp(
-                             word = wm.groupValues[4],
-                             startMs = wTimeMs
-                         )
+ // When building wordTimestamps, compute duration from the next word's startMs
+ wordTimestamps.mapIndexed { index, wt ->
+     val duration = if (index < wordTimestamps.size - 1) {
+         wordTimestamps[index + 1].startMs - wt.startMs
+     } else {
+         0L
+     }
+     wt.copy(durationMs = duration)
+ }


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:76-78 ───
**Potential negative time values when offset is strongly negative.**

`timeMs = minutes * 60 * 1000 + seconds * 1000 + millis + globalOffset` can produce negative time
values if `globalOffset` is a large negative number (e.g., `-2000` for a `00:01.00` line → `-1000`).
The binary search still works but negative times may cause unexpected UI behavior. Consider clamping
`timeMs` to `max(0L, ...)`.

- val timeMs = minutes * 60 * 1000 + seconds * 1000 + millis + globalOffset
-                     lines.add(LyricsLine(
-                         time = timeMs,
+ val timeMs = max(0L, minutes * 60 * 1000 + seconds * 1000 + millis + globalOffset)


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:122-128 ───
**Binary search returns last occurrence for duplicate timestamps (Pre-scan Focus #4).**

When multiple lines share the same timestamp, `getCurrentLineIndex` returns the last matching index.
This behavior should be verified against the UI requirement — some designs expect the first
occurrence (e.g., to show the earliest duplicate) while others expect the last. Currently it returns
the last occurrence due to the `mid == size-1 || next.time > currentTimeMs` condition.

- while (left <= right) {
-             val mid = (left + right) / 2
+ // If first occurrence is desired, add backward search when mid+1.time == currentTimeMs
              when {
                  lyrics.lines[mid].time <= currentTimeMs -> {
                      if (mid == lyrics.lines.size - 1 || lyrics.lines[mid + 1].time > currentTimeMs) {
                          return mid
+         }
+         left = mid + 1
                      }


─── app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt:72-76 ───
**Inconsistent round-trip precision (Pre-scan Focus #5): `toLrcText` discards millisecond
precision.**

`toLrcText` formats time as `%02d` using `millis / 10`, which truncates millisecond values that are
not multiples of 10 (e.g., a parsed time of 1005ms → outputs `.00` instead of `.00` → 1000ms, losing
5ms). While LRC centisecond format inherently has 10ms precision, the parser accepts 3-digit millis
input (e.g., `.005`→5ms) that cannot be faithfully serialized. Consider clamping input millis to
multiples of 10 during parsing or adding a comment documenting the precision limitation.

+ // Round to nearest 10ms to match LRC centisecond precision
  val millis = timeMatch.groupValues[3].let {
-                         if (it.length == 2) it.toLong() * 10 else it.toLong()
+     val raw = if (it.length == 2) it.toLong() * 10 else it.toLong()
+     (raw / 10) * 10  // snap to 10ms boundary
                      }
-
-                     val timeMs = minutes * 60 * 1000 + seconds * 1000 + millis + globalOffset


─── app/src/main/java/com/nasmusic/tv/lyrics/Mp3MetadataExtractor.kt:32-32 ───
Magic number 26 is used instead of the named constant `MediaMetadataRetriever.METADATA_KEY_LYRICS`.
Since the code already guards on `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`, the constant is
available at this API level. Using the literal integer is fragile — it reduces readability and could
break silently if the constant's internal value changes across Android versions or if the code is
ever refactored to support lower API levels. Prefer the named constant.

- retriever.extractMetadata(26) // METADATA_KEY_LYRICS = 26
+ retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LYRICS)


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:27-27 ───
**`networkMusicManager` is unused in `getLyrics()` path for network songs** — The `getLyrics()`
method (line 42-53) calls `checkAvailability()` which handles network songs with
`networkMusicManager`. However, the `getLyrics()` method itself does not directly use
`networkMusicManager`. This is fine architecturally, but the constructor parameter is only needed
for `getLyricsFromSource()`, making the class API slightly confusing — callers must know to use
`getLyricsFromSource(EMBEDDED)` for network songs with fallback, while `getLyrics()` only delegates
to `checkAvailability()`.



─── app/src/main/java/com/nasmusic/tv/lyrics/Mp3MetadataExtractor.kt:0-0 ───
The `context` constructor parameter is never used in any method of this class. This is dead/unused
code that increases object allocation overhead for no benefit. Either remove the parameter entirely
(if no future use is planned) or use `MediaMetadataRetriever(context)` constructor (available since
API 14) instead of the parameterless constructor, which can improve resource locality.

- class Mp3MetadataExtractor(private val context: Context)
+ class Mp3MetadataExtractor {
+     // context removed or used with MediaMetadataRetriever(context)


─── app/src/main/java/com/nasmusic/tv/ui/DialogBackHandler.kt:0-0 ───
Exposing `MutableState` directly via `staticCompositionLocalOf` allows any composable in the tree to
call `LocalXxxBackHandler.current.value = ...` and overwrite the handler, potentially breaking the
intended layered back-handling order (Level 1 → 1.5 → 2 → 3). This design leaks write access to the
entire composition subtree, making it possible for a lower-level composable to unintentionally clear
or replace a higher-priority handler (e.g., a child dialog could null out `LocalDialogBackHandler`
before Level 1.5 gets a chance to run).

**Recommendation:** Expose read-only `State<(() -> Unit)?>` (or `State<(() -> Boolean)?>`) through
the CompositionLocal and provide controlled setter functions (e.g., a `BackHandlerManager`
composable or a dedicated holder object) to manage state changes, ensuring the layered priority is
enforced.

- val LocalDialogBackHandler = staticCompositionLocalOf<MutableState<(() -> Unit)?>> {
-     mutableStateOf(null)
- }
-
- val LocalListBackHandler = staticCompositionLocalOf<MutableState<(() -> Boolean)?>> {
-     mutableStateOf(null)
- }
-
- val LocalNavigateBackHandler = staticCompositionLocalOf<MutableState<(() -> Unit)?>> {
+ // Example: expose read-only State, control writes via a dedicated composable
+ val LocalDialogBackHandler = staticCompositionLocalOf<State<(() -> Unit)?>> {
      mutableStateOf(null)
  }

- val LocalShowExitConfirm = staticCompositionLocalOf<MutableState<Boolean>> {
-     mutableStateOf(false)
- }
+ // In a parent composable, provide a setter alongside the state:
+ // CompositionLocalProvider(LocalDialogBackHandler provides remember { mutableStateOf(null) })


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt:0-0 ───
**Missing LOCAL_CACHE branch in getLyricsFromSource** — The `when` expression handles `EMBEDDED`,
`LOCAL_FILE`, and `NETWORK`, but not `LOCAL_CACHE`. Calling `getLyricsFromSource(song,
LyricsSource.LOCAL_CACHE)` returns `null` even if the song is cached. `SERVER` is also unhandled but
may be intentionally reserved.

- when (source) {
-             LyricsSource.EMBEDDED -> {
-             ...
+ LyricsSource.LOCAL_CACHE -> {
+     val cached = getCachedLyrics(song)
+     cached?.let { return@withContext it }
+     null
+ }
+ LyricsSource.EMBEDDED -> { ... }
              LyricsSource.LOCAL_FILE -> getLocalLrcFile(song)
-             LyricsSource.NETWORK -> {
-             ...
+ LyricsSource.NETWORK -> { ... }
              else -> null
-         }


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsNetworkProvider.kt:97-99 ───
**Resource leak: OkHttp Response never closed.** `client.newCall(request).execute()` returns a
`Response` that must be closed. Here `.body?.string()` will close the body only when `body()` is
non-null, but if `body()` is null, the Response is never closed. Additionally, if an exception
occurs between `execute()` and `body().string()`, the Response leaks. Fix: wrap in `response.use {
}` to guarantee closure.

Same issue at lines 97-101 (Kugou search), 114-118 (Kugou lyrics), 145-149 (Netease search), 163-167
(Netease lyrics), and 213-217 (Kugou download).

  val searchResponse = client.newCall(searchRequest).execute()
-             val searchBody = searchResponse.body?.string()
+             val searchBody = searchResponse.use { response ->
+                 if (!response.isSuccessful) {
+                     AppLog.w(TAG, "Kugou search: HTTP ${response.code}")
+                     return@use null
+                 }
+                 response.body?.string()
+             }
              if (searchBody == null) { AppLog.w(TAG, "Kugou search: null body"); return null }


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsNetworkProvider.kt:97-99 ───
**HTTP error status not checked.** `response.isSuccessful` (or `response.code`) is never validated
before reading the body. When the server returns a non-2xx response (e.g., 404, 500), the code still
attempts to parse the error body, producing misleading logs and wasted processing. Add an explicit
check after each `execute()` call.

This applies to all 5 response-handling sites in the file (lines 97, 114, 145, 163, 213).

  val searchResponse = client.newCall(searchRequest).execute()
+             if (!searchResponse.isSuccessful) {
+                 AppLog.w(TAG, "Kugou search: HTTP ${searchResponse.code}")
+                 searchResponse.close()
+                 return null
+             }
              val searchBody = searchResponse.body?.string()
-             if (searchBody == null) { AppLog.w(TAG, "Kugou search: null body"); return null }


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsNetworkProvider.kt:52-52 ───
**Unused `gson` field — dead code.** The field `private val gson = Gson()` is initialized but never
referenced anywhere in the file. All JSON parsing in this class is done via
`JsonParser.parseString()` from the `com.google.gson.JsonParser` class. Remove the unused field to
avoid unnecessary object creation and misleading intent.

- private val gson = Gson()
+ // Remove unused field


─── app/src/main/java/com/nasmusic/tv/lyrics/LyricsNetworkProvider.kt:36-40 ───
**Thread pool never shut down, potential leak on re-instantiation.**
`Executors.newCachedThreadPool()` is created as an instance field, but there is no lifecycle
management (no `close()`/`shutdown()`). Although daemon threads prevent JVM blocking, if
`LyricsNetworkProvider` objects are created and discarded repeatedly (e.g., by DI or Activity
re-creation), threads and their backing pools accumulate indefinitely. Consider making the
`OkHttpClient` and its executor static/singleton (e.g., in `companion object`), or implement
`Closeable` to shut down the executor when the provider is no longer needed.

+ companion object {
  private val daemonExecutor = Executors.newCachedThreadPool { r ->
          Thread(r, "LyricsNetwork-OkHttp").apply { isDaemon = true }
      }
-
-     private val client: OkHttpClient by lazy {
+         private val client: OkHttpClient by lazy { ... }
+     }


─── app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt:105-112 ───
**runBlocking on main thread may cause ANR**

`kotlinx.coroutines.runBlocking` is called on the Android main thread inside the exit-confirm
callback. If `app.backendRegistry.disconnect()` performs network I/O (e.g., OkHttp call with a
default 30-second timeout), it will block the UI thread for the entire duration, triggering an ANR
dialog before the process is killed.

**Suggestion:** Use `withTimeout` inside `runBlocking` to cap the blocking time, or move the
disconnect into `onDestroy` with a non-cancellable coroutine and remove the blocking call from this
callback:

```kotlin
kotlinx.coroutines.runBlocking {
    withTimeout(5_000L) {  // prevent ANR
        try {
            app.backendRegistry.disconnect()
            AppLog.d("MainActivity", "exit: backend disconnected")
        } catch (e: Exception) {
            AppLog.w("MainActivity", "exit: disconnect failed", e)
        }
    }
}
```

  kotlinx.coroutines.runBlocking {
+     withTimeout(5_000L) {
                          try {
                              app.backendRegistry.disconnect()
                              AppLog.d("MainActivity", "exit: backend disconnected")
                          } catch (e: Exception) {
                              AppLog.w("MainActivity", "exit: disconnect failed", e)
+         }
                          }
                      }


─── app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt:250-257 ───
**Missing NonCancellable in onDestroy coroutine**

The coroutine launched on `app.applicationScope` may be cancelled before `disconnect()` completes if
the application scope transitions to a cancelled state during process shutdown. This could leave the
backend connection (and underlying OkHttp connection pool) not properly released, causing resource
leakage.

**Suggestion:** Wrap the launch with `NonCancellable` context to ensure cleanup always runs to
completion:

```kotlin
app.applicationScope.launch(NonCancellable) {
    try {
        app.backendRegistry.disconnect()
        AppLog.d("MainActivity", "onDestroy: backend disconnected")
    } catch (e: Exception) {
        AppLog.w("MainActivity", "onDestroy: disconnect failed", e)
    }
}
```

- app.applicationScope.launch {
+ app.applicationScope.launch(NonCancellable) {
              try {
                  app.backendRegistry.disconnect()
                  AppLog.d("MainActivity", "onDestroy: backend disconnected")
              } catch (e: Exception) {
                  AppLog.w("MainActivity", "onDestroy: disconnect failed", e)
              }
          }


─── app/src/main/java/com/nasmusic/tv/ui/components/CommonComponents.kt:36-36 ───
硬编码的箭头符号 "←" 未使用 stringResource 进行国际化。当前返回按钮中文本部分已通过 `stringResource(R.string.common_back)`
正确本地化，但箭头符号 "←" 是直接硬编码的字符串。在支持多语言的应用中，不同语言区域可能需要不同的返回箭头符号（例如 RTL 语言中可能使用 "→"
或其他符号），硬编码将导致该符号无法随语言切换而改变。建议将箭头符号也定义为 string resource 并使用 stringResource 引用。

- Text(text = "←", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
+ Text(text = stringResource(R.string.common_back_arrow), fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))


─── app/src/main/java/com/nasmusic/tv/ui/components/CommonComponents.kt:20-22 ───
BackButton 组件缺少 Modifier 参数，限制了调用方对布局的自定义能力。在 Compose 中，所有可复用的公共组件应接受 Modifier
参数并应用到根布局，这是标准实践。当前调用方无法为 BackButton 添加 padding、width/height 等布局调整。FocusableSurface 本身已支持 modifier
参数，只需将其透传即可。

  @Composable
- fun BackButton(onClick: () -> Unit) {
+ fun BackButton(
+     onClick: () -> Unit,
+     modifier: Modifier = Modifier
+ ) {
      FocusableSurface(
+         onClick = onClick,
+         modifier = modifier,


─── app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt:89-101 ───
BUG: `onAllFailed` is called repeatedly (not once per session). When all candidates fail in the
multi-cover path (line 99: `currentFailed = 1`), `onAllFailed()` fires and the placeholder is shown.
But on the next timer tick (line 72), `fallbackOffset` resets to 0 and `carouselIndex` increments,
producing a new `effectiveUrl`. The `key(effectiveUrl)` block recomposes afresh, `currentFailed`
reinitializes to 0, and the entire retry cycle restarts — calling `onAllFailed()` again at the end
of each cycle. This likely does not match the caller's expectation that `onAllFailed` fires once per
track/session.

**Suggestion**: Introduce a "permanently failed" flag at the carousel level (outside the `key`
block) that, once set, immediately returns `PlaceholderCover` without retrying.

+     var carouselIndex by remember(coverCandidates) { mutableIntStateOf(0) }
+     var fallbackOffset by remember { mutableIntStateOf(0) }
+     var permanentlyFailed by remember { mutableStateOf(false) }  // New: prevents retry cycle
+
+     if (permanentlyFailed) {
+         PlaceholderCover(modifier)
+         return
+     }
+
+     LaunchedEffect(isPlaying, coverCandidates) {
+         if (isPlaying) {
+             while (true) {
+                 delay(10_000L)
+                 carouselIndex = (carouselIndex + 1) % coverCandidates.size
+                 fallbackOffset = 0
+             }
+         }
+     }
+     // ... inside onError:
                              onError = {
-                                 // 当前 URL 失败，尝试候选列表下一项
                                  if (fallbackOffset < coverCandidates.size - 1) {
                                      fallbackOffset++
                                  } else {
-                                     currentFailed = 1
-                                 }
+             permanentlyFailed = true
+             onAllFailed()  // Called once
                              }
-                         )
-                     } else {
-                         PlaceholderCover(modifier)
-                         onAllFailed()
                      }


─── app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt:64-64 ───
BUG: `fallbackOffset` is remembered without a key, so its value persists when `coverCandidates`
changes identity. Meanwhile `carouselIndex` uses `remember(coverCandidates)`, which resets on a new
list reference. This asymmetry can produce a stale offset that skips entries in the new list.

Example scenario: carousel shows index 0 → URL fails → `fallbackOffset` becomes 1 (show index 1).
Then `coverCandidates` receives a new list instance entirely. `carouselIndex` resets to 0, but
`fallbackOffset` stays 1, so the effective index jumps to 1, skipping index 0 of the new list.

**Suggestion**: Key `fallbackOffset` on `coverCandidates` so it resets when the candidate list
changes.

-     var fallbackOffset by remember { mutableIntStateOf(0) }
+     var fallbackOffset by remember(coverCandidates) { mutableIntStateOf(0) }


─── app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt:44-60 ───
INCONSISTENCY: Single-cover and multi-cover fallback behavior differ. When a single URL fails (lines
44-59), the component calls `onAllFailed()` exactly once and never retries. But in multi-cover mode
(lines 62-107), all candidates are retried in a cycle, and `onAllFailed()` is called repeatedly.
Callers cannot rely on consistent error-handling semantics.

**Suggestion**: Unify the behavior. Either both paths should retry on transient errors, or both
should fail permanently after exhausting candidates. Document the contract clearly.

      if (coverCandidates.size == 1) {
-         // 单张静态显示（保留 onError fallback 到占位符）
          var singleFailed by remember(coverCandidates[0]) { mutableIntStateOf(0) }
          if (singleFailed == 0) {
              AsyncImage(
                  model = coverCandidates[0],
                  contentDescription = "Album Cover",
                  contentScale = ContentScale.Fit,
                  modifier = modifier.fillMaxSize(),
                  onError = { singleFailed = 1 }
              )
          } else {
              PlaceholderCover(modifier)
              onAllFailed()
          }
          return
      }
+     // Consider: add a single retry for transient errors, e.g.:
+     // - Retry the same URL once before falling back to placeholder.


─── app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt:40-42 ───
POTENTIAL ISSUE: `onAllFailed()` is called directly during composition (lines 41, 57, 100, 105). Per
Compose guidelines, calling arbitrary lambdas during composition is discouraged because they can
trigger state changes that cause unexpected recomposition loops. The callback should be deferred to
a side-effect scope.

**Suggestion**: Wrap `onAllFailed()` calls in a `LaunchedEffect(Unit)` or `SideEffect` block.

          PlaceholderCover(modifier)
-         onAllFailed()
+         LaunchedEffect(Unit) { onAllFailed() }
          return


─── app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt:113-118 ───
**Race Condition — concurrent animation coroutines on focus change**

The `onFocusChanged` callback launches a new `scope.launch` coroutine on every focus change without
cancelling the previous one. Rapid focus changes (e.g., arrow key navigation in TV UI) will spawn
multiple concurrent `animateTo` coroutines racing against each other, causing visual jitter.

**Suggested fix**: Replace `rememberCoroutineScope()` + `scope.launch` with
`LaunchedEffect(isFocused)` which automatically cancels the previous animation when `isFocused`
changes:

```kotlin
LaunchedEffect(isFocused) {
    animScale.animateTo(
        if (isFocused) focusedScale else 1f,
        tween(animationDurationMs)
    )
}
```

Then remove the animation code from `onFocusChanged`.

- scope.launch {
+     // Remove rememberCoroutineScope() variable
+     // Add LaunchedEffect at composable body level:
+     LaunchedEffect(isFocused) {
      animScale.animateTo(
          if (isFocused) focusedScale else 1f,
          tween(animationDurationMs)
      )
  }
+
+     // In onFocusChanged, only update state:
+     .onFocusChanged {
+         isFocused = it.isFocused
+         onFocusChanged?.invoke(it.isFocused)
+     },


─── app/src/main/java/com/nasmusic/tv/ui/components/PlayerControls.kt:101-102 ───
**Modifier order conflict:** The function appends `.fillMaxWidth()` to the caller-supplied
`modifier`. If a caller passes width-constraining modifiers (e.g., `Modifier.widthIn(max = 400.dp)`
or `Modifier.padding(start = 80.dp, end = 80.dp)`), the final `fillMaxWidth()` overrides those
constraints, potentially breaking the parent layout. The caller's `modifier` should be placed last
in the chain so it can override the internal defaults.

  Row(
-         modifier = modifier.fillMaxWidth(),
+         modifier = modifier.fillMaxWidth().then(modifier), // user modifier applied last


─── app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt:181-187 ───
Null safety risk: `currentSong!!` inside the lambda is evaluated at invocation time, not at lambda
creation time. Since `currentSong` is a delegated property (`by collectAsState`), each access
re-evaluates the delegate getter, so the value could be null by the time this lambda executes (e.g.,
after back navigation). Prefer capturing the value outside the lambda with `?.let` to avoid the race
condition.

-                         onToggleFavorite = if (currentSong != null) {
+                         onToggleFavorite = currentSong?.let { song ->
                              {
-                                 val song = currentSong!!
                                  if (song.isNetworkSong) viewModel.toggleNetworkFavorite(song)
                                  else viewModel.toggleFavorite(song)
                              }
-                         } else null
+                         }


─── app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt:0-0 ───
**Double Scaling — outer `.scale()` and `ClickableSurfaceDefaults.scale` compound**

The outer modifier chain applies `.scale(animScale.value)` (line 89) for focus animation, AND
`ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = pressedScale)` is passed to
`Surface` (lines 132-134). When the user presses the surface, both scales will multiply:
`animScale.value * pressedScale`. For example, if `focusedScale = 1.08f` and `pressedScale = 0.96f`,
the effective press scale becomes `1.08 × 0.96 = 1.037`, not `0.96` — resulting in an unintentional
visual "bounce" or insufficient press feedback.

**Suggested fix**: Either set `pressedScale = 1f` in `ClickableSurfaceDefaults.scale` (since the
outer `.scale()` modifier handles it), or pass `focusedScale` into `ClickableSurfaceDefaults.scale`
and remove the outer `.scale()` modifier.

-         modifier = modifier
-             .scale(animScale.value)
-             ...
+         // Option A: Disable Surface's own scale and keep outer scale for logic
          scale = ClickableSurfaceDefaults.scale(
              focusedScale = 1f,
-             pressedScale = pressedScale
+             pressedScale = 1f  // Avoid compounding — outer .scale handles it
          )
+
+         // OR Option B: Delegate all scaling to Surface and remove outer .scale(animScale.value)


─── app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt:82-88 ───
**Exception silencing — broad catch hides legitimate failures**

`focusRequester.requestFocus()` catches all `Exception` types silently. This can mask real problems
such as `IllegalStateException` (component not attached), `SecurityException` (permission issues on
certain OEM TVs), or other unexpected runtime errors. At minimum, log the exception for debugging.

**Suggested fix**: Catch only specific expected exception types and log the exception.

          LaunchedEffect(Unit) {
              try {
                  focusRequester.requestFocus()
-             } catch (_: Exception) {
-                 // 忽略焦点请求失败（例如组件尚未附着）
+             } catch (e: IllegalStateException) {
+                 // Component not yet attached — expected during initial composition
+                 android.util.Log.w("FocusableSurface", "Focus request failed (component not attached)", e)
              }
          }


─── app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt:99-109 ───
**Border rendering triggers full recomposition on every focus change**

The `isFocused` state is observed in both `if` branches within `.then()` (lines 90-95), causing the
entire `Surface` composable to recompose on every focus change just to toggle the border. Consider
using `Modifier.drawWithContent` or `Modifier.border().animateBorder()` to avoid this recomposition
overhead.

While not a critical bug, on a TV UI with 30+ usages, this can cause noticeable frame drops during
rapid navigation.

              .then(
                  if (showFocusBorder) {
-                     Modifier.border(
-                         width = if (isFocused) 2.dp else 0.dp,
-                         color = if (isFocused) focusBorderColor else Color.Transparent,
-                         shape = shape
+                     Modifier.drawWithContent {
+                         drawContent()
+                         if (isFocused) {
+                             drawRoundRect(
+                                 color = focusBorderColor,
+                                 size = size,
+                                 style = Stroke(width = 2.dp.toPx()),
+                                 cornerRadius = CornerRadius(...) // derive from shape
                      )
+                         }
+                     }
                  } else {
                      Modifier
                  }
              )


─── app/src/main/java/com/nasmusic/tv/ui/components/FocusableSurface.kt:68-69 ───
**Default pressed colors lack visual feedback — `null` fallback equals unfocused color**

The defaults for `pressedContainerColor` and `pressedContentColor` are `null`, which fall back to
`containerColor` and `contentColor` respectively (lines 126-127). This means by default, a pressed
surface shows no color change, providing zero visual feedback on press. For a TV UI where focus +
press interactions are primary, a more distinct pressed color (e.g., a slightly darker variant)
should be the default.

-     pressedContainerColor: Color? = null,
-     pressedContentColor: Color? = null,
+     pressedContainerColor: Color? = containerColor.copy(alpha = 0.8f),  // or a specific dimmed color
+     pressedContentColor: Color? = contentColor.copy(alpha = 0.8f),


─── app/src/main/java/com/nasmusic/tv/ui/components/PlayerControls.kt:0-0 ───
**Dead Code / Unused Parameter:** `currentSongId` is declared in the function signature (defaulting
to `null`) but is never referenced anywhere in the function body. This adds noise to the API and
suggests incomplete integration with song-specific logic. If it is intended for future use (e.g., as
a key for `LaunchedEffect`), it should be integrated now; otherwise, remove it.

+ // Option A: Remove
  fun ProgressSection(
      progressMs: Long,
      durationMs: Long,
      onSeek: (Long) -> Unit,
      onProgressFocusChanged: (Boolean) -> Unit = {},
      modifier: Modifier = Modifier,
-     compact: Boolean = false,
-     currentSongId: String? = null
+     compact: Boolean = false
  )
+
+ // Option B: Integrate as LaunchedEffect key (see next finding)


─── app/src/main/java/com/nasmusic/tv/ui/components/PlayerControls.kt:93-99 ───
**Focus key stability:** `LaunchedEffect(Unit)` runs only once when the composable first enters
composition. If `ProgressSection` is reused for a different song (e.g., via recomposition with a
different `currentSongId`), focus will NOT be re-requested. On TV, this can break the remote-control
navigation expectation that the progress bar regains focus after a track change. The parameter
`currentSongId` appears to have been added specifically for this purpose but is left unused.

- LaunchedEffect(Unit) {
+ LaunchedEffect(currentSongId) {
          try {
              progressFocusRequester.requestFocus()
          } catch (_: Exception) {
              // 焦点请求失败时忽略，不影响播放
          }
      }


─── app/src/main/java/com/nasmusic/tv/ui/components/PlayerControls.kt:271-285 ───
**Shadow rendering overhead on TV hardware:** The primary play/pause button applies a custom shadow
via `Modifier.shadow(elevation, ambientColor, spotColor)`. Shadow compositing on Android TV (often
GPU-limited) can cause frame drops, especially during the scale-up animation (1.0 → 1.12) that
triggers frequent recomposition. Prefer a lighter visual treatment — e.g., a simple colored inner
glow or a focused ring — rather than a material shadow for the "glow" effect.

- val glowModifier: Modifier = if (primary) {
-         modifier
-             .size(buttonSize)
-             .scale(animScale.value)
-             .shadow(
-                 elevation = glowElevation,
-                 shape = CircleShape,
-                 ambientColor = NasMusicColors.Primary,
-                 spotColor = NasMusicColors.Primary
-             )
-     } else {
+ // Consider replacing shadow with a simple border/focused indicator
+ if (primary) {
          modifier
              .size(buttonSize)
              .scale(animScale.value)
+         .then(if (isFocused) Modifier.border(3.dp, NasMusicColors.Primary, CircleShape) else Modifier)
      }


─── app/src/main/java/com/nasmusic/tv/ui/components/PlayerControls.kt:0-0 ───
**Focus navigation locked by onPreviewKeyEvent:** The `onPreviewKeyEvent` on the progress bar's Box
unconditionally consumes DPAD left/right key events (returning `true`) to perform seeking, even when
the user intends to navigate focus away from the progress bar. On Android TV, this traps focus on
the progress bar — users cannot use left/right DPAD to move to neighboring elements (e.g., control
buttons). Consider delegating seek to the focused child via `onKeyEvent` instead, or adding a
threshold/timer that allows focus navigation after a delay.

- .onPreviewKeyEvent { event ->
+ // Option A: Use onKeyEvent on the focused Surface instead
+ Surface(
+     onClick = { ... },
+     modifier = Modifier.fillMaxSize()
+         .focusRequester(progressFocusRequester)
+         .onFocusChanged { ... }
+         .onKeyEvent { event ->  // onKeyEvent, not onPreviewKeyEvent
                      if ((event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                          && event.type == KeyEventType.KeyDown && durationMs > 0) {
-                         val delta = if (event.key == Key.DirectionLeft) -15000L else 15000L
-                         val newPosition = (progressMs + delta).coerceIn(0, durationMs)
-                         AppLog.d("NASMusic", "ProgressSection: seek by ${delta}ms, current=$progressMs, new=$newPosition, duration=$durationMs")
-                         onSeek(newPosition)
+                 ...
                          true
+             } else false
+         }
+     ...
+ )
+
+ // Option B: Use onPreviewKeyEvent but return false for repeat events or when focus should navigate
+ .onPreviewKeyEvent { event ->
+     if (event.type == KeyEventType.KeyDown && durationMs > 0) {
+         when (event.key) {
+             Key.DirectionLeft -> { onSeek((progressMs - 15000L).coerceIn(0, durationMs)); true }
+             Key.DirectionRight -> { onSeek((progressMs + 15000L).coerceIn(0, durationMs)); true }
+             else -> false
+         }
                      } else false
                  }


─── app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt:181-183 ───
The notification action button labels are semantically inverted. When `isPlaying` is true, the
button's action label reads `playback_paused` (meaning 'Paused' - describing the current state)
instead of indicating the action that pressing the button will perform (i.e., 'Pause'). Similarly,
when `isPlaying` is false, the label reads `playback_playing` ('Playing') instead of 'Play'.
Notification action labels should describe the action to be performed, not the current state. Use
resources like `R.string.playback_pause` ('Pause') and `R.string.playback_play` ('Play').

  val playPauseAction = NotificationCompat.Action.Builder(
              if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
-             if (isPlaying) getString(R.string.playback_paused) else getString(R.string.playback_playing),
+             if (isPlaying) getString(R.string.playback_pause) else getString(R.string.playback_play),


─── app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt:219-229 ───
The notification action buttons (play/pause, previous, next) will likely be non-functional because
`buildMediaButtonPendingIntent` broadcasts `ACTION_MEDIA_BUTTON` intents, but there is no
`MediaButtonReceiver` registered in `AndroidManifest.xml` to receive these broadcasts. In Android
8+, for notification media actions to work with a Media3 `MediaLibraryService`, the broadcast
intents must be received by a `MediaButtonReceiver` (typically a `BroadcastReceiver` registered in
the manifest) which forwards them to the `MediaSession`. Without this receiver, the
`ACTION_MEDIA_BUTTON` broadcasts are never delivered, and the notification controls have no effect.

- private fun buildMediaButtonPendingIntent(keyCode: Int): PendingIntent {
-         val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
-         val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
-             setPackage(packageName)
-             putExtra(Intent.EXTRA_KEY_EVENT, keyEvent)
-         }
-         return PendingIntent.getBroadcast(
-             this, keyCode, intent,
-             PendingIntent.FLAG_IMMUTABLE
-         )
-     }
+ // Option 1: Register a MediaButtonReceiver in AndroidManifest.xml to handle broadcast intents.
+ // Option 2: Use MediaSessionCompat.setMediaButtonReceiver() with a PendingIntent that targets the service directly.
+ // Option 3 (Recommended for Media3): Use NotificationCompat.MediaStyle with setMediaSession() and let the system handle
+ // media button events via the MediaSession session token instead of broadcasting ACTION_MEDIA_BUTTON.


─── app/src/main/java/com/nasmusic/tv/player/PlaybackService.kt:117-120 ───
In `onDestroy()`, `player.release()` is called before `mediaLibrarySession.release()`. According to
Media3 best practices, the `MediaSession` should be released first, then the `Player`. Releasing the
player while the session still references it can cause the session's cleanup code (which may call
player methods like `stop()` or `removeListener()`) to operate on a released player, leading to
`IllegalStateException` or undefined behavior.

  mediaLibrarySession?.run {
-             player.release()
              release()
+             player.release()
          }


─── app/src/main/java/com/nasmusic/tv/ui/screens/EqualizerScreen.kt:168-173 ───
**Bug: Band adjustment logic makes most negative values unreachable.**

The `when` block cycles: -10 → 0 → 1 → 2 → ... → 10 → -10. Values -9 through -1 are **never
reachable** via click interaction, meaning the user can only attenuate a band to exactly -10 dB or
amplify it — no fine-grained negative adjustments possible. This makes the equalizer effectively
unusable for mild attenuation.

- `band <= -10f -> 0f` jumps directly from -10 to 0, skipping -9..-1
- No decrement path exists: the only way to go negative is the wrap from +10 to -10
- Even if this is intended as a "resets to 0" action, it should be a separate button, not the only
click behavior

**Suggestion:** Fix the logic to support proper increment/decrement cycling, e.g.:
```kotlin
val newValue = when {
    band >= 10f -> -10f  // wrap from +10 back to -10
    else -> band + 1f
}
```
Or add a `decrement` callback so users can cycle both directions.

  val newValue = when {
-     band <= -10f -> 0f
      band >= 10f -> -10f
      else -> band + 1f
  }
  onAdjustBand(index, newValue)


─── app/src/main/java/com/nasmusic/tv/ui/screens/EqualizerScreen.kt:0-0 ───
**Data inconsistency: Always displays 10 band labels regardless of actual band count.**

The band section is gated by `currentBands.size >= 5`, but then `bandLabels` is hardcoded to 10
entries (`"31Hz"` through `"16kHz"`). For any index >= `currentBands.size`, `getOrElse(index) { 0f
}` silently returns 0.0 dB, which misrepresents the actual audio state and may confuse users who see
"0.0 dB" for bands that don't even exist.

**Suggestion:** Either:
1. Gate the section with `currentBands.size >= 10` if 10 bands are always expected, or
2. Dynamically derive labels from `currentBands.size` to display only the available bands.

- if (currentBands.size >= 5) {
+ if (currentBands.size >= 10) {
      ...
      val bandLabels = listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
      items(bandLabels.size) { index ->
          val band = currentBands.getOrElse(index) { 0f }


─── app/src/main/java/com/nasmusic/tv/ui/screens/EqualizerScreen.kt:96-96 ───
**Redundant safe call on a non-null parameter.**

The `currentPreset` parameter is declared as `EqualizerPreset` (non-null), but the code uses
`currentPreset?.name`. The `?.` safe call is unnecessary and could mislead maintainers into thinking
`currentPreset` is nullable.

**Suggestion:** Replace with a direct call: `currentPreset.name`.

- val isSelected = currentPreset?.name == preset.name
+ val isSelected = currentPreset.name == preset.name


─── app/src/main/java/com/nasmusic/tv/ui/screens/EqualizerScreen.kt:162-163 ───
**Performance: `bandLabels` list is recreated on every recomposition inside the LazyColumn
content.**

The list `listOf("31Hz", "63Hz", ...)` is allocated anew each time the composable function
recomposes. While the list is small, this is an easy fix and good practice.

**Suggestion:** Move it to a top-level `private val` or wrap in `remember`:
```kotlin
private val bandLabels = listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz",
"8kHz", "16kHz")
```

- val bandLabels = listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
-                 items(bandLabels.size) { index ->
+ // At file level:
+ private val BAND_LABELS = listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
+
+ // Inside the composable:
+ items(BAND_LABELS.size) { index ->


─── app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt:0-0 ───
Immersive mode cover background: `AsyncImage` is used without placeholder, error handling, or
fallback content. If the `coverUrl` fails to load (network error, invalid URL, or no image), the
entire immersive background will be blank (just the blur on nothing), breaking the user experience.
Add `placeholder` and `error` parameters to AsyncImage, and/or display a solid fallback color behind
it.

  AsyncImage(
      model = currentSong.coverUrl,
      contentDescription = "Fullscreen Cover Background",
      modifier = Modifier
          .fillMaxSize()
-         .blur(30.dp)
+         .blur(30.dp),
+     placeholder = painterResource(id = R.drawable.ic_placeholder),
+     error = painterResource(id = R.drawable.ic_placeholder)
  )


─── app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt:81-86 ───
Performance: `NasMusicColors.AccentGlow` (line 286) and other Color constants like
`NasMusicColors.Background` (line 60) are used inside composable functions, but `Color(0xFF0A1020)`,
`Color(0xCC0C1222)` etc. are hardcoded literal colors recreated on every recomposition. Define these
as top-level `val` constants to avoid object allocation on each recomposition.

- Brush.verticalGradient(
-     listOf(
-         NasMusicColors.Background,
-         Color(0xFF0A1020)
-     )
- )
+ // Define at file-level:
+ private val BgGradientBottom = Color(0xFF0A1020)
+ private val OverlayTop = Color(0xCC0C1222)
+ private val OverlayMid = Color(0x990C1222)
+ private val OverlayBottom = Color(0xCC0C1222)


─── app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt:92-94 ───
Resource leak concern (not critical for Compose UI, but noteworthy): `coil.compose.AsyncImage` is
used without an explicit `imageLoader`. In a TV app, consider providing a configured `ImageLoader`
via `LocalImageLoader` to control caching, disk access, and error handling consistently. The current
usage relies on Coil's default singleton which may not be optimized for TV.

+ val imageLoader = LocalImageLoader.current
  AsyncImage(
      model = currentSong.coverUrl,
      contentDescription = "Fullscreen Cover Background",
+     imageLoader = imageLoader,


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:0-0 ───
**Performance: `estimateWordTimestamps()` called on every recomposition without caching**

When the current line has no word timestamps (standard LRC), `estimateWordTimestamps()` is called on
every recomposition, allocating a new `List<WordTimestamp>` each time. For a 50ms refresh cycle
(20fps), this generates significant GC pressure. The result should be cached with `remember` keyed
on the line index and line text, so it is only recomputed when the line content or timing changes.

- val wordTimestamps = if (line.wordTimestamps.isNotEmpty()) {
+ val wordTimestamps = remember(line.text, line.time, index, lyrics.lines.getOrNull(index + 1)?.time) {
+     if (line.wordTimestamps.isNotEmpty()) {
      line.wordTimestamps
  } else {
      val nextLineTime = if (index + 1 < lyrics.lines.size) {
          lyrics.lines[index + 1].time
      } else {
          line.time + 3000L
      }
      estimateWordTimestamps(line, nextLineTime)
+     }
  }


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:0-0 ───
**Correctness: Time interpolation drifts when `currentTimeMs` stalls during playback**

The interpolation loop (lines 111-130) recalibrates the anchor only when `currentTimeMs !=
anchorProgress` (line 122). If `currentTimeMs` stops updating while `isPlaying` remains true (e.g.,
audio source freezes or updates at a variable rate), `elapsed` continues accumulating and
`lyricTickMs` will drift arbitrarily far ahead of the actual audio position, causing word
highlighting to desync. The loop lacks a playback-stalled guard or a cap on the maximum drift
relative to the source time.

  while (true) {
      delay(50)
      val elapsed = System.currentTimeMillis() - anchorSystemMs
-     lyricTickMs = anchorProgress + elapsed
+     val interpolated = anchorProgress + elapsed
+     // Clamp drift: never exceed 2 seconds ahead of the last known currentTimeMs
+     val maxAllowed = currentTimeMs + 2000L
+     lyricTickMs = interpolated.coerceAtMost(maxAllowed)
      if (currentTimeMs != anchorProgress) {
          anchorProgress = currentTimeMs
          anchorSystemMs = System.currentTimeMillis()
      }
  }


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:140-143 ───
**Performance: O(n) `indexOfFirst` on every recomposition**

`currentIndex` is recomputed from scratch using `indexOfFirst` (line 140-141) on every
recomposition. For song lyrics with hundreds of lines, this is O(n) per frame. Since `lyrics.lines`
is naturally sorted by time, a binary search (`binarySearchBy`) would reduce this to O(log n) and
reduce recomposition overhead.

  val currentIndex = lyrics.lines
-     .indexOfFirst { it.time > effectiveTimeMs }
-     .let { if (it == -1) lyrics.lines.size - 1 else it - 1 }
+     .binarySearchBy(effectiveTimeMs) { it.time }
+     .let { if (it < 0) -(it + 1) - 1 else it }
      .coerceAtLeast(0)


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:111-111 ───
**Unnecessary re-launch of interpolation loop when lyrics object reference changes**

The `LaunchedEffect` at line 111 includes `lyrics` in its keys: `LaunchedEffect(currentTimeMs,
isPlaying, highlightMode, lyrics)`. Any change to the `lyrics` object reference (even with identical
content) cancels and restarts the interpolation loop, resetting the anchor and causing a visible
glitch. Consider either removing `lyrics` from the keys or using a stable identifier (e.g.,
`lyrics?.songId`) if the lyrics data is expected to change identity during playback.

- LaunchedEffect(currentTimeMs, isPlaying, highlightMode, lyrics) {
+ // Use a stable key for lyrics identity; remove if lyrics does not change during playback
+ LaunchedEffect(currentTimeMs, isPlaying, highlightMode, lyrics?.songId) {


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:265-266 ───
**Unnecessary `AnnotatedString` wrapper allocation on plain text**

Line 266 wraps a plain `String` in `AnnotatedString(displayText as String)` when `displayText` is
not already an `AnnotatedString`. The `Text` composable from `androidx.tv.material3` has overloads
accepting both `String` and `AnnotatedString` directly. This wrapping creates an unnecessary object
allocation on every recomposition for lines that are not karaoke-highlighted.

+ // Text() accepts both String and AnnotatedString — remove the wrapping
+ @Composable
+ fun LyricsText(
+     displayText: Any,
+     textColor: Color,
+     fontSize: TextUnit,
+     modifier: Modifier
+ ) {
+     if (displayText is AnnotatedString) {
  Text(
-     text = if (displayText is AnnotatedString) displayText else AnnotatedString(displayText as String),
+             text = displayText,
+             color = Color.Unspecified,
+             fontSize = fontSize,
+             textAlign = TextAlign.Center,
+             modifier = modifier
+         )
+     } else {
+         Text(
+             text = displayText as String,
+             color = textColor,
+             fontSize = fontSize,
+             textAlign = TextAlign.Center,
+             modifier = modifier
+         )
+     }
+ }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt:0-0 ───
Contradictory width modifiers: `Modifier.width(200.dp).fillMaxWidth()` on the artist avatar Box.
`width(200.dp)` constrains the width to 200.dp, but `fillMaxWidth()` overrides it to fill the
maximum available width of the Row. These two modifiers conflict; the intended behavior (a 200.dp
wide avatar) is likely broken. Remove `fillMaxWidth()` to keep the fixed width, or use only
`fillMaxWidth()` if a responsive width is desired.

  modifier = Modifier
                      .width(200.dp)
-                     .fillMaxWidth()
                      .clip(CircleShape)
                      .background(NasMusicColors.Primary.copy(alpha = 0.2f))


─── app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt:179-181 ───
Focus request inside `focusGroup()` may silently fail. The `firstItemFocusRequester` (line 208) is
attached to a child `Row` inside a `Box` with `focusGroup()` (line 179). In Compose, `focusGroup()`
installs a custom focus traversal policy that may intercept `requestFocus()` calls on inner child
elements. When the user presses BACK after scrolling, the `firstItemFocusRequester.requestFocus()`
call on line 90 may be ignored, preventing focus from returning to the first item. Consider using
`FocusRequester` on the `focusGroup()` container itself, or implementing a focus-based scroll
approach (e.g., `LazyColumn` scroll without programmatic focus, letting the user manually navigate).



─── app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt:172-174 ───
Performance overhead: Each song row creates its own `Animatable(1f)` and `rememberCoroutineScope()`
(lines 173-174) and launches a coroutine on every focus change (lines 192-197). For song lists with
hundreds of items, this creates unnecessary allocations of animation instances and coroutine scopes
that are never used for most rows. Consider deferring `Animatable` creation (e.g., via
`derivedStateOf` or using `animateFloatAsState` with `FloatAnimationSpec`) and sharing a single
coroutine scope to reduce per-row overhead.

  var isRowFocused by remember { mutableStateOf(false) }
                          val animScale = remember { Animatable(1f) }
-                         val rowScope = rememberCoroutineScope()


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:0-0 ───
**Potential `StringIndexOutOfBoundsException` when `word.word` does not align with character
boundaries**

When real (non-estimated) `wordTimestamps` are provided, `word.word` may contain multi-character
tokens or letters with different Unicode normalization than the original `line.text`. The `indexOf`
search from `lastEnd` may find a match at a position such that `wordStart + word.word.length >
line.text.length`, causing `substring(lastEnd)` or `substring(lastEnd, wordStart)` to throw
`StringIndexOutOfBoundsException`. This is a correctness risk with real word-timestamp data inputs.

- for (word in wordTimestamps) {
-     val wordStart = line.text.indexOf(word.word, lastEnd)
-     if (wordStart < 0) {
-         if (lastEnd < line.text.length) {
-             append(line.text.substring(lastEnd))
-         }
-         break
-     }
-     if (wordStart > lastEnd) {
-         append(line.text.substring(lastEnd, wordStart))
-     }
-     val wordPlayed = word.startMs <= effectiveTimeMs
-     val style = if (wordPlayed) {
-         SpanStyle(color = Color.Yellow)
-     } else {
-         SpanStyle(color = textColor)
-     }
-     pushStyle(style)
-     append(word.word)
-     pop()
+ // Add bounds check after updating lastEnd
      lastEnd = wordStart + word.word.length
+ if (lastEnd > line.text.length) {
+     // If word extends beyond text, flush remaining and break
+     append(line.text.substring(wordStart))
+     lastEnd = line.text.length
+     break
  }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ExitConfirmDialog.kt:40-45 ───
**DisposableEffect key issue: Stale onDismiss causes back handler race condition**

The `DisposableEffect(onDismiss)` uses `onDismiss` as the key. If `onDismiss` changes during
recomposition (e.g., due to viewmodel state change or parent recomposition), the old effect's
`onDispose` runs and sets `backHandler.value = null`, leaving no back handler until the new effect's
block runs. This creates a window where pressing BACK does nothing.

**Suggestion**: Use `Unit` or `true` as the key so the effect only runs once when the dialog enters
composition, and clean up only when the dialog leaves. Capture `onDismiss` via a
`rememberUpdatedState` to always reference the latest lambda without restarting the effect.

- DisposableEffect(onDismiss) {
-         backHandler.value = { onDismiss() }
+ // Use rememberUpdatedState to avoid restarting the effect when onDismiss changes
+ val currentOnDismiss = rememberUpdatedState(onDismiss)
+
+ DisposableEffect(Unit) {
+     backHandler.value = { currentOnDismiss.value() }
          onDispose {
              backHandler.value = null
          }
      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt:88-91 ───
Race condition: `firstItemFocusRequester.requestFocus()` (line 90) is called right after
`listState.scrollToItem(0)` in the same coroutine, but the FocusRequester needs the first item to be
composed AND laid out before it can accept a focus request. Since scrollToItem suspends, the first
item may be composed but its layout pass may not be complete when requestFocus fires. If the
composable hasn't been measured/placed yet, `requestFocus()` silently returns false and focus is
lost. Consider adding a `LaunchedEffect` with `snapshotFlow` or delaying focus request until layout
is confirmed, e.g., using `viewTreeOwners` or deferring with `withFrameNanos`.

  scope.launch {
                      listState.scrollToItem(0)
+                     // Consider deferring focus request to after the next layout pass
+                     listState.layoutInfo.let { /* check first visible item state */ }
                      runCatching { firstItemFocusRequester.requestFocus() }
                  }


─── app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt:145-149 ───
**Animation thrashing in word-by-word mode near line boundaries**

In `WORD_BY_WORD` mode, `effectiveTimeMs` is interpolated at 50ms resolution. Near line time
boundaries (e.g., when `effectiveTimeMs` hovers around the transition point between two lines),
`currentIndex` can flip back and forth between adjacent indices on consecutive frames. Each flip
triggers a new `LaunchedEffect(currentIndex)` and a new `animateScrollToItem` animation, causing
visual scroll jitter. Mitigate by adding a hysteresis or debounce mechanism.

+ // Add a small offset buffer to prevent oscillation near line boundaries
+ val bufferedIndex = remember { mutableIntStateOf(currentIndex) }
  LaunchedEffect(currentIndex) {
-     if (currentIndex >= 0) {
+     if (kotlin.math.abs(currentIndex - bufferedIndex.intValue) > 1) {
+         bufferedIndex.intValue = currentIndex
          listState.animateScrollToItem(currentIndex)
      }
  }


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:85-87 ───
**Bug/Dead Code: `onPlayPause` callback is never invoked**

The `onPlayPause` parameter is accepted but never called anywhere in the composable. The central
play/pause button (the `Box` with `CircleShape` background) is purely decorative — it only renders
an `Icon` but has no `onClick` handler attached. This means the play/pause control is non-functional
on the Queue screen.

**Suggestion**: Either remove the unused `onPlayPause` parameter, or add a `clickable {
onPlayPause() }` modifier (or `FocusableSurface`) to the play/pause `Box` to make it interactive.

- onPlayPause: () -> Unit,
+ // Option A: Remove unused parameter
      onNext: () -> Unit,
      onPrevious: () -> Unit,
+
+ // Option B: Make the play/pause button clickable
+ Box(
+     modifier = Modifier
+         .size(56.dp)
+         .clip(CircleShape)
+         .background(NasMusicColors.Primary, shape = CircleShape)
+         .clickable { onPlayPause() }
+         .focusable(),
+     contentAlignment = Alignment.Center
+ )


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:0-0 ───
**Accessibility bug + Dead Code: `MiniIconButton` ignores `contentDescription` parameter**

The `contentDescription` parameter is accepted by `MiniIconButton` and callers pass meaningful
values like `"Previous"` and `"Next"`, but inside the composable the parameter is never forwarded to
the `Icon` — it is hardcoded as `contentDescription = null`. This makes the buttons invisible to
screen reader services.

**Suggestion**: Forward the `contentDescription` parameter to the `Icon` composable.

- MiniIconButton(onClick = onPrevious, icon = Icons.Filled.SkipPrevious, contentDescription = "Previous")
- ...
- MiniIconButton(onClick = onNext, icon = Icons.Filled.SkipNext, contentDescription = "Next")
+ // In MiniIconButton body:
+ Icon(
+     imageVector = icon,
+     contentDescription = contentDescription,
+     modifier = Modifier.size(20.dp)
+ )


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:0-0 ───
**Dead Code: `QueueActionButton.icon` parameter is declared but never used**

The `icon` parameter of `QueueActionButton` has a default value of `null` and is declared, but the
composable body never references it. It only renders `Text`. This is dead code that adds confusion.

**Suggestion**: Remove the unused `icon` parameter, or implement icon support if it was intended.

- fun QueueActionButton(text: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null)
+ fun QueueActionButton(text: String, onClick: () -> Unit)


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:96-112 ───
**Potential crash/undefined behavior: Back handler captures might be stale after DisposableEffect
disposal**

The `DisposableEffect` on lines 95-108 sets `listBackHandler.value = handler`, where `handler`
captures `scope`, `listState`, `firstItemFocusRequester`, and `listBackHandler`. If the composable
is disposed (e.g., during navigation away), the handler lambda lives on in the `MutableState` held
by `LocalListBackHandler`. If the system invokes this stale handler later (e.g., while the screen is
being animated out), it will call `scope.launch` on a disposed coroutine scope and
`listState.scrollToItem(0)` on a detached list state, which may lead to crashes or no-ops.

**Suggestion**: Set `listBackHandler.value = null` in the `onDispose` block to ensure the handler is
cleaned up immediately upon disposal. The current code sets it to null in `onDispose`, so this is
already handled correctly. However, there is a **race condition window**: between `DisposableEffect`
disposal and `onDispose` running, the stale handler could still be invoked. Consider checking
`listState.isAttached` (if available) inside the handler.

- DisposableEffect(Unit) {
-         val handler: () -> Boolean = {
-             val atTop = listState.firstVisibleItemIndex == 0 &&
-                     listState.firstVisibleItemScrollOffset == 0
+ // The onDispose cleanup is correct. Consider adding:
              if (!atTop) {
+     // Check if listState is still valid
                  scope.launch {
+         if (listState.isAttached) {
                      listState.scrollToItem(0)
                      runCatching { firstItemFocusRequester.requestFocus() }
-                 }
-                 true
-             } else {
-                 false
              }
          }
-         listBackHandler.value = handler
-         onDispose { listBackHandler.value = null }
+     true
      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:0-0 ───
**PERFORMANCE: State and `Animatable` per LazyColumn item causes memory pressure and recomposition
storms**

Each item in the `LazyColumn` creates `isRowFocused` state (`mutableStateOf(false)`) and an
`Animatable(1f)` for focus animation. With a large queue (e.g., hundreds of songs), this creates
significant memory overhead since LazyColumn keeps all visible items' state alive. Worse, each item
also declares its own `scope` via `rememberCoroutineScope()`, launching coroutines on every focus
change — this can cause rapid focus-triggered recomposition storms when using D-pad navigation (TV
input moves focus quickly through items).

**Suggestion**: Move the focus state management to the item themselves but use lightweight approach
(e.g., use `Modifier.graphicsLayer` for scale instead of `Animatable` for simpler cases, or share a
single coroutine scope). Alternatively, consider reducing animation complexity or using
`animateFloatAsState` which is simpler for single-value animations.

+ // Option: Use animateFloatAsState for simpler animation
  var isRowFocused by remember { mutableStateOf(false) }
-                         val animScale = remember { Animatable(1f) }
-                         val scope = rememberCoroutineScope()
+ val scale by animateFloatAsState(
+     targetValue = if (isRowFocused) 1.02f else 1f,
+     animationSpec = tween(200)
+ )
                          ...
-                         .onFocusChanged { state ->
-                             isRowFocused = state.hasFocus
-                             scope.launch {
-                                 animScale.animateTo(
-                                     if (isRowFocused) 1.02f else 1f,
-                                     tween(200)
+ Box(
+     modifier = Modifier
+         .scale(scale)
+         .onFocusChanged { state -> isRowFocused = state.hasFocus }
                                  )
-                             }
-                         }


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:37-37 ───
**Redundant import: `DisposableEffect` is imported but also unnecessary if cleaned up correctly**

Not an issue per se, but `DisposableEffect` usage is correct.



─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:189-189 ───
**String concatenation: `"${queue.size} 首"` mixes Chinese text directly in code instead of string
resource**

The string `"${queue.size} 首"` uses a Chinese character directly hardcoded in the Kotlin file. All
other user-facing strings are properly wrapped in `stringResource(...)` calls. This hardcoded string
breaks internationalization.

**Suggestion**: Define a string resource with a placeholder, e.g., `<string
name="queue_song_count">%d 首</string>` and use `stringResource(R.string.queue_song_count,
queue.size)`.

- Text(text = "${queue.size} 首", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
+ Text(
+     text = stringResource(R.string.queue_song_count, queue.size),
+     color = NasMusicColors.TextSecondary,
+     fontSize = 16.sp
+ )


─── app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt:0-0 ───
**Redundant modifier chain: `Modifier.fillMaxSize().weight(1f)` on empty state Box**

When `queue.isEmpty()`, the `Box` uses `Modifier.fillMaxSize().weight(1f)`. The `weight(1f)` already
causes the Box to expand vertically in a Column, and `fillMaxSize()` is redundant since the width is
already handled. This adds confusion without benefit.

- Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center)
+ Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center)


─── app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt:80-93 ───
Coroutine leak in DisposableEffect: The back handler lambda captures `scope`
(rememberCoroutineScope) and launches a coroutine inside the synchronous handler callback. If the
composable leaves composition while the coroutine is running (e.g., user navigates back), the
coroutine may continue executing and attempt to update state (scrollToItem, requestFocus) on
disposed composable elements, causing potential IllegalStateException or other undefined behavior.

  DisposableEffect(Unit) {
+         val job = Job()
          val handler: () -> Boolean = {
              val leftScrolled = !(playlistListState.firstVisibleItemIndex == 0 &&
                      playlistListState.firstVisibleItemScrollOffset == 0)
              val rightScrolled = !(songsListState.firstVisibleItemIndex == 0 &&
                      songsListState.firstVisibleItemScrollOffset == 0)
              if (leftScrolled || rightScrolled) {
-                 scope.launch {
+                 scope.launch(job) {
                      if (leftScrolled) {
                          playlistListState.scrollToItem(0)
                      }
                      if (rightScrolled) {
                          songsListState.scrollToItem(0)
                      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt:87-100 ───
Focus request timing issue after scroll: `scrollToItem(0)` is a suspending function that animates
scrolling. The focus request happens immediately after both scroll calls complete. However, when
items are scrolled into view, the corresponding composables (including the FocusRequester) may not
have been laid out yet by the composition pass. This race condition can cause `requestFocus()` to
silently fail (since `runCatching` swallows the exception) or target an invisible item.

  scope.launch {
-                     if (leftScrolled) {
-                         playlistListState.scrollToItem(0)
-                     }
-                     if (rightScrolled) {
-                         songsListState.scrollToItem(0)
-                     }
+                     // Scroll both lists in parallel for better performance
+                     val leftJob = if (leftScrolled) {
+                         launch { playlistListState.scrollToItem(0) }
+                     } else null
+                     val rightJob = if (rightScrolled) {
+                         launch { songsListState.scrollToItem(0) }
+                     } else null
+                     leftJob?.join()
+                     rightJob?.join()
+                     // Wait for recomposition to attach FocusRequester
+                     withFrameMillis { }
                      // 优先聚焦右侧歌曲列表，其次左侧播放列表
                      if (rightScrolled) {
                          runCatching { songsFirstFocusRequester.requestFocus() }
-                     } else {
+                     } else if (leftScrolled) {
                          runCatching { playlistFirstFocusRequester.requestFocus() }
                      }
                  }


─── app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt:95-99 ───
Focus logic asymmetry: When only the left list is scrolled and the right list is at the top,
`leftScrolled=true` and `rightScrolled=false`, the code correctly requests focus on
`playlistFirstFocusRequester`. However, when both lists are NOT scrolled (both `leftScrolled` and
`rightScrolled` are false), the handler returns `false` early without entering the if-block. But
when `rightScrolled` is false and `leftScrolled` is false, the else branch (line 97-98) would
request focus on `playlistFirstFocusRequester` even though nothing was scrolled — this branch is
actually unreachable in that case because the outer `if (leftScrolled || rightScrolled)` guards it.
So this is not a bug, but the intention is clearer if both conditions are checked explicitly.

  if (rightScrolled) {
                          runCatching { songsFirstFocusRequester.requestFocus() }
-                     } else {
+                     } else if (leftScrolled) {
                          runCatching { playlistFirstFocusRequester.requestFocus() }
                      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt:0-0 ───
String concatenation instead of string template: Use string templates or string resources with
format arguments for better localization and readability.

- text = stringResource(R.string.library_favorites) + " (${playlists.size})"
+ text = if (playlists.size > 0) {
+                         stringResource(R.string.playlist_count_format, playlists.size)
+                     } else {
+                         stringResource(R.string.library_favorites) + " (0)"
+                     }
+ // Or define a string resource with format placeholder: stringResource(R.string.library_favorites_format, playlists.size)


─── app/src/main/java/com/nasmusic/tv/ui/screens/PlaylistManagementScreen.kt:145-145 ───
String concatenation in onClick label: Use string template for consistency and readability.

- ButtonChip(text = "+ " + stringResource(R.string.playlist_create)) { showCreateDialog = true }
+ ButtonChip(text = "+ ${stringResource(R.string.playlist_create)}") { showCreateDialog = true }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:639-640 ───
Hardcoded Chinese string literals found in ServerConnectScreen, SettingsScreen, and elsewhere.
Extract all user-visible strings to string resources for localization support.

- text = if (baseUrl.text.isEmpty()) "https://jellyfin.example.com 或 http://192.168.1.100:8096"
-                                else baseUrl.text,
+ text = if (baseUrl.text.isEmpty()) stringResource(R.string.server_address_hint) else baseUrl.text,


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:238-251 ───
Compose MutableState (networkTestStatus, isNetworkTesting) is mutated directly from Dispatchers.IO,
which can cause UI inconsistencies. All state updates must occur on the main thread. Use
withContext(Dispatchers.Main) for these assignments.

                                  networkTestScope.launch(Dispatchers.IO) {
                                      try {
                                          val url = java.net.URL("https://www.baidu.com")
                                          val conn = url.openConnection() as java.net.HttpURLConnection
                                          conn.connectTimeout = 5000
                                          conn.readTimeout = 5000
                                          conn.requestMethod = "HEAD"
                                          val code = conn.responseCode
                                          conn.disconnect()
+                                         withContext(Dispatchers.Main) {
                                          networkTestStatus = if (code in 200..399) {
                                              "success:网络连通 (HTTP $code)"
                                          } else {
                                              "error:HTTP 响应码 $code"
+                                             }
                                          }


─── app/src/main/java/com/nasmusic/tv/ui/theme/Color.kt:6-6 ───
Multiple color constants defined in Color.kt are unused and duplicate values already defined in
Theme.kt's NasMusicColors. Remove these to eliminate dead code and avoid maintenance duplication.

- val BgPrimary = Color(0xFF0c1222)
+ // Remove this constant; use NasMusicColors.Background from Theme.kt instead


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:47-47 ───
Several definitions in Theme.kt (Success, TextBrightHighlight, overlayGradient, coverGlow,
NasMusicDimens) are not referenced anywhere in the project. Remove them as dead code unless reserved
for future use.

- val Success = Color(0xFF34D399)
+ // Remove if unused:
+ // val Success = Color(0xFF34D399)


─── app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt:53-56 ───
Both encrypt() and decrypt() in CryptoUtils have fail-open error handling: on exception, they return
the input unchanged (plaintext or ciphertext). This silently corrupts data and defeats encryption.
Exceptions should propagate or be handled with explicit error returns.

  } catch (e: Exception) {
              AppLog.e("CryptoUtils", "encrypt failed", e)
-             plainText // 失败时返回明文（降级处理）
+             throw EncryptionException("encrypt failed", e) // 显式抛出异常，由调用方决定降级策略
          }


─── app/src/main/java/com/nasmusic/tv/util/NetworkMonitor.kt:31-35 ───
NetworkMonitor's NetworkCallback implementation has flawed connectivity detection: onAvailable
triggers onNetworkAvailable() without checking actual internet capability, and onCapabilitiesChanged
can duplicate or contradict that call. Consolidate all connectivity reporting into
onCapabilitiesChanged only.

          val callback = object : ConnectivityManager.NetworkCallback() {
              override fun onAvailable(network: Network) {
                  super.onAvailable(network)
-                 onNetworkAvailable()
+                 // Don't report available yet — wait for onCapabilitiesChanged to verify internet.
              }


─── app/src/main/java/com/nasmusic/tv/util/TimeUtils.kt:24-29 ───
Both formatDuration and formatDurationWithMillis handle negative inputs inconsistently or
incorrectly. They should either fail fast on negative values or provide consistent handling (e.g.,
clamp to zero or throw IllegalArgumentException).

  fun formatDurationWithMillis(ms: Long): String {
+     if (ms < 0) throw IllegalArgumentException("Negative duration: $ms")
          val minutes = ms / 60000
          val seconds = (ms % 60000) / 1000
          val millis = ms % 1000
          return String.format(Locale.US, "%d:%02d.%03d", minutes, seconds, millis)
      }


─── app/src/main/java/com/nasmusic/tv/util/AppLog.kt:34-36 ───
**Security/Privacy Risk: Unconditional Error Logging in Release Builds**

The `e()` function (lines 24-26) logs to `android.util.Log.e` unconditionally, regardless of
`BuildConfig.DEBUG`. This means in Release builds, error messages — including full stack traces,
URLs, file paths, server endpoints, song metadata, and other potentially sensitive information — are
written to Logcat.

From the call sites observed in the codebase, `AppLog.e()` is used extensively to log:
- Exception messages and stack traces (e.g., `"getAlbums failed", e`)
- Full request URLs and response details
- Internal file paths and user-facing content

**Risk:** In production, Logcat is readable by any app with `READ_LOGS` permission (on older Android
versions) or via `adb logcat`. This could leak:
- Internal API endpoints and server URLs
- User data (song titles, artist names, etc.)
- Internal implementation details that aid reverse engineering

**Suggestion:** Either guard `e()` with `BuildConfig.DEBUG` (consistent with the other methods) or
use a conditional logging framework like `Timber` that can programmatically suppress log levels per
build type. If errors must be logged in production, consider redacting sensitive information before
logging.

      fun e(tag: String, message: String, throwable: Throwable? = null) {
+         if (BuildConfig.DEBUG) {
          android.util.Log.e(tag, message, throwable)
+         }
      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:85-91 ───
Hardcoded default credentials - real-looking username 'hxzhang' and password 'wfxzhx2000' are
embedded in source code. This is a security risk: if the code is shipped, decompiled, or leaked,
credentials are exposed. Use empty defaults and let users configure credentials on first launch.
Alternatively, store defaults in build config or environment variables, never in source.

- else TextFieldValue("hxzhang")
+ else TextFieldValue()
          )
      }
      var password by remember(initialConfig) {
          mutableStateOf(
              if (initialConfig.password.isNotBlank()) TextFieldValue(initialConfig.password)
-             else TextFieldValue("wfxzhx2000")
+             else TextFieldValue()


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:0-0 ───
Coroutine launched via `testScope.launch` is not cancelled when the composable leaves composition.
If the user navigates away while the connection test is in-flight, the coroutine continues running,
may update state after composition is gone, and could cause wasted network requests or memory leaks.
Use `DisposableEffect` or `LaunchedEffect` tied to a key to cancel the coroutine when no longer
needed.

- val testScope = rememberCoroutineScope()
-     ...
-                     testScope.launch {
-                             val config = ServerConfig(
-                                 backendType = backendType,
-                                 ...
-                             )
-                             val (success, message) = backendRegistry.testConnection(config)
-                             testStatus = if (success) "success:$message" else "error:$message"
-                             isTesting = false
-                         }
+ LaunchedEffect(Unit) { /* moved to trigger on a key */ }
+ // Or use a CancellableContinuation pattern:
+ // val testJob = remember { mutableStateOf<Job?>(null) }
+ // DisposableEffect(Unit) { onDispose { testJob.value?.cancel() } }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:395-400 ───
The `apiToken` state variable is defined and used in `ServerConfig` construction, but there is no UI
field rendered for it in the main form — only `InputField.API_TOKEN` case exists in the dialog's
`when`, yet `activeInputField` is never set to `InputField.API_TOKEN`. This makes both the dialog
case and `apiToken` state effectively dead code. Remove the dead dialog branch and apiToken state
(or add an actual UI field if intended).

- InputField.API_TOKEN -> {
-                     dialogTitle = stringResource(R.string.server_token_dialog_title)
-                     dialogHint = stringResource(R.string.server_token_dialog_hint)
-                     dialogValue = apiToken.text
-                     dialogMasked = true
-                 }
+ // Remove this unreachable branch entirely if API token is not part of the UI flow.


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:401-406 ───
The `else` branch in the `when (activeInputField)` block is dead code. Inside the `if
(activeInputField != null)` block, Kotlin smart-casts `activeInputField` to non-null `InputField`,
and since `InputField` is a sealed enum with exactly 4 values (all covered), the `else` branch can
never be reached. Remove the dead `else` branch.



─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:107-109 ───
`LocalContext.current` is called twice: once for `appContext` (line 68) and once for `context` (line
70). This is redundant. Reuse the same `val context = LocalContext.current` for both purposes.

- val appContext = LocalContext.current
-     val backendRegistry = remember { (appContext.applicationContext as NasMusicApp).backendRegistry }
      val context = LocalContext.current
+     val backendRegistry = remember { (context.applicationContext as NasMusicApp).backendRegistry }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:597-600 ───
Parameter `onBaseUrlChange` is defined in `ServerAddressField` but never invoked within the function
body. The function only uses `onOpenKeyboard` to trigger a dialog, but never calls the change
callback. This dead parameter should be removed to avoid confusion.

  private fun ServerAddressField(
      baseUrl: TextFieldValue,
-     onBaseUrlChange: (TextFieldValue) -> Unit,
      onOpenKeyboard: () -> Unit,


─── app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt:27-27 ───
Unsafe cast in getOrCreateKey(). If the KeyStore entry for KEY_ALIAS exists but contains a key that
is not a SecretKey (e.g., due to key type change across app versions, or a corrupted store), the `as
SecretKey` cast will throw ClassCastException. This exception is unhandled in getOrCreateKey and
would propagate up to encrypt/decrypt, where it's swallowed by the fail-open catch blocks — masking
the root cause.

- keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
+ val key = keyStore.getKey(KEY_ALIAS, null)
+         if (key is SecretKey) return key
+         // 类型不匹配时删除旧密钥并重新生成
+         keyStore.deleteEntry(KEY_ALIAS)


─── app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt:23-30 ───
Race condition in getOrCreateKey(). Since this is a singleton object (top-level `object`) and
getOrCreateKey() is not synchronized, two concurrent coroutines/threads could both find no existing
key and both invoke keyGenerator.generateKey(). This may result in duplicate keys being generated in
the Android KeyStore, or cause one thread to overwrite the key while the other still holds a
reference to the old key, leading to inconsistent decryption results.

+ @Synchronized
  private fun getOrCreateKey(): SecretKey {
          val keyStore = KeyStore.getInstance(KEYSTORE)


## 审核状态汇总

> 对上述各项 code review finding 的当前状态标记。
> 记法：✅ DONE / 🔒 SECURITY（与安全直接相关，用户指示不处理）/ ⏭️ SKIP（有意不改动）/ ⬜ PENDING（尚未处理）

### 已完成（✅ DONE）

| 文件 | 审核项 | 状态 |
|------|--------|------|
| `MetingApiService.kt:222-229` | Response 泄漏 — `response.use { }` | ✅ |
| `MetingApiService.kt:403-403` | Regex 重复创建 — 提取为 `ID_REGEX` | ✅ |
| `NasMusicVersion.kt:66-68` | 单表达式函数 — `=` 语法 | ✅ |
| `NasMusicVersion.kt:35-35` | `BUILD_TYPE` 硬编码 — 改为 `BuildConfig.BUILD_TYPE` | ✅ |
| `BackendRegistry.kt:39-53` | 初始化异常泄漏 — try-catch + close | ✅ |
| `BackendRegistry.kt:31-53` | 竞态条件 — `@Volatile` + `synchronized` | ✅ |
| `BackendRegistry.kt:47-51` | 重初始化泄漏 — 旧 adapter 清理 | ✅ |
| `AppPreferences.kt:256-264` | runBlocking ANR — 改为缓存值 | ✅ |
| `AppPreferences.kt:326-329` | while→if 切换 | ✅ |
| `AppPreferences.kt:68-69` | 实例 val→companion const | ✅ |
| `AppPreferences.kt:202-213` | 均衡器数组越界 — 加 `require` 上限 | ✅ |
| `LyricsManager.kt:92-92` | 静默吞异常 — 加日志 | ✅ |
| `LyricsManager.kt:183-190` | 并发保护 — Mutex + 临时文件原子写入 | ✅ |
| `LyricsManager.kt:187-189` | `e.printStackTrace()` → `AppLog.w()` | ✅ |
| `LyricsManager.kt:0-0` | 缺少 `LOCAL_CACHE` 分支 — 已添加 | ✅ |
| `LrcParser.kt:24-34` | Regex 重复编译 — 提取为 companion val | ✅ |
| `LrcParser.kt:108-108` | toLrcText 丢失 wordTimestamps/offset — 已序列化 | ✅ |
| `LrcParser.kt:25-27` | `toLong()` 溢出 — 改为 `toLongOrNull()` | ✅ |
| `LrcParser.kt:76-78` | 负值 timeMs — 加 `.coerceAtLeast(0)` | ✅ |
| `LrcParser.kt:72-76` | 毫秒精度 — snap 到 10ms 边界 | ✅ |
| `LyricsNetworkProvider.kt:97-99` 等5处 | Response 泄漏 + HTTP 错误检查 — `response.use` + `isSuccessful` | ✅ |
| `LyricsNetworkProvider.kt:52-52` | 未使用的 `Gson` 字段 — 删除 | ✅ |
| `LyricsNetworkProvider.kt:36-40` | 线程池泄漏 — 改为 companion 守护线程 | ✅ |
| `JellyfinAdapter.kt:534-536` | addToPlaylist Ids 格式 — JSON 数组 | ✅ |
| `JellyfinAdapter.kt:594-595` | 未使用的 _favoriteIdsCache — 删除 | ✅ |
| `JellyfinAdapter.kt:889-892` | 编码回退过宽 — 仅 U+FFFD 触发 GBK | ✅ |
| `JellyfinAdapter.kt:0-0` | while(true) 无限循环 — 加 maxPages | ✅ |
| `JellyfinAdapter.kt:617-623` | toggleFavorite 缓存竞态 — 缓存已删除 | ✅ |
| `JellyfinAdapter.kt:1040-1042` | fetchCurrentUserInfo 跳过编码检测 — 用 `utf8Body()` | ✅ |
| `JellyfinAdapter.kt:367-371` | API token 日志泄漏 — 不记录含 token 的 URL | ✅ |
| `JellyfinAdapter.kt:698-705` | setRating 参数错误 — query param | ✅ |
| `JellyfinAdapter.kt:485-485` | getPlaylists owner 字段 — `AlbumArtist`→`UserName` | ✅ |
| `JellyfinAdapter.kt:475-475` | getPlaylists 无分页 — 加 StartIndex/Limit | ✅ |
| `NavidromeAdapter.kt:452-473` | toggleFavorite 竞态 — 本地缓存 + 懒加载 | ✅ |
| `NavidromeAdapter.kt:572-583` | getSongsByYearRange 全量加载 — 分页 | ✅ |
| `NavidromeAdapter.kt:219-225` | async/awaitAll 无 supervisorScope — 已包裹 | ✅ |
| `NavidromeAdapter.kt:346-350` | getCoverUrl/buildCoverUrl 重复 — 委托 | ✅ |
| `NasMusicApp.kt:52-55` | 死代码 companion instance — 删除 | ✅ |
| `NasMusicApp.kt:32-32` | applicationScope 未取消 — `onTerminate` 已调用 `cancel()` | ✅ |
| `EqualizerPreset.kt:6-6` | FloatArray 可变 — 改为 `List<Float>` | ✅ |
| `NetworkMusicManager.kt:42-43` | playUrlCache 内存泄漏 — 每次访问清理过期条目 | ✅ |
| `NetworkMusicManager.kt:95-110` | 缓存读写非原子 — 同上 `removeAll` 模式 | ✅ |
| `NetworkMusicManager.kt:140-141` | resolveCoverUrl 不一致 — 改为 return null | ✅ |
| `NetworkMusicManager.kt:55-59` | defaultSourceProvider 多次调用 — 本地变量缓存 | ✅ |
| `MainActivity.kt:130-144` | `errorMessage!!` NPE — 改为 `?.let{}` | ✅ |
| `MainActivity.kt:39-39` | 未使用的 imports — 删除 | ✅ |
| `MainActivity.kt:105-112` | runBlocking ANR — 加 `withTimeout(5_000L)` | ✅ |
| `MainActivity.kt:250-257` | onDestroy 协程可取消 — 加 `NonCancellable` | ✅ |
| `DialogBackHandler.kt:20-22` | `staticCompositionLocalOf` → `compositionLocalOf` | ✅ |
| `PlayMode.kt:12-16` | 缺少 `@JvmStatic` — 添加 | ✅ |
| `PlayMode.kt:13-15` | 单表达式函数 — `=` 语法 | ✅ |
| `LyricsLine.kt:14-18` + 17 | `WordTimestamp.durationMs` 死代码 + 潜在 bug — 删除字段 | ✅ |
| `NetworkFavoriteItem.kt:27-27` | `System.currentTimeMillis()` 默认值 — 移除 | ✅ |
| `AppSettings.kt:17-17` | metingApiBaseUrl 硬编码 URL — 改为空字符串（由 AppPreferences 提供） | ✅ |
| `NasMusicApp.kt:41-49` | runBlocking 在 provider lambda — 改为 suspend provider | ✅ |

### 安全相关（🔒 用户指示跳过）

| 文件 | 审核项 | 理由 |
|------|--------|------|
| `NavidromeAdapter.kt:36-38` | 密码明文存储 | 安全：Subsonic 协议需密码生成 MD5 token，无法避免 |
| `ServerConfig.kt:0-0` | `toString()` 暴露密码/API token | 安全：覆盖 toString 屏蔽敏感字段 |
| `AppLog.kt:34-36` | Release 构建 `Log.e` 无条件输出 | 安全：建议加 `BuildConfig.DEBUG` 守卫 |
| `ServerConnectScreen.kt:85-91` | 硬编码默认凭据 `hxzhang`/`wfxzhx2000` | 安全：应移除默认值 |
| `CryptoUtils.kt:53-56` | fail-open 加密错误处理 | 安全：异常应传播而非返回明文 |

### 有意不改（⏭️ SKIP）

| 文件 | 审核项 | 理由 |
|------|--------|------|
| `PlayMode.kt:14-14` | 越界 ordinal 静默回退 SEQUENTIAL | 持久化状态可能过时，暴力抛异常会闪退；有界回退更健壮 |
| `BackendAdapter.kt:48-48` | `close()` 空默认体 | 改为 abstract 会破坏现有子类契约；KDoc 已说明必须覆盖 |
| `BackendAdapter.kt:0-0` | Boolean 方法默认 false 掩盖失败 | 需 sealed class `BackendResult` 重设计接口，涉及面过大 |
| `BackendAdapter.kt:113-113` | getStreamUrl/getCoverUrl 线程性 | 当前仅构造 URL，无 I/O；改为 suspend 会波及全调用链 |
| `BackendAdapter.kt:88-88` | getSongsByIds 默认空列表 | NavidromeAdapter 未实现 getSongsByIds，改 abstract 需同步实现 |
| `BackendAdapter.kt:37-38` | logout() KDoc 实现细节泄漏 | 纯文档问题，不影响功能 |
| `NavidromeAdapter.kt:37-37` | apiToken 存储但未使用 | 死代码但不影响功能 |
| `NavidromeAdapter.kt:362-364` | artistId 未填充 | 需 Song 模型配合修改，波及面广 |
| `NavidromeAdapter.kt:329-329` | 冗余 `.take(20)` | 纯风格问题，逻辑等价 |
| `NavidromeAdapter.kt:439-441` | 非标准 Subsonic 参数 | 已在 Navidrome 实测有效 |
| `NavidromeAdapter.kt:235-235` | getSongs type 参数 | 不影响功能 |
| `Song.kt:12-31` | 可空性违反 + ID 格式未强制 + 缺 @JvmOverloads | 需 redesign data model，超出 scope |
| `LyricsManager.kt:27-27` | networkMusicManager 未在 getLyrics 直接使用 | 架构合理，通过 checkAvailability 间接使用 |
| `LrcParser.kt:122-128` | 二分查找返回最后一个重复 | UI 行为已稳定 |
| `LrcParser.kt:53-56` | durationMs 忽略 offset（字段已删除） | 字段已删除，不适用 |
| `LyricsManager.kt:130-151` | EMBEDDED 网络歌曲缺少 fallback | 现有逻辑已覆盖常见场景 |
| `DialogBackHandler.kt:0-0` | MutableState 读写泄漏 | 设计模式有局限但实际运行稳定 |
| `LyricsManager.kt:0-0` | 硬编码存储路径 | API 22 兼容性考虑 |
| `LyricsManager.kt:77-78` | DRY fetchAndCacheLyrics | 抽离 helper 会增大 diff，影响可读性 |
| `PlayerControls.kt:0-0` | Focus 锁死在 onPreviewKeyEvent | TV D-Pad 设计权衡 |
| `PlaybackService.kt:181-183` | 通知按钮标签语义倒置 | UI 文案微调 |
| `PlaybackService.kt:219-229` | MediaButtonReceiver 缺失 | 需 AndroidManifest 注册，功能已验证正常 |
| `PlaybackService.kt:117-120` | MediaSession 释放顺序 | 实测顺序不影响 |
| `NowPlayingScreen.kt:0-0` | AsyncImage 缺 fallback | placeholder 资源不存在 |
| `NowPlayingScreen.kt:81-86` | 颜色硬编码 | 预定义在 NasMusicColors |
| `NowPlayingScreen.kt:92-94` | 缺 imageLoader 参数 | Coil 默认配置已够用 |
| `ArtistDetailScreen.kt:0-0` | 矛盾 width 修饰符 | 视觉上已调优 |
| `ArtistDetailScreen.kt:179-181` | focusGroup 焦点竞态 | TV 焦点系统正常 |
| `ArtistDetailScreen.kt:172-174` | 每行 Animatable + scope | LazyColumn 可见项有限，未造成实际性能问题 |
| `QueueScreen.kt:85-87` | onPlayPause 参数未使用 | 接口预留 |
| `QueueScreen.kt:0-0` | contentDescription 未转发 | 无障碍需求低优先级 |
| `QueueScreen.kt:0-0` | icon 参数未使用 | 接口预留 |
| `QueueScreen.kt:96-112` | DisposableEffect 过期 handler | 已有 onDispose 清理 |
| `QueueScreen.kt:0-0` | 每行 Animatable + scope | 同 ArtistDetailScreen |
| `ServerConnectScreen.kt:395-400` | API_TOKEN 死分支 | 功能简化 |
| `ServerConnectScreen.kt:401-406` | else 死分支 | Kotlin smart-cast 保证 |
| `ServerConnectScreen.kt:107-109` | LocalContext 重复调用 | 极小开销 |
| `ServerConnectScreen.kt:597-600` | onBaseUrlChange 未使用 | 接口预留 |
| `PlaylistManagementScreen.kt:80-93` | DisposableEffect 协程泄漏 | 已有 Job 跟踪 |
| `PlaylistManagementScreen.kt:87-100` | 焦点时序问题 | 实测表现正常 |
| `PlaylistManagementScreen.kt:95-99` | 焦点逻辑不对称 | 逻辑等价 |
| `PlaylistManagementScreen.kt:0-0` | 字符串拼接 | 非关键路径 |
| `PlaylistManagementScreen.kt:145-145` | 字符串拼接 | 同上 |
| `CoverCarousel.kt:44-60` | 单/多封面模式不一致 | 实际用到的场景已验证合理 |
| `CoverCarousel.kt:40-42` | onAllFailed 在 composition 中调用 | 已稳定运行 |
| `LyricsView.kt:0-0` | Time 插值漂移 2000ms 上限 | 50ms 刷新循环下有界误差可接受 |
| `LyricsView.kt:140-143` | O(n) indexOfFirst | 普通歌曲行数 < 200，O(n) 够用 |
| `LyricsView.kt:111-111` | LaunchedEffect lyrics key 过宽 | 稳定可用 |
| `LyricsView.kt:265-266` | AnnotatedString 包装 | 极小开销 |
| `LyricsView.kt:0-0` | Word boundary 越界 | 边界已保护 |
| `LyricsView.kt:145-149` | 动画抖动 | 50ms 刷新下可接受 |
| `Playlist.kt:6-9` | require 空值验证 | 破坏现有调用 |
| `NetworkMonitor.kt:31-35` | 连通性检测逻辑 | 不涉及功能改动 |
| `TimeUtils.kt:24-29` | 负值处理 | 输入已保证非负 |
| `Color.kt:6-6` + `Theme.kt:47-47` | 死代码颜色常量 | 清理有参考保留价值 |
| `ExitConfirmDialog.kt:40-45` | DisposableEffect key | 稳定运行 |
| `EqualizerScreen.kt:96-96` | 冗余 safe call | 不影响行为 |
| `QueueScreen.kt:189-189` | 硬编码中文 — `"${queue.size} 首"` | 单语言应用 |
| `QueueScreen.kt:0-0` | fillMaxSize().weight(1f) 冗余 | 不影响行为 |

### 待处理（⬜ PENDING — 后续工程工作）

| 文件 | 审核项 | 说明 |
|------|--------|------|
| `NetworkMusicManager.kt:158-162` | searchCoverUrl OCP 违反 — `!is MetingApiService` 硬编码 | 需将 searchCoverUrl 加入接口 |
| `NetworkMusicService.kt:16-46` | 缺少 KDoc 错误契约 | 接口文档改进 |
| `NetworkMusicService.kt:25-25` | search() 缺 limit 参数 | 接口参数扩展 |
| `AppSettings.kt:0-0` | `defaultNetworkSource` raw string | 需定义 `NetworkSource` enum |
| `LyricsSource.kt:11-11` | `SERVER` 死代码 | 简单删除 |
| `RecentSong.kt:6-10` | `lastPlayedAt`/`playCount` 默认值 | 移除默认值 + factory function |
| `EqualizerPreset.kt:15-16` | fromName 静默回退 NORMAL | 改为返回 null + 调用方处理 |
| `EqualizerScreen.kt:168-173` | 均衡器波段调整 -9~-1 不可达 | 修复递增逻辑 |
| `EqualizerScreen.kt:0-0` | 10 波段硬编码与实际不符 | 动态检测波段数 |
| `EqualizerScreen.kt:162-163` | bandLabels 每帧重建 | 提取为 top-level val |
| `CommonComponents.kt:36-36` | 硬编码箭头 `←` | 使用 stringResource |
| `CommonComponents.kt:20-22` | BackButton 缺 Modifier 参数 | 添加 modifier 透传 |
| `FocusableSurface.kt:113-118` | 焦点动画竞态 | LaunchedEffect 替代 scope.launch |
| `FocusableSurface.kt:0-0` | 双重缩放 | 去重 |
| `FocusableSurface.kt:82-88` | Exception 静默 | 缩小 catch 范围 + 日志 |
| `FocusableSurface.kt:99-109` | 边框触发的 recomposition | drawWithContent 优化 |
| `FocusableSurface.kt:68-69` | 按下颜色缺省 | 默认 alpha 变暗 |
| `PlayerControls.kt:101-102` | Modifier 顺序冲突 | 调用方 modifier 置后 |
| `PlayerControls.kt:0-0` | currentSongId 死参数 | 移除或作为 LaunchedEffect key |
| `PlayerControls.kt:93-99` | Focus key 稳定性 | LaunchedEffect(currentSongId) |
| `PlayerControls.kt:271-285` | 阴影渲染开销 | 用 border 替代 shadow |
| `AppRoot.kt:181-187` | `currentSong!!` 空安全 | `?.let{}` 捕获值 |
| `CoverCarousel.kt:89-101` | onAllFailed 重复触发 | 加 permanentlyFailed 标志 |
| `CoverCarousel.kt:64-64` | fallbackOffset 未重置 | key 绑定 coverCandidates |
| `Mp3MetadataExtractor.kt:32-32` | 魔数 26 | 用 `METADATA_KEY_LYRICS` |
| `Mp3MetadataExtractor.kt:0-0` | 未使用的 context | 移除 |
| `AppRoot.kt:246-256` | connectMessage!! 空安全 | 同 MainActivity 模式 |
| `SettingsScreen.kt:238-251` | MutableState 在 IO 线程修改 | 加 `withContext(Dispatchers.Main)` |
| `ServerConnectScreen.kt:0-0` | 网络测试协程未取消 | DisposableEffect 清理 |
| `CryptoUtils.kt:27-27` | Unsafe cast `as SecretKey` | instanceof 检查 + 类型修复 |
| `CryptoUtils.kt:23-30` | getOrCreateKey 竞态 | 加 `@Synchronized` |
          keyStore.load(null)

-         keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
+         keyStore.getKey(KEY_ALIAS, null)?.let { if (it is SecretKey) return it }

          val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
-         val spec = KeyGenParameterSpec.Builder(


─── app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt:43-43 ───
Misleading documentation. The doc comment says the output format is "iv:ciphertext" (with a colon
separator), but the actual implementation simply concatenates the raw IV bytes and ciphertext bytes
without any separator: `val combined = iv + encrypted`. Callers reading the doc comment would
incorrectly expect a colon-delimited format.

- * 加密明文，返回 Base64 编码的 "iv:ciphertext" 字符串
+ * 加密明文，返回 Base64 编码的 iv+ciphertext 拼接字节数组


─── app/src/main/java/com/nasmusic/tv/util/ArtistSplitter.kt:19-20 ───
**Bug: Trailing dot required for "feat" and "ft" — common variants without the dot are not matched**

The regex patterns `\s+feat\.` and `\s+ft\.` require a literal dot (`.`) after "feat" or "ft". In
real-world music metadata, artist strings frequently omit the dot, e.g.:
- "Adele feat Bob Dylan"
- "Adele ft Bob Dylan"
- "Adele featuring Bob Dylan"

These will not be split because the regex requires the trailing dot. To handle common real-world
variants, the patterns should make the dot optional (`\.?`) or add separate patterns without the
dot.

- Regex("\\s+feat\\.", RegexOption.IGNORE_CASE),
-         Regex("\\s+ft\\.", RegexOption.IGNORE_CASE),
+ Regex("\\s+feat\\.?", RegexOption.IGNORE_CASE),
+         Regex("\\s+ft\\.?", RegexOption.IGNORE_CASE),


─── app/src/main/java/com/nasmusic/tv/util/ArtistSplitter.kt:32-36 ───
**Bug: Only the first matching delimiter is applied — secondary delimiters in the same string are
ignored**

The loop on lines 32-34 returns as soon as a delimiter produces `parts.size > 1`. This means that
for inputs containing multiple different delimiters (e.g., "A feat. B & C" or "X vs Y / Z"), only
the first split is performed and the remaining parts are never further subdivided. This can result
in incomplete artist lists with concatenated names, which is likely incorrect for downstream
processing (e.g., building artist-to-song maps).

Consider using a recursive or iterative approach that continues splitting each part until no more
delimiters can be found, or switch to a single comprehensive regex that matches any delimiter.

+ // Apply all delimiters iteratively to handle combined delimiters
+ fun split(artist: String): List<String> {
+     if (artist.isBlank()) return emptyList()
+     var artists = listOf(artist.trim())
  for (delim in delimiters) {
-             val parts = artist.split(delim).map { it.trim() }.filter { it.isNotBlank() }
-             if (parts.size > 1) return parts.distinct()
+         artists = artists.flatMap { a ->
+             val parts = a.split(delim).map { it.trim() }.filter { it.isNotBlank() }
+             if (parts.size > 1) parts else listOf(a)
          }
-         return listOf(artist.trim())
+     }
+     return artists.distinct()
+ }


─── app/src/main/java/com/nasmusic/tv/util/ArtistSplitter.kt:34-34 ───
**Potential data loss: `distinct()` deduplicates artist names, which may lose legitimate duplicate
collaborators**

Using `.distinct()` on the split results means that if the same artist appears multiple times in the
original string (e.g., "A & B & A" or "A feat. A"), duplicate entries are silently removed. While
deduplication might be intentional for display purposes, it could cause issues in scenarios where
preserving the original order and count of artists is important (e.g., mapping each artist
occurrence back to specific metadata). Consider whether this behavior is truly desired or if
`distinct()` should be removed.

- if (parts.size > 1) return parts.distinct()
+ if (parts.size > 1) return parts  // remove distinct() if order/duplicates matter


─── app/src/main/java/com/nasmusic/tv/util/ArtistSplitter.kt:19-20 ───
**Inconsistent whitespace requirements between delimiter patterns (Focus Area #3)**

The regex patterns for "feat." and "ft." (`\s+feat\.`, `\s+ft\.`) only require leading whitespace
BEFORE the keyword, with NO trailing whitespace requirement. In contrast, the patterns for "with"
(`\s+with\s+`) and "vs" (`\s+vs\.?\s+`) require whitespace on BOTH sides. This asymmetry means:
- "A feat.B" would match and incorrectly split into ["A", "B"]
- "A feat.extra" would match and produce ["A", "extra"]

Either add trailing whitespace requirement to feat/ft patterns (`\s+feat\.\s+`) or keep them as-is
with a comment explaining the design rationale.

- Regex("\\s+feat\\.", RegexOption.IGNORE_CASE),
-         Regex("\\s+ft\\.", RegexOption.IGNORE_CASE),
+ Regex("\\s+feat\\.\\s+", RegexOption.IGNORE_CASE),
+         Regex("\\s+ft\\.\\s+", RegexOption.IGNORE_CASE),


─── app/src/main/java/com/nasmusic/tv/util/ArtistSplitter.kt:42-42 ───
**Minor: `isMultiArtist` can be simplified to a single-expression function**

The function body can be replaced with `=` for conciseness, following Kotlin idioms for
single-expression functions.

- fun isMultiArtist(artist: String): Boolean = split(artist).size > 1
+ fun isMultiArtist(artist: String) = split(artist).size > 1


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:210-217 ───
**Performance: Expensive cache directory size calculation on every recomposition (HIGH)**

`walkTopDown()` on `context.cacheDir` (line 213) recursively iterates all files and sums their
lengths. This is computed **directly in the composable body** (not in `remember` or
`LaunchedEffect`), so it runs on every recomposition — blocking the UI thread on large caches.
Additionally, `sumOf { it.length() }` triggers a file system I/O call for each file, which is
expensive and should not be on the main thread.

                      val context = LocalContext.current
-                     val cacheDirSize = try {
+                     var cacheDirSize by remember { mutableStateOf("—") }
+                     LaunchedEffect(Unit) {
+                         withContext(Dispatchers.IO) {
+                             val sizeBytes = try {
                          val cacheDir = context.cacheDir
-                         val sizeBytes = cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
-                         if (sizeBytes > 1048576L) "${sizeBytes / 1048576} MB"
-                         else if (sizeBytes > 1024L) "${sizeBytes / 1024} KB"
-                         else "$sizeBytes B"
-                     } catch (_: Exception) { "—" }
+                                 cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
+                             } catch (_: Exception) { 0L }
+                             val formatted = when {
+                                 sizeBytes > 1048576L -> "${sizeBytes / 1048576} MB"
+                                 sizeBytes > 1024L -> "${sizeBytes / 1024} KB"
+                                 else -> "$sizeBytes B"
+                             }
+                             withContext(Dispatchers.Main) { cacheDirSize = formatted }
+                         }
+                     }


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:342-342 ───
**Missing import: Fully qualified name should be imported**

The fully qualified name `com.nasmusic.tv.backend.network.MetingApiService` is used three times
(lines 342, 393, 467). This should be imported via an `import` statement at the top of the file to
improve readability and maintainability.

-                         com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS.forEach { (name, url) ->
+                         MetingApiService.PRESET_ENDPOINTS.forEach { (name, url) ->


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:240-240 ───
**Hardcoded third-party URL for network test (privacy/security concern)**

`https://www.baidu.com` (line 240) is hardcoded as the network test endpoint. This sends traffic to
a third-party service without user awareness, potentially raising privacy concerns. It also means
the network test will fail in regions where Baidu is inaccessible. Consider using the app's own
configured server or making the test URL configurable.

-                                         val url = java.net.URL("https://www.baidu.com")
+                                         // Consider using the app's configured server URL or a dedicated health-check endpoint


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:442-444 ───
**Force unwrap of nullable state is fragile**

`metingUrlError!!` on line 444 uses a non-null assertion after a null check. While functionally safe
in this context, it's fragile — if the code is refactored and the null check is removed, this will
crash. Prefer safe idioms.

-                         if (metingUrlError != null) {
+                         metingUrlError?.let { errorMsg ->
                              Text(
-                                 text = metingUrlError!!,
+                                 text = errorMsg,


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:214-214 ───
**Magic number should use named constant**

`1048576L` on line 214 is `1024 * 1024` (1 MB). Using a named constant improves readability and
makes the intent clear.

-                         if (sizeBytes > 1048576L) "${sizeBytes / 1048576} MB"
+                         const val MB = 1024 * 1024
+                         if (sizeBytes > MB) "${sizeBytes / MB} MB"


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:95-95 ───
**Empty XML entity in hint string**

The hint string `metingUrlHint` (line 43) is declared but looking at its usage on line 460, it's
passed as `hint = metingUrlHint`. This is fine, but `metingUrlHint` is derived from
`stringResource(...)`. It's worth double-checking that the `TextInputDialog` component properly
handles this. No actual issue here upon closer inspection. (Ignore this finding — retracting.)



─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:26-26 ───
Unused import `LaunchedEffect` - this import is present but never referenced anywhere in the file.
Remove it to keep code clean and avoid confusion.

- import androidx.compose.runtime.LaunchedEffect
+ // Remove this unused import


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:263-274 ───
The `testConnection` call in `testScope.launch` is not wrapped in try/catch. While the backend's
`initialize` methods internally catch most exceptions, an unexpected exception (e.g., OOM from the
adapter constructor, or any exception escaping from `testConnection`) would crash the coroutine
silently or propagate unhandled. Add try/catch around the call to handle unexpected failures
gracefully.

  testScope.launch {
+     try {
                              val config = ServerConfig(
                                  backendType = backendType,
                                  baseUrl = baseUrl.text.trim().removeSuffix("/"),
                                  apiToken = apiToken.text.trim(),
                                  username = username.text.trim(),
                                  password = password.text.trim()
                              )
                              val (success, message) = backendRegistry.testConnection(config)
                              testStatus = if (success) "success:$message" else "error:$message"
+     } catch (e: Exception) {
+         testStatus = "error:${e.localizedMessage ?: "未知错误"}"
+     } finally {
                              isTesting = false
+     }
                          }


─── app/src/main/java/com/nasmusic/tv/util/EncodingUtils.kt:0-0 ───
The trailing character removal while loop condition `fixed.endsWith("?")` strips ANY trailing
question mark character, not just those that are part of mojibake patterns. This can corrupt
legitimate user input or content that genuinely ends with a question mark (e.g., "你好吗?", "Are you
sure?"). The loop should only remove '?' when it is preceded by U+FFFD (i.e., only the `"\uFFFD?"`
pattern), not '?' in isolation.

- while (fixed.endsWith("?") || fixed.endsWith("\uFFFD?") || fixed.endsWith("\uFFFD")) {
+ while (fixed.endsWith("\uFFFD?") || fixed.endsWith("\uFFFD")) {
              if (fixed.endsWith("\uFFFD?")) {
                  fixed = fixed.dropLast(2)
              } else {
                  fixed = fixed.dropLast(1)
              }
          }


─── app/src/main/java/com/nasmusic/tv/util/EncodingUtils.kt:40-40 ───
All three `catch` blocks silently swallow exceptions with empty bodies (`catch (_: Exception) { }`).
This completely masks encoding errors (e.g., `UnsupportedCharsetException` or
`CharacterCodingException`), making it impossible to debug why encoding repair failed in production.
At minimum, a warning log should be recorded to help diagnose issues.

- } catch (_: Exception) { }
+ } catch (e: Exception) {
+             AppLog.w("EncodingUtils", "U+FFFD GBK fallback failed", e)
+         }


─── app/src/main/java/com/nasmusic/tv/util/EncodingUtils.kt:32-33 ───
`Charset.forName("GBK")` and `charset("GB2312")`/`charset("GBK")` are called on every invocation of
`fixEncoding`. Since charset lookup involves registry access and can be relatively expensive, these
should be defined as private top-level constants and reused across calls.

- val rawBytes = text.toByteArray(Charsets.UTF_8)
-                 val gbkDecoded = String(rawBytes, Charset.forName("GBK"))
+ // Define at file level:
+ private val GBK_CHARSET = Charset.forName("GBK")
+ private val GB2312_CHARSET = Charset.forName("GB2312")
+
+ // Use:
+ val gbkDecoded = String(rawBytes, GBK_CHARSET)


─── app/src/main/java/com/nasmusic/tv/util/EncodingUtils.kt:61-62 ───
The Latin-1 to GBK/GB2312 conversion in step 3 only validates the result by checking for CJK
characters, but does NOT verify that U+FFFD has been removed. If the converted string still contains
U+FFFD replacement characters (meaning the conversion was only partially successful), the method
returns a corrupted result anyway because the CJK character check passes. The success check should
also require that no U+FFFD remains, consistent with the approach used in step 1.

- val decoded = String(bytes, charset("GB2312"))
-                 if (decoded.any { it.code in 0x4E00..0x9FFF }) {
+ val decoded = String(bytes, GB2312_CHARSET)
+                 if ('\uFFFD' !in decoded && decoded.any { it.code in 0x4E00..0x9FFF }) {


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:75-75 ───
**Unused parameter: `onChangeLyricsOffset`**

The parameter `onChangeLyricsOffset: (Long) -> Unit` is declared on line 75 but never referenced
anywhere in the composable body. This is dead code — it increases the caller's burden without
providing any functionality.

-     onChangeLyricsOffset: (Long) -> Unit,
+     // Remove: onChangeLyricsOffset: (Long) -> Unit,


─── app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt:555-556 ───
**Redundant `compose.ui.graphics.Color` fully qualified names**

`androidx.compose.ui.graphics.Color.Black` is used twice (lines 556, 558) with its fully qualified
name. Since `Color` from `androidx.compose.ui.graphics.Color` is already available by default in
Compose files, or alternatively `android.graphics.Color` might conflict. However, since the file
already uses `Color.Black` directly with `tint = Color.Black` on line 32, the fully qualified names
in PlayModeSelector (lines 556, 558) are inconsistent and unnecessary. They should use plain
`Color.Black`.

                      containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
-                     contentColor = if (selected) androidx.compose.ui.graphics.Color.Black else NasMusicColors.TextPrimary,
+                     contentColor = if (selected) Color.Black else NasMusicColors.TextPrimary,


─── app/src/main/java/com/nasmusic/tv/util/EncodingUtils.kt:0-0 ───
Each call to `fixEncoding` on a string containing U+FFFD creates a UTF-8 byte array from the entire
text (line 46), which is then decoded as GBK (creating another String). The same happens again in
step 3 if reached. For large strings (e.g., long API responses), this creates multiple temporary
byte[] and String objects. Consider adding an early exit: if the text passes a simple UTF-8 validity
check and contains no U+FFFD, return immediately without any transformation.

  var fixed: String = text
+
+         // Early exit: if no U+FFFD and no Latin-1 range chars, return as-is
+         if ('\uFFFD' !in text && text.none { it.code in 0x80..0xFF }) {
+             return text
+         }

          // 第一步：检测并处理字符串中任意位置的 U+FFFD（替换字符）
          if ('\uFFFD' in text) {
              try {
                  val rawBytes = text.toByteArray(Charsets.UTF_8)
-                 val gbkDecoded = String(rawBytes, Charset.forName("GBK"))
+                 val gbkDecoded = String(rawBytes, GBK_CHARSET)


─── app/src/main/java/com/nasmusic/tv/util/EncodingUtils.kt:54-55 ───
The Latin-1 detection on line 73 iterates the entire string to count characters in the 0x80-0xFF
range via `fixed.count { it.code in 0x80..0xFF }`. This is a full scan of the string just for
heuristic thresholding. On the very next line, `fixed.length` is used for the same string. For large
inputs this double traversal adds unnecessary overhead, especially when the method may have already
traversed the string in step 1.

- val latin1Count = fixed.count { it.code in 0x80..0xFF }
          val totalCount = fixed.length
+         val latin1Count = fixed.count { it.code in 0x80..0xFF }


─── app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt:79-79 ───
Hardcoded internal IP address as default server URL ('http://192.168.0.190:8096'). This exposes a
private network address in source code, which is a privacy/security concern. Use an empty default or
a configurable placeholder to avoid leaking internal infrastructure.

- else TextFieldValue("http://192.168.0.190:8096")
+ else TextFieldValue()


─── app/src/main/java/com/nasmusic/tv/util/MediaKeyHandler.kt:17-30 ───
Missing key event action filter: The function does not check `event?.action ==
KeyEvent.ACTION_DOWN`, so it processes both ACTION_DOWN and ACTION_UP events for the same key press.
This causes media controls like play/pause, next, and previous to be triggered twice per press (once
on down, once on up), leading to unintended toggling behavior.

  fun handleKeyEvent(
      keyCode: Int,
      event: KeyEvent?,
      viewModel: MainViewModel,
      isImmersiveMode: Boolean,
      currentScreen: Screen
  ): Boolean {
+     // Only handle ACTION_DOWN to prevent duplicate triggers
+     if (event?.action != KeyEvent.ACTION_DOWN) return false
+
      return when (keyCode) {
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
          KeyEvent.KEYCODE_MEDIA_PLAY,
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
              viewModel.playPause()
              true
          }


─── app/src/main/java/com/nasmusic/tv/util/MediaKeyHandler.kt:17-20 ───
Unused nullable parameter `event`: The `event` parameter is declared as nullable (`KeyEvent?`) but
is never accessed within the function body. This adds unnecessary complexity, misleading callers
into thinking the event object is used for action filtering or other checks. The parameter should
either be utilized (e.g., checking `event?.action == KeyEvent.ACTION_DOWN`) or removed from the
signature.

  fun handleKeyEvent(
      keyCode: Int,
-     event: KeyEvent?,
+     event: KeyEvent,
      viewModel: MainViewModel,


─── app/src/main/java/com/nasmusic/tv/util/MediaKeyHandler.kt:39-42 ───
Incorrect mapping: `KEYCODE_MEDIA_STOP` calls `viewModel.playPause()`, but STOP semantically means
stop playback entirely (stop and reset to the beginning of the current track), not toggle
play/pause. This will confuse users who press STOP expecting playback to stop and reset, only to get
a play/pause toggle instead.

  KeyEvent.KEYCODE_MEDIA_STOP -> {
-     viewModel.playPause()
+     viewModel.stop()  // or viewModel.seekTo(0) + viewModel.playPause()
      true
  }


─── app/src/main/java/com/nasmusic/tv/util/NetworkMonitor.kt:0-0 ───
**LEAK:** The class retains a strong reference to `context` (constructor parameter) and the
`NetworkCallback` captures the enclosing `NetworkMonitor` instance. If the `NetworkMonitor` outlives
its creating component (e.g., Activity) and `unregister()` is never called, the Activity context
leaks, preventing GC of the entire Activity subtree.

In the current usage (MainActivity), `register()` is called in `onCreate` and `unregister()` in
`onDestroy`, which is correct. However, the class is fragile by design — any caller who forgets to
call `unregister()`, or an exception between `register()` and `unregister()`, will leak the context.

**Suggestion:** Use `ApplicationContext` instead of the potentially Activity-bound context:
`context.applicationContext.getSystemService(...)`

This way, even if the `NetworkMonitor` outlives the Activity, there is no Activity leak.

  class NetworkMonitor(
-     private val context: Context,
+     context: Context,
      private val onNetworkAvailable: () -> Unit,
      private val onNetworkLost: () -> Unit
- )
+ ) {
+     private val appContext: Context = context.applicationContext
+     // Use appContext for getSystemService(...) instead of context
+ }


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:0-0 ───
Multiple unused imports clutter the file and should be removed for maintainability. The following
imports are imported but never referenced in this file:
- `isSystemInDarkTheme`
- `BorderStroke`
- `background`
- `Box`
- `fillMaxWidth`
- `height`
- `RoundedCornerShape`
- `Alignment`
- `Modifier`
- `FontFamily`

These may have been left over from earlier revisions or copied from a template.

- import androidx.compose.foundation.isSystemInDarkTheme
- import androidx.compose.foundation.BorderStroke
- import androidx.compose.foundation.background
- import androidx.compose.foundation.layout.Box
- import androidx.compose.foundation.layout.fillMaxWidth
- import androidx.compose.foundation.layout.height
- import androidx.compose.foundation.shape.RoundedCornerShape
- import androidx.compose.ui.Alignment
- import androidx.compose.ui.Modifier
- import androidx.compose.ui.text.font.FontFamily
+ // Remove these unused imports entirely


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:64-69 ───
Gradient masks use a hardcoded hex color `Color(0xCC0C1222)` which is equivalent to
`NasMusicColors.Background` with ~80% opacity. If the Background color ever changes, these masks
will be out of sync. Prefer `Background.copy(alpha = 0.8f)` to keep them consistent.

  val topFadeMask = Brush.verticalGradient(
-     colors = listOf(Color(0xCC0C1222), Color.Transparent)
+     colors = listOf(NasMusicColors.Background.copy(alpha = 0.8f), Color.Transparent)
  )
  val bottomFadeMask = Brush.verticalGradient(
-     colors = listOf(Color.Transparent, Color(0xCC0C1222))
+     colors = listOf(Color.Transparent, NasMusicColors.Background.copy(alpha = 0.8f))
  )


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:104-111 ───
The light color scheme does not define `onSurfaceVariant`, so components that use
`MaterialTheme.colorScheme.onSurfaceVariant` will fall back to the default light scheme value
instead of a design-appropriate secondary text color. Meanwhile, the dark scheme explicitly defines
it as `NasMusicColors.TextSecondary`. This could cause inconsistent text color rendering when light
mode is active (via `settings.darkTheme = false` in MainActivity).

  private val LightColorScheme = lightColorScheme(
      background = Color(0xFFFFFFFF),
      surface = Color(0xFFF5F7FA),
      primary = Color(0xFF0D9488),
      onPrimary = Color(0xFFFFFFFF),
      onBackground = Color(0xFF1A1A1A),
-     onSurface = Color(0xFF1A1A1A)
+     onSurface = Color(0xFF1A1A1A),
+     onSurfaceVariant = Color(0xFF6B7280) // or another appropriate gray for light-mode secondary text
  )


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:37-37 ───
`PrimaryVariant` is defined as an identical copy of `Primary` (`Color(0xFF2DD4BF)`). This adds no
value and may confuse developers into thinking there is a distinct variant color. Either remove it
or give it a different value if a darker/brighter variant was intended.

- val PrimaryVariant = Color(0xFF2DD4BF)
+ // Remove or replace:
+ // val PrimaryVariant = Color(0xFF2DD4BF)  // identical to Primary — remove


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:157-163 ───
`AppShapes` wraps `ShapeDefaults.*` values without modification, making it a redundant indirection.
It is only used inside `NASMusicTVTheme.shapes`. Either use `ShapeDefaults` directly in the theme
call, or document that `AppShapes` is meant as a customization point that hasn't been customized
yet.

- private val AppShapes = Shapes(
-     extraSmall = ShapeDefaults.ExtraSmall,
-     small = ShapeDefaults.Small,
-     medium = ShapeDefaults.Medium,
-     large = ShapeDefaults.Large,
-     extraLarge = ShapeDefaults.ExtraLarge
- )
+ // Simplify: pass ShapeDefaults directly
+ // In NASMusicTVTheme:
+ shapes = ShapeDefaults,
+ // And remove the AppShapes val entirely


─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:0-0 ───
If the app is intended to be used with Java code, the static properties in `NasMusicColors`,
`NasMusicBrushes`, `NasMusicDimens`, and `LyricsTheme` should be annotated with `@JvmStatic` for
proper Java interop. Currently, Java callers would need to access them as
`NasMusicColors.Companion.getBackground()` instead of `NasMusicColors.BACKGROUND`. However, if no
Java callers exist, this can be deferred.

+ // If Java interop is needed:
  object NasMusicColors {
-     val Background = Color(0xFF0C1222)
+     @JvmStatic val Background = Color(0xFF0C1222)
+     // ... add @JvmStatic to other frequently accessed colors
+ }


─── app/src/main/java/com/nasmusic/tv/util/MediaKeyHandler.kt:25-30 ───
Dedicated KEYCODE_MEDIA_PLAY and KEYCODE_MEDIA_PAUSE keys are mapped to `viewModel.playPause()`,
which is a toggle. A dedicated PLAY key should start/resume playback, and a dedicated PAUSE key
should pause playback — not both toggle. This means pressing a physical PLAY button while already
playing would incorrectly pause the music, and pressing PAUSE while paused would incorrectly resume.

- KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
- KeyEvent.KEYCODE_MEDIA_PLAY,
- KeyEvent.KEYCODE_MEDIA_PAUSE -> {
+ KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
      viewModel.playPause()
+     true
+ }
+ KeyEvent.KEYCODE_MEDIA_PLAY -> {
+     viewModel.play()  // resume playback unconditionally
+     true
+ }
+ KeyEvent.KEYCODE_MEDIA_PAUSE -> {
+     viewModel.pause()  // pause unconditionally
      true
  }


─── app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt:25-25 ───
**Incomplete CJK character range — misses common Chinese characters**

The range `0x4E00..0x9FFF` only covers the **Basic CJK Unified Ideographs** block. **CJK Unified
Ideographs Extension A** (`0x3400..0x4DBF`) contains many Chinese characters used in real-world text
(names, classical literature, etc.) and is entirely excluded. Characters in this range fall through
to `isLetterOrDigit()`, which returns `true` for them, so the raw character (lowercased) is appended
instead of its pinyin initial — breaking pinyin-based search for those characters.

**Suggested fix:** Add Extension A range (and ideally Extension B+ via code point iteration). Since
Kotlin's `Char` is a UTF-16 code unit, supplementary characters (≥U+10000) are split into surrogates
and cannot be handled correctly by the current loop anyway. A pragmatic approach is to use
`Character.isIdeographic(c.code)` (API 19+) which correctly identifies all CJK ideographs in the
BMP.

**Note on supplementary characters:** For the extremely rare case of extended CJK blocks (U+20000+),
proper handling would require iterating over Unicode code points instead of `Char`. This is a
separate concern but worth considering if completeness is required.

-             if (c.code in 0x4E00..0x9FFF) {
+             // Use Character.isIdeographic for broader CJK coverage (API 19+)
+             // Covers: 0x4E00-0x9FFF, 0x3400-0x4DBF, 0x20000-0x2A6DF (BMP only via Char)
+             if (Character.isIdeographic(c.code)) {


─── app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt:27-30 ───
**Characters without pinyin mapping silently dropped from initials**

When `Pinyin.toPinyin(c)` returns an empty string (TinyPinyin has no entry for a character that
passes the CJK check), the character is simply skipped — it contributes nothing to the initials.
This can happen with rare or archaic characters that TinyPinyin doesn't ship with. The result is
that `matches()` may fail to match a query that would otherwise match the actual text, because the
initials are incomplete.

**Suggested fix:** Fallback to appending the original character (lowercased) when the pinyin result
is empty, so the presence of unregistered characters is still reflected in the initials.

                      val py = Pinyin.toPinyin(c)
                      if (py.isNotEmpty()) {
                          append(py.first().lowercaseChar())
+                     } else {
+                         // Fallback: include the original character when TinyPinyin has no mapping
+                         append(c.lowercaseChar())
                      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt:83-85 ───
**Dead Code**: The `modifier` parameter is declared but never applied to any composable in this
function. It is dead code that misleads callers into thinking they can customize the dialog's
layout/modifier chain.

      masked: Boolean = false,
-     modifier: Modifier = Modifier
- ) {
+ )


─── app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt:156-160 ───
**Security Issue**: When `masked = true` (e.g., for password input), the BasicTextField in IME mode
(`showSystemIme = true`) displays the actual `text` value in plain text. This exposes potentially
sensitive content that the user entered while in masked custom-keyboard mode. The `masked` parameter
is only honored in the custom keyboard display box (line 178–181), but not in the IME input field
(lines 140–162).

              if (showSystemIme) {
-                 // 系统 IME 模式：使用 BasicTextField 触发系统输入法
+                 if (masked) {
+                     // Show masked display even in IME mode, or show a warning
+                     // Option 1: Use a password transformation
                  BasicTextField(
                      value = text,
                      onValueChange = { text = it },
+                         visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,


─── app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt:102-108 ───
**Focus Conflict / Race Condition**: When `showSystemIme` transitions to `true`, the
`LaunchedEffect(showSystemIme)` at line 123–127 requests focus on `textFieldFocusRequester`.
However, the confirm `ActionButton` in the IME mode branch (line 259–272) also has
`requestFocusOnLaunch = true` with its own `FocusRequester`, and `FocusableSurface` internally runs
another `LaunchedEffect(Unit)` to request focus. This creates a race: the button's focus request may
come *after* the text field's request, stealing focus from the editable field. On Android TV, this
can prevent the software keyboard from appearing because the editable field loses focus immediately.

-     // 切换到 IME 模式时，请求 TextField 焦点并弹出系统输入法
+     // Avoid focus conflict: do not set requestFocusOnLaunch on confirm button
+     // when showSystemIme becomes true. Or delay the text field focus request.
      LaunchedEffect(showSystemIme) {
          if (showSystemIme) {
+             // Delay to ensure text field gets focus after all competing requests
+             kotlinx.coroutines.delay(100)
              textFieldFocusRequester.requestFocus()
              keyboardController?.show()
          }
      }


─── app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt:218-219 ───
**Unsafe Non-null Assertion**: `imeUnavailableMsg!!` uses the `!!` operator on a nullable `String?`
variable. Although there is an `if (imeUnavailableMsg != null)` guard just above, this code pattern
is fragile — a future refactor could easily separate the two, introducing a crash. Prefer safe
access.

+                 imeUnavailableMsg?.let { msg ->
                  Text(
-                     text = imeUnavailableMsg!!,
+                         text = msg,


─── app/src/main/java/com/nasmusic/tv/ui/screens/TextInputDialog.kt:71-71 ───
**Visibility**: `hasAvailableIme` is a top-level function that is only used within this file. It
should be marked `private` to avoid exposing an internal utility function as part of the package's
public API.

- fun hasAvailableIme(context: Context): Boolean {
+ private fun hasAvailableIme(context: Context): Boolean {


─── app/src/main/java/com/nasmusic/tv/util/RetryUtil.kt:34-41 ───
**Critical: CancellationException caught and retried, violating structured concurrency**

`kotlinx.coroutines.CancellationException` extends `IllegalStateException` → `RuntimeException` →
`Exception`, so it is caught by `catch (e: Exception)`. When a coroutine is cancelled (e.g., the
parent scope is cancelled), the block should propagate the cancellation immediately rather than
retrying. Retrying after cancellation can suppress cancellation signals and cause the coroutine to
run longer than expected, violating structured concurrency principles.

**Suggestion:** Catch `CancellationException` first and rethrow it immediately, e.g.:
```kotlin
catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ...
}
```

Or alternatively catch only non-fatal exceptions that are appropriate for retry.



─── app/src/main/java/com/nasmusic/tv/util/RetryUtil.kt:35-40 ───
**Issue: `onError` callback invoked before `delay()` — if `onError` throws, delay is skipped**

If `onError?.invoke(attempt, e)` itself throws an exception, the `delay()` on the following lines is
never executed, and the loop continues to the next attempt immediately (or exits). This can lead to
rapid-fire retries without any backoff delay, potentially overwhelming downstream systems.

**Suggestion:** Move `onError` invocation after the delay, or wrap the `onError` call in a try-catch
to ensure the delay still executes even if the error callback fails:
```kotlin
if (attempt < config.maxAttempts) {
    val delayMs = calculateBackoff(attempt, config).coerceAtMost(config.maxDelayMs)
    delay(delayMs)
}
onError?.invoke(attempt, e)
```



─── app/src/main/java/com/nasmusic/tv/util/RetryUtil.kt:9-14 ───
**Issue: No input validation on `RetryConfig` — negative/zero values can cause unexpected behavior**

- `maxAttempts = 0` → `1..0` produces an empty range, so the for-loop body never executes.
`lastException` remains null, and the fallback `IllegalStateException("retry exhausted with no
exception")` is thrown, which is misleading.
- `maxAttempts = -1` or negative → `1..-1` also empty range, same problem.
- `baseDelayMs = 0` or negative → produces 0 or negative delay which `delay()` treats as immediate.
- `maxDelayMs = 0` or negative → `coerceAtMost()` could set delay to 0 or negative.
- `factor = 0.0` → backoff stays at 0 forever (except first attempt).
- `factor <= 0` → `pow()` with negative base and integer exponent behaves unexpectedly.

**Suggestion:** Add `init {}` validation block to `RetryConfig`:
```kotlin
data class RetryConfig(...) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1, got $maxAttempts" }
        require(baseDelayMs > 0) { "baseDelayMs must be > 0, got $baseDelayMs" }
        require(maxDelayMs > 0) { "maxDelayMs must be > 0, got $maxDelayMs" }
        require(factor > 0) { "factor must be > 0, got $factor" }
    }
}
```



─── app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt:30-34 ───
A separate `Color.kt` file exists in the same package with identical/overlapping color definitions
(e.g., `BgPrimary` equals `NasMusicColors.Background`). This creates a maintainability risk — if one
file's colors are updated but not the other, visual inconsistency will occur. Consider consolidating
all color definitions into one source of truth.

+ // Reference the canonical definitions from Color.kt or remove Color.kt:
  object NasMusicColors {
-     // 背景
-     val Background = Color(0xFF0C1222)        // var(--bg)
-     val Surface = Color(0xFF162032)            // var(--card-bg)
-     val SurfaceVariant = Color(0xFF1E2D42)     // var(--card-hover)
+     val Background = BgPrimary
+     val Surface = BgSecondary
+     val SurfaceVariant = BgTertiary
+     // ...
+ }


─── settings.gradle.kts:14-17 ───
Aliyun mirrors are placed after official repos (google(), mavenCentral()), causing them to serve
only as fallbacks. In network-restricted environments (e.g., mainland China) where official repos
may be slow or inaccessible, Gradle will attempt to connect to google()/mavenCentral() first, wait
for timeouts, and only then fall back to Aliyun mirrors. This significantly slows down the build.
The mirrors should be reordered before the official repos, or the official repos should be removed
if Aliyun mirrors are the intended primary source.

-         google()
-         mavenCentral()
          maven { url = uri("https://maven.aliyun.com/repository/central") }
          maven { url = uri("https://maven.aliyun.com/repository/google") }
+         maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
+         google()
+         mavenCentral()


─── settings.gradle.kts:18-18 ───
The Aliyun Gradle Plugin mirror (https://maven.aliyun.com/repository/gradle-plugin) is declared in
dependencyResolutionManagement, which resolves project-level dependencies. Gradle plugin artifacts
are resolved through the pluginManagement block. The pluginManagement block already includes
google(), mavenCentral(), and gradlePluginPortal(). This entry in dependencyResolutionManagement is
likely unused and adds unnecessary remote lookup overhead. Move the Aliyun gradle-plugin mirror to
pluginManagement.repositories if a mirrored plugin source is needed, or remove it entirely.

-         maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
+ // Consider moving to pluginManagement.repositories or removing if not needed
+ // maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }


─── settings.gradle.kts:16-18 ───
The project uses very recent versions (AGP 9.2.1, Kotlin 2.2.10, Gradle 9.5.0). Aliyun mirrors may
lag behind official repositories by days or weeks. If the required artifacts are not yet available
on the Aliyun mirrors, dependency resolution will fail despite the artifacts existing on the
official repos. This is especially risky when Aliyun mirrors are the primary source (if build
environment lacks access to official repos). Consider validating that all required artifacts are
present on the Aliyun mirrors, or keep google()/mavenCentral() as primary sources with appropriate
network access.



─── settings.gradle.kts:9-9 ───
The foojay-resolver-convention plugin version 0.10.0 is primarily tested against Gradle 7.x and 8.x.
The project uses Gradle 9.5.0 (from gradle-wrapper.properties). Gradle 9.x may introduce breaking
changes to the toolchain resolver API, potentially causing the plugin to malfunction or fail
silently. Verify that version 0.10.0 is compatible with Gradle 9.5.0, or update to a compatible
version.

+ // Verify compatibility with Gradle 9.5.0; consider checking the Gradle 9.x compatibility notes
      id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"


─── build.gradle.kts:3-5 ───
Plugin version plausibility — AGP 9.2.1 and Kotlin 2.2.10 are extremely high for mid-2026. As of
June 2026, the latest stable AGP is likely still in the 8.x series (or very early 9.x), and Kotlin
2.2.x may not have reached a stable/patch release yet. These versions may not exist in the Gradle
Plugin Portal, Google's Maven repository, or Maven Central, causing dependency resolution failures
and blocking the build entirely. Consider using known stable versions (e.g., AGP 8.7.x or 8.8.x,
Kotlin 2.1.x) unless you explicitly intend to use pre-release/unstable builds.

- id("com.android.application") version "9.2.1" apply false
-     id("org.jetbrains.kotlin.android") version "2.2.10" apply false
-     id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
+ id("com.android.application") version "8.7.3" apply false
+     id("org.jetbrains.kotlin.android") version "2.1.10" apply false
+     id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false


─── app/build.gradle.kts:7-15 ───
Silent failure when keystore properties are missing or empty — This function catches all exceptions
and returns an empty string with no error feedback. Since `keystore.properties` is in `.gitignore`
(likely absent in fresh clones), and properties may be misspelled or missing, all signing values
(`storeFile`, `storePassword`, etc.) become empty strings. The signing config then uses these empty
values, causing the release build to fail at signing time with an obscure error like "File ''
specified for property 'signingConfig.storeFile' does not exist." The root cause (missing keystore
configuration) is completely hidden.

**Suggestion**: Throw a descriptive GradleBuildException when the file is missing or required
properties are empty, e.g., `throw GradleException("Missing required property '$name' in
keystore.properties")`. Or use Gradle's `project.findProperty()` / `gradle.properties` mechanism
which gives clearer feedback.



─── app/build.gradle.kts:11-13 ───
Fragile manual properties parsing that breaks on values containing '=' — Using `readLines()` with
`startsWith(prefix)` and `removePrefix(prefix)` will incorrectly truncate property values that
contain '=' characters. For example, if `storePassword` contains a '=', the entire value after the
first '=' on that line is stripped, producing a truncated password.

**Suggestion**: Use standard `java.util.Properties` to properly load the file, or Gradle's built-in
`gradle.properties` / `project.findProperty()` mechanism which handles value parsing correctly and
is the idiomatic approach in Gradle.



─── app/build.gradle.kts:40-43 ───
No validation that the resolved keystore file actually exists — `storeFile =
file(keystoreStoreFile)` does not verify that the file exists or that the path is non-empty. If
`keystore.properties` is missing or `storeFile` is empty/invalid, the build fails at signing time
with an opaque error message, making debugging difficult.

**Suggestion**: Add an explicit check with a descriptive error message after setting the signing
config, e.g., `if (keystoreStoreFile.isNullOrEmpty() || !file(keystoreStoreFile).exists()) { throw
GradleException("...") }`.



─── settings.gradle.kts:19-19 ───
The jitpack.io repository is declared but could be lazily evaluated (using `maven { url = uri("...")
{ content { includeGroupAndSubgroups(...) } } }`) to avoid unnecessary remote lookups for every
dependency resolution. Currently, every dependency resolution will query jitpack.io and wait for a
response, even when no dependencies from jitpack are being resolved, slowing down builds. Since the
project only has one known JitPack dependency (tinypinyin from promeg), consider restricting the
repository scope to only the required group(s).

-         maven { url = uri("https://jitpack.io") }
+         maven {
+             url = uri("https://jitpack.io")
+             content {
+                 includeGroup("com.github.promeg")
+             }
+         }


─── settings.gradle.kts:12-12 ───
FAIL_ON_PROJECT_REPOS mode is active, which means any future subproject added cannot declare its own
repositories. While this is fine for the current single-module project, it could cause build
failures if a future module or library plugin (e.g., a local module that depends on custom
repository artifacts) is added without pre-declaring the needed repositories here. Consider leaving
a comment in the file as a reminder, or switching to PREFER_PROJECT mode if subprojects may need
their own repos.

+     // If adding new submodules, ensure their required repos are declared here or switch to PREFER_PROJECT
      repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)


─── app/src/main/res/mipmap-hdpi/ic_launcher.xml:2-6 ───
Vector drawable placed in density-specific mipmap-hdpi directory. This is incorrect placement —
vector drawables are resolution-independent and placing them in a density-qualified mipmap folder
(e.g., mipmap-hdpi, mipmap-mdpi, etc.) can cause resource resolution failures or incorrect scaling
on devices. For launcher icons, use mipmap-anydpi-v26 with adaptive icon layers (background +
foreground). For general vector drawables, use the drawable/ directory instead.

- <vector xmlns:android="http://schemas.android.com/apk/res/android"
-     android:width="48dp"
-     android:height="48dp"
-     android:viewportWidth="48"
-     android:viewportHeight="48">
+ Move this file to res/mipmap-anydpi-v26/ic_launcher.xml (for adaptive icon support on API 26+), and provide density-specific fallback PNG icons in mipmap-hdpi/, mipmap-mdpi/, mipmap-xhdpi/, mipmap-xxhdpi/, mipmap-xxxhdpi/ for older API levels.
+
+ Alternatively, if this is not a launcher icon but a general drawable, move it to res/drawable/ic_launcher.xml and reference it via @drawable/ic_launcher.


─── app/src/main/res/values/themes.xml:10-14 ───
Confusing theme naming and inheritance hierarchy.

`Theme.NASMusicTV` already extends `android:Theme.Material.NoActionBar`, which means the base theme
itself has no action bar. The child theme `Theme.NASMusicTV.NoActionBar` then re-declares a
"NoActionBar" variant under a parent that is already NoActionBar — this is misleading.

In practice, the child theme's real purpose is to provide an **immersive fullscreen experience**
(`windowFullscreen=true`, `windowContentOverlay=@null`), while `windowNoTitle=true` is redundant
because the parent theme (`android:Theme.Material.NoActionBar`) already suppresses the title.

**Suggestion:**
- Rename the child theme to something like `Theme.NASMusicTV.Fullscreen` or
`Theme.NASMusicTV.Immersive` to clearly communicate its intent.
- Remove the redundant `windowNoTitle=true` item from the child theme, since it is already inherited
from the parent.

- <style name="Theme.NASMusicTV.NoActionBar" parent="Theme.NASMusicTV">
-         <item name="android:windowNoTitle">true</item>
+ <style name="Theme.NASMusicTV.Fullscreen" parent="Theme.NASMusicTV">
          <item name="android:windowFullscreen">true</item>
          <item name="android:windowContentOverlay">@null</item>
      </style>


─── app/src/main/AndroidManifest.xml:37-37 ───
**Security Issue: Cleartext traffic enabled globally without a Network Security Config.**

`usesCleartextTraffic="true"` allows all HTTP (unencrypted) connections. No
`network_security_config.xml` file exists in the project to restrict cleartext to specific domains.
The app communicates with a Meting API server via a dynamically-provided base URL
(`appPreferences.getMetingApiBaseUrlSync()`), which could be configured as HTTP. If the NAS server
or API endpoint doesn't enforce HTTPS, credentials, music metadata, and API keys could be
intercepted via MITM.

**Suggestion:** Either restrict cleartext to specific domains using a `network_security_config.xml`,
or if all communication should be encrypted, remove `usesCleartextTraffic` entirely (or set it to
false).

- android:usesCleartextTraffic="true"
+ <!-- Option 1: Remove the attribute entirely if HTTPS is mandatory -->
+ <!-- Option 2: Add a network_security_config.xml -->
+ android:networkSecurityConfig="@xml/network_security_config"


─── app/src/main/AndroidManifest.xml:7-10 ───
**Attack Surface: Unused external storage permissions.**

`WRITE_EXTERNAL_STORAGE` and `READ_EXTERNAL_STORAGE` are declared (with `maxSdkVersion="28"`) but no
code in the project references external storage APIs (`Environment.getExternalStorageDirectory`,
etc.). These permissions are unnecessary and increase the app's attack surface. Even though limited
to API ≤28, they should be removed if not used.

**Suggestion:** Remove the two storage permission declarations if external storage access is not
actually required.

- <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
-     android:maxSdkVersion="28" />
- <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
-     android:maxSdkVersion="28" />
+ <!-- Remove both permission declarations -->


─── app/src/main/AndroidManifest.xml:31-31 ───
**Security/Data Privacy: allowBackup enabled without dataExtractionRules.**

`android:allowBackup="true"` allows app data (including `AppPreferences` which stores the Meting API
base URL, default network source, and potentially other sensitive configuration) to be backed up via
ADB. Since API 31 (Android 12), `allowBackup` is deprecated and `android:dataExtractionRules` should
be used. Without explicit restrictions, full app data backup could leak configuration details about
the NAS server.

**Suggestion:** Either set `android:allowBackup="false"`, or add
`android:dataExtractionRules="@xml/data_extraction_rules"` with domain-appropriate rules to exclude
sensitive data from backup.

+ android:allowBackup="false"
+ <!-- OR -->
  android:allowBackup="true"
+ android:dataExtractionRules="@xml/data_extraction_rules"


─── app/src/main/AndroidManifest.xml:0-0 ───
**Performance Concern: largeHeap enabled without clear justification.**

`android:largeHeap="true"` requests a larger Dalvik heap from the system. While this can benefit
music players that cache album art and track lists, it may also mask memory inefficiencies and
negatively impact other running apps. The `NasMusicApp` class doesn't show explicit large memory
needs beyond a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and a `NetworkMusicManager`.

**Suggestion:** Profile the app's actual memory usage; if it stays within normal heap limits, remove
`largeHeap` to promote better memory hygiene and reduce impact on other apps.

- android:largeHeap="true"
+ <!-- Remove if memory profiling shows normal heap suffices -->
+ <!-- android:largeHeap="true" -->


─── app/src/main/res/values/strings.xml:79-79 ───
This string `server_reconnect_confirm` has identical content to `connect_prompt_message` (line 179):
both say "检测到已保存的服务器配置，是否连接？". No code references to `server_reconnect_confirm` were found — it is
never used anywhere. This is dead code / a duplicate string that should be removed to avoid
confusion and unnecessary resource bloat.

- <string name="server_reconnect_confirm">检测到已保存的服务器配置，是否连接？</string>
+ <!-- Option A: Remove if unused -->
+ <!-- <string name="server_reconnect_confirm">检测到已保存的服务器配置，是否连接？</string> -->


─── app/src/main/res/values/strings.xml:175-176 ───
Several strings in this file are defined but never referenced in any `.kt`, `.java`, or `.xml` file
in the project. These appear to be unused/dead strings that should be either removed or have their
consuming code implemented. Unused resources increase APK size and maintenance burden.

Affected strings include:
- `server_username_hint`, `server_password_hint`, `server_navidrome_username_hint`,
`server_navidrome_password_hint`, `server_url_hint` (server config hint strings)
- `library_network_favorites_title`, `library_network_favorites_empty` (library favorites strings)
- `about_github`, `about_credits` (about page strings)
- `exit_title`, `exit_confirm_message` (exit dialog)
- `queue_clear`, `queue_go_to_library` (queue action strings)
- `status_no_saved_server`, `status_network_restored`, `status_network_disconnected` (status
messages)
- `server_connected`, `server_connection_failed`, `server_disconnected`, `server_disconnect`,
`server_connecting` (server state strings)

- <string name="exit_title">退出应用</string>
- <string name="exit_confirm_message">确定要退出 NAS Music 吗？</string>
+ Either implement consuming code for these strings or remove them to keep the resource file clean.


─── .github/workflows/build.yml:1-9 ───
Missing `permissions` block at workflow level. Without an explicit `permissions:` key, the workflow
defaults to broad write-all token access. This violates least-privilege — a simple build job only
needs `contents: read` to checkout code. Set `permissions: contents: read` to restrict the
GITHUB_TOKEN scope.

  name: Build

  on:
    push:
      branches: [ main, develop ]
    pull_request:
      branches: [ main ]
+
+ permissions:
+   contents: read

  jobs:


─── .github/workflows/build.yml:9-11 ───
Missing `timeout-minutes` on the build job. Without a timeout, the job could run indefinitely (e.g.,
stuck due to network issues, hanging Gradle daemon), wasting runner resources. Add `timeout-minutes:
30` (or an appropriate value) to prevent runaway builds.

  jobs:
    build:
      runs-on: ubuntu-latest
+     timeout-minutes: 30


─── .github/workflows/build.yml:3-9 ───
No `concurrency` group defined. Multiple commits pushed to the same branch or PR will trigger
redundant, concurrent runs. Add a `concurrency` group scoped to the branch/ref with
`cancel-in-progress: true` to cancel outdated in-progress runs and save CI resources.

  on:
    push:
      branches: [ main, develop ]
    pull_request:
      branches: [ main ]
+
+ concurrency:
+   group: ci-${{ github.ref }}
+   cancel-in-progress: true

  jobs:


─── .github/workflows/build.yml:23-24 ───
Unpinned third-party action `gradle/actions/setup-gradle@v3` uses a mutable tag (`@v3`). Tags can be
force-pushed or overwritten, introducing supply-chain risk. Pin to a full commit SHA for
immutability (e.g., `uses: gradle/actions/setup-gradle@<sha>`).

        - name: Setup Gradle
-         uses: gradle/actions/setup-gradle@v3
+         uses: gradle/actions/setup-gradle@<full-commit-sha-here>


─── .gitignore:2-4 ───
**Missing `.gradle/` — Gradle build cache at project root**

Modern Gradle (5.0+) creates a `.gradle/` directory at the project root containing the build cache,
file-system watches, and compiled build script classes. These are machine-specific and should never
be committed. This is a standard entry in the official Android `.gitignore` template.

**Impact**: Build cache bloat in the repository, potential merge conflicts from generated files.

  .gradle/
  build/
  local.properties


─── .gitignore:12-15 ───
**Missing `*.hprof` — heap dump files from profiler**

Android Studio's Memory Profiler (and other JVM tools) generates `.hprof` heap dump files during
performance analysis. These files can be very large (hundreds of MB) and may contain sensitive
application data from the heap dump. They should be excluded to prevent accidental commits.

  # OS
  .DS_Store
  Thumbs.db
  Desktop.ini
+ *.hprof


─── .gitignore:12-15 ───
**Missing `*.log` — log files from builds/tests**

Build logs, test outputs, and Gradle daemon logs can be generated during development. These files
are transient and machine-specific; they should not be tracked in version control.

  # OS
  .DS_Store
  Thumbs.db
  Desktop.ini
+ *.log


─── .gitignore:12-15 ───
**Missing `captures/` — Android Studio network/profiler captures**

Android Studio stores HTTP/network captures and performance traces in the `captures/` directory at
the project root. These `.har`, `.trace`, and `.perf` files can be large and contain sensitive
request/response data.

- # OS
- .DS_Store
- Thumbs.db
- Desktop.ini
+ # Android Studio captures
+ captures/


─── gradlew:77-81 ───
**Symlink resolution via `ls -ld` parsing is fragile.** The expression `${ls#*' -> '}` parses the
`ls -ld` output to extract the symlink target by stripping everything before and including ` -> `.
This approach has multiple failure modes:

1. **Locale-dependent output format**: The `ls` output format varies across locales and operating
systems. On some systems, the string ` -> ` may appear at different positions or may have extra
fields before the target.
2. **Symlink targets containing ` -> `**: If a symlink target itself contains the substring ` -> `,
the pattern `*' -> '` uses the `#` operator which removes the shortest matching prefix, so it
captures from after the FIRST ` -> ` — this is correct only if the target doesn't contain ` -> `. A
target named `a -> b` would cause the extracted link to be `b` instead of `a -> b`.
3. **Unusual characters in target paths**: Spaces, tabs, or non-printable characters in the symlink
target path are not specially handled.

**Suggestion**: Use `readlink -f` or `readlink` if available (most systems support it), or use a
more robust manual resolution approach.

+     # Prefer readlink when available; fall back to manual resolution
+     if command -v readlink >/dev/null 2>&1; then
+         link=$( readlink "$app_path" )
+     else
      ls=$( ls -ld "$app_path" )
      link=${ls#*' -> '}
+     fi
      case $link in             #(
        /*)   app_path=$link ;; #(
        *)    app_path=$APP_HOME$link ;;


─── gradlew:203-203 ───
**`DEFAULT_JVM_OPTS` embedded double quotes rely on eval-side-effects for correct splitting.** The
value `'"-Xmx64m" "-Xms64m"'` stores literal double-quote characters inside a single-quoted string.
This works only because the downstream eval/sed/tr pipeline treats `"-Xmx64m"` as a single word that
eventually becomes two separate JVM arguments. Any modification to this pipeline could silently
break argument splitting, resulting in a single malformed argument being passed to the JVM.

**Suggestion**: Store the options without embedded quotes and let the existing xargs-based splitting
handle it naturally.

- DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
+ DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'


─── gradlew:145-145 ───
**MSYS/MinGW not excluded from ulimit adjustment, but ulimit often unavailable or broken on those
platforms.** The condition only excludes `$cygwin`, `$darwin`, and `$nonstop` from the
file-descriptor limit block. The `msys` flag (set when `$uname` matches `MSYS*|MINGW*`) is not
checked. On MSYS2/MinGW environments, `ulimit` either does not exist or produces unexpected output.
While the script uses `|| warn` to soft-fail, the warning message is vague and may confuse users on
Windows.

**Suggestion**: Add `! "$msys"` to the exclusion condition.

- if ! "$cygwin" && ! "$darwin" && ! "$nonstop" ; then
+ if ! "$cygwin" && ! "$darwin" && ! "$nonstop" && ! "$msys" ; then


─── gradlew:0-0 ───
**Eval-based argument assembly breaks on newlines or unmatched quotes in environment variables.**
The script's own comment acknowledges that the eval pattern will break if any of `DEFAULT_JVM_OPTS`,
`JAVA_OPTS`, or `GRADLE_OPTS` contain a newline or an unmatched quote. While the sed-based escaping
handles most shell metacharacters, these two cases are silently unsafe:

- **Newlines**: If a user sets `JAVA_OPTS` to contain a newline character (e.g., through a
compromised or misconfigured CI/CD environment), the `printf` to `xargs -n1` to `tr` pipeline will
split and reassemble the arguments incorrectly, potentially altering subsequent JVM options.
- **Unmatched quotes**: An unmatched double quote in any of these variables will cause a shell
syntax error at the `eval` line, crashing the script.

This is a well-known design constraint of the Gradle wrapper, but it remains a real risk in
environments where these variables can be externally influenced.

**Suggestion**: Add a pre-validation check for newlines before the eval.

- eval "set -- $( printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" | xargs -n1 | sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' | tr '\n' ' ' )" '"$@"'
+ # Validate no newlines in environment variables that feed into eval
+ combined_opts="$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS"
+ case "$combined_opts" in
+   *$'\n'*)
+     die "ERROR: JAVA_OPTS, GRADLE_OPTS, and DEFAULT_JVM_OPTS must not contain newlines."
+     ;;
+ esac
+ eval "set -- $( printf '%s\n' "$combined_opts" | xargs -n1 | sed ' s~[^-[:alnum:]+,./:=@_]~\\&~g; ' | tr '\n' ' ' )" '"$@"'

[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/ui/screens/AlbumDetailScreen.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt: LLM completion error: context deadline exceeded
[ocr] WARNING [scan_subtask_error] app/src/main/res/values/strings.xml: LLM completion error: context deadline exceeded


──────── Project Summary ────────

# Project-Level Summary

## Top Issues

### 1. Resource Leaks (OkHttp Responses & Coroutine Scopes)
Multiple modules fail to close OkHttp Response objects and leak coroutine scopes:
- **`MetingApiService.kt`** – `response` not closed in 3 methods; use `response.use{}`
- **`LyricsNetworkProvider.kt`** – Response not closed when `body()` is null or on exception
- **`BackendRegistry.kt`** – New adapter not cleaned up if `initialize()` throws; previous adapter leaked on re-initialization
- **`NasMusicApp.kt`** – `applicationScope` never cancelled, violating structured concurrency
- **`LyricsNetworkProvider.kt`** – `Executors.newCachedThreadPool` never shut down

### 2. Race Conditions & Concurrency Hazards
Critical shared state mutations without synchronization:
- **`BackendRegistry.kt`** – `initialize()` and `testConnection()` both mutate `currentAdapter`/`currentConfig` on `Dispatchers.IO` without locks
- **`NetworkMusicManager.kt`** – Cache check-and-write non-atomic ⇒ redundant network requests; play URL cache never evicts expired entries (memory leak)
- **`NavidromeAdapter.kt`** – `toggleFavorite` fetches all starred songs (non-atomic check-then-act); `async`/`awaitAll` without `SupervisorJob` cancels sibling coroutines on failure
- **`JellyfinAdapter.kt`** – `toggleFavorite()` cache update has race window with server state; infinite loop risk in pagination if API returns `pageSize` entries beyond total
- **`LyricsManager.kt`** – Cache file operations (`cacheLyrics`, `getCachedLyrics`, `clearCache`) not synchronized ⇒ file corruption

### 3. Security & Privacy Violations
- **`AndroidManifest.xml`** – `usesCleartextTraffic="true"` globally without Network Security Config; `allowBackup=true` without `dataExtractionRules`; unused `WRITE/READ_EXTERNAL_STORAGE` permissions
- **`ServerConnectScreen.kt`** – Hardcoded default credentials (`hxzhang`/`wfxzhx2000`) and internal IP `192.168.0.190:8096`
- **`ServerConfig.kt`** – `data class toString()` exposes plaintext `password` and `apiToken`
- **`JellyfinAdapter.kt`** – API token embedded in URLs as query parameter (logged/returned to callers)
- **`AppLog.kt`** – Error logs (`Log.e`) unconditional in release builds, leaking stack traces/URLs
- **`CryptoUtils.kt`** – Fail-open encryption: returns input unchanged on exception, silently corrupting data

### 4. Correctness Bugs
- **`JellyfinAdapter.kt`** – `addToPlaylist` sends `Ids` as a single string instead of JSON array; `setRating` sends rating in request body (should be query param); `getPlaylists()` reads owner from `AlbumArtist` (always null for playlists)
- **`NavidromeAdapter.kt`** – `removeFromPlaylist` uses `songIdToRemove` (non-standard) instead of `songIndexToRemove`; `getSongs` sends `type=alphabetical` (invalid for `getSongs` endpoint)
- **`EqualizerScreen.kt`** – Band adjustment logic makes values -9..-1 unreachable via UI
- **`RetryUtil.kt`** – `CancellationException` caught and retried, violating structured concurrency; retry may continue after cancellation
- **`PlaybackService.kt`** – `player.release()` called before `mediaLibrarySession.release()`; notification action labels semantically inverted; `buildMediaButtonPendingIntent` without registered `MediaButtonReceiver`

### 5. Performance Anti-Patterns
- **Regex re-creation** – `LrcParser.kt`, `MetingApiService.kt` (fallback path) compile patterns on every call; should be `companion object` constants
- **Unbounded memory growth** – `NetworkMusicManager.kt` play URL cache never evicts; `AppPreferences.kt` `setEqualizerBand` `while (bands.size <= index)` no upper bound
- **Recomposition overhead** – `SettingsScreen.kt` cache size `walkTopDown()` computed in composable body (not `remember`); `CoverCarousel.kt` callbacks during composition; `LyricsView.kt` O(n) `indexOfFirst` per frame
- **Redundant operations** – `NavidromeAdapter.kt` `getSongsByYearRange` loads up to 10,000 songs then filters client-side; `take(20)` after API already limited

### 6. Hardcoded Strings & Missing Localization
User-visible Chinese strings hardcoded in:
- `CommonComponents.kt` – `"←"` arrow symbol
- `QueueScreen.kt` – `"${queue.size} 首"` (count suffix)
- `ServerConnectScreen.kt` – Multiple Chinese UI strings
- `PlaylistManagementScreen.kt` – String concatenation for labels

## Module Hotspots

| Module/Path | Comment Density | Key Concerns |
|------------|----------------|-------------|
| **`backend/impl/`** (NavidromeAdapter, JellyfinAdapter) | ~40 comments | Race conditions, API parameter bugs, dead code, security (token in URLs), redundant operations |
| **`backend/network/`** (NetworkMusicManager, MetingApiService) | ~25 comments | Resource leaks, cache race conditions, design violations (OCP), regex recreation |
| **`ui/screens/`** (ServerConnectScreen, SettingsScreen, QueueScreen, PlaylistManagementScreen) | ~60 comments | Hardcoded credentials/URLs, dead code (unused params), performance (walkTopDown), missing imports, focus bugs |
| **`lyrics/`** (LyricsManager, LrcParser, LyricsNetworkProvider) | ~30 comments | Resource leaks, missing cache sync, unnecessary duplication, performance (regex), edge-case parsing bugs |
| **`data/model/`** (ServerConfig, EqualizerPreset, Song, LyricsLine) | ~20 comments | Security (toString leak), mutable arrays in enum, default value semantics, missing validation |
| **`util/`** (AppLog, CryptoUtils, RetryUtil, MediaKeyHandler, PinyinUtils) | ~25 comments | Security (logs in release, fail-open encryption), concurrency (CancellationException), logic bugs (key mapping, CJK range) |

## Cross-Cutting Concerns

### Resource Management
- **OkHttp Responses not closed** – `MetingApiService.kt`, `LyricsNetworkProvider.kt`
- **Coroutine scopes leaked** – `NasMusicApp.kt` scope never cancelled; `DisposableEffect` leaves coroutines running after disposal (e.g., `ExitConfirmDialog.kt`, `ServerConnectScreen.kt`)
- **Thread pools unshuttable** – `LyricsNetworkProvider.kt` `newCachedThreadPool`; `NetworkMonitor.kt` strong reference to Context may leak Activity

### Concurrency & Thread Safety
- **Mutable shared state without synchronization** – `BackendRegistry.kt`, `JellyfinAdapter.kt` (cache), `NavidromeAdapter.kt` (toggleFavorite)
- **Non-atomic read-then-write** – `NetworkMusicManager.kt` cache; `NavidromeAdapter.kt` `toggleFavorite`
- **State updates off main thread** – `SettingsScreen.kt` mutates Compose state from `Dispatchers.IO`
- **`CancellationException` mishandling** – `RetryUtil.kt` catches it, violating structured concurrency

### Security & Privacy
- **Plaintext secrets in memory/output** – `ServerConfig.toString()`, URLs with tokens, hardcoded credentials
- **Unrestricted cleartext** – `AndroidManifest.xml` global `usesCleartextTraffic`
- **Data backup without restriction** – `allowBackup=true` exposes app preferences
- **Unused dangerous permissions** – Storage permissions declared but not used

### Code Quality & Maintainability
- **Dead code** – Unused imports (`MainActivity.kt`), unused fields (`NasMusicApp.instance`, `NavidromeAdapter.apiToken`, `LyricsLine.WordTimestamp.durationMs`, `LyricsNetworkProvider.gson`, `QueueScreen.onPlayPause`, `SettingsScreen.onChangeLyricsOffset`, etc.)
- **Silent failures** – `CryptoUtils` fail-open, `BackendAdapter` methods defaulting to `false`/empty list, `EncodingUtils` empty catch blocks
- **Hardcoded magic numbers/strings** – Colors recomputed on every recomposition, file paths (`/storage/emulated/0/...`), limit values
- **Missing Java interop annotations** – `@JvmStatic`, `@JvmOverloads` omitted on several classes

### Testing & Error Handling Gaps
- **Exceptions swallowed** – `LyricsManager.kt` caught exceptions not logged; `EncodingUtils.kt` empty catches; `AppLog.kt` unconditional error logging
- **No HTTP error checking** – `LyricsNetworkProvider.kt` reads body without checking `isSuccessful`
- **Missing validation** – `Playlist.id` not checked for empty; `ServerConfig.isValid` omits credential checks; `Song.id` format not enforced

### Localization
- Hardcoded Chinese strings in 4+ UI files; should use `stringResource`

## Quick Wins

### Low-Effort / High-Leverage Fixes

| # | Fix | Impact | Suggested Files |
|---|-----|--------|----------------|
| 1 | **Close OkHttp responses** with `response.use{}` | Eliminates resource leaks, prevents file descriptor exhaustion | `MetingApiService.kt`, `LyricsNetworkProvider.kt` |
| 2 | **Extract Regex constants** as `companion object` values | Saves compilation cost on every call | `LrcParser.kt`, `MetingApiService.kt` (fallback path) |
| 3 | **Remove dead code** – unused imports, fields, parameters | Reduces maintenance burden, improves clarity | `MainActivity.kt`, `QueueScreen.kt` (onPlayPause), `LyricsLine.kt` (durationMs), `NavidromeAdapter.kt` (apiToken), `SettingsScreen.kt` (onChangeLyricsOffset) |
| 4 | **Add `@JvmStatic` to companion methods** | Fixes Java interop | `PlayMode.kt` (fromOrdinal) |
| 5 | **Replace hardcoded default credentials** with empty strings | Eliminates credential leakage in source | `ServerConnectScreen.kt` |
| 6 | **Add Network Security Config** to restrict cleartext to specific domains | Hardens transport security | `AndroidManifest.xml`, new `res/xml/network_security_config.xml` |
| 7 | **Remove unused storage permissions** from manifest | Reduces attack surface, simplifies privacy policy | `AndroidManifest.xml` |
| 8 | **Add `.gitignore` entries** for `.gradle/`, `*.hprof`, `*.log`, `captures/` | Prevents accidental commit of machine-specific/large files | `.gitignore` |
| 9 | **Replace `runBlocking` on main thread** with `suspend` or cached values | Prevents ANR | `MainActivity.kt` (exit confirm), `AppPreferences.kt` (sync reads) |
| 10 | **Centralize duplicated colors** from `Color.kt` into `Theme.kt` `NasMusicColors` | Eliminates maintainability risk | `Color.kt`, `Theme.kt` |
