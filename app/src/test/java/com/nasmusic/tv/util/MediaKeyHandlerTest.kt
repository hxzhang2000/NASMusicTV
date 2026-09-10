package com.nasmusic.tv.util

import android.view.KeyEvent
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.ui.viewmodel.PlayerViewModel
import com.nasmusic.tv.data.model.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/**
 * MediaKeyHandler 单元测试
 * 验证不同按键代码的路由逻辑
 *
 * N-1 后 MediaKeyHandler 经 viewModel.playerVM 调用播放控制（子 VM 公开 + 手动 DI），
 * mock 需用 doReturn 桩 playerVM（when() 内嵌 mock 调用会触发 UnfinishedStubbingException）。
 */
class MediaKeyHandlerTest {

    /** 构造桩好 playerVM 的 MainViewModel mock */
    private fun mockVm(): Pair<MainViewModel, PlayerViewModel> {
        val vm = mock<MainViewModel>()
        val playerVM = mock<PlayerViewModel>()
        doReturn(playerVM).`when`(vm).playerVM
        return vm to playerVM
    }

    @Test
    fun `PLAY_PAUSE calls playPause and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).playPause()
    }

    @Test
    fun `PLAY calls playPause and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_MEDIA_PLAY, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).playPause()
    }

    @Test
    fun `PAUSE calls playPause and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_MEDIA_PAUSE, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).playPause()
    }

    @Test
    fun `NEXT calls next and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_MEDIA_NEXT, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).next()
    }

    @Test
    fun `PREVIOUS calls previous and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).previous()
    }

    @Test
    fun `STOP calls playPause and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_MEDIA_STOP, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).playPause()
    }

    @Test
    fun `DPAD_CENTER in NowPlaying calls playPause and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_DPAD_CENTER, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).playPause()
    }

    @Test
    fun `DPAD_CENTER in immersive mode returns false without calling playPause`() {
        val vm = mock<MainViewModel>()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_DPAD_CENTER, null, vm, true, Screen.NowPlaying
        )
        assertFalse(result)
        verifyNoInteractions(vm)
    }

    @Test
    fun `DPAD_CENTER in LibraryScreen returns false without calling playPause`() {
        val vm = mock<MainViewModel>()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_DPAD_CENTER, null, vm, false, Screen.Library
        )
        assertFalse(result)
        verifyNoInteractions(vm)
    }

    @Test
    fun `ENTER in NowPlaying calls playPause and returns true`() {
        val (vm, playerVM) = mockVm()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_ENTER, null, vm, false, Screen.NowPlaying
        )
        assertTrue(result)
        verify(playerVM).playPause()
    }

    @Test
    fun `DPAD_CENTER in SettingsScreen returns false`() {
        val vm = mock<MainViewModel>()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_DPAD_CENTER, null, vm, false, Screen.Settings
        )
        assertFalse(result)
        verifyNoInteractions(vm)
    }

    @Test
    fun `unknown key returns false`() {
        val vm = mock<MainViewModel>()
        val result = MediaKeyHandler.handleKeyEvent(
            KeyEvent.KEYCODE_BACK, null, vm, false, Screen.NowPlaying
        )
        assertFalse(result)
        verifyNoInteractions(vm)
    }
}
