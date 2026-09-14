package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.baidu.BaiduFileIndexCache
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.backend.network.baidu.BaiduPanApi
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.NetworkFavoriteItem
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 网络音乐/百度网盘域 ViewModel（R-1 拆分自 MainViewModel）。
 *
 * W0 冻结：百度组件通过属性委托延迟访问（与 MainViewModel 原实现 :4847-4849 形式一致）。
 */
class NetworkMusicViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    private val baiduOAuth: BaiduOAuthClient get() = nasMusicApp.baiduOAuthClient
    private val baiduApi: BaiduPanApi get() = nasMusicApp.baiduPanApi
    private val baiduIndexCache: BaiduFileIndexCache get() = nasMusicApp.baiduFileIndexCache

    /** 加入队列动作（经 MainViewModel 路由到 PlayerViewModel） */
    var onAddToQueue: ((List<Song>) -> Unit)? = null
    /** 播放动作（经事件路由到 PlayerViewModel） */
    var onPlayQueue: ((List<Song>, Int) -> Unit)? = null
    /** 消息通道 */
    var showMessage: ((String) -> Unit)? = null

    /** 合并数据联动回调（百度索引变化时刷新曲库） */
    var onMergedDataInvalidated: (() -> Unit)? = null

    // ---- 网络收藏 ----

    private val _networkFavorites = MutableStateFlow<List<NetworkFavoriteItem>>(emptyList())

    init {
        // 监听网络收藏变化（DataStore 持久化，响应式更新）
        viewModelScope.launch {
            prefs.history.networkFavorites.collect { favorites ->
                _networkFavorites.value = favorites
            }
        }
    }

    /** 网络收藏原始列表（供 MainViewModel 派生 networkFavoriteSongs / networkFavoriteIds） */
    val networkFavorites: StateFlow<List<NetworkFavoriteItem>> = _networkFavorites.asStateFlow()

    /**
     * 切换歌曲收藏状态（统一模型）
     *
     * 所有歌曲共用一套收藏：网络/本地歌曲持久化到 DataStore（NetworkFavoriteItem，
     * 本地歌曲 networkSource="local"）。
     */
    fun toggleNetworkFavorite(song: Song, isNasFavorite: Boolean, onNasToggle: (Song, Boolean) -> Unit) {
        if (song.isNetworkSong || song.isLocalSong) {
            // 网络 / 本地歌曲：DataStore 持久化
            viewModelScope.launch {
                val item = NetworkFavoriteItem(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    coverUrl = song.coverUrl,
                    networkSource = if (song.isLocalSong) "local" else song.networkSource ?: "network",
                    networkId = song.networkId ?: "",
                    addedAtMs = System.currentTimeMillis()
                )
                prefs.history.toggleNetworkFavorite(item)
            }
        } else {
            // NAS 歌曲：走后端 adapter
            onNasToggle(song, isNasFavorite)
        }
    }

    /**
     * 判断网络歌曲是否已收藏（同步，用于 UI 快速判断）
     */
    fun isNetworkFavorite(songId: String): Boolean {
        return _networkFavorites.value.any { it.songId == songId }
    }

    /**
     * 播放网络歌曲
     *
     * 网络歌曲的 streamUrl 不持久化，播放前实时解析：
     * 1. 通过 NetworkMusicManager.resolvePlayUrl() 获取直联 URL
     * 2. 将解析后的 URL 填入 song.streamUrl
     * 3. 交给 PlayerManager 播放
     *
     * 解析失败时显示错误提示。
     */
    fun playNetworkSong(song: Song, onPlaySong: (Song) -> Unit) {
        AppLog.e("NetworkMusicViewModel", "playNetworkSong ENTRY: id=${song.id} title=${song.title} networkSource=${song.networkSource} networkId=${song.networkId} isNetworkSong=${song.isNetworkSong}")
        if (!song.isNetworkSong) {
            // 非 network 歌曲，走普通播放流程
            onPlaySong(song)
            return
        }
        // 防御：百度源若因登录时序未注册（浏览用 baiduApi 直连不需要注册，但播放需 services["baidu"]），
        // 播放时自愈注册，避免"能浏览不能播"
        viewModelScope.launch {
            // T2 第一批：注册改 suspend，移入协程内保证"先注册、后解析播放"
            if (song.networkSource == "baidu" && !nasMusicApp.networkMusicManager.isServiceRegistered("baidu")) {
                AppLog.e("NetworkMusicViewModel", "playNetworkSong: 检测到 baidu 服务未注册，尝试自愈注册后播放")
                nasMusicApp.refreshBaiduServiceRegistration()
            }
            try {
                // 已下载优先：本地文件存在则直接播本地（离线可播、省去直链解析），
                // 避免"已下载歌曲在直链过期/断网时仍走网络解析失败"
                val localUri = runCatching {
                    nasMusicApp.downloadRepository.playableLocalUri(song)
                }.getOrNull()
                if (!localUri.isNullOrBlank()) {
                    AppLog.d("NetworkMusicViewModel", "playNetworkSong: '${song.title}' 已下载，直接播本地文件")
                    onPlaySong(song.copy(streamUrl = localUri))
                    return@launch
                }
                val playUrl = nasMusicApp.networkMusicManager.resolvePlayUrl(song)
                if (playUrl.isNullOrBlank()) {
                    if (song.networkSource == "baidu") {
                        AppLog.w("NetworkMusicViewModel", "playNetworkSong: 百度网盘 resolvePlayUrl 返回 null（检查 token 授权 / filemetas dlink / 网络，详见 BaiduStreamFactory 日志）")
                    }
                    showMessage?.invoke(getApplication<Application>().getString(R.string.resolve_url_failed_retry))
                    return@launch
                }
                val playable = song.copy(streamUrl = playUrl)
                AppLog.d("NetworkMusicViewModel", "playNetworkSong: ${song.title} → $playUrl")
                onPlaySong(playable)
            } catch (e: Exception) {
                AppLog.e("NetworkMusicViewModel", "playNetworkSong failed", e)
                showMessage?.invoke(getApplication<Application>().getString(R.string.play_failed_with_msg, e.message?.take(50)))
            }
        }
    }

    // ---- 百度网盘连接状态 ----

    /** 百度网盘连接状态 */
    sealed class BaiduConnectionState {
        object Off : BaiduConnectionState()           // 未开启或未登录
        object Connecting : BaiduConnectionState()     // 设备码轮询中
        object LoggedIn : BaiduConnectionState()      // 已登录
        object DirMissing : BaiduConnectionState()    // 已登录但音乐根目录不存在，需重新设置
        data class Failed(val message: String) : BaiduConnectionState()
    }

    private val _baiduConnectionState = MutableStateFlow<BaiduConnectionState>(BaiduConnectionState.Off)
    val baiduConnectionState: StateFlow<BaiduConnectionState> = _baiduConnectionState.asStateFlow()

    /** 设备码授权结果（供对话框显示） */
    private val _baiduDeviceCode = MutableStateFlow<BaiduOAuthClient.DeviceCodeResult?>(null)
    val baiduDeviceCode: StateFlow<BaiduOAuthClient.DeviceCodeResult?> = _baiduDeviceCode.asStateFlow()

    /** 网盘目录浏览 */
    private val _netdiskCurrentDir = MutableStateFlow(BaiduNetdiskConfig.APP_DIR)
    val netdiskCurrentDir: StateFlow<String> = _netdiskCurrentDir.asStateFlow()
    private val _netdiskDirFiles = MutableStateFlow<List<BaiduFile>>(emptyList())
    val netdiskDirFiles: StateFlow<List<BaiduFile>> = _netdiskDirFiles.asStateFlow()
    /** 网盘根目录是否已从配置同步过（防止 refreshBaiduConnectionState 每次重置浏览位置） */
    private var netdiskDirSynced = false
    private val _netdiskIsLoading = MutableStateFlow(false)
    val netdiskIsLoading: StateFlow<Boolean> = _netdiskIsLoading.asStateFlow()

    /** 网盘搜索 */
    private val _netdiskSearchResults = MutableStateFlow<List<Song>>(emptyList())
    val netdiskSearchResults: StateFlow<List<Song>> = _netdiskSearchResults.asStateFlow()
    private val _netdiskSearchKeyword = MutableStateFlow("")
    val netdiskSearchKeyword: StateFlow<String> = _netdiskSearchKeyword.asStateFlow()

    /** 索引状态 */
    private val _baiduIndexScanned = MutableStateFlow(0)
    val baiduIndexScanned: StateFlow<Int> = _baiduIndexScanned.asStateFlow()
    private val _baiduIndexScanning = MutableStateFlow(false)
    val baiduIndexScanning: StateFlow<Boolean> = _baiduIndexScanning.asStateFlow()
    private val _baiduIndexLastSync = MutableStateFlow(0L)
    val baiduIndexLastSync: StateFlow<Long> = _baiduIndexLastSync.asStateFlow()

    // APIC 后台提取进度
    private val _baiduApicExtracting = MutableStateFlow(false)
    val baiduApicExtracting: StateFlow<Boolean> = _baiduApicExtracting.asStateFlow()
    private val _baiduApicExtracted = MutableStateFlow(0)
    val baiduApicExtracted: StateFlow<Int> = _baiduApicExtracted.asStateFlow()
    private val _baiduApicTotal = MutableStateFlow(0)
    val baiduApicTotal: StateFlow<Int> = _baiduApicTotal.asStateFlow()

    /** API 错误回调注册（由 MainViewModel 在构造期注入 errno=-6 分支） */
    var onBaiduApiError: ((Int, String) -> Unit)? = null

    /** 认证失败（errno=-6）：设 Failed 状态（由 MainViewModel 的 onApiError 回调转发） */
    fun onBaiduAuthFailed(desc: String) {
        _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
    }

    private var deviceCodePollJob: kotlinx.coroutines.Job? = null

    /** 异步刷新连接状态（初始化与开关切换后调用；T2 第一批：getBaiduConfigSync → baiduConfigFlow.first()） */
    suspend fun refreshBaiduConnectionState() {
        val cfg = prefs.baidu.baiduConfigFlow.first()
        val prevState = _baiduConnectionState.value
        _baiduConnectionState.value = when {
            !cfg.isActive -> BaiduConnectionState.Off
            // 当前正在验证、已失败或目录缺失时保留，不被 "tokens 存在" 覆盖回 LoggedIn
            prevState is BaiduConnectionState.Connecting -> prevState
            prevState is BaiduConnectionState.Failed -> prevState
            prevState is BaiduConnectionState.DirMissing -> prevState
            // 有 token 但未验证时，先设 Connecting 再异步验证，不直接设 LoggedIn
            cfg.tokens != null -> BaiduConnectionState.Connecting
            else -> BaiduConnectionState.Off
        }
        AppLog.d("BaiduAuth", "refreshBaiduConnectionState: isActive=${cfg.isActive}, hasTokens=${cfg.tokens != null}, $prevState -> ${_baiduConnectionState.value}")
        if (cfg.isActive) {
            // 仅首次启用/登录时同步根目录到配置值；之后保留用户浏览位置，切换页面不重置
            if (!netdiskDirSynced) {
                _netdiskCurrentDir.value = cfg.musicRootDir.ifBlank { BaiduNetdiskConfig.APP_DIR }
                netdiskDirSynced = true
            }
            _baiduIndexLastSync.value = baiduIndexCache.load()?.lastSyncAt ?: 0L
            // 有 token 且当前是 Connecting（刚从 Off/LoggedIn 转来）→ 异步验证
            if (cfg.tokens != null && _baiduConnectionState.value is BaiduConnectionState.Connecting) {
                verifyBaiduTokenAsync()
            }
        }
        // 百度连接状态变化可能影响合并数据（启用/停用百度源）
        onMergedDataInvalidated?.invoke()
        // 通知 NasMusicApp 运行时注册/注销百度 service
        nasMusicApp.refreshBaiduServiceRegistration()
    }

    /** 异步验证百度 token 有效性：调 listDir(APP_DIR) 检查 errno */
    private fun verifyBaiduTokenAsync() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val result = baiduApi.listDir(BaiduNetdiskConfig.APP_DIR)
                    if (result.errno == -9) {
                        // APP_DIR 不存在，尝试自动创建
                        AppLog.i("BaiduAuth", "verifyBaiduTokenAsync: APP_DIR not found (errno=-9), auto-creating...")
                        val createErrno = baiduApi.createDir(BaiduNetdiskConfig.APP_DIR)
                        if (createErrno == 0 || createErrno == -8) {
                            AppLog.i("BaiduAuth", "verifyBaiduTokenAsync: createDir returned errno=$createErrno, retrying listDir...")
                            val retryResult = baiduApi.listDir(BaiduNetdiskConfig.APP_DIR)
                            if (retryResult.errno != 0) {
                                val desc = BaiduNetdiskConfig.describeErrno(retryResult.errno)
                                _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                                AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: retry after create failed errno=${retryResult.errno}")
                            } else {
                                // APP_DIR 创建成功，检查用户音乐根目录是否存在
                                checkMusicRootDirAfterVerify()
                            }
                        } else {
                            val desc = "目录不存在且创建失败 (errno=$createErrno)"
                            _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                            AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: createDir failed errno=$createErrno")
                        }
                    } else if (result.errno == -6) {
                        // access_token 无效
                        val desc = BaiduNetdiskConfig.describeErrno(result.errno)
                        _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                        AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: errno=-6 (auth failed), set state=Failed")
                    } else if (result.errno != 0) {
                        // 其他 API 错误（非认证），token 本身有效，按登录处理
                        val desc = BaiduNetdiskConfig.describeErrno(result.errno)
                        AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: errno=${result.errno} ($desc), not auth-related, treating as logged in")
                        checkMusicRootDirAfterVerify()
                    } else {
                        // APP_DIR 存在，检查用户音乐根目录
                        checkMusicRootDirAfterVerify()
                    }
                } catch (e: Exception) {
                    _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                    AppLog.w("BaiduAuth", "verifyBaiduTokenAsync: network error, fallback to LoggedIn: ${e.message}")
                    triggerBaiduIndexScanIfNeeded()
                }
            }
        }
    }

    /** 验证 token 有效后，检查用户配置的音乐根目录是否存在（T2 第二批：suspend，读取不再阻塞） */
    private suspend fun checkMusicRootDirAfterVerify() {
        val musicRoot = prefs.baidu.getBaiduMusicRootDir()
        // 如果音乐根目录就是 APP_DIR 本身，不需要额外检查
        if (musicRoot == BaiduNetdiskConfig.APP_DIR) {
            onVerifyBaiduSuccess()
            return
        }
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    val result = baiduApi.listDir(musicRoot)
                    if (result.errno == -9) {
                        // 音乐根目录不存在：auth 没问题，但目录需要重新设置
                        AppLog.i("BaiduAuth", "checkMusicRootDirAfterVerify: musicRootDir='$musicRoot' not found (errno=-9), set state=DirMissing")
                        _baiduConnectionState.value = BaiduConnectionState.DirMissing
                    } else {
                        onVerifyBaiduSuccess()
                    }
                } catch (e: Exception) {
                    // 网络错误不影响判定，按登录处理
                    AppLog.w("BaiduAuth", "checkMusicRootDirAfterVerify: network error, fallback to LoggedIn: ${e.message}")
                    onVerifyBaiduSuccess()
                }
            }
        }
    }

    /** 百度验证成功后：设 LoggedIn + 修正旧根目录 + 触发索引扫描（T2 第二批：suspend） */
    private suspend fun onVerifyBaiduSuccess() {
        _baiduConnectionState.value = BaiduConnectionState.LoggedIn
        AppLog.d("BaiduAuth", "onVerifyBaiduSuccess: set state=LoggedIn")
        // 沙箱策略修正：如果用户保存的根目录不在 /apps/NASMusicTV 下，自动修正
        val savedRoot = prefs.baidu.getBaiduMusicRootDir()
        if (!savedRoot.startsWith(BaiduNetdiskConfig.APP_DIR)) {
            AppLog.i("BaiduAuth", "onVerifyBaiduSuccess: musicRootDir='$savedRoot' outside sandbox, resetting to ${BaiduNetdiskConfig.APP_DIR}")
            prefs.baidu.setBaiduMusicRootDir(BaiduNetdiskConfig.APP_DIR)
            _netdiskCurrentDir.value = BaiduNetdiskConfig.APP_DIR
        }
        triggerBaiduIndexScanIfNeeded()
    }

    /** 设置百度源总开关 */
    fun setBaiduEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.baidu.setBaiduEnabled(enabled)
            refreshBaiduConnectionState()
        }
    }

    /** 启动设备码授权流程：请求设备码并开始轮询 */
    fun startBaiduDeviceCodeFlow() {
        AppLog.d("BaiduAuth", "startBaiduDeviceCodeFlow: called, current state=${_baiduConnectionState.value}")
        viewModelScope.launch {
            _baiduConnectionState.value = BaiduConnectionState.Connecting
            AppLog.d("BaiduAuth", "startBaiduDeviceCodeFlow: set state=Connecting, requesting device code...")
            val code = baiduOAuth.requestDeviceCode()
            if (code == null) {
                AppLog.w("BaiduAuth", "startBaiduDeviceCodeFlow: requestDeviceCode returned null")
                _baiduConnectionState.value = BaiduConnectionState.Failed(getApplication<Application>().getString(R.string.baidu_get_device_code_failed))
                return@launch
            }
            AppLog.d("BaiduAuth", "startBaiduDeviceCodeFlow: got device code=${code.userCode}, expiresIn=${code.expiresIn}s, starting poll")
            _baiduDeviceCode.value = code
            pollDeviceCode(code)
        }
    }

    /** 取消设备码轮询 */
    fun cancelBaiduDeviceCode() {
        AppLog.d("BaiduAuth", "cancelBaiduDeviceCode: called, current state=${_baiduConnectionState.value}")
        deviceCodePollJob?.cancel()
        deviceCodePollJob = null
        _baiduDeviceCode.value = null
        if (_baiduConnectionState.value is BaiduConnectionState.Connecting) {
            _baiduConnectionState.value = BaiduConnectionState.Off
            AppLog.d("BaiduAuth", "cancelBaiduDeviceCode: was Connecting, set state=Off")
        }
    }

    private fun pollDeviceCode(code: BaiduOAuthClient.DeviceCodeResult) {
        deviceCodePollJob?.cancel()
        deviceCodePollJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            AppLog.d("BaiduAuth", "pollDeviceCode: start, deadline=${code.expiresIn}s, interval=${code.interval}s")
            while (System.currentTimeMillis() < deadline && isActive()) {
                when (val r = baiduOAuth.pollDeviceToken(code.deviceCode)) {
                    is BaiduOAuthClient.PollResult.Success -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: Success, verifying token before setting LoggedIn...")
                        _baiduDeviceCode.value = null
                        // 先设 Connecting，异步验证 token 后再决定 LoggedIn/Failed
                        _baiduConnectionState.value = BaiduConnectionState.Connecting
                        nasMusicApp.refreshBaiduServiceRegistration()
                        verifyBaiduTokenAsync()
                        return@launch
                    }
                    BaiduOAuthClient.PollResult.Pending -> {
                        kotlinx.coroutines.delay(interval)
                    }
                    BaiduOAuthClient.PollResult.Declined -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: Declined -> Failed")
                        _baiduConnectionState.value = BaiduConnectionState.Failed(getApplication<Application>().getString(R.string.baidu_user_declined))
                        _baiduDeviceCode.value = null
                        return@launch
                    }
                    is BaiduOAuthClient.PollResult.SlowDown -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: SlowDown, newInterval=${r.newInterval}s")
                        interval = r.newInterval * 1000L
                        kotlinx.coroutines.delay(interval)
                    }
                    is BaiduOAuthClient.PollResult.Failed -> {
                        AppLog.d("BaiduAuth", "pollDeviceCode: Failed -> ${r.message}")
                        _baiduConnectionState.value = BaiduConnectionState.Failed(r.message)
                        _baiduDeviceCode.value = null
                        return@launch
                    }
                }
            }
            AppLog.d("BaiduAuth", "pollDeviceCode: timeout -> Failed")
            _baiduConnectionState.value = BaiduConnectionState.Failed(getApplication<Application>().getString(R.string.baidu_auth_timeout))
            _baiduDeviceCode.value = null
        }
    }

    private suspend fun isActive(): Boolean =
        kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    /** 登出 */
    fun logoutBaidu() {
        AppLog.d("BaiduAuth", "logoutBaidu: called, current state=${_baiduConnectionState.value}")
        viewModelScope.launch {
            baiduOAuth.logout()
            nasMusicApp.refreshBaiduServiceRegistration()
            _baiduConnectionState.value = BaiduConnectionState.Off
            AppLog.d("BaiduAuth", "logoutBaidu: set state=Off")
        }
    }

    // ---- 网盘目录浏览 ----

    fun listBaiduDir(dir: String) {
        AppLog.d("BaiduAuth", "listBaiduDir: dir=$dir, current state=${_baiduConnectionState.value}")
        _netdiskCurrentDir.value = dir
        _netdiskIsLoading.value = true
        viewModelScope.launch {
            try {
                val result = baiduApi.listDir(dir)
                AppLog.d("BaiduAuth", "listBaiduDir: got ${result.files.size} files, hasMore=${result.hasMore}, errno=${result.errno}")
                // API 返回错误时设置 Failed 状态（不再依赖回调）
                if (result.errno != 0) {
                    val desc = BaiduNetdiskConfig.describeErrno(result.errno)
                    AppLog.w("BaiduAuth", "listBaiduDir: errno=${result.errno} ($desc), setting state=Failed")
                    _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                }
                _netdiskDirFiles.value = result.files
            } catch (e: Exception) {
                AppLog.e("NetworkMusicViewModel", "listBaiduDir error", e)
                showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_load_dir_error, e.message?.take(40)))
                _netdiskDirFiles.value = emptyList()
            } finally {
                _netdiskIsLoading.value = false
            }
        }
    }

    /**
     * 列出网盘指定路径下的文件（供 [com.nasmusic.tv.ui.components.BaiduDirPickerDialog] 目录树选择使用）。
     * 异常直接上抛，由对话框展示失败态并允许重试。
     */
    suspend fun listBaiduDirs(path: String): List<BaiduFile> =
        baiduApi.listDir(path).files

    fun navigateBaiduDirUp() {
        val current = _netdiskCurrentDir.value
        if (current == "/" || current.isBlank()) return
        val parent = current.substringBeforeLast('/').ifBlank { "/" }
        listBaiduDir(parent)
    }

    fun enterBaiduDir(name: String) {
        val base = _netdiskCurrentDir.value.trimEnd('/')
        listBaiduDir("$base/$name")
    }

    // ---- 网盘搜索 ----

    fun searchBaidu(keyword: String) {
        _netdiskSearchKeyword.value = keyword
        if (keyword.isBlank()) {
            _netdiskSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _netdiskIsLoading.value = true
            try {
                val rootDir = prefs.baidu.getBaiduMusicRootDir().ifBlank { BaiduNetdiskConfig.APP_DIR }
                val files = baiduApi.searchAudio(keyword, dir = rootDir)
                _netdiskSearchResults.value = files.map { it.toSong() }
            } catch (e: Exception) {
                AppLog.e("NetworkMusicViewModel", "searchBaidu error", e)
                _netdiskSearchResults.value = emptyList()
            } finally {
                _netdiskIsLoading.value = false
            }
        }
    }

    fun clearNetdiskSearch() {
        _netdiskSearchKeyword.value = ""
        _netdiskSearchResults.value = emptyList()
    }

    /** 播放全部网盘搜索结果 */
    fun playAllNetdiskSearch() {
        val results = _netdiskSearchResults.value
        if (results.isEmpty()) {
            showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_search_no_results))
            return
        }
        onPlayQueue?.invoke(results, 0)
    }

    /**
     * 播放当前目录（含子目录）的全部音频。
     *
     * 优先走本地索引（毫秒级）；索引未覆盖/缺失时回退递归 BFS 扫描网盘。
     * @param onPlayAll 收集完成后回调（播放队列由调用方导航到 NowPlaying）
     */
    fun playAllNetdiskDir(dir: String, onPlayAll: (List<Song>) -> Unit) {
        viewModelScope.launch {
            val songs = collectAudioInDir(dir)
            if (songs.isEmpty()) {
                showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_no_audio_files))
            } else {
                onPlayAll(songs)
            }
        }
    }

    /** 收集目录（含子目录）内全部音频：优先索引，回退 API 递归扫描 */
    private suspend fun collectAudioInDir(dir: String): List<Song> {
        val base = dir.trimEnd('/')
        val index = baiduIndexCache.load()
        val indexed = index?.entries?.filter {
            it.category == BaiduNetdiskConfig.CATEGORY_AUDIO &&
                (it.path == base || it.path.startsWith("$base/"))
        }?.map { it.toSong() }
        if (indexed != null && indexed.isNotEmpty()) return indexed

        // 回退：BFS 递归扫描（索引未建或未覆盖该目录时）
        val songs = mutableListOf<Song>()
        val queue = ArrayDeque<String>()
        val visited = HashSet<String>()
        queue.addLast(base)
        visited.add(base)
        try {
            while (queue.isNotEmpty()) {
                val dirPath = queue.removeFirst()
                val result = baiduApi.listDir(dirPath)
                for (f in result.files) {
                    if (f.isDir) {
                        if (visited.add(f.path)) queue.addLast(f.path)
                    } else if (BaiduPanApi.isAudioFile(f.serverFilename, f.category)) {
                        songs.add(f.toSong())
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("NetworkMusicViewModel", "collectAudioInDir error", e)
        }
        return songs
    }

    // ---- 索引管理 ----

    suspend fun triggerBaiduIndexScanIfNeeded() {
        val index = baiduIndexCache.load()
        val root = prefs.baidu.getBaiduMusicRootDir().ifBlank { BaiduNetdiskConfig.APP_DIR }
        if (index == null || index.rootPath != root) {
            rebuildBaiduIndex()
        } else {
            _baiduIndexScanned.value = index.entries.size
            _baiduIndexLastSync.value = index.lastSyncAt
        }
    }

    fun rebuildBaiduIndex() {
        if (_baiduIndexScanning.value) return
        viewModelScope.launch {
            _baiduIndexScanning.value = true
            _baiduIndexScanned.value = 0
            val root = prefs.baidu.getBaiduMusicRootDir().ifBlank { BaiduNetdiskConfig.APP_DIR }
            val callback = object : BaiduFileIndexCache.ProgressCallback {
                override fun onProgress(scanned: Int) { _baiduIndexScanned.value = scanned }
                override fun onComplete(total: Int) {
                    _baiduIndexScanned.value = total
                    _baiduIndexLastSync.value = System.currentTimeMillis()
                    onMergedDataInvalidated?.invoke()
                }
                override fun onFailed(message: String) {
                    showMessage?.invoke(getApplication<Application>().getString(R.string.netdisk_index_scan_interrupted, message))
                }
            }
            try {
                val mvDir = prefs.baidu.getBaiduMvDir()
                baiduIndexCache.fullScan(root, baiduApi, mvDir, callback)
                // 扫描成功后，对 coverUrl 为空的音频条目启动 APIC 后台提取。
                // listall+web=1 返回的 thumbs 仅对图片/视频有效，音频文件几乎都为 null。
                val index = baiduIndexCache.load()
                val pendingCovers = index?.entries?.count {
                    it.coverUrl == null && it.category != BaiduNetdiskConfig.CATEGORY_VIDEO
                } ?: 0
                if (pendingCovers > 0) {
                    AppLog.i("NetworkMusicViewModel", "rebuildBaiduIndex: $pendingCovers entries pending cover extraction, starting APIC")
                    startApicExtraction()
                }
            } catch (e: Exception) {
                AppLog.e("NetworkMusicViewModel", "rebuildBaiduIndex error", e)
            } finally {
                _baiduIndexScanning.value = false
            }
        }
    }

    /** 后台提取 APIC 封面（扫描完成后自动触发，也可手动调用） */
    private fun startApicExtraction() {
        if (_baiduApicExtracting.value) return
        viewModelScope.launch {
            _baiduApicExtracting.value = true
            _baiduApicExtracted.value = 0
            _baiduApicTotal.value = 0
            try {
                val callback = object : BaiduFileIndexCache.ApicProgressCallback {
                    override fun onProgress(extracted: Int, total: Int) {
                        _baiduApicExtracted.value = extracted
                        _baiduApicTotal.value = total
                    }
                    override fun onComplete(totalExtracted: Int) {
                        _baiduApicExtracted.value = totalExtracted
                        _baiduApicTotal.value = totalExtracted
                        if (totalExtracted > 0) onMergedDataInvalidated?.invoke()
                    }
                    override fun onFailed(message: String) {
                        AppLog.e("NetworkMusicViewModel", "APIC extraction failed: $message")
                    }
                }
                baiduIndexCache.extractApicInBackground(
                    coverProvider = nasMusicApp.baiduCoverProvider,
                    concurrency = 5,
                    batchSize = 20,
                    onProgress = callback
                )
            } catch (e: Exception) {
                AppLog.e("NetworkMusicViewModel", "startApicExtraction error", e)
            } finally {
                _baiduApicExtracting.value = false
            }
        }
    }

    // ---- 配置项 ----

    fun setBaiduMusicRootDir(dir: String) {
        _netdiskCurrentDir.value = dir
        viewModelScope.launch {
            prefs.baidu.setBaiduMusicRootDir(dir)
            // 根目录变更后旧索引失效，触发重建
            rebuildBaiduIndex()
            // 如果之前是 DirMissing，重新验证新目录
            if (_baiduConnectionState.value is BaiduConnectionState.DirMissing) {
                checkMusicRootDirAfterVerify()
            }
        }
    }

    fun setBaiduMvDir(dir: String?) {
        viewModelScope.launch {
            prefs.baidu.setBaiduMvDir(dir)
        }
    }

    /** 加载索引中的歌曲（供 NetdiskScreen 首页展示已扫描曲库） */
    fun loadBaiduIndexedSongs(): List<Song> =
        baiduIndexCache.load()?.entries?.map { it.toSong() } ?: emptyList()

    /** 启动期恢复百度索引状态（由 MainViewModel 构造期调用，替代原 init 逻辑） */
    fun restoreBaiduIndexOnStart(onIndexLoaded: (com.nasmusic.tv.data.model.BaiduFileIndex) -> Unit) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                // P1#12 修复（2026-09-13）：用 baiduConfigFlow.first() 替代 getBaiduConfigSync()
                // 避免 runBlocking+Dispatchers.IO 在 Default 线程池上阻塞其他解析/计算协程。
                val baiduCfg = prefs.baidu.baiduConfigFlow.first()
                AppLog.d("BaiduAuth", "init: cfg.isActive=${baiduCfg.isActive}, tokens=${baiduCfg.tokens != null}")
                if (baiduCfg.isActive) {
                    val savedIndex = baiduIndexCache.load()
                    if (savedIndex != null && savedIndex.entries.isNotEmpty()) {
                        _baiduIndexScanned.value = savedIndex.entries.size
                        _baiduIndexLastSync.value = savedIndex.lastSyncAt
                    }
                    // 有 token 时先设 Connecting（验证中），避免闪烁"已登录"再变"授权失败"
                    if (baiduCfg.tokens != null) {
                        _baiduConnectionState.value = BaiduConnectionState.Connecting
                        AppLog.d("BaiduAuth", "init: set state=Connecting, verifying token...")
                        try {
                            val verifyResult = baiduApi.listDir(BaiduNetdiskConfig.APP_DIR)
                            if (verifyResult.errno != 0) {
                                // 直接从结果读取 errno，不依赖回调
                                val desc = BaiduNetdiskConfig.describeErrno(verifyResult.errno)
                                _baiduConnectionState.value = BaiduConnectionState.Failed(desc)
                                AppLog.w("BaiduAuth", "init: verify failed errno=${verifyResult.errno}, set state=Failed($desc)")
                            } else {
                                _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                                AppLog.d("BaiduAuth", "init: verify OK, set state=LoggedIn, ${verifyResult.files.size} items in /")
                            }
                        } catch (e: Exception) {
                            // 网络异常等，保守设 LoggedIn（可能是临时网络问题，不是 token 失效）
                            _baiduConnectionState.value = BaiduConnectionState.LoggedIn
                            AppLog.w("BaiduAuth", "init: verify network error, fallback to LoggedIn: ${e.message}")
                        }
                    } else {
                        _baiduConnectionState.value = BaiduConnectionState.Off
                        AppLog.d("BaiduAuth", "init: no tokens, set state=Off")
                    }
                    // 百度索引有数据时通知加载
                    if (savedIndex != null && savedIndex.entries.isNotEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            onIndexLoaded(savedIndex)
                        }
                    }
                }
            }
        }
    }
}
