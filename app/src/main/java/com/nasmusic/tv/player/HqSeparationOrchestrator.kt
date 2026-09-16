package com.nasmusic.tv.player

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 高质量人声分离编排器（N-4 提取自 PlayerManager）：
 * 负责 HT-Demucs ONNX 模式的完整编排——输入解析（本地文件/下载 streamUrl）、
 * 模型加载、后台分离、伴奏/原唱切换、错误回退。
 *
 * 播放器操作经 [PlayerHost] 窄接口回调（窄接口模式），
 * 避免与 PlayerManager 播放状态机形成强环引用。

 *
 * ─────────────────────────────────────────────────────────────────────
 * 🎤 K 歌双路径总览（2026-09-13 标注）
 * ─────────────────────────────────────────────────────────────────────
 * 本类是 K 歌「高质量」路径的编排器，负责：
 *   - HT-Demucs ONNX 模型加载/卸载
 *   - 异步人声分离（协程 + 文件 IO）
 *   - 切换原曲/错误时取消未完成任务（PlayerManager.release() 调用本类 release()）
 *
 * K 歌模块共有两条路径（叠加运行，非互斥）：
 *   ① 实时 DSP 路径（默认/兜底）—— SpectralMaskProcessor
 *      1 阶 RC 低通 250Hz 截止，零延迟，TV 设备 CPU 友好
 *      由 VocalSeparationViewModel.toggleVocalRemoval() 始终启用
 *   ② 高质量模型路径（本类）—— HT-Demucs ONNX 推理
 *      仅在用户开启"高质量模式"且本地有模型文件时启用
 *      异步分离，输出伴奏/和声/低音 stem
 *
 * 调度入口：VocalSeparationViewModel.toggleVocalRemoval()
 *   ├─ setVocalRemovalEnabled(true)  启用 DSP 路径（永远执行）
 *   └─ isHighQualityMode() ? enableHighQualityRemoval() : —
 *                            条件启用本类
 *
 * 历史备忘：曾有一个更精细的 4 阶 Linkwitz-Riley DSP 实现
 * （VocalRemovalProcessor），已被 SpectralMaskProcessor 取代，2026-09-14 作为
 * 死代码删除；其算法与参数已归档于 docs/technical-overview.md §10.152 与
 * docs/vocal-removal-approach-b-dsp.md，可供高保真/离线批处理场景复原。
 * 与本类无关，不要混淆。
 * ─────────────────────────────────────────────────────────────────────
 */
