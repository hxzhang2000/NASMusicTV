package com.nasmusic.tv.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 链接相关工具：打开浏览器 / 复制到剪贴板
 */
object LinkUtils {

    /**
     * 在浏览器中打开 URL（视频/文字弹窗中展示的链接，点击跳转系统浏览器）
     *
     * @return true 表示已成功启动浏览器；false 表示失败（无浏览器 / 非法 URL 等）
     */
    fun openInBrowser(context: Context, url: String): Boolean {
        val uri = Uri.parse(url)
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                // 从非 Activity context（如应用 Application）启动时需要 NEW_TASK
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 复制文本到系统剪贴板，并弹出 Toast 提示"已复制"（短暂悬浮提示，非弹窗）
     */
    fun copyToClipboard(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}