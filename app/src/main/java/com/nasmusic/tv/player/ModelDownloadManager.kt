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
class ModelDownloadManager(
    private val context: Context,
    /**
     * 自定义下载 URL 提供方（每次下载时读取，空串表示用内置候选列表）。
     * 由 AppPreferences.getModelDownloadUrlSync() 提供——用户可指向自建镜像 / NAS。
     */
    private val customUrlProvider: () -> String = { "" }
) {

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

        // 期望文件大小（fp16 权重文件的精确字节数），允许 10% 误差仅用于 UI 展示/粗判
        private const val EXPECTED_SIZE_BYTES = 165_612_636L

        /**
         * 模型 SHA-256（2026-09-14，P1-4）。
         *
         * 取自 HuggingFace LFS 元数据的 `oid`——对 LFS 对象而言 oid **就是** SHA-256：
         * https://huggingface.co/api/models/StemSplitio/htdemucs-ft-vocals-onnx/tree/main
         *   path=htdemucs_ft_vocals_fp16weights.onnx
         *   size=165612636
         *   oid =0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce
         *
         * 旧实现只校验「> 0.8 × 166MB」，截断、镜像站返回错误页面、串流错位都能通过，
         * 之后表现为 createSession 失败或推理结果异常，用户只看到含糊报错。
         *
         * ⚠️ 该校验对自定义 URL（customUrlProvider）同样生效——自定义源的用途是
         * 「自建镜像 / NAS」，应当提供字节完全一致的文件。若确实要换成不同的模型
         * 权重，必须同步更新此常量，否则下载会被拒绝（报「SHA-256 不匹配」）。
         */
        internal const val EXPECTED_SHA256 =
            "0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce"

        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        /**
         * 计算文件 SHA-256（小写十六进制）。
         * 手工拼 hex 而不用 `"%02x".format()`：后者依赖 Formatter 对 Byte 的无符号处理，
         * 且受默认 Locale 影响，这里用确定性实现。
         *
         * P2-11（2026-09-16）：从实例方法移到 companion 并放宽为 internal，
         * 供 `ModelTransferServer`（上传路径）复用同一实现与同一期望哈希——
         * 否则「下载有校验、上传没校验」会给模型完整性留一个后门。
         */
        internal fun sha256Of(file: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(file).use { input ->
                val buf = ByteArray(256 * 1024)
                var n = input.read(buf)
                while (n != -1) {
                    digest.update(buf, 0, n)
                    n = input.read(buf)
                }
            }
            val bytes = digest.digest()
            val hex = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                hex.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
            }
            return hex.toString()
        }

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
     * 检查模型是否已下载（快速判定：文件存在且大小落在期望值 ±1% 内）
     *
     * 2026-09-14（P1-4）说明：本方法可能被 UI/主线程调用，因此**不做** 166MB 的哈希
     * 计算（那会 ANR）。真正的完整性由两处把关：
     * - [downloadModel] 下载完成后立即校验 SHA-256，不匹配则删除并尝试下一个源
     * - [verifyModelIntegrity] 在加载模型前于 IO 线程做一次全文件校验
     *
     * 2026-09-14（报告 §四-2）：阈值由原来的 `> EXPECTED_SIZE_BYTES * 0.8`（−20%，
     * 约 132.5MB）收紧到 ±1%（163,956,509 ~ 167,268,762 字节）。原阈值过宽，
     * 截断到 133MB 的残缺文件、或镜像返回的 HTML 错误页都能通过这道快速判定。
     * 而且它只有下界没有上界，超大垃圾文件同样能过。FP16 权重的字节数是确定的
     * （[EXPECTED_SHA256] 已锁定具体文件），不需要 20% 余量。
     */
    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        if (!file.exists()) return false
        val length = file.length()
        return length in (EXPECTED_SIZE_BYTES * 99 / 100)..(EXPECTED_SIZE_BYTES * 101 / 100)
    }

    /**
     * 校验已安装模型的 SHA-256 是否与 [EXPECTED_SHA256] 一致。
     *
     * 完整读一遍 166MB 文件，耗时约 0.3~1s，**必须在 IO 线程调用**，且不要放进
     * UI 判定路径（如按钮可用性）。用于加载模型前的最后一道完整性闸门。
     *
     * @return true 表示文件存在且哈希匹配
     */
    suspend fun verifyModelIntegrity(): Boolean = withContext(Dispatchers.IO) {
        val file = getModelFile()
        if (!file.exists()) {
            AppLog.w(TAG, "verifyModelIntegrity: model file not found")
            return@withContext false
        }
        val actual = try {
            sha256Of(file)
        } catch (e: Exception) {
            AppLog.e(TAG, "verifyModelIntegrity: hashing failed", e)
            return@withContext false
        }
        val ok = actual.equals(EXPECTED_SHA256, ignoreCase = true)
        if (ok) {
            AppLog.d(TAG, "verifyModelIntegrity: OK (${file.length()} bytes)")
        } else {
            AppLog.e(TAG, "verifyModelIntegrity: SHA-256 mismatch, size=${file.length()}, expected=$EXPECTED_SHA256, actual=$actual")
        }
        ok
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

        // 自定义 URL 优先（用户可在设置中指向自建镜像/NAS）；随后回退内置候选列表
        val custom = customUrlProvider().trim()
        val urls = if (custom.isNotEmpty()) listOf(custom) + MODEL_URLS else MODEL_URLS

        // 依次尝试每个候选 URL（先镜像后官方），全部失败返回最后的错误信息
        var lastError: String? = null
        for (urlStr in urls) {
            tempFile.delete()
            val err = tryDownloadUrl(urlStr, tempFile, onProgress)
            if (err == null) {
                // 2026-09-14（P1-4）：下载完成后校验 SHA-256。
                // 旧实现只判「> 0.8 × 166MB」，截断的响应、镜像站返回的错误页、
                // 串流错位都能通过，之后 createSession 失败或推理结果异常，
                // 用户只看到含糊的错误提示。SHA-256 是唯一的权威判据。
                val actualSha = try {
                    sha256Of(tempFile)
                } catch (e: Exception) {
                    AppLog.e(TAG, "downloadModel: hashing failed", e)
                    null
                }
                if (actualSha == null || !actualSha.equals(EXPECTED_SHA256, ignoreCase = true)) {
                    val got = tempFile.length() / (1024 * 1024)
                    lastError = if (actualSha == null) {
                        "模型校验失败（无法读取下载文件）"
                    } else {
                        "模型校验失败（SHA-256 不匹配，已下载 ${got}MB）"
                    }
                    AppLog.e(TAG, "downloadModel: integrity check failed from $urlStr, size=${tempFile.length()}, expected=$EXPECTED_SHA256, actual=$actualSha")
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

                AppLog.d(TAG, "downloadModel: success from $urlStr, size = ${getModelSizeMB()}MB, sha256 verified")
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
    fun getModelDownloadUrl(): String = customUrlProvider().trim().ifEmpty { MODEL_URLS.first() }

    /** 获取内置默认候选 URL 列表（供 UI 展示"默认源"提示） */
    fun getDefaultModelUrls(): List<String> = MODEL_URLS

    /**
     * 获取模型文件名（供 UI 显示）
     */
    fun getModelFilename(): String = MODEL_FILENAME

    /**
     * 获取期望文件大小（MB，供 UI 显示）
     */
    fun getExpectedSizeMB(): Double = EXPECTED_SIZE_BYTES / (1024.0 * 1024.0)
}
