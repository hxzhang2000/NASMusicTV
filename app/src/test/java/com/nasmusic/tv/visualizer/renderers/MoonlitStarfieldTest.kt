package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §十三 **G11 星野哈希位序 + 无规则格点**（E44「明月」层 2）。
 *
 * ## 这条门禁原本要判什么（§3.0(f) 第 7 条 / §十六）
 * 原型是 JS，`hash1` 曾写成 `x | 0 ^ K` —— JS 里 `|` 优先级**高于** `^`，于是折成
 * `x | (0 ^ K)` = `x | K`：K 中为 1 的位被 OR 掉，**不同的 `x` 撞进同一个值**，
 * 星野当场长出一张规则点阵（"星星排成方阵"）。正确写法是 `(x | 0) ^ K`（定稿即此）。
 * ⛔ 这是那种"一眼看穿、但单测永远抓不到"的错 —— 所以本类的**一半篇幅**花在证明
 * 判据**真的有牙**（③④ 两条负向自证），而不是花在证明星野好看。
 *
 * ## ⚠️ 本轮的诚实降级（登记为偏差 **D20**）
 * §3.0(f) 第 7 条要求 Kotlin 侧位序**逐字对齐**
 * `Math.imul((x|0) ^ 0x9E3779B9, 0x85EBCA77)` → `^= h ushr 13` → `imul(h, 0xC2B2AE3D)`
 * → `^= h ushr 16` → `(h>>>0)/4294967296`。**但 E44 的星位并不由本效果自算哈希**：
 * 层 2 复用共享纹理 [ProceduralTexture.Id.STARFIELD]，星点来自
 * [ProceduralTexture.starLayout] 的 **LCG**（`st = st·1664525 + 1013904223`、
 * `lcgFloat = ((st shr 8) and 0xFFFFFF) / 2^24`、种子 `0x5EEDF00D`）—— 生产码里
 * **没有 murmur 位序可断言**。因此：
 * - **无格点**半边：判在**真实星位**上（①②，直接吃 [ProceduralTexture.starLayout]）。
 * - **位序**半边：降级为 (a) 测试内的**有牙夹具**（③：同一判据下坑写法必判红、
 *   正确写法判绿）+ (b) 一条**前进钩子**（⑤：一旦生产码出现该种子常量，位序必须走 `xor`）。
 * ⛔ 不假装断言"生产码位序已验证"，也不把这条门禁整条删掉 —— 删掉就等于把原型踩过的
 * 坑重新设为不可见。
 *
 * ## 阈值怎么来的（⛔ 不是拍的）
 * 用 node 探针（`logs_temp/g11_probe.js` / `logs_temp/g11_lcg.js`）在 960×540 / 1280×720 /
 * 1920×1080 / 3840×2160 四组画幅上实测真实星位：**`dup = 0`、`maxCol ≤ 3`、`maxRow ≤ 4`、
 * `distinct = n`**；而位序坑夹具是 `dup = 195`、`maxCol = maxRow = 22`、`distinct = 35`。
 * ⇒ 阈值取 **`dup ≤ 2` / `maxCol ≤ 6` / `maxRow ≤ 6` / `distinct == n`**：给真实值留一倍以上的
 * 余量（换画幅、改星密度都不会误红），同时与坑夹具差**一个数量级**（不会被推到）。
 *
 * ⚠️ 纯 JVM：[ProceduralTexture.starLayout] 与 [ProceduralTexture.starBake] 都是纯数组运算，
 * 不建 `Bitmap` ⇒ ⛔ 不挂 Robolectric（先例 `ProceduralTextureStarfieldBucketTest` 同口径）。
 */
class MoonlitStarfieldTest {

    // ═════════════════ ① 真实星位：四组画幅都不成格点 ═════════════════

