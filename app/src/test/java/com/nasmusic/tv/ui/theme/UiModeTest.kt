package com.nasmusic.tv.ui.theme

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.nasmusic.tv.ui.components.PHONE_TOUCH_TARGET_DP
import com.nasmusic.tv.ui.components.adaptiveColumnsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏 UI 方案（docs/phone-portrait-ui-plan.md）§8.1 / §10.1 的纯函数单测。
 *
 * 覆盖：
 * - [deriveUiMode] 形态判定（TV 优先，B1 硬规则回归）
 * - [resolveOrientation] 全局策略 + 全屏页覆盖（含脏数据回退）
 * - [ScreenOrientationPref.nextOnToggle] L2 单击循环（D1）
 * - [adaptiveColumnsOf] 列数阈值（`>=1000` / `>=600` / else）
 * - §2.7 dp 口径护栏（`PHONE_UI_SCALE` 下的物理/Compose dp 换算）
 *
 * ⚠️ `ActivityInfo.SCREEN_ORIENTATION_*` 与 `Configuration.ORIENTATION_*` 都是
 * 编译期常量（会被内联），因此本文件可在**纯 JVM**（无需 Robolectric）下运行。
 */
class UiModeTest {

    // ── deriveUiMode ──

    @Test
    fun `TV 设备即使竖屏也判定为 TV`() {
        assertEquals(UiMode.TV, deriveUiMode(isTV = true, orientation = Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun `TV 设备横屏判定为 TV`() {
        assertEquals(UiMode.TV, deriveUiMode(isTV = true, orientation = Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test
    fun `手机竖屏判定为 PhonePortrait`() {
        assertEquals(UiMode.PhonePortrait, deriveUiMode(isTV = false, orientation = Configuration.ORIENTATION_PORTRAIT))
    }

    @Test
    fun `手机横屏判定为 PhoneLandscape`() {
        assertEquals(UiMode.PhoneLandscape, deriveUiMode(isTV = false, orientation = Configuration.ORIENTATION_LANDSCAPE))
    }

    /**
     * B1 回归护栏（方案 §3.1 硬规则）：
     * 横屏手机**不得**落入 TV 分支 —— 否则 AppRoot 会把横屏手机切到新的 PhoneTopBar/PhoneNavBar，
     * 直接违反 §10.3 用例 3「横屏与改前一致」。
     */
    @Test
    fun `横屏手机不得判定为 TV（B1 回归）`() {
        assertNotEquals(UiMode.TV, deriveUiMode(isTV = false, orientation = Configuration.ORIENTATION_LANDSCAPE))
    }

    /** 未知方向值（防御脏数据）不应落到 PhonePortrait，避免误开竖屏新界面。 */
    @Test
    fun `未知方向值不落 PhonePortrait`() {
        assertEquals(UiMode.PhoneLandscape, deriveUiMode(isTV = false, orientation = 999))
    }

    // ── resolveOrientation ──

    @Test
    fun `auto 且非全屏页 返回 UNSPECIFIED`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            resolveOrientation("auto", isFullScreenPage = false)
        )
    }

    @Test
    fun `portrait 返回 USER_PORTRAIT`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
            resolveOrientation("portrait", isFullScreenPage = false)
        )
    }

    @Test
    fun `landscape 返回 USER_LANDSCAPE`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
            resolveOrientation("landscape", isFullScreenPage = false)
        )
    }

