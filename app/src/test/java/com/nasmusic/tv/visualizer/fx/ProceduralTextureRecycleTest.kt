package com.nasmusic.tv.visualizer.fx

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** §八 G5：纹理仓库释放门禁（Robolectric：Bitmap.recycle / isRecycled 需 shadow 实现） */
@RunWith(RobolectricTestRunner::class)
class ProceduralTextureRecycleTest {

    private fun snapshotAllTiles(): List<androidx.compose.ui.graphics.ImageBitmap> {
        val set = LinkedHashSet<androidx.compose.ui.graphics.ImageBitmap>()
        for (id in ProceduralTexture.Id.entries) {
            for (v in 0 until ProceduralTexture.VARIANTS) {
                ProceduralTexture.tile(id, v)?.let { set.add(it) }
            }
        }
        return set.toList()
    }

    @Test
    fun `ensure 后 GRAIN 八变体与全屏纹理就位`() {
        ProceduralTexture.ensure(64, 48)
        for (v in 0 until ProceduralTexture.VARIANTS) {
            assertNotNull("GRAIN 变体 $v 缺失", ProceduralTexture.tile(ProceduralTexture.Id.GRAIN, v))
        }
        for (id in ProceduralTexture.Id.entries) {
            if (id == ProceduralTexture.Id.GRAIN) continue
            assertNotNull("$id 缺失", ProceduralTexture.tile(id))
        }
        // GRAIN 是 128×128；全屏型是 ensure 尺寸
        assertEquals(128, ProceduralTexture.tile(ProceduralTexture.Id.GRAIN)!!.width)
        assertEquals(64, ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)!!.width)
        assertEquals(48, ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)!!.height)
        ProceduralTexture.release()
    }

    @Test
    fun `release 后所有位图已回收 且 tile 恒 null`() {
        ProceduralTexture.ensure(64, 48)
        val sphere = ProceduralTexture.sphereSprite(Color(0xFFCC4422.toInt()))
        assertNotNull(sphere)
        val tiles = snapshotAllTiles()
        assertTrue("ensure 后至少应有 12 张纹理", tiles.size >= 12)

        ProceduralTexture.release()

        // ⛔ 断言①：全部位图 isRecycled == true（不是"只置 null"）
        for (t in tiles) {
            assertTrue("release 后仍有位图未回收", t.asAndroidBitmap().isRecycled)
        }
        assertTrue("sphereSprite 也必须回收", sphere!!.asAndroidBitmap().isRecycled)
        // 断言②：槽位已清 ⇒ tile() 恒 null（调用方判空跳过绘制）
        for (id in ProceduralTexture.Id.entries) {
            assertNull(ProceduralTexture.tile(id))
        }
        // 断言③：可安全重复调用
        ProceduralTexture.release()
        ProceduralTexture.release()
    }

    @Test
    fun `sphereSprite 同色命中缓存 - 异色另建`() {
        ProceduralTexture.release()
        val c1 = ProceduralTexture.sphereSprite(Color(0xFF3366AA.toInt()))
        val c1again = ProceduralTexture.sphereSprite(Color(0xFF3366AA.toInt()))
        assertNotNull(c1)
        assertTrue("同色应命中缓存（同一实例）", c1 === c1again)
        val c2 = ProceduralTexture.sphereSprite(Color(0xFF22AA55.toInt()))
        assertNotNull(c2)
        assertTrue("异色应另建实例", c1 !== c2)
        ProceduralTexture.release()
    }

    @Test
    fun `ensure 幂等 - 同尺寸重复调用不重建`() {
        ProceduralTexture.ensure(64, 48)
        val star1 = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        ProceduralTexture.ensure(64, 48)
        val star2 = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        assertTrue("同尺寸 ensure 应为 no-op（同一实例）", star1 === star2)
        // 尺寸变化 ⇒ 必须重建（缓存键含 (w,h)）
        ProceduralTexture.ensure(80, 60)
        val star3 = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        assertTrue(star3!!.width == 80 && star3.height == 60)
        ProceduralTexture.release()
    }

    // ═══════════ ensureFullscreenOnly（只烘 1 张全屏纹理） ═══════════

    @Test
    fun `ensureFullscreenOnly 只烘被点名的那一张 - 其余五张仍为 null`() {
        ProceduralTexture.release()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)

        val star = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        assertNotNull("STARFIELD 应就位", star)
        assertEquals(64, star!!.width)
        assertEquals(48, star.height)

        // ⛔ 断言：另外 5 张全屏纹理一张都不许烘（这正是首帧黑屏 6369ms 的成本来源）
        for (id in listOf(
            ProceduralTexture.Id.PAPER, ProceduralTexture.Id.WATER,
            ProceduralTexture.Id.CAUSTIC, ProceduralTexture.Id.PLASMA, ProceduralTexture.Id.FOG,
        )) {
            assertNull("$id 不应被 ensureFullscreenOnly 烘出", ProceduralTexture.tile(id))
        }
        // 平铺型同样不该被这次调用带出来（ensureFullscreenOnly 不碰它们）
        assertNull("GRAIN 是平铺型，不应被 ensureFullscreenOnly 带出", ProceduralTexture.tile(ProceduralTexture.Id.GRAIN))
        assertNull("SCANLINE 同理", ProceduralTexture.tile(ProceduralTexture.Id.SCANLINE))

        ProceduralTexture.release()
    }

    @Test
    fun `ensureFullscreenOnly 同尺寸重复调用是 no-op - 槽位实例不变`() {
        ProceduralTexture.release()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)
        val first = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)
        val second = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        assertTrue("同尺寸重复调用必须是 no-op（同一实例）", first === second)

        // 尺寸变化 ⇒ 必须重建（缓存键含 (w,h)，与 ensure 同一套记账）
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 80, 60)
        val third = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)!!
        assertTrue("尺寸变化必须重建", third.width == 80 && third.height == 60)
        assertTrue("重建后是不同实例", first !== third)
        ProceduralTexture.release()
    }

    @Test
    fun `负向 - ensureFullscreenOnly 传平铺型 Id 必须抛异常`() {
        ProceduralTexture.release()
        for (id in listOf(ProceduralTexture.Id.GRAIN, ProceduralTexture.Id.SCANLINE)) {
            try {
                ProceduralTexture.ensureFullscreenOnly(id, 64, 48)
                fail("$id 是平铺型，ensureFullscreenOnly 必须拒绝（否则门禁空转）")
            } catch (expected: IllegalArgumentException) {
                assertTrue("异常信息应点名是平铺型", expected.message!!.contains("平铺型"))
            }
        }
        ProceduralTexture.release()
    }

    @Test
    fun `负向 - 若 ensureFullscreenOnly 偷偷调 ensure - 本判据必须挂`() {
        // 自证：门禁检查的正是「另外 5 张槽位为 null」这件事。
        // 模拟"实现里改调了 ensure"（6 张全烘）⇒ 判据必须真的挂。
        ProceduralTexture.release()
        ProceduralTexture.ensure(64, 48)   // 假装是新实现的行为
        val leaked = ProceduralTexture.tile(ProceduralTexture.Id.FOG)
        try {
            assertNull("回退到 ensure 后 FOG 会被烘出，判据应挂", leaked)
            fail("只烘 1 张的判据必须是真判据（否则本门禁是空转）")
        } catch (expected: AssertionError) {
            // ✅ 预期：判据真的在工作
        } finally {
            ProceduralTexture.release()
        }
    }

    @Test
    fun `六个全屏 Id 逐个走 ensureFullscreenOnly 都能烘出 - 防止两份映射漂移`() {
        // ⛔ `ensure` 里那六行 `ensureFullscreen(Id.X, …)` 与 [rowFiller] 的 when 是**同一组
        //    lambda 的两份拷贝**（`ensure` 的字面量被 LightBeamsTest / PlasmaFlowTest 的源码
        //    扫描门禁锁死，不能改成循环）。本条锁住 `rowFiller` **没有漏掉任何一张**。
        val fullscreen = listOf(
            ProceduralTexture.Id.STARFIELD, ProceduralTexture.Id.PAPER, ProceduralTexture.Id.WATER,
            ProceduralTexture.Id.CAUSTIC, ProceduralTexture.Id.PLASMA, ProceduralTexture.Id.FOG,
        )
        for (id in fullscreen) {
            ProceduralTexture.release()
            ProceduralTexture.ensureFullscreenOnly(id, 32, 24)
            val t = ProceduralTexture.tile(id)
            assertNotNull("ensureFullscreenOnly($id) 必须能烘出", t)
            assertEquals("$id 宽", 32, t!!.width)
            assertEquals("$id 高", 24, t.height)
        }
        ProceduralTexture.release()
    }

    @Test
    fun `ensure 语义未被 ensureFullscreenOnly 改动 - 六张全屏纹理仍全部就位`() {
        ProceduralTexture.release()
        ProceduralTexture.ensure(64, 48)
        for (id in ProceduralTexture.Id.entries) {
            if (id == ProceduralTexture.Id.GRAIN) continue
            assertNotNull("ensure 回归：$id 缺失", ProceduralTexture.tile(id))
        }
        assertEquals(128, ProceduralTexture.tile(ProceduralTexture.Id.GRAIN)!!.width)
        ProceduralTexture.release()
    }

    // ═══════════ H4 修复回归（2026-10-06）：ensure / ensureFullscreenOnly 记账拆分 ═══════════
    // ⛔ 旧缺陷：两者共享 ensuredW/ensuredH 且 ensure 以「尺寸相同」整体早退 ⇒
    //    StarrySky（ensureFullscreenOnly）切到 LightBeams（ensure）后，其余 5 张
    //    全屏纹理永不生成。以下用例锁死「先 Only 后 ensure」的交叉场景。

    @Test
    fun `H4 - 先 ensureFullscreenOnly 后 ensure 同尺寸必须补齐其余五张`() {
        ProceduralTexture.release()
        // 场景：StarrySky 首帧只烘 STARFIELD
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)
        assertNotNull(ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD))
        // 切到 LightBeams 等 ensure 型渲染器：同尺寸下必须补齐全部（含平铺型）
        ProceduralTexture.ensure(64, 48)
        for (id in ProceduralTexture.Id.entries) {
            if (id == ProceduralTexture.Id.GRAIN) continue
            assertNotNull("H4：$id 应被 ensure 补齐", ProceduralTexture.tile(id))
        }
        for (v in 0 until ProceduralTexture.VARIANTS) {
            assertNotNull("H4：GRAIN 变体 $v 应被 ensure 补齐", ProceduralTexture.tile(ProceduralTexture.Id.GRAIN, v))
        }
        assertEquals(64, ProceduralTexture.tile(ProceduralTexture.Id.PAPER)!!.width)
        assertEquals(48, ProceduralTexture.tile(ProceduralTexture.Id.PAPER)!!.height)
        ProceduralTexture.release()
    }

    @Test
    fun `H4 - 反向交叉 先 ensure 后 ensureFullscreenOnly 同尺寸不重建已烘纹理`() {
        ProceduralTexture.release()
        ProceduralTexture.ensure(64, 48)
        val before = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        // ensure 已烘全 6 张 ⇒ ensureFullscreenOnly 同尺寸必须命中缓存不重建
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)
        assertTrue("同尺寸且该槽已烘，必须是 no-op", before === ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD))
        ProceduralTexture.release()
    }

    @Test
    fun `H4 - release 后 ensureFullscreenOnly 的记账一并复位`() {
        ProceduralTexture.release()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)
        ProceduralTexture.release()
        // release 复位 ensuredW/H ⇒ 同尺寸再次 ensureFullscreenOnly 必须重烘而不是误判已烘
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 64, 48)
        assertNotNull("release 后同尺寸必须能重烘", ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD))
        ProceduralTexture.release()
    }

    @Test
    fun `负向 - 未回收的位图必须被门禁判失败`() {
        // 门禁实现：assertAllRecycled（与上面 release 用例同一判据）
        fun assertAllRecycled(bitmaps: List<androidx.compose.ui.graphics.ImageBitmap>) {
            for (b in bitmaps) {
                assertTrue("有位图未回收", b.asAndroidBitmap().isRecycled)
            }
        }
        // 喂一张"只置 null 不 recycle"实现下的存活位图 ⇒ 判据必须真的挂
        val alive = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImageBitmap()
        try {
            assertAllRecycled(listOf(alive))
            fail("未回收的位图必须被判失败（否则本门禁是空转）")
        } catch (expected: AssertionError) {
            // ✅ 预期：判据真的在工作
        } finally {
            alive.asAndroidBitmap().recycle()
        }
    }
}
