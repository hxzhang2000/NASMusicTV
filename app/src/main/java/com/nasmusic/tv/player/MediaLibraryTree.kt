package com.nasmusic.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.download.db.DownloadSongEntity
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android Auto / Wear OS 媒体浏览树
 *
 * ## 结构
 *
 * ```
 * root
 * ├── queue            当前播放（PlayerManager 队列快照）
 * ├── download         离线下载（本地文件，无网络依赖）
 * ├── fav              收藏（网络音乐收藏）
 * └── pl               歌单（NAS 后端，需内网可达）
 *     └── pl/{id}      某歌单内歌曲
 * ```
 *
 * 根菜单固定 4 项，贴合 Android Auto 的 root hints 上限（默认 4）。
 * 前 3 项**不依赖家庭内网**，保证车机场景下始终有内容可播——
 * 开车时手机通常在移动数据上，NAS 内网地址不可达（详见开发方案 §6）。
 *
 * ## 关键设计：树里不放 URI
 *
 * 叶子节点只带稳定的 `mediaId`，**不调用 `setUri()`**。
 * 原因：
 * - 网络歌曲的 `streamUrl` 按设计不持久化（见 [Song] 注释），播放时才解析
 * - NAS 流地址通常带 token，会过期
 *
 * URI 解析统一由 `PlaybackService` 的 `onAddMediaItems` / `onSetMediaItems` 负责
 * （Media3 官方设计的解析入口）。这同时修复了历史缺陷：旧版 `findInQueue()`
 * 漏了 `setUri()`，会让 `onAddMediaItems` 的默认实现抛 `UnsupportedOperationException`。
 *
 * ## 线程模型
 *
 * - [getLibraryRoot] / [getItem] 是**同步**的，只读内存（Media3 要求快速返回）
 * - [loadChildren] 是 **suspend** 的，可做网络 IO（NAS 歌单等），由调用方异步等待
 *
 * ## 缓存
 *
 * 构造出的 [Song] 会写入 [BrowseCache]，供播放入口在会话线程上同步读取，
 * 避免在 Media3 回调里做网络 IO。
 */
