package com.nasmusic.tv.util

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.util.PermissionHelper.PhotoPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 照片权限**三态判定**门禁（§9.6 / §15.3 T9.1 的验收判据）
 *
 * 这一条判据在真机上的表现是「Android 14 选『仅选择照片』→ 必须判为 `PARTIAL` 而非 `DENIED`」。
 * 真机复验由用户执行，但**判据本身**可以在这里钉住：
 * 把「重启之后」的真实权限状态如实模拟进来（`READ_MEDIA_IMAGES` 未授予、
 * `READ_MEDIA_VISUAL_USER_SELECTED` 已授予），断言结果是 `PARTIAL`。
 *
 * ⚠️ 为什么专门测这个：`READ_MEDIA_IMAGES` 在「仅选择照片」下是**会话级**授予
 * ⇒ 重启后它就是未授予。只判它会把「已经授权了部分照片」的用户误判成被拒，
 * 于是开关被错误回弹（风险 R27）。真机上这个问题**只在重启后才暴露**，
 * 而模拟器 / 单元测试都不会主动报错 ⇒ 必须显式钉住。
 *
 * ## 关于 SDK 档位
 *
 * 本机 Robolectric 只缓存了 **SDK 34** 与 **SDK 30** 两个 `android-all` jar
 * （见 `~/.m2/repository/org/robolectric/android-all-instrumented/`）⇒
 * 只覆盖这两档；**API 33 那一支（两态，无部分授权）本机跑不了**，
 * 由代码审查 + `photoPermissionState` 的 `when` 分支结构保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoPermissionStateTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    private fun deny(vararg permissions: String) = shadowOf(app).denyPermissions(*permissions)

    // ── Android 14（API 34）：三态 ────────────────────────────────────────

    @Test
    fun `android 14 - allow all is FULL`() {
        deny(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        grant(Manifest.permission.READ_MEDIA_IMAGES)
        assertEquals(PhotoPermissionState.FULL, PermissionHelper.photoPermissionState(app))
        assertTrue(PermissionHelper.hasPhotoPermission(app))
    }

    @Test
    fun `android 14 - selected photos only is PARTIAL and never DENIED`() {
        // ⛔ 本门禁的靶子：这正是「重启之后」的真实状态 ——
        //    READ_MEDIA_IMAGES 是会话级授予，重启即失效；只有 VISUAL_USER_SELECTED 是持久的。
        deny(Manifest.permission.READ_MEDIA_IMAGES)
        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        val state = PermissionHelper.photoPermissionState(app)
        assertFalse(
            "只判 READ_MEDIA_IMAGES 会把它判成 DENIED ⇒ 开关被误回弹（风险 R27）",
            state == PhotoPermissionState.DENIED,
        )
        assertEquals(PhotoPermissionState.PARTIAL, state)
        assertTrue("部分授权下「已选中的那批」是可读的 ⇒ 必须算可读", PermissionHelper.hasPhotoPermission(app))
    }

    @Test
    fun `android 14 - deny all is DENIED`() {
        deny(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        assertEquals(PhotoPermissionState.DENIED, PermissionHelper.photoPermissionState(app))
        assertFalse(PermissionHelper.hasPhotoPermission(app))
    }

    @Test
    fun `android 14 requests both permissions`() {
        val perms = PermissionHelper.getPhotoPermissions().toList()
        assertEquals("API 34+ 必须同时申请两个权限", 2, perms.size)
        assertTrue(perms.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertTrue(
            "不申请 VISUAL_USER_SELECTED 时，系统对话框根本不会出现「仅选择照片」选项",
            perms.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        )
    }

    // ── Android 11（API 30）：旧版存储权限 ────────────────────────────────

    @Test
    @Config(sdk = [30])
    fun `legacy storage permission maps to FULL_LEGACY`() {
        deny(Manifest.permission.READ_MEDIA_IMAGES)
        grant(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(PhotoPermissionState.FULL_LEGACY, PermissionHelper.photoPermissionState(app))
        assertTrue(PermissionHelper.hasPhotoPermission(app))
    }

    @Test
    @Config(sdk = [30])
    fun `legacy without storage permission is DENIED`() {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(PhotoPermissionState.DENIED, PermissionHelper.photoPermissionState(app))
    }

    @Test
    @Config(sdk = [30])
    fun `legacy asks for READ_EXTERNAL_STORAGE only`() {
        val perms = PermissionHelper.getPhotoPermissions().toList()
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), perms)
    }
}
