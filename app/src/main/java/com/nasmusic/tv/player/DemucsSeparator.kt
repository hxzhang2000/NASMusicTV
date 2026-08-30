package com.nasmusic.tv.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.nasmusic.tv.util.AppLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

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
        private const val SEGMENT_SAMPLES = 343980  // 7.81s at 44100Hz
        private const val OVERLAP_SAMPLES = 3440     // overlap for smooth transition (~78ms)
        private const val TOTAL_SAMPLES_PER_SEG = SEGMENT_SAMPLES + OVERLAP_SAMPLES * 2

        // 输出 stems: drums=0, bass=1, other=2, vocals=3
        private const val VOCALS_INDEX = 3

        // 模型输入 shape: [1, 2, samples]
        private val INPUT_SHAPE = longArrayOf(1, 2, SEGMENT_SAMPLES.toLong())
        // 模型输出 shape: [1, 4, 2, samples]
        private val OUTPUT_SHAPE = longArrayOf(1, 4, 2, SEGMENT_SAMPLES.toLong())
    }

    private var ortEnv: OrtEnvironment? = null
    private var modelSession: OrtSession? = null
    private var isInitialized = false
    /** ONNX 模型的实际输入名（从 session 动态读取，不用硬编码 "input"） */
    private var inputName: String = "input"

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
                lastError = "模型文件不存在"
                return false
            }

            ortEnv = OrtEnvironment.getEnvironment()

            // 直接从文件路径加载模型，使用 mmap 避免将 166MB 读入 JVM 堆（readBytes 会导致 OOM 崩溃）
            modelSession = ortEnv!!.createSession(modelPath)

            // 读取模型实际输入名（替代硬编码 "input"，避免 Unknown input name 错误）
            inputName = modelSession!!.inputInfo.keys.firstOrNull() ?: "input"

            isInitialized = true
            lastError = null
            AppLog.d(TAG, "initialize: OK, model loaded from $modelPath (${modelFile.length() / (1024 * 1024)}MB), input='$inputName'")
            true
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "initialize: OOM loading model", e)
            lastError = "内存不足，无法加载模型（${modelFile.length() / (1024 * 1024)}MB）"
            System.gc()
            false
        } catch (e: Exception) {
            AppLog.e(TAG, "initialize: failed", e)
            lastError = "模型初始化失败：${e.message?.take(60)}"
            false
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        modelSession?.close()
        ortEnv?.close()
        modelSession = null
        ortEnv = null
        isInitialized = false
    }

    /**
     * 检查是否已初始化
     */
    fun isReady(): Boolean = isInitialized && modelSession != null

    /**
     * 分离人声和伴奏
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
        if (!isReady()) {
            AppLog.e(TAG, "separate: not initialized")
            lastError = "分离器未初始化"
            return null
        }

        // 内存预检：低于 200MB 可用空间时拒绝执行，避免被 LMK 杀掉
        val runtime = Runtime.getRuntime()
        val availableMB = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        if (availableMB < 200) {
            AppLog.e(TAG, "separate: available memory too low: ${availableMB}MB")
            lastError = "可用内存不足（${availableMB}MB < 200MB），请关闭其他应用后重试"
            return null
        }

        return try {
            progress?.onProgress(0f, "解码音频")

            // 1. 解码输入音频为 PCM 浮点（立体声）
            var pcmData = decodeAudio(inputPath, progress)
            if (pcmData == null) {
                AppLog.e(TAG, "separate: decode failed")
                return null
            }

            progress?.onProgress(0.2f, "分段处理")

            // 2. 分离左右声道
            val totalSamples = pcmData.size / 2
            val leftChannel = FloatArray(totalSamples)
            val rightChannel = FloatArray(totalSamples)
            for (i in 0 until totalSamples) {
                leftChannel[i] = pcmData[i * 2]
                rightChannel[i] = pcmData[i * 2 + 1]
            }
            pcmData = FloatArray(0) // 释放 ~60MB

            // 3. 打开输出流，先写 WAV 头（用总采样数计算精确大小）
            val vocalsFile = File(outputDir, "${songId}_vocals.wav")
            val accompanimentFile = File(outputDir, "${songId}_accompaniment.wav")
            val vocalsFos = BufferedOutputStream(FileOutputStream(vocalsFile))
            val accFos = BufferedOutputStream(FileOutputStream(accompanimentFile))
            writeWavHeader(vocalsFos, totalSamples)
            writeWavHeader(accFos, totalSamples)

            // 4. 逐段处理：ONNX 推理 → 直接写入文件（不累积全长度 vocals 数组，节省 ~60MB）
            var startSample = 0
            var segmentIndex = 0
            val totalSegments = (totalSamples + SEGMENT_SAMPLES - 1) / SEGMENT_SAMPLES
            val segmentInputBuf = FloatArray(2 * SEGMENT_SAMPLES) // 预分配，每段复用

            while (startSample < totalSamples) {
                val endSample = minOf(startSample + SEGMENT_SAMPLES, totalSamples)
                val segLen = endSample - startSample

                // 提取当前段左/右声道
                for (i in 0 until segLen) {
                    segmentInputBuf[i] = leftChannel[startSample + i]
                    segmentInputBuf[i + SEGMENT_SAMPLES] = rightChannel[startSample + i]
                }

                // ONNX 推理
                val (vocL, vocR) = processSegmentFromBuffer(segmentInputBuf, segLen)

                // 写入人声段（交织立体声 PCM 16-bit）
                for (i in vocL.indices) {
                    vocalsFos.write(shortToByteArray((vocL[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()))
                    vocalsFos.write(shortToByteArray((vocR[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()))
                }

                // 写入伴奏段（original - vocals，逐样本内联计算）
                for (i in 0 until segLen) {
                    val left = ((leftChannel[startSample + i] - vocL[i]) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    val right = ((rightChannel[startSample + i] - vocR[i]) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    accFos.write(shortToByteArray(left))
                    accFos.write(shortToByteArray(right))
                }

                segmentIndex++
                progress?.onProgress(0.2f + segmentIndex.toFloat() / totalSegments * 0.7f, "分离中 ($segmentIndex/$totalSegments)")

                startSample += SEGMENT_SAMPLES
            }

            vocalsFos.close()
            accFos.close()

            // 释放大数组
            val durationMs = (totalSamples.toFloat() / SAMPLE_RATE * 1000).toLong()

            progress?.onProgress(1f, "完成")

            AppLog.d(TAG, "separate: OK, vocals=${vocalsFile.absolutePath}, accompaniment=${accompanimentFile.absolutePath}")
            lastError = null
            SeparationResult(vocalsFile, accompanimentFile, durationMs)
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "separate: OOM — 设备内存不足，无法完成高质量分离", e)
            lastError = "内存不足（模型166MB+音频数据），设备内存不够"
            System.gc()
            null
        } catch (e: Exception) {
            AppLog.e(TAG, "separate: failed", e)
            lastError = "分离异常：${e.message?.take(40)}"
            null
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
        actualLen: Int
    ): Pair<FloatArray, FloatArray> {
        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!,
            FloatBuffer.wrap(inputBuf),
            INPUT_SHAPE  // [1, 2, 343980]
        )

        val output = modelSession!!.run(mapOf(inputName to inputTensor))

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

    /**
     * 处理单段音频：ONNX 推理提取 vocals
     *
     * @param leftChannel 左声道样本（长度 ≤ SEGMENT_SAMPLES）
     * @param rightChannel 右声道样本
     * @return (vocalsLeft, vocalsRight)，长度 = leftChannel.size
     */
    private fun processSegment(
        leftChannel: FloatArray,
        rightChannel: FloatArray
    ): Pair<FloatArray, FloatArray> {
        val actualLen = leftChannel.size  // 最后一段可能 < SEGMENT_SAMPLES

        // 总是 pad 到 SEGMENT_SAMPLES（模型要求固定输入 shape [1,2,343980]）
        // 缺少的部分用零填充
        val inputData = FloatArray(2 * SEGMENT_SAMPLES)
        for (i in 0 until actualLen) {
            inputData[i] = leftChannel[i]
            inputData[i + SEGMENT_SAMPLES] = rightChannel[i]
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!,
            FloatBuffer.wrap(inputData),
            INPUT_SHAPE  // [1, 2, 343980]
        )

        // ONNX 推理
        val output = modelSession!!.run(mapOf(inputName to inputTensor))

        // 输出 shape: [1, 4, 2, 343980]，只取前 actualLen 个样本
        @Suppress("UNCHECKED_CAST")
        val outputData = output[0].value as Array<Array<Array<FloatArray>>>

        // 提取 vocals stem (index=3)
        val vocalsLeft = FloatArray(actualLen)
        val vocalsRight = FloatArray(actualLen)
        for (i in 0 until actualLen) {
            vocalsLeft[i] = outputData[0][VOCALS_INDEX][0][i]
            vocalsRight[i] = outputData[0][VOCALS_INDEX][1][i]
        }

        // 释放 tensor
        inputTensor.close()
        output.close()

        return Pair(vocalsLeft, vocalsRight)
    }

    /**
     * 提取带 padding 的段（用于 overlap-add）
     */
    private fun extractSegmentWithPadding(
        channel: FloatArray,
        start: Int,
        segLen: Int,
        padLeft: Int,
        padRight: Int
    ): FloatArray {
        val totalLen = padLeft + segLen + padRight
        val segment = FloatArray(totalLen)

        // 左 padding（从前面复制）
        for (i in 0 until padLeft) {
            segment[i] = channel[start - padLeft + i]
        }

        // 主体
        for (i in 0 until segLen) {
            segment[padLeft + i] = channel[start + i]
        }

        // 右 padding（从后面复制）
        for (i in 0 until padRight) {
            segment[padLeft + segLen + i] = channel[start + segLen + i]
        }

        return segment
    }

    /**
     * 解码音频文件为 PCM 浮点数据（立体声）
     *
     * 优化：用预分配 FloatArray 替换 mutableListOf<Float>，避免装箱开销
     * （装箱 Float 对象 ~16 字节 vs FloatArray 4 字节/元素，省 ~75% 内存）
     */
    private fun decodeAudio(inputPath: String, progress: ProgressCallback?): FloatArray? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            // 查找音频轨道
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
                lastError = "音频文件无音轨（格式不支持?）"
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val codec = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME)!!
            )
            codec.configure(format, null, null, 0)
            codec.start()

            // 预分配 FloatArray：基于 MediaFormat 的 duration/sampleRate/channelCount 估算
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else SAMPLE_RATE
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else CHANNEL_COUNT
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            // 估算样本数（+10% 余量应对编码器 delay/padding）
            val estimatedSamples = if (durationUs > 0) {
                (durationUs.toDouble() * sampleRate / 1_000_000.0 * channelCount * 1.1).toInt()
            } else {
                // 无时长信息，初始 30 秒容量，动态增长
                sampleRate * channelCount * 30
            }

            var pcmData = FloatArray(estimatedSamples)
            var writeIdx = 0
            var inputDone = false
            var outputDone = false
            // 解码进度：每解码 10% 更新一次
            var lastDecodeProgressReport = 0
            val decodeProgressInterval = if (durationUs > 0) (durationUs / 10) else Long.MAX_VALUE

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

                        // 直接写入预分配 FloatArray，避免装箱开销
                        while (outputBuffer.hasRemaining()) {
                            if (writeIdx >= pcmData.size) {
                                // 扩容 1.5 倍，减少拷贝次数
                                pcmData = pcmData.copyOf((pcmData.size * 1.5).toInt())
                            }
                            pcmData[writeIdx++] = outputBuffer.short.toFloat() / 32768f
                        }

                        // 解码进度（节流：每10%更新一次，从0%到20%）
                        if (durationUs > 0) {
                            val ptsMs = bufferInfo.presentationTimeUs / 1000
                            val progress10 = (ptsMs * 10 / (durationUs / 1000)).toInt()
                            if (progress10 > lastDecodeProgressReport && progress10 <= 10) {
                                lastDecodeProgressReport = progress10
                                progress?.onProgress(progress10.toFloat() * 0.2f, "解码音频 (${progress10 * 10}%)")
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            // 修剪到实际大小
            if (writeIdx < pcmData.size) {
                pcmData = pcmData.copyOf(writeIdx)
            }

            AppLog.d(TAG, "decodeAudio: decoded $writeIdx samples, ${writeIdx / 2 / sampleRate}s at ${sampleRate}Hz")
            pcmData
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "decodeAudio: OOM", e)
            lastError = "解码内存不足（音频过长?）"
            System.gc()
            null
        } catch (e: Exception) {
            AppLog.e(TAG, "decodeAudio: failed", e)
            lastError = "音频解码失败：${e.message?.take(30)}"
            null
        }
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
