package com.nasmusic.tv.data.model

/**
 * 网络音乐来源枚举，编译期类型安全。
 *
 * 用法：
 * - [key] 作为 [NetworkMusicManager] services map 的键（例如 `"meting"`）
 * - [displayName] 作为设置界面展示文本（从 string resource 读取，或 fallback 到 key）
 *
 * 存储：DataStore 中持久化为 key 字符串，通过 [fromKey] / [fromName] 反序列化。
 */
enum class NetworkSource(
    /** 与 NetworkMusicManager.services map 的键一致 */
    val key: String,
    /** 设置页面显示的友好名称 */
    val displayName: String
) {
    METING("meting", "Meting"),
    ALAPI("alapi", "Alapi"),
    JIOSAAVN("jiosaavn", "JioSaavn");

    companion object {
        /**
         * 通过 key 查找枚举值。
         * 用于 DataStore 反序列化持久化的 key 字符串。
         */
        fun fromKey(key: String): NetworkSource? =
            entries.find { it.key == key }

        /**
         * 通过枚举 name 查找枚举值（向后兼容，读取旧持久化数据可能存的是 name 而非 key）。
         */
        fun fromName(name: String): NetworkSource? =
            entries.find { it.name == name }

        /** 默认值 */
        val DEFAULT = METING
    }
}
