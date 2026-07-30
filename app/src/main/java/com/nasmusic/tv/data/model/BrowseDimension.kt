package com.nasmusic.tv.data.model

/**
 * 浏览维度枚举。
 *
 * 每个维度有若干选项（[Option]），选项包含搜索关键词列表。
 * 当用户选择某选项后，所有选中维度非 ALL 的选项关键词拼接成搜索词。
 */
enum class BrowseDimension(
    /** 维度显示名（如"语种""纯音乐"） */
    val displayName: String,
    /** 该维度下的可选值 */
    val options: List<Option>
) {
    LANGUAGE(
        displayName = "语种",
        options = listOf(
            Option.ALL,
            Option("粤语", listOf("粤语歌", "粤语歌曲", "粤语经典", "粤语金曲")),
            Option("国语", listOf("国语歌", "国语歌曲", "国语经典", "华语金曲")),
            Option("英语", listOf("英语歌", "英文歌曲", "English songs", "欧美热歌")),
            Option("日语", listOf("日语歌", "日语歌曲", "日语流行", "JPOP")),
            Option("韩语", listOf("韩语歌", "韩语歌曲", "韩文歌", "KPOP"))
        )
    ),
    INSTRUMENT(
        displayName = "纯音乐",
        options = listOf(
            Option.ALL,
            Option("萨克斯", listOf("萨克斯曲", "萨克斯独奏", "萨克斯纯音乐", "萨克斯名曲")),
            Option("笛子", listOf("笛子曲", "笛子独奏", "笛子纯音乐", "竹笛名曲")),
            Option("吉他", listOf("吉他曲", "吉他独奏", "吉他纯音乐", "吉他名曲")),
            Option("钢琴", listOf("钢琴曲", "钢琴独奏", "钢琴纯音乐", "钢琴名曲")),
            Option("古筝", listOf("古筝曲", "古筝独奏", "古筝纯音乐", "古筝名曲")),
            Option("二胡", listOf("二胡曲", "二胡独奏", "二胡纯音乐", "二胡名曲")),
            Option("小提琴", listOf("小提琴曲", "小提琴独奏", "小提琴名曲"))
        )
    ),
    ERA(
        displayName = "年代",
        options = listOf(
            Option.ALL,
            Option("70后", listOf("70年代金曲", "70年代经典", "70年代老歌")),
            Option("80后", listOf("80年代金曲", "80年代经典", "80年代老歌")),
            Option("90后", listOf("90年代金曲", "90年代经典", "90年代老歌")),
            Option("00后", listOf("00年代金曲", "00年代经典", "00年代歌曲"))
        )
    ),
    NOSTALGIA(
        displayName = "情怀",
        options = listOf(
            Option.ALL,
            Option("红歌", listOf("红歌", "红色歌曲", "革命歌曲")),
            Option("草原", listOf("草原歌曲", "草原歌", "草原金曲")),
            Option("民歌", listOf("民歌", "民歌金曲", "经典民歌"))
        )
    ),
    STYLE(
        displayName = "风格",
        options = listOf(
            Option.ALL,
            Option("民谣", listOf("民谣", "民谣歌曲", "民谣经典")),
            Option("摇滚", listOf("摇滚", "摇滚歌曲", "摇滚金曲")),
            Option("古风", listOf("古风", "古风歌曲", "古风金曲")),
            Option("说唱", listOf("说唱", "中文说唱", "说唱歌曲"))
        )
    );

    /**
     * 浏览选项。
     *
     * @param label 显示名，如"粤语""萨克斯"。ALL 保留特殊含义。
     * @param keywords 构建搜索词时随机取一个，ALL 时此列表无关。
     */
    data class Option(
        val label: String,
        val keywords: List<String> = emptyList()
    ) {
        companion object {
            /** "所有"选项，表示该维度不参与搜索词拼接 */
            val ALL = Option("所有")
        }
    }
}
