package com.nasmusic.tv.backend.photo

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 图库来源（**仅手机**）—— 读 MediaStore 索引
 *
 * ⚠️ **电视上不可用**：电视（Android 5.1.1）没有系统图库，
 * 且照片放在内部存储的概率极低（决策：只读外接卷）。
 *
 * ## MediaStore 索引边界（§6.2，核实 AOSP + Android 官方文档）
 *
 * **并非内部存储的图片都被索引**。以下**不在**图库中：
 * `.nomedia` 目录 / 隐藏目录 / `Android/data` / `Android/obb` /
 * 其他 App 私有目录 / 非媒体扩展名。
 * 而漏掉的那些装的是**功能性图片**（漫画页 / App 缓存），不是「回忆照片」
 * ⇒ 这个边界与产品目标一致，无需额外补偿。
 *
 * ## 时间字段
 *
 * `DATE_MODIFIED` / `DATE_ADDED` 在 MediaStore 里**原生就是秒**（Unix epoch 秒），
 * 无需换算 —— 但仍在下方显式注明，因为跨来源去重依赖「秒」这一契约。
 */
class MediaStorePhotoSource(private val context: Context) : PhotoSource {

    override val kind: PhotoSourceKind = PhotoSourceKind.GALLERY

    override suspend fun status(): PhotoSourceStatus = withContext(Dispatchers.IO) {
        when (PermissionHelper.photoPermissionState(context)) {
            PermissionHelper.PhotoPermissionState.FULL,
            PermissionHelper.PhotoPermissionState.FULL_LEGACY -> PhotoSourceStatus.OK

            // ⚠️ 部分授权**算可用** —— 用户已选中的那批照片是可读的（§9.6）
            PermissionHelper.PhotoPermissionState.PARTIAL -> PhotoSourceStatus.PARTIAL_PERMISSION

            PermissionHelper.PhotoPermissionState.DENIED -> PhotoSourceStatus.PERMISSION_DENIED
        }
    }

    override suspend fun listPhotos(): List<PhotoRef> = withContext(Dispatchers.IO) {
        if (!PermissionHelper.hasPhotoPermission(context)) {
            AppLog.d(TAG, "listPhotos skipped: no photo permission")
            return@withContext emptyList()
        }
        val out = ArrayList<PhotoRef>(256)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_ADDED,
        )
        try {
            context.contentResolver.query(
                collection(),
                projection,
                null,
                null,
                "${MediaStore.Images.Media._ID} ASC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    // 图库里也可能有 HEIC 之类（系统能解、本应用解不了）⇒ 仍要过白名单
                    if (!isSupportedPhotoName(name)) continue
                    out.add(
                        PhotoRef(
                            id = PhotoIds.of(PhotoSourceKind.GALLERY, id.toString()),
                            displayName = name,
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol),
                            size = cursor.getLong(sizeCol),
                            // MediaStore 的这两个字段原生就是**秒**
                            lastModified = cursor.getLong(modCol),
                            dateAdded = cursor.getLong(addedCol),
                            source = PhotoSourceKind.GALLERY,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "MediaStore photo query failed: ${e.message}", e)
        }
        AppLog.d(TAG, "Scanned ${out.size} photos via MediaStore")
        out
    }

    override suspend fun openStream(ref: PhotoRef): InputStream? = withContext(Dispatchers.IO) {
        val rowId = PhotoIds.payloadOf(ref.id)?.toLongOrNull() ?: return@withContext null
        val uri = ContentUris.withAppendedId(collection(), rowId)
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * 图库集合 URI。
     *
     * API 29+ 用 `getContentUri(VOLUME_EXTERNAL)`（覆盖所有外接卷）；
     * 23–28 只有 `EXTERNAL_CONTENT_URI`。
     */
    private fun collection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private companion object {
        const val TAG = "MediaStorePhotoSource"
    }
}
