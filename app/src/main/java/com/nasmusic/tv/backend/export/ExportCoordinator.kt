package com.nasmusic.tv.backend.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nasmusic.tv.backend.download.DownloadPathBuilder
import com.nasmusic.tv.backend.download.DownloadRepository
import com.nasmusic.tv.backend.download.db.DownloadDatabase
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出协调器（见方案 §8.8）
 *
 * 职责：
 * - 编排导出全流程：枚举设备 → SAF 探测/授权 → 解析导出根 → 调 SongExporter 复制
 * - 维护导出状态 [state]（供设置页 UI）
 * - 授权 URI 持久化与校验（[prefs] 中 exportTreeUri/exportVolumeId）
 *
 * 生命周期由 NasMusicApp 持有，Activity 通过 `(application as NasMusicApp).exportCoordinator` 访问。
 */
class ExportCoordinator(
    private val context: Context,
    private val appPreferences: AppPreferences,
    private val downloadRepository: DownloadRepository,
    private val downloadPathBuilder: DownloadPathBuilder,
    /** F-4：注入应用级 scope（NasMusicApp.applicationScope，SupervisorJob+IO，进程级单例语义） */
    private val externalScope: CoroutineScope? = null
) {
    companion object { private const val TAG = "ExportCoordinator" }

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var _exportDevices: List<com.nasmusic.tv.data.model.StorageDevice> = emptyList()
    val exportDevices: List<com.nasmusic.tv.data.model.StorageDevice> get() = _exportDevices

    // F-4：优先注入的 applicationScope（单例场景不 cancel 是正确语义）；未注入时回退私有 scope
    private val scope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exporter by lazy {
        SongExporter(
            context,
            downloadRepository,
            DownloadDatabase.get(context).exportRecordDao(),
            appPreferences
        )
    }

    /** 设备 UUID（StorageVolume.getUuid() 为 null 时回退 stableHash(path)） */
    fun volumeIdOf(device: com.nasmusic.tv.data.model.StorageDevice): String {
        val uuid = try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            sm.storageVolumes.firstOrNull { it.directory?.absolutePath == device.path }?.uuid
        } catch (e: Exception) { null }
        return uuid ?: com.nasmusic.tv.util.HashUtils.stablePathHash64(device.path).toString()
    }

    /** 刷新外接设备列表（设置页进入时调用；type ∈ USB/EXTERNAL 且已挂载） */
    suspend fun refreshDevices(devices: List<com.nasmusic.tv.data.model.StorageDevice>) {
        _exportDevices = devices.filter {
            (it.type == com.nasmusic.tv.data.model.StorageType.USB ||
                it.type == com.nasmusic.tv.data.model.StorageType.EXTERNAL) && it.isMounted
        }
    }

    /** 导出前请求设备选择（返回可导出设备列表） */
    fun requestExportDevices(): List<com.nasmusic.tv.data.model.StorageDevice> = _exportDevices

    /** SAF 授权回调（MainActivity registerForActivityResult 完成后调用） */
    fun onTreeGranted(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Failed to take persistable URI permission: ${e.message}")
        }
        scope.launch {
            appPreferences.setExportTreeUri(uri.toString())
            // 记录当前卷（由 UI 传入，这里先用已选设备）
            appPreferences.setExportVolumeId(_pendingVolumeId)
            _pendingVolumeId = null
            // 授权后继续导出
            _exportDevices.firstOrNull()?.let { exportTo(it) }
        }
    }

    private var _pendingVolumeId: String? = null

    /** 发起导出（MainViewModel 调用） */
    fun exportTo(device: com.nasmusic.tv.data.model.StorageDevice) {
        val volumeId = volumeIdOf(device)
        scope.launch {
            val treeUri = runCatching { appPreferences.exportTreeUri.first() }.getOrNull()?.let { Uri.parse(it) }
            val storedVolumeId = runCatching { appPreferences.exportVolumeId.first() }.getOrNull()

            // SAF 可用且未授权 → 发起授权流程（由 Activity 处理）
            if (treeUri == null || storedVolumeId != volumeId) {
                if (ExportPermissionHelper.isTreePickAvailable(context)) {
                    _pendingVolumeId = volumeId
                    _state.value = ExportState.Preparing(0)
                    requestTreePick()
                    return@launch
                }
                // SAF 不可用 → 静默降级到应用专属目录
            }

            val root = ExportPermissionHelper.resolveRoot(
                context, device.path, treeUri, storedVolumeId, volumeId
            )
            if (root is ExportRoot.Unavailable) {
                _state.value = ExportState.Failed(ExportError.NO_PERMISSION)
                return@launch
            }
            // 降级到 File 根时提示目标路径（§8.8.10）
            if (root is ExportRoot.File) {
                AppLog.d(TAG, "export fallback to app-specific dir: ${root.dir.absolutePath}")
            }
            exporter.export(root, volumeId, device.path) { done, skipped, failed ->
                _state.value = ExportState.Completed(done, skipped, failed)
            }
        }
    }

    /** 由 MainActivity 注入 SAF 授权启动器（TV 无 DocumentsUI 时降级） */
    var treePickLauncher: (() -> Unit)? = null
    private fun requestTreePick() {
        treePickLauncher?.invoke() ?: run {
            _state.value = ExportState.Failed(ExportError.NO_PERMISSION)
        }
    }

    /** SAF 不可用/用户取消时回调 */
    fun onTreeUnavailable() {
        _state.value = ExportState.Failed(ExportError.NO_PERMISSION)
    }

    fun cancel() {
        exporter.cancel()
        _state.value = ExportState.Cancelled
    }

    fun reset() {
        _state.value = ExportState.Idle
    }
}