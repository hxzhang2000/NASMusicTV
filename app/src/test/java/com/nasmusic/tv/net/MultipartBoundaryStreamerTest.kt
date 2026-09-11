package com.nasmusic.tv.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * multipart 边界流式扫描器回归测试。
 *
 * 重点覆盖 **自重叠边界**：朴素匹配（失配即 matched=0）会漏判起点，
 * 把边界字节写进文件；KMP 版本必须正确截断。
 */
class MultipartBoundaryStreamerTest {

    private fun scan(boundary: String, input: ByteArray): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val streamer = MultipartBoundaryStreamer(boundary.toByteArray(Charsets.ISO_8859_1))
        streamer.stream(ByteArrayInputStream(input), out)
        return out.toByteArray() to streamer.boundaryFound
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `simple boundary terminates content`() {
        val (out, found) = scan("XYZ", bytes("hello worldXYZ"))
        assertTrue(found)
        assertArrayEquals(bytes("hello world"), out)
    }

    @Test
    fun `content without boundary is fully written on EOF`() {
        val (out, found) = scan("XYZ", bytes("hello world"))
        assertFalse(found)
        assertArrayEquals(bytes("hello world"), out)
    }

    @Test
    fun `self overlapping boundary keeps correct start point`() {
        // 边界 "aab" 出现在内容 "aaab" 的 index=1 处 → 内容应为 "a"
        val (out, found) = scan("aab", bytes("aaab"))
        assertTrue(found)
        assertArrayEquals(bytes("a"), out)
    }

    @Test
    fun `self overlapping boundary with longer run`() {
        // "aaaaab" + 边界 "aab" → 边界出现在 index=3 → 内容 "aaa"
        val (out, found) = scan("aab", bytes("aaaaab"))
        assertTrue(found)
        assertArrayEquals(bytes("aaa"), out)
    }

    @Test
    fun `partial boundary prefix in content is preserved when not completed`() {
        // "\r\n--bound" 之后不是完整边界 → 这些字节属于内容
        val boundary = "\r\n--sep"
        val (out, found) = scan(boundary, bytes("data\r\n--se" + "X" + boundary))
        assertTrue(found)
        assertArrayEquals(bytes("data\r\n--seX"), out)
    }

    @Test
    fun `repeated near misses do not corrupt output`() {
        val boundary = "abab"
        // 内容里多次出现边界前缀 "aba" 但被打断，最后才出现真正边界
        val content = "aba" + "x" + "aba" + "y" + "aba"
        val (out, found) = scan(boundary, bytes(content + boundary))
        assertTrue(found)
        assertArrayEquals(bytes(content), out)
    }

    @Test
    fun `large payload crossing internal buffer boundary`() {
        val boundary = "\r\n--boundary"
        val payload = ByteArray(300 * 1024) { (it % 251).toByte() }
        val input = payload + bytes(boundary)
        val (out, found) = scan(boundary, input)
        assertTrue(found)
        assertArrayEquals(payload, out)
        assertEquals(payload.size.toLong(), out.size.toLong())
    }

    @Test
    fun `payload ending with partial boundary then EOF is preserved`() {
        val boundary = "aab"
        val content = "zzza"
        val (out, found) = scan(boundary, bytes(content))
        assertFalse(found)
        assertArrayEquals(bytes(content), out)
    }
}
