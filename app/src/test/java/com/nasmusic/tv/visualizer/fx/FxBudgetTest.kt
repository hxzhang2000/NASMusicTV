package com.nasmusic.tv.visualizer.fx

import com.nasmusic.tv.data.model.VisualQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §八 G1：画质 → 后处理档位推导（含负向自证：分支对调必须判失败） */
class FxBudgetTest {

    @Test
    fun `三档画质推导 - LOW→OFF MEDIUM→LITE HIGH→FULL`() {
        assertEquals(FxLevel.OFF, FxBudget.of(VisualQuality.LOW))
        assertEquals(FxLevel.LITE, FxBudget.of(VisualQuality.MEDIUM))
        assertEquals(FxLevel.FULL, FxBudget.of(VisualQuality.HIGH))
    }

    @Test
    fun `推导依据 - allowFramebuffer 只有 HIGH 为 true`() {
        // 防止枚举参数重排后推导悄然失配（AppSettings.kt:252-254 的字面量）
        assertTrue(VisualQuality.HIGH.allowFramebuffer)
        assertTrue(!VisualQuality.MEDIUM.allowFramebuffer)
        assertTrue(!VisualQuality.LOW.allowFramebuffer)
        assertEquals(3, VisualQuality.HIGH.glowLayers)
        assertEquals(2, VisualQuality.MEDIUM.glowLayers)
        assertEquals(1, VisualQuality.LOW.glowLayers)
    }

    @Test
    fun `负向 - 分支对调的错误实现必须被判失败`() {
        // 模拟"of() 的第一分支被对调（allowFramebuffer → OFF）"的错误实现：
        fun swappedOf(quality: VisualQuality): FxLevel = when {
            quality.allowFramebuffer -> FxLevel.OFF          // ⛔ 错误：HIGH 判成 OFF
            quality.glowLayers >= 2 -> FxLevel.LITE
            else -> FxLevel.FULL
        }
        // 正确实现与错误实现的结论必须不同 —— 否则本文件的等值断言抓不住对调
        assertTrue(FxBudget.of(VisualQuality.HIGH) != swappedOf(VisualQuality.HIGH))
        assertTrue(FxBudget.of(VisualQuality.LOW) != swappedOf(VisualQuality.LOW))
    }
}