    @Test
    fun `G11 ① 真实星位在四组画幅下都不成规则格点`() {
        for ((w, h) in RESOLUTIONS) {
            val s = gridStatsOfStarLayout(w, h)
            val m = "画幅 ${w}×${h}：n=${s.n} dup=${s.dup} maxCol=${s.maxCol} maxRow=${s.maxRow} distinct=${s.distinct}"
            assertTrue("${m} ⇒ 同一像素格里出现重复星点（位序/散点退化，$m）", s.dup <= MAX_DUP)
            assertTrue("${m} ⇒ 某一像素列挤了 ${s.maxCol} 颗星 = 竖向条纹（格点征兆）", s.maxCol <= MAX_COL)
            assertTrue("${m} ⇒ 某一像素行挤了 ${s.maxRow} 颗星 = 横向条纹（格点征兆）", s.maxRow <= MAX_ROW)
            assertTrue("${m} ⇒ distinct(${s.distinct}) != n(${s.n}) ⇒ 有星点重合在格点上", s.distinct == s.n)
        }
    }

    @Test
    fun `G11 ② 星数与坐标域按 w·h 9000 的生成规格`() {
        for ((w, h) in RESOLUTIONS) {
            val layout = ProceduralTexture.starLayout(w, h)
            // 与生产式**同式**（Float 乘除 + 截断 + 下限 3），⛔ 不用 Long 反推：
            //   两端算法一旦不同口径，这条会变成"测试自己造一个 n"。
            val expect = (w.toFloat() * h / 9000f).toInt().coerceAtLeast(3)
            assertTrue(
                "${w}×${h} 应产 $expect 颗星（实际 n=${layout.size / 3}）",
                layout.size == expect * 3,
            )
            // 归一化域与档位：越界会让星贴在画布外或掉进不存在的半径档
            var i = 0
            while (i < layout.size) {
                val x = layout[i]
                val y = layout[i + 1]
                val tier = layout[i + 2].toInt()
                assertTrue("星 $i 的 x=$x 不在 [0,1)（越界 ⇒ 贴边或被裁掉）", x >= 0f && x < 1f)
                assertTrue("星 $i 的 y=$y 不在 [0,1)", y >= 0f && y < 1f)
                assertTrue("星 $i 的档位=$tier 不在 {0,1,2}（STAR_R/STAR_A 只有三档）", tier in 0..2)
                i += 3
            }
        }
    }

    // ═════════════════ ③ 负向自证：位序坑必须被同一判据判红 ═════════════════

    @Test
    fun `G11 ③ 位序坑夹具判红 正确位序判绿（检测器有牙）`() {
        val good = gridStatsOfPoints(murmurPoints(badBitOrder = false))
        val bad = gridStatsOfPoints(murmurPoints(badBitOrder = true))
        assertTrue("正确位序 (x xor K) 却被判成格点 ⇒ 阈值太紧，会把真实星野误杀：$good", latticeFree(good))
        assertTrue(
            "位序坑 (x or K) 却**没有**被判红 ⇒ 本门禁是空转的（dup=${bad.dup} maxCol=${bad.maxCol} " +
                "maxRow=${bad.maxRow} distinct=${bad.distinct}/${bad.n}）",
            !latticeFree(bad),
        )
        // 量级差本身也要钉住：撞值后只剩 35 个不同格点（真实 230）⇒ 差一个数量级，不是临界
        assertTrue(
            "坑夹具的 distinct(${bad.distinct}) 必须远小于 n(${bad.n})，否则判据只是勉强擦红",
            bad.distinct * 4 < bad.n,
        )
    }

    // ═════════════════ ④ 负向自证：单度量不足，三度量必须并用 ═════════════════

    @Test
    fun `G11 ④ 纯方阵没有重复星点 只靠列 行载荷才抓得到`() {
        val lat = gridStatsOfPoints(pureLattice(23, 10))
        // 方阵是"最完美"的格点：每颗占自己的格子 ⇒ 重复点判据完全看不见它
        assertTrue("方阵夹具不该有重复点（本例要的正是这个盲区）：$lat", lat.dup == 0)
        assertTrue("方阵夹具不该有重合格点：$lat", lat.distinct == lat.n)
        assertTrue(
            "方阵夹具必须被列载荷判红（每列 ${lat.maxCol} 颗，阈值 $MAX_COL）⇒ 否则 maxCol 是摆设",
            lat.maxCol > MAX_COL,
        )
        assertTrue(
            "方阵夹具必须被行载荷判红（每行 ${lat.maxRow} 颗，阈值 $MAX_ROW）⇒ 否则 maxRow 是摆设",
            lat.maxRow > MAX_ROW,
        )
        assertTrue("方阵夹具整体必须被判为格点：$lat", !latticeFree(lat))
    }

