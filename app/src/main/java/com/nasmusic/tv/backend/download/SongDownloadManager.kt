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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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
    /**
     * P0-1 修复（2026-09-16）：下载链路与播放链路共用同一套后端认证头注入。
     * 飞牛（fnOS）的 track/stream 端点要求 `Authorization: <userToken>` 请求头，
     * 播放链路经 BaiduHttpDataSourceFactory 拦截器注入，而下载此前用独立裸 client → 401。
     * 此处复用 BackendAuthHeaders.forHost（host 精确匹配，令牌不随 302 泄漏到第三方域）。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val req = chain.request()
            val headers = com.nasmusic.tv.backend.BackendAuthHeaders.forHost(req.url.host)
            if (headers.isEmpty()) chain.proceed(req)
            else {
                val b = req.newBuilder()
                headers.forEach { (k, v) -> b.header(k, v) }
                chain.proceed(b.build())
            }
        }
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

    /**
     * P1-5 修复（2026-09-16）：cancelAll 改为「cancel 当前 Call + 置位 cancelRequested +
     * drain 排队任务」，不再 cancel/restart loop 协程。
     * 原实现 cancel loop 后立即 startLoop()，旧 loop 协程（阻塞在 socket 读上）尚未退出，
     * 新 loop 已开始消费 → 两个 executeDownload 并发，破坏「串行队列 1 个消费者」保证。
     * 现在 loop 常驻唯一实例；进行中的下载由 call.cancel() 触发 IO 异常，
     * executeDownload 的重试判定看到 cancelRequested=true 后不再重试、直接落 FAILED。
     */
    @Volatile
    private var cancelRequested = false

    /**
     * 当前进行中的下载 Call。
     * 仅 cancel 协程不会中断 OkHttp 的阻塞 socket 读，必须显式 call.cancel()，
     * 否则 cancelAll() 后旧下载仍会继续写盘（覆盖窗口）。
     */
    @Volatile
    private var currentCall: okhttp3.Call? = null

    init {
        // loop 常驻唯一消费者（P1-5 修复后 cancelAll 不再重启 loop）
        scope.launch(Dispatchers.IO) { loop() }
    }

    /** 手动 / 自动入队（手动优先：手动队列非空时自动任务不抢占） */
    fun enqueue(song: Song, auto: Boolean) {
        scope.launch(Dispatchers.IO) {
            (if (auto) autoQueue else manualQueue).send(song to auto)
        }
    }

    private suspend fun loop() {
        while (true) {
            // 手动优先：先非阻塞检查手动队列
            val task = manualQueue.tryReceive().getOrNull()
            if (task != null) {
                executeDownload(task.first, task.second)
                continue
            }
            // 两队列都空时阻塞等待，select 按 clause 顺序优先（手动优先）
            val polled = select<Pair<Song, Boolean>> {
                manualQueue.onReceive { it }
                autoQueue.onReceive { it }
            }
            executeDownload(polled.first, polled.second)
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
        // P1-5：新任务开始执行 → 上一次 cancelAll 的取消窗口结束
        cancelRequested = false

        // 幂等检查：已在下载队列中或已完成 → 不重复下载
        val current = repo.get(key)
        if (current?.status == DownloadStatus.COMPLETED.name) {
            _downloadStates.update {
                it + (key to DownloadState.Completed(
                    current.audioPath ?: "",
                    coverPath = current.coverPath,
                    lyricPath = current.lyricPath,
                    embedded = current.embedded
                ))
            }
            return DownloadResult.Already
        }
        if (current?.status == DownloadStatus.DOWNLOADING.name) {
            return DownloadResult.Duplicated
        }

        var attempt = 0
        while (true) {
            attempt++
            _downloadStates.update { it + (key to DownloadState.Downloading(0)) }
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

            // P1-5：cancelAll 已置位 → 不再重试，直接落 FAILED
            if (cancelRequested) {
                repo.updateStatus(key, DownloadStatus.FAILED, 0, "已取消")
                _downloadStates.update { it - key }
                return result
            }

            // 可重试失败：从 reason 中提取 HTTP 状态码，404/403 等不重试
            val reason = (result as? DownloadResult.Failure)?.reason
            val httpCode = reason?.removePrefix("HTTP ")?.toIntOrNull()
            val shouldNotRetry = httpCode != null && httpCode in NO_RETRY_CODES
            if (attempt <= MAX_RETRY && !shouldNotRetry && !cancelRequested) {
                val delayMs = RETRY_DELAYS.getOrElse(attempt - 1) { RETRY_DELAYS.last() }
                AppLog.d(TAG, "retry $attempt/$MAX_RETRY for ${song.title} in ${delayMs}ms")
                _downloadStates.update { it + (key to DownloadState.Queued) }
                delay(delayMs)
                continue
            }
            // 重试耗尽 → FAILED
            repo.updateStatus(key, DownloadStatus.FAILED, 0, reason ?: "下载失败")
            _downloadStates.update { it + (key to DownloadState.Failed(reason)) }
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

        // 5. 下载到临时文件（内部做 Content-Length 完整性校验，失败即抛异常触发重试）
        // P2-1 修复（2026-09-16）：进度回调按 PROGRESS_STEP(512KB) 节流。
        // 原实现每个 8KB 块都做一次全 Map 拷贝 + StateFlow 发布 + DB 写，
        // 与「每 512KB 更新」注释不符，大 Map 时 UI 列表每 8KB 重组一次。
        var lastProgressStep = -1L
        downloadFile(url, p.tmpFile, estimated) { progress, written ->
            val step = written / PROGRESS_STEP
            if (step != lastProgressStep) {
                lastProgressStep = step
                _downloadStates.update { it + (key to DownloadState.Downloading(progress)) }
                repo.updateProgress(key, progress)
            }
            // 空间复查保持原有近似节奏（每 512KB 边界附近查一次）
            if (written % PROGRESS_STEP < 8192 && !storage.hasRoomFor(0)) {
                throw StorageFullException()
            }
        }

        // 6. 封面 + 歌词
        coverWriter.writeArtistCover(p.artistDir, song)
        val coverBytes = coverWriter.writeAlbumCover(p.albumDir, song)
        val lrc = runCatching { lyricsProvider(song) }.getOrNull()

        // 7. 原子 rename 到最终路径（必须在 embed 之前，否则 .part 扩展名
        //    不在 EMBEDDABLE 集合中，supportsEmbedding() 返回 false 导致永远不内嵌）
        if (!p.tmpFile.renameTo(p.finalFile)) {
            // 跨目录 rename 失败（极少数情况）：复制 + 删除
            val copied = runCatching { p.tmpFile.copyTo(p.finalFile, overwrite = true) }.isSuccess
            if (!copied) {
                p.tmpFile.delete()
                return DownloadResult.Failure("文件保存失败")
            }
            p.tmpFile.delete()
        }

        // 8~9. 内嵌 + 落库
        //   ⚠️ rename 之后、DB 提交之前的任何异常都会把 finalFile 变成「孤儿」
        //   （recoverAfterCrash 只清 .part/.tmp），故在此统一 try/catch 主动清理。
        var completed: DownloadSongEntity? = null
        var coverPath: String? = null
        var lyricPath: String? = null
        var embedded = false
        try {
            // 8. 元数据内嵌（失败走旁路）
            val fileExt = p.finalFile.extension.lowercase()
            AppLog.d(TAG, "embed: file=${p.finalFile.name}, ext=$fileExt, supportsEmbed=${MediaTagWriter.supportsEmbedding(p.finalFile)}, coverBytes=${coverBytes?.size}, lrc=${lrc?.take(50)}")
            embedded = tagWriter.embed(p.finalFile, song, coverBytes, lrc)
            AppLog.d(TAG, "embed: result=$embedded, coverPath will be=${if (!embedded) "sidecar" else "null"}")
            if (!embedded) {
                // 内嵌失败才写 sidecar
                lrc?.let {
                    val lrcFile = File(p.albumDir, "${p.baseName}.lrc")
                    runCatching { lrcFile.writeText(it, Charsets.UTF_8) }.onSuccess { lyricPath = lrcFile.absolutePath }
                }
                coverBytes?.let {
                    val jpgFile = File(p.albumDir, "${p.baseName}.jpg")
                    runCatching { jpgFile.writeBytes(it) }.onSuccess { coverPath = jpgFile.absolutePath }
                }
            }
            AppLog.d(TAG, "embed: final coverPath=$coverPath, lyricPath=$lyricPath, embedded=$embedded")

            // 9. 更新 COMPLETED 记录
            val record = entity.copy(
                status = DownloadStatus.COMPLETED.name,
                progress = 100,
                audioPath = p.finalFile.absolutePath,
                coverPath = coverPath,
                lyricPath = lyricPath,
                embedded = embedded,
                fileSize = p.finalFile.length(),
                completedAt = System.currentTimeMillis()
            )
            repo.upsert(record)
            completed = record
        } catch (e: Exception) {
            // 孤儿清理：删除已 rename 的最终音频 + 可能已写出的旁路文件，
            // 避免磁盘残留与 DB 计数不一致
            AppLog.w(TAG, "post-rename failure, cleaning orphan: ${p.finalFile.absolutePath}", e)
            runCatching { p.finalFile.delete() }
            runCatching { File(p.albumDir, "${p.baseName}.lrc").delete() }
            runCatching { File(p.albumDir, "${p.baseName}.jpg").delete() }
            throw e
        }
        val done = completed ?: return DownloadResult.Failure("文件保存失败")
        _downloadStates.update {
            it + (key to DownloadState.Completed(
                p.finalFile.absolutePath,
                coverPath = coverPath,
                lyricPath = lyricPath,
                embedded = embedded
            ))
        }

        // 10. 触发媒体扫描（best-effort）
        triggerMediaScan(p.finalFile)
        onNotify("已下载：${song.title}")
        AppLog.i(TAG, "downloaded: ${p.finalFile.absolutePath}")
        // 11. 即时入库 local_songs（§7.5.5）：由 NasMusicApp 接管，构建 ScannedSong + upsertDownloaded + 刷新 _localSongs
        runCatching { onCompleted?.invoke(done) }
            .onFailure { AppLog.w(TAG, "onCompleted hook failed: ${it.message}", it) }
        return DownloadResult.Success(p.finalFile)
    }

    /**
     * 下载流式写入临时文件，返回总字节数。
     *
     * 完整性校验：服务器声明的 Content-Length 是权威长度。若读到的字节数与声明不符
     * （服务器中途断流、代理截断），抛出 [IOException] 触发上层重试，避免半截文件
     * 被 rename + 内嵌 + 标记 COMPLETED。任何失败都会清理半成品，防止磁盘残留。
     */
    private suspend fun downloadFile(
        url: String,
        target: File,
        expected: Long,
        onProgress: suspend (Int, Long) -> Unit
    ): Long {
        val req = Request.Builder().url(url).build()
        val call = client.newCall(req)
        currentCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("empty body")
                val declaredLength = body.contentLength() // -1 表示服务器未声明
                val total = if (expected > 0) expected else declaredLength.takeIf { it > 0 } ?: 0L
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
                // 完整性校验：声明长度已知但实际写入不足/不符 → 视为失败以触发重试
                if (declaredLength > 0 && written != declaredLength) {
                    throw IOException("incomplete download: $written/$declaredLength bytes")
                }
                return written
            }
        } catch (e: Exception) {
            // 失败/取消时清理半成品：避免截断文件残留，也避免被后续逻辑误判为完整文件
            runCatching { if (target.exists()) target.delete() }
            throw e
        } finally {
            if (currentCall === call) currentCall = null
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

    /**
     * 按时长+码率预估大小；再回退 8MB。
     * P1-4 修复（2026-09-16）：Song.bitrate 单位是 kbps（Jellyfin 已 ÷1000、Navidrome/Subsonic 直接 kbps），
     * `durationMs/1000 * bitrate / 8` 结果单位是 **KB**，原实现直接按字节比较 → 差 1000 倍，
     * 预检形同虚设。现乘 1024L 对齐到字节。
     */
    private fun estimateFromSong(song: Song): Long {
        if (song.durationMs > 0 && song.bitrate > 0) {
            return song.durationMs / 1000 * song.bitrate / 8 * 1024L
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
        // P1-5 修复（2026-09-16）：不再 cancel/restart loop（消除双 loop 并发窗口）。
        // ① 置位取消标志 → 进行中任务在异常/重试判定处直接落 FAILED，不再重试
        cancelRequested = true
        // ② drain 排队未开始的任务（含手动与自动两条队列）
        while (manualQueue.tryReceive().getOrNull() != null) { /* drain */ }
        while (autoQueue.tryReceive().getOrNull() != null) { /* drain */ }
        // ③ cancel 进行中的 Call（中断阻塞 socket 读）
        runCatching { currentCall?.cancel() }
        currentCall = null
        // ④ 落库清理：未完成任务标记 FAILED + 删 .part 临时文件
        repo.getUnfinished().forEach { entity ->
            entity.tmpPath?.let {
                runCatching { File(it).delete() }
            }
            repo.updateStatus(entity.songKey, DownloadStatus.FAILED, 0, "已取消")
            _downloadStates.update { it - entity.songKey }
        }
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
            _downloadStates.update { it - entity.songKey }
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
        _downloadStates.update { it - key }
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
    /**
     * P0-2 修复（2026-09-16）：为 DOWNLOADING/PENDING 记录反推最终文件路径。
     *
     * DOWNLOADING 记录创建时 audioPath 恒为 null（只有 tmpPath），崩溃发生在
     * 「rename 之后、COMPLETED 提交之前」时磁盘上已有最终文件但 DB 无从知晓。
     * 按 DownloadPathBuilder 的命名规则（artist/album/「NN - 」title.ext）在下载根目录
     * 反推；同时检查「title.ext」「title (2..10).ext」命中任一即视为孤儿完成文件。
     * 反推失败（如手动改过目录结构）则维持原 FAILED 行为，无害。
     */
    private fun recoverFinalPathOrNull(entity: com.nasmusic.tv.backend.download.db.DownloadSongEntity): String? {
        return runCatching {
            val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC) ?: return null
            val artist = DownloadPathBuilder.sanitize(entity.artist.ifBlank { "未知歌手" }).ifBlank { "未知歌手" }
            val album = DownloadPathBuilder.sanitize(entity.album.ifBlank { DownloadPathBuilder.DEFAULT_ALBUM }).ifBlank { DownloadPathBuilder.DEFAULT_ALBUM }
            if (entity.title.isBlank()) return null
            val baseName = entity.title
            val albumDir = File(root, "$artist/$album")
            if (!albumDir.exists()) return null
            // ext 优先用 DB 记录的 containerExt；缺失时遍历常见音频扩展名
            val exts = if (entity.containerExt.isNotBlank()) listOf(entity.containerExt.lowercase())
                       else DownloadPathBuilder.AUDIO_EXTS.toList()
            for (ext in exts) {
                val candidate0 = File(albumDir, "$baseName.$ext")
                if (candidate0.exists()) return candidate0.absolutePath
                for (i in 2..10) {
                    val candidateI = File(albumDir, "$baseName ($i).$ext")
                    if (candidateI.exists()) return candidateI.absolutePath
                }
            }
            null
        }.getOrNull()
    }

    suspend fun recoverAfterCrash() {
        withContext(Dispatchers.IO) {
            // 1. 清理 .tmp 下所有 .part
            val root = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            val tmpDir = root?.let { File(it, DownloadPathBuilder.TMP_DIR) }
            if (tmpDir != null && tmpDir.exists()) {
                tmpDir.listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() }
            }
            // 2. 逐条恢复未完成任务：最终文件已存在 → COMPLETED，否则清理残留 + FAILED
            // P0-2 修复（2026-09-16）：原实现读 entity.audioPath，但 DOWNLOADING 记录创建时
            // audioPath 恒为 null（只有 tmpPath），孤儿恢复分支永不命中（上次审查 P1-5 修复无效）。
            // 现在：优先用 DB 里已提交的 audioPath；没有则按 downloadRoot/artist/album/title
            // 反推最终路径（与 DownloadPathBuilder.build 的命名规则一致，含 " (2)" 去重序号排除）。
            repo.getUnfinished().forEach { entity ->
                val finalPath = entity.audioPath ?: recoverFinalPathOrNull(entity)
                if (finalPath != null && File(finalPath).exists()) {
                    // 下载已完成但 DB 未更新（崩溃在 rename 后、upsert 前）
                    repo.updateStatus(entity.songKey, DownloadStatus.COMPLETED, 100, null)
                    _downloadStates.update {
                        it + (entity.songKey to DownloadState.Completed(
                            finalPath,
                            coverPath = entity.coverPath,
                            lyricPath = entity.lyricPath,
                            embedded = entity.embedded
                        ))
                    }
                } else {
                    // 清理残留 .part 临时文件
                    entity.tmpPath?.let { runCatching { File(it).delete() } }
                    repo.updateStatus(entity.songKey, DownloadStatus.FAILED, 0, "已中断")
                    _downloadStates.update { it - entity.songKey }
                }
            }
            // 3. 校验 COMPLETED 文件
            val completed = repo.getCompleted()
            completed.forEach { entity ->
                val f = entity.audioPath?.let { File(it) }
                if (f != null && !f.exists()) {
                    repo.updateStatus(entity.songKey, DownloadStatus.FAILED, 0, "文件缺失")
                    _downloadStates.update { it - entity.songKey }
                } else if (f != null) {
                    _downloadStates.update {
                        it + (entity.songKey to DownloadState.Completed(
                            f.absolutePath,
                            coverPath = entity.coverPath,
                            lyricPath = entity.lyricPath,
                            embedded = entity.embedded
                        ))
                    }
                }
            }
        }
    }

    /** 获取已下载状态 Map（供 UI 列表 collect） */
    fun snapshotStates(): Map<String, DownloadState> = _downloadStates.value

    /** 清空所有下载状态（供 MainViewModel.clearAllDownloads() 调用，同步内存 Map） */
    fun clearAllStates() {
        _downloadStates.update { emptyMap() }
    }
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