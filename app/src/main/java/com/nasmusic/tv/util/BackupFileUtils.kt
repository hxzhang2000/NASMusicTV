package com.nasmusic.tv.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件读写工具
 *
 * 备份文件统一存放在 Downloads/NASMusic/ 目录（TV 用户可经文件管理器访问）：
 * - API 29+：MediaStore.Downloads（无需权限）
 * - API < 29：主备份写应用内部存储 filesDir/backups/（/data 真闪存，断电不丢），
 *   另写一份到外部存储公共 Downloads 目录供文件管理器访问
 *   （注意：部分电视 ROM 的公共存储是 RAM-backed，断电即丢失，故内部存储才是可靠主备份）
 *
 * 文件名格式：NASMusic_backup_yyyyMMdd_HHmmss.json
 */
object BackupFileUtils {

    private const val TAG = "BackupFileUtils"
    private const val BACKUP_SUBDIR = "NASMusic"
    private const val FILE_PREFIX = "NASMusic_backup_"
    private const val FILE_SUFFIX = ".json"

    /** API < 29：应用内部存储备份目录（/data 真闪存，断电不丢） */
    private fun internalBackupDir(context: Context): File =
        File(context.filesDir, BACKUP_SUBDIR)

    /** API < 29：公共 Downloads 备份目录（普通 ROM 上文件管理器可访问） */
    private fun publicBackupDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            BACKUP_SUBDIR
        )

    data class BackupFile(
        val displayName: String,
        val uri: Uri,
        val lastModified: Long
    )

    /**
     * 生成备份文件名（含时间戳）
     */
    fun generateFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$FILE_PREFIX$ts$FILE_SUFFIX"
    }

    /**
     * 导出备份 JSON 到 Downloads/NASMusic/
     *
     * @return 成功返回备份文件显示名；失败返回错误信息
     */
    fun export(context: Context, json: String): Result<String> {
        return try {
            val fileName = generateFileName()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR")
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values)
                    ?: return Result.failure(Exception("无法创建备份文件"))
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: return Result.failure(Exception("无法写入备份文件"))
                Result.success(fileName)
            } else {
                // 主备份：应用内部存储（/data 真闪存，断电不丢）
                val internalDir = internalBackupDir(context)
                if (!internalDir.exists() && !internalDir.mkdirs()) {
                    return Result.failure(Exception("无法创建备份目录"))
                }
                File(internalDir, fileName).writeText(json, Charsets.UTF_8)
                // 辅助副本：公共 Downloads（普通 ROM 上文件管理器可访问；RAM 盘 ROM 上尽力而为）
                try {
                    val publicDir = publicBackupDir()
                    if (publicDir.exists() || publicDir.mkdirs()) {
                        File(publicDir, fileName).writeText(json, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "public backup copy failed", e)
                }
                Result.success(fileName)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "export failed", e)
            Result.failure(e)
        }
    }

    /**
     * 列出 Downloads/NASMusic/ 下的备份文件（按修改时间倒序）
     */
    fun listBackups(context: Context): List<BackupFile> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR/")
                val files = mutableListOf<BackupFile>()
                context.contentResolver.query(
                    collection, projection, selection, selectionArgs,
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) continue
                        val id = cursor.getLong(idCol)
                        files.add(
                            BackupFile(
                                displayName = name,
                                uri = Uri.withAppendedPath(collection, id.toString()),
                                lastModified = cursor.getLong(dateCol) * 1000L
                            )
                        )
                    }
                }
                files
            } else {
                // 合并内部存储（可靠主备份）与公共 Downloads（普通 ROM 上可见），按名称去重
                val dirs = listOf(internalBackupDir(context), publicBackupDir())
                val seen = mutableSetOf<String>()
                val files = mutableListOf<BackupFile>()
                for (dir in dirs) {
                    if (!dir.exists()) continue
                    dir.listFiles { f ->
                        f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
                    }?.forEach { f ->
                        if (seen.add(f.name)) {
                            files.add(
                                BackupFile(
                                    displayName = f.name,
                                    uri = Uri.fromFile(f),
                                    lastModified = f.lastModified()
                                )
                            )
                        }
                    }
                }
                files.sortedByDescending { it.lastModified }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "listBackups failed", e)
            emptyList()
        }
    }

    /**
     * 读取备份文件内容
     */
    fun read(context: Context, uri: Uri): Result<String> {
        return try {
            val json = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: return Result.failure(Exception("无法读取备份文件"))
            Result.success(json)
        } catch (e: Exception) {
            AppLog.e(TAG, "read failed", e)
            Result.failure(e)
        }
    }

    /**
     * 删除备份文件
     *
     * - API 29+：通过 MediaStore.Downloads 删除（文件为应用自身创建，无需额外权限）
     * - API < 29：同时删除内部存储与公共 Downloads 中的两份副本（按文件名匹配）
     */
    fun delete(context: Context, uri: Uri): Result<Unit> {
        return try {
            val deleted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(uri, null, null)
            } else {
                val file = File(uri.path ?: return Result.failure(Exception("无效的文件路径")))
                val fileName = file.name
                var count = 0
                // 两份副本（内部存储 + 公共 Downloads）都删除，避免列表去重后残留
                val candidates = listOf(
                    File(internalBackupDir(context), fileName),
                    File(publicBackupDir(), fileName)
                )
                for (candidate in candidates) {
                    if (candidate.exists() && candidate.delete()) count++
                }
                count
            }
            if (deleted > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("删除失败，文件不存在或已被删除"))
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "delete failed", e)
            Result.failure(e)
        }
    }
}
