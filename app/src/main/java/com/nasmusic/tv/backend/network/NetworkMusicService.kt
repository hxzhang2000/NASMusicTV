package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song

/**
 * 网络音乐服务接口
 *
 * 抽象不同网络音乐源（Meting-API、AlAPI、JioSaavn 等）的统一访问层。
 * 实现类负责将各源平台的响应转换为统一的 [Song] 模型。
 *
 * v2.2.0 适配规范：
 * - OkHttpClient 使用守护线程池（isDaemon = true），防止阻止进程退出
 * - 日志使用 AppLog（Release 构建自动抑制调试日志）
 * - JSON 解析统一用 Gson
 *
 * @param T 搜索结果的中间类型（各源的原始搜索结果），由各实现定义
 */
interface NetworkMusicService {

    /** 来源标识，如 "meting" / "alapi" / "jiosaavn" */
    val sourceId: String

    /**
     * 搜索歌曲。
     *
     * @param keyword 搜索关键词
     * @param limit 返回结果数量上限；0 或 null 表示使用实现默认值
     * @return 搜索结果列表。返回空列表表示无结果。
     * @throws Exception 网络或解析错误由上游 [NetworkMusicManager] 统一处理
     */
    suspend fun search(keyword: String, limit: Int = 0): List<Song>

    /**
     * 解析播放链接。
     *
     * Meting-API 返回的是 302 端点，需要跟随重定向拿到直联 URL；
     * 部分 API 可能在 search 结果中直接返回，此时可直接返回 song.streamUrl。
     *
     * @return 可直接播放的 URL；null 表示解析失败
     * @throws Exception 网络错误由上游统一处理
     */
    suspend fun resolvePlayUrl(song: Song): String?

    /**
     * 获取歌词（LRC 文本）。
     * 可为空，回退到 LyricsNetworkProvider。
     *
     * @return LRC 格式的歌词文本；null 表示无匹配
     * @throws Exception 网络错误由上游统一处理
     */
    suspend fun resolveLyrics(song: Song): String?

    /**
     * 获取封面 URL。
     * 如不需要额外解析（搜索结果已含直联封面 URL）则返回 null，使用 song.coverUrl。
     *
     * @return 封面图片 URL；null 表示无额外封面
     * @throws Exception 网络错误由上游统一处理
     */
    suspend fun resolveCoverUrl(song: Song): String? = null

    /**
     * 按标题+艺术家搜索封面 URL。
     * 默认返回 null，仅需要此功能的服务需覆盖实现。
     *
     * @param title 歌曲标题
     * @param artist 艺术家名
     * @return 封面图片 URL；null 表示未找到
     */
    suspend fun searchCoverUrl(title: String, artist: String): String? = null

    /**
     * 获取歌单中的所有歌曲。
     *
     * @param playlistId 歌单 ID（平台特定，如网易云歌单 ID）
     * @return 歌单中的歌曲列表。返回空列表表示无结果或获取失败。
     * @throws Exception 网络或解析错误由上游统一处理
     */
    suspend fun getPlaylist(playlistId: String): List<Song>
}
