package com.nasmusic.tv.data.model

/**
 * 歌词来源
 */
enum class LyricsSource(val displayName: String) {
    EMBEDDED("内嵌歌词"),
    LOCAL_FILE("本地歌词"),
    NETWORK("在线歌词"),
    CACHED("缓存")
}
