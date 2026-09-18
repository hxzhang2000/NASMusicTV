package com.nasmusic.tv.ui.components

import com.nasmusic.tv.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音质徽标文案测试（多码率方案 §3.5）。
 *
 * 用户要求：本地歌曲播放时也应显示音质（不可点击）。
 * 本测试锁定"网络可点击 / 本地只读 / 无数据不渲染"三态。
 */
class QualityBadgeLabelTest {

    private fun networkSong() = Song(
        id = "ntwk_meting_1", title = "南方姑娘",
        isNetworkSong = true, networkSource = "meting", networkId = "1"
    )

    private fun nasSong(bitrate: Int) = Song(
        id = "nas_1", title = "南方姑娘", bitrate = bitrate
    )

    private fun localSong(bitrate: Int = 0, resolvedQuality: Int = 0) = Song(
        id = "local_1", title = "南方姑娘", isLocalSong = true,
        bitrate = bitrate, resolvedQuality = resolvedQuality
    )

    // ── 网络歌曲：显示档位标签 ──────────────────────────────

    @Test
    fun `网络歌曲显示档位标签`() {
        assertEquals("♪ 无损", qualityBadgeLabel(networkSong(), "无损"))
        assertEquals("♪ 320k", qualityBadgeLabel(networkSong(), "320k"))
        assertEquals("♪ 192k", qualityBadgeLabel(networkSong(), "192k"))
    }

    @Test
    fun `网络歌曲档位标签为空时回退自动`() {
        assertEquals("♪ 自动", qualityBadgeLabel(networkSong(), ""))
    }

    @Test
    fun `网络歌曲忽略 bitrate（档位才是可切换语义）`() {
        val s = networkSong().copy(bitrate = 320)
        assertEquals("♪ 极高", qualityBadgeLabel(s, "极高"))
    }

    // ── 本地 / NAS：显示真实码率（只读） ─────────────────────

    @Test
    fun `NAS 歌曲显示真实码率`() {
        assertEquals("♪ 320 kbps", qualityBadgeLabel(nasSong(320), ""))
        assertEquals("♪ 128 kbps", qualityBadgeLabel(nasSong(128), ""))
    }

    @Test
    fun `高码率按无损呈现`() {
        // FLAC 常见 1411 kbps
        assertEquals("♪ 无损 1.4M", qualityBadgeLabel(nasSong(1411), ""))
        // 恰好 1000
        assertEquals("♪ 无损 1.0M", qualityBadgeLabel(nasSong(1000), ""))
        // 边界：999 仍是普通码率
        assertEquals("♪ 999 kbps", qualityBadgeLabel(nasSong(999), ""))
    }

    @Test
    fun `本地歌曲有 bitrate 时显示`() {
        assertEquals("♪ 192 kbps", qualityBadgeLabel(localSong(bitrate = 192), ""))
    }

    // ── 无数据：不渲染（而非显示 0 kbps） ────────────────────

    @Test
    fun `本地歌曲无 bitrate 无档位时不渲染`() {
        assertNull(
            "无码率信息时必须返回 null，不能显示 '♪ 0 kbps'",
            qualityBadgeLabel(localSong(), "")
        )
    }

    @Test
    fun `飞牛后端 bitrate 为 0 时不渲染`() {
        // FeiniuAdapter 的 BITRATE_UNVERIFIED = 0（单位未确认故不填）
        assertNull(qualityBadgeLabel(nasSong(0), ""))
    }

    @Test
    fun `currentSong 为 null 时返回 null`() {
        assertNull(qualityBadgeLabel(null, "无损"))
    }

    // ── 回退：已下载歌曲的实际档位 ───────────────────────────

    @Test
    fun `无 bitrate 时回退 resolvedQuality`() {
        assertEquals("♪ 320 kbps", qualityBadgeLabel(localSong(resolvedQuality = 320), ""))
        assertEquals("♪ 192 kbps", qualityBadgeLabel(localSong(resolvedQuality = 192), ""))
        assertEquals("♪ 128 kbps", qualityBadgeLabel(localSong(resolvedQuality = 128), ""))
    }

    @Test
    fun `resolvedQuality 为 999 显示无损而非 999 kbps`() {
        // 999 是 FLAC 约定标识符，不是真实码率
        assertEquals("♪ 无损", qualityBadgeLabel(localSong(resolvedQuality = 999), ""))
    }

    @Test
    fun `bitrate 优先于 resolvedQuality`() {
        val s = localSong(bitrate = 320, resolvedQuality = 128)
        assertEquals("真实码率优先", "♪ 320 kbps", qualityBadgeLabel(s, ""))
    }

    @Test
    fun `AUTO 档不作为回退值渲染`() {
        assertNull(qualityBadgeLabel(localSong(resolvedQuality = 0), ""))
    }
}
