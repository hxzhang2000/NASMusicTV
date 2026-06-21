package com.nasmusic.tv.lyrics

import com.nasmusic.tv.data.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E-2: LrcParser 单元测试
 */
class LrcParserTest {

    @Test
    fun `parse valid LRC with single timestamp`() {
        val lrc = "[00:01.50]Hello World"
        val lyrics = LrcParser.parse(lrc, "test-1")
        assertEquals(1, lyrics.lines.size)
        assertEquals("Hello World", lyrics.lines[0].text)
        assertEquals(1500L, lyrics.lines[0].time)
        assertEquals(LyricsSource.EMBEDDED, lyrics.source)
    }

    @Test
    fun `parse LRC with multiple timestamps`() {
        val lrc = """
[00:01.00]Line 1
[00:05.50]Line 2
[00:10.25]Line 3
        """.trimIndent()
        val lyrics = LrcParser.parse(lrc, "test-2")
        assertEquals(3, lyrics.lines.size)
        assertEquals("Line 1", lyrics.lines[0].text)
        assertEquals(1000L, lyrics.lines[0].time)
        assertEquals("Line 2", lyrics.lines[1].text)
        assertEquals(5500L, lyrics.lines[1].time)
        assertEquals("Line 3", lyrics.lines[2].text)
        assertEquals(10250L, lyrics.lines[2].time)
    }

    @Test
    fun `parse LRC with duplicate timestamps on one line`() {
        val lrc = "[00:01.00][00:02.50]Repeated line"
        val lyrics = LrcParser.parse(lrc, "test-3")
        assertEquals(2, lyrics.lines.size)
        assertEquals(1000L, lyrics.lines[0].time)
        assertEquals(2500L, lyrics.lines[1].time)
    }

    @Test
    fun `parse respects global offset`() {
        val lrc = "[offset:-500]\n[00:01.00]Offset line"
        val lyrics = LrcParser.parse(lrc, "test-4")
        assertEquals(1, lyrics.lines.size)
        assertEquals(500L, lyrics.lines[0].time)
    }

    @Test
    fun `parse sorts lines by time`() {
        val lrc = "[00:05.00]Second\n[00:01.00]First"
        val lyrics = LrcParser.parse(lrc, "test-5")
        assertEquals(2, lyrics.lines.size)
        assertEquals("First", lyrics.lines[0].text)
        assertEquals("Second", lyrics.lines[1].text)
    }

    @Test
    fun `isValidLrc returns true for valid format`() {
        assertTrue(LrcParser.isValidLrc("[00:01.00]text"))
        assertTrue(LrcParser.isValidLrc("[00:01.000]text"))
    }

    @Test
    fun `isValidLrc returns false for invalid format`() {
        assertFalse(LrcParser.isValidLrc("plain text"))
        assertFalse(LrcParser.isValidLrc(""))
        assertFalse(LrcParser.isValidLrc("[00:01]no milliseconds"))
    }

    @Test
    fun `toLrcText produces valid LRC`() {
        val lyrics = LrcParser.parse("[00:01.50]Hello", "test")
        val text = LrcParser.toLrcText(lyrics)
        assertTrue(text.contains("[00:01.50]"))
        assertTrue(text.contains("Hello"))
    }

    @Test
    fun `getCurrentLineIndex returns correct index`() {
        val lyrics = LrcParser.parse(
            "[00:01.00]First\n[00:05.00]Second\n[00:10.00]Third", "test"
        )
        assertEquals(-1, LrcParser.getCurrentLineIndex(lyrics, 0L))
        assertEquals(0, LrcParser.getCurrentLineIndex(lyrics, 1000L))
        assertEquals(0, LrcParser.getCurrentLineIndex(lyrics, 3000L))
        assertEquals(1, LrcParser.getCurrentLineIndex(lyrics, 5000L))
        assertEquals(2, LrcParser.getCurrentLineIndex(lyrics, 10000L))
        assertEquals(2, LrcParser.getCurrentLineIndex(lyrics, 99999L))
    }

    @Test
    fun `getCurrentLineIndex returns -1 for empty lyrics`() {
        val lyrics = LrcParser.parse("", "empty")
        assertEquals(-1, LrcParser.getCurrentLineIndex(lyrics, 0L))
    }
}
