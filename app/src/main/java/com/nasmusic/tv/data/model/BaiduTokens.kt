package com.nasmusic.tv.data.model

/**
 * 百度网盘 OAuth Token 持久化模型
 *
 * @param accessToken  访问令牌（有效期 30 天）
 * @param refreshToken 刷新令牌（单次有效：刷新成功后响应会带新值，必须覆盖写回）
 * @param expiresAt    access_token 到期时间戳（毫秒）
 * @param scope        授权范围，固定 "basic netdisk"
 */
data class BaiduTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val scope: String = "basic netdisk"
) {
    /**
     * 是否临近过期（提前 5 分钟判定为需刷新）
     */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean =
        now >= expiresAt - 5 * 60 * 1000L
}
