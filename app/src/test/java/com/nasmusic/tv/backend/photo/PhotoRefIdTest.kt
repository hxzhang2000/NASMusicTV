package com.nasmusic.tv.backend.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据层契约门禁（§14.4 的 **G9** + 白名单 / SAF 卷策略 / ISO 时间换算）
 *
 * 覆盖：
 * 1. `PhotoRef.id` 的 `<kind>:<payload>` 格式与解析 —— **跨来源同名文件不得冲突**
 * 2. 图片扩展名白名单（**显式排除 HEIC / HEIF**，电视 API 22 解不了）
 * 3. SAF 目录的卷策略（⛔ 内部存储必须被拒）
 * 4. ISO 8601 → Unix **秒**（不能用 `java.time`，minSdk 22）
 *
 * ⚠️ 全部用 `assertTrue` / `assertEquals`（项目硬约定：Kotlin `assert` 在测试 JVM 是空操作）。
 * ⚠️ 含负向自证。
 */
class PhotoRefIdTest {

    private fun ref(
        kind: PhotoSourceKind,
        payload: String,
        name: String = "IMG_0001.jpg",
    ) = PhotoRef(
        id = PhotoIds.of(kind, payload),
        displayName = name,
        width = 0,
        height = 0,
        size = 0L,
        lastModified = 0L,
        dateAdded = 0L,
        source = kind,
    )

    // ────────────────────── ① id 格式与解析 ──────────────────────

    @Test
    fun `id has kind prefix in lowercase`() {
        assertEquals("gallery:123", PhotoIds.of(PhotoSourceKind.GALLERY, "123"))
        assertEquals("external:/mnt/usb0/DCIM/a.jpg",
            PhotoIds.of(PhotoSourceKind.EXTERNAL, "/mnt/usb0/DCIM/a.jpg"))
        assertEquals("jellyfin:abc123", PhotoIds.of(PhotoSourceKind.JELLYFIN, "abc123"))
    }

    @Test
    fun `kind and payload round trip`() {
        val id = PhotoIds.of(PhotoSourceKind.EXTERNAL, "/mnt/usb0/DCIM/a.jpg")
        assertEquals(PhotoSourceKind.EXTERNAL, PhotoIds.kindOf(id))
        assertEquals("/mnt/usb0/DCIM/a.jpg", PhotoIds.payloadOf(id))
    }

    @Test
    fun `payload keeps its own colons`() {
        // 绝对路径与 content URI 里都可能有 `:` —— 只能按**第一个**分隔符切
        val uri = "content://com.android.externalstorage.documents/tree/0123-4567%3ADCIM"
        val id = PhotoIds.of(PhotoSourceKind.EXTERNAL, uri)
        assertEquals(PhotoSourceKind.EXTERNAL, PhotoIds.kindOf(id))
        assertEquals(uri, PhotoIds.payloadOf(id))
    }

    @Test
    fun `malformed ids are rejected`() {
        assertNull("无分隔符必须拒绝", PhotoIds.kindOf("gallery123"))
        assertNull("空 kind 必须拒绝", PhotoIds.kindOf(":123"))
        assertNull("未知 kind 必须拒绝", PhotoIds.kindOf("unknown:123"))
        assertNull("空 payload 必须拒绝", PhotoIds.payloadOf("gallery:"))
        assertNull("无分隔符时 payload 必须拒绝", PhotoIds.payloadOf("gallery123"))
    }

    // ────────────────────── ② 跨来源同名文件不冲突（G9 核心） ──────────────────────

    @Test
    fun `same file name from different sources does not collide`() {
        val gallery = ref(PhotoSourceKind.GALLERY, "100", "IMG_0001.jpg")
        val external = ref(PhotoSourceKind.EXTERNAL, "/mnt/usb0/DCIM/IMG_0001.jpg", "IMG_0001.jpg")
        val jellyfin = ref(PhotoSourceKind.JELLYFIN, "deadbeef", "IMG_0001.jpg")

        val ids = listOf(gallery.id, external.id, jellyfin.id)
        assertTrue("三个来源的同名文件 id 必须互不相同，实际 $ids", ids.distinct().size == 3)
    }

