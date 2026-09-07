package com.nasmusic.tv.backend.export

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * SAF（Storage Access Framework）权限辅助（见方案 §8.8.2 / 8.8.3）
 *
 * 关键点：
 * - TV 上 SAF 的有无无法靠文档推断。对 [ACTION_OPEN_DOCUMENT_TREE]，
 *   部分 ROM 的 `resolveActivity()` 会返回 Google 桩实现
 *   `com.google.android.tv.frameworkpackagestubs/.Stubs$DocumentsStub` —— 启动后只弹
 *   "You don't have an app that can do this"。因此必须双保险探测（package 名 + 可启动性）。
 * - 只探测 TREE，不要拿 OPEN_DOCUMENT 的探测结果类推。
 * - 实际启动仍必须 try/catch ActivityNotFoundException，降级到应用专属目录。
 */
object ExportPermissionHelper {

    /** TV 上的 SAF 桩实现包名，命中即视为不可用 */
    private const val TV_STUB_PKG = "com.google.android.tv.frameworkpackagestubs"

    /**
     * 判断 ACTION_OPEN_DOCUMENT_TREE 是否真的可用。
     */
    fun isTreePickAvailable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            val ri = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: return false
            // 双保险：resolveActivity 非 null 也可能是桩
            val pkg = ri.activityInfo?.packageName
            pkg != null && pkg != TV_STUB_PKG
        } catch (e: Exception) {
            false
        }
    }

    /** 校验持久化授权 URI 是否仍在且含 WRITE 权限（跨重启有效） */
    fun hasPersistedWrite(context: Context, treeUri: Uri): Boolean {
        return try {
            context.contentResolver.persistedUriPermissions
                .any { perm ->
                    perm.uri == treeUri &&
                        perm.isWritePermission && perm.isReadPermission
                }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把 StorageDevice 解析成实际可写的根（SAF 优先，应用专属目录兜底）。
     *
     * @return Saf（已授权 SAF 树）/ File（应用专属目录）/ Unavailable（都不可用）
     */
    fun resolveRoot(
        context: Context,
        devicePath: String,
        treeUri: Uri?,
        exportVolumeId: String?,
        volumeId: String
    ): ExportRoot {
        // A：已授权的 SAF 树
        if (treeUri != null && exportVolumeId == volumeId && hasPersistedWrite(context, treeUri)) {
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return ExportRoot.Unavailable
            val nasMusicDir = tree.findFile(EXPORT_DIR)
                ?: tree.createDirectory(EXPORT_DIR)
                ?: return ExportRoot.Unavailable
            return ExportRoot.Saf(nasMusicDir)
        }
        // C：应用专属目录（无权限要求）
        val dirs = context.getExternalFilesDirs(android.os.Environment.DIRECTORY_MUSIC)
        val match = dirs.firstOrNull { it != null && it.absolutePath.startsWith(devicePath) }
        return if (match != null) ExportRoot.File(match) else ExportRoot.Unavailable
    }

    /** 导出的根目录名（SAF 路径统一加 NASMusic/ 子目录） */
    const val EXPORT_DIR = "NASMusic"
}

/** 导出根抽象（SAF 与文件系统对上层透明） */
sealed interface ExportRoot {
    data class Saf(val dir: DocumentFile) : ExportRoot
    data class File(val dir: java.io.File) : ExportRoot
    object Unavailable : ExportRoot
}