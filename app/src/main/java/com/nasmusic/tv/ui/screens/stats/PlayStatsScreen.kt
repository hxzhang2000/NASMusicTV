package com.nasmusic.tv.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.data.stats.ArtistStat
import com.nasmusic.tv.data.stats.GenreStat
import com.nasmusic.tv.data.stats.PlayHeatmap
import com.nasmusic.tv.data.stats.StatsBundle
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 播放统计页面（F2-1 / F2-2）
 *
 * 结构：返回+标题+Tab（本月 / 累计 / 热力图）→ KPI 行（播放次数/歌曲数）→ 最爱歌手 Top10 横向列表
 * → 流派分布条形图；热力图 Tab 则展示按日期聚合的听歌热力图 + 活跃度摘要。
 *
 * 热力图**独立成 Tab 而非追加在下方**：本页 Column 不可滚动，而 TV 上无焦点的滚动容器
 * 无法用遥控器驱动，堆在下方的内容会直接被裁掉。
 *
 * D-Pad 焦点：返回 → Tab → 歌手行（横向可滚动）。热力图为纯展示，不入焦点链。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayStatsScreen(
    onBack: () -> Unit,
    viewModel: com.nasmusic.tv.ui.viewmodel.PlayStatsViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val monthly by viewModel.monthlyBundle.collectAsState()
    val allTime by viewModel.allTimeBundle.collectAsState()
    val heatmap by viewModel.heatmap.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var tab by remember { mutableStateOf(StatsTab.MONTHLY) }

    LaunchedEffect(Unit) { viewModel.loadStats() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // 标题行
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.pstats_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title()
            )
            Spacer(modifier = Modifier.weight(1f))
            // Tab 切换：本月 / 累计 / 热力图
            StatsTab.entries.forEachIndexed { index, item ->
                if (index > 0) Spacer(modifier = Modifier.width(8.dp))
                StatsTabButton(
                    text = stringResource(item.labelRes),
                    selected = tab == item,
                    onClick = { tab = item }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (tab == StatsTab.HEATMAP) {
            HeatmapContent(heatmap = heatmap, loading = loading)
        } else {
            val bundle = if (tab == StatsTab.ALL_TIME) allTime else monthly
            when {
                loading && bundle == null -> StatsPlaceholder(R.string.pstats_loading)
                bundle == null || (bundle.totalPlays == 0 && bundle.topArtists.isEmpty()) ->
                    StatsPlaceholder(R.string.pstats_empty)
                else -> StatsContent(bundle)
            }
        }
    }
}

/** 统计页 Tab；[labelRes] 为文案资源，顺序即显示顺序 */
private enum class StatsTab(val labelRes: Int) {
    MONTHLY(R.string.pstats_tab_monthly),
    ALL_TIME(R.string.pstats_tab_alltime),
    HEATMAP(R.string.pstats_tab_heatmap)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsPlaceholder(textRes: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(textRes),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body()
        )
    }
}

/**
 * 热力图 Tab 内容：标题 + 日期范围 + 网格 + 活跃度摘要。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeatmapContent(heatmap: PlayHeatmap?, loading: Boolean) {
    if (heatmap == null) {
        StatsPlaceholder(if (loading) R.string.pstats_loading else R.string.pstats_empty)
        return
    }
    if (heatmap.isEmpty) {
        StatsPlaceholder(R.string.pstats_heatmap_empty)
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.pstats_heatmap_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.subtitle(),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.pstats_heatmap_range,
                    heatmap.startDateKey,
                    heatmap.endDateKey
                ),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption()
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        PlayHeatmapChart(heatmap = heatmap)

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MiniStat(
                value = stringResource(R.string.pstats_heatmap_days_format, heatmap.activeDays),
                label = stringResource(R.string.pstats_heatmap_active_days),
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                value = stringResource(R.string.pstats_heatmap_days_format, heatmap.longestStreak),
                label = stringResource(R.string.pstats_heatmap_longest_streak),
                modifier = Modifier.weight(1f)
            )
            MiniStat(
                value = heatmap.bestDay?.dateKey ?: "-",
                label = stringResource(R.string.pstats_heatmap_best_day),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(NasMusicColors.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = value,
            color = NasMusicColors.Primary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.caption()
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsContent(bundle: StatsBundle) {
    // KPI 行
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        KpiCard(
            value = stringResource(R.string.pstats_plays_format, bundle.totalPlays),
            label = stringResource(R.string.pstats_total_plays),
            modifier = Modifier.weight(1f)
        )
        KpiCard(
            value = stringResource(R.string.pstats_songs_format, bundle.distinctSongs),
            label = stringResource(R.string.pstats_distinct_songs),
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // 最爱歌手
    if (bundle.topArtists.isNotEmpty()) {
        Text(
            text = stringResource(R.string.pstats_top_artists),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(bundle.topArtists, key = { it.artist }) { artist ->
                ArtistCard(artist)
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // 流派分布
    if (bundle.genreDistribution.isNotEmpty()) {
        Text(
            text = stringResource(R.string.pstats_genre_distribution),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        GenreBarChart(
            genres = bundle.genreDistribution,
            modifier = Modifier
                .fillMaxWidth()
                .height((bundle.genreDistribution.size * 44).coerceAtMost(320).dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KpiCard(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(NasMusicColors.Surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = NasMusicColors.Primary,
            fontSize = FontSize.title(),
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.caption()
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistCard(artist: ArtistStat) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(120.dp)
    ) {
        // 封面占位（圆形，未加载图片——首期用色块，避免引入图片加载依赖）
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(NasMusicColors.Primary.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = artist.artist.take(1).ifBlank { "?" },
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title(),
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.artist,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.caption(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.pstats_plays_format, artist.playCount),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.small()
        )
    }
}

/** 流派分布横向条形图（原生 Canvas，不引第三方图表库） */
@Composable
private fun GenreBarChart(genres: List<GenreStat>, modifier: Modifier = Modifier) {
    val maxCount = genres.maxOf { it.playCount }.coerceAtLeast(1)
    val accent = NasMusicColors.Primary
    val trackColor = NasMusicColors.Surface
    val labelColor = NasMusicColors.TextPrimary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        genres.forEach { g ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = g.genre,
                        color = labelColor,
                        fontSize = FontSize.caption(),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${g.playCount}",
                        color = labelColor,
                        fontSize = FontSize.caption()
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(8.dp)) {
                    drawRoundRect(
                        color = trackColor,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        size = Size(size.width, size.height)
                    )
                    drawRoundRect(
                        color = accent,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        topLeft = Offset.Zero,
                        size = Size(size.width * g.playCount / maxCount, size.height)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsTabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    com.nasmusic.tv.ui.components.FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = text,
            color = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
