package com.nasmusic.tv.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.nasmusic.tv.R
import com.nasmusic.tv.util.AppLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.BufferedOutputStream
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * HT-Demucs FT ONNX 高质量人声分离器
 *
 * 流程：
 * 1. 读取输入音频（支持 MP3/FLAC/WAV/OGG 等 ExoPlayer 支持的格式）
 * 2. 解码为 PCM 浮点数据（44100Hz 立体声）
 * 3. 分段处理（7.81s 段，overlap-add）
 * 4. ONNX 推理 → 4 stems（drums, bass, other, vocals）
 * 5. 提取 vocals stem → iSTFT（模型内部处理）
 * 6. 写入伴奏文件（ex vocals from mix）
 *
 * 模型文件：
 * - htdemucs_ft_vocals.onnx（HT-Demucs FT Vocals Specialist, FP16, ~166MB）
 * 下载地址：https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx
 *
 * 与旧 Spleeter 方案的区别：
 * - 输入：立体声原始 PCM（不需要外部 STFT）
 * - 输出：4 stems，取 vocals（index=3）
 * - 模型内置 STFT/iSTFT，我们只需提供原始 PCM
 * - 需要 overlap-add chunking 处理长音频
 */
class DemucsSeparator(private val context: Context) {

    companion object {
        private const val TAG = "DemucsSeparator"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2

        // HT-Demucs FT 参数
        private const val SEGMENT_SAMPLES = 343980  // 7.81s at 44100Hz（模型固定输入长度）
        // 相邻段交叠长度（约 78ms）：overlap-add 的交叉淡化区，用于消除段边界爆音。
        private const val OVERLAP_SAMPLES = 3440

        // 输出 stems: drums=0, bass=1, other=2, vocals=3
        private const val VOCALS_INDEX = 3

        // 模型输入 shape: [1, 2, samples]
        private val INPUT_SHAPE = longArrayOf(1, 2, SEGMENT_SAMPLES.toLong())
    }

    private var ortEnv: OrtEnvironment? = null
    private var modelSession: OrtSession? = null
    @Volatile private var isInitialized = false
    /** ONNX 模型的实际输入名（从 session 动态读取，不用硬编码 "input"） */
    @Volatile private var inputName: String = "input"

    /** 保护 initialize/release 的字段读写，避免并发 init 双建 session / release 期间 NPE */
    private val stateLock = Any()
    /** 分离单飞锁：同一时刻只允许一个 separate 运行（防止并发推理 + 并发写同一 WAV） */
    private val opMutex = Mutex()
    /** 分离进行中收到 release 请求 → 延迟到分离结束后释放，避免关掉正在推理的 session */
    @Volatile private var pendingRelease = false

    /** 上次失败的具体原因（separate/initialize/decodeAudio 失败时设置） */
    var lastError: String? = null
        private set

    /**
     * 分离结果
     */
    data class SeparationResult(
        val vocalsFile: File,
        val accompanimentFile: File,
        val durationMs: Long
    )

    /**
     * 分离进度回调
     */
    fun interface ProgressCallback {
        fun onProgress(progress: Float, stage: String)
    }

