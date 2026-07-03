package com.nasmusic.tv.data.model

/**
 * 网络音乐平台来源枚举
 *
 * 对应 Meting-API 的 server 参数值。
 * 用于网络音乐 Tab 中的来源选择器。
 */
enum class MusicSource(val apiKey: String, val displayName: String) {
    NETEASE("netease", "网易云"),
    QQ("tencent", "QQ音乐"),
    KUGOU("kugou", "酷狗"),
    KUWO("kuwo", "酷我"),
    MIGU("migu", "咪咕");

    companion object {
        /** 默认来源：网易云 */
        const val DEFAULT_API_KEY = "netease"

        /** 通过 apiKey 查找枚举值 */
        fun fromApiKey(key: String): MusicSource =
            entries.find { it.apiKey == key } ?: NETEASE

        /** 获取所有支持的 apiKey 列表 */
        fun allApiKeys(): List<String> = entries.map { it.apiKey }
    }
}
