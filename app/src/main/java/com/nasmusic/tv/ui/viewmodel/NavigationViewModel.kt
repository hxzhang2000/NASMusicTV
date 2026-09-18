package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.ui.screens.settings.SettingsSection

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

    // =====================================================================
    // v2.36.0 竖屏设置两级页（方案 §6.2 / §8.7 / K2）
    //
    // ⚠️ 为什么放在这里而不是 SettingsScreen 的局部 remember：
    // `AppRoot` 的 BACK handler 需要读它才能实现「二级页 BACK → 回设置列表」。
    // 页面级 BACK 状态必须归 navVM（与 currentScreen 同构），且要加进 AppRoot
    // 那个 LaunchedEffect 的 key 列表，否则 handler 不会随分区切换刷新。
    // =====================================================================

    /** 竖屏设置页当前进入的分区；`null` = 停留在设置主页（一级） */
    private val _settingsSection = MutableStateFlow<SettingsSection?>(null)
    val settingsSection: StateFlow<SettingsSection?> = _settingsSection.asStateFlow()

    /** 进入某个设置分区（竖屏二级页） */
    fun openSettingsSection(section: SettingsSection) {
        _settingsSection.value = section
    }

    /** 关闭设置分区，回到设置主页 */
    fun closeSettingsSection() {
        _settingsSection.value = null
    }
}
