package com.nasmusic.tv.backend.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.nasmusic.tv.data.model.StorageDevice
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 存储设备监听器
 *
 * 监听 USB / SD 卡设备的插拔广播，通过 [onDeviceMounted] / [onDeviceUnmounted]
 * 向上层通知设备变更，触发对应的定向扫描 / 索引清理。
 */
class StorageMonitor(private val context: Context) {

    companion object { private const val TAG = "StorageMonitor" }

    private val _storageDevices = MutableStateFlow<List<StorageDevice>>(emptyList())
    val storageDevices: StateFlow<List<StorageDevice>> = _storageDevices.asStateFlow()

    private val _onDeviceMounted = MutableSharedFlow<StorageDevice>(extraBufferCapacity = 4)
    val onDeviceMounted: SharedFlow<StorageDevice> = _onDeviceMounted.asSharedFlow()

    private val _onDeviceUnmounted = MutableSharedFlow<StorageDevice>(extraBufferCapacity = 4)
    val onDeviceUnmounted: SharedFlow<StorageDevice> = _onDeviceUnmounted.asSharedFlow()

    private var receiver: BroadcastReceiver? = null

    /** 开始监听（Application.onCreate 中调用一次） */
    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addDataScheme("file")
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_MEDIA_MOUNTED,
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        AppLog.d(TAG, "Device mounted: ${intent.data}")
                        refreshStorageDevices()
                        _storageDevices.value
                            .filter { it.isMounted && it.type == StorageType.USB }
                            .forEach { _onDeviceMounted.tryEmit(it) }
                    }
                    Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_REMOVED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        AppLog.d(TAG, "Device removed: ${intent.data}")
                        _storageDevices.value
                            .filter { it.type == StorageType.USB }
                            .forEach { _onDeviceUnmounted.tryEmit(it) }
                        refreshStorageDevices()
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
        refreshStorageDevices()
    }

    /** 停止监听（Application.onTerminate 中调用） */
    fun stopListening() {
        receiver?.let {
            context.unregisterReceiver(it)
            receiver = null
        }
    }

    /** 刷新存储设备列表 */
    fun refreshStorageDevices() {
        // StorageManager.getStorageVolumes() 需要 API 24+，低版本跳过
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            _storageDevices.value = emptyList()
            return
        }
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        val devices = sm.storageVolumes.mapNotNull { volume ->
            val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath ?: return@mapNotNull null
            } else {
                @Suppress("DEPRECATION")
                val dir = volume.javaClass.getMethod("getPath").invoke(volume) as? String
                    ?: return@mapNotNull null
                dir
            }
            val type = when {
                path.contains("usb", ignoreCase = true) -> StorageType.USB
                path.contains("sd", ignoreCase = true) -> StorageType.EXTERNAL
                else -> StorageType.INTERNAL
            }
            val mounted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                volume.state == Environment.MEDIA_MOUNTED
            } else {
                try {
                    volume.javaClass.getMethod("isMounted").invoke(volume) as? Boolean ?: false
                } catch (_: Exception) { false }
            }
            StorageDevice(
                path = path,
                name = volume.getDescription(context) ?: path,
                type = type,
                isMounted = mounted,
                availableSpace = getAvailableSpace(path)
            )
        }
        _storageDevices.value = devices
        AppLog.d(TAG, "Found ${devices.size} storage devices")
    }

    private fun getAvailableSpace(path: String): Long = try {
        val stat = StatFs(path)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) { 0L }
}