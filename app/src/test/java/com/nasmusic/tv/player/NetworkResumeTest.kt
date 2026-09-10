package com.nasmusic.tv.player

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F2-4 断线续播：networkLost 冻结 + ResumePoint 逻辑单测。
 *
 * onPlayerError 的冻结分支依赖真实 ExoPlayer，此处直接测 PlayerManager 的
 * 状态机核心（networkLost/recordPendingResume/onNetworkRestored 语义）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkResumeTest {

    private fun newManager(): PlayerManager {
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(context)
        return PlayerManager(context)
    }

    @Test
    fun `networkGone sets flag and restore clears it`() {
        val pm = newManager()
        assertFalse(pm.networkLost)
        pm.onNetworkGone()
        assertTrue(pm.networkLost)
        assertNull(pm.onNetworkRestored())
        assertFalse(pm.networkLost)
    }

    @Test
    fun `restore without pending resume returns null`() {
        val pm = newManager()
        assertNull(pm.pendingResume)
        assertNull(pm.onNetworkRestored())
    }

    @Test
    fun `resume point data class semantics`() {
        val rp = PlayerManager.ResumePoint(index = 3, positionMs = 45_000L, wasPlaying = true)
        assertEquals(3, rp.index)
        assertEquals(45_000L, rp.positionMs)
        assertTrue(rp.wasPlaying)
        assertEquals(rp, rp.copy())
    }
}
