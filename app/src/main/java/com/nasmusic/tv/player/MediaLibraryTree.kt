package com.nasmusic.tv.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.db.DownloadSongEntity
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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

        /**
         * 根菜单图标的光栅化尺寸（px）。
         *
         * 矢量源是 24dp，车机 tab 实际显示尺寸很小（约 24–40dp），256px 已远超所需。
         * 之所以不取更小值：`CoilBitmapLoader.decodeBitmap()` 固定用 `.size(512, 512)`
         * 请求（见 `CoilBitmapLoader.kt:53`），Coil 默认会**放大**到目标尺寸——
         * 源图过小会被插值放大成模糊图。256px 把放大倍数压到 2x，观感可接受；
         * 同时纯色平面图形的 PNG 仍只有几 KB（4 个图标合计 < 20KB，可安全内联进 Binder）。
         */
        private const val ICON_RASTER_PX = 256

        /**
         * 搜索结果上限（阶段 3）。
         *
         * 车机端搜索列表很短，且搜索要跨两个源合并，给个上限防止超长列表拖垮车机 UI
         * （与 [MAX_CHILDREN] 同源理由）。
         */
        private const val MAX_SEARCH_RESULTS = 50
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
    // 根菜单 tab 图标（阶段 2.5）
    // ────────────────────────────────────────────────────────────

    /**
     * 光栅化结果缓存（drawable resId → PNG 字节）。
     *
     * 值可为 null（表示该图标渲染失败），因此用 `HashMap` + `containsKey` 而不是
     * `getOrPut`（后者无法区分「未缓存」与「缓存了 null」，会反复重试失败路径）。
     */
    private val iconDataCache = HashMap<Int, ByteArray?>()

    /**
     * 根菜单节点 → 图标资源。
     *
     * 只覆盖根菜单 4 项：官方规范要求 **每个 tab 项都配单色（最好白色）图标**。
     * 子级节点（歌单内歌曲、队列歌曲等）用的是专辑封面，不走这里。
     */
    private fun rootIconOf(mediaId: String): Int? = when (mediaId) {
        QUEUE_ID -> R.drawable.ic_auto_queue
        DOWNLOAD_ID -> R.drawable.ic_auto_download
        FAVORITE_ID -> R.drawable.ic_auto_favorite
        PLAYLIST_ID -> R.drawable.ic_auto_playlist
        else -> null
    }

    /**
     * 把矢量图标光栅化为 PNG 字节，供 `MediaMetadata.setArtworkData()` 使用。
     *
     * ## 为什么必须光栅化，不能只给 artworkUri
     *
     * Media3 到 legacy（Android Auto）客户端的图标有两条路（`LegacyConversions.java`）：
     * - `artworkData` → `MediaDescriptionCompat.setIconBitmap()`（源码 `:329`）
     * - `artworkUri`  → `MediaDescriptionCompat.setIconUri()`（源码 `:357`）
     *
     * 而 `artworkUri` 那条路要求消费方能**解码该 URI 指向的内容**。
     * 本项目的图标源是**矢量 XML**，而 `BitmapFactory` **无法解码 VectorDrawable**
     * （`decodeStream`/`decodeResource` 对矢量 XML 返回 null——这是 Android 的已知行为，
     * 必须经 `Resources.getDrawable()` 渲染）。
     * 由于无法确认车机侧的加载实现，**只给 URI 有静默失效的风险**，
     * 因此这里主动渲染成位图走 `artworkData`（确定性最高），
     * 同时仍设置 `artworkUri` 作为次选（两条路都给，成本为零）。
     *
     * 结果按 resId 缓存——4 个图标只渲染一次。
     */
    private fun rasterizeIcon(resId: Int): ByteArray? = synchronized(iconDataCache) {
        if (iconDataCache.containsKey(resId)) return@synchronized iconDataCache[resId]

        val bytes = try {
            // ContextCompat.getDrawable：与项目既有写法一致，且避免 lint 的
            // UseCompatLoadingForDrawables 告警（直接用 Context.getDrawable 会新增 1 条）
            val drawable = ContextCompat.getDrawable(context, resId)
            if (drawable == null) {
                AppLog.w(TAG, "rasterizeIcon: drawable 0x${resId.toString(16)} is null")
                null
            } else {
                // toBitmap 是 androidx.core.graphics.drawable 的扩展（项目内已有先例，
                // 见 CoilBitmapLoader.kt:40）；显式给尺寸，避免依赖矢量图的 intrinsic 值
                val bitmap = drawable.toBitmap(ICON_RASTER_PX, ICON_RASTER_PX)
                val png = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                bitmap.recycle()
                png
            }
        } catch (e: Exception) {
            // 图标渲染失败不应影响内容树——降级为「无图标」
            AppLog.w(TAG, "rasterizeIcon(0x${resId.toString(16)}) failed", e)
            null
        }

        iconDataCache[resId] = bytes
        bytes
    }

    /** `android.resource://<pkg>/drawable/<name>`，作为 iconUri 的次选 */
    private fun iconResourceUri(resId: Int): Uri? = runCatching {
        val name = context.resources.getResourceEntryName(resId)
        "android.resource://${context.packageName}/drawable/$name".toUri()
    }.getOrNull()

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
        mediaId == QUEUE_ID -> browseItem(QUEUE_ID, "当前播放", rootIconOf(QUEUE_ID))
        mediaId == DOWNLOAD_ID -> browseItem(DOWNLOAD_ID, "离线下载", rootIconOf(DOWNLOAD_ID))
        mediaId == FAVORITE_ID -> browseItem(FAVORITE_ID, "收藏", rootIconOf(FAVORITE_ID))
        mediaId == PLAYLIST_ID -> browseItem(PLAYLIST_ID, "歌单", rootIconOf(PLAYLIST_ID))
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

    /** 根菜单：固定 4 项可浏览节点（各配单色白色图标），按 root hints 上限裁剪 */
    private fun loadRootChildren(): List<MediaItem> {
        val all = listOf(
            browseItem(QUEUE_ID, "当前播放", rootIconOf(QUEUE_ID)),
            browseItem(DOWNLOAD_ID, "离线下载", rootIconOf(DOWNLOAD_ID)),
            browseItem(FAVORITE_ID, "收藏", rootIconOf(FAVORITE_ID)),
            browseItem(PLAYLIST_ID, "歌单", rootIconOf(PLAYLIST_ID))
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
    // 搜索（阶段 3）
    // ────────────────────────────────────────────────────────────

    /**
     * 搜索歌曲。
     *
     * ## 搜索源策略：优先公网可用，NAS 尽力而为
     *
     * 与根菜单「前 3 项不依赖内网」同源理由 —— 开车时手机多在移动数据上、家庭内网不可达，因此：
     * 1. **网络音乐**（Meting，走公网）—— 始终尝试
     * 2. **NAS 后端**（`BackendAdapter.searchSongs`，需内网）—— 仅在后端已连接时尝试，
     *    失败 / 不可达**静默跳过**，不影响网络音乐的结果
     *
     * 两源结果按 `song.id` 去重后合并（同一首歌在两个源里 id 不同，属预期 ——
     * 让用户看到两个来源、各自都能播）。
     *
     * ## 返回值
     *
     * 可播放的**叶子**节点（`song/{id}`）。构造统一走 [songToItem]，因此：
     * - `mediaId` 非空，且 `isBrowsable` / `isPlayable` **显式设置** ——
     *   `LibraryResult.ofItemList` 内部的 `verifyMediaItem` 强制要求这两项非 null，
     *   缺失会**直接抛异常**（源码 `LibraryResult.java:257-261`）
     * - **不设 URI**，但会写入 [BrowseCache]，由播放入口统一解析
     *   （网络歌曲需实时解析；NAS 歌曲的 `streamUrl` 由适配器填好，见 `jsonObjectToSong`）
     *
     * @return 搜索结果；查询为空或两源都失败时返回**空列表**（不抛异常）。
     *   ⚠️ 调用方**不要**把空列表直接交给 Media3 —— 见 `PlaybackService.onSetMediaItems` 的处理说明
     */
    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        // 1) 网络音乐（公网，始终尝试）
        val networkSongs = try {
            app?.networkMusicManager?.search(q).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "search(network) failed: ${e.message}", e)
            emptyList()
        }

        // 2) NAS 后端（内网，尽力而为；未连接或不可达时静默跳过）
        val adapter = app?.backendRegistry?.getAdapter()
        val nasSongs = if (adapter == null) {
            emptyList()
        } else {
            try {
                adapter.searchSongs(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 用 d 级：内网不可达在车机场景下是**常态**，不是异常
                AppLog.d(TAG, "search(nas) skipped: ${e.message}")
                emptyList()
            }
        }

        // 合并规则见 [mergeSearchResults]（纯函数，有单测覆盖）
        val result = mergeSearchResults(networkSongs, nasSongs, MAX_SEARCH_RESULTS)
            .map { songToItem(it) }
        AppLog.d(TAG, "search('$q'): ${result.size} results")
        result
    }

    // ────────────────────────────────────────────────────────────
    // MediaItem 构造
    // ────────────────────────────────────────────────────────────

    /**
     * 可浏览目录节点（无 URI，不可播放）。
     *
     * @param iconRes 单色白色矢量图标。**只有根菜单 4 项需要**（车机把它们渲染为 tab，
     *   官方规范要求每个 tab 项配单色图标）；传 null 表示无图标。
     *   图标同时以两种形式下发，见 [rasterizeIcon] 的说明。
     */
    private fun browseItem(mediaId: String, title: String, iconRes: Int? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setTitle(title)

        if (iconRes != null) {
            // 主路径：光栅化后的 PNG → MediaDescriptionCompat.setIconBitmap()
            rasterizeIcon(iconRes)?.let {
                metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            // 次选：android.resource:// URI → MediaDescriptionCompat.setIconUri()
            iconResourceUri(iconRes)?.let { metadata.setArtworkUri(it) }
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata.build())
            .build()
    }

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
                            ?.toUri()
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
                        song.coverUrl?.takeIf { it.isNotBlank() }?.toUri()
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

/**
 * 搜索结果合并（阶段 3，Android Auto 车机搜索）—— **纯函数**，有 JVM 单测覆盖
 * （`SearchMergeTest`）。
 *
 * ## 为什么抽成顶层函数
 * 与 `PlayerManager.kt` 的 `computeQueueRemoval`（见 `QueueRemovalTest`）同一做法：
 * 合并规则不依赖 Context / 网络 / Media3，抽出来就能在单测里穷举边界。
 * 留在 [MediaLibraryTree.search] 里则只能靠 DHU / 真车验证，成本高得多。
 *
 * ## 规则
 * - **去重按 `song.id`，先出现的赢** —— 调用方按「网络音乐 → NAS」顺序传入，
 *   故网络结果优先级更高（车机场景下公网可达性优于家庭内网，见 `docs/android-auto-plan.md` §6）
 * - **保序**：`LinkedHashMap` 保持插入顺序，**不排序**（顺序即优先级）
 * - **先合并去重、再截断到 [limit]** —— 顺序反了会把重复项算进配额、白占名额
 *
 * ## ⚠️ 不要用 `Map.putIfAbsent` 实现去重
 * 它是 `HashMap#putIfAbsent`，**API 24+ 才有**；本项目 `minSdk 22`、目标电视为
 * Android 5.1.1，调用会直接 `NoSuchMethodError` **崩溃**。
 * 故改用 Kotlin stdlib 的 `MutableMap.getOrPut`（纯 Kotlin 实现，无 API 版本限制）。
 * 该问题由 lint 的 `NewApi` 检查抓出（构建时 `lintDebug` 曾因此报 2 条 error）。
 *
 * @param networkSongs 网络音乐（Meting 等）结果，优先级高
 * @param nasSongs NAS 后端结果，优先级低
 * @param limit 结果上限（生产路径传 `MediaLibraryTree.MAX_SEARCH_RESULTS`）
 */
internal fun mergeSearchResults(
    networkSongs: List<Song>,
    nasSongs: List<Song>,
    limit: Int
): List<Song> {
    val merged = LinkedHashMap<String, Song>(networkSongs.size + nasSongs.size)
    networkSongs.forEach { song -> merged.getOrPut(song.id) { song } }
    nasSongs.forEach { song -> merged.getOrPut(song.id) { song } }
    return merged.values.take(limit)
}
