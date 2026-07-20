package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * 可视化均衡器柱状频谱图
 *
 * NowPlaying 页面底部的频谱动画组件。
 * 模拟频段能量，动态生成随机柱状条高度。
 *
 * @param isPlaying 是否在播放（播放时动画活跃，暂停时衰减）
 * @param barCount 柱状条数量
 * @param barWidth 每根柱子的宽度
 * @param barSpacing 柱子间距
 * @param maxBarHeight 最大柱子高度（百分比 of container height）
 * @param modifier Modifier
 */
@Composable
fun VisualEqualizer(
    isPlaying: Boolean,
    barCount: Int = 32,
    barWidth: Dp = 4.dp,
    barSpacing: Dp = 2.dp,
    maxBarHeight: Float = 0.85f,
    modifier: Modifier = Modifier
) {
    // 存储每根柱子的当前高度（0..1）
    val barHeights = remember { Array(barCount) { 0f } }
    // 目标高度（用于平滑过渡）
    val targetHeights = remember { Array(barCount) { 0f } }
    // 每个柱子的相位偏移（龙卷风效果：不同柱子节奏不同）
    val phases = remember {
        Array(barCount) { Random.nextFloat() * kotlin.math.PI.toFloat() * 2f }
    }

    // 动画更新：定时刷新柱子高度
    LaunchedEffect(isPlaying) {
        while (true) {
            kotlinx.coroutines.delay(if (isPlaying) 80 else 200)

            if (isPlaying) {
                // 播放中：模拟频谱能量
                // 低频（左侧）倾向于更高，高频（右侧）倾向于更低
                for (i in 0 until barCount) {
                    val freqRatio = i.toFloat() / barCount
                    // 低频能量更高（模拟真实频谱特征）
                    val baseGain = 1.0f - freqRatio * 0.6f
                    // 随机波动
                    val phase = phases[i] + (System.nanoTime().toFloat() / 1e8f) * (0.5f + freqRatio * 2f)
                    val randomFactor = abs(sin(phase))
                    val energy = baseGain * randomFactor
                    // 用噪声产生更多变化
                    val noise = Random.nextFloat() * 0.2f
                    targetHeights[i] = (energy + noise).coerceIn(0.05f, 1.0f)
                }
            } else {
                // 暂停中：柱子逐渐衰减归零
                for (i in 0 until barCount) {
                    targetHeights[i] *= 0.85f
                    if (targetHeights[i] < 0.01f) targetHeights[i] = 0f
                }
            }

            // 平滑过渡：向目标靠近
            for (i in 0 until barCount) {
                barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(NasMusicColors.Surface.copy(alpha = 0.15f))
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            val barWidthPx = barWidth.toPx()
            val barSpacingPx = barSpacing.toPx()
            val totalBarWidth = barWidthPx * barCount
            val totalSpacing = barSpacingPx * (barCount - 1)
            val startX = (size.width - totalBarWidth - totalSpacing) / 2f
            val middleY = size.height / 2f
            val usableHeight = size.height * maxBarHeight / 2f

            for (i in 0 until barCount) {
                val x = startX + i * (barWidthPx + barSpacingPx)
                val height = barHeights[i].coerceIn(0.01f, 1f) * usableHeight

                // 对称上下的柱子
                if (height > 1f) {
                    // 柱子颜色渐变：低频暖色 → 高频冷色
                    val fraction = i.toFloat() / barCount
                    val color = lerpColor(
                        Color(0xFF2dd4bf), // 青色（低频）
                        Color(0xFF60a5fa), // 蓝色（高频）
                        fraction
                    )
                    val alpha = (0.4f + height / usableHeight * 0.6f).coerceIn(0.2f, 1f)

                    drawRoundRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(x, middleY - height),
                        size = Size(barWidthPx, height * 2f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidthPx / 2f)
                    )
                }
            }
        }
    }
}

/**
 * 双色插值
 */
private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = 1f
    )
}
