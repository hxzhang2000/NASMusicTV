package com.nasmusic.tv.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 权限辅助类
 *
 * 统一处理本地音乐所需的存储权限（Android 13+ 用 READ_MEDIA_AUDIO，
 * 低版本用 READ_EXTERNAL_STORAGE）。
 */
object PermissionHelper {

    /**
     * 检查是否已授予本地音乐读取权限
     */
    fun hasLocalMusicPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取本地音乐所需权限数组（用于 requestPermissions）
     */
    fun getLocalMusicPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    // ══════════════════════════════════════════════════════════════════
    //  照片读取权限（照片墙，§九）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 照片读取权限状态（**四态**，§9.6）
     *
     * ⛔ 为什么必须有 `PARTIAL`：Android 14+（API 34）的系统对话框是**三选一**
     * （全部允许 / **仅选择照片** / 不允许）。选「仅选择照片」时
     * `READ_MEDIA_IMAGES` **只是会话级临时授予**（杀进程即失效），
     * 而 `READ_MEDIA_VISUAL_USER_SELECTED` 才是**持久标识**。
     * ⇒ 回弹判据必须含后者，否则「重启后把部分授权误判成被拒」→ 开关被错误回弹。
     */
    enum class PhotoPermissionState {
        /** 全部照片可读（API 33+ 的 READ_MEDIA_IMAGES，或旧版的 READ_EXTERNAL_STORAGE） */
        FULL,

        /** Android 14+「仅选择照片」 */
        PARTIAL,

        /** API 30–32：用旧版存储权限（已被系统降级为「仅媒体」语义） */
        FULL_LEGACY,

        /** 被拒 */
        DENIED,
    }

    /** 当前照片读取权限状态 */
    fun photoPermissionState(context: Context): PhotoPermissionState {
        val imagesGranted = granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> when {
                imagesGranted -> PhotoPermissionState.FULL
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
                    PhotoPermissionState.PARTIAL
                else -> PhotoPermissionState.DENIED
            }
            // API 33：对话框只有「允许 / 不允许」两选一，没有部分授权
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                if (imagesGranted) PhotoPermissionState.FULL else PhotoPermissionState.DENIED
            // API ≤ 32：READ_EXTERNAL_STORAGE
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE) ->
                PhotoPermissionState.FULL_LEGACY
            else -> PhotoPermissionState.DENIED
        }
    }

    /** 照片可读（含部分授权 —— 部分授权下「已选中的那批」是可读的） */
    fun hasPhotoPermission(context: Context): Boolean =
        photoPermissionState(context) != PhotoPermissionState.DENIED

    /**
     * 照片所需权限数组（用于 requestPermissions）
     *
     * ⚠️ API 34+ 要**同时**申请两个：只申请 `READ_MEDIA_IMAGES` 时
     * 系统对话框不会出现「仅选择照片」选项。
     */
    fun getPhotoPermissions(): Array<String> =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
