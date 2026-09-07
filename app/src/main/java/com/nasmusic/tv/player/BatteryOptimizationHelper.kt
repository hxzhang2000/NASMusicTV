package com.nasmusic.tv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.nasmusic.tv.util.AppLog

/**
 * 电池优化辅助类
 *
 * 检测并请求关闭电池优化，确保后台播放稳定。
 * 仅在手机端生效（TV 设备通常无电池优化限制）。
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptHelper"

    /**
     * 检查当前应用是否被忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true // 无法获取则视为已优化
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 打开电池优化设置页面，让用户手动将本应用加入白名单
     *
     * 适用于 Android 6.0+ (API 23+)
     */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            AppLog.d(TAG, "Android < 6.0, skip battery optimization request")
            return false
        }

        if (isIgnoringBatteryOptimizations(context)) {
            AppLog.d(TAG, "Already ignoring battery optimizations")
            return true
        }

        return try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            AppLog.d(TAG, "Opened battery optimization settings")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to open battery optimization settings", e)
            false
        }
    }

    /**
     * 判断当前设备是否为手机（非 TV）
     * 手机端需要处理电池优化，TV 设备通常不需要
     */
    fun isPhoneDevice(context: Context): Boolean {
        val pm = context.packageManager
        return !pm.hasSystemFeature("android.software.leanback") &&
                !pm.hasSystemFeature("android.hardware.type.television")
    }

    /**
     * 启动时检查并请求忽略电池优化（仅手机端）
     * 应在 MainActivity.onCreate 中调用
     */
    fun checkAndRequest(context: Context) {
        if (!isPhoneDevice(context)) {
            AppLog.d(TAG, "TV device, skip battery optimization check")
            return
        }

        if (isIgnoringBatteryOptimizations(context)) {
            AppLog.d(TAG, "Battery optimization already ignored")
            return
        }

        AppLog.d(TAG, "Battery optimization not ignored, requesting...")
        requestIgnoreBatteryOptimizations(context)
    }
}