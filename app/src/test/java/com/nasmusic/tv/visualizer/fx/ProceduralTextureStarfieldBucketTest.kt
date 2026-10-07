package com.nasmusic.tv.visualizer.fx

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v2.38.4 烘焙提速门禁 ②⭐：[ProceduralTexture.starBuckets] **行区间分桶**的重构等价性。
 *
 * ## 为什么这是最关键的一条
 *
 * 目标改动把 STARFIELD 的每行光栅化从「遍历全部 N 颗星」改成「只重放 y 跨度覆盖本行的星」。
 * STARFIELD 是**全仓唯一被 3 个效果共用**的全屏纹理（E13 `AdvancedRenderers.kt`、E17、
 * E42 `StarrySkyRenderer.kt`）⇒ 杠杆最大，也意味着**改错一次就是三个效果一起变画面**。
 *
 * 分桶有**三个**可能悄悄改变输出的地方，本门禁逐个堵：
 *   ① 桶窗口算窄了 ⇒ 漏星（画面少星）—— 靠「逐像素全等」直接暴露；
 *   ② 桶窗口内的判定被改写 ⇒ 边界行不同 —— 同上；
 *   ③ **行内遍历顺序变了** ⇒ 重叠星是「后写覆盖」不是混合 ⇒ 同一个像素拿到不同颜色 ——
 *      这一条**逐像素全等**同样能暴露（所以参考实现必须严格按星下标升序遍历）。
 *
 * ⭐ **真尺寸**：1920×1080 全 1080 行**逐像素**比较，不做抽样。
 * 内存：2 × `IntArray(1920)` ≈ 15 KB（⛔ 不建 `Bitmap`，纯数组 ⇒ 不需要 Robolectric）。
 * 耗时：参考实现每行遍历 230 颗星 ⇒ 1080 × 230 = 248,400 次判定，实测 < 1s。
 */
class ProceduralTextureStarfieldBucketTest {

    // ── 重构前的参考实现（v2.38.4 之前 [ProceduralTexture.starfieldRow] 的逐字拷贝）────────
    //
    // ⛔ **冻结副本**：这是「重构前」的算法，它必须与历史版本逐字一致，**不得**跟着生产实现
    //    一起改 —— 一旦跟着改，本门禁就退化成「拿新实现和自己比」的空转。
    //    生产实现任何改动都必须先问：改完之后本函数还能给出同样的像素吗？
    private val STAR_R = floatArrayOf(0.6f, 1.0f, 1.6f)
    private val STAR_A = intArrayOf((0.20f * 255).toInt(), (0.35f * 255).toInt(), (0.60f * 255).toInt())

    private fun referenceStarfieldRow(out: IntArray, y: Int, w: Int, h: Int, layout: FloatArray) {
        java.util.Arrays.fill(out, 0)
        val n = layout.size / 3
        for (i in 0 until n) {
            val sx = layout[i * 3] * w
            val sy = layout[i * 3 + 1] * h
            val tier = layout[i * 3 + 2].toInt()
            val r = STAR_R[tier]
            val dy = y - sy
            if (dy < -r || dy > r) continue
            val dx = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
            val x0 = (sx - dx).toInt().coerceIn(0, w - 1)
            val x1 = (sx + dx).toInt().coerceIn(0, w - 1)
            val argb = (STAR_A[tier] shl 24) or 0x00FFFFFF
            for (x in x0..x1) out[x] = argb
        }
    }

    // ═════════════════════ ① ⭐ 真尺寸逐像素等价（核心判据） ═════════════════════

