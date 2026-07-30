package com.nasmusic.tv.ui.components

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 可视化均衡器柱状频谱图
 *
 * NowPlaying 页面底部的频谱动画组件。
 * 支持三种视觉主题：[VisualizerTheme.COLOR_FLOW]、[VisualizerTheme.NEON_PULSE]、[VisualizerTheme.CLASSICAL_WAVE]。
 *
 * 支持两种数据源：
 * 1) 真实频谱：传入 [spectrumData] 来自 SpectrumAnalyzer（32 柱感知频率翘曲映射）
 * 2) 模拟回退：当 [spectrumData] 为 null 时，使用随机生成（预览/测试场景）
 *
 * @param isPlaying 是否在播放
 * @param spectrumData 真实频谱数据（32 柱幅值，0~1），null 时使用随机模拟
 * @param theme 视觉主题
 * @param barCount 柱状条数量
 * @param maxBarHeight 最大柱子高度（百分比 of container height）
 * @param modifier Modifier
 */
@Composable
fun VisualEqualizer(
    isPlaying: Boolean,
    spectrumData: FloatArray? = null,
    theme: VisualizerTheme = VisualizerTheme.COLOR_FLOW,
    barCount: Int = 32,
    maxBarHeight: Float = 0.85f,
    modifier: Modifier = Modifier
) {
    val srcBarCount = spectrumData?.size ?: barCount
    Log.d("VisualEqualizer", "Composed: isPlaying=$isPlaying, barCount=$barCount, theme=$theme, " +
            "realData=${spectrumData != null}, srcSize=${spectrumData?.size}")

    val barHeights = remember { Array(barCount) { 0f } }
    val targetHeights = remember { Array(barCount) { 0f } }
    var tick by remember { mutableIntStateOf(0) }
    // 色相偏移量——ColorFlow 主题使用，随时间递增使色彩流动
    var hueOffset by remember { mutableFloatStateOf(0f) }
    val currentSpectrumData by rememberUpdatedState(spectrumData)

    // 动画更新：定时刷新柱子高度
    LaunchedEffect(isPlaying) {
        while (true) {
            kotlinx.coroutines.delay(if (isPlaying) 33 else 400)
            val data = currentSpectrumData

            if (isPlaying && data != null && data.isNotEmpty()) {
                val rawTargets = FloatArray(barCount) { i ->
                    if (i < data.size) data[i].coerceIn(0.02f, 1f) else 0.02f
                }
                val smoothedTargets = applyFrequencySmoothing(rawTargets)
                val gatedTargets = applyNoiseGate(smoothedTargets)
                for (i in 0 until barCount) {
                    targetHeights[i] = gatedTargets[i]
                }
            } else if (isPlaying) {
                for (i in 0 until barCount) {
                    val freqRatio = i.toFloat() / barCount
                    val baseGain = 1.0f - freqRatio * 0.7f
                    val main = Random.nextFloat() * 0.6f
                    val spike = if (Random.nextFloat() > 0.75f) Random.nextFloat() * 0.8f else 0f
                    targetHeights[i] = (main + spike * baseGain).coerceIn(0.02f, 1.0f)
                }
            } else {
                for (i in 0 until barCount) {
                    targetHeights[i] *= 0.85f
                    if (targetHeights[i] < 0.01f) targetHeights[i] = 0f
                }
            }

            // 分区 Attack/Release 双系数滤波
            for (i in 0 until barCount) {
                val diff = targetHeights[i] - barHeights[i]
                val isRising = diff > 0
                val (attack, release) = when (i) {
                    in 5..19 -> 0.96f to 0.12f
                    in 20..27 -> 0.80f to 0.20f
                    else -> 0.60f to 0.40f
                }
                barHeights[i] += diff * (if (isRising) attack else release)
            }

            // ColorFlow 色相偏移
            if (theme == VisualizerTheme.COLOR_FLOW) {
                hueOffset = (hueOffset + 0.005f) % 1f
            }

            tick++
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(NasMusicColors.SurfaceVariant.copy(alpha = 0.5f))
    ) {
        Log.d("VisualEqualizer", "Box rendered, isPlaying=$isPlaying, theme=$theme")
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (tick >= 0) { /* no-op, just read tick */ }

            when (theme) {
                VisualizerTheme.COLOR_FLOW -> drawColorFlow(barHeights, barCount, maxBarHeight, hueOffset, size)
                VisualizerTheme.NEON_PULSE -> drawNeonPulse(barHeights, barCount, maxBarHeight, size)
                VisualizerTheme.CLASSICAL_WAVE -> drawClassicalWave(barHeights, barCount, maxBarHeight, size)
            }
        }
    }
}

