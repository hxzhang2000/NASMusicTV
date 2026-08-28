package com.nasmusic.tv.player

import android.content.Context
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HT-Demucs FT 人声分离模型下载管理器
 *
 * 负责：
 * 1. 检查模型是否已下载
 * 2. 从 HuggingFace 下载模型（带进度回调）
 * 3. 管理模型文件（删除、获取大小）
 * 4. 模型存储位置：context.getExternalFilesDir(null)/models/
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloadManager"

        // HT-Demucs FT Vocals Specialist (FP16, ~166MB)
        // Source: https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx
        private const val MODEL_URL = "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx"
        private const val MODEL_FILENAME = "htdemucs_ft_vocals.onnx"

        // 期望文件大小（~166MB），允许 10% 误差
        private const val EXPECTED_SIZE_BYTES = 166_000_000L

        // 连接超时
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    /**
     * 获取模型目录
     */
    private fun getModelsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 获取模型文件路径
     */
    fun getModelFile(): File {
        return File(getModelsDir(), MODEL_FILENAME)
    }

    /**
     * 检查模型是否已下载（文件存在且大小合理）
     */
    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > EXPECTED_SIZE_BYTES * 0.8  // 允许 20% 误差（FP16 精确大小可能有差异）
    }

    /**
     * 获取模型文件路径（已下载时返回，否则返回 null）
     */
    fun getModelPath(): String? {
        return if (isModelDownloaded()) getModelFile().absolutePath else null
    }

    /**
     * 获取模型文件大小（MB），未下载返回 0.0
     */
    fun getModelSizeMB(): Double {
        val file = getModelFile()
        return if (file.exists()) file.length() / (1024.0 * 1024.0) else 0.0
    }

    /**
     * 获取模型文件大小（字节），未下载返回 0
     */
    fun getModelSizeBytes(): Long {
        return getModelFile().let { if (it.exists()) it.length() else 0L }
    }

    /**
     * 下载模型文件（带进度回调）
     *
     * @param onProgress 进度回调：(已下载字节, 总字节)
     * @return 下载是否成功
     */
    suspend fun downloadModel(
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDir()
        val tempFile = File(modelsDir, "$MODEL_FILENAME.download")
        val finalFile = getModelFile()

        try {
            AppLog.d(TAG, "downloadModel: starting download from $MODEL_URL")

            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "NASMusicTV/2.22")
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLog.e(TAG, "downloadModel: HTTP ${connection.responseCode}")
                return@withContext false
            }

            val totalBytes = connection.contentLength.toLong()
            AppLog.d(TAG, "downloadModel: total size = ${totalBytes / (1024 * 1024)}MB")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        // 回调进度（每 512KB 或完成时）
                        if (downloadedBytes % (512 * 1024) < bytesRead || downloadedBytes == totalBytes) {
                            onProgress(downloadedBytes, if (totalBytes > 0) totalBytes else EXPECTED_SIZE_BYTES)
                        }
                    }
                }
            }

            // 检查下载的文件大小
            if (tempFile.length() < EXPECTED_SIZE_BYTES * 0.8) {
                AppLog.e(TAG, "downloadModel: file too small (${tempFile.length()} bytes), expected ~${EXPECTED_SIZE_BYTES}")
                tempFile.delete()
                return@withContext false
            }

            // 原子重命名（先删旧文件，再重命名新文件）
            if (finalFile.exists()) {
                finalFile.delete()
            }
            val renamed = tempFile.renameTo(finalFile)
            if (!renamed) {
                AppLog.e(TAG, "downloadModel: rename failed")
                tempFile.delete()
                return@withContext false
            }

            AppLog.d(TAG, "downloadModel: success, size = ${getModelSizeMB()}MB")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "downloadModel: failed", e)
            tempFile.delete()
            false
        }
    }

    /**
     * 删除模型文件
     */
    fun deleteModel(): Boolean {
        val file = getModelFile()
        val deleted = file.delete()
        AppLog.d(TAG, "deleteModel: $deleted")
        return deleted
    }

    /**
     * 获取模型下载 URL（供 UI 显示）
     */
    fun getModelDownloadUrl(): String = MODEL_URL

    /**
     * 获取模型文件名（供 UI 显示）
     */
    fun getModelFilename(): String = MODEL_FILENAME

    /**
     * 获取期望文件大小（MB，供 UI 显示）
     */
    fun getExpectedSizeMB(): Double = EXPECTED_SIZE_BYTES / (1024.0 * 1024.0)
}
