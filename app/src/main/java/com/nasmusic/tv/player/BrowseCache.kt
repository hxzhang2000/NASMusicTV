package com.nasmusic.tv.player

import com.nasmusic.tv.data.model.Song

/**
 * 浏览树节点缓存：`mediaId` → [Song]
 *
 * ## 为什么需要它
 *
 * Android Auto 的内容树是**异步加载**的（[MediaLibraryTree.loadChildren] 会做网络 IO），
 * 但播放入口 `MediaLibrarySession.Callback.onSetMediaItems` / `onAddMediaItems`
 * 拿到的是控制器传来的 `MediaItem`（只有 mediaId，没有完整歌曲信息）。
 *
 * 这两个回调运行在 Media3 的会话线程上，**不应在其中做网络 IO**（会阻塞会话、拖慢车机响应）。
 * 因此在树加载时把 [Song] 写入本缓存，播放入口直接命中内存读取。
 *
 * ## 容量与淘汰
 *
 * LRU，默认 [DEFAULT_MAX_SIZE] 条。车机浏览是「点一下加载一次」的模式，
 * 2000 条足以覆盖一次出行中反复访问的节点，且内存占用可控（每条约数百字节）。
 *
 * ## mediaId 约定
 *
 * 叶子节点 ID 形如 `song/{songId}`（见 [MediaLibraryTree]）。
 * [songOf] 会**自动剥离** `song/` 前缀，因此传入完整 mediaId 或裸 songId 都能命中。
 *
 * ## 线程安全
 *
 * 读（播放入口，会话线程）与写（树加载，协程 IO 线程）可能并发，故全部访问加锁。
 */
class BrowseCache(private val maxSize: Int = DEFAULT_MAX_SIZE) {

    companion object {
        /** 默认容量 */
        const val DEFAULT_MAX_SIZE = 2000

        /** 叶子节点 ID 前缀（与 MediaLibraryTree 保持一致） */
        const val SONG_PREFIX = "song/"
    }

    /**
     * accessOrder = true 的 LinkedHashMap + removeEldestEntry 构成标准 LRU。
     */
    private val map = object : LinkedHashMap<String, Song>(64, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?): Boolean =
            size > maxSize
    }

    /** 写入单条（以 [Song.id] 为键） */
    fun put(song: Song) {
        synchronized(map) { map[song.id] = song }
    }

    /** 批量写入 */
    fun putAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        synchronized(map) { songs.forEach { map[it.id] = it } }
    }

    /**
     * 按 mediaId 查询歌曲。
     *
     * 兼容两种入参：完整的 `song/{songId}` 或裸 `songId`。
     */
    fun songOf(mediaId: String): Song? {
        if (mediaId.isBlank()) return null
        val id = mediaId.removePrefix(SONG_PREFIX)
        return synchronized(map) { map[id] }
    }

    /** 批量查询（保持输入顺序，查不到的条目被丢弃） */
    fun songsOf(mediaIds: List<String>): List<Song> = mediaIds.mapNotNull { songOf(it) }

    /** 当前条目数（供测试与诊断） */
    val size: Int get() = synchronized(map) { map.size }

    /** 清空（服务销毁时调用，避免持有已失效的歌曲引用） */
    fun clear() {
        synchronized(map) { map.clear() }
    }
}
