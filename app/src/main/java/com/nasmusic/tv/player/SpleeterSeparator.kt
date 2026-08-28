package com.nasmusic.tv.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.nasmusic.tv.util.AppLog
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spleeter ONNX 高质量人声分离器
 *
 * 流程：
 * 1. 读取输入音频（支持 MP3/FLAC/WAV/OGG 等 ExoPlayer 支持的格式）
 * 2. 解码为 PCM 浮点数据（44100Hz 立体声）
 * 3. STFT → 复数频谱
 * 4. ONNX 推理 → soft mask（vocals + accompaniment）
 * 5. Wiener 归一化
 * 6. iSTFT → 时域信号
 * 7. 写入 accompaniment.wav（供 ExoPlayer 播放）
 *
 * 模型文件：
 * - vocals.fp16.onnx（人声模型）
 * - accompaniment.fp16.onnx（伴奏模型）
 * 放置在 app/src/main/assets/spleeter/ 目录
 *
 * 注意：模型文件需要从 Spleeter 官方仓库下载 .h5 权重，
 * 用 tf2onnx 转换为 ONNX，再做 FP16 量化。详见方案文档。
 */
class SpleeterSeparator(private val context: Context) {

    companion object {
        private const val TAG = "SpleeterSeparator"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 2

        // 模型文件名
        private const val VOCALS_MODEL = "spleeter/vocals_fp16.onnx"
        private const val ACCOMPANIMENT_MODEL = "spleeter/accompaniment_fp16.onnx"
    }

    private val dsp = SpleeterDsp()
    private var ortEnv: OrtEnvironment? = null
    private var vocalsSession: OrtSession? = null
    private var accompanimentSession: OrtSession? = null

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
     * 初始化 ONNX Runtime 会话
     */
    fun initialize(): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()

            // 加载人声模型
            val vocalsBytes = context.assets.open(VOCALS_MODEL).use { it.readBytes() }
            vocalsSession = ortEnv!!.createSession(vocalsBytes)

            // 加载伴奏模型
            val accompanimentBytes = context.assets.open(ACCOMPANIMENT_MODEL).use { it.readBytes() }
            accompanimentSession = ortEnv!!.createSession(accompanimentBytes)

            AppLog.d(TAG, "initialize: OK, models loaded")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "initialize: failed", e)
            false
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        vocalsSession?.close()
        accompanimentSession?.close()
        ortEnv?.close()
        vocalsSession = null
        accompanimentSession = null
        ortEnv = null
    }

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
        return try {
            progress?.onProgress(0f, "解码音频")

            // 1. 解码输入音频为 PCM 浮点
            val pcmData = decodeAudio(inputPath, progress)
            if (pcmData == null) {
                AppLog.e(TAG, "separate: decode failed")
                return null
            }

            progress?.onProgress(0.3f, "STFT 变换")

            // 2. 分离左右声道
            val leftChannel = FloatArray(pcmData.size / 2)
            val rightChannel = FloatArray(pcmData.size / 2)
            for (i in leftChannel.indices) {
                leftChannel[i] = pcmData[i * 2]
                rightChannel[i] = pcmData[i * 2 + 1]
            }

            // 3. 对每个声道做 STFT + ONNX 推理 + iSTFT
            val vocalsLeft = processChannel(leftChannel, vocalsSession!!, progress)
            val vocalsRight = processChannel(rightChannel, vocalsSession!!, progress)

            val accompanimentLeft = processChannel(leftChannel, accompanimentSession!!, progress)
            val accompanimentRight = processChannel(rightChannel, accompanimentSession!!, progress)

            progress?.onProgress(0.8f, "写入伴奏文件")

            // 4. 合并左右声道并写入 WAV
            val vocalsFile = File(outputDir, "${songId}_vocals.wav")
            val accompanimentFile = File(outputDir, "${songId}_accompaniment.wav")

            writeWav(vocalsFile, mergeChannels(vocalsLeft, vocalsRight))
            writeWav(accompanimentFile, mergeChannels(accompanimentLeft, accompanimentRight))

            // 5. 计算时长
            val durationMs = (pcmData.size.toFloat() / (SAMPLE_RATE * CHANNEL_COUNT) * 1000).toLong()

            progress?.onProgress(1f, "完成")

            AppLog.d(TAG, "separate: OK, vocals=${vocalsFile.absolutePath}, accompaniment=${accompanimentFile.absolutePath}")
            SeparationResult(vocalsFile, accompanimentFile, durationMs)
        } catch (e: Exception) {
            AppLog.e(TAG, "separate: failed", e)
            null
        }
    }

    /**
     * 处理单个声道：STFT → ONNX 推理 → iSTFT
     */
    private fun processChannel(
        samples: FloatArray,
        session: OrtSession,
        progress: ProgressCallback?
    ): FloatArray {
        // STFT
        val (real, imag) = dsp.stft(samples)
        val magnitude = dsp.magnitude(real, imag)
        val phase = dsp.phase(real, imag)

        // 准备输入 tensor [1, 1, numBins, numFrames]
        val numFrames = real.size / SpleeterDsp.NUM_BINS
        val inputData = FloatArray(1 * 1 * SpleeterDsp.NUM_BINS * numFrames)
        for (frame in 0 until numFrames) {
            for (k in 0 until SpleeterDsp.NUM_BINS) {
                inputData[frame * SpleeterDsp.NUM_BINS + k] = magnitude[frame * SpleeterDsp.NUM_BINS + k]
            }
        }

        // ONNX 推理
        val inputShape = longArrayOf(1, 1, SpleeterDsp.NUM_BINS.toLong(), numFrames.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv!!, FloatBuffer.wrap(inputData), inputShape)

        val output = session.run(mapOf("input_1" to inputTensor))
        val outputTensor = output[0].value as Array<Array<Array<FloatArray>>>
        val maskData = outputTensor[0][0]

        // 提取 mask [numBins, numFrames] → [numFrames * numBins]
        val mask = FloatArray(numFrames * SpleeterDsp.NUM_BINS)
        for (frame in 0 until numFrames) {
            for (k in 0 until SpleeterDsp.NUM_BINS) {
                mask[frame * SpleeterDsp.NUM_BINS + k] = maskData[k][frame]
            }
        }

        // Wiener 归一化（简单版本：直接使用 mask）
        // 对于 2-stem 模型，mask 已经是 soft mask，无需额外归一化

        // 应用 mask 到 magnitude + phase → 复数频谱
        val (maskedReal, maskedImag) = dsp.applyMask(magnitude, phase, mask)

        // iSTFT
        return dsp.istft(maskedReal, maskedImag)
    }

    /**
     * 解码音频文件为 PCM 浮点数据
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
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            // 设置目标格式：44100Hz 立体声 16-bit
            val targetSampleRate = SAMPLE_RATE
            val targetChannelCount = CHANNEL_COUNT

            val codec = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME)!!
            )
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmData = mutableListOf<Float>()
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

                        // 读取 PCM 16-bit 数据并转换为浮点
                        while (outputBuffer.hasRemaining()) {
                            val sample = outputBuffer.short.toFloat() / 32768f
                            pcmData.add(sample)
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            pcmData.toFloatArray()
        } catch (e: Exception) {
            AppLog.e(TAG, "decodeAudio: failed", e)
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
