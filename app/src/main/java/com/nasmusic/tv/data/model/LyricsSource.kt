package com.nasmusic.tv.data.model

/**
 * 歌词来源
 */
enum class LyricsSource(val displayName: String) {
    EMBEDDED("内嵌歌词"),
    LOCAL_FILE("本地文件"),
    LOCAL_CACHE("本地缓存"),
    NETWORK("在线歌词"),
    SERVER("服务器")
}
