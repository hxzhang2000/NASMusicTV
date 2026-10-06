package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地音乐总开关持久化测试（G2，权限与签名统一方案 §7.2）。
 *
 * `localMusicEnabled` 是 B 线的中枢偏好：默认 `true`（D1 —— 全新装机首启不弹权限框、
 * 但升级用户保持原有曲库行为），开关驱动权限申请与扫描运行时。
 * 本测试锁住它的持久化契约：默认值 / 读写往返 / Gson 默认构造 / 老备份缺键容错 / 备份往返。
 *
 * 照 [PhotoWallPrefsTest] 的既有写法：
 * ① `settle() = delay(30)` —— datastore 1.0.0 在 Windows + Robolectric 下连续快速写
 *    同名文件存在 rename 竞态，写之间让出句柄；
 * ② `@RunWith(RobolectricTestRunner::class) @Config(sdk = [34])` —— 本机只缓存了
 *    SDK 34/30 两个 android-all jar。
 *
 * ## 断言口径（方案 §7.2）
 *
 * | # | 内容 |
 * |---|---|
 * | L1 | 默认 `localMusicEnabled == true`（D1，零回归的断言化） |
 * | L2 | 写 `false` → 读回 `false`；再写 `true` → 读回 `true` |
 * | L3 | `AppSettings().localMusicEnabled == true`（新对象默认值，防 Gson 无参构造消失） |
 * | L4 | legacy JSON（缺该键）导入后仍为 `true` 且不抛异常 |
 * | L5 | `importBackupData` + `exportBackupData` 往返后值不变 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMusicPrefsTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
    }

    private suspend fun settle() = delay(30)

    private suspend fun settings(): AppSettings = prefs.appSettings.first()

    // ── ① L1：默认值 ────────────────────────────────────────────────────────

    @Test
    fun `localMusicEnabled 默认为 true`() = runBlocking {
        assertTrue(
            "默认值必须是 true（D1）—— 改成 false 会让全新装机首启曲库为空、" +
                "升级用户的本地/下载曲全部消失",
            settings().localMusicEnabled,
        )
    }

    // ── ② L2：读写往返 ──────────────────────────────────────────────────────

    @Test
    fun `localMusicEnabled 写读往返一致`() = runBlocking {
        prefs.setLocalMusicEnabled(false); settle()
        assertFalse("写 false 后读回应为 false", settings().localMusicEnabled)

        prefs.setLocalMusicEnabled(true); settle()
        assertTrue("写 true 后读回应为 true", settings().localMusicEnabled)
    }

    // ── ③ L3：AppSettings 新对象默认值 ──────────────────────────────────────

    @Test
    fun `AppSettings 无参构造的 localMusicEnabled 为 true`() {
        // 防备份反序列化路径的 Gson 无参构造消失后字段回落 false（§10.172 同款坑）
        assertTrue(AppSettings().localMusicEnabled)
    }

    // ── ④ L4：老备份缺键容错 ────────────────────────────────────────────────

    @Test
    fun `导入缺键的 legacy 备份不抛异常且保持默认 true`() = runBlocking {
        // v2.38.3 之前导出的备份没有 local_music_enabled 键
        val legacyJson = """{"visualizerTheme":"CLASSICAL_WAVE"}"""
        val legacy = backupGson.fromJson(legacyJson, AppSettings::class.java)

        // 不抛异常就是通过（缺键 ⇒ Gson 走无参构造默认值）
        prefs.importBackupData(AppPreferences.BackupData(appSettings = legacy))
        settle()

        assertTrue(
            "老备份缺键 ⇒ 应回落默认 true（D1），且升级用户开关状态不被误关",
            settings().localMusicEnabled,
        )
    }

    // ── ⑤ L5：备份往返 ──────────────────────────────────────────────────────

    @Test
    fun `备份导出导入往返后 localMusicEnabled 不变`() = runBlocking {
        prefs.setLocalMusicEnabled(false); settle()
        val exported = prefs.exportBackupData()

        // 前置自证：翻转当前值，证明导入确实恢复了导出时的快照
        prefs.setLocalMusicEnabled(true); settle()
        assertTrue(settings().localMusicEnabled)

        prefs.importBackupData(exported); settle()
        assertFalse(
            "export → import 往返后应恢复导出时的 false —— 丢失该字段会让用户换机/恢复后" +
                "本地音乐源静默重新开启并触发权限申请",
            settings().localMusicEnabled,
        )
    }
}
