package com.nasmusic.tv.backend.download

import android.content.Context
import com.nasmusic.tv.backend.download.db.DownloadSongEntity
import com.nasmusic.tv.backend.download.db.DownloadStatus
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.DownloadSettings
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 下载编排器（见方案 §5）
 *
 * - 串行队列 1 个消费者（手动优先），避免抢占播放带宽
 * - 手动 / 自动双通道入队，重复入队幂等拦截
 * - 每 512KB 更新进度 + 空间复检
 * - 失败重试 ≤3 次（2s / 8s / 30s），404 不重试
 * - 崩溃恢复：清理 .tmp/.part + 重置未完成任务
 *
 * 生命周期由 NasMusicApp 持有（applicationScope），与 UI 通过 [downloadStates] StateFlow 通信。
 */
class SongDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repo: DownloadRepository,
    private val storage: StorageGuard,
    private val paths: DownloadPathBuilder,
    private val resolver: StreamUrlResolver,
    private val tagWriter: MediaTagWriter,
    private val coverWriter: CoverFileWriter,
    private val lyricsProvider: suspend (Song) -> String?,
    private val settings: suspend () -> DownloadSettings,
    private val onNotify: (String) -> Unit,
    /** 下载完成即时入库回调（§7.5.5）：NasMusicApp 接管 → localMusicRepository.upsertDownloaded + 刷新 */
    private val onCompleted: (suspend (entity: DownloadSongEntity) -> Unit)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "SongDownloadManager"
        private const val PROGRESS_STEP = 512 * 1024          // 每 512KB 更新进度
        private const val MAX_RETRY = 3
        private val RETRY_DELAYS = longArrayOf(2000, 8000, 30_000)  // 2s / 8s / 30s
        private val NO_RETRY_CODES = setOf(404, 403)          // 404/403 不重试
        private const val MAX_FILE_SIZE_FALLBACK = 8L * 1024 * 1024  // 8MB 预估兜底
    }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates.asStateFlow()

    private val manualQueue = Channel<Pair<Song, Boolean>>(Channel.UNLIMITED)
    private val autoQueue = Channel<Pair<Song, Boolean>>(Channel.UNLIMITED)

    /** 当前下载协程（loop 所在 coroutine），cancelAll 可取消以中断进行中的下载 */
    @Volatile
    private var currentDownloadJob: Job? = null

    /** 启动串行下载循环，返回的 Job 保存到 [currentDownloadJob] 供 cancelAll 中断 */
    private fun startLoop() {
        currentDownloadJob = scope.launch(Dispatchers.IO) { loop() }
    }

    init {
        startLoop()
    }

    /** 手动 / 自动入队（手动优先：手动队列非空时自动任务不抢占） */
    fun enqueue(song: Song, auto: Boolean) {
        scope.launch(Dispatchers.IO) {
            (if (auto) autoQueue else manualQueue).send(song to auto)
        }
    }

    private suspend fun loop() {
        while (true) {
            // 手动优先：先 drain 手动队列，再 drain 自动队列
            val task = manualQueue.tryReceive().getOrNull()
                ?: autoQueue.receive()
            executeDownload(task.first, task.second)
        }
    }

    /**
     * 实际执行一次下载。
     * 带重试：失败在 [MAX_RETRY] 内按退避重试。
     *
     * 幂等：入口处检查 DB 状态，已 COMPLETED / DOWNLOADING 直接返回，避免重复下载。
     */
    private suspend fun executeDownload(song: Song, auto: Boolean): DownloadResult {
        val key = song.downloadKey

        // 幂等检查：已在下载队列中或已完成 → 不重复下载
        val current = repo.get(key)
        if (current?.status == DownloadStatus.COMPLETED.name) {
            _downloadStates.value = _downloadStates.value +
                (key to DownloadState.Completed(current.audioPath ?: ""))
            return DownloadResult.Already
        }
        if (current?.status == DownloadStatus.DOWNLOADING.name) {
            return DownloadResult.Duplicated
        }

        var attempt = 0
        while (true) {
            attempt++
            _downloadStates.value = _downloadStates.value + (key to DownloadState.Downloading(0))
            val result = try {
                singleAttempt(song, auto, attempt)
            } catch (e: StorageFullException) {
                onNotify("存储空间不足（已预留 100MB），请清理后重试")
                DownloadResult.StorageFull
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "download failed: ${song.title} - ${e.message}", e)
                DownloadResult.Failure(e.message)
            }

            if (result is DownloadResult.Success || result is DownloadResult.Already) return result
            if (result is DownloadResult.QuotaExceeded || result is DownloadResult.Disabled) return result
            if (result is DownloadResult.StorageFull || result is DownloadResult.NotDownloadable) return result

            // 可重试失败：从 reason 中提取 HTTP 状态码，404/403 等不重试
            val reason = (result as? DownloadResult.Failure)?.reason
            val httpCode = reason?.removePrefix("HTTP ")?.toIntOrNull()
            val shouldNotRetry = httpCode != null && httpCode in NO_RETRY_CODES
            if (attempt <= MAX_RETRY && !shouldNotRetry) {
                val delayMs = RETRY_DELAYS.getOrElse(attempt - 1) { RETRY_DELAYS.last() }
                AppLog.d(TAG, "retry $attempt/$MAX_RETRY for ${song.title} in ${delayMs}ms")
                _downloadStates.value = _downloadStates.value + (key to DownloadState.Queued)
                delay(delayMs)
                continue
            }
            // 重试耗尽 → FAILED
            repo.updateStatus(key, DownloadStatus.FAILED, 0, reason ?: "下载失败")
            _downloadStates.value = _downloadStates.value + (key to DownloadState.Failed(reason))
            onNotify("下载失败：${song.title}")
            return result
        }
    }

    private suspend fun singleAttempt(song: Song, auto: Boolean, attempt: Int): DownloadResult {
        val key = song.downloadKey

        // 1. 解析直链
        val url = resolver.resolve(song)
            ?: return DownloadResult.Failure("无法获取下载链接")

        // 2. 预估大小 + 空间校验
        val estimated = headContentLength(url) ?: estimateFromSong(song)
        if (!storage.hasRoomFor(estimated)) {
            storage.notifyFullOnce(onNotify)
            return DownloadResult.StorageFull
        }

        // 3. 构造路径
        val ext = paths.extOf(url, song)
        val p = paths.build(song, ext)
        p.artistDir.mkdirs(); p.albumDir.mkdirs(); p.tmpFile.parentFile?.mkdirs()

        // 4. 建 DOWNLOADING 记录
        val entity = repo.newDownloadingEntity(song, resolver.sourceTypeOf(song), p.tmpFile.absolutePath, auto)
        repo.upsert(entity)

        // 5. 下载到临时文件
        val fileSize = downloadFile(url, p.tmpFile, estimated) { progress, written ->
            _downloadStates.value = _downloadStates.value + (key to DownloadState.Downloading(progress))
            repo.updateProgress(key, progress)
            if (written % PROGRESS_STEP < 8192 && !storage.hasRoomFor(0)) {
                throw StorageFullException()
            }
        }

        // 6. 封面 + 歌词
        coverWriter.writeArtistCover(p.artistDir, song)
        val coverBytes = coverWriter.writeAlbumCover(p.albumDir, song)
        val lrc = runCatching { lyricsProvider(song) }.getOrNull()

        // 7. 元数据内嵌（失败走旁路）
        val embedded = tagWriter.embed(p.tmpFile, song, coverBytes, lrc)
        var coverPath: String? = null
        var lyricPath: String? = null
        if (!embedded) {
            lrc?.let {
                val lrcFile = File(p.albumDir, "${p.baseName}.lrc")
                runCatching { lrcFile.writeText(it, Charsets.UTF_8) }.onSuccess { lyricPath = lrcFile.absolutePath }
            }
            coverBytes?.let {
                val jpgFile = File(p.albumDir, "${p.baseName}.jpg")
                runCatching { jpgFile.writeBytes(it) }.onSuccess { coverPath = jpgFile.absolutePath }
            }
        }

        // 8. 原子 rename 到最终路径
        if (!p.tmpFile.renameTo(p.finalFile)) {
            // 跨目录 rename 失败（极少数情况）：复制 + 删除
            val copied = runCatching { p.tmpFile.copyTo(p.finalFile, overwrite = true) }.isSuccess
            if (!copied) {
                p.tmpFile.delete()
                return DownloadResult.Failure("文件保存失败")
            }
            p.tmpFile.delete()
        }

        // 9. 更新 COMPLETED 记录
        val completed = entity.copy(
            status = DownloadStatus.COMPLETED.name,
            progress = 100,
            audioPath = p.finalFile.absolutePath,
            coverPath = coverPath,
            lyricPath = lyricPath,
            embedded = embedded,
            fileSize = p.finalFile.length(),
            completedAt = System.currentTimeMillis()
        )
        repo.upsert(completed)
        _downloadStates.value = _downloadStates.value +
            (key to DownloadState.Completed(p.finalFile.absolutePath))

        // 10. 触发媒体扫描（best-effort）
        triggerMediaScan(p.finalFile)
        onNotify("已下载：${song.title}")
        AppLog.i(TAG, "downloaded: ${p.finalFile.absolutePath}")
        // 11. 即时入库 local_songs（§7.5.5）：由 NasMusicApp 接管，构建 ScannedSong + upsertDownloaded + 刷新 _localSongs
        runCatching { onCompleted?.invoke(completed) }
            .onFailure { AppLog.w(TAG, "onCompleted hook failed: ${it.message}", it) }
        return DownloadResult.Success(p.finalFile)
    }

    /** 下载流式写入临时文件，返回总字节数 */
    private suspend fun downloadFile(
        url: String,
        target: File,
        expected: Long,
        onProgress: suspend (Int, Long) -> Unit
    ): Long {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("empty body")
            val total = if (expected > 0) expected else body.contentLength().takeIf { it > 0 } ?: 0L
            var written = 0L
            val buf = ByteArray(8192)
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        val progress = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 99) else 0
                        onProgress(progress, written)
                    }
                }
            }
            return written
        }
    }

    /** HTTP HEAD 获取 Content-Length（失败返回 null → 走预估） */
    private fun headContentLength(url: String): Long? = runCatching {
        val req = Request.Builder().url(url).head().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.header("Content-Length")?.toLongOrNull()
        }
    }.getOrNull()

    /** 按时长+码率预估大小；再回退 8MB */
    private fun estimateFromSong(song: Song): Long {
        if (song.durationMs > 0 && song.bitrate > 0) {
            return song.durationMs / 1000 * song.bitrate / 8
        }
        return MAX_FILE_SIZE_FALLBACK
    }

    private fun triggerMediaScan(file: File) {
        runCatching {
            val mime = when (file.extension.lowercase()) {
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "m4a", "mp4" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "wav" -> "audio/wav"
                else -> "audio/*"
            }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }.onFailure { AppLog.w(TAG, "media scan failed: ${it.message}") }
    }

    // ── 清除与删除（见方案 §8.7）──

    /** 取消所有进行中任务 + 删除 .part 临时文件 */
    suspend fun cancelAll() {
        // 中断进行中的下载协程（loop → executeDownload → singleAttempt）
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        repo.getUnfinished().forEach { entity ->
            entity.tmpPath?.let {
                runCatching { File(it).delete() }
            }
            repo.updateStatus(entity.songKey, DownloadStatus.FAILED, 0, "已取消")
            _downloadStates.value = _downloadStates.value - entity.songKey
        }
        // 重启下载循环，使后续 enqueue 仍可处理
        startLoop()
    }

    /**
     * 清空全部已下载：删文件 + 清 downloads.db + 回收空目录。
     * @return (删除的歌曲数, 释放的字节数)
     */
    suspend fun clearAll(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        cancelAll()
        val completed = repo.getCompleted()
        var freed = 0L
        var count = 0
        completed.forEach { entity ->
            val audio = entity.audioPath?.let { File(it) }
            if (audio != null && audio.exists()) {
                freed += audio.length()
                audio.delete()
                count++
            }
            entity.coverPath?.let { File(it).delete() }
            entity.lyricPath?.let { File(it).delete() }
            repo.delete(entity.songKey)
            _downloadStates.value = _downloadStates.value - entity.songKey
        }
        // 回收空目录（从下载根目录向上遍历删除空目录，保留根）
        runCatching {
            val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            if (root != null && root.exists()) {
                root.walkTopDown().filter { it.isDirectory && it != root }
                    .sortedByDescending { it.absolutePath.length }
                    .forEach { dir -> if (dir.listFiles()?.isEmpty() == true) dir.delete() }
            }
        }
        AppLog.i(TAG, "clearAll: $count songs, $freed bytes freed")
        count to freed
    }

    /** 删除单首已下载歌曲（文件 + downloads.db + 回收空目录） */
    suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        val entity = repo.get(key) ?: return@withContext false
        val audio = entity.audioPath?.let { File(it) }
        val ok = audio?.delete() == true || audio?.exists() == false
        entity.coverPath?.let { File(it).delete() }
        entity.lyricPath?.let { File(it).delete() }
        repo.delete(key)
        _downloadStates.value = _downloadStates.value - key
        // 回收空目录
        audio?.parentFile?.let { dir ->
            runCatching {
                val albumDir = dir
                val artistDir = albumDir.parentFile
                if (albumDir.exists() && albumDir.listFiles()?.isEmpty() == true) albumDir.delete()
                if (artistDir != null && artistDir.exists() && artistDir.listFiles()?.isEmpty() == true) artistDir.delete()
            }
        }
        ok
    }

    /** 查询某 songKey 的完成路径（供删除判定） */
    suspend fun pathOf(key: String): String? = repo.get(key)?.audioPath

    /**
     * 崩溃恢复（NasMusicApp.onCreate 调用）：
     * 1. 清理 .tmp/.part
     * 2. 重置 PENDING/DOWNLOADING → 检查最终文件是否已存在：存在 → COMPLETED，否则 → FAILED
     * 3. 校验 COMPLETED 记录文件是否存在，缺失 → FAILED
     */
    suspend fun recoverAfterCrash() {
        withContext(Dispatchers.IO) {
            // 1. 清理 .tmp 下所有 .part
            val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            val tmpDir = root?.let { File(it, DownloadPathBuilder.TMP_DIR) }
            if (tmpDir != null && tmpDir.exists()) {
                tmpDir.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
            }
            // 2. 逐条恢复未完成任务：最终文件已存在 → COMPLETED，否则清理残留 + FAILED
            repo.getUnfinished().forEach { entity ->
                val finalPath = entity.audioPath
                if (finalPath != null && File(finalPath).exists()) {
                    // 下载已完成但 DB 未更新（崩溃在 rename 后、upsert 前）
                    repo.updateStatus(entity.songKey, DownloadStatus.COMPLETED, 100, null)
                    _downloadStates.value = _downloadStates.value +
                        (entity.songKey to DownloadState.Completed(finalPath))
                } else {
                    // 清理残留 .part 临时文件
                    entity.tmpPath?.let { runCatching { File(it).delete() } }
                    repo.updateStatus(entity.songKey, DownloadStatus.FAILED, 0, "已中断")
                    _downloadStates.value = _downloadStates.value - entity.songKey
                }
            }
            // 3. 校验 COMPLETED 文件
            val completed = repo.getCompleted()
            completed.forEach { entity ->
                val f = entity.audioPath?.let { File(it) }
                if (f != null && !f.exists()) {
                    repo.updateStatus(entity.songKey, DownloadStatus.FAILED, 0, "文件缺失")
                    _downloadStates.value = _downloadStates.value - entity.songKey
                } else if (f != null) {
                    _downloadStates.value = _downloadStates.value +
                        (entity.songKey to DownloadState.Completed(f.absolutePath))
                }
            }
        }
    }

    /** 获取已下载状态 Map（供 UI 列表 collect） */
    fun snapshotStates(): Map<String, DownloadState> = _downloadStates.value
}

/** 下载结果枚举 */
sealed interface DownloadResult {
    data class Success(val file: java.io.File) : DownloadResult
    data object Already : DownloadResult
    data object Duplicated : DownloadResult
    data object QuotaExceeded : DownloadResult
    data object StorageFull : DownloadResult
    data object Disabled : DownloadResult
    data object NotDownloadable : DownloadResult
    data class Failure(val reason: String?) : DownloadResult
}