    // ═════════════════ ⑤ 前进钩子：生产码一旦出现该哈希，位序必须走 xor ═════════════════

    /**
     * D20 的可执行部分。今天 E44 生产码里**没有** murmur 种子常量（星位借共享 LCG），
     * 所以本例对生产码判的是"空集成立"；一旦 T7/T8 给云播种或粼光引入自算哈希，
     * 这条就自动变成真判据 —— ⛔ 届时不要来删它，而要把它扩到新增文件上。
     *
     * ⚠️ 牙齿在 fixture 那半边：把坑写法 `x or K` 塞进**同一个行判据**必须判红。
     */
    @Test
    fun `G11 ⑤ 生产码若出现 murmur 种子 位序必须走 xor`() {
        val lines = productionSources().flatMap { stripComments(it).lines() }
        val hits = lines.filter { it.contains("9E3779B9", ignoreCase = true) }
        for (line in hits) {
            assertTrue("位序必须是 xor（JS 坑 `x or K` 会让不同输入撞值）：$line", isXorBitOrder(line))
        }
        // 夹具自证：同一条判据对坑写法判红
        assertTrue("判据认不出坑写法 ⇒ 空集通过是假绿", !isXorBitOrder("var h = x or 0x9E3779B9"))
        assertTrue("判据连正确写法都不认 ⇒ 同上", isXorBitOrder("var h = x xor 0x9E3779B9"))
    }

    // ── 判据 ────────────────────────────────────────────────────────────────────

    /** 像素格点统计（口径与 node 探针逐字一致：`floor(v·W)`、越界钳到最后一格）。 */
    private class Grid(
        val n: Int,
        val dup: Int,
        val maxCol: Int,
        val maxRow: Int,
        val distinct: Int,
    ) {
        override fun toString(): String =
            "Grid(n=$n dup=$dup maxCol=$maxCol maxRow=$maxRow distinct=$distinct)"
    }

    /** 无格点判据 —— ①③④ 共用**同一把尺**，⛔ 不许各条测试自带宽松版本。 */
    private fun latticeFree(s: Grid): Boolean =
        s.dup <= MAX_DUP && s.maxCol <= MAX_COL && s.maxRow <= MAX_ROW && s.distinct == s.n

