package com.nasmusic.tv.backend.export

import android.content.Context
import android.media.MediaScannerConnection
import androidx.documentfile.provider.DocumentFile
import com.nasmusic.tv.backend.download.DownloadRepository
import com.nasmusic.tv.backend.download.db.DownloadSongEntity
import com.nasmusic.tv.backend.download.db.ExportRecordEntity
import com.nasmusic.tv.backend.download.db.DownloadDatabase
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 导出到外接设备（USB/SD）执行器（见方案 §8.8）
 *
 * - 导出 downloads.db 中 state=COMPLETED 的全部歌曲：音频 + artist.jpg + cover.jpg + 旁路 .lrc/.jpg
 * - 字节复制，不重新编码（源文件下载时已内嵌封面/歌词）
 * - 增量过滤：export_records 中 relPath 已存在 且 srcSize 未变 → skipped
 * - 可取消 / 可续跑：每首歌结束后保存已完成记录，取消后重插 U 盘可继续
 * - SAF 下直接写目标（无 rename 语义）；File 下先写 .part 再 rename
 */
class SongExporter(
    private val context: Context,
    private val repo: DownloadRepository,
    private val exportRecordDao: com.nasmusic.tv.backend.download.db.ExportRecordDao,
    private val prefs: com.nasmusic.tv.data.prefs.AppPreferences
) {
    companion object {
        private const val TAG = "SongExporter"
        private const val BUFFER = 8192
        private const val SPACE_CHECK_INTERVAL = 50L * 1024 * 1024   // 每 50MB 复检
        private const val RESERVED_BYTES = 100L * 1024 * 1024         // 目标设备也留 100MB 余量
    }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    @Volatile private var isActive = false
    @Volatile private var cancelRequested = false
    private var currentRoot: ExportRoot = ExportRoot.Unavailable

    /**
     * 导出互斥锁（下载 #M-7 修复）：单例无并发保护时重复触发会并发写同一目标、
     * 且两个任务互相踩 cancelRequested。后者直接忽略（返回 false）而不是排队。
     */
    private val exportMutex = kotlinx.coroutines.sync.Mutex()
    private val exportActive = java.util.concurrent.atomic.AtomicBoolean(false)

    fun cancel() {
        cancelRequested = true
        _state.value = ExportState.Cancelled
    }

    /**
     * 执行导出。
     *
     * @param root 导出根（SAF 或文件系统）
     * @param volumeId 卷标识（用于 export_records 增量）
     * @param devicePath 设备路径（用于判定导出目录在应用专属目录时的可见性）
     * @param onCompleted 完成回调（更新本地曲库让新导出的歌出现在 USB 列表）
     */
    suspend fun export(root: ExportRoot, volumeId: String, devicePath: String, onCompleted: (Int, Int, Int) -> Unit) {
        // 并发守卫：重复触发直接忽略（避免并发写同一目标 + cancelRequested 互相干扰）
        if (!exportMutex.tryLock()) {
            AppLog.w(TAG, "export: another export is running, ignore duplicate trigger")
            return
        }
        try {
            exportLocked(root, volumeId, devicePath, onCompleted)
        } finally {
            exportMutex.unlock()
        }
    }

    private suspend fun exportLocked(
        root: ExportRoot,
        volumeId: String,
        devicePath: String,
        onCompleted: (Int, Int, Int) -> Unit
    ) {
        if (root is ExportRoot.Unavailable) {
            _state.value = ExportState.Failed(ExportError.NO_PERMISSION)
            return
        }
        val completedSongs = repo.getCompleted().filter { it.audioPath != null }
        if (completedSongs.isEmpty()) {
            _state.value = ExportState.Failed(ExportError.NOTHING_TO_EXPORT)
            return
        }

        // 1. 枚举待导出文件任务（音频 + 封面/歌词，同专辑封面去重）
        val tasks = mutableListOf<FileTask>()
        val exportedCovers = HashSet<String>()   // "artist/album" → 已导出封面
        completedSongs.forEach { song ->
            val audio = song.audioPath?.let { File(it) }
            if (audio == null || !audio.exists()) return@forEach   // 源文件缺失，跳过
            tasks.add(FileTask(audio, relAudioPath(audio)))
            // 封面（同专辑去重）
            audio.parentFile?.let { albumDir ->
                val key = albumDir.absolutePath
                if (exportedCovers.add(key)) {
                    val albumName = albumDir.name
                    val artistName = albumDir.parentFile?.name ?: "未知歌手"
                    File(albumDir, "cover.jpg").takeIf { it.exists() }?.let {
                        tasks.add(FileTask(it, "$artistName/$albumName/cover.jpg"))
                    }
                    albumDir.parentFile?.let { artistDir ->
                        File(artistDir, "artist.jpg").takeIf { it.exists() }?.let {
                            tasks.add(FileTask(it, "$artistName/artist.jpg"))
                        }
                    }
                }
            }
            // 旁路 .lrc / .jpg
            song.lyricPath?.let { File(it).takeIf { f -> f.exists() }?.let { f ->
                tasks.add(FileTask(f, relSidecarPath(f)))
            } }
            song.coverPath?.let { File(it).takeIf { f -> f.exists() }?.let { f ->
                tasks.add(FileTask(f, relSidecarPath(f)))
            } }
        }
        if (tasks.isEmpty()) {
            _state.value = ExportState.Failed(ExportError.NOTHING_TO_EXPORT)
            return
        }

        // 2. 空间校验（sum 待导字节 > 设备可用 - 100MB → NO_SPACE）
        val totalBytes = tasks.sumOf { it.src.length() }
        val available = com.nasmusic.tv.util.StorageUtils.availableBytesAt(File(devicePath))
        if (available > 0 && totalBytes > available - RESERVED_BYTES) {
            _state.value = ExportState.Failed(ExportError.NO_SPACE)
            return
        }

        // 3. 增量过滤（查 export_records(volumeId)）
        val existing = runCatching { exportRecordDao.byVolume(volumeId) }.getOrDefault(emptyList())
        val existingMap = existing.associateBy { it.relPath }
        var skipped = 0
        var done = 0
        var failed = 0
        var writtenSinceCheck = 0L

        _state.value = ExportState.Running(0, tasks.size, "", skipped, failed)
        currentRoot = root
        isActive = true
        cancelRequested = false

        for (task in tasks) {
            if (cancelRequested) {
                _state.value = ExportState.Cancelled
                isActive = false
                return
            }
            _state.value = ExportState.Running(done, tasks.size, task.src.name, skipped, failed)

            // 增量：relPath 已存在 且 srcSize 未变 → 跳过
            val rec = existingMap[task.relPath]
            if (rec != null && rec.srcSize == task.src.length() && !shouldOverwrite(task)) {
                skipped++
                continue
            }

            val ok = try {
                copyToRoot(root, task)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                // 每完成一个文件 upsert 一条 record
                runCatching {
                    exportRecordDao.upsert(
                        ExportRecordEntity(
                            volumeId = volumeId,
                            relPath = task.relPath,
                            srcPath = task.src.absolutePath,
                            size = task.src.length(),
                            srcSize = task.src.length(),
                            exportedAt = System.currentTimeMillis()
                        )
                    )
                }
                done++
                writtenSinceCheck += task.src.length()
                // 每 50MB 复检空间，跌破即中止
                if (writtenSinceCheck >= SPACE_CHECK_INTERVAL) {
                    writtenSinceCheck = 0
                    val nowAvail = com.nasmusic.tv.util.StorageUtils.availableBytesAt(File(devicePath))
                    if (nowAvail <= RESERVED_BYTES) {
                        _state.value = ExportState.Failed(ExportError.NO_SPACE)
                        isActive = false
                        return
                    }
                }
            } else {
                failed++
            }
            _state.value = ExportState.Running(done, tasks.size, task.src.name, skipped, failed)
        }

        isActive = false
        // 完成 → 触发媒体扫描 + 更新本地曲库
        runCatching { mediaScan(devicePath) }
        _state.value = ExportState.Completed(done, skipped, failed)
        onCompleted(done, skipped, failed)
    }

    /**
     * 是否覆盖目标已有文件：目标存在且长度一致 → false（跳过）；否则 true（覆盖）。
     * P1-6 修复（2026-09-16）：原实现 targetFile 只支持 File 根，SAF 分支恒返回 null
     * → shouldOverwrite 对 SAF 恒 true，增量判定只剩 export_records，
     * 清掉导出记录后重导会无条件覆盖 USB 上同名不同长的文件。
     * 现在 SAF 分支用 resolveChildDoc 反查目标 DocumentFile 的长度对比。
     */
    private fun shouldOverwrite(task: FileTask): Boolean {
        return when (val root = currentRoot) {
            is ExportRoot.File -> {
                val target = File(root.dir, task.relPath)
                !target.exists() || target.length() != task.src.length()
            }
            is ExportRoot.Saf -> {
                val doc = resolveChildDoc(root.dir, task.relPath) ?: return true
                doc.length() != task.src.length()
            }
            ExportRoot.Unavailable -> true
        }
    }

    private suspend fun copyToRoot(root: ExportRoot, task: FileTask): Boolean = withContext(Dispatchers.IO) {
        when (root) {
            is ExportRoot.Saf -> {
                val parent = root.dir
                val doc = resolveChildDoc(parent, task.relPath) ?: return@withContext false
                try {
                    val output = context.contentResolver.openOutputStream(doc.uri) ?: return@withContext false
                    output.use { out ->
                        task.src.inputStream().use { input -> copyStream(input, out) }
                    }
                    true
                } catch (e: Exception) {
                    // SAF 无 rename 语义，写失败即删除半截文件
                    runCatching { doc.delete() }
                    false
                }
            }
            is ExportRoot.File -> {
                val target = File(root.dir, task.relPath)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, "${target.name}.part")
                try {
                    tmp.outputStream().use { out ->
                        task.src.inputStream().use { input -> copyStream(input, out) }
                    }
                    if (!tmp.renameTo(target)) {
                        // rename 失败：复制兜底
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    true
                } catch (e: Exception) {
                    runCatching { tmp.delete() }
                    false
                }
            }
            ExportRoot.Unavailable -> false
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
        }
    }

/** SAF 嵌套目录解析：逐级 findFile/createDirectory 定位到叶子目录，再 createFile 文件名 */
    private fun resolveChildDoc(root: DocumentFile, relPath: String): DocumentFile? {
        val segments = relPath.split("/").toMutableList()
        // NewApi 修复（2026-09-14）：removeLast() 在 API 35 会被 java.util.SequencedCollection
        // 的同名方法遮蔽，重新编译后会在低版本上 NoSuchMethodError；改用 removeAt(lastIndex)。
        val fileName = segments.removeAt(segments.lastIndex) // 最后一段是文件名
        var current = root
        for (dir in segments) {
            current = current.findFile(dir)
                ?: current.createDirectory(dir)
                ?: return null
        }
        return current.findFile(fileName) ?: current.createFile("audio/*", fileName)
    }

    /** 音频相对路径（歌手/专辑/文件名）。原 volumeId/song 为未使用的死参数，易误导「按卷隔离」 */
    private fun relAudioPath(audio: File): String {
        val parent = audio.parentFile
        val album = parent?.name ?: "单曲"
        val artist = parent?.parentFile?.name ?: "未知歌手"
        return "$artist/$album/${audio.name}"
    }

    /** 旁路文件相对路径（歌手/专辑/文件名）。原 volumeId/song/ext 为未使用的死参数 */
    private fun relSidecarPath(f: File): String {
        val parent = f.parentFile
        val album = parent?.name ?: "单曲"
        val artist = parent?.parentFile?.name ?: "未知歌手"
        return "$artist/$album/${f.name}"
    }

    /** 媒体扫描（best-effort；应用专属目录在 Android/data 下多数 ROM 不扫，调用方改用 scanUsbDevice 重建索引） */
    private suspend fun mediaScan(devicePath: String) {
        runCatching {
            val paths = repo.getCompleted().mapNotNull { it.audioPath }
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }

    private data class FileTask(val src: File, val relPath: String)
}

/** 导出状态机 */
sealed interface ExportState {
    object Idle : ExportState
    data class Preparing(val total: Int) : ExportState
    data class Running(
        val done: Int,
        val total: Int,
        val current: String,
        val skipped: Int = 0,
        val failed: Int = 0
    ) : ExportState
    data class Completed(val done: Int, val skipped: Int, val failed: Int) : ExportState
    data class Failed(val reason: ExportError) : ExportState
    object Cancelled : ExportState
}

enum class ExportError { NO_DEVICE, NO_PERMISSION, NO_SPACE, DEVICE_REMOVED, IO, NOTHING_TO_EXPORT }