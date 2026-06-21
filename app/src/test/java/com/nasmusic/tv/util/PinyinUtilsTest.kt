package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-2: PinyinUtils 单元测试
 *
 * 注：android.icu.text.Transliterator 需要 Android API 24+，
 * 纯 JUnit 环境（Robolectric/模拟器）下可能无法正常运行。
 * 这些测试覆盖了直接子串匹配的逻辑（独立于 ICU）。
 */
class PinyinUtilsTest {

    @Test
    fun `matches with empty query returns true`() {
        assertTrue(PinyinUtils.matches("周杰伦", ""))
        assertTrue(PinyinUtils.matches("Taylor", ""))
    }

    @Test
    fun `matches with direct substring matching`() {
        assertTrue(PinyinUtils.matches("周杰伦", "周杰伦"))
        assertTrue(PinyinUtils.matches("Taylor Swift", "taylor"))
        assertTrue(PinyinUtils.matches("Taylor Swift", "SWIFT"))
    }

    @Test
    fun `matches returns false for non-matching query`() {
        assertFalse(PinyinUtils.matches("周杰伦", "林俊杰"))
        assertFalse(PinyinUtils.matches("Taylor Swift", "Adele"))
    }

    @Test
    fun `matches with case insensitive matching`() {
        assertTrue(PinyinUtils.matches("Adele", "adele"))
        assertTrue(PinyinUtils.matches("adele", "ADELE"))
    }

    @Test
    fun `matches handles blank text`() {
        assertFalse(PinyinUtils.matches("", "a"))
    }
}
