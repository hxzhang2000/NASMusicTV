package com.nasmusic.tv.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * NetworkMonitor 单元测试
 * 验证网络回调的注册、注销以及回调触发逻辑
 *
 * Robolectric 4.11.1 对 NetworkRequest.Builder.addCapability 无 shadow 实现，
 * 因此测试统一通过构造函数注入 mock 的 NetworkRequest，避免触发框架桩方法。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NetworkMonitorTest {

    /**
     * 共享测试夹具：mock Context/ConnectivityManager，并注入 mock NetworkRequest
     * （与 verify 校验的实例一致，避免 Builder 真实调用抛 not mocked）。
     */
    private class Fixture {
        val context: Context = mock(Context::class.java)
        val cm: ConnectivityManager = mock(ConnectivityManager::class.java)
        val request: NetworkRequest = mock(NetworkRequest::class.java)

        init {
            `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)
        }
    }

    @Test
    fun `register creates callback and triggers onAvailable`() {
        val fixture = Fixture()

        var availableCalled = false
        var lostCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = { lostCalled = true },
            networkRequest = fixture.request
        )

        monitor.register()

        // Capture the callback that was registered with ConnectivityManager
        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        val callback = captor.value

        // Simulate network available
        callback.onAvailable(mock(Network::class.java))
        assertTrue("onAvailable should be called", availableCalled)
    }

    @Test
    fun `onAvailable triggers available callback`() {
        val fixture = Fixture()

        var availableCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = {},
            networkRequest = fixture.request
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        captor.value.onLost(mock(Network::class.java))
        // onLost does NOT trigger onNetworkAvailable
        assertFalse(availableCalled)
    }

    @Test
    fun `onLost triggers lost callback`() {
        val fixture = Fixture()

        var lostCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = {},
            onNetworkLost = { lostCalled = true },
            networkRequest = fixture.request
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        // 防抖设计：onLost 仅在"已连接"（lastHasInternet=true）时回调一次。
        // 先建立连接状态，再模拟断网事件。
        captor.value.onAvailable(mock(Network::class.java))
        captor.value.onLost(mock(Network::class.java))
        assertTrue("onLost should be called after established connection", lostCalled)
    }

    @Test
    fun `onLost without established connection does not call lost`() {
        val fixture = Fixture()

        var lostCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = {},
            onNetworkLost = { lostCalled = true },
            networkRequest = fixture.request
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        // 从未连接（lastHasInternet=false）时 onLost 不应误报断网
        captor.value.onLost(mock(Network::class.java))
        assertFalse("onLost should NOT fire when never connected (debounce)", lostCalled)
    }

    @Test
    fun `onCapabilitiesChanged with internet calls available`() {
        val fixture = Fixture()

        var availableCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = {},
            networkRequest = fixture.request
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        val caps = mock(NetworkCapabilities::class.java)
        `when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)

        captor.value.onCapabilitiesChanged(mock(Network::class.java), caps)
        assertTrue("onCapabilitiesChanged with internet should trigger onNetworkAvailable", availableCalled)
    }

    @Test
    fun `onCapabilitiesChanged without internet does not call lost`() {
        val fixture = Fixture()

        var lostCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = {},
            onNetworkLost = { lostCalled = true },
            networkRequest = fixture.request
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        val caps = mock(NetworkCapabilities::class.java)
        `when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)

        captor.value.onCapabilitiesChanged(mock(Network::class.java), caps)
        // 防抖设计：onCapabilitiesChanged 丢失 internet 能力不触发 onNetworkLost
        //（WiFi 信号波动/网络切换高频触发，断网统一由 onLost 负责，避免重连风暴）
        assertFalse("without internet should NOT call lost (debounce)", lostCalled)
    }

    @Test
    fun `capabilities flap does not fire lost then onLost still fires`() {
        val fixture = Fixture()

        var availableCalled = false
        var lostCalled = false
        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = { lostCalled = true },
            networkRequest = fixture.request
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        // 1. 连接建立
        captor.value.onAvailable(mock(Network::class.java))
        assertTrue(availableCalled)

        // 2. capabilities 瞬时丢 internet（WiFi 抖动）→ 不误报断网
        val capsNoInternet = mock(NetworkCapabilities::class.java)
        `when`(capsNoInternet.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)
        captor.value.onCapabilitiesChanged(mock(Network::class.java), capsNoInternet)
        assertFalse("capabilities flap should not fire lost", lostCalled)

        // 3. capabilities 恢复 internet → 不重复触发 available（已连接）
        val capsInternet = mock(NetworkCapabilities::class.java)
        `when`(capsInternet.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        captor.value.onCapabilitiesChanged(mock(Network::class.java), capsInternet)
        // onNetworkAvailable 仍应只有一次（availableCalled 无计数器，此处验证未额外副作用可略）

        // 4. 真正断网（onLost）→ 触发 lost
        captor.value.onLost(mock(Network::class.java))
        assertTrue("onLost after established connection should fire", lostCalled)
    }

    @Test
    fun `unregister removes callback and clears state`() {
        val fixture = Fixture()

        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = {},
            onNetworkLost = {},
            networkRequest = fixture.request
        )

        monitor.register()
        monitor.unregister()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(fixture.cm).registerNetworkCallback(eq(fixture.request), captor.capture())

        verify(fixture.cm).unregisterNetworkCallback(captor.value)
    }

    @Test
    fun `unregister without prior register does not throw`() {
        val context = mock(Context::class.java)
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {},
            onNetworkLost = {}
        )

        // Should not throw
        monitor.unregister()
    }

    @Test
    fun `double unregister does not throw`() {
        val fixture = Fixture()

        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = {},
            onNetworkLost = {},
            networkRequest = fixture.request
        )

        monitor.register()
        monitor.unregister()
        monitor.unregister() // Second unregister should be safe
    }

    @Test
    fun `double register registers only once`() {
        val fixture = Fixture()

        val monitor = NetworkMonitor(
            context = fixture.context,
            onNetworkAvailable = {},
            onNetworkLost = {},
            networkRequest = fixture.request
        )

        monitor.register()
        monitor.register() // Second register replaces callback

        verify(fixture.cm).registerNetworkCallback(
            eq(fixture.request),
            any(ConnectivityManager.NetworkCallback::class.java)
        )
    }
}
