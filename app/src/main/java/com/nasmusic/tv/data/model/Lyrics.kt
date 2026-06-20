package com.nasmusic.tv.data.model

/**
 * 歌词对象
 */
data class Lyrics(
    val songId: String,
    val lines: List<LyricsLine>,
    val source: LyricsSource,
    val offset: Long = 0L
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * 歌词来源信息
 */
data class LyricsAvailability(
    val backend: Lyrics? = null,
    val network: Lyrics? = null
) {
    val hasBackend: Boolean get() = backend != null
    val hasNetwork: Boolean get() = network != null
}
