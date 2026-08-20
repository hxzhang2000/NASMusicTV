package com.nasmusic.tv.backend.network.mv

import com.nasmusic.tv.data.model.MvInfo
import com.nasmusic.tv.data.model.MvCandidate
import com.nasmusic.tv.data.model.MvSearchResult
import com.nasmusic.tv.data.model.Song

/**
 * MV 搜索服务接口
 *
 * 按歌曲标题+艺术家搜索对应的音乐视频。
 * 实现类负责将各来源（Bilibili 等）平台的搜索结果转换为统一的 [MvInfo] / [MvCandidate]。
 *
 * 约定：
 * - 网络/解析错误由实现类自行吞掉并返回 null（MTV 按钮置暗即可，不打扰用户）
 * - 返回的 videoUrl 为直链 mp4，有有效期
 * - [searchMv] 返回最佳匹配（已解析直链）+ 候选列表（未解析），候选供 MTV 页面切换
 * - [resolveMv] 按需解析指定 bvid 的直链（切换视频时调用）
 *
 * v2.18（百度网盘 MV 接入）：[searchMv] 新增带默认值的 `song` 参数，供 [BaiduMvFileService]
 * 判断歌曲来源（仅百度歌曲生效）与反查同目录 path。[BilibiliMvService] 零改动（默认值）。
 *
 * 对应 docs/mv-karaoke-feature-proposal.md Step 1
 */
interface MvSearchService {

    /**
     * 搜索歌曲对应的 MV。
     *
     * @param title  歌曲标题
     * @param artist 艺术家名（可为空）
     * @param excludeBvids 需排除的 bvid 集合（重搜时排除已展示的结果）
     * @param minSimilarity 标题相似度阈值（首次搜索 0.5，重搜可降低以获取更多结果）
     * @param song 完整歌曲上下文（v2.18 新增，带默认值保向后兼容；百度 MV 据此判源/反查 path）
     * @return 搜索结果（最佳匹配已解析直链 + 候选列表）；null 表示未找到或获取失败
     */
    suspend fun searchMv(
        title: String,
        artist: String,
        excludeBvids: Set<String> = emptySet(),
        minSimilarity: Float = 0.5f,
        song: Song? = null
    ): MvSearchResult?

    /**
     * 按需解析指定 bvid 的直链（MTV 页面切换视频时调用）。
     *
     * @param bvid 视频 bvid
     * @return 解析后的 MvInfo（含直链）；null 表示解析失败
     */
    suspend fun resolveMv(bvid: String): MvInfo?
}