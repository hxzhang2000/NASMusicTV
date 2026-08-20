package com.nasmusic.tv.data.model

/**
 * 单网盘配置（按 [CloudDriveType] 存取）
 *
 * 总开关语义（[enabled]）：
 * - 关闭：app 完全不访问该网盘——不刷新 token、不调任何 API、不建/不刷新索引、
 *   `NetworkMusicManager.services` 中不注册该源的 service。
 * - 已登录后关闭：token 保留不清除（下次开启免重新授权），但停止所有 API 活动。
 *
 * ⚠️ [tokens] 类型当前绑死 [BaiduTokens]，多网盘扩展时需泛型化或 sealed——
 * 首批接受此约束。
 *
 * @param enabled         总开关，默认 false（用户须主动开启）
 * @param tokens          鉴权 token
 * @param musicRootDir    音乐根目录（默认 "/音乐"）
 * @param mvDir           MV 文件目录（可选；null 表示与 musicRootDir 相同）
 * @param customAppKey    用户自填 AppKey（覆盖默认嵌入值）
 * @param customSecretKey 用户自填 SecretKey
 * @param apiProbeBaseline 本版 APK 实现时验证过的 API 字段指纹（哈希），随 App 版本固化
 * @param apiProbeResult  最近一次启动探测到的实际字段指纹，与 baseline 对比
 */
data class CloudDriveConfig(
    val type: CloudDriveType,
    val enabled: Boolean = false,
    val tokens: BaiduTokens? = null,
    val musicRootDir: String = "/音乐",
    val mvDir: String? = null,
    val customAppKey: String? = null,
    val customSecretKey: String? = null,
    val apiProbeBaseline: String? = null,
    val apiProbeResult: String? = null,
    /** API 漂移一次性提示是否已弹（避免每次启动重复提示） */
    val apiDriftNotified: Boolean = false
) {
    /** 实际指纹与本地基线不一致 → API 可能已升级 */
    val apiDrifted: Boolean
        get() = !apiProbeBaseline.isNullOrBlank() &&
            apiProbeBaseline != apiProbeResult && !apiProbeResult.isNullOrBlank()

    /** 总开关关闭时，NetworkMusicManager 不注册该网盘的 service */
    val isActive: Boolean get() = enabled && tokens != null

    /** MV 搜索路径：未设置时默认与音乐根目录相同 */
    val effectiveMvDir: String get() = mvDir ?: musicRootDir
}
