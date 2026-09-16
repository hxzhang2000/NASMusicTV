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
        // P2-4 修复（2026-09-16）：原实现把 MEDIA_* 与 USB_* 广播放进同一个 IntentFilter
        // 并 addDataScheme("file")。USB ATTACHED/DETACHED 是 extras-only（无 data URI），
        // 带 scheme 约束的 filter 收不到 → USB 插拔事件漏判（依赖 MEDIA_MOUNTED 兜底）。
        // 拆成两个过滤：file scheme 只约束 MEDIA_*（其 intent.data 为 file:// 挂载点）；
        // USB_* 用独立 filter，无 data 约束。
        val mediaFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addDataScheme("file")
        }
        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, mediaFilter, Context.RECEIVER_NOT_EXPORTED)
            context.registerReceiver(receiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, mediaFilter)
            context.registerReceiver(receiver, usbFilter)
        }
        refreshStorageDevices()
    }

    /** 停止监听（Application.onTerminate 中调用） */
    fun stopListening() {
        receiver?.let {
            // P2-4：同一 receiver 注册了两个 filter（media/usb），各需 unregister 一次
            runCatching { context.unregisterReceiver(it) }
            runCatching { context.unregisterReceiver(it) }
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
                // B15 修复：API 24–29 用隐藏 API 反射 `getPath`，可能抛
                // NoSuchMethodException/InvocationTargetException/IllegalAccessException。
                // 原实现无 try/catch，个别 ROM 隐藏该 API 时 BroadcastReceiver.onReceive
                // 直接崩溃。改为捕获异常并跳过该卷。
                @Suppress("DEPRECATION")
                try {
                    volume.javaClass.getMethod("getPath").invoke(volume) as? String
                        ?: return@mapNotNull null
                } catch (_: Exception) {
                    return@mapNotNull null
                }
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

    private fun getAvailableSpace(path: String): Long = com.nasmusic.tv.util.StorageUtils.availableBytesAt(java.io.File(path))
}