package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.photo.ExternalFilePhotoSource
import com.nasmusic.tv.backend.photo.JellyfinPhotoSource
import com.nasmusic.tv.backend.photo.MediaStorePhotoSource
import com.nasmusic.tv.backend.photo.PhotoSource
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.backend.photo.PhotoWallAccessPolicy
import com.nasmusic.tv.backend.photo.SafDirectoryPolicy
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.PermissionHelper
import com.nasmusic.tv.util.PermissionHelper.PhotoPermissionState
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.photo.PhotoWallAvailability
import com.nasmusic.tv.visualizer.photo.PhotoWallController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 照片墙授权的用户提示（§9）
 *
 * ⚠️ **数据层不产出面向用户的文案**（项目硬约定）⇒ 这里只带 string 资源 id，
 * 由 `MainViewModel` 取文案后走既有的 `errorMessage` 顶部提示通道。
 * 放在 viewmodel 层而不是 `backend/photo` 层，正是因为它引用了 `R`。
 */
enum class PhotoAccessNotice(@StringRes val messageRes: Int) {
    /** 用户在系统对话框里点了「不允许」 */
    GALLERY_DENIED(R.string.photo_wall_notice_gallery_denied),

    /** 授权在系统设置里被撤销，回到应用后开关被回弹（§6.2） */
    GALLERY_REVOKED(R.string.photo_wall_notice_gallery_revoked),

    /** Android 14+ 拿到「仅选择照片」的部分授权（§9.6） */
    GALLERY_PARTIAL(R.string.photo_wall_notice_gallery_partial),

    /** SAF 目录的持久授权已失效（系统回收 / 用户清除） */
    DIRECTORY_REVOKED(R.string.photo_wall_notice_dir_revoked),

    /** 选到了内部存储（我们自己加的硬限制，§6.3） */
    DIRECTORY_INTERNAL(R.string.photo_wall_notice_dir_internal),

    /** 目录标识异常 */
    DIRECTORY_MALFORMED(R.string.photo_wall_notice_dir_malformed),

    /** 目录标识为空 */
    DIRECTORY_EMPTY(R.string.photo_wall_notice_dir_empty),

    /** 当前设备没有可用的系统文件选择器（电视 ROM 可能裁剪了 DocumentsUI） */
    DIRECTORY_UNAVAILABLE(R.string.photo_wall_notice_dir_unavailable),
}

/**
 * 全屏可视化舞台的 ViewModel。
 *
 * 状态照 `VocalSeparationViewModel` 的 `showKaraoke` 模式：
 * 不新增 Screen 枚举，而是用 StateFlow<Boolean> 驱动的全屏覆盖层。
 */
