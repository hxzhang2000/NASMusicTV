package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务器连接域 ViewModel（R-1 拆分自 MainViewModel）：
 * 连接/断开/自动连接/API 版本聚合。
 *
 * 依赖：BackendRegistry（AppPreferences 经 NasMusicApp 取）。
 * 连接成功后的曲库加载/队列刷新经 [onConnected]/[onDisconnected] 回调路由回 MainViewModel。
 */
class ServerViewModel(
    app: Application,
    private val backendRegistry: BackendRegistry
) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    /** 连接成功后的联动回调（加载曲库/刷新队列/导航），由 MainViewModel 注入 */
    var onConnected: (suspend () -> Unit)? = null
    /** 断开后的联动回调（清空曲库状态），由 MainViewModel 注入 */
    var onDisconnected: (() -> Unit)? = null

    // --- 连接状态 ---
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _serverDisplayName = MutableStateFlow("")
    val serverDisplayName: StateFlow<String> = _serverDisplayName.asStateFlow()

    /** 当前后端的 API 版本号（initialize 时获取，供设置→关于页展示） */
    private val _backendApiVersion = MutableStateFlow("Unknown")
    val backendApiVersion: StateFlow<String> = _backendApiVersion.asStateFlow()

    /** 全量后端/服务 API 版本号聚合（供设置→关于页分段展示） */
    private val _apiVersions = MutableStateFlow<List<VersionInfo>>(emptyList())
    val apiVersions: StateFlow<List<VersionInfo>> = _apiVersions.asStateFlow()

    // --- 启动连接提示 ---
    private val _showConnectPrompt = MutableStateFlow(false)
    val showConnectPrompt: StateFlow<Boolean> = _showConnectPrompt.asStateFlow()

    // --- 连接结果提示消息（显示几秒后自动清除）---
    private val _connectMessage = MutableStateFlow<String?>(null)
    val connectMessage: StateFlow<String?> = _connectMessage.asStateFlow()

    /** 启动时若有已保存配置，显示连接提示 */
    fun checkSavedConfigOnStart() {
        viewModelScope.launch {
            val config = prefs.serverConfig.first()
            if (config.baseUrl.isNotBlank()) {
                _showConnectPrompt.value = true
            }
        }
    }

    /** 关闭连接提示对话框 */
    fun dismissConnectPrompt() {
        _showConnectPrompt.value = false
    }

    /** 供其他域短暂显示连接类消息（如「已加入队列 N 首」），显示后自动清除 */
    fun postConnectMessage(message: String) {
        _connectMessage.value = message
        viewModelScope.launch {
            delay(3000)
            _connectMessage.value = null
        }
    }

    /**
     * 连接服务器。返回是否成功；连接成功的联动（导航/加载曲库）经 [onConnected] 回调。
     */
    suspend fun connectToServer(config: ServerConfig): Boolean {
        _isLoading.value = true
        return try {
            val success = backendRegistry.initialize(config)
            if (success) {
                _isConnected.value = true
                _serverDisplayName.value = backendRegistry.getServerDisplayName()
                _backendApiVersion.value = backendRegistry.getAdapter()?.apiVersion ?: "Unknown"
                refreshApiVersions()
                prefs.saveServerConfig(config.copy(isConnected = true))
                onConnected?.invoke()
            }
            success
        } catch (e: Exception) {
            _connectMessage.value = getApplication<Application>().getString(R.string.connect_failed_with_msg, e.message?.take(50))
            viewModelScope.launch {
                delay(3000)
                _connectMessage.value = null
            }
            false
        } finally {
            _isLoading.value = false
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                backendRegistry.disconnect()
            } catch (e: Exception) {
                AppLog.e("ServerViewModel", "disconnect failed", e)
            }
            _isConnected.value = false
            _serverDisplayName.value = ""
            _backendApiVersion.value = "Unknown"
            refreshApiVersions()
            onDisconnected?.invoke()
            try {
                val current = prefs.serverConfig.first()
                prefs.saveServerConfig(current.copy(isConnected = false))
            } catch (e: Exception) {
                AppLog.e("ServerViewModel", "disconnect: save config failed", e)
            }
        }
    }

    /**
     * 刷新全量后端/服务的 API 版本号聚合（供设置→关于页展示）。
     *
     * 调用时机：连接成功、断开连接、进入关于页。
     * 后端用运行时获取（[VersionInfo.Runtime]），未连接显示 Disconnected；
     * 外部服务（Jamendo/Open-Meteo/OpenWeatherMap）用静态常量；
     * 无版本号服务（Meting-API/Bilibili MV）用 NoVersion 仅展示服务名；
     * 百度网盘用静态常量展示 PCS 版本。
     */
    suspend fun refreshApiVersions() {
        val result = mutableListOf<VersionInfo>()

        // 1. 当前 NAS 后端（运行时获取）
        val adapter = backendRegistry.getAdapter()
        if (adapter != null) {
            result.add(try { adapter.getApiVersion() } catch (e: Exception) { VersionInfo.Disconnected(adapter.backendType) })
        }

        // 2. 百度网盘（静态常量）
        result.add(VersionInfo.Static("百度网盘", "PCS rest/2.0", "接口静默演进，无显式版本号"))

        // 3. 外部服务（静态常量）
        result.add(VersionInfo.Static("Jamendo", "v3.0"))
        result.add(VersionInfo.Static("Open-Meteo", "v1.0", "默认天气源"))
        result.add(VersionInfo.Static("OpenWeatherMap", "v2.5", "备用天气源"))

        // 4. 无版本号服务（仅展示服务名）
        result.add(VersionInfo.NoVersion("Meting-API"))
        result.add(VersionInfo.NoVersion("Bilibili MV"))

        _apiVersions.value = result
    }

    /** 后台刷新版本聚合（启动时） */
    fun refreshApiVersionsAsync() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                refreshApiVersions()
            }
        }
    }

    /**
     * 使用已保存的服务器配置自动连接
     * @param silent 静默模式（不显示提示消息）
     */
    fun connectToSavedServer(silent: Boolean = false) {
        viewModelScope.launch {
            val config = prefs.serverConfig.first()
            if (config.baseUrl.isBlank()) {
                if (!silent) {
                    _connectMessage.value = getApplication<Application>().getString(R.string.status_no_saved_server)
                    delay(3000)
                    _connectMessage.value = null
                }
                return@launch
            }

            if (!silent) {
                _showConnectPrompt.value = false
            }
            _isLoading.value = true
            try {
                val success = backendRegistry.initialize(config)
                if (success) {
                    _isConnected.value = true
                    _serverDisplayName.value = backendRegistry.getServerDisplayName()
                    _backendApiVersion.value = backendRegistry.getAdapter()?.apiVersion ?: "Unknown"
                    refreshApiVersions()
                    prefs.saveServerConfig(config.copy(isConnected = true))
                    onConnected?.invoke()
                    if (!silent) {
                        _connectMessage.value = getApplication<Application>().getString(R.string.connect_to_server_success, backendRegistry.getServerDisplayName())
                        delay(3000)
                        _connectMessage.value = null
                    }
                } else {
                    AppLog.w("ServerViewModel", "connectToSavedServer: initialize returned false")
                    if (!silent) {
                        _connectMessage.value = getApplication<Application>().getString(R.string.connect_failed_check_settings, "")
                        delay(3000)
                        _connectMessage.value = null
                    }
                }
            } catch (e: Exception) {
                AppLog.e("ServerViewModel", "connectToSavedServer failed", e)
                if (!silent) {
                    _connectMessage.value = getApplication<Application>().getString(R.string.connect_failed_with_msg, e.message)
                    delay(3000)
                    _connectMessage.value = null
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
}
