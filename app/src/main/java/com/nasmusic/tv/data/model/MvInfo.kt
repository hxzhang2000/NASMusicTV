package com.nasmusic.tv.data.model

/**
 * MV（音乐视频）信息
 *
 * 由 MvSearchManager 从各 MV 搜索源（v1 仅 Bilibili）后台搜索得到。
 * videoUrl 为直接可播放的直链 mp4，有有效期（小时级），因此
 * 结果仅做内存缓存（MvSearchManager 内带 TTL），不做持久化。
 *
 * 对应 docs/mv-karaoke-feature-proposal.md §4.1
 */
data class MvInfo(
    val bvid: String,
    val title: String,          // MV 标题（用于调试/展示）
    val coverUrl: String?,      // 封面
    val videoUrl: String,       // 直链 mp4
    val durationMs: Long = 0L,
    val fetchedAt: Long = System.currentTimeMillis()  // 用于缓存过期判断
)

/**
 * MV 搜索候选（未解析直链的轻量结果）
 *
 * 仅含搜索接口返回的 bvid / 标题 / 封面，不含直链。
 * 用户在 MTV 页面切换视频时，按 bvid 调 [MvSearchService.resolveMv] 懒加载直链。
 */
data class MvCandidate(
    val bvid: String,
    val title: String,
    val coverUrl: String?
)

/**
 * MV 搜索结果：已解析的最佳匹配 + 其余候选（未解析直链）
 *
 * 最佳匹配的 [mv] 可直接播放；[alternatives] 供用户在 MTV 页面切换。
 */
data class MvSearchResult(
    val mv: MvInfo,
    val alternatives: List<MvCandidate>
)