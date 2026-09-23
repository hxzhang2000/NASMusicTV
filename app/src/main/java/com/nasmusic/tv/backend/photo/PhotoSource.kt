package com.nasmusic.tv.backend.photo

import java.io.InputStream

/**
 * 照片来源种类
 *
 * 三个来源**互相独立**（各有自己的开关），合并成一个池随机展示（§6.8）。
 */
enum class PhotoSourceKind {
    /** 手机系统图库（MediaStore 索引） */
    GALLERY,

    /** 外接存储：U 盘 / SD 卡（File 遍历主 + SAF 兜底，§6.3） */
    EXTERNAL,

    /** Jellyfin 照片库（`IncludeItemTypes=Photo`，§6.4） */
    JELLYFIN,
}

/**
 * 来源可用性
 *
 * ⚠️ 用枚举而不是 String：**数据层不产出面向用户的文案**
 * （文案统一在 `strings.xml`，项目硬约定）。设置页拿到枚举后自己映射文案。
 */
enum class PhotoSourceStatus {
    /** 可用 */
    OK,

    /** 开关关着 */
    DISABLED,

    /** 权限被拒（仅图库） */
    PERMISSION_DENIED,

    /** Android 14+「仅选择照片」的部分授权（仅图库，§9.6） */
    PARTIAL_PERMISSION,

    /** 手机外接存储尚未指定目录 */
    NO_DIRECTORY,

    /** Jellyfin：未连接 NAS */
    NOT_CONNECTED,

    /** Jellyfin：已连接但没有照片库 */
    NO_PHOTO_LIBRARY,

    /** 其他（盘已拔 / 目录不可达 / SAF 授权失效） */
    UNAVAILABLE,
}

/**
 * 一张照片的**元数据**（不含位图）
 *
 * ⛔ **宽高契约**（§14.2.1）：`width` / `height` 是**可选**元数据，`0` = 未探测。
 * - `MediaStore` / `Jellyfin` 查询时**免费**拿到宽高 ⇒ 必须填真实值
 * - `ExternalFilePhotoSource` 要拿宽高得**每个文件开一次流**（`inJustDecodeBounds`）
 *   ⇒ 填 `0`，由 `PhotoBuffer` 在解码那一刻现算（反正已经拿到流了）
 *
 * ⛔ **时间契约**：`lastModified` / `dateAdded` 一律**秒**。
 * 归一化责任在**各 PhotoSource 实现内**（`MediaStore` 与 `File` 原生就是秒；
 * Jellyfin 返回 ISO 8601 或 tick，必须转换）—— 因为跨来源去重依赖它（§6.1）。
 */
data class PhotoRef(
    /** `"<kind>:<payload>"` —— 全局唯一（见 [PhotoIds]） */
    val id: String,
    val displayName: String,
    /** ⚠️ 0 = 未探测（见上方「宽高契约」） */
    val width: Int,
    val height: Int,
    /** 字节数；未知填 0 */
    val size: Long,
    /** ⚠️ **秒** */
    val lastModified: Long,
    /** ⚠️ **秒** */
    val dateAdded: Long,
    val source: PhotoSourceKind,
)

/**
 * 照片来源抽象
 *
 * 实现类必须是**可重复调用**的：`listPhotos()` 不缓存（缓存由上层
 * `PhotoWallController` / `PhotoBuffer` 负责），`openStream()` 每次都开新流。
 */
interface PhotoSource {

    val kind: PhotoSourceKind

    /**
     * 当前状态（供设置页显示「为什么不可用」）。
     *
     * ⛔ **不申请权限、不扫描** —— 只做轻量判定，可被设置页高频调用。
     */
    suspend fun status(): PhotoSourceStatus

    /**
     * 只读元数据，**不加载位图**。
     *
     * 不可用时返回**空表**（不抛异常）—— 调用方用 [status] 区分「空」与「不可用」。
     */
    suspend fun listPhotos(): List<PhotoRef>

    /**
     * 打开原始字节流（供 `PhotoBuffer` 降采样解码）。
     *
     * @return 失败返回 null（不抛异常）
     */
    suspend fun openStream(ref: PhotoRef): InputStream?
}

/**
 * `PhotoRef.id` 的构造 / 解析工具
 *
 * 格式 `"<kind>:<payload>"`，`kind` 用小写枚举名。
 * ⛔ 全局唯一性靠 **payload 本身带来源内唯一标识**（MediaStore 行 id / 绝对路径 /
 * SAF document URI / Jellyfin item id）保证 —— 不同来源的**同名文件**不会冲突。
 */
object PhotoIds {

    private const val SEPARATOR = ':'

    fun of(kind: PhotoSourceKind, payload: String): String =
        kind.name.lowercase() + SEPARATOR + payload

    /** 解析来源；格式非法返回 null */
    fun kindOf(id: String): PhotoSourceKind? {
        val head = id.substringBefore(SEPARATOR, missingDelimiterValue = "")
        if (head.isEmpty() || !id.contains(SEPARATOR)) return null
        return PhotoSourceKind.entries.firstOrNull { it.name.equals(head, ignoreCase = true) }
    }

    /** 解析 payload（`:` 之后**全部**内容 —— 绝对路径 / URI 里都可能有 `:`） */
    fun payloadOf(id: String): String? {
        val idx = id.indexOf(SEPARATOR)
        if (idx <= 0 || idx == id.length - 1) return null
        return id.substring(idx + 1)
    }
}

/**
 * 支持的图片扩展名**白名单**（小写，不含点）
 *
 * ⛔ **用白名单不用黑名单** —— 而且要**显式排除 `heic` / `heif`**：
 * 电视是 Android 5.1.1（API 22），系统解码器不认 HEIC，放进去只会得到一堆解码失败。
 */
val PHOTO_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "bmp", "webp", "gif")

/**
 * 文件名是否属于支持的图片格式（大小写不敏感）。
 *
 * ⛔ **这是「这个文件名算不算照片」的唯一定义处** —— 三条来源（MediaStore / File 遍历 /
 * SAF 遍历）都必须过这里，否则会出现「同一种文件在一条路上被排除、在另一条路上被纳入」
 * 的不一致（实现期实测：SAF 路线单独写了隐藏文件过滤，File 路线漏了）。
 *
 * ⛔ **以点开头一律拒绝**（Unix 隐藏文件惯例）：
 * - `.nomedia` —— 这个目录的标记文件本身不是照片
 * - `.thumb.jpg` / `.a.jpg` —— 隐藏文件不该展示
 * - `.jpg` —— 点开头且**没有基名**，`lastIndexOf('.') == 0` 是同一个坑的极端形态
 *   （实现期单测抓到的边界：只判 `dot < 0` 会让它通过）
 */
fun isSupportedPhotoName(fileName: String): Boolean {
    if (fileName.startsWith(".")) return false
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0 || dot == fileName.length - 1) return false
    return fileName.substring(dot + 1).lowercase() in PHOTO_EXTENSIONS
}
