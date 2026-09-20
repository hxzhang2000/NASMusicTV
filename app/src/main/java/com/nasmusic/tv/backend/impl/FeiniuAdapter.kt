package com.nasmusic.tv.backend.impl

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nasmusic.tv.backend.BackendAdapter
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.UrlSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 飞牛音乐（fnOS 内置音乐服务）后端适配器
 *
 * ## 协议依据
 *
 * 本实现**不再**基于第三方逆向文章的猜测，而是对齐可运行的飞牛 TV 客户端
 * `fn-music-tv`（github.com/QiaoKes/fn-music-tv）的真实协议，详见
 * `docs/archive/feiniu-backend-improvement-plan.md`。
 *
 * 要点：
 * - API 基址：`<scheme>://<host>:<port>/music/api/v1/`（默认端口 5666 / HTTPS 5667）
 * - 认证：`POST user/password-login`（密码 SHA-256 小写 hex）→ `data.userToken`；
 *   后续请求用 **`Authorization: <userToken>`（原始值，无 Bearer 前缀）**
 * - 信封：`{code, msg, data}`，`code != 0` 即失败；`data` 可能为 null
 * - 分页：`page`（从 1 开始）+ `size`（**不是** limit/offset）
 * - ID：全部为 **GUID 字符串**；`duration` 单位**已是毫秒**
 * - 封面按 `static/cover?coverId=<id>&size=<px>` 取（**不是**按曲目 ID）
 * - 播放流 `track/stream?guid=<guid>`（guid 是**查询参数**）
 *
 * **本适配器刻意不调用 `EncodingUtils.fixEncoding()`**，元数据字符串原样使用。
 * 依据与理由见 [parseTrack] 上方的注释——简言之：该函数是为 Jellyfin 的
 * 「GBK 字节被当 UTF-8 存」问题设计的，而它会**无条件剥掉结尾的 `?`**，
 * 用在返回正常 UTF-8 的飞牛上是纯损失（"Why?" → "Why"）。
 *
 * Song ID 格式：`feiniu_<GUID>`，跨会话稳定。
 */
class FeiniuAdapter(private val appContext: Context? = null) : BackendAdapter {

    companion object {
        private const val TAG = "FeiniuAdapter"

        /** Song/Album/Artist/Playlist 统一 ID 前缀 */
        private const val ID_PREFIX = "feiniu_"

        /** 翻页默认页大小 */
        private const val DEFAULT_PAGE_SIZE = 200

        /** 防御服务端 total 异常：单次翻页最多取多少页 */
        private const val MAX_PAGES = 200

        /** 曲目元数据并发上限（`getSongsByIds`） */
        private const val METADATA_CONCURRENCY = 4

        /** 超过该数量改用"全量拉取后过滤"，避免逐条请求 */
        private const val METADATA_BATCH_THRESHOLD = 40

        /** 封面请求尺寸 */
        private const val COVER_SIZE = 512

        /**
         * ⚠️ `audioSpec.bitrate` 的单位（bps 还是 kbps）**未确认，一律填 0**。
         *
         * 依据：参考项目只在 `AudioSpecDto` 里声明了该字段、**全项目从未使用**，
         * 契约文档亦未说明单位 —— 没有任何证据支持某一种解读。
         * 而 `SongInfoPanel` 会把 `SongTechnicalInfo.bitrate` 直接渲染成 "N kbps"，
         * 猜错就会显示 "320000 kbps" 这种一眼假的值。
         * **宁缺勿错**：填 0 时 UI 显示 "—"。待真机抓一条已知码率的曲目确认单位后，
         * 再改为原值（kbps）或 /1000（bps）。见开发计划 §12 待确认项。
         */
        private const val BITRATE_UNVERIFIED = 0

        /** 搜索用的全量曲目缓存有效期 */
        private const val TRACK_CACHE_TTL_MS = 5 * 60 * 1000L

        /** deviceId 持久化键（跨进程启动保持稳定，避免服务端设备列表膨胀） */
        private const val PREF_NAME = "feiniu_backend"
        private const val PREF_DEVICE_ID = "device_id"

        private const val PREFS_UNSET = ""
    }

    override val backendType: String = ServerConfig.TYPE_FEINIU
    override var serverName: String = "飞牛音乐"
    override var apiVersion: String = "Unknown"

    /** 归一化后的 API 基址，恒以 `/music/api/v1/` 结尾 */
    private var apiBase: String = ""

    /** 登录令牌（对应服务端 `userToken`） */
    @Volatile
    private var userToken: String = ""

    /** 登录用户名与密码摘要，仅用于令牌失效时静默重登 */
    @Volatile
    private var loginUsername: String = ""
    @Volatile
    private var loginPasswordSha: String = ""

    /** 令牌代数：登录成功递增；并发重登去重用（见 [withAuthRetry]） */
    @Volatile
    private var tokenGeneration: Int = 0

    /** 静默重登互斥：批量并发同时 401 时只真正重登一次 */
    private val reloginMutex = Mutex()

    private var serverVersion: String = ""
    private var mediasrvVersion: String = ""

    /** 曲目 ID → coverId（供 `getCoverUrl` 兜底路径使用） */
    private val trackCoverIds = LruCache<String, String>(512)

    /** 专辑 ID → coverId（供 `getCoverUrlCandidates` 使用） */
    private val albumCoverIds = LruCache<String, String>(512)

