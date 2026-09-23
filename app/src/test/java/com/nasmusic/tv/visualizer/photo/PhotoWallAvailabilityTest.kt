package com.nasmusic.tv.visualizer.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 **G8**（§14.4）—— 照片墙可用性由三来源开关派生（§7.4）。
 *
 * ## 为什么要单独一条门禁
 *
 * 原方案的「总开关」（`photoWallEnabled`）在 2026-09-23 被取消，降级为三个**来源开关**；
 * 「照片墙该不该出现在效果列表里」这件事从此**没有持久化字段**，而是**派生**出来的。
 * 派生逻辑一旦写错，症状是「设置了照片墙却看不到」或「三个都没开也看得到」——
 * 两种都不会崩、不会报错，只能靠单测抓。
 *
 * ## 为什么是「或」而不是「与」
 *
 * 绝大多数用户只会用一个来源（电视：U 盘；手机：图库；有 NAS 的：Jellyfin）。
 * 写成「与」就必须三个全开才生效 —— 那是显而易见的错误用法，但**代码上只差一个字符**。
 * ⇒ 本文件专门用一条**负向自证**把这个字符钉死（见最后一个用例）。
 *
 * 纯 JVM：被测对象只有 `Boolean` 入参，不碰 `android.*`，无需 Robolectric。
 */
class PhotoWallAvailabilityTest {

    @Test
    fun `every source off means unavailable`() {
        assertFalse(PhotoWallAvailability.isAvailable(false, false, false))
    }

    @Test
    fun `any single source on is enough`() {
        // 图库（手机）
        assertTrue("只开图库就该可用", PhotoWallAvailability.isAvailable(true, false, false))
        // 外接存储（电视 U 盘）
        assertTrue("只开外接存储就该可用", PhotoWallAvailability.isAvailable(false, true, false))
        // Jellyfin（NAS）—— §14.4 判据里点名的这一条
        assertTrue("只开 Jellyfin 就该可用", PhotoWallAvailability.isAvailable(false, false, true))
    }

    @Test
    fun `multiple sources on stays available`() {
        assertTrue(PhotoWallAvailability.isAvailable(true, true, false))
        assertTrue(PhotoWallAvailability.isAvailable(true, false, true))
        assertTrue(PhotoWallAvailability.isAvailable(false, true, true))
        assertTrue(PhotoWallAvailability.isAvailable(true, true, true))
    }

    /**
     * `isFullyDisabled` 必须是 [PhotoWallAvailability.isAvailable] 的**严格补集**。
     *
     * 它承担的是另一件事：三关时「完全无照片 I/O」（不扫描、不预解码、不申请权限、
     * 不启动人脸检测）。两条判定若各写一份 `||`，日后改一处忘一处就会「可用但没数据」。
     */
    @Test
    fun `fully disabled is the exact complement of available`() {
        for (g in BOOLS) {
            for (e in BOOLS) {
                for (j in BOOLS) {
                    val available = PhotoWallAvailability.isAvailable(g, e, j)
                    val disabled = PhotoWallAvailability.isFullyDisabled(g, e, j)
                    assertTrue(
                        "isFullyDisabled 必须与 isAvailable 互斥且穷尽：(g=$g, e=$e, j=$j)",
                        available != disabled,
                    )
                }
            }
        }
        assertTrue(PhotoWallAvailability.isFullyDisabled(false, false, false))
        assertFalse(PhotoWallAvailability.isFullyDisabled(false, false, true))
    }

    /**
     * ⛔ **负向自证**：证明本门禁真的能判出「把 `||` 写成 `&&`」这个笔误。
     *
     * 「或」与「与」的真值表**只在「全真」那一行重合** ⇒ 8 种组合里必须恰好有 **6 处**分歧。
     *
     * - 若分歧数为 `0`，说明两者等价 ⇒ 本门禁对写法完全不敏感（空转），
     *   上面那几条「只开一个来源就该可用」的断言也就失去了判别力；
     * - 若把上面任一断言改成断言 `false`，本用例不会变红（它只验分歧计数），
     *   所以它**不替代**正向断言，只补上「门禁有判别力」这一条证据。
     */
    @Test
    fun `negative proof - an AND implementation would disagree on exactly 6 of 8 combinations`() {
        var combos = 0
        var disagreements = 0
        for (g in BOOLS) {
            for (e in BOOLS) {
                for (j in BOOLS) {
                    combos++
                    val andImplementation = g && e && j
                    if (andImplementation != PhotoWallAvailability.isAvailable(g, e, j)) disagreements++
                }
            }
        }
        assertEquals("布尔三元的组合数必须正好是 8", 8, combos)
        assertEquals(
            "「或」与「与」只在全真那一行重合 ⇒ 必须恰好 6 处分歧；" +
                "为 0 说明本门禁区分不出这两种写法（空转）",
            6,
            disagreements,
        )
    }

    private companion object {
        val BOOLS = booleanArrayOf(false, true)
    }
}