class VisualizerViewModel(
    private val app: Application,
    private val playerManager: PlayerManager,
    private val prefs: AppPreferences
) : ViewModel() {

    private val _showVisualizer = MutableStateFlow(false)
    val showVisualizer: StateFlow<Boolean> = _showVisualizer.asStateFlow()

    private val _theme = MutableStateFlow(VisualizerTheme.Default)
    val theme: StateFlow<VisualizerTheme> = _theme.asStateFlow()

    private val _quality = MutableStateFlow(VisualQuality.Default)
    val quality: StateFlow<VisualQuality> = _quality.asStateFlow()

    /**
     * 照片墙可用性（三来源开关之「或」，§7.4）。
     *
     * ⛔ 它是 `PHOTO_WALL` 是否出现在效果列表里的**唯一判据**，且必须与
     * `AppRoot.VisualizerOverlay` 传给 `VisualizerStage` 的值**完全一致**
     * （指示器与左右键切到的是同一份列表，否则会出现「指示器上没有、却切得到」）。
     * ⇒ 因此收口在这里，由 UI 层 `collectAsState()` 订阅，而不是各自算一遍。
     */
    private val _photoWallAvailable = MutableStateFlow(false)
    val photoWallAvailable: StateFlow<Boolean> = _photoWallAvailable.asStateFlow()

    // ══════════════════════════════════════════════════════════════════════
    //  照片访问授权（阶段 9，§9）
    //
    //  ⛔ 两条最容易做错的规则（§6.2 / §9.6）：
    //  ① 授权**由图库开关驱动**，不是 onCreate 无条件请求；
    //     拒绝 ⇒ 开关自动回弹为关（不能留一个「开着但没数据」的开关）。
    //  ② 权限状态**绝不落盘**（官方明确禁止存 SharedPreferences / DataStore）——
    //     只每次现查 `checkSelfPermission`，否则用户在系统设置里撤销后
    //     本地标志仍为 true，UI 显示与实际不一致。
    // ══════════════════════════════════════════════════════════════════════

    /** 当前照片权限三态（§9.6）；初值即现查一次，不读任何缓存 */
    private val _photoPermissionState =
        MutableStateFlow(PermissionHelper.photoPermissionState(app))
    val photoPermissionState: StateFlow<PhotoPermissionState> = _photoPermissionState.asStateFlow()

    /** 最近一次目录选择被拒的原因（`null` = 没有待提示的拒绝） */
    private val _photoDirectoryReject = MutableStateFlow<SafDirectoryPolicy.RejectReason?>(null)
    val photoDirectoryReject: StateFlow<SafDirectoryPolicy.RejectReason?> =
        _photoDirectoryReject.asStateFlow()

    /**
     * 授权相关提示（单向事件流）
     *
     * ⚠️ `replay = 0`：提示是**事件**不是状态，晚订阅者不该重看旧提示。
     * `extraBufferCapacity` 是 `tryEmit` 能成功的前提（0 缓冲时无订阅者必失败）。
     * 本流由 `MainViewModel.init` 订阅并转发到 `errorMessage`（UI 有消费方）。
     */
    private val _photoAccessNotice = MutableSharedFlow<PhotoAccessNotice>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val photoAccessNotice: SharedFlow<PhotoAccessNotice> = _photoAccessNotice.asSharedFlow()

    /**
     * 系统权限对话框启动器（由 `MainActivity` 注入）
     *
     * ⚠️ ViewModel 无法自己 `registerForActivityResult`（那是 Activity/Compose 的能力），
     * 与 `ExportCoordinator.treePickLauncher` 同款做法。
     * 未注入（如某些测试环境）⇒ 视为拿不到授权，不静默把开关打开。
     */
    var photoPermissionLauncher: (() -> Unit)? = null

    /** SAF 目录选择器启动器（同上） */
    var photoDirectoryLauncher: (() -> Unit)? = null

    // ══════════════════════════════════════════════════════════════════════
    //  照片墙编排（阶段 10，§14.2.5）
    //
    //  ⛔ **构造来源的责任在 ViewModel，不在控制器**：三个来源要拿 `Context` /
    //  `BackendRegistry` / `StorageMonitor`，而控制器刻意不碰这些（见它的 KDoc）。
    //
    //  ⚠️ 「图库」**只在手机**上放进 sources 表（§6.2：电视没有系统相册）。
    //     不放进来 ⇒ 聚合器在 `PhotoSourceKind.entries` 循环里直接 `continue`，
    //     既不调 `status()` 也不调 `listPhotos()`。
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 是否电视。
     *
     * ⚠️ 判据与 `AppPreferences.isTelevisionDevice` / `FocusableSurface.isTVDevice()`
     * `MainActivity` 等处**完全一致**（`leanback` 或 `type.television`）——
     * 判据不一致会出现「按电视给的默认值、按手机渲染的设置页」这类错位。
     * ⚠️ `by lazy`：`hasSystemFeature` 在 API 22 上可能是一次 binder 调用。
     */
    private val isTVDevice: Boolean by lazy {
        app.packageManager.run {
            hasSystemFeature("android.software.leanback") ||
                hasSystemFeature("android.hardware.type.television")
        }
    }

    /** 照片来源表（键 = [PhotoSourceKind]；**没有的键** = 本平台不存在该来源） */
    private val photoWallSources: Map<PhotoSourceKind, PhotoSource> by lazy {
        val nasApp = app as NasMusicApp
        val out = LinkedHashMap<PhotoSourceKind, PhotoSource>(PhotoSourceKind.entries.size)
        if (!isTVDevice) out[PhotoSourceKind.GALLERY] = MediaStorePhotoSource(app)
        out[PhotoSourceKind.EXTERNAL] = ExternalFilePhotoSource(
            context = app,
            // 电视：自动探测已挂载的 USB / SD 卡（`StorageMonitor` 的当前快照）
            fileRootsProvider = {
                nasApp.storageMonitor.storageDevices.value
                    .filter { it.isMounted }
                    .map { it.path }
            },
            // 手机：用户通过 SAF 选中的目录（留空则走上面的文件遍历路线）
            safTreeUriProvider = { latestSettings.photoWallDirUri.takeIf { it.isNotBlank() } },
            commonDirsOnlyProvider = { latestSettings.photoWallCommonDirsOnly },
        )
        out[PhotoSourceKind.JELLYFIN] = JellyfinPhotoSource(
            adapterProvider = { nasApp.backendRegistry.getAdapter() },
        )
        out
    }

    /**
     * 照片墙编排器（由 `VisualizerStage` 的帧循环 / 绘制块各接一行）。
     *
     * ⚠️ 用 `by lazy` 但**实际在 [init] 的 `appSettings` 收集器里就会被触发**（要推设置）；
     *     构造成本只等于几个纯 Kotlin 对象（`PhotoBuffer` 是等到第一帧知道画布尺寸才建的）。
     */
    val photoWall: PhotoWallController by lazy {
        PhotoWallController(sources = photoWallSources)
    }

    /** 最近一次发射的 `AppSettings`（供 `onResume` 同步读取，避免再挂一个订阅） */
    @Volatile private var latestSettings: AppSettings = AppSettings()

    /** 回弹写入尚未落盘时的去重标志，避免同一轮里重复提示 */
    private var galleryRollbackPending = false

    /** 音频帧（来自 SpectrumRepository 单例） */
    val frame: AudioFrame get() = playerManager.spectrumRepository.frame

    private val _cover = MutableStateFlow<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val cover: StateFlow<androidx.compose.ui.graphics.ImageBitmap?> = _cover.asStateFlow()

    private val _palette = MutableStateFlow(com.nasmusic.tv.visualizer.CoverPalette.Fallback)
    val palette: StateFlow<com.nasmusic.tv.visualizer.CoverPalette> = _palette.asStateFlow()

    private val paletteProvider = com.nasmusic.tv.visualizer.CoverPaletteProvider()

    /** 当前已加载封面对应的 key，避免重复加载。
     *  P1#10 修复（2026-09-13）：主线程写(63行)/IO 读(82行) 跨线程,加 @Volatile 保证可见性。 */
    @Volatile private var loadedCoverKey: String? = null

    /**
     * 异步加载封面并取色（技法 T5）。
     * 在 IO 线程执行，未就绪时渲染层使用 [CoverPalette.Fallback]，绝不阻塞。
     */
    fun loadCover(url: String?, key: String?) {
        if (key == null || key == loadedCoverKey) {
            if (key == null) { _cover.value = null; _palette.value = com.nasmusic.tv.visualizer.CoverPalette.Fallback; loadedCoverKey = null }
            return
        }
        loadedCoverKey = key
        val requestedKey = key
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ib: androidx.compose.ui.graphics.ImageBitmap? = runCatching {
                // 必须用 Coil 全局单例：NasMusicApp.newImageLoader() 注入了百度 dlink
                // UA 拦截器，coil.ImageLoader(app) 会新建无配置实例 → 百度网盘封面 403，
                // 且该实例从不 shutdown，泄漏线程池与缓存（同 MainViewModel 的教训）。
                val loader = coil.Coil.imageLoader(app)
                val req = coil.request.ImageRequest.Builder(app)
                    .data(url)
                    .allowHardware(false)          // 关闭硬件位图，否则无法取色
                    .build()
                val drawable = loader.execute(req).drawable
                val bmp = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                bmp?.asImageBitmap()
            }.getOrNull()

            // 快速切歌时先发的任务可能后返回：只认“仍是当前 key”的结果，
            // 否则会用上一首的封面/配色覆盖当前歌曲。
            if (requestedKey != loadedCoverKey) return@launch

            if (ib == null) {
                _cover.value = null
                _palette.value = com.nasmusic.tv.visualizer.CoverPalette.Fallback
                return@launch
            }
            _cover.value = ib
            _palette.value = paletteProvider.obtain(requestedKey, ib)
        }
    }

    init {
        viewModelScope.launch {
            prefs.appSettings.collect { s ->
                latestSettings = s
                _theme.value = s.visualizerTheme
                _quality.value = s.visualizerQuality
                val available = PhotoWallAvailability.isAvailable(
                    galleryEnabled = s.photoWallGalleryEnabled,
                    externalEnabled = s.photoWallExternalEnabled,
                    jellyfinEnabled = s.photoWallJellyfinEnabled,
                )
                _photoWallAvailable.value = available

                // §7.4 实现要点 3：三来源全关且当前正显示照片墙 ⇒ **平滑切回**默认效果。
                // 不需要额外动画代码 —— 主题一变，`RendererSwapper` 的既有 crossfade 就接管了。
                // ⚠️ 这条同时兜住「冷启动时存档主题是 PHOTO_WALL 但开关已全关」的情况。
                if (!available && _theme.value == VisualizerTheme.PHOTO_WALL) {
                    _theme.value = VisualizerTheme.Default
                    persist()
                }

                // §6.2：冷启动时「存档里图库开着、权限却已被撤销」也要回弹 ——
                // 与 onResume 的刷新是**两条互补的路**（DataStore 与 onResume 谁先到不确定）。
                applyGalleryRollback(s, _photoPermissionState.value)

                // 照片墙：设置 / 画质推给编排器。
                // ⛔ 它自带「要不要重扫」的判断 ⇒ 每改一个不相干的开关都不会重扫 U 盘。
                photoWall.onSettingsChanged(
                    settings = s,
                    quality = s.visualizerQuality,
                    sdkInt = Build.VERSION.SDK_INT,
                )
            }
        }

        // 外接存储插拔（§14.6 电视第 5 条「拔 U 盘 → 不崩；缓存清空」）。
        // ⚠️ 插入也要重扫 —— 否则插盘后照片墙还是空的。
        viewModelScope.launch {
            runCatching { (app as NasMusicApp).storageMonitor }
                .onFailure { AppLog.w(TAG, "storageMonitor unavailable: ${it.message}") }
                .getOrNull()?.let { monitor ->
                    merge(monitor.onDeviceMounted, monitor.onDeviceUnmounted).collect {
                        photoWall.onExternalStorageChanged()
                    }
                }
        }
    }

    fun enterVisualizer() {
        _showVisualizer.value = true
    }

    fun exitVisualizer() {
        _showVisualizer.value = false
    }

    /** 当前实际生效的主题名（供 Toast 显示）——用户选中哪个就恒定显示哪个 */
    fun activeThemeName(): String = _theme.value.displayName

