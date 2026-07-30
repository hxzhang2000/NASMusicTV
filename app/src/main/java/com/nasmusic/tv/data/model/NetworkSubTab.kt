package com.nasmusic.tv.data.model

/**
 * 网络音乐页子 Tab 枚举
 */
enum class NetworkSubTab(val displayNameResId: Int) {
    DISCOVER(com.nasmusic.tv.R.string.network_tab_discover),
    WEATHER(com.nasmusic.tv.R.string.network_tab_weather),
    CHARTS(com.nasmusic.tv.R.string.network_tab_charts),
    SEARCH(com.nasmusic.tv.R.string.network_tab_search),
    BROWSE(com.nasmusic.tv.R.string.network_tab_browse)
}
