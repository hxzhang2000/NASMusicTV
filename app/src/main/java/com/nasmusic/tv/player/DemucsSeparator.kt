package com.nasmusic.tv.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.nasmusic.tv.util.AppLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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

            // 加载模型（从文件读取字节）
            val modelBytes = modelFile.readBytes()
            modelSession = ortEnv!!.createSession(modelBytes)

            // 读取模型实际输入名（替代硬编码 "input"，避免 Unknown input name 错误）
            inputName = modelSession!!.inputInfo.keys.firstOrNull() ?: "input"

            isInitialized = true
            lastError = null
            AppLog.d(TAG, "initialize: OK, model loaded from $modelPath (${modelBytes.size / (1024 * 1024)}MB), input='$inputName'")
            true
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "initialize: OOM loading model", e)
            lastError = "内存不足，无法加载模型（${modelFile.length() / (1024 * 1024)}MB）"
            System.gc()
            false
        } catch (e: Exception) {
            AppLog.e(TAG, "initialize: failed", e)
            lastError = "模型初始化失败：${e.message?.take(40)}"
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

        return try {
            progress?.onProgress(0f, "解码音频")

            // 1. 解码输入音频为 PCM 浮点（立体声）
            var pcmData = decodeAudio(inputPath, progress)
            if (pcmData == null) {
                AppLog.e(TAG, "separate: decode failed")
                // decodeAudio 内部已设置 lastError
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
            // pcmData 不再需要，释放 ~80MB 帮助 GC
            pcmData = FloatArray(0)

            // 3. 分段处理（overlap-add）
            val vocalsLeft = FloatArray(totalSamples)
            val vocalsRight = FloatArray(totalSamples)
            val weights = FloatArray(totalSamples)  // overlap 权重

            var startSample = 0
            var segmentIndex = 0
            val totalSegments = (totalSamples + SEGMENT_SAMPLES - 1) / SEGMENT_SAMPLES

            while (startSample < totalSamples) {
                val endSample = minOf(startSample + SEGMENT_SAMPLES, totalSamples)
                val segLen = endSample - startSample

                // 提取当前段（带 overlap padding）
                val padLeft = minOf(OVERLAP_SAMPLES, startSample)
                val padRight = minOf(OVERLAP_SAMPLES, totalSamples - endSample)
                val segLeft = extractSegmentWithPadding(leftChannel, startSample, segLen, padLeft, padRight)
                val segRight = extractSegmentWithPadding(rightChannel, startSample, segLen, padLeft, padRight)

                // ONNX 推理
                val (vocL, vocR) = processSegment(segLeft, segRight)

                // overlap-add 回写
                val writeStart = startSample - padLeft
                val writeLen = padLeft + segLen + padRight
                for (i in 0 until writeLen) {
                    val idx = writeStart + i
                    if (idx in 0 until totalSamples) {
                        vocalsLeft[idx] += vocL[i]
                        vocalsRight[idx] += vocR[i]
                        weights[idx] += 1.0f
                    }
                }

                // 进度（节流：只在实际百分比变化时更新）
                segmentIndex++
                progress?.onProgress(0.2f + segmentIndex.toFloat() / totalSegments * 0.6f, "分离中 ($segmentIndex/$totalSegments)")

                // 移动到下一段
                startSample += SEGMENT_SAMPLES
            }

            // 归一化 overlap 权重
            for (i in 0 until totalSamples) {
                if (weights[i] > 0) {
                    vocalsLeft[i] /= weights[i]
                    vocalsRight[i] /= weights[i]
                }
            }
            // weights 不再需要
            // (FloatArray 不可变长度，但内容已用完)

            progress?.onProgress(0.85f, "写入人声")

            // 4. 写入人声 WAV（先写人声，写完可释放 vocals 数组）
            val vocalsFile = File(outputDir, "${songId}_vocals.wav")
            writeWav(vocalsFile, mergeChannels(vocalsLeft, vocalsRight))

            progress?.onProgress(0.9f, "写入伴奏")

            // 5. 写入伴奏 WAV（内联计算 original - vocals，不分配额外数组）
            val accompanimentFile = File(outputDir, "${songId}_accompaniment.wav")
            writeAccompanimentWav(accompanimentFile, leftChannel, rightChannel, vocalsLeft, vocalsRight)

            // 6. 计算时长
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
     * 处理单段音频：ONNX 推理提取 vocals
     *
     * @param leftChannel 左声道样本
     * @param rightChannel 右声道样本
     * @return (vocalsLeft, vocalsRight)
     */
    private fun processSegment(
        leftChannel: FloatArray,
        rightChannel: FloatArray
    ): Pair<FloatArray, FloatArray> {
        val segLen = minOf(leftChannel.size, SEGMENT_SAMPLES)

        // 准备输入 tensor [1, 2, samples]
        val inputData = FloatArray(2 * segLen)
        for (i in 0 until segLen) {
            inputData[i] = leftChannel[i]
            inputData[i + segLen] = rightChannel[i]
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnv!!,
            FloatBuffer.wrap(inputData),
            longArrayOf(1, 2, segLen.toLong())
        )

        // ONNX 推理
        val output = modelSession!!.run(mapOf(inputName to inputTensor))

        // 输出 shape: [1, 4, 2, samples]
        @Suppress("UNCHECKED_CAST")
        val outputData = output[0].value as Array<Array<Array<FloatArray>>>

        // 提取 vocals stem (index=3)
        val vocalsLeft = FloatArray(segLen)
        val vocalsRight = FloatArray(segLen)
        for (i in 0 until segLen) {
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

    /**
     * 合并左右声道
     */
    private fun mergeChannels(left: FloatArray, right: FloatArray): FloatArray {
        val merged = FloatArray(left.size * 2)
        for (i in left.indices) {
            merged[i * 2] = left[i]
            merged[i * 2 + 1] = right[i]
        }
        return merged
    }

    /**
     * 写入 WAV 文件
     */
    private fun writeWav(file: File, samples: FloatArray) {
        val numSamples = samples.size
        val dataSize = numSamples * 2 // 16-bit = 2 bytes per sample
        val fileSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(fileSize))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16)) // chunk size
            fos.write(shortToByteArray(1)) // PCM format
            fos.write(shortToByteArray(CHANNEL_COUNT.toShort()))
            fos.write(intToByteArray(SAMPLE_RATE))
            fos.write(intToByteArray(SAMPLE_RATE * CHANNEL_COUNT * 2)) // byte rate
            fos.write(shortToByteArray((CHANNEL_COUNT * 2).toShort())) // block align
            fos.write(shortToByteArray(16)) // bits per sample

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            // PCM data
            for (sample in samples) {
                val pcm = (sample * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                fos.write(shortToByteArray(pcm))
            }
        }
    }

    /**
     * 写入伴奏 WAV 文件（内联计算 original - vocals，不分配额外数组）
     * 省去 ~80MB 临时数组（accompanimentLeft + accompanimentRight + mergeChannels 的 merged）
     */
    private fun writeAccompanimentWav(
        file: File,
        origLeft: FloatArray,
        origRight: FloatArray,
        vocalsLeft: FloatArray,
        vocalsRight: FloatArray
    ) {
        val totalSamples = origLeft.size
        val numPcmSamples = totalSamples * CHANNEL_COUNT
        val dataSize = numPcmSamples * 2
        val fileSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(fileSize))
            fos.write("WAVE".toByteArray())

            // fmt chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16))
            fos.write(shortToByteArray(1))
            fos.write(shortToByteArray(CHANNEL_COUNT.toShort()))
            fos.write(intToByteArray(SAMPLE_RATE))
            fos.write(intToByteArray(SAMPLE_RATE * CHANNEL_COUNT * 2))
            fos.write(shortToByteArray((CHANNEL_COUNT * 2).toShort()))
            fos.write(shortToByteArray(16))

            // data chunk
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            // 伴奏 = original - vocals，内联计算逐样本写入
            for (i in 0 until totalSamples) {
                val left = (origLeft[i] - vocalsLeft[i]) * 32767f
                val right = (origRight[i] - vocalsRight[i]) * 32767f
                fos.write(shortToByteArray(left.toInt().coerceIn(-32768, 32767).toShort()))
                fos.write(shortToByteArray(right.toInt().coerceIn(-32768, 32767).toShort()))
            }
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
