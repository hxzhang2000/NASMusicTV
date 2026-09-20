package com.nasmusic.tv.data.prefs

import com.google.gson.Gson
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份容错反序列化护栏（v2.36.2）。
 *
 * 覆盖真机故障：用户**以前保存的备份文件导入失败**，且导入后界面毫无提示。
 * 备份 JSON 里 `appSettings.visualizerTheme` 存的是历史枚举名 `CLASSICAL_WAVE`，
 * 该常量早已从 [VisualizerTheme]（现 34 项）中删除。
 *
 * 根因链（本文件第 1 个用例就是它的**负向自证**，前提不成立时会立刻红）：
 * 1. Gson 2.10.1 的 `EnumTypeAdapter#read` 在名字找不到时**返回 null 而不是抛异常**：
 *    `return (constant == null) ? stringToConstant.get(key) : constant;`
 * 2. Gson 用反射把 null 写进字段，**绕过 Kotlin 的非空检查**
 *    → 声明为非空的 `AppSettings.visualizerTheme` 变成 null
 * 3. `AppPreferences.importBackupData()` 里 `settings.visualizerTheme.name` 抛 NPE
 *    → `dataStore.edit {}` 事务回滚 → 整份备份导入失败
 * 4. `VisualizerTheme.LEGACY_MAP` 只服务 `fromKey()`（DataStore 那条路），
 *    **覆盖不到 Gson 反序列化这条路** —— 这就是「老备份导入失败」的由来。
 *
 * 修复 = [backupGson]（备份专用的容错 Gson，见 BackupGson.kt）。
 *
 * ⚠️ 护栏一律用 `assertTrue/assertEquals`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 */
class BackupGsonTest {

    /** 老备份里 `visualizerTheme` 存的是已删除的历史枚举名 */
    private val legacySettingsJson = """{"visualizerTheme":"CLASSICAL_WAVE"}"""

    // ── ① 负向自证：先证明「默认 Gson 确实会坏」，否则本测试的前提不成立 ──────────

    @Test
    fun `default Gson turns a legacy enum name into null (root cause self-proof)`() {
        val viaDefault = Gson().fromJson(legacySettingsJson, AppSettings::class.java)
        // 先落到可空局部变量，避免「非空类型 == null」的恒假告警
        val theme: VisualizerTheme? = viaDefault.visualizerTheme
        assertTrue(
            "默认 Gson 的枚举适配器在名字找不到时返回 null（不抛异常），" +
                "再经反射绕过 Kotlin 非空检查写进字段 —— 若这里不是 null，说明根因判断有误",
            theme == null
        )
    }

    // ── ② 容错 Gson：历史枚举名必须迁移，绝不能是 null ─────────────────────────

    @Test
    fun `backupGson migrates a legacy enum name to the mapped theme`() {
        val settings = backupGson.fromJson(legacySettingsJson, AppSettings::class.java)
        // LEGACY_MAP: "CLASSICAL_WAVE" -> CIRCULAR_RING（与 fromKey 的迁移结果一致）
        assertEquals(VisualizerTheme.CIRCULAR_RING, settings.visualizerTheme)
        // 导入时真正会执行的那一步：修复前正是在这里抛 NPE
        assertEquals("CIRCULAR_RING", settings.visualizerTheme.name)
    }

    /**
     * 第 ② 级回落：未知名字 + 枚举**自带**兼容映射。
     *
     * `VisualizerTheme.fromKey()` 未命中时返回 `Default`（= `CIRCULAR_RING`）而**不是** null，
     * 所以本枚举永远在第 ② 级就返回了，**到不了**第 ③ 级「首个常量」。
     * 断言必须写 `Default`，写 `entries.first()`（= `IMMERSIVE_BLOOM`）会假红。
     */
    @Test
    fun `an unknown theme name falls back to the theme default, never null`() {
        val settings = backupGson.fromJson(
            """{"visualizerTheme":"NO_SUCH_THEME_AT_ALL"}""",
            AppSettings::class.java
        )
        val theme: VisualizerTheme? = settings.visualizerTheme
        assertNotNull("容错适配器绝不允许把非空枚举字段写成 null", theme)
        assertEquals(VisualizerTheme.Default, theme)
        assertEquals(VisualizerTheme.CIRCULAR_RING, theme)
    }

