package com.nasmusic.tv.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.nasmusic.tv.R
import com.nasmusic.tv.util.AppLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
        // 模型输出 shape: [1, 4, 2, samples]
        private val OUTPUT_SHAPE = longArrayOf(1, 4, 2, SEGMENT_SAMPLES.toLong())
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
                inputName = modelSession!!.inputInfo.keys.firstOrNull() ?: "input"

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
     * 释放资源
     */
    fun release() {
        if (!opMutex.tryLock()) {
            // 有分离正在进行：延迟到其结束后释放，避免关闭正在推理的 session
            pendingRelease = true
            return
        }
        try {
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
    ): SeparationResult? = opMutex.withLock {
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

            // 写出一帧（人声 + 伴奏）；全局位置越界（末段零填充区）则跳过
            fun emit(gi: Int, l: Float, r: Float, origL: Float, origR: Float) {
                if (gi >= totalSamples) return
                vocalsFos!!.write(shortToByteArray((l * 32767f).toInt().coerceIn(-32768, 32767).toShort()))
                vocalsFos!!.write(shortToByteArray((r * 32767f).toInt().coerceIn(-32768, 32767).toShort()))
                accFos!!.write(shortToByteArray(((origL - l) * 32767f).toInt().coerceIn(-32768, 32767).toShort()))
                accFos!!.write(shortToByteArray(((origR - r) * 32767f).toInt().coerceIn(-32768, 32767).toShort()))
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
            // 分离期间收到 release 请求 → 此刻安全释放
            if (pendingRelease) releaseInternal()
        }
    }

    /**
     * 解码音频并写入临时文件（不在 JVM 堆中保留完整 FloatArray）
     *
     * 临时文件格式：原始 big-endian float32 交织立体声（L0,R0,L1,R1,...）
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

            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else SAMPLE_RATE
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else CHANNEL_COUNT
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            // 输出通道数固定为 2（立体声），模型要求
            val outChannels = 2

            var totalFloatsWritten = 0L
            var inputDone = false
            var outputDone = false
            var lastDecodeProgressReport = 0

            // 复用预分配的 float32 写入缓冲（BIG_ENDIAN，与 readFloat 一致），
            // 批量写入减少逐样本 IO；flush 剩余不足一段的字节。
            // 临时文件为连续交织 float32（L0,R0,L1,R1,...），无段间填充。
            val writeBuf = ByteArray(64 * 1024) // 64KB 缓冲
            var writeBufPos = 0

            FileOutputStream(tempFile).use { fos ->
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

                            // 读取 shorts，转为 floats，写入临时文件（BIG_ENDIAN，与 readFloat 一致）
                            // MediaCodec 输出是 interleaved PCM 16-bit
                            while (outputBuffer.remaining() >= 2) {
                                val left = outputBuffer.short.toFloat() / 32768f
                                val right = if (outputBuffer.remaining() >= 2) {
                                    outputBuffer.short.toFloat() / 32768f
                                } else left  // 奇数样本时复制

                                // 预分配缓冲区写满即 flush，避免逐样本 fos.write
                                if (writeBufPos + 8 > writeBuf.size) {
                                    fos.write(writeBuf, 0, writeBufPos)
                                    writeBufPos = 0
                                }
                                val lb = java.lang.Float.floatToIntBits(left)
                                val rb = java.lang.Float.floatToIntBits(right)
                                // 显式 BIG_ENDIAN 写入，保证与 DataInputStream.readFloat() 一致
                                writeBuf[writeBufPos++] = (lb ushr 24).toByte()
                                writeBuf[writeBufPos++] = (lb ushr 16).toByte()
                                writeBuf[writeBufPos++] = (lb ushr 8).toByte()
                                writeBuf[writeBufPos++] = lb.toByte()
                                writeBuf[writeBufPos++] = (rb ushr 24).toByte()
                                writeBuf[writeBufPos++] = (rb ushr 16).toByte()
                                writeBuf[writeBufPos++] = (rb ushr 8).toByte()
                                writeBuf[writeBufPos++] = rb.toByte()
                                totalFloatsWritten += 2
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
                    }
                }

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

            val totalSamples = (totalFloatsWritten / outChannels).toInt()

            AppLog.d(TAG, "decodeAudioToTempFile: wrote $totalFloatsWritten floats ($totalSamples stereo samples), temp=${tempFile.absolutePath}, size=${tempFile.length() / (1024*1024)}MB")
            return DecodeResult(totalSamples, sampleRate, outChannels, tempFile)
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

        val output = session.run(mapOf(modelInputName to inputTensor))

        @Suppress("UNCHECKED_CAST")
        val outputData = output[0].value as Array<Array<Array<FloatArray>>>

        val vocalsLeft = FloatArray(actualLen)
        val vocalsRight = FloatArray(actualLen)
        for (i in 0 until actualLen) {
            vocalsLeft[i] = outputData[0][VOCALS_INDEX][0][i]
            vocalsRight[i] = outputData[0][VOCALS_INDEX][1][i]
        }

        inputTensor.close()
        output.close()

        return Pair(vocalsLeft, vocalsRight)
    }

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
}
