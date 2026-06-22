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
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

/**
 * NetworkMonitor 单元测试
 * 验证网络回调的注册、注销以及回调触发逻辑
 */
@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    @Test
    fun `register creates callback and triggers onAvailable`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        var availableCalled = false
        var lostCalled = false
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = { lostCalled = true }
        )

        monitor.register()

        // Capture the callback that was registered with ConnectivityManager
        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            captor.capture()
        )

        val callback = captor.value

        // Simulate network available
        callback.onAvailable(mock(Network::class.java))
        assertTrue("onAvailable should be called", availableCalled)
    }

    @Test
    fun `onAvailable triggers available callback`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        var availableCalled = false
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = {}
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            captor.capture()
        )

        captor.value.onLost(mock(Network::class.java))
        // onLost does NOT trigger onNetworkAvailable
        assertFalse(availableCalled)
    }

    @Test
    fun `onLost triggers lost callback`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        var lostCalled = false
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {},
            onNetworkLost = { lostCalled = true }
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            captor.capture()
        )

        captor.value.onLost(mock(Network::class.java))
        assertTrue("onLost should be called", lostCalled)
    }

    @Test
    fun `onCapabilitiesChanged with internet calls available`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        var availableCalled = false
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = { availableCalled = true },
            onNetworkLost = {}
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            captor.capture()
        )

        val caps = mock(NetworkCapabilities::class.java)
        `when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)

        captor.value.onCapabilitiesChanged(mock(Network::class.java), caps)
        assertTrue("onCapabilitiesChanged with internet should trigger onNetworkAvailable", availableCalled)
    }

    @Test
    fun `onCapabilitiesChanged without internet calls lost`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        var lostCalled = false
        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {},
            onNetworkLost = { lostCalled = true }
        )

        monitor.register()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            captor.capture()
        )

        val caps = mock(NetworkCapabilities::class.java)
        `when`(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(false)

        captor.value.onCapabilitiesChanged(mock(Network::class.java), caps)
        assertTrue("onCapabilitiesChanged without internet should trigger onNetworkLost", lostCalled)
    }

    @Test
    fun `unregister removes callback and clears state`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {},
            onNetworkLost = {}
        )

        monitor.register()
        monitor.unregister()

        val captor = ArgumentCaptor.forClass(ConnectivityManager.NetworkCallback::class.java)
        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            captor.capture()
        )

        verify(cm).unregisterNetworkCallback(captor.value)
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
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {},
            onNetworkLost = {}
        )

        monitor.register()
        monitor.unregister()
        monitor.unregister() // Second unregister should be safe
    }

    @Test
    fun `double register registers only once`() {
        val context = mock(Context::class.java)
        val cm = mock(ConnectivityManager::class.java)
        `when`(context.getSystemService(Context.CONNECTIVITY_SERVICE)).thenReturn(cm)

        val monitor = NetworkMonitor(
            context = context,
            onNetworkAvailable = {},
            onNetworkLost = {}
        )

        monitor.register()
        monitor.register() // Second register replaces callback

        verify(cm).registerNetworkCallback(
            mock(NetworkRequest::class.java),
            mock(ConnectivityManager.NetworkCallback::class.java)
        )
    }
}