// ─── ColorFlow ────────────────────────────────────────────────────────

private fun DrawScope.drawColorFlow(
    barHeights: Array<Float>,
    barCount: Int,
    maxBarHeight: Float,
    hueOffset: Float,
    canvasSize: Size
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    val baseW = usableWidth / barCount
    val barWidths = computeBarWidths(barCount, baseW, usableWidth)

    var currentX = startX
    for (i in 0 until barCount) {
        val barW = barWidths[i]
        val hNorm = barHeights[i].coerceIn(0.01f, 1f)
        val hPx = hNorm * usableHeight

        if (hPx > 1f) {
            // 色相偏移：在频段渐变基础上叠加 hueOffset，形成流动感
            val baseFraction = i.toFloat() / barCount
            val shiftedFraction = (baseFraction + hueOffset) % 1f
            val color = lerpColor(
                Color(0xFF34d399),  // 翠绿
                Color(0xFF6366f1),  // 靛蓝
                shiftedFraction
            )
            val alpha = (0.5f + hNorm * 0.5f).coerceIn(0.4f, 1f)

            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(currentX, bottomY - hPx),
                size = Size(barW + 1f, hPx),
                cornerRadius = CornerRadius(barW / 4f, barW / 4f)
            )
        }
        currentX += barW
    }
}

// ─── NeonPulse ────────────────────────────────────────────────────────

private fun DrawScope.drawNeonPulse(
    barHeights: Array<Float>,
    barCount: Int,
    maxBarHeight: Float,
    canvasSize: Size
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    // 当前帧平均振幅——用于整体亮度脉动
    val avgAmp = barHeights.take(barCount).average().toFloat().coerceIn(0.05f, 1f)
    val pulse = 0.7f + 0.3f * avgAmp  // 0.7~1.0 范围

    val baseW = usableWidth / barCount
    val barWidths = computeBarWidths(barCount, baseW, usableWidth)

    // 三组霓虹色
    val neonColors = listOf(
        Color(0xFFFF1493),  // 深粉红
        Color(0xFF00FFFF),  // 青色
        Color(0xFFFFD700),  // 金色
        Color(0xFF00FF7F),  // 春绿
        Color(0xFF7B68EE),  // 中紫
        Color(0xFFFF4500)   // 橙红
    )

    var currentX = startX
    for (i in 0 until barCount) {
        val barW = barWidths[i]
        val hNorm = barHeights[i].coerceIn(0.01f, 1f)
        val hPx = hNorm * usableHeight

        if (hPx > 1f) {
            val color = neonColors[i % neonColors.size]
            val brightness = hNorm * pulse
            val alpha = (0.5f + brightness * 0.5f).coerceIn(0.4f, 1f)

            // 外发光：在柱子周围画一个更宽的半透明矩形
            val glowWidth = barW * 2.2f
            val glowAlpha = alpha * 0.2f
            drawRoundRect(
                color = color.copy(alpha = glowAlpha),
                topLeft = Offset(currentX - (glowWidth - barW) / 2f, bottomY - hPx),
                size = Size(glowWidth, hPx),
                cornerRadius = CornerRadius(glowWidth / 3f, glowWidth / 3f)
            )

            // 主柱体（更圆润）
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(currentX, bottomY - hPx),
                size = Size(barW + 1f, hPx),
                cornerRadius = CornerRadius(barW / 3f, barW / 3f)
            )

            // 顶部高光线（模拟光泽）
            val highlightH = (hPx * 0.12f).coerceAtLeast(3f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.25f * brightness),
                topLeft = Offset(currentX + 1f, bottomY - hPx),
                size = Size((barW - 2f).coerceAtLeast(1f), highlightH),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
        currentX += barW
    }
}

// ─── ClassicalWave ────────────────────────────────────────────────────