    @Test
    fun `全屏页覆盖一切 pref（含 portrait）`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            resolveOrientation("portrait", isFullScreenPage = true)
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            resolveOrientation("auto", isFullScreenPage = true)
        )
    }

    @Test
    fun `空串与未知值一律回退 UNSPECIFIED`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, resolveOrientation("", isFullScreenPage = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, resolveOrientation("PORTRAIT", isFullScreenPage = false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, resolveOrientation("sensor", isFullScreenPage = false))
    }

    /** 绝不能用 SENSOR / FULL_SENSOR —— 两者都会忽略用户的系统旋转锁（方案 C6）。 */
    @Test
    fun `非全屏页的任何输入都不会返回忽略系统旋转锁的 SENSOR 系列`() {
        val inputs = listOf("auto", "portrait", "landscape", "", "garbage")
        inputs.forEach { pref ->
            val result = resolveOrientation(pref, isFullScreenPage = false)
            assertNotEquals("pref=$pref", ActivityInfo.SCREEN_ORIENTATION_SENSOR, result)
            assertNotEquals("pref=$pref", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, result)
            assertNotEquals("pref=$pref", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, result)
            assertNotEquals("pref=$pref", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, result)
        }
    }

    // ── ScreenOrientationPref.nextOnToggle（L2 单击，D1）──

    @Test
    fun `L2 单击在竖横之间循环`() {
        assertEquals(ScreenOrientationPref.LANDSCAPE, ScreenOrientationPref.nextOnToggle(ScreenOrientationPref.PORTRAIT))
        assertEquals(ScreenOrientationPref.PORTRAIT, ScreenOrientationPref.nextOnToggle(ScreenOrientationPref.LANDSCAPE))
    }

    @Test
    fun `L2 在自动态单击落竖屏`() {
        assertEquals(ScreenOrientationPref.PORTRAIT, ScreenOrientationPref.nextOnToggle(ScreenOrientationPref.AUTO))
        assertEquals(ScreenOrientationPref.PORTRAIT, ScreenOrientationPref.nextOnToggle(""))
    }

    @Test
    fun `L2 永远不会切到自动态`() {
        listOf(ScreenOrientationPref.AUTO, ScreenOrientationPref.PORTRAIT, ScreenOrientationPref.LANDSCAPE).forEach {
            assertNotEquals(ScreenOrientationPref.AUTO, ScreenOrientationPref.nextOnToggle(it))
        }
    }

    // ── adaptiveColumnsOf ──

    @Test
    fun `列数阈值 竖屏落 phonePortrait`() {
        assertEquals(3, adaptiveColumnsOf(360, tv = 6, phonePortrait = 3, medium = 6))
        assertEquals(3, adaptiveColumnsOf(599, tv = 6, phonePortrait = 3, medium = 6))
    }

    @Test
    fun `列数阈值 600 起落 medium`() {
        assertEquals(6, adaptiveColumnsOf(600, tv = 6, phonePortrait = 3, medium = 6))
        assertEquals(6, adaptiveColumnsOf(999, tv = 6, phonePortrait = 3, medium = 6))
    }

    @Test
    fun `列数阈值 1000 起落 tv`() {
        assertEquals(6, adaptiveColumnsOf(1000, tv = 6, phonePortrait = 3, medium = 6))
        assertEquals(6, adaptiveColumnsOf(1920, tv = 6, phonePortrait = 3, medium = 6))
    }

    @Test
    fun `列数阈值 电台网格三档`() {
        assertEquals(3, adaptiveColumnsOf(1280, tv = 3, phonePortrait = 1, medium = 2))
        assertEquals(2, adaptiveColumnsOf(800, tv = 3, phonePortrait = 1, medium = 2))
        assertEquals(1, adaptiveColumnsOf(360, tv = 3, phonePortrait = 1, medium = 2))
    }

    // ── §2.7 dp 口径护栏 ──

    /**
     * 竖屏下 `LocalDensity` 被 `PHONE_UI_SCALE = 0.82` 缩放，
     * Compose 里写的 `X.dp` 实际只占 `X × 0.82` 个**物理 dp**。
     *
     * 护栏 1：底部导航容器 Compose 56dp → 物理 ≈ 45.9dp ≥ 44dp（D3 达标）。
     * 护栏 2：物理 44dp 需要 Compose ≥ 53.7dp —— 代码里**不能**再拿 44dp 当热区下限。
     *
     * ⚠️ 函数名里不能出现 `.`（JVM 方法名非法字符），故写作「2-7」而非「2.7」。
     */
    @Test
    fun `竖屏触摸目标 dp 口径（D3 与方案 2-7 回归护栏）`() {
        val scale = CompactSizes.PHONE_UI_SCALE
        val physicalOf56 = 56 * scale
        assertEquals(45.92f, physicalOf56, 0.01f)
        assertTrue("底部导航 56dp 容器物理高度不足 44dp", physicalOf56 >= 44f)

        val composeFor44Physical = 44f / scale
        assertEquals(53.66f, composeFor44Physical, 0.01f)
        assertTrue("44 物理 dp 需要更大的 Compose dp", composeFor44Physical > 44f)
    }

    /**
     * P0-26 回归护栏：`PHONE_TOUCH_TARGET` 必须真的满足「物理 ≥ 44dp」，
     * 且 44 / 48 / 52 这三个**曾被写进代码**的值必须被证明不达标 —— 防止有人再把它们改回去。
     *
     * ⚠️ 这里用 `assertTrue` 而**不是** Kotlin 的 `assert(...)`：
     * 后者在未开 `-ea` 的测试 JVM 里是**空操作**，护栏会静默失效。
     */
    @Test
    fun `PHONE_TOUCH_TARGET 满足物理 44dp 而 44 与 48 不满足`() {
        val scale = CompactSizes.PHONE_UI_SCALE

        // 常量本身达标
        val physicalOfTarget = PHONE_TOUCH_TARGET_DP * scale
        assertTrue(
            "PHONE_TOUCH_TARGET_DP=$PHONE_TOUCH_TARGET_DP 只有 $physicalOfTarget 物理 dp",
            physicalOfTarget >= 44f,
        )
        assertTrue(
            "常量小于 44 物理 dp 所需的 Compose dp",
            PHONE_TOUCH_TARGET_DP >= 44f / scale,
        )
        // 且是「够用的最小整数档」：再小一档（52）就不够了，说明不能下调
        assertTrue("若 52dp 已达标，PHONE_TOUCH_TARGET 应下调", 52f * scale < 44f)

        // 历史上被写进代码的三个值都不达标（§2.7 第 1 条的原始依据）
        assertTrue("44 Compose dp 竟然达标了？§2.7 口径需重新推导", 44f * scale < 44f)
        assertTrue("48 Compose dp 竟然达标了？§2.7 口径需重新推导", 48f * scale < 44f)
        assertEquals(36.08f, 44f * scale, 0.01f)
        assertEquals(39.36f, 48f * scale, 0.01f)
    }

    @Test
    fun `LYRICS_RECOVER_SCALE 是 PHONE_UI_SCALE 的倒数`() {
        assertEquals(1f / CompactSizes.PHONE_UI_SCALE, CompactSizes.LYRICS_RECOVER_SCALE, 0.0001f)
    }

    /**
     * **P2-37 决策护栏**（方案 §9 P2-37「是否把 0.82 调到 0.88」）。
     *
     * 这不是"禁止改"，而是**强迫改的人先读这一段**：`PHONE_UI_SCALE` 是 §2.7 全部尺寸口径的
     * 唯一输入 —— 一改就要连带复核：
     *
     * 1. `CompactSizes.LYRICS_RECOVER_SCALE`（= 1 / PHONE_UI_SCALE，有独立用例守着）
     * 2. `PHONE_TOUCH_TARGET_DP` 的取值依据（0.82 下 52dp 不达标才取 56dp；
     *    若改到 0.88，52dp 恰好达标，取值应重新推导）
     * 3. 所有竖屏固定尺寸的物理换算（`docs/conventions-adaptive-ui.md` §6）
     * 4. 上表 §2.7 的"两条硬结论"与方案 §2.5 的决策记录
     *
     * 本版（v2.36.0）**有意保持 0.82**：方案标为"可选"，且无法上机验证改后的观感。
     */
    @Test
    fun `PHONE_UI_SCALE 变更需同步复核 2-7 全部口径（P2-37 护栏）`() {
        assertTrue(
            "PHONE_UI_SCALE 变了 —— 请同步复核 docs/conventions-adaptive-ui.md §6：" +
                "PHONE_TOUCH_TARGET 取值依据、LYRICS_RECOVER_SCALE、各竖屏固定尺寸的物理 dp，" +
                "以及 docs/phone-portrait-ui-plan.md §2.7 / §2.5 的结论。" +
                "当前实测值 = ${CompactSizes.PHONE_UI_SCALE}",
            kotlin.math.abs(CompactSizes.PHONE_UI_SCALE - 0.82f) < 0.0001f,
        )
    }
}
