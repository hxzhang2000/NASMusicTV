package com.nasmusic.tv.backend.download

import android.content.Context
import com.nasmusic.tv.backend.download.db.DownloadDatabase
import com.nasmusic.tv.backend.download.db.DownloadSongDao
import com.nasmusic.tv.backend.download.db.DownloadSongEntity
import com.nasmusic.tv.backend.download.db.DownloadStatus
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载索引 CRUD + 统计封装
 *
 * - 不依赖 [com.nasmusic.tv.backend.local.LocalMusicRepository]（下载索引独立建库，见 §7.1）
 * - 通过 [DownloadDatabase.get] 单例持有 DB 实例
 * - 提供 [reindexFromDisk] 兜底：DB 损坏或文件已存在但无索引时重建
 *
 * 使用约定：
 * - 进入 [executeDownload] 前调 [get]/[findCompletedByDedupe] 判定是否已下载
 * - 下载开始时 [upsert] 一条 DOWNLOADING 记录
 * - 下载成功 [upsert] COMPLETED 记录，失败 [updateStatus] FAILED
 */
class DownloadRepository(
    private val context: Context,
    private val dao: DownloadSongDao = DownloadDatabase.get(context).downloadSongDao()
) {
    companion object {
        private const val TAG = "DownloadRepository"
        val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "opus", "wav")
    }

    suspend fun get(key: String): DownloadSongEntity? = withContext(Dispatchers.IO) { dao.get(key) }

    suspend fun findCompletedByDedupe(dedupe: String): DownloadSongEntity? =
        withContext(Dispatchers.IO) { dao.findCompletedByDedupe(dedupe) }

    suspend fun getCompleted(): List<DownloadSongEntity> =
        withContext(Dispatchers.IO) { dao.getCompleted() }

    fun observeCompleted(): Flow<List<DownloadSongEntity>> = dao.observeCompleted()

    suspend fun countAutoCompleted(): Int = withContext(Dispatchers.IO) { dao.countAutoCompleted() }
    suspend fun countCompleted(): Int = withContext(Dispatchers.IO) { dao.countCompleted() }
    suspend fun sumCompletedSize(): Long = withContext(Dispatchers.IO) { dao.sumCompletedSize() }
    suspend fun countLyrics(): Int = withContext(Dispatchers.IO) { dao.countLyrics() }
    suspend fun countCovers(): Int = withContext(Dispatchers.IO) { dao.countCovers() }

    /** 聚合下载统计信息（供设置页一次性获取） */
    suspend fun getDownloadStats(): DownloadStats = withContext(Dispatchers.IO) {
        DownloadStats(
            songCount = dao.countCompleted(),
            lyricsCount = dao.countLyrics(),
            coverCount = dao.countCovers(),
            totalBytes = dao.sumCompletedSize()
        )
    }

    suspend fun upsert(e: DownloadSongEntity) = withContext(Dispatchers.IO) { dao.upsert(e) }

    suspend fun updateStatus(key: String, status: DownloadStatus, progress: Int, err: String? = null) =
        withContext(Dispatchers.IO) { dao.updateStatus(key, status.name, progress, err) }

    suspend fun updateProgress(key: String, progress: Int) =
        withContext(Dispatchers.IO) { dao.updateProgress(key, progress) }

    suspend fun resetUnfinished(err: String) = withContext(Dispatchers.IO) { dao.resetUnfinished(err) }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) { dao.delete(key) }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    suspend fun getUnfinished(): List<DownloadSongEntity> =
        withContext(Dispatchers.IO) { dao.getUnfinished() }

    /** 暴露底层 DAO（供本地曲库合并下载目录时注入） */
    fun getDao(): DownloadSongDao = dao

    /**
     * 从磁盘重建索引兜底（DB 损坏或用户手动添加文件到下载目录后用）。
     *
     * 不删除现有 COMPLETED 记录，仅扫描 Music/ 目录下未在索引中的音频文件，
     * 按路径解析出歌手/专辑/标题后插入。
     */
    suspend fun reindexFromDisk(rootProvider: () -> File): Int =
        withContext(Dispatchers.IO) {
            val root = rootProvider()
            if (!root.exists()) return@withContext 0
            val existing = dao.getCompleted().map { it.audioPath }.toSet()
            var added = 0
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
                .filter { it.absolutePath !in existing }
                .forEach { file ->
                    val rel = file.relativeTo(root).path
                    val parts = rel.split(File.separator)
                    val (artist, album, title) = when {
                        parts.size >= 3 -> Triple(parts[0], parts[1], file.nameWithoutExtension)
                        parts.size == 2 -> Triple(parts[0], "单曲", file.nameWithoutExtension)
                        else -> Triple("未知歌手", "单曲", file.nameWithoutExtension)
                    }
                    val songKey = "local_${com.nasmusic.tv.util.HashUtils.stablePathHash64(file.absolutePath)}"
                    val dedupeKey = "${title.lowercase().replace(Regex("\\s+"), "")}|${artist.lowercase().replace(Regex("\\s+"), "")}"
                    dao.upsert(
                        DownloadSongEntity(
                            songKey = songKey,
                            dedupeKey = dedupeKey,
                            songId = songKey,
                            title = title,
                            artist = artist,
                            album = album,
                            sourceType = "DOWNLOAD",
                            audioPath = file.absolutePath,
                            embedded = false,
                            fileSize = file.length(),
                            containerExt = file.extension.lowercase(),
                            status = DownloadStatus.COMPLETED.name,
                            progress = 100,
                            completedAt = file.lastModified()
                        )
                    )
                    added++
                }
            AppLog.i(TAG, "reindexFromDisk: added $added songs from $root")
            added
        }

    /** 将 DB Entity 映射为 UI State（[DownloadState]） */
    fun toUiState(entity: DownloadSongEntity?): DownloadState = when (entity?.status) {
        null -> DownloadState.Idle
        DownloadStatus.PENDING.name -> DownloadState.Queued
        DownloadStatus.DOWNLOADING.name -> DownloadState.Downloading(entity.progress)
        DownloadStatus.COMPLETED.name -> DownloadState.Completed(entity.audioPath ?: "")
        DownloadStatus.FAILED.name -> DownloadState.Failed(entity.errorMsg)
        DownloadStatus.DELETED.name -> DownloadState.Idle
        else -> DownloadState.Idle
    }

    /** 批量构建 UI 状态 Map（供 UI 列表外层 collect 一次得到） */
    fun toUiStateMap(completed: List<DownloadSongEntity>): Map<String, DownloadState> =
        completed.associate { it.songKey to DownloadState.Completed(it.audioPath ?: "") }

    /**
     * 根据 [Song] 构造一条 DOWNLOADING 初始记录（供 SongDownloadManager 在开始下载时调用）
     */
    fun newDownloadingEntity(
        song: Song,
        sourceType: String,
        tmpPath: String,
        autoDownloaded: Boolean
    ): DownloadSongEntity = DownloadSongEntity(
        songKey = song.downloadKey,
        dedupeKey = song.dedupeKey,
        songId = song.id,
        title = song.title,
        artist = song.artist,
        album = song.album,
        sourceType = sourceType,
        networkSource = song.networkSource,
        tmpPath = tmpPath,
        embedded = false,
        durationMs = song.durationMs,
        bitrate = song.bitrate,
        status = DownloadStatus.DOWNLOADING.name,
        progress = 0,
        autoDownloaded = autoDownloaded,
        createdAt = System.currentTimeMillis()
    )
}

/** 下载统计信息（供设置页展示） */
data class DownloadStats(
    val songCount: Int = 0,
    val lyricsCount: Int = 0,
    val coverCount: Int = 0,
    val totalBytes: Long = 0L
)
