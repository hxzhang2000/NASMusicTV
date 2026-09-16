package com.nasmusic.tv.net

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

/**
 * 手机遥控服务器
 *
 * 在 TV 上启动轻量 HTTP server，手机扫码后浏览器打开控制页，
 * 可查看/调整播放队列、搜索歌曲加入队列。
 *
 * 端口 [DEFAULT_PORT]，URL 固定。
 * 生命周期：按需启动（进入 K 歌/MTV 模式时由 MainViewModel 调用），App 退出时停止。
 * 不再 App 启动常驻，避免 TV 资源受限设备上的无谓常驻开销。
 */
class RemoteControlServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        const val DEFAULT_PORT = 18082
        private const val TAG = "RemoteControlServer"

        /**
         * P2-9（2026-09-16）：并发搜索上限。
         * 每次搜索都会 `runBlocking` 独占一个 NanoHTTPD worker 线程（最长 10s），
         * 手机端连发搜索可把 worker 全部占满，连队列操作等轻量请求都要排队。
         */
        private const val MAX_CONCURRENT_SEARCHES = 2

        /** P2-9：超过该耗时即记为慢查询（用于后续按实测调整上限/超时） */
        private const val SLOW_SEARCH_MS = 3000L
    }

    private var server: Impl? = null
    private var serverUrl: String? = null

    /**
     * 启动服务器
     * @param callbacks 操作回调（由 MainViewModel 实现）
     * @return 服务器 URL，用于生成二维码；null 启动失败
     */
    fun start(callbacks: RemoteCallbacks): String? {
        if (server != null) return serverUrl
        val impl = Impl(port, callbacks, context)
        return try {
            impl.start(30000, false) // 30 秒超时（默认 5 秒在 WiFi 环境下偏短）
            server = impl
            val ip = NetworkUtils.getLocalIpAddress()
            serverUrl = if (ip != null) "http://$ip:$port" else null
            AppLog.i(TAG, "Started on port $port, url=$serverUrl")
            serverUrl
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start on port $port", e)
            null
        }
    }

    fun stop() {
        server?.let {
            try {
                it.stop()
                AppLog.i(TAG, "Stopped")
            } catch (e: Exception) {
                AppLog.w(TAG, "Error stopping", e)
            }
        }
        server = null
        serverUrl = null
    }

    fun getUrl(): String? = serverUrl

    // ---- NanoHTTPD 实现 ----

    private class Impl(
        port: Int,
        private val callbacks: RemoteCallbacks,
        private val context: Context
    ) : NanoHTTPD(port) {

        private val gson = Gson()

        /** P2-9：搜索并发闸门，超出直接 503 快速失败（不排队占住 worker） */
        private val searchSlots = java.util.concurrent.Semaphore(MAX_CONCURRENT_SEARCHES)

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            val params = session.parameters

            val response = when {
                uri == "/" && method == Method.GET -> serveControlPage()
                uri == "/api/queue" && method == Method.GET -> handleGetQueue()
                uri == "/api/queue/play" && method == Method.POST -> handlePlay(session)
                uri == "/api/queue/move" && method == Method.POST -> handleMove(session)
                uri == "/api/queue/remove" && method == Method.POST -> handleRemove(session)
                uri == "/api/queue/add" && method == Method.POST -> handleAdd(session)
                uri == "/api/search" && method == Method.GET -> handleSearch(params)
                uri == "/api/status" && method == Method.GET -> handleStatus()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
            // 强制关闭连接，避免 keep-alive 连接堆积导致后续请求等待
            response.addHeader("Connection", "close")
            return response
        }

        private fun serveControlPage(): Response {
            val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", buildControlPageHtml(context))
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            return response
        }

        private fun handleGetQueue(): Response {
            val queue = callbacks.getQueue()
            val currentIndex = callbacks.getCurrentIndex()
            val isPlaying = callbacks.isPlaying()
            val songs = queue.map { it.toLightMap() }
            val json = JsonObject().apply {
                addProperty("currentIndex", currentIndex)
                addProperty("isPlaying", isPlaying)
                add("songs", gson.toJsonTree(songs))
            }
            return jsonResponse(json.toString())
        }

        /** P3：队列索引越界校验（0 ≤ idx < size），防越界访问导致播放器异常 */
        private fun isValidQueueIndex(index: Int): Boolean {
            return index in 0 until callbacks.getQueue().size
        }

        private fun handlePlay(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val index = body.get("index")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing index")
            if (!isValidQueueIndex(index)) return jsonError(Response.Status.BAD_REQUEST, "index out of range")
            callbacks.playAt(index)
            return jsonOk()
        }

        private fun handleMove(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val from = body.get("from")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing from")
            val to = body.get("to")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing to")
            if (!isValidQueueIndex(from) || !isValidQueueIndex(to)) {
                return jsonError(Response.Status.BAD_REQUEST, "index out of range")
            }
            callbacks.moveQueueItem(from, to)
            return jsonOk()
        }

        private fun handleRemove(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val index = body.get("index")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing index")
            if (!isValidQueueIndex(index)) return jsonError(Response.Status.BAD_REQUEST, "index out of range")
            callbacks.removeFromQueue(index)
            return jsonOk()
        }

        private fun handleAdd(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val songObj = body.getAsJsonObject("song") ?: return jsonError(Response.Status.BAD_REQUEST, "missing song")
            val song = try {
                gson.fromJson(songObj, Song::class.java)
            } catch (e: Exception) {
                null
            } ?: return jsonError(Response.Status.BAD_REQUEST, "invalid song")
            // P1-8 修复（2026-09-16）：反序列化外部 JSON 后做最小校验，阻断任意 URI 注入。
            // 背景：服务器绑定 0.0.0.0，此前任意设备可提交任意 JSON 直接入队；
            // 合法的手机端流程只会带 title + 可选 http(s) 音频链（搜索结果为纯元数据）。
            if (song.title.isBlank()) return jsonError(Response.Status.BAD_REQUEST, "invalid title")
            val uri = song.streamUrl?.takeIf { it.isNotBlank() }
            if (uri != null && !(
                    uri.startsWith("http://") || uri.startsWith("https://") ||
                        uri.startsWith("content://") || uri.startsWith("file://")
                    )
            ) {
                return jsonError(Response.Status.BAD_REQUEST, "unsupported uri scheme")
            }
            callbacks.addToQueue(song)
            return jsonOk()
        }

        private fun handleSearch(params: Map<String, List<String>>): Response {
            val query = params["q"]?.firstOrNull() ?: return jsonError(Response.Status.BAD_REQUEST, "missing q")
            // P2-9 修复（2026-09-16）：见 MAX_CONCURRENT_SEARCHES 注释。
            // ① 并发上限：拿不到槽位立刻 503（快速失败，避免请求在 worker 上排队堆积）；
            // ② 慢查询记录：把实测耗时打出来，作为后续调参依据。
            if (!searchSlots.tryAcquire()) {
                AppLog.w(TAG, "search rejected: concurrent limit($MAX_CONCURRENT_SEARCHES) reached, q=${query.take(50)}")
                return jsonError(Response.Status.SERVICE_UNAVAILABLE, "search busy, retry later")
            }
            val startedAt = System.currentTimeMillis()
            return try {
                // 修复（M-7）：跨源搜索限时 10s——原 runBlocking 无超时，
                // 各搜索端点同时慢响应时会长时间占用 NanoHTTPD worker 线程
                val result = runBlocking { kotlinx.coroutines.withTimeout(10_000) { callbacks.search(query) } }
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed >= SLOW_SEARCH_MS) {
                    AppLog.w(TAG, "slow search: ${elapsed}ms, q=${query.take(50)}")
                } else {
                    AppLog.d(TAG, "search: ${elapsed}ms, q=${query.take(50)}")
                }
                val json = JsonObject().apply {
                    add("nasResults", gson.toJsonTree(result.nasResults.map { it.toLightMap() }))
                    add("networkResults", gson.toJsonTree(result.networkResults.map { it.toLightMap() }))
                }
                jsonResponse(json.toString())
            } catch (e: Exception) {
                AppLog.e(TAG, "Search failed", e)
                jsonError(Response.Status.INTERNAL_ERROR, "search failed")
            } finally {
                searchSlots.release()
            }
        }

        private fun handleStatus(): Response {
            val json = JsonObject().apply {
                addProperty("currentIndex", callbacks.getCurrentIndex())
                addProperty("isPlaying", callbacks.isPlaying())
                addProperty("positionMs", callbacks.getProgressMs())
                addProperty("durationMs", callbacks.getDurationMs())
                callbacks.getQueue().getOrNull(callbacks.getCurrentIndex())?.let {
                    addProperty("title", it.title)
                    addProperty("artist", it.artist)
                }
            }
            return jsonResponse(json.toString())
        }

        private fun parseJsonBody(session: IHTTPSession): JsonObject? {
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val postData = files["postData"] ?: return null
                gson.fromJson(postData, JsonObject::class.java)
            } catch (e: Exception) {
                null
            }
        }

        private fun jsonResponse(json: String): Response {
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json)
        }

        private fun jsonOk(): Response = jsonResponse("""{"ok":true}""")

        private fun jsonError(status: Response.Status, msg: String): Response {
            return newFixedLengthResponse(status, "application/json; charset=UTF-8", """{"ok":false,"error":"$msg"}""")
        }

        /** Song -> 轻量 Map（不含 streamUrl 等内部字段） */
        private fun Song.toLightMap(): Map<String, Any?> = mapOf(
            "id" to id,
            "title" to title,
            "artist" to artist,
            "album" to album,
            "durationMs" to durationMs,
            "isNetworkSong" to isNetworkSong,
            "networkSource" to networkSource,
            "networkId" to networkId
        )
    }
}

/** 回调接口，由 MainViewModel 实现 */
interface RemoteCallbacks {
    fun getQueue(): List<Song>
    fun getCurrentIndex(): Int
    fun isPlaying(): Boolean
    fun getProgressMs(): Long
    fun getDurationMs(): Long
    fun playAt(index: Int)
    fun moveQueueItem(from: Int, to: Int)
    fun removeFromQueue(index: Int)
    fun addToQueue(song: Song)
    suspend fun search(keyword: String): RemoteSearchResult
}

data class RemoteSearchResult(
    val nasResults: List<Song>,
    val networkResults: List<Song>
)