    /** 歌手 ID → coverId（供 `getCoverUrlCandidates` 使用） */
    private val artistCoverIds = LruCache<String, String>(512)

    /** 搜索用的全量曲目快照 */
    @Volatile
    private var allTracksCache: List<Song> = emptyList()
    @Volatile
    private var allTracksCacheAt: Long = 0L
    @Volatile
    private var allTracksCacheKey: String = ""

    /**
     * P2-8 修复（2026-09-16）：全量曲目拉取单飞锁。
     * 缓存 miss 时并发调用（搜索 + 收藏 + getSongsByIds 各自触发）会各自跑一次
     * `fetchAllPages` 全量翻页（最多 MAX_PAGES 页），造成重复网络风暴。
     * 加锁后排队者拿到锁会重查缓存，只有第一个真正拉取。
     */
    private val allTracksRefreshMutex = Mutex()

    // 安全修复（C-1）：不使用 trust-all，采用系统默认证书校验。

    private val client: OkHttpClient by lazy {
        // R-6：注入共享连接池 / Dispatcher（BackendRegistry 持有，切后端不再累积线程池）
        OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 播放流 / 封面请求需要注入的认证头。
     *
     * 由 `BackendRegistry` 在连接成功时将本属性绑定为 provider 给 `BackendAuthHeaders`
     * （每次请求实时读取，静默重登换新令牌后即时生效），
     * 再经 `BaiduHttpDataSourceFactory` 的拦截器注入 ExoPlayer 与 Coil 两条链路。
     */
    override val streamHeaders: Map<String, String>
        get() = if (userToken.isNotBlank()) mapOf("Authorization" to userToken) else emptyMap()

    // ==================== 认证 ====================

    override suspend fun initialize(
        baseUrl: String,
        apiToken: String,
        username: String,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        val normalized = FeiniuUrl.normalize(baseUrl)
        if (normalized.isBlank()) {
            AppLog.w(TAG, "initialize: invalid baseUrl=${UrlSanitizer.sanitize(baseUrl)}")
            return@withContext false
        }
        apiBase = normalized
        AppLog.d(TAG, "initialize: apiBase=${UrlSanitizer.sanitize(apiBase)}")

        // 已有令牌：直接校验
        if (apiToken.isNotBlank()) {
            userToken = apiToken
            if (verifyToken()) {
                fetchSystemConfig()
                return@withContext true
            }
            AppLog.w(TAG, "initialize: saved token rejected, falling back to password login")
            userToken = ""
        }

        // 用户名 + 密码登录
        if (username.isNotBlank() && password.isNotBlank()) {
            val sha = sha256Hex(password)
            if (login(username, sha)) {
                loginUsername = username
                loginPasswordSha = sha
                fetchSystemConfig()
                return@withContext true
            }
        }
        false
    }

    /**
     * `POST user/password-login`
     * Body: `{"username": ..., "password": "<sha256 hex>", "deviceId": ...}`
     * 响应 `data.userToken` —— 注意是 **userToken**，不是 token。
     */
    private fun login(username: String, passwordSha: String): Boolean {
        val body = JsonObject().apply {
            addProperty("username", username)
            addProperty("password", passwordSha)
            addProperty("deviceId", deviceId())
        }.toString()
        val url = FeiniuUrl.endpoint(apiBase, "user/password-login")
        return try {
            val data = dataOf(post(url, body, authenticated = false), url) ?: return false
            val token = str(data, "userToken")
            if (token.isNullOrBlank()) {
                AppLog.w(TAG, "login: response has no userToken")
                return false
            }
            userToken = token
            tokenGeneration++
            AppLog.d(TAG, "login success, token=${token.take(8)}...")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "login failed", e)
            false
        }
    }

    /** `GET user/me` 校验令牌是否有效 */
    private fun verifyToken(): Boolean {
        if (userToken.isBlank() || apiBase.isBlank()) return false
        return try {
            val url = FeiniuUrl.endpoint(apiBase, "user/me")
            dataOf(get(url), url) != null
        } catch (e: Exception) {
            AppLog.d(TAG, "verifyToken failed: ${e.message}")
            false
        }
    }

    /**
     * `GET sys/config`（免认证）→ 服务器名与版本号
     * 字段：`serverGUID` / `serverName` / `serverVersion` / `mediasrvVersion`
     */
    private fun fetchSystemConfig() {
        try {
            val url = FeiniuUrl.endpoint(apiBase, "sys/config")
            val data = dataOf(get(url, authenticated = false), url) ?: return
            str(data, "serverName")?.let { if (it.isNotBlank()) serverName = it }
            serverVersion = str(data, "serverVersion") ?: ""
            mediasrvVersion = str(data, "mediasrvVersion") ?: ""
            apiVersion = serverVersion.ifBlank { "飞牛音乐" }
            AppLog.d(TAG, "sys/config: name=$serverName, version=$serverVersion, mediasrv=$mediasrvVersion")
        } catch (e: Exception) {
            AppLog.w(TAG, "sys/config failed", e)
        }
    }

