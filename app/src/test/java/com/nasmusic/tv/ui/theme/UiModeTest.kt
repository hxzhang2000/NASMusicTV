package com.nasmusic.tv.ui.theme

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import com.nasmusic.tv.ui.components.adaptiveColumnsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assert(physicalOf56 >= 44f) { "底部导航 56dp 容器物理高度不足 44dp" }

        val composeFor44Physical = 44f / scale
        assertEquals(53.66f, composeFor44Physical, 0.01f)
        assert(composeFor44Physical > 44f) { "44 物理 dp 需要更大的 Compose dp" }
    }

    @Test
    fun `LYRICS_RECOVER_SCALE 是 PHONE_UI_SCALE 的倒数`() {
        assertEquals(1f / CompactSizes.PHONE_UI_SCALE, CompactSizes.LYRICS_RECOVER_SCALE, 0.0001f)
    }
}