private fun DrawScope.drawClassicalWave(
    barHeights: Array<Float>,
    barCount: Int,
    maxBarHeight: Float,
    canvasSize: Size
) {
    val paddingPx = 8f
    val usableWidth = canvasSize.width - paddingPx * 2
    val startX = paddingPx
    val bottomY = canvasSize.height - 2f
    val usableHeight = canvasSize.height * maxBarHeight

    val baseW = usableWidth / barCount
    val barWidths = computeBarWidths(barCount, baseW, usableWidth)

    // 确定每个柱子的中心 X 坐标
    val centers = FloatArray(barCount)
    var cx = startX
    for (i in 0 until barCount) {
        centers[i] = cx + barWidths[i] / 2f
        cx += barWidths[i]
    }

    // 计算各个点的幅值，并做平滑插值（用于波形线）
    val amplitudes = FloatArray(barCount) { i ->
        bottomY - barHeights[i].coerceIn(0.01f, 1f) * usableHeight
    }

    // 主色——青蓝色渐变
    val waveColor = Color(0xFF2dd4bf)
    val waveColorDark = Color(0xFF0891b2)

    // ① 填充面积（半透明渐变）
    val fillPath = Path().apply {
        moveTo(centers[0], bottomY)
        for (i in 0 until barCount) {
            lineTo(centers[i], amplitudes[i])
        }
        lineTo(centers.last(), bottomY)
        close()
    }
    drawPath(
        path = fillPath,
        color = waveColor.copy(alpha = 0.15f)
    )

    // ② 波形主线
    val linePath = Path().apply {
        moveTo(centers[0], amplitudes[0])
        // 使用二次贝塞尔曲线平滑连接
        for (i in 1 until barCount) {
            val midX = (centers[i - 1] + centers[i]) / 2f
            val midY = (amplitudes[i - 1] + amplitudes[i]) / 2f
            val cpx1 = centers[i - 1] + (centers[i] - centers[i - 1]) * 0.4f
            val cpx2 = centers[i] - (centers[i] - centers[i - 1]) * 0.4f
            cubicTo(cpx1, amplitudes[i - 1], cpx2, amplitudes[i], centers[i], amplitudes[i])
        }
    }
    drawPath(
        path = linePath,
        color = waveColor,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // ③ 波形高光（细的半透明线叠加）
    drawPath(
        path = linePath,
        color = waveColor.copy(alpha = 0.3f),
        style = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // ④ 峰值点
    for (i in 0 until barCount) {
        val hNorm = barHeights[i]
        if (hNorm > 0.15f) {
            val dotSize = (2f + hNorm * 6f).coerceAtMost(8f)
            drawCircle(
                color = waveColorDark.copy(alpha = 0.5f),
                radius = dotSize,
                center = Offset(centers[i], amplitudes[i])
            )
            drawCircle(
                color = waveColor.copy(alpha = 0.7f),
                radius = dotSize * 0.6f,
                center = Offset(centers[i], amplitudes[i])
            )
        }
    }
}

// ─── Shared utilities ─────────────────────────────────────────────────

private fun computeBarWidths(barCount: Int, baseW: Float, usableWidth: Float): FloatArray {
    val barWidths = FloatArray(barCount) { i ->
        when (i) {
            in 5..19 -> baseW * 1.3f
            in 20..27 -> baseW * 1.0f
            else -> baseW * 0.7f
        }
    }
    val totalRaw = barWidths.sum()
    val scale = usableWidth / totalRaw
    for (i in 0 until barCount) {
        barWidths[i] *= scale
    }
    return barWidths
}

private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = 1f
    )
}

private fun applyFrequencySmoothing(data: FloatArray): FloatArray {
    val size = data.size
    if (size < 3) return data.copyOf()
    val smoothed = FloatArray(size)
    for (i in 0 until size) {
        val left = if (i > 0) data[i - 1] else data[i]
        val right = if (i < size - 1) data[i + 1] else data[i]
        smoothed[i] = left * 0.25f + data[i] * 0.5f + right * 0.25f
    }
    return smoothed
}

private fun applyNoiseGate(data: FloatArray): FloatArray {
    val sum = data.sum()
    val avg = sum / data.size
    val threshold = avg * 0.15f
    return data.map { if (it < threshold) 0f else it }.toFloatArray()
}
