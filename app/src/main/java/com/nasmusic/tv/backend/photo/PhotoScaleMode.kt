package com.nasmusic.tv.backend.photo

/**
 * 照片「画面适配」方式 —— 照片宽高比与屏幕不一致时怎么处理
 *
 * ⛔ **与横竖屏无关**：这是「图片比例 vs 屏幕比例」的取舍，
 * 电视横屏放竖幅照片、手机竖屏放横幅照片，都是同一个问题
 * ⇒ **四端（电视 / 手机横 / 手机竖）都必须暴露该设置**。
 *
 * （字段名曾为 `photoWallPortraitFit`，那个 `Portrait` 前缀是早期草稿的误导，
 * 已改名 —— 命名要描述**问题的本质**，不是**首次想到的场景**。）
 */
enum class PhotoScaleMode {
    /** 满屏：按短边铺满，超出部分裁掉（默认，无黑边） */
    CROP,

    /** 完整：整图缩放到能放下，不足处留黑边（不裁切） */
    FIT,
    ;

    companion object {
        val Default: PhotoScaleMode = CROP

        fun fromKey(key: String?): PhotoScaleMode =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: Default
    }
}

/**
 * SAF 目录选择的**卷策略**（§6.3）
 *
 * ⛔ **必须拒绝内部存储**（决策：两平台统一只读外接卷）：
 * - 手机：图库已覆盖内部存储的绝大部分照片，再扫一遍纯属重复
 * - 电视：内部存储放照片概率极低，且 API 22 上内外卷判别不可靠
 *
 * ⚠️ **注意两个容易混淆的字符串**（本项目实测踩过）：
 * - SAF 的 tree docId 用 **`"primary"`** 表示内部存储卷（AOSP `ExternalStorageProvider`）
 * - `MediaStore.VOLUME_EXTERNAL_PRIMARY` 的值是 **`"external_primary"`** —— **不是同一个字符串**
 * ⇒ 校验 SAF 必须比 `"primary"`，拿 MediaStore 的常量来比会**永远不命中**。
 */
object SafDirectoryPolicy {

    /** SAF 内部存储卷标识（AOSP `ExternalStorageProvider` 的 `"primary"`） */
    const val INTERNAL_VOLUME = "primary"

    /**
     * 从 tree docId 取卷标识。
     *
     * docId 形如 `"primary:DCIM/Photos"` 或 `"0123-4567:DCIM"`。
     * 不含 `:` 时（异常输入）返回整串。
     */
    fun volumeOf(treeDocId: String): String = treeDocId.substringBefore(':').trim()

    /** 该 tree 是否落在内部存储上（⇒ 必须拒绝） */
    fun isInternalStorage(treeDocId: String): Boolean =
        volumeOf(treeDocId).equals(INTERNAL_VOLUME, ignoreCase = true)

    /**
     * 校验一个用户选中的 tree docId 是否可用。
     *
     * @return null 表示可用；否则返回拒绝原因（供上层映射成文案）
     */
    fun rejectReason(treeDocId: String): RejectReason? {
        val id = treeDocId.trim()
        if (id.isEmpty()) return RejectReason.EMPTY
        if (!id.contains(':')) return RejectReason.MALFORMED
        if (isInternalStorage(id)) return RejectReason.INTERNAL_STORAGE
        return null
    }

    /** 拒绝原因（数据层枚举，文案在 `strings.xml`） */
    enum class RejectReason {
        /** docId 为空 */
        EMPTY,

        /** docId 格式异常（缺卷分隔符） */
        MALFORMED,

        /** 落在内部存储上 */
        INTERNAL_STORAGE,
    }
}
