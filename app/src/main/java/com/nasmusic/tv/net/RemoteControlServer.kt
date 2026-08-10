package com.nasmusic.tv.net

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
    private val port: Int = DEFAULT_PORT
) {

    companion object {
        const val DEFAULT_PORT = 18082
        private const val TAG = "RemoteControlServer"
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
        val impl = Impl(port, callbacks)
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
        private val callbacks: RemoteCallbacks
    ) : NanoHTTPD(port) {

        private val gson = Gson()

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
            val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", CONTROL_PAGE_HTML)
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

        private fun handlePlay(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val index = body.get("index")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing index")
            callbacks.playAt(index)
            return jsonOk()
        }

        private fun handleMove(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val from = body.get("from")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing from")
            val to = body.get("to")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing to")
            callbacks.moveQueueItem(from, to)
            return jsonOk()
        }

        private fun handleRemove(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val index = body.get("index")?.asInt ?: return jsonError(Response.Status.BAD_REQUEST, "missing index")
            callbacks.removeFromQueue(index)
            return jsonOk()
        }

        private fun handleAdd(session: IHTTPSession): Response {
            val body = parseJsonBody(session) ?: return jsonError(Response.Status.BAD_REQUEST, "bad body")
            val songObj = body.getAsJsonObject("song") ?: return jsonError(Response.Status.BAD_REQUEST, "missing song")
            val song = gson.fromJson(songObj, Song::class.java)
            callbacks.addToQueue(song)
            return jsonOk()
        }

        private fun handleSearch(params: Map<String, List<String>>): Response {
            val query = params["q"]?.firstOrNull() ?: return jsonError(Response.Status.BAD_REQUEST, "missing q")
            return try {
                val result = runBlocking { callbacks.search(query) }
                val json = JsonObject().apply {
                    add("nasResults", gson.toJsonTree(result.nasResults.map { it.toLightMap() }))
                    add("networkResults", gson.toJsonTree(result.networkResults.map { it.toLightMap() }))
                }
                jsonResponse(json.toString())
            } catch (e: Exception) {
                AppLog.e(TAG, "Search failed", e)
                jsonError(Response.Status.INTERNAL_ERROR, "search failed")
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