    override suspend fun getApiVersion(): VersionInfo = withContext(Dispatchers.IO) {
        if (apiBase.isBlank()) return@withContext VersionInfo.Disconnected("飞牛音乐")
        if (serverVersion.isBlank()) fetchSystemConfig()
        if (serverVersion.isBlank()) {
            VersionInfo.NoVersion("飞牛音乐")
        } else {
            VersionInfo.Runtime("飞牛音乐", serverVersion, "sys/config", System.currentTimeMillis())
        }
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        if (apiBase.isBlank()) return@withContext false
        try {
            // 已登录 → 校验会话；未登录 → 探测免认证的 sys/config
            if (userToken.isNotBlank()) verifyToken()
            else {
                val url = FeiniuUrl.endpoint(apiBase, "sys/config")
                dataOf(get(url, authenticated = false), url) != null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "testConnection failed", e)
            false
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        if (apiBase.isNotBlank() && userToken.isNotBlank()) {
            try {
                val url = FeiniuUrl.endpoint(apiBase, "user/logout")
                // 与参考项目一致：POST 空 body（无 Content-Type），而非空 JSON 串
                val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
                execute(request, url, authenticated = true)
            } catch (e: Exception) {
                AppLog.w(TAG, "logout request failed", e)
            }
        }
        clearSessionState()
    }

    override fun close() {
        // R-6：连接池 / 线程池已共享（BackendRegistry 持有），此处禁止 shutdown / evictAll
        clearSessionState()
    }

    private fun clearSessionState() {
        userToken = ""
        loginUsername = ""
        loginPasswordSha = ""
        apiBase = ""
        serverVersion = ""
        mediasrvVersion = ""
        synchronized(trackCoverIds) { trackCoverIds.clear() }
        synchronized(albumCoverIds) { albumCoverIds.clear() }
        synchronized(artistCoverIds) { artistCoverIds.clear() }
        allTracksCache = emptyList()
        allTracksCacheAt = 0L
        allTracksCacheKey = ""
    }

    // ==================== 专辑 ====================

    /** `GET album/list?page&size&sort=newTrackAddedAt,desc` */
    override suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchAllPages("album/list", "sort" to "newTrackAddedAt,desc") { parseAlbum(it) }
        }.onFailure { AppLog.e(TAG, "getAlbums failed", it) }.getOrDefault(emptyList())
    }

    /** `GET track/album-detail/list?albumGUID=&sort=trackNo,asc` */
    override suspend fun getAlbumSongs(albumId: String): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            val pages = fetchAllPagesRaw(
                "track/album-detail/list",
                "albumGUID" to stripPrefix(albumId),
                "sort" to "trackNo,asc"
            )
            val songs = ArrayList<Song>(pages.size)
            pages.forEachIndexed { index, obj -> parseTrack(obj, index + 1)?.let { songs.add(it) } }
            songs
        }.onFailure { AppLog.e(TAG, "getAlbumSongs failed", it) }.getOrDefault(emptyList())
    }

    // ==================== 歌手 ====================

    /** `GET artist/list?page&size&sort=trackCount,desc` */
    override suspend fun getArtists(): List<Artist> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchAllPages("artist/list", "sort" to "trackCount,desc") { parseArtist(it) }
        }.onFailure { AppLog.e(TAG, "getArtists failed", it) }.getOrDefault(emptyList())
    }

    /** `GET track/artist-detail/list?artistGUID=&sort=createdAt,desc` */
    override suspend fun getArtistSongs(artistId: String, artistName: String?): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchAllPages("track/artist-detail/list", "artistGUID" to stripPrefix(artistId), "sort" to "createdAt,desc") {
                parseTrack(it)
            }
        }.onFailure { AppLog.e(TAG, "getArtistSongs failed", it) }.getOrDefault(emptyList())
    }

    // ==================== 歌曲 ====================

    /** `GET track/list?page&size&sort=createdAt,desc` */
    override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            // B14 修复沿用：limit <= 0 会除零，回退默认页大小
            val safeLimit = if (limit > 0) limit else DEFAULT_PAGE_SIZE
            val page = (offset / safeLimit) + 1
            fetchAllPages("track/list", "sort" to "createdAt,desc", page = page, size = safeLimit, singlePage = true) {
                parseTrack(it)
            }
        }.onFailure { AppLog.e(TAG, "getSongs failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun getSongsTotalCount(): Int = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchTotal("track/list", "sort" to "createdAt,desc")
        }.onFailure { AppLog.e(TAG, "getSongsTotalCount failed", it) }.getOrDefault(0)
    }

    /**
     * 按 ID 批量取曲目。
     *
     * 飞牛无批量端点：少量 ID 走 `track/metadata?guid=`（并发 4），
     * 数量大时改为一次性拉取全量曲目后过滤，避免请求风暴。
     */
    override suspend fun getSongsByIds(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        runCatchingSuspend {
            if (ids.size > METADATA_BATCH_THRESHOLD) {
                val wanted = ids.map { stripPrefix(it) }.toSet()
                return@runCatchingSuspend loadAllTracks().filter { stripPrefix(it.id) in wanted }
            }
            coroutineScope {
                ids.chunked(METADATA_CONCURRENCY).flatMap { chunk ->
                    chunk.map { id -> async { fetchTrackMetadataSong(id) } }.awaitAll().filterNotNull()
                }
            }
        }.onFailure { AppLog.e(TAG, "getSongsByIds failed", it) }.getOrDefault(emptyList())
    }

    private suspend fun fetchTrackMetadataSong(id: String): Song? {
        val raw = stripPrefix(id)
        // 静默重登覆盖：播放解析链依赖本调用，401 时先重登再重试
        return withAuthRetry { fetchTrackMetadataSongRaw(raw) }
    }

    private fun fetchTrackMetadataSongRaw(raw: String): Song? {
        val url = FeiniuUrl.endpoint(apiBase, "track/metadata", "guid" to raw)
        val data = dataOf(get(url), url) ?: return null
        val track = data.getAsJsonObject("track") ?: data
        // 优先用 metadata 里的 audioSpec（列表接口返回的可能是空壳）
        val spec = data.getAsJsonObject("audioSpec")
        return parseTrack(track, audioSpec = spec)
    }

    /** `GET track/list?page=1&size=N&sort=createdAt,desc` */
    override suspend fun getRecentSongs(): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchAllPages("track/list", "sort" to "createdAt,desc", size = 100, singlePage = true) { parseTrack(it) }
        }.onFailure { AppLog.e(TAG, "getRecentSongs failed", it) }.getOrDefault(emptyList())
    }

    /**
     * 随机歌曲。
     *
     * 服务端无随机端点（`track/roam-*` 需要维护漫游会话状态，本次不引入），
     * 改为按总数随机取一页后打乱。
     */
    override suspend fun getRandomSongs(limit: Int): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            val size = if (limit > 0) limit else 20
            val total = fetchTotal("track/list", "sort" to "createdAt,desc")
            if (total <= 0) return@runCatchingSuspend emptyList<Song>()
            val maxPage = ((total + size - 1) / size).coerceAtLeast(1)
            val page = (1..maxPage).random()
            fetchAllPages("track/list", "sort" to "createdAt,desc", page = page, size = size, singlePage = true) {
                parseTrack(it)
            }.shuffled().take(size)
        }.onFailure { AppLog.e(TAG, "getRandomSongs failed", it) }.getOrDefault(emptyList())
    }

    /**
     * 搜索歌曲。
     *
     * ⚠️ 飞牛服务端**没有搜索端点**（参考项目 `TrimMusicApi` 全文无 search），
     * 因此改为客户端本地过滤：拉取全量曲目快照（5 分钟缓存）后按
     * 标题 / 艺术家 / 专辑做大小写不敏感匹配。
     */
    override suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isBlank()) return@withContext emptyList()
        runCatchingSuspend {
            val lower = keyword.lowercase()
            loadAllTracks().mapNotNull { song ->
                val title = song.title.lowercase()
                val artist = song.artist.lowercase()
                val album = song.album.lowercase()
                val score = when {
                    title.startsWith(lower) -> 0
                    artist.startsWith(lower) -> 1
                    album.startsWith(lower) -> 2
                    title.contains(lower) -> 3
                    artist.contains(lower) -> 4
                    album.contains(lower) -> 5
                    else -> return@mapNotNull null
                }
                score to song
            }.sortedWith(compareBy({ it.first }, { it.second.title.length })).map { it.second }
        }.onFailure { AppLog.e(TAG, "searchSongs failed", it) }.getOrDefault(emptyList())
    }

    /** 全量曲目快照（带 TTL 缓存，仅用于搜索 / 批量查询） */
    private suspend fun loadAllTracks(): List<Song> {
        val key = apiBase
        val now = System.currentTimeMillis()
        if (allTracksCache.isNotEmpty() &&
            allTracksCacheKey == key &&
            now - allTracksCacheAt < TRACK_CACHE_TTL_MS
        ) {
            return allTracksCache
        }
        // P2-8 修复（2026-09-16）：单飞——并发 miss 时只有一个真正全量拉取。
        // 拿到锁后必须**重查缓存**：排队期间前一个调用可能已填充缓存（含 TTL 判定），
        // 否则单飞退化为串行 N 次全量拉取。
        return allTracksRefreshMutex.withLock {
            val lockedAt = System.currentTimeMillis()
            if (allTracksCache.isNotEmpty() &&
                allTracksCacheKey == key &&
                lockedAt - allTracksCacheAt < TRACK_CACHE_TTL_MS
            ) {
                return@withLock allTracksCache
            }
            val fresh = fetchAllPages("track/list", "sort" to "createdAt,desc") { parseTrack(it) }
            allTracksCache = fresh
            allTracksCacheAt = lockedAt
            allTracksCacheKey = key
            fresh
        }
    }

    // ==================== 流 / 封面 / 歌词 / 技术信息 ====================

    /** `GET track/stream?guid=<guid>` */
    override fun getStreamUrl(songId: String): String =
        FeiniuUrl.streamUrl(apiBase, stripPrefix(songId))

    /**
     * 兜底封面地址（`static/cover?coverId=<id>&size=`）。
     *
     * ⚠️ 一、UI 主路径并不走这里（全仓库无调用者），而是 `Song.coverUrl`
     * 与 [getCoverUrlCandidates]；本方法仅保持接口契约完整。
     *
     * ⚠️ 二、本方法**只查内存缓存，不发网络请求**：它是非 suspend 的，
     * 调用方线程不可控（可能在主线程），缓存未命中时返回空串由 UI 降级占位图。
     * 需要精确封面请用 suspend 的 `getSongsByIds` / `getSongTechnicalInfo` 补齐缓存。
     */
    override fun getCoverUrl(songId: String): String {
        val raw = stripPrefix(songId)
        val cached = synchronized(trackCoverIds) { trackCoverIds[raw] }
        return FeiniuUrl.coverUrl(apiBase, cached, COVER_SIZE) ?: ""
    }

    /**
     * 封面候选列表：`歌曲封面 → 专辑封面 → 歌手封面`。
     *
     * 这是 UI **唯一**使用的封面入口（`MainViewModel`），必须包含 `song.coverUrl`，
     * 否则主路径封面全丢。
     */
    override fun getCoverUrlCandidates(song: Song): List<String> {
        val urls = LinkedHashSet<String>()
        song.coverUrl?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
        val albumCover = song.albumId?.let { synchronized(albumCoverIds) { albumCoverIds[stripPrefix(it)] } }
        FeiniuUrl.coverUrl(apiBase, albumCover, COVER_SIZE)?.let { urls.add(it) }
        val artistCover = song.artistId?.let { synchronized(artistCoverIds) { artistCoverIds[stripPrefix(it)] } }
        FeiniuUrl.coverUrl(apiBase, artistCover, COVER_SIZE)?.let { urls.add(it) }
        return urls.toList()
    }

    /**
     * `GET lyric/list?trackGUID=<guid>`
     *
     * 返回 `{list:[{guid,content,isLRC,offset}], preferred}`：
     * 优先取 `preferred` 指定的条目，否则取第一条 `isLRC == true` 的，再否则取第一条。
     * `offset` 非零时前置 `[offset:<ms>]` 行 —— `LrcParser` 会解析该头部。
     */
    override suspend fun getLyrics(songId: String): String? = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            // 静默重登覆盖：歌词加载不再因令牌过期静默失败
            withAuthRetry { fetchLyricsRaw(stripPrefix(songId)) }
        }.onFailure { AppLog.e(TAG, "getLyrics failed", it) }.getOrNull()
    }

    private fun fetchLyricsRaw(trackGuid: String): String? {
        val url = FeiniuUrl.endpoint(apiBase, "lyric/list", "trackGUID" to trackGuid)
        val data = dataOf(get(url), url) ?: return null
        val list = data.getAsJsonArray("list") ?: return null
        if (list.isEmpty) return null
        val preferred = str(data, "preferred")

        var chosen: JsonObject? = null
        var fallback: JsonObject? = null
        for (element in list) {
            val obj = element as? JsonObject ?: continue
            if (fallback == null) fallback = obj
            if (preferred != null && str(obj, "guid") == preferred) {
                chosen = obj
                break
            }
            if (chosen == null && obj.get("isLRC")?.asBoolean == true) chosen = obj
        }
        val target = chosen ?: fallback ?: return null
        val content = str(target, "content")?.takeIf { it.isNotBlank() } ?: return null
        val offset = target.get("offset")?.asLong ?: 0L
        return if (offset != 0L) "[offset:$offset]\n$content" else content
    }

    /** `GET track/metadata?guid=` → `audioSpec`（codec / container / bitrate / duration） */
    override suspend fun getSongTechnicalInfo(songId: String): SongTechnicalInfo? = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            // 静默重登覆盖：技术信息面板不再因令牌过期静默空白
            withAuthRetry { fetchTechInfoRaw(stripPrefix(songId)) }
        }.onFailure { AppLog.e(TAG, "getSongTechnicalInfo failed", it) }.getOrNull()
    }

    private fun fetchTechInfoRaw(guid: String): SongTechnicalInfo? {
        val url = FeiniuUrl.endpoint(apiBase, "track/metadata", "guid" to guid)
        val data = dataOf(get(url), url) ?: return null
        val spec = data.getAsJsonObject("audioSpec") ?: return null
        val container = str(spec, "container").orEmpty()
        val codec = str(spec, "codec").orEmpty()
        val rawBitrate = spec.get("bitrate")?.asLong ?: 0L
        if (rawBitrate != 0L) {
            AppLog.d(TAG, "track/metadata: raw bitrate=$rawBitrate (单位未确认，暂不展示)")
        }
        return SongTechnicalInfo(
            codec = codec.uppercase(),
            bitrate = BITRATE_UNVERIFIED,
            // ⚠️ 飞牛 audioSpec 不含采样率与声道数，未知字段填 0，不要臆造
            sampleRate = 0,
            channels = 0,
            fileSize = 0L,
            durationMs = spec.get("duration")?.asLong ?: 0L,
            format = container.ifBlank { codec }.uppercase()
        )
    }

    // ==================== 歌单 ====================

    /** `GET playlist/list`（列表不返回曲目数，需逐个 `playlist/detail` 补） */
    override suspend fun getPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            val url = FeiniuUrl.endpoint(apiBase, "playlist/list")
            val data = dataOf(get(url), url) ?: return@runCatchingSuspend emptyList<Playlist>()
            val list = data.getAsJsonArray("list") ?: return@runCatchingSuspend emptyList<Playlist>()
            val basic = list.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val guid = str(obj, "guid") ?: return@mapNotNull null
                Triple(
                    guid,
                    str(obj, "name") ?: "未命名歌单",
                    str(obj, "coverId")
                )
            }
            // 曲目数并发补齐（上限 4），单个失败不影响整体
            coroutineScope {
                basic.chunked(METADATA_CONCURRENCY).flatMap { chunk ->
                    chunk.map { (guid, name, coverId) ->
                        async {
                            val count = fetchPlaylistTrackCount(guid)
                            Playlist(
                                id = ID_PREFIX + guid,
                                name = name,
                                coverUrls = listOfNotNull(FeiniuUrl.coverUrl(apiBase, coverId, COVER_SIZE)),
                                songCount = count
                            )
                        }
                    }.awaitAll()
                }
            }
        }.onFailure { AppLog.e(TAG, "getPlaylists failed", it) }.getOrDefault(emptyList())
    }

    private suspend fun fetchPlaylistTrackCount(guid: String): Int {
        val url = FeiniuUrl.endpoint(apiBase, "playlist/detail", "guid" to guid)
        return try {
            withAuthRetry { dataOf(get(url), url)?.get("trackCount")?.asInt ?: 0 }
        } catch (e: Exception) {
            AppLog.d(TAG, "playlist/detail failed for guid=$guid: ${e.message}")
            0
        }
    }

    /** `GET track/playlist-detail/list?playlistGUID=&sort=trackAddedAt,desc` */
    override suspend fun getPlaylistSongs(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchAllPages(
                "track/playlist-detail/list",
                "playlistGUID" to stripPrefix(playlistId),
                "sort" to "trackAddedAt,desc"
            ) { parseTrack(it) }
        }.onFailure { AppLog.e(TAG, "getPlaylistSongs failed", it) }.getOrDefault(emptyList())
    }

    // ==================== 收藏 ====================

    /**
     * 切换收藏：`favorite-track/create` / `favorite-track/delete`
     *
     * 按入参 [isCurrentlyFavorite] 决定方向，不再"先 add 失败再 remove"
     * （旧实现那样做会导致无法真正取消收藏）。
     * 请求体固定 `{"trackGUID": "<guid>"}`，成功响应 `data` 可能为 null。
     */
    override suspend fun toggleFavorite(songId: String, isCurrentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            // 静默重登覆盖：收藏切换不再因令牌过期静默失败
            withAuthRetry { doToggleFavorite(stripPrefix(songId), isCurrentlyFavorite) }
        }.onFailure { AppLog.e(TAG, "toggleFavorite failed", it) }.getOrDefault(false)
    }

    private fun doToggleFavorite(trackGuid: String, isCurrentlyFavorite: Boolean): Boolean {
        val path = if (isCurrentlyFavorite) "favorite-track/delete" else "favorite-track/create"
        val body = JsonObject().apply { addProperty("trackGUID", trackGuid) }.toString()
        val url = FeiniuUrl.endpoint(apiBase, path)
        val envelope = post(url, body, authenticated = true) ?: return false
        val code = envelope.get("code")?.asInt ?: 0
        if (code != 0) {
            AppLog.w(TAG, "toggleFavorite failed: code=$code msg=${str(envelope, "msg")}")
            return false
        }
        return true
    }

    /** `GET favorite-track/list?page&size&sort=favoriteAt,desc` */
    override suspend fun getFavorites(): List<Song> = withContext(Dispatchers.IO) {
        runCatchingSuspend {
            fetchAllPages("favorite-track/list", "sort" to "favoriteAt,desc") { parseTrack(it) }
        }.onFailure { AppLog.e(TAG, "getFavorites failed", it) }.getOrDefault(emptyList())
    }

    // ==================== 内部：传输层 ====================

    /** 令牌失效（code 99999 / 120001 或 HTTP 401），触发一次静默重登 */
    private class AuthExpiredException : Exception("feiniu token expired")

    private fun get(url: String, authenticated: Boolean = true): JsonObject? =
        execute(Request.Builder().url(url).get().build(), url, authenticated)

    private fun post(url: String, jsonBody: String, authenticated: Boolean): JsonObject? {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        return execute(Request.Builder().url(url).post(body).build(), url, authenticated)
    }

    /**
     * 执行请求并返回**完整信封**（`{code,msg,data}`）。
     *
     * @return 信封；传输失败或 HTTP 非 2xx 时返回 null
     * @throws AuthExpiredException 401 或 code 99999/120001
     */
    private fun execute(request: Request, url: String, authenticated: Boolean): JsonObject? {
        return try {
            val builder = request.newBuilder()
            if (authenticated) {
                if (userToken.isBlank()) {
                    AppLog.w(TAG, "execute: no token for ${UrlSanitizer.sanitize(url)}")
                    return null
                }
                // 真实协议：原始 token，无 Bearer 前缀
                builder.header("Authorization", userToken)
            }
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 401) throw AuthExpiredException()
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "HTTP ${response.code} url=${UrlSanitizer.sanitize(url)}")
                    return null
                }
                val text = response.body?.string()
                if (text.isNullOrBlank()) return null
                val parsed = JsonParser.parseString(text)
                if (!parsed.isJsonObject) return null
                parsed.asJsonObject
            }
        } catch (e: AuthExpiredException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "request error url=${UrlSanitizer.sanitize(url)}", e)
            null
        }
    }

    /**
     * 取信封中的 `data` 对象。
     *
     * @return `data`；信封 code != 0 时返回 null（已记日志）
     * @throws AuthExpiredException 令牌失效
     */
    private fun dataOf(envelope: JsonObject?, url: String): JsonObject? {
        if (envelope == null) return null
        val code = envelope.get("code")?.asInt ?: 0
        if (code != 0) {
            val msg = str(envelope, "msg").orEmpty()
            when (code) {
                99999, 120001 -> throw AuthExpiredException()
                120002 -> AppLog.w(TAG, "account disabled: code=$code msg=$msg")
                100005 -> AppLog.d(TAG, "not found: code=$code url=${UrlSanitizer.sanitize(url)}")
                else -> AppLog.w(TAG, "api error code=$code msg=$msg url=${UrlSanitizer.sanitize(url)}")
            }
            return null
        }
        return envelope.getAsJsonObject("data")
    }

    // ==================== 内部：分页 ====================

    /** 翻页拉取并解析，`singlePage=true` 时只取指定页 */
    private suspend fun <T> fetchAllPages(
        path: String,
        vararg extra: Pair<String, Any?>,
        page: Int = 1,
        size: Int = DEFAULT_PAGE_SIZE,
        singlePage: Boolean = false,
        parse: (JsonObject) -> T?
    ): List<T> {
        val result = ArrayList<T>()
        var currentPage = page.coerceAtLeast(1)
        var pagesFetched = 0
        while (pagesFetched < MAX_PAGES) {
            // P2-7 修复（2026-09-16）：取整个 `data` 信封（同时含 list 与 total）。
            // 原实现只用 `items.size() < size` 判末页——服务端某页因限流/过滤少发几条时
            // 会被误判为末页，后续页被静默截断（收藏/全量搜索少歌且无任何日志）。
            // 现在优先以 `data.total` 判定。
            val data = fetchPageEnvelope(path, currentPage, size, *extra) ?: break
            val items = data.getAsJsonArray("list") ?: break
            items.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                parse(obj)?.let { result.add(it) }
            }
            pagesFetched++
            if (singlePage || items.size() == 0) break
            val total = data.get("total")?.asInt ?: -1
            val reachedEnd = if (total >= 0) {
                // 已请求 pagesFetched 页、每页 size 条，覆盖 total 即到末页
                pagesFetched.toLong() * size >= total
            } else {
                // 服务端未回 total 时退回旧判据，保持向后兼容
                items.size() < size
            }
            if (reachedEnd) break
            currentPage++
        }
        return result
    }

    /** 翻页拉取原始 JsonObject（不解析），供需要下标信息的场景使用 */
    private suspend fun fetchAllPagesRaw(
        path: String,
        vararg extra: Pair<String, Any?>
    ): List<JsonObject> = fetchAllPages(path, *extra) { it }

    /** 取单页的 `data` 信封（同时含 `list` 与 `total`）；失败或不存在时返回 null */
    private suspend fun fetchPageEnvelope(
        path: String,
        page: Int,
        size: Int,
        vararg extra: Pair<String, Any?>
    ): JsonObject? = withAuthRetry {
        val url = FeiniuUrl.endpoint(
            apiBase, path,
            *extra,
            "page" to page,
            "size" to size
        )
        dataOf(get(url), url)
    }

    /** 取 `data.total` 用于分页显示与随机取页 */
    private suspend fun fetchTotal(path: String, vararg extra: Pair<String, Any?>): Int = withAuthRetry {
        val url = FeiniuUrl.endpoint(apiBase, path, *extra, "page" to 1, "size" to 1)
        val data = dataOf(get(url), url) ?: return@withAuthRetry 0
        data.get("total")?.asInt ?: (data.getAsJsonArray("list")?.size() ?: 0)
    }

    /**
     * 令牌失效时静默重登一次再重试。
     * 重登凭据来自 `initialize` 时保存的密码摘要。
     * 并发场景（如 getSongsByIds 并发 4）：互斥 + 令牌代数去重，只真正重登一次。
     */
    private suspend fun <T> withAuthRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: AuthExpiredException) {
            if (loginUsername.isBlank() || loginPasswordSha.isBlank()) throw e
            AppLog.w(TAG, "token expired, attempting silent re-login")
            val seenGeneration = tokenGeneration
            reloginMutex.withLock {
                if (tokenGeneration == seenGeneration) {
                    if (!login(loginUsername, loginPasswordSha)) throw e
                }
            }
            block()
        }
    }

    // ==================== 内部：解析 ====================

    private fun parseAlbum(obj: JsonObject): Album? {
        val guid = str(obj, "guid") ?: return null
        val name = str(obj, "name")?.takeIf { it.isNotBlank() } ?: return null
        val coverId = str(obj, "coverId")
        if (coverId != null) synchronized(albumCoverIds) { albumCoverIds[guid] = coverId }
        // AlbumDto.artists 是数组（可能为空），展示时取首位
        val artistName = obj.getAsJsonArray("artists")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.let { str(it, "name") }
        // releaseDate 形如 "2019-05-01"，取前 4 位作年份
        val year = str(obj, "releaseDate")?.take(4)?.toIntOrNull()
        return Album(
            id = ID_PREFIX + guid,
            name = name,
            artist = artistName ?: "",
            coverUrl = FeiniuUrl.coverUrl(apiBase, coverId, COVER_SIZE),
            year = year,
            songCount = obj.get("trackCount")?.asInt ?: 0
        )
    }

    private fun parseArtist(obj: JsonObject): Artist? {
        val guid = str(obj, "guid") ?: return null
        val name = str(obj, "name")?.takeIf { it.isNotBlank() } ?: return null
        val coverId = str(obj, "coverId")
        if (coverId != null) synchronized(artistCoverIds) { artistCoverIds[guid] = coverId }
        return Artist(
            id = ID_PREFIX + guid,
            name = name,
            coverUrl = FeiniuUrl.coverUrl(apiBase, coverId, COVER_SIZE),
            albumCount = obj.get("albumCount")?.asInt ?: 0,
            songCount = obj.get("trackCount")?.asInt ?: 0
        )
    }

    /**
     * 解析服务端 TrackDto。
     *
     * @param fallbackTrackNumber 列表接口不返回 trackNo，由调用方按序号补
     * @param audioSpec 外部传入的 audioSpec（`track/metadata` 场景），为空则取对象内的
     */
    private fun parseTrack(obj: JsonObject, fallbackTrackNumber: Int = 0, audioSpec: JsonObject? = null): Song? {
        val guid = str(obj, "guid") ?: return null
        val title = str(obj, "title")?.takeIf { it.isNotBlank() } ?: return null

        val coverId = str(obj, "coverId")
        if (coverId != null) synchronized(trackCoverIds) { trackCoverIds[guid] = coverId }

        val albumObj = obj.getAsJsonObject("album")
        val albumGuid = str(albumObj, "guid")
        val albumName = str(albumObj, "name")
        val albumCoverId = str(albumObj, "coverId")
        if (albumGuid != null && albumCoverId != null) {
            synchronized(albumCoverIds) { albumCoverIds[albumGuid] = albumCoverId }
        }

        val artistNames = ArrayList<String>()
        var firstArtistGuid: String? = null
        var firstArtistCoverId: String? = null
        obj.getAsJsonArray("artists")?.forEach { el ->
            val artistObj = el as? JsonObject ?: return@forEach
            str(artistObj, "name")?.takeIf { it.isNotBlank() }?.let { artistNames.add(it) }
            val ag = str(artistObj, "guid")
            val ac = str(artistObj, "coverId")
            if (firstArtistGuid == null && ag != null) firstArtistGuid = ag
            if (firstArtistCoverId == null && ac != null) firstArtistCoverId = ac
            if (ag != null && ac != null) synchronized(artistCoverIds) { artistCoverIds[ag] = ac }
        }

        // duration 单位已是毫秒（参考项目 Dto: durationMs = duration），不要 ×1000
        val spec = audioSpec ?: obj.getAsJsonObject("audioSpec")
        val durationMs = obj.get("duration")?.asLong ?: spec?.get("duration")?.asLong ?: 0L

        return Song(
            id = ID_PREFIX + guid,
            title = title,
            artist = artistNames.joinToString(" / "),
            artistId = firstArtistGuid?.let { ID_PREFIX + it },
            album = albumName ?: "",
            albumId = albumGuid?.let { ID_PREFIX + it },
            coverUrl = FeiniuUrl.coverUrl(apiBase, coverId ?: albumCoverId ?: firstArtistCoverId, COVER_SIZE),
            // 播放解析链（PlayerViewModel.resolveStreamUrl → getSongsByIds）依赖本字段，解析期即填充
            streamUrl = FeiniuUrl.streamUrl(apiBase, guid),
            durationMs = durationMs,
            trackNumber = fallbackTrackNumber,
            // 同 [BITRATE_UNVERIFIED]：单位未确认，不填
            bitrate = BITRATE_UNVERIFIED
        )
    }

    // ==================== 内部：工具 ====================

    /** 去掉 `feiniu_` 前缀 */
    private fun stripPrefix(id: String): String =
        if (id.startsWith(ID_PREFIX)) id.substring(ID_PREFIX.length) else id

    /**
     * 安装级稳定的 deviceId。
     *
     * 持久化到 SharedPreferences，避免每次连接都生成新设备 ID
     * 导致服务端设备列表膨胀。拿不到 Context 时退化为进程内稳定值。
     */
    @Volatile
    private var memoryDeviceId: String = PREFS_UNSET

    @Synchronized
    private fun deviceId(): String {
        val cached = memoryDeviceId
        if (cached.isNotEmpty()) return cached
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var id = prefs?.getString(PREF_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs?.edit()?.putString(PREF_DEVICE_ID, id)?.apply()
        }
        memoryDeviceId = id
        return id
    }

    /** SHA-256 小写 hex（与参考项目 `PasswordHash.fromPlaintext` 一致） */
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * suspend 版本的 runCatching。
     *
     * 标准库 `runCatching` 的 block 不是 suspend，内部无法调用挂起函数
     * （本适配器的取数流程全是 suspend），故自定义一个等价物。
     */
    private suspend fun <T> runCatchingSuspend(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (t: Throwable) {
            Result.failure(t)
        }

    /** 安全取字符串：非基本类型或异常时返回 null（避免 Gson 抛 IllegalStateException） */
    private fun str(obj: JsonObject?, name: String): String? {
        val element = obj?.get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return try {
            val value = element.asString
            if (value == "null") null else value
        } catch (e: Exception) {
            null
        }
    }

    /** 定长 LRU 缓存（访问序）；所有访问需外部 synchronized */
    private class LruCache<K, V>(maxSize: Int) : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        private val limit = maxSize
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > limit
    }
}