    /**
     * 第 ③ 级回落：未知名字 + `fromKey()` **返回 null** 的枚举。
     *
     * `NetworkSource.fromKey()` 未命中返回 null（它按 `key` 小写匹配，不认枚举名），
     * 因此会穿过第 ② 级、落到第 ③ 级「首个常量」。
     */
    @Test
    fun `an unknown network source falls back to the first constant, never null`() {
        val settings = backupGson.fromJson(
            """{"defaultNetworkSource":"NO_SUCH_SOURCE"}""",
            AppSettings::class.java
        )
        val source: NetworkSource? = settings.defaultNetworkSource
        assertNotNull("容错适配器绝不允许把非空枚举字段写成 null", source)
        assertEquals(NetworkSource.entries.first(), source)
        assertEquals(NetworkSource.METING, source)
    }

    /**
     * 第 ③ 级回落：**没有任何兼容映射**的枚举（`resolveLegacyEnumName` 走 `else -> null`）。
     *
     * `PlayMode` 只按 ordinal 持久化，没有 `fromKey()`，是纯粹的第 ③ 级路径。
     */
    @Test
    fun `an unknown play mode falls back to the first constant, never null`() {
        val settings = backupGson.fromJson(
            """{"defaultPlayMode":"NO_SUCH_MODE"}""",
            AppSettings::class.java
        )
        val mode: PlayMode? = settings.defaultPlayMode
        assertNotNull("容错适配器绝不允许把非空枚举字段写成 null", mode)
        assertEquals(PlayMode.entries.first(), mode)
        assertEquals(PlayMode.SEQUENTIAL, mode)
    }

    @Test
    fun `all current enum names round-trip unchanged`() {
        VisualizerTheme.entries.forEach { theme ->
            val settings = backupGson.fromJson(
                """{"visualizerTheme":"${theme.name}"}""",
                AppSettings::class.java
            )
            assertEquals(theme, settings.visualizerTheme)
        }
    }

    // ── ③ 老备份缺字段：保留 Kotlin 默认值（Gson 走无参构造）───────────────────

    @Test
    fun `a field missing from an old backup keeps its Kotlin default`() {
        // 老备份里根本没有 visualizerQuality 这个键（已核对真机样例文件）
        val settings = backupGson.fromJson(legacySettingsJson, AppSettings::class.java)
        assertEquals(VisualQuality.MEDIUM, settings.visualizerQuality)
        assertEquals(VisualQuality.Default, settings.visualizerQuality)
    }

    // ── ④ 导出方向不受影响：写出的仍是 enum.name ────────────────────────────────

    @Test
    fun `export still writes the plain enum constant name`() {
        val json = backupGson.toJson(
            AppSettings(visualizerTheme = VisualizerTheme.TUNNEL_FLY)
        )
        assertTrue(
            "容错适配器的 write 必须与 Gson 默认行为一致（写出 enum.name），json=$json",
            json.contains("\"visualizerTheme\":\"TUNNEL_FLY\"")
        )
    }

    // ── ⑤ 端到端：整份 BackupData 反序列化（真机故障的完整路径）─────────────────

    @Test
    fun `a whole legacy backup deserializes with a non-null visualizerTheme`() {
        val json = """{"version":1,"exportedAt":1758380343000,"appSettings":$legacySettingsJson}"""
        val data = backupGson.fromJson(json, AppPreferences.BackupData::class.java)

        val settings = data.appSettings
        assertNotNull("appSettings 必须被解析出来", settings)
        assertNotNull("visualizerTheme 必须非空 —— 原故障就是它变成 null", settings!!.visualizerTheme)
        assertEquals(VisualizerTheme.CIRCULAR_RING, settings.visualizerTheme)
    }
}
