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
        // 下载候选 URL：优先国内镜像 hf-mirror.com（加速大陆下载），失败后回退官方 HuggingFace
        private val MODEL_URLS = listOf(
            "https://hf-mirror.com/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx",
            "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx"
        )
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
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(baseDir, "models")
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
     * @return 下载成功返回 null；失败返回具体错误提示（供 UI 显示）
     */
    suspend fun downloadModel(
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val modelsDir = getModelsDir()
        val tempFile = File(modelsDir, "$MODEL_FILENAME.download")
        val finalFile = getModelFile()

        // 依次尝试每个候选 URL（先镜像后官方），全部失败返回最后的错误信息
        var lastError: String? = null
        for (urlStr in MODEL_URLS) {
            tempFile.delete()
            val err = tryDownloadUrl(urlStr, tempFile, onProgress)
            if (err == null) {
                // 下载完成，检查文件大小
                if (tempFile.length() < EXPECTED_SIZE_BYTES * 0.8) {
                    val got = tempFile.length() / (1024 * 1024)
                    lastError = "下载文件大小异常（${got}MB，预期约${EXPECTED_SIZE_BYTES / (1024 * 1024)}MB）"
                    AppLog.e(TAG, "downloadModel: file too small (${tempFile.length()} bytes), expected ~${EXPECTED_SIZE_BYTES}")
                    tempFile.delete()
                    continue
                }

                // 原子重命名（先删旧文件，再重命名新文件）
                if (finalFile.exists()) {
                    finalFile.delete()
                }
                if (!tempFile.renameTo(finalFile)) {
                    lastError = "文件保存失败（重命名失败）"
                    AppLog.e(TAG, "downloadModel: rename failed")
                    tempFile.delete()
                    continue
                }

                AppLog.d(TAG, "downloadModel: success from $urlStr, size = ${getModelSizeMB()}MB")
                return@withContext null
            } else {
                lastError = err
                AppLog.w(TAG, "downloadModel: failed from $urlStr, trying next... ($err)")
            }
        }
        tempFile.delete()
        lastError ?: "所有下载源均失败，请检查网络后重试"
    }

    /**
     * 从单个 URL 下载模型到临时文件
     *
     * @return 下载成功返回 null；失败返回具体错误信息
     */
    private fun tryDownloadUrl(
        urlStr: String,
        tempFile: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): String? {
        return try {
            AppLog.d(TAG, "tryDownloadUrl: starting download from $urlStr")

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "NASMusicTV/2.22")
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                AppLog.e(TAG, "tryDownloadUrl: HTTP ${connection.responseCode}")
                return "服务器返回错误（HTTP ${connection.responseCode}）"
            }

            val totalBytes = connection.contentLength.toLong()
            AppLog.d(TAG, "tryDownloadUrl: total size = ${totalBytes / (1024 * 1024)}MB")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, true).use { output ->
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
            null
        } catch (e: Exception) {
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "连接超时，请检查网络"
                is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络/DNS"
                is java.io.FileNotFoundException -> "服务器上未找到模型文件（404）"
                else -> "网络异常：${e.message?.take(60) ?: "未知错误"}"
            }
            AppLog.e(TAG, "tryDownloadUrl: failed from $urlStr", e)
            msg
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
    fun getModelDownloadUrl(): String = MODEL_URLS.first()

    /**
     * 获取模型文件名（供 UI 显示）
     */
    fun getModelFilename(): String = MODEL_FILENAME

    /**
     * 获取期望文件大小（MB，供 UI 显示）
     */
    fun getExpectedSizeMB(): Double = EXPECTED_SIZE_BYTES / (1024.0 * 1024.0)
}