class HqSeparationOrchestrator(
    private val appContext: Context,
    private val host: PlayerHost
) {

    companion object {
        // 沿用原 PlayerManager 日志 tag，保持 logcat 过滤习惯不变
        private const val TAG = "PlayerManager"
    }

    /** PlayerManager 注入的窄接口（只暴露 HQ 编排所需的播放操作） */
    interface PlayerHost {
        /** 获取当前 ExoPlayer（可能为 null，调用方需判空） */
        fun player(): ExoPlayer?

        /** 当前播放歌曲（队列索引处的 Song） */
        fun currentSong(): Song?

        /** 主播放器当前是否正在播放 */
        fun isPlaying(): Boolean

        /** 暂停主播放器 */
        fun pause()

        /** 恢复主播放器播放 */
        fun play()

        /** 开关快速模式 DSP（SpectralMaskProcessor） */
        fun setFastVocalRemoval(enabled: Boolean)
    }

    /** 网络下载用的 HTTP 客户端（HQ 模式下载 streamUrl 到本地文件） */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(5, java.util.concurrent.TimeUnit.MINUTES)  // 整体超时，防止卡死
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var demucsSeparator: DemucsSeparator? = null
    private var accompanimentCache: AccompanimentCache? = null
    private var modelDownloadManager: ModelDownloadManager? = null

    /** 当前分离模式（快速/高质量），由 MainViewModel 从 AppPreferences 初始化 */
    private val _separationMode = MutableStateFlow(SeparationMode.FAST)
    val separationMode: StateFlow<SeparationMode> = _separationMode

    /** 高质量分离是否正在进行（用于 UI loading 状态） */
    private val _separating = MutableStateFlow(false)
    val separating: StateFlow<Boolean> = _separating

    /** 高质量分离进度（0f~1f）与阶段描述 */
    private val _separationProgress = MutableStateFlow(0f to "")
    val separationProgress: StateFlow<Pair<Float, String>> = _separationProgress

    /** 高质量分离错误信息（非空表示最近一次失败，UI 应提示用户） */
    private val _hqError = MutableStateFlow<String?>(null)
    val hqError: StateFlow<String?> = _hqError

    private val _hqSuccess = MutableStateFlow<String?>(null)
    val hqSuccess: StateFlow<String?> = _hqSuccess

    /** 原始 MediaItem 的 URI，用于切换回原始音频 */
    private var originalMediaItemUri: String? = null

    /** 上次下载失败的具体原因（resolveInputPath 失败时设置） */
    private var lastDownloadError: String? = null

    /** HQ 分离开始时间（用于计算耗时） */
    private var separationStartTimeMs: Long = 0

    /** 进行中的分离协程（release 时取消，避免在已 release 的播放器上继续跑 ONNX） */
    private var separationJob: Job? = null

    // ── 组件注入（PlayerManager 转发）──

    fun setDemucsSeparator(separator: DemucsSeparator) {
        demucsSeparator = separator
    }

    fun setAccompanimentCache(cache: AccompanimentCache) {
        accompanimentCache = cache
    }

    fun setModelDownloadManager(manager: ModelDownloadManager) {
        modelDownloadManager = manager
    }

    // ── 模式状态 ──

    fun setSeparationMode(mode: SeparationMode) {
        _separationMode.value = mode
        AppLog.d(TAG, "setSeparationMode: $mode")
    }

    fun isHighQualityMode(): Boolean {
        return _separationMode.value == SeparationMode.HIGH_QUALITY
    }

    /** 清除高质量分离错误信息 */
    fun clearHqError() {
        _hqError.value = null
    }

    /** 清除高质量分离成功信息 */
    fun clearHqSuccess() {
        _hqSuccess.value = null
    }

    /** 播放开始时自动清除分离成功提示（延迟 3 秒让用户看到） */
    fun scheduleHqSuccessClearOnPlay() {
        if (_hqSuccess.value != null) {
            scope.launch {
                kotlinx.coroutines.delay(3000)
                _hqSuccess.value = null
            }
        }
    }

    /** 清除伴奏缓存（返回删除的文件数） */
    fun clearAccompanimentCache(): Int {
        return accompanimentCache?.clearAccompaniments() ?: 0
    }

    // ── 输入解析 ──

    /**
     * 解析歌曲的本地输入路径，供 DemucsSeparator 使用。
     *
     * - 如果 song.path 非空（百度网盘本地文件），直接返回
     * - 否则如果 song.streamUrl 非空，下载到临时文件后返回路径
     * - 都为空则返回 null
     *
     * 调用方应在分离完成后调用 [cleanupTempFile] 清理下载的临时文件。
     */
    private suspend fun resolveInputPath(
        song: Song,
        progressStage: String = appContext.getString(R.string.hq_progress_downloading)
    ): String? {
        // 1. 本地文件优先（百度网盘歌曲）
        val localPath = song.path
        if (!localPath.isNullOrBlank()) {
            val file = File(localPath)
            if (file.exists() && file.length() > 0) {
                AppLog.d(TAG, "resolveInputPath: using local file for '${song.title}'")
                return localPath
            }
        }

        // 2. 从 streamUrl 下载到临时文件
        val streamUrl = song.streamUrl
        if (streamUrl.isNullOrBlank()) {
            AppLog.w(TAG, "resolveInputPath: no path and no streamUrl for '${song.title}'")
            lastDownloadError = appContext.getString(R.string.player_error_no_file)
            return null
        }

        return try {
            _separationProgress.value = 0.05f to progressStage
            val tempFile = withContext(Dispatchers.IO) {
                // 修复（H-2）：java.io.tmpdir 在 Android 上通常未定义，回退 /data/local/tmp
                // 对普通应用不可写，导致 HQ 分离下载输入文件失败；改用应用私有 cacheDir。
                val tempDir = File(appContext.cacheDir, "nasmusic_hq")
                tempDir.mkdirs()
                val outFile = File(tempDir, "${song.id}_input.tmp")

                val request = Request.Builder().url(streamUrl).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    AppLog.w(TAG, "resolveInputPath: download failed, HTTP ${response.code}")
                    lastDownloadError = appContext.getString(R.string.player_download_http_error, response.code)
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()
                var downloaded = 0L
                // 节流：只在百分比整数变化 ≥1% 时才更新 StateFlow，避免淹没 Main 线程
                var lastReportedPercent = -1

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (contentLength > 0) {
                                val pct = downloaded.toFloat() / contentLength.toFloat()
                                val intPercent = (pct * 100).toInt()
                                // 只在整百分比变化 ≥1 时才更新，大幅减少 StateFlow 发射次数
                                if (intPercent != lastReportedPercent) {
                                    lastReportedPercent = intPercent
                                    _separationProgress.value = (0.05f + pct * 0.15f) to progressStage
                                }
                            }
                        }
                    }
                }

                AppLog.d(TAG, "resolveInputPath: downloaded ${outFile.length()} bytes for '${song.title}'")
                outFile.absolutePath
            }
            if (tempFile == null) {
                // lastDownloadError 已在内部设置
                null
            } else {
                lastDownloadError = null
                tempFile
            }
        } catch (e: java.net.SocketTimeoutException) {
            AppLog.e(TAG, "resolveInputPath: download timeout", e)
            lastDownloadError = appContext.getString(R.string.player_download_timeout)
            null
        } catch (e: java.net.SocketException) {
            AppLog.e(TAG, "resolveInputPath: network error", e)
            lastDownloadError = appContext.getString(R.string.player_download_network_error, e.message?.take(30))
            null
        } catch (e: Exception) {
            AppLog.e(TAG, "resolveInputPath: download exception", e)
            lastDownloadError = appContext.getString(R.string.player_download_exception, e.message?.take(30))
            null
        }
    }

    /**
     * 清理 resolveInputPath 下载的临时文件。
     * 仅删除以 _input.tmp 结尾的文件，避免误删。
     */
    private fun cleanupTempFile(path: String?) {
        if (path == null) return
        try {
            val file = File(path)
            if (file.exists() && file.name.endsWith("_input.tmp")) {
                file.delete()
                AppLog.d(TAG, "cleanupTempFile: deleted $path")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "cleanupTempFile: failed", e)
        }
    }

    // ── HQ 编排主流程 ──

    /**
     * 高质量模式下开启人声消除：
     * 1. 检查模型是否已下载
     * 2. 检查伴奏文件是否已缓存
     * 3. 若已缓存：直接切换 MediaItem 为伴奏文件
     * 4. 若未缓存：保持原始音频播放 + 后台分离 → 完成后切换到伴奏
     */
    fun enableHighQualityRemoval(): Boolean {
        // 单飞守卫：已有分离在进行时忽略重复请求。否则连点/重试会启动两个分离协程，
        // 共享同一 DemucsSeparator 向同一 WAV 并发写、并各自 replaceMediaItem（H3）。
        if (_separating.value) {
            AppLog.w(TAG, "enableHighQualityRemoval: separation already in progress, ignoring duplicate request")
            return true
        }

        val separator = demucsSeparator
        val cache = accompanimentCache
        val songId = host.currentSong()?.id

        if (separator == null || cache == null || songId == null) {
            AppLog.w(TAG, "enableHighQualityRemoval: missing separator/cache/songId, fallback to fast mode")
            _hqError.value = appContext.getString(R.string.hq_error_component_not_ready)
            _separationMode.value = SeparationMode.FAST
            host.setFastVocalRemoval(true)
            return false
        }

        // 检查模型是否已下载
        val modelManager = modelDownloadManager
        if (modelManager != null && !modelManager.isModelDownloaded()) {
            AppLog.w(TAG, "enableHighQualityRemoval: model not downloaded, fallback to fast mode")
            _hqError.value = appContext.getString(R.string.hq_error_model_not_downloaded)
            _separationMode.value = SeparationMode.FAST
            host.setFastVocalRemoval(true)
            return false
        }

        val accompanimentFile = cache.getAccompanimentFile(songId)
        if (accompanimentFile.exists() && accompanimentFile.length() > 0) {
            // 已缓存：直接切换到伴奏文件（同时关闭快速模式 DSP，伴奏文件本身已无主唱）
            host.setFastVocalRemoval(false)
            switchToAccompaniment(accompanimentFile.absolutePath)
        } else {
            // 未缓存：暂停播放 → 后台分离 → 完成后切换到伴奏并恢复播放
            val song = host.currentSong() ?: return false
            _hqError.value = null  // 清除上次错误
            // 保存播放状态并暂停，避免分离期间继续播放原唱
            val wasPlayingBeforeSeparation = host.isPlaying()
            host.pause()
            _separating.value = true
            separationStartTimeMs = System.currentTimeMillis()
            var tempInputPath: String? = null
            separationJob = scope.launch {
                try {
                    // 解析输入路径（本地文件 或 下载 streamUrl）
                    val inputPath = withContext(Dispatchers.IO) {
                        resolveInputPath(song, appContext.getString(R.string.hq_progress_downloading))
                    }
                    if (inputPath == null) {
                        AppLog.w(TAG, "enableHighQualityRemoval: cannot resolve input path, fallback to fast mode")
                        _hqError.value = appContext.getString(R.string.hq_error_with_fallback, lastDownloadError ?: appContext.getString(R.string.hq_error_no_audio_file))
                        _separationMode.value = SeparationMode.FAST
                        host.setFastVocalRemoval(true)
                        if (wasPlayingBeforeSeparation) host.play()
                        return@launch
                    }
                    // 记录是否为临时下载文件（分离后需清理）
                    tempInputPath = if (song.path.isNullOrBlank()) inputPath else null

                    // 确保分离器已初始化（在 IO 线程加载 166MB 模型，避免主线程 ANR）
                    if (!separator.isReady()) {
                        val modelPath = modelManager?.getModelPath()
                        if (modelPath == null) {
                            AppLog.w(TAG, "enableHighQualityRemoval: model path unavailable, fallback to fast mode")
                            _hqError.value = appContext.getString(R.string.hq_error_model_path_unavailable)
                            _separationMode.value = SeparationMode.FAST
                            host.setFastVocalRemoval(true)
                            if (wasPlayingBeforeSeparation) host.play()
                            return@launch
                        }
                        // 2026-09-14（P1-4）：加载前做一次 SHA-256 完整性校验（IO 线程，
                        // 约 0.3~1s，仅在模型未加载时执行一次）。文件被截断/替换时在此拦下，
                        // 而不是把坏模型交给 ONNX 后抛难以定位的错误。
                        // modelManager 为 null 时跳过（无法校验，保持原行为）。
                        val integrityOk = withContext(Dispatchers.IO) {
                            modelManager?.verifyModelIntegrity() ?: true
                        }
                        if (!integrityOk) {
                            AppLog.w(TAG, "enableHighQualityRemoval: model integrity check failed, fallback to fast mode")
                            _hqError.value = appContext.getString(R.string.hq_error_with_fallback, appContext.getString(R.string.hq_error_model_corrupted))
                            _separationMode.value = SeparationMode.FAST
                            host.setFastVocalRemoval(true)
                            if (wasPlayingBeforeSeparation) host.play()
                            return@launch
                        }

                        _separationProgress.value = 0.2f to appContext.getString(R.string.hq_progress_loading_model)
                        val initOk = withContext(Dispatchers.IO) { separator.initialize(modelPath) }
                        if (!initOk) {
                            AppLog.w(TAG, "enableHighQualityRemoval: separator init failed, fallback to fast mode")
                            _hqError.value = appContext.getString(R.string.hq_error_with_fallback, separator.lastError ?: appContext.getString(R.string.hq_error_separator_init_failed))
                            _separationMode.value = SeparationMode.FAST
                            host.setFastVocalRemoval(true)
                            if (wasPlayingBeforeSeparation) host.play()
                            return@launch
                        }
                    }

                    val outputDir = cache.getAccompanimentFile(songId).parentFile
                        ?: java.io.File(cache.getAccompanimentFile(songId).parent)
                    val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        separator.separate(
                            inputPath = inputPath,
                            outputDir = outputDir,
                            songId = songId,
                            progress = DemucsSeparator.ProgressCallback { p, stage ->
                                val elapsedSec = (System.currentTimeMillis() - separationStartTimeMs) / 1000.0
                                _separationProgress.value = p to appContext.getString(R.string.hq_progress_stage_elapsed, stage, elapsedSec)
                            }
                        )
                    }
                    if (result != null) {
                        // 分离完成，关闭快速模式 DSP + 切换到伴奏文件 + 恢复播放
                        val totalSec = (System.currentTimeMillis() - separationStartTimeMs) / 1000.0
                        AppLog.d(TAG, "enableHighQualityRemoval: completed in ${String.format("%.1f", totalSec)}s")
                        _separationProgress.value = 1f to appContext.getString(R.string.hq_progress_done, totalSec)
                        _hqSuccess.value = appContext.getString(R.string.hq_success_separation_done, totalSec)
                        host.setFastVocalRemoval(false)
                        switchToAccompaniment(result.accompanimentFile.absolutePath)
                        if (wasPlayingBeforeSeparation) host.play()
                    } else {
                        val totalSec = (System.currentTimeMillis() - separationStartTimeMs) / 1000.0
                        AppLog.w(TAG, "enableHighQualityRemoval: separation failed in ${String.format("%.1f", totalSec)}s, fallback to fast mode")
                        _hqError.value = appContext.getString(R.string.hq_error_with_time_and_suffix, separator.lastError ?: appContext.getString(R.string.hq_error_separation_failed), totalSec)
                        _separationMode.value = SeparationMode.FAST
                        host.setFastVocalRemoval(true)
                        if (wasPlayingBeforeSeparation) host.play()
                    }
                } catch (e: CancellationException) {
                    // release()/取消导致的中断：不当作失败，交由 finally 收尾
                    AppLog.d(TAG, "enableHighQualityRemoval: cancelled")
                    throw e
                } catch (e: OutOfMemoryError) {
                    AppLog.e(TAG, "enableHighQualityRemoval: OOM", e)
                    _hqError.value = appContext.getString(R.string.hq_error_oom)
                    _separationMode.value = SeparationMode.FAST
                    host.setFastVocalRemoval(true)
                    if (wasPlayingBeforeSeparation) host.play()
                } catch (e: Exception) {
                    AppLog.e(TAG, "enableHighQualityRemoval: exception", e)
                    _hqError.value = appContext.getString(R.string.hq_error_exception_with_suffix, e.message?.take(30))
                    _separationMode.value = SeparationMode.FAST
                    host.setFastVocalRemoval(true)
                    if (wasPlayingBeforeSeparation) host.play()
                } finally {
                    _separating.value = false
                    _separationProgress.value = 0f to ""
                    // 保存原唱文件到缓存（分离时下载的输入文件），用于切回原唱时直接使用
                    //
                    // ⚠️ 约定锁死（P3-1，2026-09-16）：[AccompanimentCache.saveOriginalFile]
                    // 必须保持 **copy** 语义（现为 `source.copyTo(dest, overwrite = true)`），
                    // 不能改成 rename/move。原因：紧随其后的 cleanupTempFile(tempInputPath)
                    // 会删除 tempInputPath；若 saveOriginalFile 改为 rename，缓存下来的原唱
                    // 文件会被这一步一并删掉，之后切回原唱时文件已不存在。
                    // 两行顺序不可调换，cleanupTempFile 也不可省略（否则临时文件残留）。
                    val inputPath = tempInputPath
                    if (inputPath != null && songId != null) {
                        accompanimentCache?.saveOriginalFile(songId, inputPath)
                    }
                    // 清理临时下载文件
                    cleanupTempFile(tempInputPath)
                }
            }
        }
        return true
    }

    /** 切换到伴奏文件播放 */
    private fun switchToAccompaniment(accompanimentPath: String) {
        val p = host.player() ?: return
        val currentPos = p.currentPosition
        val wasPlaying = p.isPlaying

        // 保存原始 URI
        val currentItem = p.currentMediaItem
        if (originalMediaItemUri == null && currentItem != null) {
            originalMediaItemUri = currentItem.localConfiguration?.uri.toString()
        }

        // 构建新 MediaItem 指向伴奏文件
        // 用 Uri.fromFile 正确编码中文/空格路径（原 Uri.parse("file://$path") 遇中文/空格
        // 产生非法 URI，导致 ExoPlayer 无法播放伴奏）。
        val accompanimentUri = Uri.fromFile(java.io.File(accompanimentPath))
        val newItem = currentItem?.buildUpon()?.setUri(accompanimentUri)?.build() ?: return

        // P6 修复：用 replaceMediaItem 替换当前索引的 item，而非 setMediaItem（后者会
        // 把整个播放队列替换成单曲，导致 seekToNextMediaItem 无目标、K 歌后无法切歌）。
        val index = p.currentMediaItemIndex
        p.replaceMediaItem(index, newItem)
        p.prepare()
        p.seekTo(currentPos)
        if (wasPlaying) p.play()

        AppLog.d(TAG, "switchToAccompaniment: $accompanimentPath")
    }

    /** 切换回原始音频文件（关闭人声消除时） */
    private fun switchToOriginal() {
        val p = host.player() ?: return
        val currentPos = p.currentPosition
        val wasPlaying = p.isPlaying
        val index = p.currentMediaItemIndex

        // 优先使用本地缓存的原唱文件（分离时下载的）
        val songId = host.currentSong()?.id
        val cache = accompanimentCache
        if (songId != null && cache != null) {
            val originalFile = cache.getOriginalFile(songId)
            if (originalFile.exists() && originalFile.length() > 0) {
                val originalItem = p.currentMediaItem?.buildUpon()
                    ?.setUri(Uri.fromFile(originalFile))
                    ?.build() ?: return
                p.replaceMediaItem(index, originalItem)
                p.prepare()
                p.seekTo(currentPos)
                if (wasPlaying) p.play()
                originalMediaItemUri = null
                AppLog.d(TAG, "switchToOriginal: using local cache ${originalFile.name}")
                return
            }
        }

        // 回退：使用原始 URI（可能是网络 URL）
        val uri = originalMediaItemUri ?: return
        val originalUri = Uri.parse(uri)
        val originalItem = p.currentMediaItem?.buildUpon()?.setUri(originalUri)?.build() ?: return

        p.replaceMediaItem(index, originalItem)
        p.prepare()
        p.seekTo(currentPos)
        if (wasPlaying) p.play()

        originalMediaItemUri = null
        AppLog.d(TAG, "switchToOriginal: restored from original URI")
    }

    /**
     * 高质量模式下关闭人声消除：切换回原始文件 + 恢复 DSP 状态
     */
    fun disableHighQualityRemoval() {
        _hqError.value = null
        switchToOriginal()
        // 如果快速模式 DSP 也处于开启状态（vocalRemovalEnabled=true），恢复它
        // （高质量模式切换伴奏文件时关闭了 DSP，切回原唱时需要恢复）
    }

    /**
     * 释放资源：释放 Demucs 的 ONNX 会话（166MB 模型 + OrtSession）。
     * 原实现 release() 从未调用 demucsSeparator.release()，导致播放服务销毁后
     * modelSession/ortEnv 进程级泄漏，多次启停后内存持续增长（P10 修复）。
     */
    fun release() {
        // 取消进行中的分离，避免其继续在已释放的播放器上跑 ONNX；
        // DemucsSeparator.release() 内部用 tryLock：若分离仍持有推理锁，
        // 会置 pendingRelease，待分离结束后自行释放，避免关掉正在推理的 session。
        separationJob?.cancel()
        separationJob = null
        _separating.value = false
        demucsSeparator?.release()
        // L7 修复（2026-09-13）：release 末尾补 scope.cancel()，清理本编排器 scope 内的协程。
        // release() 由 PlayerManager.release() 调用,而 PlayerManager 为 app 级单例,
        // 实际影响有限,但补齐后保证 release 路径不留悬挂 scope。
        scope.cancel()
    }
}