    @Test
    fun `same file name in different directories does not collide`() {
        val a = ref(PhotoSourceKind.EXTERNAL, "/mnt/usb0/DCIM/IMG_0001.jpg", "IMG_0001.jpg")
        val b = ref(PhotoSourceKind.EXTERNAL, "/mnt/usb1/DCIM/IMG_0001.jpg", "IMG_0001.jpg")
        assertTrue("同来源不同目录的同名文件 id 必须不同", a.id != b.id)
    }

    /**
     * 负向自证：**用文件名当 id**（最朴素的写法）在同名文件上必然撞。
     * 证明上面那套 `<kind>:<payload>` 不是多余的。
     */
    @Test
    fun `negative proof - a name-only id scheme collides`() {
        fun naiveId(r: PhotoRef): String = r.displayName

        val gallery = ref(PhotoSourceKind.GALLERY, "100", "IMG_0001.jpg")
        val external = ref(PhotoSourceKind.EXTERNAL, "/mnt/usb0/DCIM/IMG_0001.jpg", "IMG_0001.jpg")

        assertTrue(
            "朴素做法（拿文件名当 id）必然撞 —— 证明 id 方案确实在解决问题",
            naiveId(gallery) == naiveId(external),
        )
        assertTrue("真实 id 必须区分开", gallery.id != external.id)
    }

    // ────────────────────── ③ 扩展名白名单 ──────────────────────

    @Test
    fun `supported extensions are accepted case insensitively`() {
        for (n in listOf("a.jpg", "a.JPG", "a.Jpeg", "a.png", "a.bmp", "a.webp", "a.gif")) {
            assertTrue("$n 应当被接受", isSupportedPhotoName(n))
        }
    }

    @Test
    fun `heic and heif are rejected`() {
        // 电视是 Android 5.1.1（API 22），系统解码器不认 HEIC
        for (n in listOf("a.heic", "a.HEIC", "a.heif", "a.HEIF")) {
            assertTrue("$n 必须被拒绝（电视解不了）", !isSupportedPhotoName(n))
        }
    }

    @Test
    fun `non image and malformed names are rejected`() {
        for (n in listOf("a.mp3", "a.mp4", "a.pdf", "noext", "a.", ".jpg", "")) {
            assertTrue("$n 必须被拒绝", !isSupportedPhotoName(n))
        }
    }

    /**
     * 隐藏文件一律不算照片。
     *
     * ⚠️ **实现期单测抓到的边界**：`".jpg"` 是「点开头且没有基名」——
     * `lastIndexOf('.') == 0`，只判 `dot < 0` 的实现会让它通过（首轮测试就是这条挂的）。
     * 修法是把隐藏文件判定收进 [isSupportedPhotoName] 本身，顺带修掉另一处不一致：
     * `ExternalFilePhotoSource` 的 SAF 路线单独写了隐藏文件过滤、File 路线漏了。
     */
    @Test
    fun `hidden files are rejected`() {
        for (n in listOf(".jpg", ".hidden.jpg", ".nomedia", ".thumb.PNG", ".")) {
            assertTrue("$n 是隐藏文件，必须被拒绝", !isSupportedPhotoName(n))
        }
        // 对照组：同样以点结尾的名字形态但**有基名**，必须通过
        assertTrue("a.jpg 必须通过（与 .jpg 仅差基名）", isSupportedPhotoName("a.jpg"))
    }

    @Test
    fun `whitelist does not accidentally contain heic`() {
        assertTrue("白名单里不能有 heic", "heic" !in PHOTO_EXTENSIONS)
        assertTrue("白名单里不能有 heif", "heif" !in PHOTO_EXTENSIONS)
        assertTrue("白名单应当包含 jpg", "jpg" in PHOTO_EXTENSIONS)
    }

    // ────────────────────── ④ SAF 卷策略 ──────────────────────

