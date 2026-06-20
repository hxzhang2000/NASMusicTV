package com.nasmusic.tv

/**
 * NAS Music TV 版本与构建信息
 *
 * 版本号格式：[主版本].[次版本].[补丁]
 *   - 主版本：重大架构变更或 UI 重设计时递增
 *   - 次版本：新功能发布时递增
 *   - 补丁：Bug 修复或小优化时递增
 *
 * versionCode：每次正式发布递增（用于 Android 版本检测）
 *
 * 重要规则：
 *   1. 新功能开发和修改前，先查看当前版本号
 *   2. 功能实现完毕后更新 CHANGELOG.md
 *   3. 正式发布前递增 VERSION_CODE 并更新 VERSION_NAME
 *   4. FILE_FORMAT_VERSION 仅在 DataStore/缓存数据结构向后不兼容时递增
 */
object NasMusicVersion {

    // =========================================================================
    // 版本标识（每次发布前更新）
    // =========================================================================

    /** 面向用户的版本名称 */
    const val VERSION_NAME = "1.0.0"

    /** Android versionCode（每次发布递增） */
    const val VERSION_CODE = 1

    /** 构建阶段标识 */
    const val BUILD_TYPE = "STABLE"

    /** 文件格式版本（DataStore/缓存数据结构版本，向后不兼容时递增） */
    const val FILE_FORMAT_VERSION = 1

    // =========================================================================
    // 派生显示字符串
    // =========================================================================

    /** 完整版本显示字符串（用于 UI 和日志） */
    val DISPLAY: String get() = "v$VERSION_NAME"

    /** 带构建类型的完整版本字符串 */
    val DISPLAY_FULL: String get() = "v$VERSION_NAME ($BUILD_TYPE)"

    /** 用于 About 页面的详细信息 */
    val ABOUT_STRING: String
        get() = """
            |NAS Music TV
            |版本: v$VERSION_NAME
            |构建类型: $BUILD_TYPE
            |文件格式: v$FILE_FORMAT_VERSION
        """.trimMargin()

    // =========================================================================
    // 版本比较与兼容性
    // =========================================================================

    /**
     * 检查给定的文件格式版本是否与当前版本兼容
     */
    fun isFileFormatCompatible(version: Int): Boolean {
        return version == FILE_FORMAT_VERSION
    }

    /**
     * 获取版本标签（用于 git tag 和 CI）
     */
    fun getReleaseTag(): String = "v$VERSION_NAME"
}
