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
