package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.StorageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **G7 修复**（API 22/23 外接存储枚举）的单测门禁
 *
 * 覆盖：
 * 1. 广播 `intent.data` 的挂载点解析（G7 的关键 —— 这个值此前只被打日志却没被使用）
 * 2. 内部存储黑名单（低版本 ROM 上内部存储有多个别名，判错会把内部存储当外接扫）
 * 3. 路径探测兜底（`/mnt/usb0`、`/storage/usb0`、`/mnt/media_rw/sda1` 这类）
 *
 * ⚠️ 全部用 `assertTrue` / `assertEquals`（项目硬约定：Kotlin `assert` 在测试 JVM 是空操作）。
 * ⚠️ 含**负向自证**：把「不带黑名单」的旧式判定如实模拟进来，断言它确实会误判。
 */
class LegacyStorageProbeTest {

    // ────────────────────────── 假文件系统 ──────────────────────────

    /** 把一组目录路径当成本次「存在的目录集合」 */
    private fun existsIn(dirs: Set<String>): (String) -> Boolean = { it in dirs }

    /** 从「存在的目录集合」里推导出某个父目录的直接子项名 */
    private fun childrenIn(dirs: Set<String>): (String) -> List<String> = { parent ->
        dirs.filter { it.startsWith("$parent/") && !it.removePrefix("$parent/").contains('/') }
            .map { it.substringAfterLast('/') }
    }

    // ────────────────────────── ① 路径归一化 / 广播解析 ──────────────────────────

    @Test
    fun `normalizeMountPath trims trailing slash and rejects non-absolute`() {
        assertEquals("/mnt/usb0", LegacyStorageProbe.normalizeMountPath("/mnt/usb0"))
        assertEquals("/mnt/usb0", LegacyStorageProbe.normalizeMountPath("/mnt/usb0/"))
        assertEquals("/mnt/usb0", LegacyStorageProbe.normalizeMountPath("  /mnt/usb0/  "))
        assertNull("非绝对路径必须拒绝", LegacyStorageProbe.normalizeMountPath("mnt/usb0"))
        assertNull("空串必须拒绝", LegacyStorageProbe.normalizeMountPath(""))
        assertNull("纯空白必须拒绝", LegacyStorageProbe.normalizeMountPath("   "))
        assertNull("null 必须拒绝", LegacyStorageProbe.normalizeMountPath(null))
        assertNull("根路径归一化后为空 → 拒绝", LegacyStorageProbe.normalizeMountPath("/"))
    }

    @Test
    fun `mountPointFromBroadcast extracts mount point from intent data path`() {
        // `Uri.parse("file:///mnt/usb0").path` == "/mnt/usb0"
        assertEquals("/mnt/usb0", LegacyStorageProbe.mountPointFromBroadcast("/mnt/usb0"))
        assertEquals("/storage/usb0", LegacyStorageProbe.mountPointFromBroadcast("/storage/usb0/"))
        // USB_DEVICE_ATTACHED / DETACHED 是 extras-only 广播，data 为 null
        assertNull("extras-only 广播必须返回 null 而不是抛异常",
            LegacyStorageProbe.mountPointFromBroadcast(null))
    }

    // ────────────────────────── ② 内部存储黑名单 ──────────────────────────

    @Test
    fun `classify marks internal storage aliases as INTERNAL`() {
        val internalAliases = listOf(
            "/sdcard",
            "/mnt/sdcard",
            "/storage/sdcard0",
            "/storage/emulated/0",
            "/storage/emulated/0/DCIM",
            "/storage/self/primary",
            "/mnt/secure/asec",
            "/mnt/asec/com.foo",
            "/mnt/obb",
        )
        for (p in internalAliases) {
            assertEquals("$p 必须判为内部存储", StorageType.INTERNAL, LegacyStorageProbe.classify(p))
            assertTrue("$p 必须被 isInternal 拦截", LegacyStorageProbe.isInternal(p))
        }
    }

    @Test
    fun `classify marks external storage as USB or EXTERNAL`() {
        assertEquals(StorageType.USB, LegacyStorageProbe.classify("/mnt/usb0"))
        assertEquals(StorageType.USB, LegacyStorageProbe.classify("/storage/usb0"))
        assertEquals(StorageType.USB, LegacyStorageProbe.classify("/mnt/udisk"))
        // ⚠️ /storage/sdcard1 是外接 SD 卡；/storage/sdcard0 才是内部存储（精确黑名单）
        assertEquals(StorageType.EXTERNAL, LegacyStorageProbe.classify("/storage/sdcard1"))
        assertEquals(StorageType.EXTERNAL, LegacyStorageProbe.classify("/mnt/media_rw/sda1"))
        // /mnt/extsd —— 部分国产 ROM 的命名
        assertEquals(StorageType.EXTERNAL, LegacyStorageProbe.classify("/mnt/extsd"))
    }

    @Test
    fun `sdcard0 is internal while sdcard1 is external`() {
        assertTrue("/storage/sdcard0 是内部存储",
            LegacyStorageProbe.isInternal("/storage/sdcard0"))
        assertTrue("/storage/sdcard1 不能被误杀成内部存储",
            !LegacyStorageProbe.isInternal("/storage/sdcard1"))
    }

    // ────────────────────────── ③ 候选挂载点探测 ──────────────────────────

    @Test
    fun `candidates includes broadcast mount point even when probe finds nothing`() {
        val dirs = setOf("/mnt")   // 探测一无所获
        val got = LegacyStorageProbe.candidates(
            broadcastMounts = listOf("/mnt/usb0"),
            exists = existsIn(dirs),
            children = childrenIn(dirs),
        )
        assertTrue("广播挂载点必须入选（G7 的核心），实际 $got", "/mnt/usb0" in got)
    }

