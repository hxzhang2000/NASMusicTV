package com.nasmusic.tv.data.model

/**
 * 互联网电台（radio-browser.info）
 *
 * 字段对齐 radio-browser JSON 响应（/json/stations/search）。
 * stationuuid 跨服务器稳定，收藏/播放均使用它作为稳定标识。
 */
data class RadioStation(
    val uuid: String,
    val name: String,
    val urlResolved: String,
    val faviconUrl: String? = null,
    val countryCode: String = "",
    val country: String = "",
    val tags: List<String> = emptyList(),
    val votes: Int = 0,
    val bitrate: Int = 0,
    val codec: String = ""
) {
    /**
     * 电台 → 歌曲映射（复用播放链路）
     *
     * - streamUrl 为直链，无需 resolvePlayUrl
     * - durationMs = Long.MAX_VALUE 表示无限流（直播态），UI 层据 networkSource=="radio" 显示"直播"
     */
    fun toSong(): Song = Song(
        id = "radio_${uuid}",
        title = name,
        artist = if (country.isNotBlank()) "电台 · $country" else "电台",
        coverUrl = faviconUrl?.takeIf { it.isNotBlank() },
        streamUrl = urlResolved,
        durationMs = Long.MAX_VALUE,
        isNetworkSong = true,
        networkSource = "radio",
        networkId = uuid
    )

    companion object {
        const val SOURCE_ID = "radio"
    }
}

/**
 * 是否为电台歌曲（供播放链路 / UI 判断直播态）
 */
fun String?.isRadioSong(): Boolean = this == RadioStation.SOURCE_ID