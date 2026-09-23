package com.nasmusic.tv.backend.photo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.nasmusic.tv.backend.local.LegacyStorageProbe
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 外接存储来源（**手机 + 电视**）—— U 盘 / SD 卡
 *
 * 两条取路径方式（**同一个来源**，不是两个来源）：
 * - **A 文件遍历（主）**：拿得到真实挂载点路径时走 `File` 遍历。
 *   电视靠 [LegacyStorageProbe]（G7 修复后的存储枚举）提供挂载点。
 * - **B SAF 兜底**：拿不到路径时（手机 Android 11+ 的分区存储）走用户选的目录 tree URI。
 *
 * ## ⛔ 内部存储一律拒绝（决策，§6.3）
 *
 * 两平台统一**只读外接卷**：
 * - 手机：图库已覆盖内部存储的绝大部分照片，再扫一遍纯属重复
 * - 电视：内部存储放照片概率极低，且 API 22 上内外卷判别不可靠
 *
 * 黑名单判定**复用 [LegacyStorageProbe]**（G7 修复时写的那套）——
 * 同一个判定只有一处实现，不会出现「存储枚举拒绝了、照片源又放进来了」。
 *
 * ## ⚠️ 时间单位
 *
 * `File.lastModified()` 与 SAF 的 `COLUMN_LAST_MODIFIED` 都是**毫秒**
 * ⇒ 必须 `/1000` 归一化成**秒**（跨来源去重依赖该契约，§6.1）。
 * 这一点是单测 G1 负向自证的靶子。
 *
 * ## ⚠️ 为什么不探测宽高
 *
 * 要拿宽高得对**每个文件开一次流**（`inJustDecodeBounds`）。U 盘上几千张照片
 * = 几千次 IO，不划算 ⇒ 一律填 `0`，由 `PhotoBuffer` 在解码那一刻现算（§14.2.1）。
 */
