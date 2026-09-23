package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 **G1** —— 跨来源指纹去重（§14.4）
 *
 * 断言内容：同 `(size, 秒, 名称)` 只留 1 条；`JELLYFIN` 项不参与去重。
 *
 * 负向自证：把一条记录的 `lastModified` 故意设成**毫秒** ⇒ 断言「去重失效（2 条都在）」，
 * 证明本类确实依赖「入参已归一化为秒」这一契约（归一化责任在各 `PhotoSource` 实现内，§6.1）。
 *
 * ⚠️ 项目硬约定：一律用 JUnit 的 `assertTrue` —— Kotlin 的 `assert(...)` 在测试 JVM 上是空操作。
 */
class PhotoDedupTest {

    private val dedup = PhotoDedup()

    private fun ref(
        name: String,
        size: Long = 1_000L,
        sec: Long = 1_700_000_000L,
        kind: PhotoSourceKind = PhotoSourceKind.GALLERY,
        payload: String = name,
    ) = PhotoRef(
        id = PhotoIds.of(kind, payload),
        displayName = name,
        width = 0,
        height = 0,
        size = size,
        lastModified = sec,
        dateAdded = sec,
        source = kind,
    )

    // ────────────────────── ① 指纹命中 ──────────────────────

    @Test
    fun `same fingerprint keeps only the first occurrence`() {
        val first = ref("IMG_0001.jpg", payload = "gallery/1")
        val second = ref("IMG_0001.jpg", payload = "usb/DCIM/1") // 同一张照片，另一条来源
        val out = dedup.distinct(listOf(first, second))
        assertTrue("同指纹应只留 1 条，实际 ${out.size}", out.size == 1)
        assertTrue("应保留首次出现的那个（来源序靠前者）", out[0].id == first.id)
    }

    @Test
    fun `fingerprint name comparison is case insensitive`() {
        // 不同卷的 FAT32 文件系统会给出大小写不一致的名字，必须视为同一张
        val out = dedup.distinct(
            listOf(ref("img_0001.JPG", payload = "a"), ref("IMG_0001.jpg", payload = "b")),
        )
        assertTrue("大小写不同但同名同尺寸同秒 ⇒ 应去重，实际 ${out.size}", out.size == 1)
    }

    @Test
    fun `different size or second is not deduped`() {
        val bySize = dedup.distinct(
            listOf(ref("a.jpg", size = 1_000L, payload = "1"), ref("a.jpg", size = 2_000L, payload = "2")),
        )
        assertTrue("尺寸不同不应去重，实际 ${bySize.size}", bySize.size == 2)

        val bySec = dedup.distinct(
            listOf(
                ref("a.jpg", sec = 1_700_000_000L, payload = "1"),
                ref("a.jpg", sec = 1_700_000_001L, payload = "2"),
            ),
        )
        assertTrue("修改时间差 1 秒不应去重，实际 ${bySec.size}", bySec.size == 2)
    }

    @Test
    fun `different names are not deduped`() {
        val out = dedup.distinct(listOf(ref("a.jpg", payload = "1"), ref("b.jpg", payload = "2")))
        assertTrue("文件名不同不应去重，实际 ${out.size}", out.size == 2)
    }

    // ────────────────────── ② JELLYFIN 不参与去重 ──────────────────────

    @Test
    fun `jellyfin items are never deduped against each other`() {
        // 服务端 itemId 与本机文件无对应关系 ⇒ 同指纹也不能当同一张
        val a = ref("a.jpg", kind = PhotoSourceKind.JELLYFIN, payload = "item-1")
        val b = ref("a.jpg", kind = PhotoSourceKind.JELLYFIN, payload = "item-2")
        val out = dedup.distinct(listOf(a, b))
        assertTrue("JELLYFIN 项之间不应去重，实际 ${out.size}", out.size == 2)
    }

    @Test
    fun `jellyfin item does not consume the fingerprint slot`() {
        // JELLYFIN 先出现时若写入了指纹集合，会把后面本机的同一张照片误杀
        val jelly = ref("a.jpg", kind = PhotoSourceKind.JELLYFIN, payload = "item-1")
        val local = ref("a.jpg", kind = PhotoSourceKind.EXTERNAL, payload = "/mnt/usb0/a.jpg")
        val out = dedup.distinct(listOf(jelly, local))
        assertTrue("JELLYFIN 不应占用指纹位，本机项必须保留，实际 ${out.size}", out.size == 2)
    }

    @Test
    fun `jellyfin items survive alongside deduped local items`() {
        val out = dedup.distinct(
            listOf(
                ref("a.jpg", kind = PhotoSourceKind.GALLERY, payload = "g1"),
                ref("a.jpg", kind = PhotoSourceKind.EXTERNAL, payload = "usb/a.jpg"), // 与本机重复
                ref("a.jpg", kind = PhotoSourceKind.JELLYFIN, payload = "item-1"),
                ref("a.jpg", kind = PhotoSourceKind.JELLYFIN, payload = "item-2"),
            ),
        )
        assertTrue("2 本机同指纹留 1 + 2 个 JELLYFIN 全留 = 3，实际 ${out.size}", out.size == 3)
    }

    // ────────────────────── ③ 边界 ──────────────────────

    @Test
    fun `empty and single input pass through`() {
        assertTrue("空表应返回空表", dedup.distinct(emptyList()).isEmpty())
        val one = listOf(ref("a.jpg"))
        assertTrue("单条应原样返回", dedup.distinct(one).size == 1)
    }

    @Test
    fun `order of survivors follows input order`() {
        val a = ref("a.jpg", payload = "1")
        val b = ref("b.jpg", payload = "2")
        val out = dedup.distinct(listOf(a, b, ref("a.jpg", payload = "3")))
        assertTrue("应保留 a、b 且顺序不变", out.size == 2 && out[0].id == a.id && out[1].id == b.id)
    }

    // ────────────────────── ④ 负向自证 ──────────────────────

    /**
     * **负向自证**：把一条记录的 `lastModified` 故意设成**毫秒**（差 1000 倍）。
     *
     * 此时指纹不匹配、**去重静默失效** —— 这证明本类确实依赖「入参已归一化为秒」，
     * 而不是碰巧靠别的字段（比如文件名）去重成功。
     *
     * ⛔ 若本用例变成「只留 1 条」，说明指纹里混进了与时间无关的字段、
     * 或有人在本类里加了归一化（那是**错误位置**，会让两条不同秒的记录被误判为同一张）。
     */
    @Test
    fun `negative proof - millisecond input silently defeats dedup`() {
        val seconds = 1_700_000_000L
        val milliseconds = seconds * 1000L
        val normal = ref("a.jpg", sec = seconds, payload = "g1")
        val buggy = ref("a.jpg", sec = milliseconds, payload = "usb/a.jpg")
        val out = dedup.distinct(listOf(normal, buggy))
        assertTrue(
            "毫秒记录必须导致去重失效（2 条都在）—— 否则说明本类偷偷做了归一化，实际 ${out.size}",
            out.size == 2,
        )
    }
}
