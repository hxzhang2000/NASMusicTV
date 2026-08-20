package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BaiduFilenameParser 文件名解析单测。
 *
 * 覆盖：歌手-歌名分隔、括号/标签/轨道号清洗、全角破折号分隔、边界情况。
 * 纯 JVM 测试（object，不依赖 Android）。
 */
class BaiduFilenameParserTest {

    // ── 基本分隔 ──

    @Test
    fun `常规分隔解析`() {
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦 - 晴天.mp3"))
    }

    @Test
    fun `下划线分隔解析`() {
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦_-_晴天.mp3"))
    }

    @Test
    fun `全角破折号分隔解析`() {
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦－晴天.mp3"))
    }

    @Test
    fun `半角破折号无空格不切分`() {
        // "周杰伦-晴天" 半角破折号无空格：SEPARATORS 不含裸 "-"，整串作 title
        assertEquals("" to "周杰伦-晴天", BaiduFilenameParser.parse("周杰伦-晴天.mp3"))
    }

    // ── 清洗 ──

    @Test
    fun `括号注释被去除`() {
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦 - 晴天 (Live).mp3"))
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦 - 晴天[高清].mp3"))
    }

    @Test
    fun `尾部标签被去除`() {
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦 - 晴天 - official.mp3"))
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("周杰伦 - 晴天 - 无损.mp3"))
    }

    @Test
    fun `轨道号前缀被去除`() {
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("01 - 周杰伦 - 晴天.mp3"))
        assertEquals("周杰伦" to "晴天", BaiduFilenameParser.parse("CD1 03 - 周杰伦 - 晴天.mp3"))
    }

    @Test
    fun `组合清洗`() {
        assertEquals(
            "周杰伦" to "晴天",
            BaiduFilenameParser.parse("01 - 周杰伦 - 晴天 (Live) - official.mp3")
        )
    }

    // ── 边界 ──

    @Test
    fun `无分隔符 artist 为空 title 用文件名`() {
        assertEquals("" to "晴天", BaiduFilenameParser.parse("晴天.mp3"))
    }

    @Test
    fun `歌手名含多个字不误切`() {
        assertEquals("林俊杰" to "江南", BaiduFilenameParser.parse("林俊杰 - 江南.mp3"))
    }

    @Test
    fun `文件名含点号仅去最后扩展名`() {
        // clean 会把中间 "." 归一化为空格 → "晴天 正式"
        assertEquals("周杰伦" to "晴天 正式", BaiduFilenameParser.parse("周杰伦 - 晴天.正式.mp3"))
    }

    @Test
    fun `空字符串容错`() {
        val result = BaiduFilenameParser.parse("")
        // 不抛异常，返回空 artist + 空 title
        assertEquals("", result.first)
    }
}