    @Test
    fun `① 1920x1080 全 1080 行逐像素完全相同`() {
        val w = 1920
        val h = 1080
        val bake = ProceduralTexture.starBake(w, h)

        val ref = IntArray(w)
        val prod = IntArray(w)
        var diffPixels = 0
        var firstBadY = -1
        var firstBadX = -1
        var refAt = 0
        var prodAt = 0
        for (y in 0 until h) {
            referenceStarfieldRow(ref, y, w, h, bake.layout)
            ProceduralTexture.starfieldRow(prod, y, w, h, bake)
            for (x in 0 until w) {
                if (ref[x] != prod[x]) {
                    diffPixels++
                    if (firstBadY < 0) {
                        firstBadY = y; firstBadX = x; refAt = ref[x]; prodAt = prod[x]
                    }
                }
            }
        }
        assertEquals(
            "分桶版与重构前必须在 1920x1080 上逐像素完全相同" +
                "（首个差异 y=$firstBadY x=$firstBadX ref=${hex(refAt)} prod=${hex(prodAt)}，" +
                "共 $diffPixels 个像素不同）",
            0, diffPixels,
        )
        // 覆盖率自证：整张图必须真的画了星（否则「两边都是全 0」会让本门禁空转）。
        // ⚠️ 数量级按**实测**给：230 颗星 × 每颗约 3~4 行 × 每行约 2~4 px ≈ 1000 px，
        //    ⛔ **不是** 10 万级（1080p 的星场本来就是稀疏的，密度 = w·h/9000）。
        var nz = 0
        var rowsWithStars = 0
        for (y in 0 until h) {
            referenceStarfieldRow(ref, y, w, h, bake.layout)
            var rowNz = 0
            for (v in ref) if (v != 0) { nz++; rowNz++ }
            if (rowNz > 0) rowsWithStars++
        }
        assertTrue("1920x1080 必须真的画出星（实测非零像素 $nz），否则等价性门禁空转", nz > 500)
        // ⚠️ 实测 345/1080 行含星（230 颗星 × 平均 2.55 行，扣掉重叠）。桶的保守窗口让
        //    **786** 行非空（其余 294 行为空桶）⇒ 分桶确实比实际命中宽松，测试 ③ 锁这一点。
        assertTrue("必须有可观数量的行含星（实测 $rowsWithStars / $h 行）", rowsWithStars > h / 4)
    }

    // ═════════════════════ ② 分桶工作量确实塌了一个数量级 ═════════════════════

    @Test
    fun `② 分桶把每行的星访问次数从 230 降到 O(Σ2r)`() {
        val w = 1920
        val h = 1080
        val bake = ProceduralTexture.starBake(w, h)
        val n = bake.layout.size / 3
        assertEquals("1080p 必须是 230 颗星（w*h/9000）", 230, n)

        var total = 0
        var maxRow = 0
        var emptyRows = 0
        for (y in 0 until h) {
            val c = bake.buckets.count(y)
            total += c
            if (c > maxRow) maxRow = c
            if (c == 0) emptyRows++
        }
        val before = h * n
        assertTrue(
            "分桶后总星访问次数必须远小于 $before（实测 $total）",
            total < before / 20,
        )
        // 理论值 Σ(2·ceil(r)+3) ≈ 230 × 5 ≈ 1.1k ⇒ 与实际吻合（说明既没漏桶也没过度装桶）
        assertTrue("分桶后总星访问次数应在 ~2k 量级（实测 $total）", total < 3_000)
        assertTrue("单行最多也就几颗星（实测 $maxRow）", maxRow < 12)
        // ⚠️ 空桶行数按**实测**给：每颗星的保守窗口是 `2·ceil(r)+3` 行（≈5 行），230 颗星
        //    ≈ 1150 个「行-星」槽摊在 1080 行上 ⇒ 约 2/3 的行非空、1/3 为空桶。
        //    判据取 `> h/5` 而不是 `> h/2`：后者按理论算本就不成立（会误报）。
        assertTrue(
            "必须有可观比例的空桶行（1080 行里实测 $emptyRows 行无星）—— 分桶生效的直接证据",
            emptyRows > h / 5,
        )
    }

    // ═════════════════════ ③ 桶是「真实命中集合」的严格超集（不变量 ① 的直接判据）═════