class MediaLibraryTree(
    private val context: Context,
    private val browseCache: BrowseCache
) {

    companion object {
        private const val TAG = "MediaLibraryTree"

        // ── 节点 ID ──
        const val ROOT_ID = "root"
        const val QUEUE_ID = "queue"
        const val DOWNLOAD_ID = "download"
        const val FAVORITE_ID = "fav"
        const val PLAYLIST_ID = "pl"

        private const val PLAYLIST_PREFIX = "pl/"

        /** 单个节点最多返回的子项数（防止超长列表拖垮车机 UI） */
        private const val MAX_CHILDREN = 500

        /** root hints 缺失时的默认根菜单上限 */
        const val DEFAULT_ROOT_LIMIT = 4
    }

    private val app: NasMusicApp?
        get() = context.applicationContext as? NasMusicApp

    /**
     * 根菜单上限，由 `onGetLibraryRoot` 从 root hints 读取后写入。
     * 默认 4（Android Auto / AAOS 官方默认值）。
     */
    @Volatile
    var rootChildrenLimit: Int = DEFAULT_ROOT_LIMIT

    /**
     * 歌单标题缓存（mediaId → 名称）。
     *
     * [getItem] 是同步的、不能查网络，而 Media3 可能单独请求某个 `pl/{id}` 节点，
     * 因此需要把 [loadChildren] 时拿到的歌单名缓存下来。
     */
    private val playlistTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ────────────────────────────────────────────────────────────
    // 同步接口（只读内存，必须快速返回）
    // ────────────────────────────────────────────────────────────

    /** 获取媒体库根节点 */
    fun getLibraryRoot(): MediaItem = browseItem(ROOT_ID, "NAS Music TV")

    /**
     * 获取单个节点。
     *
     * 只处理「目录节点」与「已在 [BrowseCache] 中的歌曲」；
     * 不触发任何网络请求——需要网络的内容由 [loadChildren] 负责。
     */
    fun getItem(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> getLibraryRoot()
        mediaId == QUEUE_ID -> browseItem(QUEUE_ID, "当前播放")
        mediaId == DOWNLOAD_ID -> browseItem(DOWNLOAD_ID, "离线下载")
        mediaId == FAVORITE_ID -> browseItem(FAVORITE_ID, "收藏")
        mediaId == PLAYLIST_ID -> browseItem(PLAYLIST_ID, "歌单")
        mediaId.startsWith(PLAYLIST_PREFIX) ->
            browseItem(mediaId, playlistTitles[mediaId] ?: "歌单")
        mediaId.startsWith(BrowseCache.SONG_PREFIX) ->
            browseCache.songOf(mediaId)?.let { songToItem(it) }
        else -> null
    }

    // ────────────────────────────────────────────────────────────
    // 异步接口（可做网络 IO）
    // ────────────────────────────────────────────────────────────

    /**
     * 加载指定节点的子项。
     *
     * ⚠️ 调用方必须异步等待：Android Auto 不支持分页，本方法会一次性返回全部子项。
     *
     * @param parentId 父节点 ID
     * @return 子项列表；未知节点或加载失败返回空列表（不抛异常，避免车机端卡死）
     */
    suspend fun loadChildren(parentId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            when {
                parentId == ROOT_ID -> loadRootChildren()
                parentId == QUEUE_ID -> loadQueueChildren()
                parentId == DOWNLOAD_ID -> loadDownloadChildren()
                parentId == FAVORITE_ID -> loadFavoriteChildren()
                parentId == PLAYLIST_ID -> loadPlaylistChildren()
                parentId.startsWith(PLAYLIST_PREFIX) ->
                    loadPlaylistSongs(parentId.removePrefix(PLAYLIST_PREFIX))
                else -> emptyList()
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "loadChildren($parentId) failed: ${e.message}", e)
            emptyList()
        }
    }

    /** 根菜单：固定 4 项可浏览节点，按 root hints 上限裁剪 */
    private fun loadRootChildren(): List<MediaItem> {
        val all = listOf(
            browseItem(QUEUE_ID, "当前播放"),
            browseItem(DOWNLOAD_ID, "离线下载"),
            browseItem(FAVORITE_ID, "收藏"),
            browseItem(PLAYLIST_ID, "歌单")
        )
        val limit = rootChildrenLimit.coerceIn(1, all.size)
        return all.take(limit)
    }

    /** 当前播放队列 */
    private fun loadQueueChildren(): List<MediaItem> {
        val songs = app?.playerManager?.getQueueSnapshot().orEmpty()
        return songs.take(MAX_CHILDREN).map { songToItem(it) }
    }

    /** 离线下载（本地文件，无网络依赖） */
    private suspend fun loadDownloadChildren(): List<MediaItem> {
        val entities = app?.downloadRepository?.getCompleted().orEmpty()
        return entities.take(MAX_CHILDREN).mapNotNull { downloadToSong(it)?.let(::songToItem) }
    }

    /** 收藏（网络音乐） */
    private suspend fun loadFavoriteChildren(): List<MediaItem> {
        val favorites = app?.appPreferences?.getNetworkFavorites().orEmpty()
        return favorites.take(MAX_CHILDREN).map { songToItem(favoriteToSong(it)) }
    }

    /** 歌单列表（NAS 后端，需内网可达） */
    private suspend fun loadPlaylistChildren(): List<MediaItem> {
        val adapter = app?.backendRegistry?.getAdapter() ?: run {
            AppLog.d(TAG, "loadPlaylistChildren: backend not connected")
            return emptyList()
        }
        val playlists = adapter.getPlaylists()
        return playlists.take(MAX_CHILDREN).map { playlistToItem(it) }
    }

    /** 某歌单内的歌曲 */
    private suspend fun loadPlaylistSongs(playlistId: String): List<MediaItem> {
        val adapter = app?.backendRegistry?.getAdapter() ?: return emptyList()
        val songs = adapter.getPlaylistSongs(playlistId)
        return songs.take(MAX_CHILDREN).map { songToItem(it) }
    }

    // ────────────────────────────────────────────────────────────
    // MediaItem 构造
    // ────────────────────────────────────────────────────────────

    /** 可浏览目录节点（无 URI，不可播放） */
    private fun browseItem(mediaId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle(title)
                    .build()
            )
            .build()

    /**
     * 歌单节点。
     *
     * `pl/{id}` 既**可浏览**（进入看歌曲）也**可播放**（直接播整个歌单）——
     * Media3 / Android Auto 支持这种「既可浏览又可播放」的节点。
     */
    private fun playlistToItem(playlist: Playlist): MediaItem {
        val mediaId = PLAYLIST_PREFIX + playlist.id
        playlistTitles[mediaId] = playlist.name
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setTitle(playlist.name)
                    .setSubtitle(
                        if (playlist.songCount > 0) "${playlist.songCount} 首" else null
                    )
                    .setArtworkUri(
                        playlist.coverUrls.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Uri.parse(it) }
                    )
                    .build()
            )
            .build()
    }

    /**
     * 歌曲叶子节点（可播放，**不设 URI**）。
     *
     * 同时把 [song] 写入 [BrowseCache]，供播放入口解析 URI 时使用。
     */
    private fun songToItem(song: Song): MediaItem {
        browseCache.put(song)
        return MediaItem.Builder()
            .setMediaId(BrowseCache.SONG_PREFIX + song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setTitle(song.title)
                    .setArtist(song.artist.ifBlank { null })
                    .setAlbumTitle(song.album.ifBlank { null })
                    .setArtworkUri(
                        song.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                    )
                    .build()
            )
            .build()
    }

    // ────────────────────────────────────────────────────────────
    // 模型转换
    // ────────────────────────────────────────────────────────────

    /**
     * 网络收藏项 → [Song]。
     *
     * `streamUrl` 留空：网络歌曲播放链接有时效性，由播放入口实时解析
     * （见 [NetworkFavoriteItem] 注释）。
     */
    private fun favoriteToSong(item: NetworkFavoriteItem): Song = Song(
        id = item.songId,
        title = item.title,
        artist = item.artist,
        album = item.album,
        coverUrl = item.coverUrl,
        isNetworkSong = true,
        networkSource = item.networkSource,
        networkId = item.networkId
    )

    /**
     * 下载实体 → [Song]。
     *
     * 已完成下载的音频是本地文件，直接给出 `file://` URI（ExoPlayer 可直接播放），
     * 无需再解析——这是车机场景下最可靠的一类内容。
     */
    private fun downloadToSong(entity: DownloadSongEntity): Song? {
        val path = entity.audioPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return Song(
            id = entity.songId.ifBlank { entity.songKey },
            title = entity.title,
            artist = entity.artist,
            album = entity.album,
            coverUrl = entity.coverPath,
            streamUrl = Uri.fromFile(file).toString(),
            durationMs = entity.durationMs,
            bitrate = entity.bitrate,
            path = path,
            isLocalSong = true,
            networkSource = entity.networkSource,
            storageType = StorageType.DOWNLOAD.name
        )
    }
}