    /**
     * 初始化 ONNX Runtime 会话（从外部存储加载模型）
     *
     * @param modelPath 模型文件路径（由 ModelDownloadManager 提供）
     */
    fun initialize(modelPath: String): Boolean {
        val modelFile = File(modelPath)
        return try {
            if (!modelFile.exists()) {
                AppLog.e(TAG, "initialize: model file not found: $modelPath")
                lastError = context.getString(R.string.demucs_error_model_not_found)
                return false
            }

            // 并发防护：在 synchronized 内完成「已初始化则复用」判定与 session 创建，
            // 避免双线程各自 createSession 导致前者被覆盖泄漏、以及 release 与新 session 的竞态。
            synchronized(stateLock) {
                if (isInitialized && modelSession != null) return true
                ortEnv = OrtEnvironment.getEnvironment()

                // 直接从文件路径加载模型，使用 mmap 避免将 166MB 读入 JVM 堆（readBytes 会导致 OOM 崩溃）
                modelSession = ortEnv!!.createSession(modelPath)

                // 读取模型实际输入名（替代硬编码 "input"，避免 Unknown input name 错误）
                val inputs = modelSession!!.inputInfo
                inputName = inputs.keys.firstOrNull() ?: "input"

                // 2026-09-14（P2）：校验输入 shape，避免加载了「非 HT-Demucs」的 ONNX 后
                // 到推理阶段才失败——那时已解码+分段跑了一部分，报错也不指向根因。
                // customUrlProvider 允许用户把模型 URL 指向任意地址，这个校验是必要的兜底。
                // 期望 [1, 2, 343980]；动态维（-1/0）视为兼容，只校验已知的固定维。
                val inShape = (inputs.values.firstOrNull()?.info as? TensorInfo)?.shape
                if (inShape != null) {
                    val rankOk = inShape.size == 3
                    val channelOk = !rankOk || inShape[1] <= 0L || inShape[1] == CHANNEL_COUNT.toLong()
                    val sampleOk = !rankOk || inShape[2] <= 0L || inShape[2] == SEGMENT_SAMPLES.toLong()
                    if (!rankOk || !channelOk || !sampleOk) {
                        AppLog.e(TAG, "initialize: unexpected input shape ${inShape.contentToString()}, expected [1, $CHANNEL_COUNT, $SEGMENT_SAMPLES]")
                        runCatching { modelSession?.close() }
                        modelSession = null
                        ortEnv = null
                        isInitialized = false
                        lastError = context.getString(R.string.demucs_error_bad_model_shape, inShape.contentToString())
                        return false
                    }
                }

                isInitialized = true
            }
            lastError = null
            AppLog.d(TAG, "initialize: OK, model loaded from $modelPath (${modelFile.length() / (1024 * 1024)}MB), input='$inputName'")
            true
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "initialize: OOM loading model", e)
            lastError = context.getString(R.string.demucs_error_oom_load_model, (modelFile.length() / (1024 * 1024)).toInt())
            System.gc()
            false
        } catch (e: Exception) {
            AppLog.e(TAG, "initialize: failed", e)
            lastError = context.getString(R.string.demucs_error_init_failed, e.message?.take(60))
            false
        }
    }

    /**
     * 释放资源（幂等，可重复调用）
     *
     * 2026-09-14 修复竞态（P1-5）。原实现有两个缺陷：
     *
     * 缺陷一：原实现是「先 tryLock，失败才置 pendingRelease」，而 separate() 是在
     * **解锁之前**检查该标志的，于是存在丢失窗口——release() 恰好落在「separate 检查完
     * 标志」与「separate 解锁」之间时，标志被置位却无人消费：① session 不释放
     * （166MB 驻留）；② 标志残留到下一次 separate 结束时误触发释放，表现为
     * 「分离成功一次后又要求加载模型」。
     *
     * 缺陷二：消费点若在拿到锁**之前**就清除标记，则当另一个 separate 抢到锁时，
     * 标记已被清空、释放动作却没人做 ⇒ 请求同样丢失。
     *
     * 现在统一为「先置位、再消费」，且**只有真正拿到锁并完成释放才清标记**：
     * 拿不到锁说明仍有 separate 在跑，标记原样留给它解锁后的消费点
     * （见 [consumePendingReleaseRequest]，每个 separate 的收尾都会调用）。
     * 由此形成一条链：只要有分离在飞，链就不会断；链上最后一个持锁者必然拿到锁完成释放。
     */
    fun release() {
        synchronized(stateLock) { pendingRelease = true }
        consumePendingReleaseRequest()
    }

    /**
     * 尝试消费释放请求。**必须在 opMutex 已释放之后调用**（或在不持锁的路径调用）。
     *
     * 正确性论证：release() 在调用本方法前先置位，而本方法只在 tryLock 成功时清标记。
     * 若 tryLock 失败 ⇒ 必有某个 separate 持锁 ⇒ 该 separate 的收尾（同样调用本方法）
     * 会在解锁后再次尝试 ⇒ 请求不会丢。若 tryLock 成功 ⇒ 本次即完成释放，标记被清，
     * 不会重复释放。注意标记是 @Volatile，无锁读取的可见性由 volatile 语义保证。
     */
    private fun consumePendingReleaseRequest() {
        if (!pendingRelease) return
        if (!opMutex.tryLock()) return  // 有分离在跑，留给它解锁后的消费点
        try {
            synchronized(stateLock) { pendingRelease = false }
            releaseInternal()
        } finally {
            opMutex.unlock()
        }
    }

    private fun releaseInternal() {
        synchronized(stateLock) {
            runCatching { modelSession?.close() }
            // ⚠️ 绝不 close ortEnv：OrtEnvironment.getEnvironment() 是进程级单例，
            // 关闭后同进程内再也无法创建任何 ONNX 会话；而 TV 上 PlaybackService
            // 启停不会重启进程，会导致「首次分离正常、服务重启后再也无法分离」。
            modelSession = null
            ortEnv = null
            isInitialized = false
            pendingRelease = false
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isReady(): Boolean = isInitialized && modelSession != null

    /**
     * 解码结果（不含实际 PCM 数据，数据写入 tempFile）
     */
    private data class DecodeResult(
        val totalSamples: Int,  // 单声道采样数（立体声 = pcmBytes / 4 / 2）
        val sampleRate: Int,
        val channelCount: Int,
        val tempFile: File     // 原始 PCM 浮点数据（big-endian float32 交织）
    )

    /**
     * 分离人声和伴奏
     *
     * 内存优化：PCM 数据写入临时文件，逐段从磁盘读取处理，
     * 峰值内存仅 ~200MB（模型166MB + 段缓冲5MB + ONNX运行时30MB），
     * 而非旧方案的 ~384MB（pcmData + left + right + 模型）。
     *
     * 伴奏计算：原始音频 - 人声 = 伴奏。segmentInputBuf 中已保存原始数据，
     * 无需重新从磁盘读取。
     *
     * @param inputPath 输入音频文件路径
     * @param outputDir 输出目录
     * @param songId 歌曲 ID（用于输出文件命名）
     * @param progress 进度回调
     * @return 分离结果，失败返回 null
     */
    suspend fun separate(
        inputPath: String,
        outputDir: File,
        songId: String,
        progress: ProgressCallback? = null
    ): SeparationResult? {
        try {
            return opMutex.withLock {
                separateLocked(inputPath, outputDir, songId, progress)
            }
        } finally {
            // 2026-09-14（P1-5）：释放请求必须在**解锁之后**消费。
            // 若放在 separateLocked 的 finally 里（仍持锁），opMutex.tryLock() 必然失败，
            // 请求会被静默丢弃 → session 常驻 166MB。此处的 finally 在 withLock 释放锁
            // 之后（正常返回与非局部 return 都会走到）执行，是唯一正确的消费点。
            consumePendingReleaseRequest()
        }
    }

    /**
     * [separate] 的持锁主体。调用前必须已持有 [opMutex]（由 [separate] 保证）。
     *
     * 拆成独立函数的原因：主体内有多处 `return null`，在 inline 的 `withLock` 里属于
     * 非局部返回，会跳过任何 `.also { }` 之类的收尾逻辑；只有 try/finally 能保证
     * 解锁后一定执行 [consumePendingReleaseRequest]。
     */
    private suspend fun separateLocked(
        inputPath: String,
        outputDir: File,
        songId: String,
        progress: ProgressCallback? = null
    ): SeparationResult? {
        if (!isReady()) {
            AppLog.e(TAG, "separate: not initialized")
            lastError = context.getString(R.string.demucs_error_not_initialized)
            return null
        }

        // 内存预检：低于 150MB 可用空间时拒绝执行
        val runtime = Runtime.getRuntime()
        val availableMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        if (availableMB < 150) {
            AppLog.e(TAG, "separate: available memory too low: ${availableMB}MB")
            lastError = context.getString(R.string.demucs_error_low_memory, availableMB)
            return null
        }

        // 快照会话引用：推理全程使用本地引用，避免 release() 置空字段导致 NPE
        val session = modelSession ?: return null
        val env = ortEnv ?: return null
        val modelInputName = inputName

        var tempFile: File? = null
        // 输出流与输出文件提升到 try 外，便于 finally 统一关闭 + 失败时清理残缺文件
        // （避免残留「44 字节 WAV 头 + 部分 PCM」的残缺文件被伴奏缓存误判为有效）
        var vocalsFos: BufferedOutputStream? = null
        var accFos: BufferedOutputStream? = null
        var vocalsFile: File? = null
        var accompanimentFile: File? = null
        var success = false
        try {
            progress?.onProgress(0f, context.getString(R.string.demucs_progress_decoding))

            // 1. 解码音频 → 写入临时文件（不在内存中保留完整 FloatArray）
            val decode = decodeAudioToTempFile(inputPath, progress)
            if (decode == null) {
                AppLog.e(TAG, "separate: decode failed")
                return null
            }
            tempFile = decode.tempFile

            progress?.onProgress(0.2f, context.getString(R.string.demucs_progress_segmenting))

            val totalSamples = decode.totalSamples
            val sampleRate = decode.sampleRate

            // 2. 打开输出流，写 WAV 头
            vocalsFile = File(outputDir, "${songId}_vocals.wav")
            accompanimentFile = File(outputDir, "${songId}_accompaniment.wav")
            vocalsFos = BufferedOutputStream(FileOutputStream(vocalsFile!!))
            accFos = BufferedOutputStream(FileOutputStream(accompanimentFile!!))
            writeWavHeader(vocalsFos!!, totalSamples)
            writeWavHeader(accFos!!, totalSamples)

            // 3. 逐段从磁盘读取 → ONNX 推理 → 直接写入输出文件
            //    峰值内存：segmentInputBuf(5.4MB) + vocL/vocR(2.7MB each, 短命) + 模型(166MB)
            // overlap-add：相邻段以 OVERLAP_SAMPLES 交叠，交叠区做线性交叉淡化，
            // 消除段边界爆音；伴奏 = 原始 - 人声，交叠区两侧原始样本一致，故一并平滑。
            val hop = SEGMENT_SAMPLES - OVERLAP_SAMPLES
            var startSample = 0
            var segmentIndex = 0
            var writtenFrames = 0
            val totalSegments = ((totalSamples + hop - 1) / hop).coerceAtLeast(1)
            // 交错缓冲区：[left(0..SEGMENT_SAMPLES-1), right(SEGMENT_SAMPLES..2*SEGMENT_SAMPLES-1)]
            val segmentInputBuf = FloatArray(2 * SEGMENT_SAMPLES)
            // 上一段尾部尚未淡化的输出（对应全局 [startSample, startSample+pending.size))
            var pendingL: FloatArray? = null
            var pendingR: FloatArray? = null

            // 批量写入缓冲：emit() 每帧产出 4 个 short（8 字节，人声 L/R + 伴奏 L/R）。
            // 旧实现每帧调 4 次 shortToByteArray（各分配一个 2 字节数组）+ 4 次 write，
            // 4 分钟曲目约 4200 万次短命分配 → GC 压力显著。现在攒满 8KB 再写一次。
            val vocBuf = ByteArray(8192)
            val accBuf = ByteArray(8192)
            var vocBufPos = 0
            var accBufPos = 0

            // 写出一帧（人声 + 伴奏）；全局位置越界（末段零填充区）则跳过
            fun emit(gi: Int, l: Float, r: Float, origL: Float, origR: Float) {
                if (gi >= totalSamples) return
                if (vocBufPos + 4 > vocBuf.size) {
                    vocalsFos!!.write(vocBuf, 0, vocBufPos)
                    vocBufPos = 0
                }
                if (accBufPos + 4 > accBuf.size) {
                    accFos!!.write(accBuf, 0, accBufPos)
                    accBufPos = 0
                }
                putShortLE(vocBuf, vocBufPos, (l * 32767f).toInt().coerceIn(-32768, 32767))
                putShortLE(vocBuf, vocBufPos + 2, (r * 32767f).toInt().coerceIn(-32768, 32767))
                putShortLE(accBuf, accBufPos, ((origL - l) * 32767f).toInt().coerceIn(-32768, 32767))
                putShortLE(accBuf, accBufPos + 2, ((origR - r) * 32767f).toInt().coerceIn(-32768, 32767))
                vocBufPos += 4
                accBufPos += 4
                writtenFrames++
            }

            DataInputStream(BufferedInputStream(FileInputStream(tempFile))).use { dis ->
                while (startSample < totalSamples) {
                    val segLen = minOf(SEGMENT_SAMPLES, totalSamples - startSample)

                    // 从磁盘读取当前段的交织 PCM float32，直接 deinterleave 到 segmentInputBuf
                    // 临时文件为连续交织 float32（L0,R0,L1,R1,...，无段间填充），逐采样连续
                    // readFloat() 即为正确读取，无需任何 skip。
                    for (i in 0 until segLen) {
                        segmentInputBuf[i] = dis.readFloat()                    // left
                        segmentInputBuf[i + SEGMENT_SAMPLES] = dis.readFloat()  // right
                    }
                    // 修复：末段不足 SEGMENT_SAMPLES 时，缓冲区尾部必须清零，否则会把上一段的
                    // 陈旧采样当作末段内容送进 ONNX，导致末尾损坏。
                    for (i in segLen until SEGMENT_SAMPLES) {
                        segmentInputBuf[i] = 0f
                        segmentInputBuf[i + SEGMENT_SAMPLES] = 0f
                    }

                    // ONNX 推理（始终按完整段取输出，长度 = SEGMENT_SAMPLES）
                    val (vocL, vocR) = processSegmentFromBuffer(
                        segmentInputBuf, SEGMENT_SAMPLES, env, session, modelInputName
                    )

                    val prevL = pendingL
                    val prevR = pendingR
                    if (prevL == null || prevR == null) {
                        // 第一段：直接写 [0, hop)
                        val directEnd = minOf(hop, segLen)
                        for (i in 0 until directEnd) {
                            emit(startSample + i, vocL[i], vocR[i],
                                segmentInputBuf[i], segmentInputBuf[i + SEGMENT_SAMPLES])
                        }
                    } else {
                        // 交叠区线性交叉淡化（w: 0→1），与上一段尾部融合
                        val blendLen = minOf(prevL.size, segLen)
                        for (j in 0 until blendLen) {
                            val w = if (prevL.size > 1) j.toFloat() / (prevL.size - 1) else 1f
                            val l = prevL[j] * (1f - w) + vocL[j] * w
                            val r = prevR[j] * (1f - w) + vocR[j] * w
                            emit(startSample + j, l, r,
                                segmentInputBuf[j], segmentInputBuf[j + SEGMENT_SAMPLES])
                        }
                        // 交叠区之后的直接区 [OVERLAP, hop)
                        val directStart = prevL.size
                        val directEnd = minOf(hop, segLen)
                        for (i in directStart until directEnd) {
                            emit(startSample + i, vocL[i], vocR[i],
                                segmentInputBuf[i], segmentInputBuf[i + SEGMENT_SAMPLES])
                        }
                    }

                    // 保存本段尾部 [hop, hop+OVERLAP) 作为下一段的淡化前段
                    if (segLen > hop) {
                        val tailLen = minOf(OVERLAP_SAMPLES, segLen - hop)
                        pendingL = FloatArray(tailLen) { vocL[hop + it] }
                        pendingR = FloatArray(tailLen) { vocR[hop + it] }
                    } else {
                        pendingL = null
                        pendingR = null
                    }

                    segmentIndex++
                    progress?.onProgress(0.2f + segmentIndex.toFloat() / totalSegments * 0.7f, context.getString(R.string.demucs_progress_separating, segmentIndex, totalSegments))

                    startSample += hop
                }
            }

            // 冲刷最后一段遗留的 pending 尾部（全局位置仍 < totalSamples 的部分）
            pendingL?.let { pl ->
                val pr = pendingR!!
                for (j in pl.indices) {
                    val gi = startSample + j
                    if (gi >= totalSamples) break
                    val origIdx = hop + j
                    val origL = if (origIdx < SEGMENT_SAMPLES) segmentInputBuf[origIdx] else 0f
                    val origR = if (origIdx < SEGMENT_SAMPLES) segmentInputBuf[origIdx + SEGMENT_SAMPLES] else 0f
                    emit(gi, pl[j], pr[j], origL, origR)
                }
            }

            // 冲刷 emit() 残留的批量缓冲（不足 8KB 的尾部）
            if (vocBufPos > 0) {
                vocalsFos!!.write(vocBuf, 0, vocBufPos)
                vocBufPos = 0
            }
            if (accBufPos > 0) {
                accFos!!.write(accBuf, 0, accBufPos)
                accBufPos = 0
            }

            vocalsFos!!.close()
            accFos!!.close()
            // overlap-add 后实际写入帧数可能与预估略有出入，按实际值修正 WAV 头长度字段
            runCatching { patchWavDataSize(vocalsFile!!, writtenFrames) }
            runCatching { patchWavDataSize(accompanimentFile!!, writtenFrames) }
            success = true

            val durationMs = (totalSamples.toFloat() / sampleRate * 1000).toLong()

            progress?.onProgress(1f, context.getString(R.string.demucs_progress_done))

            AppLog.d(TAG, "separate: OK, vocals=${vocalsFile!!.absolutePath}, accompaniment=${accompanimentFile!!.absolutePath}")
            lastError = null
            return SeparationResult(vocalsFile!!, accompanimentFile!!, durationMs)
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "separate: OOM", e)
            lastError = context.getString(R.string.demucs_error_oom_separate)
            System.gc()
            return null
        } catch (e: Exception) {
            AppLog.e(TAG, "separate: failed", e)
            lastError = context.getString(R.string.demucs_error_separate_exception, e.message?.take(40))
            return null
        } finally {
            tempFile?.delete()
            // 统一关闭输出流；失败时删除残缺 WAV，避免缓存投毒
            runCatching { vocalsFos?.close() }
            runCatching { accFos?.close() }
            if (!success) {
                vocalsFile?.delete()
                accompanimentFile?.delete()
            }
            // 注意：分离期间收到的 release 请求**不在这里**处理。此处仍持有 opMutex，
            // 锁内 tryLock 必然失败，处理会把请求丢掉；统一交给 separate() 解锁后的
            // consumePendingReleaseRequest() 消费（见 P1-5 修复说明）。
        }
    }

    /**
     * 解码音频并写入临时文件（不在 JVM 堆中保留完整 FloatArray）
     *
     * **输出契约（2026-09-14 起）：恒定 [SAMPLE_RATE]（44100Hz）立体声。**
     * - 单声道源复制为 L/R（模型要求双声道输入）
     * - 非 44100Hz 源（如 48kHz）线性重采样到 44100Hz —— 模型内部 STFT 固定按
     *   44100Hz 设计，喂错速率会同时劣化分离质量并导致播放变速/变调
     * - 因此下游（分段、overlap-add、[writeWavHeader]、时长计算）可以无条件按
     *   44100Hz 处理，无需再关心源采样率
     *
     * 临时文件格式：big-endian float32 交织立体声（L0,R0,L1,R1,...）
     * 读取时按需解交织，峰值内存仅段缓冲区 ~5MB。
     */
    private fun decodeAudioToTempFile(inputPath: String, progress: ProgressCallback?): DecodeResult? {
        val tempFile = File(context.cacheDir, "demucs_pcm_${System.nanoTime()}.tmp")
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                AppLog.e(TAG, "decodeAudio: no audio track found")
                lastError = context.getString(R.string.demucs_error_no_audio_track)
                tempFile.delete()
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else SAMPLE_RATE
            val sourceChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else CHANNEL_COUNT
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            if (sourceSampleRate <= 0 || sourceChannelCount <= 0) {
                AppLog.e(TAG, "decodeAudio: invalid format, rate=$sourceSampleRate ch=$sourceChannelCount")
                lastError = context.getString(R.string.demucs_error_decode_failed, "invalid format")
                tempFile.delete()
                return null
            }

            // 实际输出参数：以 inputFormat 为初值，收到 INFO_OUTPUT_FORMAT_CHANGED 后
            // 用 codec.outputFormat 覆盖（解码器可能改变声道数/采样率，以它为准）。
            var outSampleRate = sourceSampleRate
            var outChannels = sourceChannelCount

            // 2026-09-14（P0-1）：采样率归一化。
            // MediaCodec **不会**重采样，`codec.configure` 也改不了 KEY_SAMPLE_RATE，
            // 所以 48kHz 等非 44100 源会原样进入模型。模型内部 STFT 固定按 44100Hz 设计，
            // 喂错速率的后果是双重的：① 分离质量劣化；② 若沿用旧代码把 WAV 头硬编码成
            // 44100，输出会以 48/44.1 的倍率播放——时长缩短 8.8%、音高升高约 1.5 个半音。
            // 现在统一线性重采样到 44100，下游（分段、overlap-add、WAV 头、时长计算）
            // 全部按 44100 处理，只有一处真相。
            var resampler: LinearResampler? = null

            var framesWritten = 0L
            var inputDone = false
            var outputDone = false
            var lastDecodeProgressReport = 0

            // 复用预分配的 float32 写入缓冲（BIG_ENDIAN，与 readFloat 一致），
            // 批量写入减少逐样本 IO；flush 剩余不足一段的字节。
            // 临时文件为连续交织 float32（L0,R0,L1,R1,...），无段间填充。
            val writeBuf = ByteArray(64 * 1024) // 64KB 缓冲
            var writeBufPos = 0

            FileOutputStream(tempFile).use { fos ->
                // 把一帧（已归一化为 44100Hz）以 BIG_ENDIAN float32 写入临时文件，
                // 与读取端 DataInputStream.readFloat() 的字节序一致。
                fun writeFrame(l: Float, r: Float) {
                    if (writeBufPos + 8 > writeBuf.size) {
                        fos.write(writeBuf, 0, writeBufPos)
                        writeBufPos = 0
                    }
                    val lb = java.lang.Float.floatToIntBits(l)
                    val rb = java.lang.Float.floatToIntBits(r)
                    writeBuf[writeBufPos++] = (lb ushr 24).toByte()
                    writeBuf[writeBufPos++] = (lb ushr 16).toByte()
                    writeBuf[writeBufPos++] = (lb ushr 8).toByte()
                    writeBuf[writeBufPos++] = lb.toByte()
                    writeBuf[writeBufPos++] = (rb ushr 24).toByte()
                    writeBuf[writeBufPos++] = (rb ushr 16).toByte()
                    writeBuf[writeBufPos++] = (rb ushr 8).toByte()
                    writeBuf[writeBufPos++] = rb.toByte()
                    framesWritten++
                }

                while (!outputDone) {
                    // 输入
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // 输出
                    val bufferInfo = MediaCodec.BufferInfo()
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    if (outputIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }

                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            // 首个数据块到来时才创建重采样器：此时 outSampleRate 已被
                            // INFO_OUTPUT_FORMAT_CHANGED 校正为解码器的真实输出速率。
                            if (resampler == null && outSampleRate != SAMPLE_RATE) {
                                AppLog.w(TAG, "decodeAudio: 源采样率 ${outSampleRate}Hz ≠ ${SAMPLE_RATE}Hz，线性重采样以匹配模型")
                                resampler = LinearResampler(outSampleRate, SAMPLE_RATE) { l, r -> writeFrame(l, r) }
                            }

                            // 2026-09-14（P0-2）：按**真实声道数**拆帧。
                            // 旧实现无条件按 L/R 成对读，单声道源会被解释成「两倍帧数的
                            // 立体声」→ 输出帧数减半、播放翻倍速且升八度。现在：
                            //   单声道  → 同一采样复制到 L/R（模型要求双声道输入）
                            //   立体声  → 正常成对读取
                            //   >2 声道 → 取前两路（通常 FL/FR），跳过其余声道
                            // MediaCodec 输出是 interleaved PCM 16-bit。
                            val frameBytes = outChannels * 2
                            while (outputBuffer.remaining() >= frameBytes) {
                                val l: Float
                                val r: Float
                                if (outChannels == 1) {
                                    val mono = outputBuffer.short.toFloat() / 32768f
                                    l = mono
                                    r = mono
                                } else {
                                    l = outputBuffer.short.toFloat() / 32768f
                                    r = outputBuffer.short.toFloat() / 32768f
                                    if (outChannels > 2) {
                                        // 跳过本帧剩余声道
                                        outputBuffer.position(outputBuffer.position() + (outChannels - 2) * 2)
                                    }
                                }
                                val rs = resampler
                                if (rs == null) writeFrame(l, r) else rs.push(l, r)
                            }

                            // 解码进度
                            if (durationUs > 0) {
                                val ptsMs = bufferInfo.presentationTimeUs / 1000
                                val progress10 = (ptsMs * 10 / (durationUs / 1000)).toInt()
                                if (progress10 > lastDecodeProgressReport && progress10 <= 10) {
                                    lastDecodeProgressReport = progress10
                                    progress?.onProgress(progress10.toFloat() * 0.2f, context.getString(R.string.demucs_progress_decoding_percent, progress10 * 10))
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outputIndex, false)
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 解码器在此报告真实输出格式，以它为准（可能与容器声明不一致）
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outSampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        AppLog.d(TAG, "decodeAudio: output format = ${outSampleRate}Hz x ${outChannels}ch")
                    }
                }

                // 冲刷重采样器尾部（最后不足一个输入帧间隔的输出样本）
                resampler?.flush()

                // 冲刷剩余不足 64KB 的字节
                if (writeBufPos > 0) {
                    fos.write(writeBuf, 0, writeBufPos)
                    writeBufPos = 0
                }
            }

            codec.stop()
            codec.release()
            codec = null
            extractor.release()
            extractor = null

            // 重采样后帧数即为 44100Hz 下的真实帧数（无需重采样时与源帧数相同）。
            // 注意：这里不再用「float 数 / 声道数」反推——重采样会改变帧数，且单声道
            // 源已被复制成双声道写入，只有 writeFrame 的调用次数是权威值。
            val totalSamples = framesWritten.toInt()

            AppLog.d(TAG, "decodeAudioToTempFile: wrote $framesWritten frames (${totalSamples / SAMPLE_RATE}s @ ${SAMPLE_RATE}Hz), src=${sourceSampleRate}Hz x ${sourceChannelCount}ch, temp=${tempFile.absolutePath}, size=${tempFile.length() / (1024 * 1024)}MB")
            return DecodeResult(totalSamples, SAMPLE_RATE, CHANNEL_COUNT, tempFile)
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "decodeAudioToTempFile: OOM", e)
            lastError = context.getString(R.string.demucs_error_decode_oom)
            tempFile.delete()
            System.gc()
            return null
        } catch (e: Exception) {
            AppLog.e(TAG, "decodeAudioToTempFile: failed", e)
            lastError = context.getString(R.string.demucs_error_decode_failed, e.message?.take(30))
            tempFile.delete()
            return null
        } finally {
            // 异常路径也确保释放解码器/抽取器，避免资源泄漏
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /**
     * 处理单段音频：从预分配缓冲区推理提取 vocals（避免每段创建新 FloatArray）
     *
     * @param inputBuf 预分配的交错缓冲区 [left(0..SEGMENT_SAMPLES-1), right(SEGMENT_SAMPLES..2*SEGMENT_SAMPLES-1)]
     * @param actualLen 本段实际采样数（最后一段可能 < SEGMENT_SAMPLES）
     * @return (vocalsLeft, vocalsRight)，长度 = actualLen
     * @throws IllegalStateException 模型输出 shape 与预期不符（携带实际 shape，便于定位）
     *
     * 2026-09-14（P1-3）：输入张量与 OrtSession.Result 都持有 ONNX Runtime 的 native 内存
     * （输入 ~2.75MB，输出 ~11MB），GC 回收不到。旧实现是「先取值、再 close」，一旦
     * `session.run()` 抛异常或强转失败，两者都不会被 close，而 separate() 的
     * `catch (e: Exception)` 会吞掉异常继续下一段 ⇒ 每段泄漏约 14MB native 内存，
     * 长曲目必然 OOM 崩溃。现在用嵌套 try/finally 保证任何路径都释放。
     */
    private fun processSegmentFromBuffer(
        inputBuf: FloatArray,
        actualLen: Int,
        env: OrtEnvironment,
        session: OrtSession,
        modelInputName: String
    ): Pair<FloatArray, FloatArray> {
        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuf),
            INPUT_SHAPE  // [1, 2, 343980]
        )
        try {
            val output = session.run(mapOf(modelInputName to inputTensor))
            try {
                val raw = output[0].value
                val shapeText = shapeOf(output[0])

                // 输出 shape 校验：预期 [1, 4, 2, samples]。旧实现直接强转，shape 不符时
                // 抛 ClassCastException / ArrayIndexOutOfBounds，且异常信息里看不到实际 shape。
                val outer = raw as? Array<*>
                    ?: throw IllegalStateException("Demucs 输出类型异常：${raw?.javaClass?.name}")
                val stems = outer.getOrNull(0) as? Array<*>
                    ?: throw IllegalStateException("Demucs 输出缺少 batch 维：shape=$shapeText")
                val vocalsStem = stems.getOrNull(VOCALS_INDEX) as? Array<*>
                    ?: throw IllegalStateException("Demucs 输出 stem 数不足（需 > $VOCALS_INDEX）：shape=$shapeText")
                val leftChannel = vocalsStem.getOrNull(0) as? FloatArray
                    ?: throw IllegalStateException("Demucs 输出声道数不足：shape=$shapeText")
                val rightChannel = vocalsStem.getOrNull(1) as? FloatArray
                    ?: throw IllegalStateException("Demucs 输出为单声道（模型需输出 L/R）：shape=$shapeText")

                if (leftChannel.size < actualLen || rightChannel.size < actualLen) {
                    throw IllegalStateException(
                        "Demucs 输出样本数不足：需要 $actualLen，实际 L=${leftChannel.size} R=${rightChannel.size}，shape=$shapeText"
                    )
                }

                val vocalsLeft = FloatArray(actualLen)
                val vocalsRight = FloatArray(actualLen)
                for (i in 0 until actualLen) {
                    vocalsLeft[i] = leftChannel[i]
                    vocalsRight[i] = rightChannel[i]
                }
                return Pair(vocalsLeft, vocalsRight)
            } finally {
                output.close()
            }
        } finally {
            inputTensor.close()
        }
    }

    /**
     * 安全读取张量 shape 文本用于报错。
     *
     * 注意：ONNX Runtime 的 [ValueInfo] 是**空接口**，shape 只在具体实现 [TensorInfo] 上，
     * 因此必须做类型判断，不能直接 `value.info.shape`（编译不过）。
     */
    private fun shapeOf(value: OnnxValue): String =
        (value.info as? TensorInfo)?.shape?.contentToString() ?: "unknown"

    /**
     * 依据实际写入帧数修正 WAV 头的 RIFF 长度（偏移 4）与 data 长度（偏移 40）。
     * overlap-add 会改变实际帧数，若不修正则头部声明长度与实际 PCM 不符。
     */
    private fun patchWavDataSize(file: File, frames: Int) {
        val dataSize = frames * CHANNEL_COUNT * 2
        val fileSize = 36 + dataSize
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intToByteArray(fileSize))
            raf.seek(40)
            raf.write(intToByteArray(dataSize))
        }
    }

    /**
     * 写入 WAV 文件头（占位 data size，调用方后续追加 PCM 数据）
     *
     * 采样率与声道数无条件使用 [SAMPLE_RATE] / [CHANNEL_COUNT]：这是安全的，因为
     * [decodeAudioToTempFile] 已保证输出恒为 44100Hz 立体声（单声道复制、非 44100
     * 重采样）。若日后放开该保证，此处必须改为接收实际参数。
     */
    private fun writeWavHeader(fos: BufferedOutputStream, totalSamples: Int) {
        val numPcmSamples = totalSamples * CHANNEL_COUNT
        val dataSize = numPcmSamples * 2 // 16-bit = 2 bytes per sample
        val fileSize = 36 + dataSize

        // RIFF header
        fos.write("RIFF".toByteArray())
        fos.write(intToByteArray(fileSize))
        fos.write("WAVE".toByteArray())

        // fmt chunk
        fos.write("fmt ".toByteArray())
        fos.write(intToByteArray(16))
        fos.write(shortToByteArray(1)) // PCM
        fos.write(shortToByteArray(CHANNEL_COUNT.toShort()))
        fos.write(intToByteArray(SAMPLE_RATE))
        fos.write(intToByteArray(SAMPLE_RATE * CHANNEL_COUNT * 2))
        fos.write(shortToByteArray((CHANNEL_COUNT * 2).toShort()))
        fos.write(shortToByteArray(16))

        // data chunk
        fos.write("data".toByteArray())
        fos.write(intToByteArray(dataSize))
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            (value.toInt() shr 8 and 0xFF).toByte()
        )
    }

    /**
     * 流式线性插值重采样器（立体声，输入输出均为归一化 float）。
     *
     * 为什么需要它：MediaCodec 解码不会重采样，`codec.configure` 也无法改写
     * `KEY_SAMPLE_RATE`，所以 48kHz 源会原样进入按 44100Hz 设计的 Demucs 模型。
     * 若同时把 WAV 头硬编码成 44100，用户听到的伴奏会变速变调（48kHz 源缩短 8.8%、
     * 音高升高约 1.5 个半音）。
     *
     * 实现：输出帧 k 对应输入坐标 `k * ratio`（ratio = inRate / outRate），在该坐标
     * 相邻两个输入帧之间做线性插值。用「输出序号 × ratio」而非「累加 ratio」计算坐标，
     * 避免长曲目（千万帧级）上的浮点累积漂移。
     *
     * 不变式：push 第 i 帧（i 从 0 起）时，所有坐标 < i-1 的输出都已发出，因此本次
     * 只需 prev = v[i-1] 与 cur = v[i] 两个样本即可覆盖坐标区间 [i-1, i)。
     *
     * 说明：线性插值在降采样（如 48k→44.1k）时不做抗混叠滤波，22kHz 以上的镜像分量
     * 会被折叠进来。音乐内容在该频段能量极低（有损编码通常 20kHz 就截止），实际影响
     * 可忽略；换来的是零依赖、可预测的实现，且比「喂错采样率给模型」好得多。
     *
     * 可见性为 `internal`（而非 `private`）的唯一原因：让 `LinearResamplerTest` 能直接
     * 覆盖它。本机 Gradle 测试 worker 无法启动，该测试只在 CI 上跑。
     */
    internal class LinearResampler(
        inRate: Int,
        outRate: Int,
        private val sink: (Float, Float) -> Unit
    ) {
        private val ratio = inRate.toDouble() / outRate.toDouble()

        private var prevL = 0f
        private var prevR = 0f
        private var curL = 0f
        private var curR = 0f
        /** 最近一次 push 的输入帧索引，-1 表示尚未收到任何帧 */
        private var inIndex = -1L
        /** 已产出的输出帧数 */
        private var outIndex = 0L

        fun push(l: Float, r: Float) {
            prevL = curL
            prevR = curR
            curL = l
            curR = r
            inIndex++
            if (inIndex == 0L) {
                // 首帧没有前驱样本，prev 与 cur 都取它自身
                prevL = l
                prevR = r
                return
            }
            // 发出坐标落在 [inIndex-1, inIndex) 的输出（frac ∈ [0,1)，取 prev/cur 插值）
            while (outIndex * ratio < inIndex.toDouble()) {
                val frac = (outIndex * ratio - (inIndex - 1)).toFloat()
                sink(prevL + (curL - prevL) * frac, prevR + (curR - prevR) * frac)
                outIndex++
            }
        }

        /** 冲刷尾部：末帧之后的输出样本只能钳制到最后一个输入样本 */
        fun flush() {
            if (inIndex < 0L) return
            val last = inIndex.toDouble()
            while (outIndex * ratio <= last) {
                val frac = (outIndex * ratio - (last - 1)).toFloat().coerceIn(0f, 1f)
                sink(prevL + (curL - prevL) * frac, prevR + (curR - prevR) * frac)
                outIndex++
            }
        }
    }

    /**
     * 把 16-bit 有符号样本以小端序写入缓冲区指定偏移（PCM WAV 的字节序）。
     * 供 [separate] 的批量写出缓冲使用，避免每帧分配短命字节数组。
     */
    private fun putShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