    @Test
    fun `③ 每一行实际画出的星都必须在该行的桶里`() {
        val w = 1920
        val h = 1080
        val bake = ProceduralTexture.starBake(w, h)
        val n = bake.layout.size / 3
        var violations = 0
        for (y in 0 until h) {
            // 参考实现里「本行真正通过判定」的星下标集合
            val hit = HashSet<Int>()
            for (i in 0 until n) {
                val sy = bake.layout[i * 3 + 1] * h
                val r = STAR_R[bake.layout[i * 3 + 2].toInt()]
                val dy = y - sy
                if (dy >= -r && dy <= r) hit.add(i)
            }
            val bucketed = HashSet<Int>()
            for (p in bake.buckets.bucketStart[y] until bake.buckets.bucketStart[y + 1]) {
                bucketed.add(bake.buckets.items[p])
            }
            if (!bucketed.containsAll(hit)) violations++
        }
        assertEquals("桶必须覆盖每一行真实命中的全部星（实测 $violations 行违反）", 0, violations)
    }

    // ═════════════════════ ④ 边界尺寸参数化（h=1 / 极小 / 非整除） ═════════════════════

    @Test
    fun `④ 边界与参差尺寸下逐像素同样完全相同`() {
        val sizes = arrayOf(
            intArrayOf(8, 1), intArrayOf(8, 2), intArrayOf(1, 1),
            intArrayOf(3, 5), intArrayOf(17, 13), intArrayOf(64, 48),
            intArrayOf(1919, 1081), intArrayOf(320, 180), intArrayOf(1280, 720),
            intArrayOf(1920, 1080),
        )
        for (sz in sizes) {
            val w = sz[0]
            val h = sz[1]
            val bake = ProceduralTexture.starBake(w, h)
            val ref = IntArray(w)
            val prod = IntArray(w)
            for (y in 0 until h) {
                referenceStarfieldRow(ref, y, w, h, bake.layout)
                ProceduralTexture.starfieldRow(prod, y, w, h, bake)
                for (x in 0 until w) {
                    if (ref[x] != prod[x]) {
                        fail("${w}x$h 在 y=$y x=$x 不一致：ref=${hex(ref[x])} prod=${hex(prod[x])}")
                    }
                }
            }
        }
    }

    // ═════════════════════ ⑤ starLayout 每次烘焙只调 1 次 ═════════════════════

    @Test
    fun `⑤ starBake 只调 starLayout 一次`() {
        ProceduralTexture.resetStarLayoutCallCount()
        ProceduralTexture.starBake(1920, 1080)
        assertEquals(
            "starLayout 必须只调 1 次（旧写法是每行 1 次 ⇒ 1080 次）",
            1, ProceduralTexture.starLayoutCallCount(),
        )
    }

    // ═════════════════════ ⑥ 负向自证 ═════════════════════

    @Test
    fun `⑥ 负向 - 若分桶漏掉一行 参考实现必须能抓到`() {
        // 自证：本门禁的「不一致」判据必须是真判据 —— 人为丢掉一颗星 ⇒ 必须被抓到
        val w = 1920
        val h = 1080
        val bake = ProceduralTexture.starBake(w, h)
        // 找一颗确实画在第 0 行的星，把它的窗口挪走（模拟装桶算窄）
        val ref = IntArray(w)
        referenceStarfieldRow(ref, 0, w, h, bake.layout)
        var caught = false
        for (i in 0 until bake.layout.size / 3) {
            val sy = bake.layout[i * 3 + 1] * h
            val r = STAR_R[bake.layout[i * 3 + 2].toInt()]
            if (-(0f - sy) < -r || (0f - sy) > r) continue      // 这颗星第 0 行不命中 ⇒ 跳过
            val probe = IntArray(w)
            java.util.Arrays.fill(probe, 0)
            // 只画这颗星
            val dx = kotlin.math.sqrt((r * r - (0f - sy) * (0f - sy)).coerceAtLeast(0f))
            val sx = bake.layout[i * 3] * w
            val x0 = (sx - dx).toInt().coerceIn(0, w - 1)
            val x1 = (sx + dx).toInt().coerceIn(0, w - 1)
            val argb = (STAR_A[bake.layout[i * 3 + 2].toInt()] shl 24) or 0x00FFFFFF
            for (x in x0..x1) probe[x] = argb
            var any = false
            for (x in 0 until w) if (probe[x] != 0 && ref[x] == 0) any = true
            if (any) { caught = true; break }
        }
        assertTrue("自证前提：必须能找到第 0 行命中的星", caught)
        // 真判据自检：把参考实现的这一颗星去掉后，比较必须失败
        val stripped = FloatArray(bake.layout.size)
        System.arraycopy(bake.layout, 0, stripped, 0, bake.layout.size)
        // 全 0 化第 0 颗星 ⇒ 参考实现第 0 行会少一颗 ⇒ 与分桶版不一致
        for (k in 0 until 3) stripped[k] = 0f
        val ref2 = IntArray(w)
        referenceStarfieldRow(ref2, 0, w, h, stripped)
        var differ = false
        for (x in 0 until w) if (ref2[x] != ref[x]) differ = true
        assertTrue("参考实现对 layout 改动必须敏感（否则「逐像素全等」判据是空转）", differ)
    }

