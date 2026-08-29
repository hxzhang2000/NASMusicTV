package com.nasmusic.tv.player

import android.content.Context
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 伴奏文件缓存管理 + 预分离队列
 *
 * 功能：
 * 1. 缓存人声分离结果（伴奏 WAV 文件），避免重复分离
 * 2. LRU 淘汰：总缓存上限 500MB
 * 3. 预分离队列：当前歌曲播放进度 > 50% 时触发下一首预分离
 *
 * 缓存策略：
 * - 文件路径：context.cacheDir/accompaniment/{songId}_accompaniment.wav
 * - 文件名：{songId}_accompaniment.wav
 * - LRU 淘汰：按最后访问时间排序，超过 500MB 时删除最旧的文件
 */
class AccompanimentCache(private val context: Context) {

    companion object {
        private const val TAG = "AccompanimentCache"
        private const val CACHE_DIR_NAME = "accompaniment"
        private const val MAX_CACHE_SIZE_BYTES = 500L * 1024 * 1024 // 500MB
        private const val MAX_CACHE_FILES = 10  // 最多保留10首伴奏
    }

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preSeparationJob: Job? = null

    /**
     * 预分离状态
     */
    data class PreSeparationState(
        val isRunning: Boolean = false,
        val currentSongId: String? = null,
        val progress: Float = 0f,
        val stage: String = ""
    )

    private val _preSeparationState = MutableStateFlow(PreSeparationState())
    val preSeparationState: StateFlow<PreSeparationState> = _preSeparationState

    /**
     * 检查伴奏文件是否已缓存
     */
    fun hasAccompaniment(songId: String): Boolean {
        val file = getAccompanimentFile(songId)
        val exists = file.exists() && file.length() > 0
        if (exists) {
            // 更新访问时间（LRU）
            file.setLastModified(System.currentTimeMillis())
        }
        return exists
    }

    /**
     * 获取伴奏文件
     */
    fun getAccompanimentFile(songId: String): File {
        return File(cacheDir, "${songId}_accompaniment.wav")
    }

    /**
     * 获取人声文件（用于高质量模式播放）
     */
    fun getVocalsFile(songId: String): File {
        return File(cacheDir, "${songId}_vocals.wav")
    }

    /**
     * 启动预分离（后台协程）
     *
     * @param songId 当前歌曲 ID
     * @param inputPath 输入音频文件路径
     * @param separator DemucsSeparator 实例
     */
    fun startPreSeparation(
        songId: String,
        inputPath: String,
        separator: DemucsSeparator
    ) {
        // 如果已经缓存，跳过
        if (hasAccompaniment(songId)) {
            AppLog.d(TAG, "startPreSeparation: already cached for $songId")
            return
        }

        // 如果正在预分离同一首歌，跳过
        if (_preSeparationState.value.currentSongId == songId && _preSeparationState.value.isRunning) {
            return
        }

        // 取消之前的预分离
        preSeparationJob?.cancel()

        preSeparationJob = scope.launch {
            _preSeparationState.value = PreSeparationState(
                isRunning = true,
                currentSongId = songId,
                progress = 0f,
                stage = "准备中"
            )

            try {
                val result = withContext(Dispatchers.IO) {
                    separator.separate(
                        inputPath = inputPath,
                        outputDir = cacheDir,
                        songId = songId,
                        progress = { progress, stage ->
                            _preSeparationState.value = _preSeparationState.value.copy(
                                progress = progress,
                                stage = stage
                            )
                        }
                    )
                }

                if (result != null) {
                    AppLog.d(TAG, "startPreSeparation: OK for $songId")
                    cleanupCache()
                } else {
                    AppLog.w(TAG, "startPreSeparation: failed for $songId")
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "startPreSeparation: exception for $songId", e)
            } finally {
                _preSeparationState.value = PreSeparationState()
            }
        }
    }

    /**
     * 取消预分离
     */
    fun cancelPreSeparation() {
        preSeparationJob?.cancel()
        preSeparationJob = null
        _preSeparationState.value = PreSeparationState()
    }

    /**
     * 清理缓存（LRU 淘汰：按大小 + 文件数）
     */
    private fun cleanupCache() {
        val files = cacheDir.listFiles() ?: return

        // 1. 按文件数淘汰（最多保留 MAX_CACHE_FILES 首）
        if (files.size > MAX_CACHE_FILES) {
            val sortedFiles = files.sortedBy { it.lastModified() }
            val toDelete = files.size - MAX_CACHE_FILES
            for (i in 0 until toDelete) {
                val file = sortedFiles[i]
                val fileSize = file.length()
                if (file.delete()) {
                    AppLog.d(TAG, "cleanupCache: deleted (count limit) ${file.name} (${fileSize} bytes)")
                }
            }
        }

        // 2. 按总大小淘汰（LRU）
        val remainingFiles = cacheDir.listFiles() ?: return
        val totalSize = remainingFiles.sumOf { it.length() }
        if (totalSize <= MAX_CACHE_SIZE_BYTES) return

        val sortedFiles = remainingFiles.sortedBy { it.lastModified() }
        var freedSize = 0L
        val targetFree = totalSize - MAX_CACHE_SIZE_BYTES

        for (file in sortedFiles) {
            if (freedSize >= targetFree) break
            val fileSize = file.length()
            if (file.delete()) {
                freedSize += fileSize
                AppLog.d(TAG, "cleanupCache: deleted (size limit) ${file.name} (${fileSize} bytes)")
            }
        }
    }

    /**
     * 获取缓存大小（字节）
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * 获取缓存文件数
     */
    fun getCacheFileCount(): Int {
        return cacheDir.listFiles()?.size ?: 0
    }

    /**
     * 清空缓存
     */
    fun clearCache() {
        cancelPreSeparation()
        cacheDir.listFiles()?.forEach { it.delete() }
        AppLog.d(TAG, "clearCache: all files deleted")
    }

    /**
     * 仅清除伴奏文件（保留人声文件，用于 K 歌对比）
     */
    fun clearAccompaniments(): Int {
        cancelPreSeparation()
        var count = 0
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.endsWith("_accompaniment.wav")) {
                file.delete()
                count++
            }
        }
        AppLog.d(TAG, "clearAccompaniments: deleted $count accompaniment files")
        return count
    }

    /**
     * 删除指定歌曲的缓存
     */
    fun deleteCache(songId: String) {
        val accompanimentFile = getAccompanimentFile(songId)
        val vocalsFile = getVocalsFile(songId)
        accompanimentFile.delete()
        vocalsFile.delete()
        AppLog.d(TAG, "deleteCache: deleted files for $songId")
    }
}
