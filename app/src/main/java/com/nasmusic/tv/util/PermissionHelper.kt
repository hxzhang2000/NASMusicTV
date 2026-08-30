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
}
