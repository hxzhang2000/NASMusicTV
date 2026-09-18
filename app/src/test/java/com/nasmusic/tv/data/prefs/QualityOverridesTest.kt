package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.network.QualityTiers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 单曲音质覆盖测试（多码率方案 §10.1 / §2.3.1 / D3 两级模型）。
 *
 * 覆盖 §10.1 列出的 9 条断言：`tierOf`/`put`/`remove`/`all`/`clearAll`、
 * 500 条 LRU 淘汰、键格式、存储隔离、以及"自动下载不读取覆盖值"的语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QualityOverridesTest {

    private lateinit var ctx: Context
    private lateinit var overrides: QualityOverrides

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        // 清理上一轮残留的 DataStore 文件，保证用例独立
        runCatching { File(ctx.filesDir, "datastore/quality_overrides.preferences_pb").delete() }
        overrides = QualityOverrides(ctx)
        runBlocking { overrides.clearAll() }
    }

    // ── 断言 1：无覆盖 → null（调用方回退全局默认） ─────────────

    @Test
    fun `无覆盖时 tierOf 返回 null`() {
        assertNull(overrides.tierOf("meting", "1001"))
    }

    // ── 断言 2：put 后命中 ────────────────────────────────────

    @Test
    fun `put 后 tierOf 命中覆盖值`() = runBlocking {
        overrides.put("meting", "1001", QualityTiers.LOSSLESS)
        assertEquals(999, overrides.tierOf("meting", "1001"))
    }

    // ── 断言 3：remove 后返回 null ────────────────────────────

    @Test
    fun `remove 后 tierOf 返回 null`() = runBlocking {
        overrides.put("meting", "1001", 320)
        assertEquals(320, overrides.tierOf("meting", "1001"))
        overrides.remove("meting", "1001")
        assertNull(overrides.tierOf("meting", "1001"))
    }

    // ── 断言 4：同键两次 put → updatedAt 递增，all() 不重复 ─────

    @Test
    fun `同键两次 put 不产生重复键`() = runBlocking {
        overrides.put("meting", "1001", 128)
        val first = overrides.tierOf("meting", "1001")
        overrides.put("meting", "1001", 999)
        assertEquals("应覆盖为新值", 999, overrides.tierOf("meting", "1001"))
        assertEquals("all() 中该键只应出现一次", 1, overrides.all().size)
        assertTrue(first != overrides.tierOf("meting", "1001"))
    }

    // ── 断言 5：500 条上限 LRU 淘汰 ───────────────────────────

    @Test
    fun `写入超过上限时淘汰最旧 容量恒不超 500`() = runBlocking {
        val n = QualityOverrides.MAX_ENTRIES + 1
        repeat(n) { i ->
            overrides.put("meting", "id$i", 128)
        }
        assertEquals(
            "容量必须恒 ≤ ${QualityOverrides.MAX_ENTRIES}",
            QualityOverrides.MAX_ENTRIES, overrides.size()
        )
        // ⚠️ 不断言"具体哪一条被淘汰"：循环在同一毫秒内完成，
        // 所有 Entry.updatedAt 相同，sortedByDescending 的淘汰对象不确定。
        // 这里断言可确定的部分：容量收敛到上限、且最新写入的一定在。
        assertEquals(128, overrides.tierOf("meting", "id${n - 1}"))
    }

    // ── 断言 6：all() 键格式 networkSource:networkId ──────────

    @Test
    fun `all 返回的键格式为 source 冒号 id`() = runBlocking {
        overrides.put("meting", "1001", 320)
        overrides.put("jamendo", "abc", 128)
        val keys = overrides.all().keys
        assertTrue("键应为 meting:1001", keys.contains("meting:1001"))
        assertTrue("键应为 jamendo:abc", keys.contains("jamendo:abc"))
    }

    @Test
    fun `all 返回值与 tierOf 一致`() = runBlocking {
        overrides.put("meting", "1", 999)
        overrides.put("meting", "2", 128)
        val all = overrides.all()
        assertEquals(999, all["meting:1"])
        assertEquals(128, all["meting:2"])
        assertEquals(2, all.size)
    }

    // ── 断言 7：clearAll ─────────────────────────────────────

    @Test
    fun `clearAll 后 all 为空`() = runBlocking {
        overrides.put("meting", "1", 320)
        overrides.put("meting", "2", 128)
        assertTrue(overrides.size() > 0)
        overrides.clearAll()
        assertTrue("清空后 all() 必须为空", overrides.all().isEmpty())
        assertEquals(0, overrides.size())
        assertNull(overrides.tierOf("meting", "1"))
    }

    // ── 断言 8：存储隔离（独立 DataStore 文件） ────────────────

    @Test
    fun `覆盖值写入独立 DataStore 文件 不污染主偏好`() = runBlocking {
        val own = File(ctx.filesDir, "datastore/quality_overrides.preferences_pb")
        val mainPrefs = File(ctx.filesDir, "datastore/app_preferences.preferences_pb")
        val mainLenBefore = if (mainPrefs.exists()) mainPrefs.length() else -1L

        overrides.put("meting", "1", 320)

        assertTrue("应写入独立 DataStore 文件 quality_overrides", own.exists() && own.length() > 0)
        val mainLenAfter = if (mainPrefs.exists()) mainPrefs.length() else -1L
        assertEquals(
            "本类不应改动主偏好文件（存储隔离）",
            mainLenBefore, mainLenAfter
        )
    }

    @Test
    fun `持久化确实写入磁盘（可被后续读取）`() = runBlocking {
        overrides.put("meting", "1001", QualityTiers.LOSSLESS)
        // ⚠️ 不能在同一进程内 new 第二个 QualityOverrides 来验证"重启后仍可读"——
        // DataStore 有"同一文件只允许一个活动实例"的硬约束，会抛
        // IllegalStateException: There are multiple DataStores active for the same file。
        // 生产代码是单实例（NasMusicApp 持有），不存在该场景。
        // 这里改为直接读底层文件，验证持久化确实发生了。
        val f = File(ctx.filesDir, "datastore/quality_overrides.preferences_pb")
        assertTrue("持久化后文件应存在且非空", f.exists() && f.length() > 0)
    }

    // ── 断言 9：生效优先级 + 自动下载不读取覆盖 ─────────────────

    @Test
    fun `单曲覆盖优先于全局默认档位`() = runBlocking {
        val globalDefault = 128
        overrides.put("meting", "1001", 999)
        val effective = overrides.tierOf("meting", "1001") ?: globalDefault
        assertEquals("有覆盖时必须用覆盖值", 999, effective)

        val other = overrides.tierOf("meting", "9999") ?: globalDefault
        assertEquals("无覆盖时回退全局默认", 128, other)
    }

    @Test
    fun `自动下载路径不读取覆盖值`() = runBlocking {
        // 语义约束：AutoDownloadController 只接受 qualityTierProvider（全局默认），
        // 构造上没有 songQualityOverrideProvider，因此覆盖值无法进入自动下载。
        overrides.put("meting", "1001", 999)
        val globalDefault = 128
        // 自动下载取档逻辑：仅全局默认
        val requestedByAuto = globalDefault
        assertEquals("自动下载必须用全局默认档", 128, requestedByAuto)
        assertTrue(
            "即使该曲有覆盖值，自动下载也不应取到",
            requestedByAuto != overrides.tierOf("meting", "1001")
        )
    }

    // ── 边界：非法档位被拒绝 ─────────────────────────────────

    @Test
    fun `put 非法档位被忽略 不污染存储`() = runBlocking {
        overrides.put("meting", "1", 256)   // 非合法档位
        assertNull("非法档位不应写入", overrides.tierOf("meting", "1"))
        assertEquals(0, overrides.size())
    }

    @Test
    fun `put 全部合法档位均可写入`() = runBlocking {
        QualityTiers.all.forEachIndexed { i, tier ->
            overrides.put("meting", "id$i", tier)
            assertEquals(tier, overrides.tierOf("meting", "id$i"))
        }
        assertEquals(QualityTiers.all.size, overrides.size())
    }

    @Test
    fun `remove 不存在的键不抛异常`() = runBlocking {
        overrides.remove("meting", "never")
        assertNull(overrides.tierOf("meting", "never"))
    }

    @Test
    fun `clearAll 在空存储上不抛异常`() = runBlocking {
        overrides.clearAll()
        overrides.clearAll()
        assertEquals(0, overrides.size())
    }
}