    private fun gridStatsOfStarLayout(w: Int, h: Int): Grid {
        val layout = ProceduralTexture.starLayout(w, h)
        val n = layout.size / 3
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = layout[i * 3].toDouble()
            ys[i] = layout[i * 3 + 1].toDouble()
        }
        return gridStats(xs, ys, w, h)
    }

    private fun gridStatsOfPoints(pts: Array<DoubleArray>): Grid {
        val xs = DoubleArray(pts.size)
        val ys = DoubleArray(pts.size)
        for (i in pts.indices) {
            xs[i] = pts[i][0]
            ys[i] = pts[i][1]
        }
        return gridStats(xs, ys, 1920, 1080)
    }

    private fun gridStats(xs: DoubleArray, ys: DoubleArray, w: Int, h: Int): Grid {
        val cols = IntArray(w)
        val rows = IntArray(h)
        val seen = HashSet<Int>(xs.size * 2)
        var dup = 0
        var maxCol = 0
        var maxRow = 0
        for (i in xs.indices) {
            val px = minOf(w - 1, (xs[i] * w).toInt())
            val py = minOf(h - 1, (ys[i] * h).toInt())
            cols[px]++
            rows[py]++
            if (cols[px] > maxCol) maxCol = cols[px]
            if (rows[py] > maxRow) maxRow = rows[py]
            if (!seen.add(py * w + px)) dup++
        }
        return Grid(xs.size, dup, maxCol, maxRow, seen.size)
    }

    /**
     * §3.0(f) 第 7 条的那段哈希，两种位序各来一遍。
     *
     * ⚠️ `Int` 乘法即 `Math.imul`（同样按 2^32 回绕）、`ushr` 即 `>>>`
     * （等价性论证先例 `SeasideWaves.kt:224-225`）。常量写成 `Long.toInt()` 而不是十进制
     * 负数，是为了与文档/原型的十六进制**逐字可比**（生产码里则按 `SeasideWaves` 的口径
     * 写有符号十进制，见该文件 `hash2` 的注释）。
     */
    private fun murmurPoints(badBitOrder: Boolean): Array<DoubleArray> {
        val seed = 0x9E3779B9.toInt()
        val pts = Array(STAR_N) { DoubleArray(2) }
        for (i in 0 until STAR_N) {
            pts[i][0] = mix32(i * 3 + 1, seed, badBitOrder)
            pts[i][1] = mix32(i * 3 + 2, seed, badBitOrder)
        }
        return pts
    }

    private fun mix32(x: Int, seed: Int, badBitOrder: Boolean): Double {
        // 坑：`x | K`（JS 的 `x | 0 ^ K` 折叠后的实际形状）⇒ 高位被 OR 抹平、不同 x 撞值
        var h = if (badBitOrder) x or seed else x xor seed
        h = h * 0x85EBCA77.toInt()
        h = h xor (h ushr 13)
        h = h * 0xC2B2AE3D.toInt()
        h = h xor (h ushr 16)
        return (h.toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    /** 规则方阵：每颗占自己的格子 ⇒ `dup = 0`，但列/行载荷爆表（④ 的夹具）。 */
    private fun pureLattice(columns: Int, rowsPerCol: Int): Array<DoubleArray> {
        val pts = Array(columns * rowsPerCol) { DoubleArray(2) }
        var i = 0
        for (c in 0 until columns) {
            for (r in 0 until rowsPerCol) {
                pts[i][0] = (c + 0.5) / columns
                pts[i][1] = (r + 0.5) / rowsPerCol
                i++
            }
        }
        return pts
    }

    /** 一行里对 murmur 种子常量施加的运算是不是 `xor`（⛔ `or` 就是那个坑）。 */
    private fun isXorBitOrder(line: String): Boolean {
        val at = line.indexOf("9E3779B9", ignoreCase = true)
        if (at < 0) return true
        val before = line.substring(0, at)
        val xor = before.lastIndexOf("xor")
        val or = lastIndexOfOr(before)
        return xor > or
    }

    /** 找**独立**的 `or`（`for` / `floor` 里的字母 o-r 不算）。 */
    private fun lastIndexOfOr(before: String): Int {
        var i = before.lastIndexOf("or")
        while (i >= 0) {
            val leftOk = i == 0 || !before[i - 1].isLetterOrDigit()
            val rightOk = i + 2 >= before.length || !before[i + 2].isLetterOrDigit()
            if (leftOk && rightOk) return i
            i = before.lastIndexOf("or", i - 1)
        }
        return -1
    }

    // ── 源码扫描助手（与 MoonPhaseShadowTest 同口径）────────────────────────────

    /**
     * ⚠️ 一律跑在**剥掉注释**的源码上：本效果的类 KDoc 与红线说明里**原文引用**了
     * `0x9E3779B9` 这类写法（G11 要判的正是它），不剥注释就是自己判自己红。
     */
    private fun productionSources(): List<String> = listOf(
        "com/nasmusic/tv/visualizer/renderers/MoonlitRenderer.kt",
        "com/nasmusic/tv/visualizer/renderers/MoonSeascape.kt",
        "com/nasmusic/tv/visualizer/renderers/MoonDiskBake.kt",
    ).map { File(mainSourceRoot(), it).readText() }

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var blockDepth = 1
                while (i < src.length && blockDepth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { blockDepth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { blockDepth--; i += 2 }
                    else {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }

    private companion object {
        const val MAX_DUP = 2
        const val MAX_COL = 6
        const val MAX_ROW = 6
        val RESOLUTIONS = arrayOf(intArrayOf(960, 540), intArrayOf(1280, 720), intArrayOf(1920, 1080), intArrayOf(3840, 2160))

        /** 与 1920×1080 的真实星数同量级（夹具要和被检对象比得了，⛔ 别用 10 颗测"会不会撞"）。 */
        const val STAR_N = 230
    }
}
