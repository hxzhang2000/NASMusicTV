package com.nasmusic.tv.backend.network.baidu

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.CloudDriveConfig
import com.nasmusic.tv.data.model.CloudDriveType
import com.nasmusic.tv.data.prefs.AppPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * API 漂移一次性提示逻辑单测。
 *
 * 覆盖：漂移时首次应提示、提示后写入 baiduApiDriftNotified 标记去重（再次启动不再弹）、
 * 以及 AppPreferences 标记的持久化回环。
 *
 * Robolectric：AppPreferences 依赖 Context（DataStore），AppLog 调 android.util.Log。
 */
@RunWith(RobolectricTestRunner::class)
class ApiDriftNotifyTest {

    private fun newPrefs(): AppPreferences =
        AppPreferences(ApplicationProvider.getApplicationContext<Context>())

    private fun driftedConfig(): CloudDriveConfig =
        CloudDriveConfig(
            type = CloudDriveType.BAIDU,
            apiProbeBaseline = "fp-v1",
            apiProbeResult = "fp-v2"
        )

    private fun stableConfig(): CloudDriveConfig =
        CloudDriveConfig(
            type = CloudDriveType.BAIDU,
            apiProbeBaseline = "fp-v1",
            apiProbeResult = "fp-v1"
        )

    @Test
    fun `初始未提示过`() {
        assertFalse(newPrefs().getBaiduApiDriftNotifiedSync())
    }

    @Test
    fun `漂移且未提示过应弹一次提示`() {
        val prefs = newPrefs()
        assertTrue(ApiProbe.shouldNotifyDrift(driftedConfig().apiDrifted, prefs.getBaiduApiDriftNotifiedSync()))
    }

    @Test
    fun `提示后写标记再次启动不再弹`() {
        val prefs = newPrefs()
        // 首次：漂移 + 未提示 → 应提示
        assertTrue(ApiProbe.shouldNotifyDrift(driftedConfig().apiDrifted, prefs.getBaiduApiDriftNotifiedSync()))
        // 提示后写入标记（模拟 MainViewModel 提示完成）
        prefs.setBaiduApiDriftNotifiedSync(true)
        // 再次启动：漂移 + 已提示 → 不再弹
        assertFalse(ApiProbe.shouldNotifyDrift(driftedConfig().apiDrifted, prefs.getBaiduApiDriftNotifiedSync()))
    }

    @Test
    fun `未漂移即使未提示也不弹`() {
        val prefs = newPrefs()
        assertFalse(ApiProbe.shouldNotifyDrift(stableConfig().apiDrifted, prefs.getBaiduApiDriftNotifiedSync()))
    }

    @Test
    fun `baiduApiDriftNotified 标记持久化回环`() {
        val prefs = newPrefs()
        prefs.setBaiduApiDriftNotifiedSync(true)
        assertTrue(prefs.getBaiduApiDriftNotifiedSync())
        // 漂移但已提示 → 不弹
        assertFalse(ApiProbe.shouldNotifyDrift(driftedConfig().apiDrifted, prefs.getBaiduApiDriftNotifiedSync()))
    }
}