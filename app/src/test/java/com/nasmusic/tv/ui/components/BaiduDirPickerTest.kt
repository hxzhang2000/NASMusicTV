package com.nasmusic.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 根目录选择器（BaiduDirPickerDialog）目录树导航逻辑单测。
 *
 * 覆盖对话框内 [parentPath]（上级路径）与 [childPath]（子路径）两个纯函数，
 * 即目录树"加载/进入子目录/返回上级"的核心路径计算。
 * 纯 JVM 测试（不涉及 Compose 渲染 / android.util.Log）。
 */
class BaiduDirPickerTest {

    // ── parentPath（返回上级）──

    @Test
    fun `parentPath 多级目录返回上级`() {
        assertEquals("/音乐", parentPath("/音乐/周杰伦"))
    }

    @Test
    fun `parentPath 一级目录返回根`() {
        assertEquals("/", parentPath("/音乐"))
    }

    @Test
    fun `parentPath 根目录保持根`() {
        assertEquals("/", parentPath("/"))
    }

    @Test
    fun `parentPath 尾部斜杠被忽略`() {
        assertEquals("/音乐", parentPath("/音乐/周杰伦/"))
    }

    // ── childPath（进入子目录）──

    @Test
    fun `childPath 常规路径拼接`() {
        assertEquals("/音乐/周杰伦", childPath("/音乐", "周杰伦"))
    }

    @Test
    fun `childPath 根目录拼接`() {
        assertEquals("/音乐", childPath("/", "音乐"))
    }

    @Test
    fun `childPath 父路径尾部斜杠不重复`() {
        assertEquals("/音乐/周杰伦", childPath("/音乐/", "周杰伦"))
    }
}