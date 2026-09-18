package com.nasmusic.tv.backend.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QualityTiers] 档位单一真相源测试（多码率方案 §10.1）。
 */
class QualityTiersTest {

    @Test
    fun `AUTO fallbackChain 为 listOf(null)`() {
        assertEquals(listOf<Int?>(null), QualityTiers.fallbackChainOf(QualityTiers.AUTO))
    }

    @Test
    fun `LOSSLESS 降级链含 192`() {
        // v2.35.0 关键更正：原实现 999 → 320 → 128 会跳过 192
        assertEquals(
            listOf<Int?>(999, 320, 192, 128),
            QualityTiers.fallbackChainOf(QualityTiers.LOSSLESS)
        )
    }

    @Test
    fun `HIGH 降级链`() {
        assertEquals(
            listOf<Int?>(320, 192, 128),
            QualityTiers.fallbackChainOf(QualityTiers.HIGH)
        )
    }

    @Test
    fun `GOOD 降级链`() {
        assertEquals(listOf<Int?>(192, 128), QualityTiers.fallbackChainOf(QualityTiers.GOOD))
    }

    @Test
    fun `STANDARD 降级链`() {
        assertEquals(listOf<Int?>(128), QualityTiers.fallbackChainOf(QualityTiers.STANDARD))
    }

    @Test
    fun `preferredExtOf 无损为 flac 其余为 mp3`() {
        assertEquals("flac", QualityTiers.preferredExtOf(QualityTiers.LOSSLESS))
        assertEquals("mp3", QualityTiers.preferredExtOf(QualityTiers.HIGH))
        assertEquals("mp3", QualityTiers.preferredExtOf(QualityTiers.GOOD))
        assertEquals("mp3", QualityTiers.preferredExtOf(QualityTiers.STANDARD))
        assertEquals("mp3", QualityTiers.preferredExtOf(QualityTiers.AUTO))
    }

    @Test
    fun `all 恰含 5 个值且含 192`() {
        assertEquals(5, QualityTiers.all.size)
        assertTrue(QualityTiers.all.contains(QualityTiers.GOOD))
        assertEquals(192, QualityTiers.GOOD)
    }

    @Test
    fun `availableTiers 恰含 4 个值且不含 AUTO 且降序`() {
        assertEquals(4, QualityTiers.availableTiers.size)
        assertFalse(QualityTiers.availableTiers.contains(QualityTiers.AUTO))
        assertEquals(listOf(999, 320, 192, 128), QualityTiers.availableTiers)
    }

    @Test
    fun `isNoBr 仅 AUTO 为 true`() {
        assertTrue(QualityTiers.isNoBr(QualityTiers.AUTO))
        assertFalse(QualityTiers.isNoBr(QualityTiers.LOSSLESS))
        assertFalse(QualityTiers.isNoBr(QualityTiers.STANDARD))
    }

    @Test
    fun `isValid 覆盖全部档位`() {
        QualityTiers.all.forEach { assertTrue("tier=$it 应合法", QualityTiers.isValid(it)) }
        assertFalse(QualityTiers.isValid(256))
        assertFalse(QualityTiers.isValid(-1))
    }

    @Test
    fun `descriptionOf 无损返回 FLAC 而非 999 kbps`() {
        // 999 是 FLAC 标识符，不是真实码率 —— 不能显示成 "999 kbps"
        assertEquals("FLAC", QualityTiers.descriptionOf(QualityTiers.LOSSLESS))
        assertEquals("320 kbps", QualityTiers.descriptionOf(QualityTiers.HIGH))
        assertEquals("192 kbps", QualityTiers.descriptionOf(QualityTiers.GOOD))
        assertEquals("128 kbps", QualityTiers.descriptionOf(QualityTiers.STANDARD))
    }
}
