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
 */
interface NetworkMusicService {

    /** 来源标识，如 "meting" / "alapi" / "jiosaavn" */
    val sourceId: String

    /**
     * 搜索歌曲。
     * limit 由各实现自行控制，接口层不暴露。
     */
    suspend fun search(keyword: String): List<Song>

    /**
     * 解析播放链接。
     *
     * Meting-API 返回的是 302 端点，需要跟随重定向拿到直联 URL；
     * 部分 API 可能在 search 结果中直接返回，此时可直接返回 song.streamUrl。
     */
    suspend fun resolvePlayUrl(song: Song): String?

    /**
     * 获取歌词（LRC 文本）。
     * 可为空，回退到 LyricsNetworkProvider。
     */
    suspend fun resolveLyrics(song: Song): String?

    /**
     * 获取封面 URL。
     * 如不需要额外解析（搜索结果已含直联封面 URL）则返回 null，使用 song.coverUrl。
     */
    suspend fun resolveCoverUrl(song: Song): String? = null
}
