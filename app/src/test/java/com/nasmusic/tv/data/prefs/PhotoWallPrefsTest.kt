package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 照片墙持久化层测试（§7.3 的 17 个字段 / §15.3 T8.2 的验收判据）。
 *
 * 覆盖三件事：
 * 1. **默认值**逐个对得上 §7.3 的表（尤其 `photoWallHoldMs = 8000` —— 用户 2026-09-23 确认）
 * 2. **读写往返一致**（17 个字段全部写非默认值再读回）
 * 3. **越界钳制**：转场 / 停留时长在写入侧就被夹到合法区间
 *
 * ⚠️ Robolectric 下 `ApplicationProvider.getApplicationContext()` 的 `packageManager`
 * 不含 leanback / television 特性 ⇒ [AppPreferences] 判为**手机** ⇒
 * 「外接存储」默认 `false`。这条正是「平台相关默认值」的**可断言入口**。
 *
 * ⚠️ 注（沿用 `AppPreferencesPlaylistTest` 的说明）：datastore 1.0.0 在 Windows +
 * Robolectric 下连续快速写同名文件存在 rename 竞态 ⇒ 每次写之间 `delay` 让出句柄。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoWallPrefsTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
    }

    private suspend fun settle() = delay(30)

    private suspend fun settings() = prefs.appSettings.first()

    // ── ① 默认值 ────────────────────────────────────────────────────────────

    @Test
    fun `defaults match the documented table`() = runBlocking {
        val s = settings()
        // 三个来源开关：Robolectric 非电视 ⇒ 全部关（外接存储的平台默认值见类 KDoc）
        assertFalse("图库默认关", s.photoWallGalleryEnabled)
        assertFalse("非电视上外接存储默认关", s.photoWallExternalEnabled)
        assertFalse("Jellyfin 默认关", s.photoWallJellyfinEnabled)
        assertFalse("来源均衡默认关", s.photoWallSourceBalance)
        assertEquals("目录默认空", "", s.photoWallDirUri)
        assertTrue("仅扫常见目录默认开", s.photoWallCommonDirsOnly)
        assertFalse("仅含人像默认关", s.photoWallFacesOnly)
        assertFalse("人脸检测未完成", s.photoWallFaceScanDone)
        assertTrue("随机转场默认开", s.photoWallRandomTransition)
        assertEquals("指定转场默认交叉淡化", PhotoTransitionId.CROSSFADE, s.photoWallFixedTransition)
        assertEquals("转场时长默认 700ms", 700, s.photoWallTransitionMs)
        // ✅ 2026-09-23 用户确认：默认 8 秒
        assertEquals("停留时长默认 8000ms", 8_000, s.photoWallHoldMs)
        assertEquals("画面适配默认满屏", PhotoScaleMode.CROP, s.photoWallScaleMode)
        assertTrue("Ken Burns 默认开", s.photoWallKenBurns)
        assertFalse("音频反应默认关（§5.5 不卡节拍）", s.photoWallAudioReactive)
        assertTrue("随节拍缩放默认开", s.photoWallPulseZoom)
        assertTrue("随低频呼吸默认开", s.photoWallBreathe)
    }

    // ── ② 读写往返 ──────────────────────────────────────────────────────────

    @Test
    fun `every field survives a write read round trip`() = runBlocking {
        prefs.photoWall.setGalleryEnabled(true); settle()
        prefs.photoWall.setExternalEnabled(true); settle()
        prefs.photoWall.setJellyfinEnabled(true); settle()
        prefs.photoWall.setSourceBalance(true); settle()
        prefs.photoWall.setDirUri("content://com.android.externalstorage.documents/tree/1234%3APhotos"); settle()
        prefs.photoWall.setCommonDirsOnly(false); settle()
        prefs.photoWall.setFacesOnly(true); settle()
        prefs.photoWall.setFaceScanDone(true); settle()
        prefs.photoWall.setRandomTransition(false); settle()
        prefs.photoWall.setFixedTransition(PhotoTransitionId.NOISE_DISSOLVE); settle()
        prefs.photoWall.setTransitionMs(1_500); settle()
        prefs.photoWall.setHoldMs(12_000); settle()
        prefs.photoWall.setScaleMode(PhotoScaleMode.FIT); settle()
        prefs.photoWall.setKenBurns(false); settle()
        prefs.photoWall.setAudioReactive(true); settle()
        prefs.photoWall.setPulseZoom(false); settle()
        prefs.photoWall.setBreathe(false); settle()

        val s = settings()
        assertTrue(s.photoWallGalleryEnabled)
        assertTrue(s.photoWallExternalEnabled)
        assertTrue(s.photoWallJellyfinEnabled)
        assertTrue(s.photoWallSourceBalance)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/1234%3APhotos",
            s.photoWallDirUri
        )
        assertFalse(s.photoWallCommonDirsOnly)
        assertTrue(s.photoWallFacesOnly)
        assertTrue(s.photoWallFaceScanDone)
        assertFalse(s.photoWallRandomTransition)
        assertEquals(PhotoTransitionId.NOISE_DISSOLVE, s.photoWallFixedTransition)
        assertEquals(1_500, s.photoWallTransitionMs)
        assertEquals(12_000, s.photoWallHoldMs)
        assertEquals(PhotoScaleMode.FIT, s.photoWallScaleMode)
        assertFalse(s.photoWallKenBurns)
        assertTrue(s.photoWallAudioReactive)
        assertFalse(s.photoWallPulseZoom)
        assertFalse(s.photoWallBreathe)
    }

    // ── ③ 越界钳制（写入侧） ────────────────────────────────────────────────

    /**
     * 钳制必须发生在**写入侧**：设置页的 `+/-` 自己会夹一次，但**备份导入**那条路不经过 UI
     * ⇒ 手改过的备份（`photoWallTransitionMs: 999999`）只有靠 setter 才能挡住。
     */
    @Test
    fun `out of range durations are clamped on write`() = runBlocking {
        prefs.photoWall.setTransitionMs(10); settle()
        assertEquals("低于下限夹到 300", 300, settings().photoWallTransitionMs)

        prefs.photoWall.setTransitionMs(999_999); settle()
        assertEquals("高于上限夹到 2000", 2_000, settings().photoWallTransitionMs)

        prefs.photoWall.setHoldMs(0); settle()
        assertEquals("低于下限夹到 3000", 3_000, settings().photoWallHoldMs)

        prefs.photoWall.setHoldMs(999_999); settle()
        assertEquals("高于上限夹到 30000", 30_000, settings().photoWallHoldMs)
    }

    // ── ④ 枚举解析：永不返回 null ───────────────────────────────────────────

    /**
     * `fromKey()` 是 DataStore 那条路的唯一入口。它**必须**永不返回 null ——
     * 返回 null 会让 `AppSettings` 的非空字段变 null，后续 `.name` 直接 NPE
     * （与 `docs/technical-overview.md` §10.172 记的是同一个坑，只是那条路在 Gson 上）。
     */
    @Test
    fun `transition fromKey never returns null`() {
        assertEquals(PhotoTransitionId.CROSSFADE, PhotoTransitionId.fromKey("CROSSFADE"))
        assertEquals(PhotoTransitionId.Default, PhotoTransitionId.CROSSFADE)
        assertEquals("未知名字回落默认", PhotoTransitionId.Default, PhotoTransitionId.fromKey("NO_SUCH_TRANSITION"))
        assertEquals("null 回落默认", PhotoTransitionId.Default, PhotoTransitionId.fromKey(null))
        assertEquals("空串回落默认", PhotoTransitionId.Default, PhotoTransitionId.fromKey(""))
        // ⚠️ 区分大小写（枚举名是常量名，不是用户输入）—— 与 fromKey 的实现保持一致
        assertEquals(PhotoTransitionId.Default, PhotoTransitionId.fromKey("crossfade"))
    }

    // ── ⑤ 端到端：老备份导入（T8.2 的验收判据）──────────────────────────────

    /**
     * §15.3 T8.2 的验收判据：「用旧备份反序列化不抛异常」。
     *
     * 这里走的是**完整两步**：`backupGson` 反序列化老 JSON（缺 17 个新键）→
     * `importBackupData()` 把它写进 DataStore。修复前这条链正是老备份导入失败的现场
     * （枚举字段变 null ⇒ `.name` NPE ⇒ `dataStore.edit {}` 事务回滚）。
     *
     * ⚠️ 顺带覆盖 `nameOrDefault()`：`backupGson` 的容错适配器**只对「名字不认识」回落**，
     * 对 JSON 里的字面 `null` 仍返回 null ⇒ 导入路径必须自己再兜一层。
     */
    @Test
    fun `importing a legacy backup that lacks every photo wall key does not throw`() = runBlocking {
        val legacyJson = """{"visualizerTheme":"CLASSICAL_WAVE"}"""
        val legacy = backupGson.fromJson(legacyJson, com.nasmusic.tv.data.model.AppSettings::class.java)

        // 不抛异常就是通过（修复前这里会抛 NPE）
        prefs.importBackupData(AppPreferences.BackupData(appSettings = legacy))
        settle()

        val s = settings()
        assertEquals("缺键 ⇒ 保持默认 8000ms", 8_000, s.photoWallHoldMs)
        assertEquals(700, s.photoWallTransitionMs)
        assertEquals(PhotoTransitionId.CROSSFADE, s.photoWallFixedTransition)
        assertEquals(PhotoScaleMode.CROP, s.photoWallScaleMode)
        assertTrue(s.photoWallRandomTransition)
        assertTrue(s.photoWallCommonDirsOnly)
        assertTrue(s.photoWallKenBurns)
        assertFalse(s.photoWallGalleryEnabled)
        assertFalse(s.photoWallAudioReactive)
    }
}
