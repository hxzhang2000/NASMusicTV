package com.nasmusic.tv.data.model

import androidx.compose.ui.graphics.Color

/**
 * 歌词来源
 *
 * 与 [MusicSourceType]（歌曲来源）结构统一：displayName + icon + color，
 * 供 UI 以同一套来源标签体系渲染（SourceTag / SourceBadge / 信息面板）。
 * 颜色语义对齐歌曲来源：内嵌→NAS 蓝 / 本地→LOCAL 橙 / 在线→网络 绿 / 缓存→DOWNLOAD 青。
 */
enum class LyricsSource(
    /** 中文显示名（短版，与歌曲来源标签文案风格一致；完整语义见枚举注释） */
    val displayName: String,
    /** 图标字符 */
    val icon: String,
    /** 主题色 */
    val color: Color
) {
    EMBEDDED("内嵌", "🎵", Color(0xFF60A5FA)),   // 蓝（对齐 NAS：音频文件内嵌歌词）
    LOCAL_FILE("本地", "📱", Color(0xFFFB923C)), // 橙（对齐 LOCAL：同目录 LRC / 本地歌词文件）
    NETWORK("在线", "🌐", Color(0xFF34D399)),    // 绿（对齐 NETWORK_MUSIC：网络模糊匹配）
    CACHED("缓存", "💾", Color(0xFF22D3EE))      // 青（对齐 DOWNLOAD：本地持久化缓存歌词）
}
