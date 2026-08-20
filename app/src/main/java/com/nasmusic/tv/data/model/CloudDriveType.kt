package com.nasmusic.tv.data.model

/**
 * 网盘类型枚举（可扩展）
 *
 * 首批仅实现百度网盘，阿里云盘/123 网盘/夸克网盘为占位（设置页灰显"敬请期待"）。
 *
 * @param key         AppPreferences 存储键（与 sourceId 一致）
 * @param displayName 设置页显示名
 */
enum class CloudDriveType(
    val key: String,
    val displayName: String
) {
    BAIDU("baidu", "百度网盘"),
    ALIYUN("aliyun", "阿里云盘"),
    DRIVE_123("123", "123网盘"),
    QUARK("quark", "夸克网盘");

    companion object {
        /** 首批已实现的网盘类型 */
        val IMPLEMENTED: Set<CloudDriveType> = setOf(BAIDU)
        /** 占位未实现的（设置页灰显"敬请期待"） */
        val PLACEHOLDER: Set<CloudDriveType> = entries.toSet() - IMPLEMENTED

        fun fromKey(key: String?): CloudDriveType? = entries.find { it.key == key }
    }
}