class ExternalFilePhotoSource(
    private val context: Context,
    /** 外接存储挂载点（电视：来自 `StorageMonitor`；手机：通常为空） */
    private val fileRootsProvider: () -> List<String> = { emptyList() },
    /** 用户通过 SAF 选中的目录（tree URI 字符串；手机用） */
    private val safTreeUriProvider: () -> String? = { null },
    /** 是否只扫常见目录（`DCIM` / `Pictures`）；只对文件遍历路线生效 */
    private val commonDirsOnlyProvider: () -> Boolean = { true },
) : PhotoSource {

    override val kind: PhotoSourceKind = PhotoSourceKind.EXTERNAL

    override suspend fun status(): PhotoSourceStatus = withContext(Dispatchers.IO) {
        val saf = safTreeUriProvider()
        if (!saf.isNullOrBlank()) {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(saf)) }
                .getOrNull()
            // SAF 授权失效 / 指向内部存储 ⇒ 视为不可用（用户需重选目录）
            if (docId == null || SafDirectoryPolicy.rejectReason(docId) != null) {
                return@withContext PhotoSourceStatus.UNAVAILABLE
            }
            return@withContext PhotoSourceStatus.OK
        }
        val usableRoots = fileRootsProvider().filterNot { LegacyStorageProbe.isInternal(it) }
        if (usableRoots.isEmpty()) PhotoSourceStatus.NO_DIRECTORY else PhotoSourceStatus.OK
    }

    override suspend fun listPhotos(): List<PhotoRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, PhotoRef>()

        // A 文件遍历（主）
        for (root in fileRootsProvider()) {
            if (LegacyStorageProbe.isInternal(root)) {
                AppLog.d(TAG, "skip internal root: $root")
                continue
            }
            walkFileRoot(root, out)
        }

        // B SAF 兜底
        safTreeUriProvider()?.takeIf { it.isNotBlank() }?.let { walkSafTree(it, out) }

        AppLog.d(TAG, "Scanned ${out.size} photos from external storage")
        out.values.toList()
    }

    override suspend fun openStream(ref: PhotoRef): InputStream? = withContext(Dispatchers.IO) {
        val payload = PhotoIds.payloadOf(ref.id) ?: return@withContext null
        runCatching {
            if (payload.startsWith(CONTENT_SCHEME)) {
                context.contentResolver.openInputStream(Uri.parse(payload))
            } else {
                File(payload).inputStream()
            }
        }.getOrNull()
    }

    // ────────────────────────── A 文件遍历 ──────────────────────────

    private fun walkFileRoot(rootPath: String, out: MutableMap<String, PhotoRef>) {
        val root = File(rootPath)
        if (!root.isDirectory) return

        val targets = if (commonDirsOnlyProvider()) {
            val common = COMMON_PHOTO_DIRS.map { File(root, it) }.filter { it.isDirectory }
            if (common.isEmpty()) {
                // ⚠️ 实现取舍：限定目录**一个都不存在**时回落到整盘扫描。
                // 否则用户的照片放在 `Photos/` 之外的目录时会「一张都不显示」，
                // 而用户完全无从得知是被这个开关挡了。
                AppLog.d(TAG, "no common photo dirs under $rootPath, fall back to full scan")
                listOf(root)
            } else {
                common
            }
        } else {
            listOf(root)
        }

        for (target in targets) {
            target.walkTopDown()
                // onEnter 只对**目录**生效 ⇒ 这里挡的是隐藏目录 / .nomedia 目录。
                // ⚠️ **隐藏文件**（`.thumb.jpg`）不在这里挡，由下一行的
                // isSupportedPhotoName 统一负责（那才是「算不算照片」的唯一定义处）
                .onEnter { dir -> !dir.name.startsWith(".") && !File(dir, NOMEDIA).exists() }
                .maxDepth(MAX_SCAN_DEPTH)
                .filter { it.isFile && isSupportedPhotoName(it.name) }
                .forEach { f ->
                    val id = PhotoIds.of(PhotoSourceKind.EXTERNAL, f.absolutePath)
                    if (out.containsKey(id)) return@forEach
                    out[id] = PhotoRef(
                        id = id,
                        displayName = f.name,
                        width = 0,          // ⚠️ 不探测（见类文档）
                        height = 0,
                        size = f.length(),
                        // ⚠️ File 给的是**毫秒** ⇒ 归一化成秒
                        lastModified = f.lastModified() / 1000L,
                        // File 在 Android 上没有「创建时间」，用修改时间当代理
                        dateAdded = f.lastModified() / 1000L,
                        source = PhotoSourceKind.EXTERNAL,
                    )
                }
        }
    }

    // ────────────────────────── B SAF 兜底 ──────────────────────────

    /**
     * SAF 目录遍历（迭代式，避免深目录递归爆栈）
     *
     * ⚠️ **不用 `DocumentFile`**：那需要引入 `androidx.documentfile` 依赖，
     * 而 `DocumentsContract` 直接查询是同一能力、零新增依赖（本项目的依赖面刻意收窄）。
     */
    private fun walkSafTree(treeUriString: String, out: MutableMap<String, PhotoRef>) {
        val treeUri = Uri.parse(treeUriString)
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (rootDocId == null) {
            AppLog.w(TAG, "SAF tree uri has no document id")
            return
        }
        // ⛔ 内部存储必须在**入口**就拒绝（选目录时也会拦一次，这里是兜底）
        val reason = SafDirectoryPolicy.rejectReason(rootDocId)
        if (reason != null) {
            AppLog.w(TAG, "SAF tree rejected ($reason): $rootDocId")
            return
        }

        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(rootDocId to 0)

        while (stack.isNotEmpty()) {
            val (docId, depth) = stack.removeLast()
            if (depth > MAX_SCAN_DEPTH) continue
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cursor = runCatching {
                context.contentResolver.query(childrenUri, SAF_PROJECTION, null, null, null)
            }.getOrNull() ?: continue

            cursor.use { c ->
                while (c.moveToNext()) {
                    val childId = c.getString(COL_ID)
                    val name = c.getString(COL_NAME)
                    if (childId != null && !name.isNullOrEmpty() && !name.startsWith(".")) {
                        // ⚠️ 上面这条 startsWith(".") 对**目录**是必须的（isSupportedPhotoName
                        // 只判文件）；对文件是冗余的（同一判定已在 isSupportedPhotoName 内），
                        // 保留是为了让「隐藏项一律跳过」在这条路上一眼可见。
                        val mime = c.getString(COL_MIME) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.addLast(childId to (depth + 1))
                        } else if (isSupportedPhotoName(name)) {
                            val size = if (c.isNull(COL_SIZE)) 0L else c.getLong(COL_SIZE)
                            val modifiedMs =
                                if (c.isNull(COL_MODIFIED)) 0L else c.getLong(COL_MODIFIED)
                            val docUri =
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            val id = PhotoIds.of(PhotoSourceKind.EXTERNAL, docUri.toString())
                            if (!out.containsKey(id)) {
                                out[id] = PhotoRef(
                                    id = id,
                                    displayName = name,
                                    width = 0,
                                    height = 0,
                                    size = size,
                                    // ⚠️ SAF 也是**毫秒** ⇒ 归一化成秒
                                    lastModified = modifiedMs / 1000L,
                                    dateAdded = modifiedMs / 1000L,
                                    source = PhotoSourceKind.EXTERNAL,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "ExternalFilePhotoSource"

        /** 与 `MusicScanner.MAX_SCAN_DEPTH` 保持一致 */
        const val MAX_SCAN_DEPTH = 8

        const val NOMEDIA = ".nomedia"
        const val CONTENT_SCHEME = "content://"

        /** 「只扫常见目录」时优先看的子目录名（相机与截图默认落点） */
        val COMMON_PHOTO_DIRS = listOf("DCIM", "Pictures")

        const val COL_ID = 0
        const val COL_NAME = 1
        const val COL_MIME = 2
        const val COL_SIZE = 3
        const val COL_MODIFIED = 4

        val SAF_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
