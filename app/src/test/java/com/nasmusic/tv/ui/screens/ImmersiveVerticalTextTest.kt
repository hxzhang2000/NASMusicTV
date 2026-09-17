package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸播放页竖排文字的拆字逻辑单测（`NowPlayingScreen.splitToCodePoints`）。
 *
 * 竖排是把字符串逐字拆成一列 `Text`，拆错的后果是**乱码**，而不是排版小瑕疵：
 * - 按 `Char` 拆会把 emoji / 生僻字的**代理对**切成两个孤立 surrogate → 显示成豆腐块
 * - 截断时若直接截断而不补「…」，用户看不出歌名被截了
 *
 * 这里锁住：按码点切、超长补省略号且总长不超 [maxChars]、短文本原样返回。
 */
class ImmersiveVerticalTextTest {

    @Test
    fun `中文按字拆分`() {
        assertEquals(listOf("夜", "空", "中", "最", "亮", "的", "星"), splitToCodePoints("夜空中最亮的星", 18))
    }

    @Test
    fun `短于上限时原样返回且不补省略号`() {
        val r = splitToCodePoints("起风了", 18)
        assertEquals(listOf("起", "风", "了"), r)
        assertTrue(r.none { it == "…" })
    }

    @Test
    fun `长度正好等于上限时不补省略号`() {
        val r = splitToCodePoints("一二三四五", 5)
        assertEquals(listOf("一", "二", "三", "四", "五"), r)
    }

    @Test
    fun `超长时截断并把末字替换为省略号`() {
        val r = splitToCodePoints("Yesterday", 5)
        assertEquals(5, r.size)
        assertEquals(listOf("Y", "e", "s", "t", "…"), r)
    }

    @Test
    fun `截断后总长不超过上限`() {
        val r = splitToCodePoints("非常非常长的一首歌名超过十八个字一定会溢出封面", 18)
        assertEquals(18, r.size)
        assertEquals("…", r.last())
    }

    @Test
    fun `emoji 代理对不被拆开`() {
        assertEquals(listOf("\uD83C\uDF19", "夜", "曲"), splitToCodePoints("\uD83C\uDF19夜曲", 18))
    }

    @Test
    fun `生僻字代理对不被拆开`() {
        // U+20BB7（"𠮷"）需一对 surrogate
        assertEquals(listOf("\uD842\uDFB7", "田"), splitToCodePoints("\uD842\uDFB7田", 18))
    }

    @Test
    fun `上限为 1 且文本更长时只剩省略号`() {
        assertEquals(listOf("…"), splitToCodePoints("夜曲", 1))
    }

    @Test
    fun `空字符串返回空列表`() {
        assertTrue(splitToCodePoints("", 18).isEmpty())
    }
}
