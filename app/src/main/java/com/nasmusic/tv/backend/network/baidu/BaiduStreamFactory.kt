package com.nasmusic.tv.backend.network.baidu

import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 百度网盘音频 dlink 解析工厂
 *
 * dlink 串流三约束（BoxPlayer adapter.ts 实证）：
 * 1. dlink 可能不含 access_token，需手动拼 `&access_token=`
 * 2. dlink 请求必须带 `User-Agent: pan.baidu.com`（>20MB 文件不加会 403）
 * 3. 建议带 `Referer: https://pan.baidu.com/`（双保险）
 *
 * UA/Referer 注入由 [BaiduHttpDataSourceFactory] 在 DataSource 层按 URL 域名条件判断完成
 * （NeriPlayer ConditionalHttpDataSourceFactory.transformDataSpec 模式），本类只负责 dlink 解析。
 */
class BaiduStreamFactory(
    private val api: BaiduPanApi,
    private val oauth: BaiduOAuthClient
) {

    /**
     * 解析播放 URL：fs_id -> filemetas(dlink) -> 返回带 access_token 的 dlink
     *
     * dlink 有效期短（小时级），缓存走 NetworkMusicManager 全局 5min TTL（方案 §11 问题 6），
     * 此处不做缓存决策。
     *
     * @return 可直接播放的 URL；解析失败返回 null
     */
    suspend fun resolveStreamUrl(fsId: Long): String? = withContext(Dispatchers.IO) {
        val metas = api.fileMetas(listOf(fsId))
        val dlink = metas.firstOrNull()?.dlink ?: run {
            AppLog.w(TAG, "resolveStreamUrl: dlink missing for fsId=$fsId")
            return@withContext null
        }
        val token = oauth.getValidAccessToken() ?: return@withContext null
        // dlink 可能不含 access_token，需手动补
        if (!dlink.contains("access_token=")) {
            dlink + (if (dlink.contains('?')) "&" else "?") +
                "access_token=" + URLEncoder.encode(token, "UTF-8")
        } else dlink
    }

    companion object {
        private const val TAG = "BaiduStreamFactory"

        /** 判断 URL 是否为百度 dlink 域名（供 DataSource 拦截器静态调用） */
        fun shouldInject(url: String?): Boolean =
            url?.let { u -> BaiduNetdiskConfig.DLINK_HOST_MARKERS.any { u.contains(it) } } == true
    }
}
