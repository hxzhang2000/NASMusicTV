package com.nasmusic.tv.data.model

import androidx.annotation.StringRes
import com.nasmusic.tv.R

/**
 * 浏览维度枚举。
 *
 * 每个维度有若干选项（[Option]），选项包含搜索关键词列表。
 * 当用户选择某选项后，所有选中维度非 ALL 的选项关键词拼接成搜索词。
 *
 * 本地化：UI 展示一律用 [displayNameRes] / [Option.labelRes]（由 UI 层 stringResource 映射）；
 * [displayName] / [Option.label] 保留中文原值，仅作数据用途（如 buildLabelCombo 拼搜索词），
 * ⛔ 不得在 UI 展示或逻辑判定中直接使用。
 */
enum class BrowseDimension(
    /** 维度显示名资源 ID（本地化展示用） */
    @StringRes val displayNameRes: Int,
    /** 维度显示名（如"语种""纯音乐"）——数据用途，UI 展示走 [displayNameRes] */
    val displayName: String,
    /** 该维度下的可选值 */
    val options: List<Option>
) {
    LANGUAGE(
        displayNameRes = R.string.browse_dim_language,
        displayName = "语种",
        options = listOf(
            Option.ALL,
            Option(labelRes = R.string.browse_opt_cantonese, label = "粤语", keywords = listOf("粤语歌", "粤语歌曲", "粤语经典", "粤语金曲")),
            Option(labelRes = R.string.browse_opt_mandarin, label = "国语", keywords = listOf("国语歌", "国语歌曲", "国语经典", "华语金曲")),
            Option(labelRes = R.string.browse_opt_english, label = "英语", keywords = listOf("英语歌", "英文歌曲", "English songs", "欧美热歌")),
            Option(labelRes = R.string.browse_opt_japanese, label = "日语", keywords = listOf("日语歌", "日语歌曲", "日语流行", "JPOP")),
            Option(labelRes = R.string.browse_opt_korean, label = "韩语", keywords = listOf("韩语歌", "韩语歌曲", "韩文歌", "KPOP"))
        )
    ),
    INSTRUMENT(
        displayNameRes = R.string.browse_dim_instrument,
        displayName = "纯音乐",
        options = listOf(
            Option.ALL,
            Option(labelRes = R.string.browse_opt_saxophone, label = "萨克斯", keywords = listOf("萨克斯曲", "萨克斯独奏", "萨克斯纯音乐", "萨克斯名曲")),
            Option(labelRes = R.string.browse_opt_flute, label = "笛子", keywords = listOf("笛子曲", "笛子独奏", "笛子纯音乐", "竹笛名曲")),
            Option(labelRes = R.string.browse_opt_guitar, label = "吉他", keywords = listOf("吉他曲", "吉他独奏", "吉他纯音乐", "吉他名曲")),
            Option(labelRes = R.string.browse_opt_piano, label = "钢琴", keywords = listOf("钢琴曲", "钢琴独奏", "钢琴纯音乐", "钢琴名曲")),
            Option(labelRes = R.string.browse_opt_guzheng, label = "古筝", keywords = listOf("古筝曲", "古筝独奏", "古筝纯音乐", "古筝名曲")),
            Option(labelRes = R.string.browse_opt_erhu, label = "二胡", keywords = listOf("二胡曲", "二胡独奏", "二胡纯音乐", "二胡名曲")),
            Option(labelRes = R.string.browse_opt_violin, label = "小提琴", keywords = listOf("小提琴曲", "小提琴独奏", "小提琴名曲"))
        )
    ),
    ERA(
        displayNameRes = R.string.browse_dim_era,
        displayName = "年代",
        options = listOf(
            Option.ALL,
            Option(labelRes = R.string.browse_opt_post70, label = "70后", keywords = listOf("70年代金曲", "70年代经典", "70年代老歌")),
            Option(labelRes = R.string.browse_opt_post80, label = "80后", keywords = listOf("80年代金曲", "80年代经典", "80年代老歌")),
            Option(labelRes = R.string.browse_opt_post90, label = "90后", keywords = listOf("90年代金曲", "90年代经典", "90年代老歌")),
            Option(labelRes = R.string.browse_opt_post00, label = "00后", keywords = listOf("00年代金曲", "00年代经典", "00年代歌曲"))
        )
    ),
    NOSTALGIA(
        displayNameRes = R.string.browse_dim_nostalgia,
        displayName = "情怀",
        options = listOf(
            Option.ALL,
            Option(labelRes = R.string.browse_opt_red, label = "红歌", keywords = listOf("红歌", "红色歌曲", "革命歌曲")),
            Option(labelRes = R.string.browse_opt_grassland, label = "草原", keywords = listOf("草原歌曲", "草原歌", "草原金曲")),
            Option(labelRes = R.string.browse_opt_folk, label = "民歌", keywords = listOf("民歌", "民歌金曲", "经典民歌"))
        )
    ),
    STYLE(
        displayNameRes = R.string.browse_dim_style,
        displayName = "风格",
        options = listOf(
            Option.ALL,
            Option(labelRes = R.string.browse_opt_ballad, label = "民谣", keywords = listOf("民谣", "民谣歌曲", "民谣经典")),
            Option(labelRes = R.string.browse_opt_rock, label = "摇滚", keywords = listOf("摇滚", "摇滚歌曲", "摇滚金曲")),
            Option(labelRes = R.string.browse_opt_guofeng, label = "古风", keywords = listOf("古风", "古风歌曲", "古风金曲")),
            Option(labelRes = R.string.browse_opt_rap, label = "说唱", keywords = listOf("说唱", "中文说唱", "说唱歌曲"))
        )
    ),
    THEME(
        displayNameRes = R.string.browse_dim_theme,
        displayName = "主题",
        options = listOf(
            Option.ALL,
            Option(labelRes = R.string.browse_opt_travel, label = "旅行", keywords = listOf("旅行", "旅行歌曲", "旅游音乐", "公路旅行")),
            Option(labelRes = R.string.browse_opt_driving, label = "驾车", keywords = listOf("驾车", "开车音乐", "公路音乐", "车载音乐")),
            Option(labelRes = R.string.browse_opt_coffee, label = "咖啡", keywords = listOf("咖啡", "咖啡厅音乐", "咖啡馆", "下午茶音乐")),
            Option(labelRes = R.string.browse_opt_sports, label = "运动", keywords = listOf("运动", "健身音乐", "跑步音乐", "锻炼歌曲")),
            Option(labelRes = R.string.browse_opt_rainy, label = "雨天", keywords = listOf("雨天", "下雨天", "雨天音乐", "雨声")),
            Option(labelRes = R.string.browse_opt_home, label = "居家", keywords = listOf("居家", "宅家音乐", "放松音乐", "治愈音乐"))
        )
    );

    /**
     * 浏览选项。
     *
     * M8 修复（2026-10-06，代码审查报告 §4）：新增 [isAll] 结构化标志——此前
     * 调用方用 `opt.label == "所有"` 文案哨兵判定，label 一旦本地化（EN locale）
     * 「所有」过滤与维度判断会整体静默失效。isAll 不依赖文案，展示名可自由本地化。
     *
     * @param labelRes 选项展示名资源 ID（本地化展示用）
     * @param label 显示名（中文原值），如"粤语""萨克斯"。数据用途（如 buildLabelCombo
     *   拼搜索词），UI 展示走 [labelRes]。⛔ 不得在 UI 展示或逻辑判定中直接使用。
     * @param keywords 构建搜索词时随机取一个，ALL 时此列表无关。
     */
    data class Option(
        @StringRes val labelRes: Int,
        val label: String,
        val keywords: List<String> = emptyList(),
        /** 该选项是否为「所有」（不参与搜索词拼接）。判定一律用此标志，⛔ 不得比较 label 文案 */
        val isAll: Boolean = false
    ) {
        companion object {
            /** "所有"选项，表示该维度不参与搜索词拼接 */
            val ALL = Option(labelRes = R.string.browse_opt_all, label = "所有", isAll = true)
        }
    }
}