fun nextTheme() = step(+1)
    fun prevTheme() = step(-1)

    /**
     * 长按遥控器方向键时系统会连续注入 KeyEvent repeat（约 3-5/s），
     * 每个 repeat 都触发渲染器重建 → 高频 离屏 surface 创建/销毁，
     * 弱 GPU（电视）上实测可导致 native 崩溃。此处节流到 180ms：
     * 只响应「新按键」，忽略 repeat 风暴。
     */
    private var lastStepMs = 0L

    /** 步进切换，自动跳过当前画质不支持的效果 */
    private fun step(dir: Int) {
        val now = System.currentTimeMillis()
        if (now - lastStepMs < SWITCH_DEBOUNCE_MS) return
        lastStepMs = now
        // ⛔ 必须用「过滤后」的列表：三来源开关全关时列表里没有 PHOTO_WALL，
        //   否则左右键会切到一个画不出东西的空效果上（§7.4 实现要点 1）。
        val list = VisualizerTheme.selectable(photoWallAvailable = _photoWallAvailable.value)
        if (list.isEmpty()) return
        val from = list.indexOf(_theme.value).let { if (it < 0) 0 else it }
        for (k in 1..list.size) {
            val idx = (from + dir * k + list.size * k) % list.size
            val cand = list[idx]
            if (_quality.value.supports(cand)) {
                _theme.value = cand
                persist()
                return
            }
        }
    }

    fun setTheme(theme: VisualizerTheme) {
        _theme.value = theme
        persist()
    }

    fun setQuality(q: VisualQuality) {
        _quality.value = q
        // 降档后当前效果可能不再支持 → 回落到默认
        if (!q.supports(_theme.value)) {
            _theme.value = VisualizerTheme.Default
        }
        viewModelScope.launch { prefs.setVisualizerQuality(q) }
    }

    private fun persist() {
        viewModelScope.launch { prefs.setVisualizerTheme(_theme.value) }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  照片访问授权：动作（判据在 PhotoWallAccessPolicy，这里只做「动作」）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 设置页「图库」开关的入口（⛔ 授权的唯一触发点，§6.2）
     *
     * - 关：直接写 false（撤回授权状态由系统管，我们只停用来源）
     * - 开且**已有**权限（含 Android 14+ 部分授权）：直接写 true，不重复弹系统对话框
     * - 开且**被拒**：拉起系统对话框，结果由 [onPhotoPermissionResult] 处理
     *
     * ⚠️ 无论哪条路都**不写任何「已授权」标志**（§9.4）。
     */
    fun setGallerySourceEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { prefs.photoWall.setGalleryEnabled(false) }
            return
        }
        val state = PermissionHelper.photoPermissionState(app)
        _photoPermissionState.value = state
        if (!PhotoWallAccessPolicy.needsPermissionRequest(state)) {
            viewModelScope.launch { prefs.photoWall.setGalleryEnabled(true) }
            return
        }
        requestGalleryPermission()
    }

    /**
     * 拉起系统权限对话框
     *
     * ⚠️ 「重新选择照片」走的是**同一个入口** —— Android 14+ 下再次请求
     * 即唤起系统的 reselection UI（§9.6 规则 3），不需要另写一条链路。
     */
    fun requestGalleryPermission() {
        val launcher = photoPermissionLauncher
        if (launcher == null) {
            _photoAccessNotice.tryEmit(PhotoAccessNotice.GALLERY_DENIED)
            return
        }
        launcher.invoke()
    }

    /**
     * 系统权限对话框返回
     *
     * ⛔ **不看回调给的 `Map<String, Boolean>`，而是重新读一次三态**：
     * Android 14+ 的「仅选择照片」下 `READ_MEDIA_IMAGES` 可能是 granted
     * 却只是**会话级**授权（§9.6），拿回调结果当判据会把部分授权当成完全授权。
     */
    fun onPhotoPermissionResult() {
        val state = PermissionHelper.photoPermissionState(app)
        _photoPermissionState.value = state
        when {
            // 部分授权也是**有效授权** ⇒ 开关保持打开，但要让用户知道只拿到一部分
            state == PhotoPermissionState.PARTIAL -> {
                viewModelScope.launch { prefs.photoWall.setGalleryEnabled(true) }
                _photoAccessNotice.tryEmit(PhotoAccessNotice.GALLERY_PARTIAL)
            }
            PhotoWallAccessPolicy.grantsGalleryAccess(state) -> {
                viewModelScope.launch { prefs.photoWall.setGalleryEnabled(true) }
            }
            // 拒绝 ⇒ 开关回弹为关（不能留「开着但没数据」的开关）
            else -> {
                viewModelScope.launch { prefs.photoWall.setGalleryEnabled(false) }
                _photoAccessNotice.tryEmit(PhotoAccessNotice.GALLERY_DENIED)
            }
        }
    }

    /** 设置页「选择照片目录」入口（SAF） */
    fun requestPhotoDirectoryPick() {
        _photoDirectoryReject.value = null
        val launcher = photoDirectoryLauncher
        if (launcher == null) {
            _photoAccessNotice.tryEmit(PhotoAccessNotice.DIRECTORY_UNAVAILABLE)
            return
        }
        launcher.invoke()
    }

    /**
     * SAF 目录选择结果（§6.3）
     *
     * ⛔ **不能只靠文案拦内部存储**：`ACTION_OPEN_DOCUMENT_TREE` 无法限制可选范围，
     * 用户仍能一路点到内部存储 ⇒ 必须在拿到 URI 后校验卷 ID，落盘前就拒绝。
     *
     * ⚠️ 用户取消（`uri == null`）⇒ 静默返回，不弹提示（取消不是错误）。
     */
    fun onPhotoDirectoryPicked(uri: Uri?) {
        if (uri == null) return

        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val reason = if (docId == null) {
            SafDirectoryPolicy.RejectReason.MALFORMED
        } else {
            SafDirectoryPolicy.rejectReason(docId)
        }
        if (reason != null) {
            _photoDirectoryReject.value = reason
            _photoAccessNotice.tryEmit(reason.toNotice())
            AppLog.w(TAG, "photo dir rejected: $reason (docId=${docId ?: "<null>"})")
            return
        }

        _photoDirectoryReject.value = null
        // ⚠️ 只申请**读**权限：照片墙从不写外接存储（最小权限）
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure {
            AppLog.w(TAG, "takePersistableUriPermission failed: ${it.message}")
        }
        viewModelScope.launch { prefs.photoWall.setDirUri(uri.toString()) }
    }

    /**
     * 回到前台时刷新授权状态（§6.2 / §9.6）
     *
     * ⛔ 官方明确：权限可能在 `onStart` / `onResume` 之间被用户改掉（App 不重启）
     * ⇒ **不能只在启动判一次**，否则「在系统设置里撤销 → 回到应用」不会回弹。
     */
    fun refreshPhotoAccess() {
        val state = PermissionHelper.photoPermissionState(app)
        _photoPermissionState.value = state
        applyGalleryRollback(latestSettings, state)

        // SAF 目录：只查系统持有的持久授权，不自己存「已授权」标志（§9.4）
        val stored = latestSettings.photoWallDirUri
        val persisted = runCatching {
            app.contentResolver.persistedUriPermissions.map { it.uri.toString() }
        }.getOrNull() ?: return

        if (PhotoWallAccessPolicy.shouldClearDirectoryUri(stored, persisted)) {
            viewModelScope.launch { prefs.photoWall.setDirUri("") }
            _photoAccessNotice.tryEmit(PhotoAccessNotice.DIRECTORY_REVOKED)
        }
    }

    /**
     * 图库开关回弹（§6.2）
     *
     * 判据在 [PhotoWallAccessPolicy.shouldRollbackGallerySwitch]（只有 `DENIED` 才回弹）。
     * `galleryRollbackPending` 防重：回弹写是异步的，在它落盘前 settings 还会再发一次
     * 带旧值的快照，没有这个标志会重复提示。
     */
    private fun applyGalleryRollback(s: AppSettings, state: PhotoPermissionState) {
        if (!s.photoWallGalleryEnabled) {
            galleryRollbackPending = false
            return
        }
        if (!PhotoWallAccessPolicy.shouldRollbackGallerySwitch(true, state)) return
        if (galleryRollbackPending) return
        galleryRollbackPending = true
        viewModelScope.launch { prefs.photoWall.setGalleryEnabled(false) }
        _photoAccessNotice.tryEmit(PhotoAccessNotice.GALLERY_REVOKED)
    }

    private fun SafDirectoryPolicy.RejectReason.toNotice(): PhotoAccessNotice = when (this) {
        SafDirectoryPolicy.RejectReason.EMPTY -> PhotoAccessNotice.DIRECTORY_EMPTY
        SafDirectoryPolicy.RejectReason.MALFORMED -> PhotoAccessNotice.DIRECTORY_MALFORMED
        SafDirectoryPolicy.RejectReason.INTERNAL_STORAGE -> PhotoAccessNotice.DIRECTORY_INTERNAL
    }

    override fun onCleared() {
        super.onCleared()
        _showVisualizer.value = false
        // 照片墙：停扫描协程、停解码线程、逐张 recycle 位图
        // ⛔ API 22 上 `ImageBitmap` 包装的 `Bitmap` 不会自动回收 ⇒ 不 close 就是泄漏
        photoWall.close()
        // 断开对 Activity 的引用（launcher 闭包由 MainActivity 注入）
        photoPermissionLauncher = null
        photoDirectoryLauncher = null
    }

    private companion object {
        /** 连续按键节流：低于此间隔的（重复/连发）切换直接忽略 */
        const val SWITCH_DEBOUNCE_MS = 180L

        const val TAG = "VisualizerViewModel"
    }
}