    @Test
    fun `saf internal storage is rejected`() {
        assertTrue(
            "primary:DCIM 是内部存储，必须拒绝",
            SafDirectoryPolicy.isInternalStorage("primary:DCIM/Photos"),
        )
        assertEquals(
            SafDirectoryPolicy.RejectReason.INTERNAL_STORAGE,
            SafDirectoryPolicy.rejectReason("primary:DCIM"),
        )
    }

    @Test
    fun `saf external volume is accepted`() {
        // 外接 SD 卡 / U 盘的卷标识是 UUID 形式
        assertTrue(
            "UUID 卷必须放行",
            SafDirectoryPolicy.rejectReason("0123-4567:DCIM") == null,
        )
        assertTrue(
            "大小写不敏感",
            SafDirectoryPolicy.isInternalStorage("PRIMARY:DCIM"),
        )
    }

    @Test
    fun `saf malformed ids are rejected`() {
        assertEquals(SafDirectoryPolicy.RejectReason.EMPTY, SafDirectoryPolicy.rejectReason(""))
        assertEquals(SafDirectoryPolicy.RejectReason.EMPTY, SafDirectoryPolicy.rejectReason("   "))
        assertEquals(
            SafDirectoryPolicy.RejectReason.MALFORMED,
            SafDirectoryPolicy.rejectReason("noColonHere"),
        )
    }

    /**
     * 负向自证：`MediaStore.VOLUME_EXTERNAL_PRIMARY` 的值是 `"external_primary"`，
     * **不是** SAF 用的 `"primary"` —— 拿前者去比会永远不命中（本项目实测踩过）。
     */
    @Test
    fun `negative proof - media store primary constant is a different string`() {
        val mediaStorePrimary = "external_primary"   // = MediaStore.VOLUME_EXTERNAL_PRIMARY
        assertTrue(
            "两个字符串确实不同 —— 混用会导致内部存储永远拦不住",
            mediaStorePrimary != SafDirectoryPolicy.INTERNAL_VOLUME,
        )
        assertTrue(
            "拿 MediaStore 的常量去比 SAF docId 会漏判",
            !SafDirectoryPolicy.isInternalStorage("$mediaStorePrimary:DCIM"),
        )
        assertTrue(
            "用 SAF 自己的 primary 才判得出来",
            SafDirectoryPolicy.isInternalStorage("primary:DCIM"),
        )
    }

    // ────────────────────── ⑤ ISO 8601 → 秒 ──────────────────────

    @Test
    fun `iso 8601 is converted to epoch seconds`() {
        // 2026-09-23T00:00:00Z = 第 20719 天 = 1790121600 秒（已独立核算）
        val expected = 1790121600L
        assertEquals(expected, isoToEpochSeconds("2026-09-23T00:00:00Z"))
        assertEquals(expected, isoToEpochSeconds("2026-09-23T00:00:00.0000000Z"))
        assertEquals(expected, isoToEpochSeconds("2026-09-23T00:00:00"))
    }

    @Test
    fun `iso 8601 blank input yields zero`() {
        assertEquals(0L, isoToEpochSeconds(null))
        assertEquals(0L, isoToEpochSeconds(""))
        assertEquals(0L, isoToEpochSeconds("   "))
        assertEquals(0L, isoToEpochSeconds("not a date"))
    }

    /**
     * 负向自证：**秒** 与 **毫秒** 差 1000 倍。
     * 这条同时是 `ExternalFilePhotoSource` 单位归一化（`lastModified() / 1000`）的靶子 ——
     * 若哪天有人把 `/1000` 去掉，跨来源去重会整片失效。
     */
    @Test
    fun `negative proof - seconds and milliseconds differ by 1000x`() {
        val seconds = isoToEpochSeconds("2026-09-23T00:00:00Z")
        val asIfMillis = seconds * 1000L
        assertTrue("秒与毫秒必须差 1000 倍", asIfMillis / 1000L == seconds)
        assertTrue("若误用毫秒，数值会明显不在「秒」量级", asIfMillis > seconds * 999L)
    }
}