    @Test
    fun `candidates probes common mount roots`() {
        val dirs = setOf("/mnt", "/mnt/usb0", "/storage", "/storage/usb0", "/storage/sdcard1")
        val got = LegacyStorageProbe.candidates(
            broadcastMounts = emptyList(),
            exists = existsIn(dirs),
            children = childrenIn(dirs),
        )
        assertTrue("/mnt/usb0 必须被探测到，实际 $got", "/mnt/usb0" in got)
        assertTrue("/storage/usb0 必须被探测到，实际 $got", "/storage/usb0" in got)
        assertTrue("/storage/sdcard1 必须被探测到，实际 $got", "/storage/sdcard1" in got)
    }

    @Test
    fun `candidates descends into media_rw container and does not add the container itself`() {
        val dirs = setOf("/mnt", "/mnt/media_rw", "/mnt/media_rw/sda1")
        val got = LegacyStorageProbe.candidates(
            broadcastMounts = emptyList(),
            exists = existsIn(dirs),
            children = childrenIn(dirs),
        )
        assertTrue("容器目录的子目录才是挂载点，实际 $got", "/mnt/media_rw/sda1" in got)
        assertTrue("容器目录本身不能当挂载点（否则会重复扫描），实际 $got",
            "/mnt/media_rw" !in got)
    }

    @Test
    fun `candidates does not descend into a plain usb mount that has an sd-named subdir`() {
        // 真实 U 盘里有个叫 MusicSD 的子目录 —— 无差别下钻会把挂载点误判成它
        val dirs = setOf("/mnt", "/mnt/usb0", "/mnt/usb0/MusicSD")
        val got = LegacyStorageProbe.candidates(
            broadcastMounts = emptyList(),
            exists = existsIn(dirs),
            children = childrenIn(dirs),
        )
        assertTrue("挂载点本身必须入选，实际 $got", "/mnt/usb0" in got)
        assertTrue("子目录不能被当成挂载点，实际 $got", "/mnt/usb0/MusicSD" !in got)
    }

    @Test
    fun `candidates never returns internal storage`() {
        val dirs = setOf("/mnt", "/mnt/sdcard", "/mnt/usb0", "/storage", "/storage/emulated",
            "/storage/emulated/0")
        val got = LegacyStorageProbe.candidates(
            broadcastMounts = listOf("/mnt/usb0", "/mnt/sdcard", "/storage/emulated/0"),
            exists = existsIn(dirs),
            children = childrenIn(dirs),
        )
        assertTrue("/mnt/usb0 必须保留，实际 $got", "/mnt/usb0" in got)
        for (bad in listOf("/mnt/sdcard", "/storage/emulated/0", "/storage/emulated")) {
            assertTrue("内部存储 $bad 绝不能出现在结果里，实际 $got", bad !in got)
        }
    }

    @Test
    fun `candidates deduplicates and keeps broadcast order first`() {
        val dirs = setOf("/mnt", "/mnt/usb0")
        val got = LegacyStorageProbe.candidates(
            broadcastMounts = listOf("/mnt/usb0", "/mnt/usb0/"),
            exists = existsIn(dirs),
            children = childrenIn(dirs),
        )
        assertEquals("归一化后必须去重", 1, got.count { it == "/mnt/usb0" })
    }

    // ────────────────────────── ④ 负向自证 ──────────────────────────

    /**
     * 负向自证（黑名单不是摆设）：
     * 内联一个**不带黑名单**的「旧式」判定（只按关键字猜），
     * 断言它确实会把内部存储误判成外接存储 —— 证明确实依赖黑名单。
     */
    @Test
    fun `negative proof - a keyword-only classifier misjudges internal storage as external`() {
        fun naiveClassify(path: String): StorageType =
            if (path.contains("usb", ignoreCase = true)) StorageType.USB
            else StorageType.EXTERNAL

        // 旧式判定：/storage/emulated/0 与 /mnt/sdcard 都会被判成外接
        assertEquals("naive 判定会把 /storage/emulated/0 误判为外接",
            StorageType.EXTERNAL, naiveClassify("/storage/emulated/0"))
        assertEquals("naive 判定会把 /mnt/sdcard 误判为外接",
            StorageType.EXTERNAL, naiveClassify("/mnt/sdcard"))

        // 真实判定必须把它们拦住
        assertEquals(StorageType.INTERNAL, LegacyStorageProbe.classify("/storage/emulated/0"))
        assertEquals(StorageType.INTERNAL, LegacyStorageProbe.classify("/mnt/sdcard"))
    }

    /**
     * 负向自证（探测不能只靠广播）：
     * 只传空广播 + 什么都不存在 ⇒ 必须返回**空表**（而不是抛异常 / 返回垃圾）。
     * 反向：一旦把挂载点放进广播，结果必须**从空变非空** —— 证明候选逻辑真的在跑。
     */
    @Test
    fun `negative proof - empty inputs yield empty result but broadcast flips it non-empty`() {
        val emptyDirs = emptySet<String>()
        val nothing = LegacyStorageProbe.candidates(
            broadcastMounts = emptyList(),
            exists = existsIn(emptyDirs),
            children = childrenIn(emptyDirs),
        )
        assertTrue("什么都不存在时必须返回空表，实际 $nothing", nothing.isEmpty())

        val withBroadcast = LegacyStorageProbe.candidates(
            broadcastMounts = listOf("/mnt/usb0"),
            exists = existsIn(emptyDirs),
            children = childrenIn(emptyDirs),
        )
        assertTrue("广播一进来就必须非空（旧实现恒为空表 ⇒ G7），实际 $withBroadcast",
            withBroadcast.isNotEmpty())
    }
}
