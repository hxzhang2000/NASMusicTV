package com.nasmusic.tv.backend.network

import com.google.gson.JsonObject

/**
 * Jamendo API v3.0 DTO（轻量解析辅助）
 *
 * 只映射本期用到的字段；字段缺失/重命名容错（get 判空）。
 */
internal object JamendoModels {

    /**
     * 解析 /tracks/ 响应 results 数组中的单个 track
     */
    fun parseTrack(obj: JsonObject): JamendoTrack? {
        return try {
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return null
            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val artistName = obj.get("artist_name")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val albumName = obj.get("album_name")?.takeIf { !it.isJsonNull }?.asString ?: ""
            // Jamendo 图片字段：image（完整图） > album_image（缩略）
            val image = obj.get("image")?.takeIf { !it.isJsonNull }?.asString
                ?: obj.get("album_image")?.takeIf { !it.isJsonNull }?.asString
            val audio = obj.get("audio")?.takeIf { !it.isJsonNull }?.asString
            val duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val lyrics = obj.get("lyrics")?.takeIf { !it.isJsonNull }?.asString
            JamendoTrack(
                id = id,
                name = name,
                artistName = artistName,
                albumName = albumName,
                image = image?.takeIf { it.startsWith("http") },
                audio = audio?.takeIf { it.startsWith("http") },
                durationMs = duration * 1000,
                lyrics = lyrics
            )
        } catch (e: Exception) {
            null
        }
    }
}

internal data class JamendoTrack(
    val id: Long,
    val name: String,
    val artistName: String,
    val albumName: String,
    val image: String?,
    val audio: String?,
    val durationMs: Long,
    val lyrics: String?
)