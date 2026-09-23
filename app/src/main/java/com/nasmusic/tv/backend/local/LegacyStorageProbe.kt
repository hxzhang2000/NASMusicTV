package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.StorageType

/**
 * API 22 / 23 的外接存储枚举兜底（**G7 修复**，2026-09-23）
 *
 * ── 为什么需要它 ──
 * [StorageMonitor.refreshStorageDevices] 的主路径依赖 `StorageManager.getStorageVolumes()`，
 * 而它**需要 API 24+**。本项目 `minSdk = 22`，电视（Android 5.1.1）正好落在 22/23 区间
 * ⇒ 主路径短路返回空列表 ⇒ **电视插 U 盘后音乐永远扫不到**（既有缺陷 G7）。
 *
 * 低版本上可用的两条信息源：
 * 1. `ACTION_MEDIA_MOUNTED` 广播的 `intent.data` —— **它本身就是挂载点 URI**
 *    （`file:///mnt/usb0`）。这个值此前一直被打了日志却没被使用（见 `StorageMonitor` 原 64 行）。
 * 2. 常见挂载点路径探测（`/mnt/usb0`、`/storage/usb0`、`/mnt/media_rw/sda1` 这类）。
 *
 * ⛔ **写 KDoc 时注意**：Kotlin 的块注释**支持嵌套**，所以路径通配符 `dir` + `/` + `*`
 * 会被当成「嵌套注释开始符」吞掉其后全部代码，报错落在**文件 EOF**。
 * 因此本文件里所有通配写法一律避开斜杠与星号相邻（写 `/mnt/usb0` 而非 `/mnt/usb` + 星号）。
 *
 * 本对象**只做纯逻辑**（路径归一化 / 类型判定 / 候选筛选），
 * 真实文件系统访问由调用方以 lambda 注入 ⇒ 可单测（门禁 `LegacyStorageProbeTest`）。
 *
 * ⚠️ **为什么内部存储用黑名单而不是白名单**：低版本 ROM 的挂载点命名毫无规范
 * （`/mnt/udisk`、`/storage/0123-4567`、`/mnt/media_rw/XXXX-XXXX` 都见过），
 * 白名单必然漏；而内部存储的路径是 AOSP 固定的少数几个别名 ⇒ 黑名单可靠。
 */
internal object LegacyStorageProbe {

    /** 需要列子目录的根 —— 真正的挂载点通常是它们的子目录 */
    private val SCAN_ROOTS = listOf("/mnt", "/storage")

    /**
     * 绝不能当作外接存储的**精确路径**（内部存储的历史别名）
     *
     * ⚠️ `/storage/sdcard0` 在 API 22 上指向内部存储；外接 SD 卡是 `/storage/sdcard1`。
     * 因此这里**只能精确匹配**，不能用 `/storage/sdcard` 前缀（会误杀 `sdcard1`）。
     */
    private val INTERNAL_EXACT = setOf(
        "/sdcard",
        "/mnt/sdcard",
        "/storage/sdcard0",
        "/storage/emulated/0",
        "/mnt/secure/asec",
    )

    /** 绝不能当作外接存储的**前缀** */
    private val INTERNAL_PREFIXES = listOf(
        "/storage/emulated",
        "/storage/self",
        "/mnt/secure",
        "/mnt/asec",
        "/mnt/obb",
        "/mnt/shell",
    )

    /** 外接存储路径的特征关键字（低版本 ROM 的实际命名） */
    private val EXTERNAL_KEYWORDS = listOf("usb", "sd", "media_rw", "ext", "udisk")

    /**
     * 已知的「**容器**」目录名 —— 它们**自身不是挂载点**，其子目录才是。
     *
     * ⚠️ 只对这些名字往下一层，**不要**对所有目录都往下钻：
     * 真实 U 盘里可能有个叫 `MusicSD` 的子目录，无差别下钻会把挂载点误判成那个子目录。
     */
    private val CONTAINER_NAMES = setOf("media_rw")

    /**
     * 挂载点路径归一化：去首尾空白 + 去尾部 `/`。
     *
     * @return 绝对路径；空 / 非绝对路径返回 null
     */
    fun normalizeMountPath(raw: String?): String? {
        val p = raw?.trim().orEmpty()
        if (p.isEmpty() || !p.startsWith("/")) return null
        return p.trimEnd('/').ifEmpty { null }
    }

    /**
     * 从广播 `intent.data` 的 path 取挂载点 —— **G7 修复的关键**
     *
     * 调用方传 `intent.data?.path`（`file:///mnt/usb0` → `/mnt/usb0`）。
     * USB ATTACHED/DETACHED 广播是 extras-only（`data` 为 null），此方法返回 null，不影响调用方。
     */
    fun mountPointFromBroadcast(dataPath: String?): String? = normalizeMountPath(dataPath)

    /** 是否内部存储（黑名单判定） */
    fun isInternal(path: String): Boolean {
        val p = normalizeMountPath(path) ?: return true
        if (p in INTERNAL_EXACT) return true
        return INTERNAL_PREFIXES.any { p == it || p.startsWith("$it/") }
    }

    /** 路径 → 存储类型 */
    fun classify(path: String): StorageType {
        val p = normalizeMountPath(path) ?: return StorageType.UNKNOWN
        if (isInternal(p)) return StorageType.INTERNAL
        return if (p.contains("usb", ignoreCase = true) || p.contains("udisk", ignoreCase = true)) {
            StorageType.USB
        } else {
            StorageType.EXTERNAL
        }
    }

    /** 路径是否「看起来像」外接存储（探测阶段的粗筛，之后还要过黑名单） */
    private fun looksExternal(path: String): Boolean =
        EXTERNAL_KEYWORDS.any { path.contains(it, ignoreCase = true) }

    /**
     * 汇总候选挂载点（纯逻辑，文件系统由调用方注入）
     *
     * 顺序即优先级：广播带来的挂载点最可信 → 再补路径探测。
     *
     * @param broadcastMounts 广播收到的挂载点（`ACTION_MEDIA_MOUNTED` 的 `intent.data`）
     * @param exists          路径是否存在**且是目录**
     * @param children        列出目录下的**子项名**（不是全路径）；不可读时返回空表
     */
    fun candidates(
        broadcastMounts: Collection<String>,
        exists: (String) -> Boolean,
        children: (String) -> List<String>,
    ): List<String> {
        val out = LinkedHashSet<String>()

        // ① 广播挂载点（最可信）
        for (m in broadcastMounts) {
            normalizeMountPath(m)?.let { out.add(it) }
        }

        // ② 路径探测兜底：/mnt、/storage 下的子目录
        //    `media_rw` 这类容器目录再往下一层（`/mnt/media_rw/sda1` 才是挂载点）
        for (root in SCAN_ROOTS) {
            if (!exists(root)) continue
            for (name in children(root)) {
                val full = "$root/$name"
                if (!exists(full)) continue
                if (name in CONTAINER_NAMES) {
                    for (sub in children(full)) {
                        val subFull = "$full/$sub"
                        if (exists(subFull) && looksExternal(subFull)) out.add(subFull)
                    }
                } else if (looksExternal(full)) {
                    out.add(full)
                }
            }
        }

        // ③ 黑名单兜底 —— 无论从哪条路来，内部存储一律剔除
        return out.filterNot { isInternal(it) }
    }
}
