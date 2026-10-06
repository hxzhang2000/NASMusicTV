package com.nasmusic.tv.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `PermissionHelper` 本地音乐权限分支的门禁测试（G1，权限与签名统一方案 §7.1）。
 *
 * ## 背景
 *
 * v2.38.3 把本地音乐权限从「启动即申请」改为「本地音乐总开关驱动、按需申请」
 * （§5.5）。`getLocalMusicPermissions()` 因此成为开关打开时**唯一**喂给系统对话框的
 * 数组 —— 若它被改成空数组，系统对话框不弹、回调不触发、开关静默回弹为关，
 * 整个 B 线功能无声失效。故把 SDK 分支抽成 `localMusicPermissionsForSdk(sdkInt)`
 * 纯函数后断言其分支契约。
 *
 * ⚠️ 本机只缓存了 Robolectric SDK 34/30 两个 android-all jar（`PhotoPermissionStateTest`
 * 的记录）⇒ 不跑 API 33 专属分支的 Robolectric 环境，SDK 分支差异全部走纯函数注入。
 *
 * ## 断言口径（方案 §7.1）
 *
 * | # | 内容 |
 * |---|---|
 * | L1 | sdkInt ≥ 33 ⇒ READ_MEDIA_AUDIO，且不含 READ_EXTERNAL_STORAGE |
 * | L2 | sdkInt ≤ 32 ⇒ READ_EXTERNAL_STORAGE，且不含 READ_MEDIA_AUDIO |
 * | L3 | 两个分支都返回非空数组（防空数组静默不弹窗） |
 * | L4 | 负向自证：权限未授予时 `hasLocalMusicPermission()` 必须 false |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMusicPermissionPolicyTest {

    private val readMediaAudio = "android.permission.READ_MEDIA_AUDIO"
    private val readExternalStorage = "android.permission.READ_EXTERNAL_STORAGE"

    // ─────────────────── L1：API 33+ 分支 ───────────────────

    @Test
    fun `api33以上只要READ_MEDIA_AUDIO且不含旧存储权限`() {
        for (sdk in listOf(33, 34, 35)) {
            val perms = PermissionHelper.localMusicPermissionsForSdk(sdk)
            assertEquals("sdkInt=$sdk 应只返回一个权限", 1, perms.size)
            assertEquals("sdkInt=$sdk 应返回 READ_MEDIA_AUDIO", readMediaAudio, perms[0])
        }
    }

    // ─────────────────── L2：API ≤ 32 分支 ───────────────────

    @Test
    fun `api32以下只要READ_EXTERNAL_STORAGE且不含细粒度媒体权限`() {
        // 22 = 本项目 minSdk；32 = 旧存储权限的最后一个版本
        for (sdk in listOf(22, 28, 30, 32)) {
            val perms = PermissionHelper.localMusicPermissionsForSdk(sdk)
            assertEquals("sdkInt=$sdk 应只返回一个权限", 1, perms.size)
            assertEquals("sdkInt=$sdk 应返回 READ_EXTERNAL_STORAGE", readExternalStorage, perms[0])
        }
    }

    // ─────────────────── L3：非空兜底 ───────────────────

    @Test
    fun `任何版本分支都不得返回空数组`() {
        // 空数组会让 requestPermissions 静默不弹窗、回调不触发 —— 开关无声失效
        for (sdk in listOf(22, 30, 32, 33, 34, 35)) {
            assertTrue(
                "sdkInt=$sdk 返回了空权限数组 —— 开关打开时系统对话框不会弹出，" +
                    "B 线功能会静默失效",
                PermissionHelper.localMusicPermissionsForSdk(sdk).isNotEmpty(),
            )
        }
    }

    // ─────────────────── L4：授权状态判定（负向自证） ───────────────────

    /**
     * 把 [PermissionHelper.getLocalMusicPermissions] 的产物喂给 checkSelfPermission 的
     * Robolectric 假实现：未授予必须 false（否则被拒回调会误判成已授权、开关不回弹），
     * 授予后必须 true。
     */
    @Test
    fun `未授予时hasLocalMusicPermission必须为false授予后为true`() {
        // ⚠️ 必须取 Application 类型：shadowOf(Context) 落到 ShadowContext（无 grant/deny），
        // 照 PhotoPermissionStateTest 的写法取 Application 才能操作权限授予状态
        val app = ApplicationProvider.getApplicationContext<Application>()
        val perms = PermissionHelper.getLocalMusicPermissions()

        shadowOf(app).denyPermissions(*perms)
        assertFalse(
            "权限被拒后 hasLocalMusicPermission 仍为 true —— 被拒回调会误判成已授权，" +
                "开关不回弹、曲库也不该出现的本地歌会出现",
            PermissionHelper.hasLocalMusicPermission(app),
        )

        shadowOf(app).grantPermissions(*perms)
        assertTrue(
            "授予权限后 hasLocalMusicPermission 仍为 false —— 开关每次打开都会重复弹窗",
            PermissionHelper.hasLocalMusicPermission(app),
        )
    }
}
