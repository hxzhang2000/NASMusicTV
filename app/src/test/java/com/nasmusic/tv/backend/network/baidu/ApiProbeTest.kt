package com.nasmusic.tv.backend.network.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ApiProbe 单测：字段指纹计算（同一响应稳定、缺字段/多字段变化、字段顺序无关、
 * 值变化不影响）+ apiDrifted 判定 + 一次性提示逻辑。
 *
 * Robolectric：解析失败路径 AppLog 会调 android.util.Log。
 */
@RunWith(RobolectricTestRunner::class)
class ApiProbeTest {

    // ── 指纹稳定性 ──

    @Test
    fun `同一响应指纹稳定`() {
        val json = """{"errno":0,"list":[{"fs_id":"123","dlink":"d","filename":"x.mp3"}]}"""
        assertEquals(ApiProbe.computeFieldFingerprint(json), ApiProbe.computeFieldFingerprint(json))
    }

    @Test
    fun `字段顺序不同指纹相同`() {
        val a = """{"errno":0,"list":[{"fs_id":"123","dlink":"d","filename":"x.mp3"}]}"""
        val b = """{"list":[{"filename":"x.mp3","dlink":"d","fs_id":"123"}],"errno":0}"""
        assertEquals(ApiProbe.computeFieldFingerprint(a), ApiProbe.computeFieldFingerprint(b))
    }

    @Test
    fun `字段值变化不影响指纹`() {
        val a = """{"errno":0,"list":[{"fs_id":"123","dlink":"d1","filename":"x.mp3"}]}"""
        val b = """{"errno":0,"list":[{"fs_id":"123","dlink":"d2","filename":"x.mp3"}]}"""
        assertEquals(ApiProbe.computeFieldFingerprint(a), ApiProbe.computeFieldFingerprint(b))
    }

    // ── 缺字段 / 多字段敏感性 ──

    @Test
    fun `缺少字段指纹变化`() {
        val full = """{"errno":0,"list":[{"fs_id":"123","dlink":"d","filename":"x.mp3"}]}"""
        val missing = """{"errno":0,"list":[{"fs_id":"123","dlink":"d"}]}"""
        assertNotEquals(ApiProbe.computeFieldFingerprint(full), ApiProbe.computeFieldFingerprint(missing))
    }

    @Test
    fun `新增字段指纹变化`() {
        val before = """{"errno":0,"list":[{"fs_id":"123"}]}"""
        val after = """{"errno":0,"list":[{"fs_id":"123","new_field":"x"}]}"""
        assertNotEquals(ApiProbe.computeFieldFingerprint(before), ApiProbe.computeFieldFingerprint(after))
    }

    @Test
    fun `嵌套字段变化指纹变化`() {
        val withThumbs = """{"errno":0,"list":[{"fs_id":"123","thumbs":{"url":"u"}}]}"""
        val noThumbs = """{"errno":0,"list":[{"fs_id":"123"}]}"""
        assertNotEquals(
            ApiProbe.computeFieldFingerprint(withThumbs),
            ApiProbe.computeFieldFingerprint(noThumbs)
        )
    }

    @Test
    fun `解析失败返回 null`() {
        assertNull(ApiProbe.computeFieldFingerprint("not-json{{{"))
    }

    // ── isDrifted 判定 ──

    @Test
    fun `isDrifted 基线为空返回 false`() {
        assertFalse(ApiProbe.isDrifted(null, "fp"))
        assertFalse(ApiProbe.isDrifted("", "fp"))
    }

    @Test
    fun `isDrifted 实测为空返回 false`() {
        assertFalse(ApiProbe.isDrifted("fp", null))
        assertFalse(ApiProbe.isDrifted("fp", ""))
    }

    @Test
    fun `isDrifted 一致返回 false`() {
        assertFalse(ApiProbe.isDrifted("same", "same"))
    }

    @Test
    fun `isDrifted 不一致返回 true`() {
        assertTrue(ApiProbe.isDrifted("fp-v1", "fp-v2"))
    }

    // ── shouldNotifyDrift（一次性提示去重）──

    @Test
    fun `漂移且未提示过应提示`() {
        assertTrue(ApiProbe.shouldNotifyDrift(apiDrifted = true, alreadyNotified = false))
    }

    @Test
    fun `漂移但已提示过不再提示`() {
        assertFalse(ApiProbe.shouldNotifyDrift(apiDrifted = true, alreadyNotified = true))
    }

    @Test
    fun `未漂移不提示`() {
        assertFalse(ApiProbe.shouldNotifyDrift(apiDrifted = false, alreadyNotified = false))
    }
}