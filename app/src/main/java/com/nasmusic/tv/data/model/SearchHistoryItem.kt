package com.nasmusic.tv.data.model

/**
 * 搜索历史条目
 *
 * 用户在搜索输入框提交关键词时写入。
 * 同一关键词重复搜索会合并为一条（count 累加、lastSearchedAt 更新）。
 * 持久化到 DataStore，30 天 TTL 自动清理。
 */
data class SearchHistoryItem(
    val query: String,
    val lastSearchedAt: Long = System.currentTimeMillis(),
    val count: Int = 1
)
