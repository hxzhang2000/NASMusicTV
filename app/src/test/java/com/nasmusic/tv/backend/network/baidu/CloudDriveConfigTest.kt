package com.nasmusic.tv.backend.network.baidu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.BaiduTokens
import com.nasmusic.tv.data.model.CloudDriveConfig
import com.nasmusic.tv.data.model.CloudDriveType
import com.nasmusic.tv.data.prefs.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * CloudDriveConfig 单测：总开关 isActive 判定、API 漂移判定、MV 目录回退、
 * 以及按 CloudDriveType 的配置存取（AppPreferences 回环）。
 *
 * Robolectric：AppPreferences 依赖 Context（DataStore），且 AppLog 调 android.util.Log。
 */
@RunWith(RobolectricTestRunner::class)
class CloudDriveConfigTest {

    private val baidu = CloudDriveType.BAIDU

    private fun cfg(
        enabled: Boolean = false,
        tokens: BaiduTokens? = null,
        rootDir: String = "/音乐",
        mvDir: String? = null,
        baseline: String? = null,
        result: String? = null
    ) = CloudDriveConfig(
        type = baidu,
        enabled = enabled,
        tokens = tokens,
        musicRootDir = rootDir,
        mvDir = mvDir,
        apiProbeBaseline = baseline,
        apiProbeResult = result
    )

    // ── isActive（总开关判定）──

    @Test
    fun `isActive 总开关关闭返回 false`() {
        assertFalse(cfg(enabled = false).isActive)
    }

    @Test
    fun `isActive 开启但未登录返回 false`() {
        assertFalse(cfg(enabled = true, tokens = null).isActive)
    }

    @Test
    fun `isActive 开启且已登录返回 true`() {
        assertTrue(cfg(enabled = true, tokens = BaiduTokens("acc", "ref", 1700000000000L)).isActive)
    }

    // ── apiDrifted（API 版本漂移判定）──

    @Test
    fun `apiDrifted 基线为空返回 false`() {
        assertFalse(cfg(baseline = null, result = "fp").apiDrifted)
        assertFalse(cfg(baseline = "", result = "fp").apiDrifted)
    }

    @Test
    fun `apiDrifted 实测为空返回 false`() {
        assertFalse(cfg(baseline = "fp", result = null).apiDrifted)
        assertFalse(cfg(baseline = "fp", result = "").apiDrifted)
    }

    @Test
    fun `apiDrifted 基线等于实测返回 false`() {
        assertFalse(cfg(baseline = "same", result = "same").apiDrifted)
    }

    @Test
    fun `apiDrifted 基线不等于实测返回 true`() {
        assertTrue(cfg(baseline = "fp-v1", result = "fp-v2").apiDrifted)
    }

    // ── effectiveMvDir（MV 目录回退）──

    @Test
    fun `effectiveMvDir 未设 mvDir 回退到音乐根目录`() {
        assertEquals("/音乐", cfg(rootDir = "/音乐", mvDir = null).effectiveMvDir)
    }

    @Test
    fun `effectiveMvDir 已设 mvDir 用 mvDir`() {
        assertEquals("/音乐/MV", cfg(rootDir = "/音乐", mvDir = "/音乐/MV").effectiveMvDir)
    }

    // ── 按 CloudDriveType 存取（AppPreferences 回环）──

    @Test
    fun `AppPreferences 按 CloudDriveType 存取回环`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)
        // 用一个独立目录标记避免与默认配置混淆
        val config = cfg(enabled = true, rootDir = "/测试目录", mvDir = "/测试目录/MV")
        prefs.saveCloudDriveConfigSync(config)
        val loaded = prefs.getCloudDriveConfigSync(CloudDriveType.BAIDU)
        assertTrue(loaded != null)
        assertTrue(loaded!!.enabled)
        assertEquals("/测试目录", loaded.musicRootDir)
        assertEquals("/测试目录/MV", loaded.mvDir)
        // 未实现的网盘类型应返回 null（未写入过）
        assertFalse(prefs.getCloudDriveConfigSync(CloudDriveType.ALIYUN) != null)
    }

    @Test
    fun `getBaiduConfigSync 兜底默认配置永不返回 null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)
        val cfg = prefs.getBaiduConfigSync()
        assertTrue(cfg != null)
        assertEquals(CloudDriveType.BAIDU, cfg.type)
        assertFalse(cfg.enabled)  // 默认关闭
    }
}