package com.nasmusic.tv.data.model

/**
 * API 版本号信息密封接口
 *
 * 四种状态：
 * - [Static]   硬编码常量版本（如 Jamendo v3.0、百度网盘 rest/2.0）
 * - [Runtime]  运行时从服务器获取（如 Jellyfin、Navidrome、道理鱼）
 * - [NoVersion] 无版本号服务（如 Meting-API、Bilibili MV）——仅展示服务名
 * - [Disconnected] 未连接（后端已配置但当前未连接）
 */
sealed interface VersionInfo {

    /** 硬编码常量版本 */
    data class Static(
        val serviceName: String,
        val version: String,
        val description: String = ""
    ) : VersionInfo

    /** 运行时从服务器获取 */
    data class Runtime(
        val serviceName: String,
        val version: String,      // 实时获取的版本号
        val endpoint: String,     // 获取端点，用于调试
        val lastUpdated: Long     // 最后成功获取时间戳
    ) : VersionInfo

    /** 无版本号服务（如 Meting-API、Bilibili MV）——仅展示服务名 */
    data class NoVersion(
        val serviceName: String
    ) : VersionInfo

    /** 未连接（后端已配置但当前未连接） */
    data class Disconnected(
        val serviceName: String,
        val expectedVersion: String? = null  // 配置中预期的版本
    ) : VersionInfo

    /** 获取用于显示的版本号文本，无版本号返回空串 */
    val displayVersion: String
        get() = when (this) {
            is Static -> version
            is Runtime -> version
            is NoVersion -> ""
            is Disconnected -> expectedVersion ?: ""
        }

    /** 获取用于显示的服务名 */
    val displayName: String
        get() = when (this) {
            is Static -> serviceName
            is Runtime -> serviceName
            is NoVersion -> serviceName
            is Disconnected -> serviceName
        }

    /** 是否有版本号可显示 */
    val hasVersion: Boolean
        get() = when (this) {
            is Static -> version.isNotBlank()
            is Runtime -> version.isNotBlank()
            is NoVersion -> false
            is Disconnected -> expectedVersion?.isNotBlank() == true
        }
}