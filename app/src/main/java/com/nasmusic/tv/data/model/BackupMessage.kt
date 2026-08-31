package com.nasmusic.tv.data.model

/**
 * 备份/恢复操作的状态消息。
 *
 * @property text 用户可见的消息文本（已本地化）。
 * @property isError 是否为错误/警告类消息。
 *   - true：失败/错误（红色 Warning）
 *   - false：成功/确认（绿色 Primary）
 *
 * 用 isError 布尔标志替代原先基于字符串前缀/子串的颜色判断逻辑
 * （`startsWith("恢复")` / `startsWith("备份失败")` / `contains("失败")`），
 * 消除消息文本与 UI 颜色逻辑的耦合。
 */
data class BackupMessage(
    val text: String,
    val isError: Boolean = false
)
