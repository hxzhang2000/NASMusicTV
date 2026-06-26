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
 *   3. 正式发布前修改 app/build.gradle.kts 的 versionName 与 versionCode（唯一来源）
 *   4. FILE_FORMAT_VERSION 仅在 DataStore/缓存数据结构向后不兼容时递增
 */
object NasMusicVersion {

    // =========================================================================
    // 版本标识
    // 唯一来源为 app/build.gradle.kts 的 defaultConfig { versionName / versionCode }，
    // AGP 自动生成 BuildConfig，本类仅作代码侧的统一访问入口，避免两处硬编码不一致。
    // 发布前只需修改 build.gradle.kts 即可。
    // =========================================================================

    /** 面向用户的版本名称（来自 BuildConfig，源：build.gradle.kts） */
    val VERSION_NAME: String get() = BuildConfig.VERSION_NAME

    /** Android versionCode（来自 BuildConfig，源：build.gradle.kts） */
    val VERSION_CODE: Int get() = BuildConfig.VERSION_CODE

    /** 构建阶段标识 */
    const val BUILD_TYPE = "RELEASE"

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
