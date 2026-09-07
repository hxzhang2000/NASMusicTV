package com.nasmusic.tv.backend.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StatFs
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.StorageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 存储空间守护（见方案 §10）
 *
 * - **双 StatFs 取 min**：应用专属目录多由 /data 承载（fuse/sdcardfs），
 *   单点判定会高估可用空间 → 取 root 与 filesDir 较小值（[StorageUtils.availableBytesMin]）
 * - **100MB 预留**：下载前必须满足 `availableBytes - RESERVED_BYTES - SAFE_BUFFER_BYTES > estimatedBytes`
 * - **30s 轮询 + 广播**：保持设置页实时显示，[ACTION_DEVICE_STORAGE_LOW] 触发队列暂停
 * - **节流提示**：[notifyFullOnce] 5 分钟内最多提示 1 次，避免反复刷屏
 */
class StorageGuard(
    private val context: Context,
    private val rootProvider: () -> File
) {
    companion object {
        private const val TAG = "StorageGuard"
        /** 需求 9：至少保留 100MB */
        const val RESERVED_BYTES = 100L * 1024 * 1024
        /** 写入抖动缓冲（避免边界值误报） */
        const val SAFE_BUFFER_BYTES = 5L * 1024 * 1024
        /** 队列已满的提示节流窗口 */
        private const val NOTIFY_THROTTLE_MS = 5 * 60 * 1000L
        private const val POLL_INTERVAL_MS = 30_000L
    }

    private val _availableBytes = MutableStateFlow(0L)
    val availableBytes: StateFlow<Long> = _availableBytes.asStateFlow()

    private val _pausedByLowStorage = MutableStateFlow(false)
    val pausedByLowStorage: StateFlow<Boolean> = _pausedByLowStorage.asStateFlow()

    private var lastFullNotifyAt = 0L
    private var receiver: BroadcastReceiver? = null
    private var pollJob: kotlinx.coroutines.Job? = null

    /**
     * 取「下载根目录所在分区」与「内部数据分区」的较小可用值。
     */
    fun availableBytes(): Long = StorageUtils.availableBytesMin(rootProvider(), context.filesDir)

    /** 是否有足够空间容纳 [estimatedBytes]（已扣除预留与缓冲） */
    fun hasRoomFor(estimatedBytes: Long): Boolean =
        availableBytes() - RESERVED_BYTES - SAFE_BUFFER_BYTES > estimatedBytes

    /** 是否触发"存储已满"阈值 */
    fun isStorageFull(): Boolean = availableBytes() <= RESERVED_BYTES

    /**
     * 队列已满提示（节流 5 分钟）。
     *
     * @return true 表示已提示（调用方应停止后续操作），false 表示被节流跳过
     */
    suspend fun notifyFullOnce(notify: (String) -> Unit): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFullNotifyAt < NOTIFY_THROTTLE_MS) return false
        lastFullNotifyAt = now
        notify("存储空间不足（已预留 100MB），请清理后重试")
        return true
    }

    /**
     * 启动存储监听（在 NasMusicApp.onCreate 调用）
     */
    fun start(scope: CoroutineScope) {
        // 初始刷新
        _availableBytes.value = availableBytes()

        // 30s 轮询
        pollJob = scope.launch(Dispatchers.IO) {
            while (true) {
                _availableBytes.value = availableBytes()
                _pausedByLowStorage.value = isStorageFull()
                delay(POLL_INTERVAL_MS)
            }
        }

        // 系统广播监听（API 26+ 后部分广播需动态注册）
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                _pausedByLowStorage.value = isStorageFull()
            }
        }
        runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW))
        }.onFailure {
            AppLog.w(TAG, "registerReceiver failed: ${it.message}")
        }
    }

    fun stop() {
        pollJob?.cancel()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /** 进入设置页时立即刷新一次 */
    suspend fun refreshNow() {
        _availableBytes.value = availableBytes()
    }
}

/** 下载中空间跌破阈值时抛出（供 SongDownloadManager 捕获并清理 .part） */
class StorageFullException : RuntimeException("storage full during download")
