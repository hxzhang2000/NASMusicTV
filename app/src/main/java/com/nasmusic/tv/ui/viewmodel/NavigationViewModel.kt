package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nasmusic.tv.data.model.Screen

/**
 * 导航域 ViewModel（R-1 拆分自 MainViewModel）：
 * 当前屏幕状态与导航。
 *
 * 注意：详情页导航（AlbumDetail/ArtistDetail）携带选中数据，仍经 LibraryEvent
 * 路由后由 MainViewModel 联动设置——本类只持有纯导航状态。
 */
class NavigationViewModel(app: Application) : AndroidViewModel(app) {

    // --- 导航状态 ---
    private val _currentScreen = MutableStateFlow(Screen.Home)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }
}
