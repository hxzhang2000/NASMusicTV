package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界陆地轮廓数据的一致性校验。
 *
 * [WorldMapData] 的编码串是离线烘焙进来的常量，[decodeWorld] 又把一维字符串展成多组
 * 扁平数组 —— 这两处任何一处写错（分块丢字符、环/点下标错位、旁路表对不上）都会在
 * draw 期变成越界崩溃或大陆画到南极去。按项目「清单错误由单测拦截」的惯例，全部拦在
 * 这里。
 */
class WorldMapDataTest {

    private companion object {
        /** 编码字母表：64 字符，顺序必须与解码表严格一致。 */
        const val ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"
        const val SEPARATOR = '/'

        val LEVELS = intArrayOf(
            WorldMapData.LOD_COARSE,
            WorldMapData.LOD_MEDIUM,
            WorldMapData.LOD_FINE,
        )
    }

    // ------------------------------------------------------------ 结构不变式 ---

    @Test
    fun `each lod decodes into a non-empty landmass`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            assertTrue("LOD$lod: 环数应 > 0", m.ringCount > 0)
            assertTrue("LOD$lod: 点数应 > 0", m.pointCount > 0)
        }
    }

    @Test
    fun `ring count is a plausible world outline, not one collapsed blob`() {
        // 守卫性断言：分块拼接若丢掉 `/` 分隔符，整档会退化成 1 个巨型环，
        // 其余不变式（点数、坐标范围、tokenCount）仍会全部通过 —— 只有环数能抓到。
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            assertTrue(
                "LOD$lod: 只解出 ${m.ringCount} 个环，疑似分隔符丢失导致整档塌成一块",
                m.ringCount > 50,
            )
            assertTrue(
                "LOD$lod: 环数 ${m.ringCount} 超过陆地上限 400",
                m.ringCount <= 400,
            )
        }
    }

    @Test
    fun `splitting the source on the separator yields exactly ringCount segments`() {
        for (lod in LEVELS) {
            val segments = WorldMapData.source(lod).split(SEPARATOR)
            assertEquals(
                "LOD$lod: 按 '/' 切出的段数应等于解出的环数",
                decodeWorld(lod).ringCount,
                segments.size,
            )
            for ((i, seg) in segments.withIndex()) {
                assertTrue("LOD$lod 段$i: 为空段", seg.isNotEmpty())
                assertEquals("LOD$lod 段$i: 字符数应为 4 的倍数", 0, seg.length % 4)
                assertTrue("LOD$lod 段$i: 至少 3 点（12 字符）", seg.length >= 12)
            }
        }
    }

    @Test
    fun `index arrays are parallel and sized by ringCount`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            assertEquals("LOD$lod: ringStart 长度", m.ringCount, m.ringStart.size)
            assertEquals("LOD$lod: ringLength 长度", m.ringCount, m.ringLength.size)
            assertEquals("LOD$lod: lon 长度", m.pointCount, m.lon.size)
            assertEquals("LOD$lod: lat 长度", m.pointCount, m.lat.size)
        }
    }

    @Test
    fun `every ring has at least three points`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            for (r in 0 until m.ringCount) {
                assertTrue(
                    "LOD$lod 环$r: 点数 ${m.ringLength[r]} < 3，画不成环",
                    m.ringLength[r] >= 3,
                )
            }
        }
    }

    @Test
    fun `ring starts are in range and never move backwards`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            assertEquals("LOD$lod: 首环应从 0 号点开始", 0, m.ringStart[0])
            var previous = 0
            for (r in 0 until m.ringCount) {
                val start = m.ringStart[r]
                assertTrue(
                    "LOD$lod 环$r: 起始下标 $start 应落在 0..${m.pointCount}",
                    start in 0..m.pointCount,
                )
                assertTrue(
                    "LOD$lod 环$r: 起始下标 $start 相对上一环 $previous 倒退了",
                    start >= previous,
                )
                previous = start
            }
        }
    }

    @Test
    fun `rings tile the point arrays without gaps or overlap`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            var cursor = 0
            var total = 0L
            for (r in 0 until m.ringCount) {
                assertEquals("LOD$lod 环$r: 环起点应紧接上一环", cursor, m.ringStart[r])
                cursor += m.ringLength[r]
                total += m.ringLength[r]
                assertTrue(
                    "LOD$lod 环$r: 环尾 ${m.ringStart[r] + m.ringLength[r]} " +
                        "越过了 pointCount=${m.pointCount}",
                    cursor <= m.pointCount,
                )
            }
            assertEquals("LOD$lod: 各环点数之和应等于 pointCount", m.pointCount.toLong(), total)
            assertEquals("LOD$lod: 末环应正好收在 pointCount", m.pointCount, cursor)
        }
    }

    @Test
    fun `every coordinate is finite and inside the valid degree range`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            for (k in 0 until m.pointCount) {
                val lon = m.lon[k]
                val lat = m.lat[k]
                assertTrue("LOD$lod 点$k: 经度 $lon 非有限值", !lon.isNaN() && !lon.isInfinite())
                assertTrue("LOD$lod 点$k: 纬度 $lat 非有限值", !lat.isNaN() && !lat.isInfinite())
                assertTrue("LOD$lod 点$k: 经度 $lon 越界", lon >= -180f && lon <= 180f)
                assertTrue("LOD$lod 点$k: 纬度 $lat 越界", lat >= -90f && lat <= 90f)
            }
        }
    }

    // ------------------------------------------------------------ 缓存 / LOD ---

    @Test
    fun `same lod returns the very same instance`() {
        for (lod in LEVELS) {
            val first = decodeWorld(lod)
            val second = decodeWorld(lod)
            assertSame("LOD$lod: 重复解码应命中缓存并返回同一实例", first, second)
        }
    }

    @Test
    fun `coarser lod never carries more points than the finer one`() {
        assertTrue(
            "LOD0(${decodeWorld(WorldMapData.LOD_COARSE).pointCount}) " +
                "点数应 <= LOD1(${decodeWorld(WorldMapData.LOD_MEDIUM).pointCount})",
            decodeWorld(WorldMapData.LOD_COARSE).pointCount <=
                decodeWorld(WorldMapData.LOD_MEDIUM).pointCount,
        )
        assertTrue(
            "LOD1(${decodeWorld(WorldMapData.LOD_MEDIUM).pointCount}) " +
                "点数应 <= LOD2(${decodeWorld(WorldMapData.LOD_FINE).pointCount})",
            decodeWorld(WorldMapData.LOD_MEDIUM).pointCount <=
                decodeWorld(WorldMapData.LOD_FINE).pointCount,
        )
    }

    @Test
    fun `out of range lod is clamped instead of throwing`() {
        for (lod in intArrayOf(-7, -1, WorldMapData.LOD_COUNT,
            WorldMapData.LOD_COUNT + 1, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val m = decodeWorld(lod)
            assertTrue("lod=$lod: 环数应 > 0", m.ringCount > 0)
            val level = lod.coerceIn(0, WorldMapData.LOD_COUNT - 1)
            assertSame("lod=$lod 应落到 LOD$level", decodeWorld(level), m)
        }
    }

    @Test
    fun `source and tokenCount clamp out of range lod too`() {
        for (lod in intArrayOf(-3, 0, 2, 3, 42)) {
            val level = lod.coerceIn(0, WorldMapData.LOD_COUNT - 1)
            assertEquals(
                "lod=$lod 的编码串应等于 LOD$level 的",
                WorldMapData.source(level),
                WorldMapData.source(lod),
            )
            assertEquals(
                "lod=$lod 的点数应等于 LOD$level 的",
                WorldMapData.tokenCount(level),
                WorldMapData.tokenCount(lod),
            )
        }
    }

    // ------------------------------------------------------ 编码串 / 往返 ---

    @Test
    fun `encoded string only uses the alphabet plus the separator`() {
        val allowed = HashSet<Char>(ALPHABET.length + 1)
        ALPHABET.forEach { allowed.add(it) }
        allowed.add(SEPARATOR)
        for (lod in LEVELS) {
            val src = WorldMapData.source(lod)
            for (k in src.indices) {
                val c = src[k]
                assertTrue(
                    "LOD$lod 第 $k 个字符 '$c'（U+%04X）不在 64 字符表内".format(c.code),
                    allowed.contains(c),
                )
            }
        }
    }

    @Test
    fun `encoded string has no leading trailing or repeated separator`() {
        for (lod in LEVELS) {
            val src = WorldMapData.source(lod)
            assertTrue("LOD$lod: 首字符不应是分隔符", src[0] != SEPARATOR)
            assertTrue("LOD$lod: 末字符不应是分隔符", src[src.length - 1] != SEPARATOR)
            assertTrue("LOD$lod: 不应出现连续分隔符（空环）", !src.contains("//"))
        }
    }

    @Test
    fun `encoded string length matches the decoded ring and point counts`() {
        for (lod in LEVELS) {
            val src = WorldMapData.source(lod)
            val separators = src.count { it == SEPARATOR }
            val m = decodeWorld(lod)
            // 分隔符个数 == 环数 - 1：环之间 1 个，首尾无。
            assertEquals("LOD$lod: 分隔符数应等于环数 - 1", m.ringCount - 1, separators)
            // 数据字符数 = 总长 - 分隔符数，且必须是 4 的倍数（1 点 = 4 字符）。
            val dataChars = src.length - separators
            assertEquals("LOD$lod: 数据字符数应能被 4 整除", 0, dataChars % 4)
            assertEquals("LOD$lod: 点数字符数应等于点数 * 4", m.pointCount * 4, dataChars)
        }
    }

    @Test
    fun `tokenCount is exactly what the source decodes to`() {
        for (lod in LEVELS) {
            val src = WorldMapData.source(lod)
            val dataChars = src.length - src.count { it == SEPARATOR }
            assertEquals("LOD$lod: tokenCount 应等于非分隔符字符数 / 4",
                dataChars / 4, WorldMapData.tokenCount(lod))
            assertEquals("LOD$lod: tokenCount 应等于解码后的 pointCount",
                WorldMapData.tokenCount(lod), decodeWorld(lod).pointCount)
        }
    }

    @Test
    fun `the three lods are three distinct datasets`() {
        val sources = LEVELS.map { WorldMapData.source(it) }
        assertEquals("三档编码串应互不相同", sources.size, sources.toSet().size)
    }

    // --------------------------------------------------- 数据规模（海岸线版） ---

    @Test
    fun `coastline data scale matches the generated ne_110m_land numbers`() {
        // 与 logs_temp/world_map_gen/coast_world_stats.txt 一致：
        // LOD0/1/2 环数 122/125/127、点数 1504/2798/4169、
        // 编码串字符数（含 '/'）6137/11316/16802。
        val ringCounts = intArrayOf(122, 125, 127)
        val pointCounts = intArrayOf(1504, 2798, 4169)
        val sourceChars = intArrayOf(6137, 11316, 16802)
        for (lod in LEVELS) {
            assertEquals("LOD$lod: 环数", ringCounts[lod], decodeWorld(lod).ringCount)
            assertEquals("LOD$lod: 点数", pointCounts[lod], decodeWorld(lod).pointCount)
            assertEquals("LOD$lod: 点数应等于 tokenCount",
                pointCounts[lod], WorldMapData.tokenCount(lod))
            assertEquals("LOD$lod: 编码串字符数", sourceChars[lod], WorldMapData.source(lod).length)
        }
        assertEquals("三档编码串总字符数（含分隔符）", 34255,
            (0 until WorldMapData.LOD_COUNT).sumOf { WorldMapData.source(it).length })
    }

    // ------------------------------------------------------------ 几何合理性 ---

    @Test
    fun `rings are not collapsed to a point and stay near the landmasses`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            for (r in 0 until m.ringCount) {
                val from = m.ringStart[r]
                val to = from + m.ringLength[r]
                var minLon = Float.MAX_VALUE
                var maxLon = -Float.MAX_VALUE
                var minLat = Float.MAX_VALUE
                var maxLat = -Float.MAX_VALUE
                for (k in from until to) {
                    if (m.lon[k] < minLon) minLon = m.lon[k]
                    if (m.lon[k] > maxLon) maxLon = m.lon[k]
                    if (m.lat[k] < minLat) minLat = m.lat[k]
                    if (m.lat[k] > maxLat) maxLat = m.lat[k]
                }
                val diag = kotlin.math.hypot(
                    (maxLon - minLon).toDouble(), (maxLat - minLat).toDouble()
                )
                assertTrue(
                    "LOD$lod 环$r: 包围盒对角线 $diag° 过小，应是退化环",
                    diag > 0.01,
                )
            }
        }
    }

    @Test
    fun `no simplification segment jumps across the antimeridian`() {
        for (lod in LEVELS) {
            val m = decodeWorld(lod)
            for (r in 0 until m.ringCount) {
                val from = m.ringStart[r]
                val to = from + m.ringLength[r]
                for (k in from until to - 1) {
                    val dLon = kotlin.math.abs(m.lon[k + 1] - m.lon[k])
                    if (dLon > 180f) {
                        // Natural Earth 已按 ±180° 切开；唯一例外是南极极点，
                        // 那里 [180,-90] 与 [-180,-90] 是同一个点，经度退化。
                        assertTrue(
                            "LOD$lod 环$r 点$k: 跨了 $dLon° 的日期变更线，" +
                                "且不在极点上",
                            kotlin.math.abs(m.lat[k]) == 90f ||
                                kotlin.math.abs(m.lat[k + 1]) == 90f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `landmasses cover both hemispheres and both poles`() {
        val m = decodeWorld(WorldMapData.LOD_FINE)
        var minLon = Float.MAX_VALUE
        var maxLon = -Float.MAX_VALUE
        var minLat = Float.MAX_VALUE
        var maxLat = -Float.MAX_VALUE
        for (k in 0 until m.pointCount) {
            if (m.lon[k] < minLon) minLon = m.lon[k]
            if (m.lon[k] > maxLon) maxLon = m.lon[k]
            if (m.lat[k] < minLat) minLat = m.lat[k]
            if (m.lat[k] > maxLat) maxLat = m.lat[k]
        }
        assertTrue("最西经 $minLon 应 <= -150", minLon <= -150f)
        assertTrue("最东经 $maxLon 应 >= 150", maxLon >= 150f)
        assertTrue("最南纬 $minLat 应 <= -80（南极洲）", minLat <= -80f)
        assertTrue("最北纬 $maxLat 应 >= 80（北冰洋沿岸）", maxLat >= 80f)
    }
}
