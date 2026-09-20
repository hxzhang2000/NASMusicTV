package com.nasmusic.tv.backend.playlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * 本应用自导 JSON v2 解析器（`formatVersion` = "nasmusic-playlist-v2"）。
 *
 * 设计：docs/archive/playlist-import-feature-plan.md §4.1.1 / §4.6
 * 写回时按 formatVersion 路由到本解析器，与网易云歌单解析路径隔离，避免误识别。
 */
class JsonPlaylistParser : PlaylistParser, PlaylistNameProvider {

    companion object {
        const val FORMAT_VERSION = "nasmusic-playlist-v2"
    }

    /** 最近一次 parse 提取的歌单名 hint（`playlistName` 字段，§4.2 取名兜底链）。 */
    override var innerName: String? = null
        private set

    /** 序列化导出格式（§4.6）。字段缺失时按默认值兜底，保证循环导入安全。 */
    fun serialize(
        playlistName: String,
        songs: List<SongExportEntry>,
        createdAt: Long = System.currentTimeMillis(),
    ): String {
        val root = JsonObject()
        root.addProperty("formatVersion", FORMAT_VERSION)
        root.addProperty("playlistName", playlistName)
        root.addProperty("createdAt", createdAt)
        val arr = JsonArray()
        for (s in songs) {
            val o = JsonObject()
            o.addProperty("title", s.title)
            if (s.artist.isNotBlank()) o.addProperty("artist", s.artist)
            if (!s.album.isNullOrBlank()) o.addProperty("album", s.album)
            s.durationSec?.let { o.addProperty("durationSec", it) }
            if (s.isNetworkSong) {
                o.addProperty("isNetworkSong", true)
                s.networkSource?.let { o.addProperty("networkSource", it) }
                s.networkId?.let { o.addProperty("networkId", it) }
            }
            arr.add(o)
        }
        root.add("songs", arr)
        return root.toString()
    }

    override fun canParse(headBytes: ByteArray, fileName: String): Boolean {
        if (headBytes.isEmpty() || !fileName.endsWith(".json", ignoreCase = true)) return false
        val head = PlaylistParsers.decode(headBytes)
        return head.contains("\"formatVersion\"") && head.contains(FORMAT_VERSION)
    }

    override fun parse(text: String, fileName: String): List<RawSongEntry> {
        innerName = null
        val root: JsonObject = try {
            JsonParser.parseString(text).asJsonObject
        } catch (e: JsonParseException) {
            return emptyList()
        }
        val version = root.get("formatVersion")?.takeIf { it.isJsonPrimitive }?.asString
        if (version != FORMAT_VERSION) return emptyList()
        // §4.2 取名兜底：JSON v2 内嵌 playlistName
        root.get("playlistName")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { innerName = it }

        val entries = mutableListOf<RawSongEntry>()
        val songs = root.getAsJsonArray("songs") ?: return emptyList()
        var index = 0
        for (el in songs) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val title = obj.get("title")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (title.isEmpty()) continue
            entries += RawSongEntry(
                title = title,
                artist = obj.get("artist")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty(),
                album = obj.get("album")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() },
                durationSec = obj.get("durationSec")?.takeIf { it.isJsonPrimitive }?.asInt,
                originalIndex = index++,
            )
        }
        return entries
    }
}

/** 导出条目（§4.6 序列化输入）。 */
data class SongExportEntry(
    val title: String,
    val artist: String = "",
    val album: String? = null,
    val durationSec: Int? = null,
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,
    val networkId: String? = null,
)