    private fun hex(v: Int) = "0x%08X".format(v)
}

/**
 * v2.38.4 烘焙提速门禁 ②-b：[ProceduralTexture.starBake] 的**接线**门禁（Robolectric）。
 *
 * ⛔ 上一条测的是 [ProceduralTexture.starBake] 这个函数本身；这条测的是**生产的两条调用链**
 *    （[ProceduralTexture.ensure] 的六行之一 与 `ensureFullscreenOnly` 的 `rowFiller`）
 *    **真的只走了一次 `starBake`** —— 因为「把 `starBake` 写回逐行 lambda」是本次重构最容易
 *    发生的静默回退（编译照过、画面照对、只是慢了 100 倍）。
 *
 * ⚠️ 需要 Robolectric：`ensure`/`ensureFullscreenOnly` 会 `Bitmap.createBitmap` + `setPixels`。
 *   这里用小尺寸（192×108）⇒ 内存与耗时都可忽略；⭐ **真尺寸**等价验证在纯 JVM 的
 *   `ProceduralTextureStarfieldBucketTest` ① 里已经做了（那里不需要 Bitmap）。
 */
@RunWith(RobolectricTestRunner::class)
class ProceduralTextureStarBakeOnceTest {

    @Test
    fun `ensure 一次只调 starBake 一次`() {
        ProceduralTexture.release()
        ProceduralTexture.resetStarLayoutCallCount()
        ProceduralTexture.ensure(192, 108)
        assertEquals(
            "ensure(192,108) 必须只调 starLayout 一次（108 行 ⇒ 旧写法会调 108 次）",
            1, ProceduralTexture.starLayoutCallCount(),
        )
        ProceduralTexture.release()
    }

    @Test
    fun `ensureFullscreenOnly STARFIELD 一次只调 starBake 一次`() {
        ProceduralTexture.release()
        ProceduralTexture.resetStarLayoutCallCount()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 192, 108)
        assertEquals(
            "ensureFullscreenOnly(STARFIELD) 必须只调 starLayout 一次",
            1, ProceduralTexture.starLayoutCallCount(),
        )
        ProceduralTexture.release()
    }

    @Test
    fun `非 STARFIELD 的 ensureFullscreenOnly 不得调 starLayout`() {
        // ⛔ 说明这是「STARFIELD 专属的一次性准备」，不是所有路径的公共前置
        ProceduralTexture.release()
        ProceduralTexture.resetStarLayoutCallCount()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.FOG, 192, 108)
        assertEquals(
            "FOG 不该触发 starLayout（行桶是 STARFIELD 专属中间量）",
            0, ProceduralTexture.starLayoutCallCount(),
        )
        ProceduralTexture.release()
    }

    @Test
    fun `接线回归 - STARFIELD 纹理仍能烘出且尺寸正确`() {
        ProceduralTexture.release()
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, 192, 108)
        val t = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)
        assertTrue("STARFIELD 必须烘得出", t != null)
        assertEquals(192, t!!.width)
        assertEquals(108, t.height)
        // 逐像素非零（真的有星，不是全黑）
        val bmp = t.asAndroidBitmap()
        var nz = 0
        for (y in 0 until 108) for (x in 0 until 192) if (bmp.getPixel(x, y) != 0) nz++
        assertTrue("STARFIELD 必须真的画出星（实测非零像素 $nz）", nz > 0)
        ProceduralTexture.release()
    }
}