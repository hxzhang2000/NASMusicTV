package com.nasmusic.tv.ui.screens.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.stats.PlayHeatmap
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 听歌热力图（F2-2）——GitHub 贡献图样式的「按日期看播放」。
 *
 * 结构：左侧星期标签列 + 顶部月份标签行 + 7×N 周网格（Canvas 一次性绘制）+ 底部色阶图例。
 *
 * 布局自适应：格子边长由可用宽度反推并夹在 3…16dp 之间——电视（宽屏）格子大、
 * 手机（窄屏）自动缩小，两边都不会溢出或需要横向滚动（TV 上无焦点容器无法用遥控器滚动）。
 * 纯展示组件，**不参与 D-Pad 焦点链**，避免打断页面原有的 返回 → Tab 焦点顺序。
 *
 * @param heatmap [com.nasmusic.tv.data.stats.PlayHeatmapBuilder.build] 的产物
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayHeatmapChart(heatmap: PlayHeatmap, modifier: Modifier = Modifier) {
    val cols = heatmap.weeks.size
    if (cols == 0) return

    // 0 = 无播放；1…4 由浅到深（与 PlayHeatmapBuilder.LEVELS 对应）
    val levelColors = remember {
        listOf(
            NasMusicColors.SurfaceVariant,
            NasMusicColors.Primary.copy(alpha = 0.28f),
            NasMusicColors.Primary.copy(alpha = 0.50f),
            NasMusicColors.Primary.copy(alpha = 0.74f),
            NasMusicColors.Primary
        )
    }
    val labelColor = NasMusicColors.TextSecondary
    val captionSize = FontSize.small()
    // 行 0 = 周日；只标注一/三/五，避免密集文字（与 GitHub 同策略）
    val weekdayLabels = listOf(
        "",
        stringResource(R.string.pstats_weekday_mon),
        "",
        stringResource(R.string.pstats_weekday_wed),
        "",
        stringResource(R.string.pstats_weekday_fri),
        ""
    )

    val labelColumnWidth = 26.dp
    val monthRowHeight = 18.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gridAvailable = (maxWidth - labelColumnWidth).coerceAtLeast(120.dp)
        // 间距取格子的 1/4（而非固定值）——固定间距在窄屏上会让 53 列的总宽超出可用宽度，
        // 被 cell 的下限截断后反而溢出屏幕。总宽 = cols*cell + (cols-1)*cell/4。
        val cell: Dp = (gridAvailable / (cols + (cols - 1) * 0.25f)).coerceIn(3.dp, 16.dp)
        val gap: Dp = (cell * 0.25f).coerceAtLeast(0.5.dp)
        val gridWidth: Dp = cell * cols + gap * (cols - 1)
        val gridHeight: Dp = cell * 7 + gap * 6
        // 格子过小时（手机窄屏）挤不下星期文字，直接隐藏，只保留网格
        val showWeekdayLabels = cell >= 9.dp

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                // ---- 星期标签列（行 0 = 周日）----
                Column(modifier = Modifier.width(labelColumnWidth)) {
                    Spacer(modifier = Modifier.height(monthRowHeight))
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (r in 0 until 7) {
                            Box(
                                modifier = Modifier.height(cell),
                                contentAlignment = Alignment.Center
                            ) {
                                if (showWeekdayLabels && weekdayLabels[r].isNotEmpty()) {
                                    Text(
                                        text = weekdayLabels[r],
                                        color = labelColor,
                                        fontSize = captionSize
                                    )
                                }
                            }
                        }
                    }
                }

                Column {
                    // ---- 月份标签行（绝对定位到所属列）----
                    Box(modifier = Modifier.width(gridWidth).height(monthRowHeight)) {
                        heatmap.monthLabels.forEach { ml ->
                            Text(
                                text = ml.text,
                                color = labelColor,
                                fontSize = captionSize,
                                modifier = Modifier.offset(x = (cell + gap) * ml.weekIndex)
                            )
                        }
                    }

                    // ---- 网格 ----
                    Canvas(modifier = Modifier.width(gridWidth).height(gridHeight)) {
                        val c = cell.toPx()
                        val g = gap.toPx()
                        val radius = CornerRadius(c * 0.25f)
                        heatmap.weeks.forEachIndexed { wi, week ->
                            week.forEachIndexed dayLoop@{ ri, day ->
                                if (day == null) return@dayLoop // 窗口外/未来日期留空
                                val x = wi * (c + g)
                                val y = ri * (c + g)
                                drawRoundRect(
                                    color = levelColors.getOrElse(day.level) { levelColors[0] },
                                    topLeft = Offset(x, y),
                                    size = Size(c, c),
                                    cornerRadius = radius
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ---- 色阶图例 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pstats_heatmap_less),
                    color = labelColor,
                    fontSize = captionSize
                )
                Spacer(modifier = Modifier.width(8.dp))
                levelColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(color, RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                }
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.pstats_heatmap_more),
                    color = labelColor,
                    fontSize = captionSize
                )
            }
        }
    }
}
