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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlin.random.Random

/**
 * 可视化均衡器柱状频谱图
 *
 * NowPlaying 页面底部的频谱动画组件。
 * 支持两种数据源：
 * 1) 真实频谱：传入 [spectrumData] 来自 SpectrumAnalyzer（32 柱感知频率翘曲映射）
 * 2) 模拟回退：当 [spectrumData] 为 null 时，使用随机生成（预览/测试场景）
 *
 * 每根柱子使用独立的 Attack/Release 系数（帧率 33ms ≈ 30fps）：
 *   [ 0-4] 极低频区    Attack=0.60 / Release=0.40（反应迟钝，快速归零）
 *   [ 5-19] 鼓点核弹区  Attack=0.96 / Release=0.12（极快弹起，缓慢释放——冲击力+拖尾）
 *   [20-27] 人声核心区  Attack=0.80 / Release=0.20（标准攻守）
 *   [28-31] 超高频区    Attack=0.60 / Release=0.40（反应迟钝，快速归零——避免乱跳）
 *
 * 渲染参数：30fps
 *
 * @param isPlaying 是否在播放
 * @param spectrumData 真实频谱数据（32 柱幅值，0~1），null 时使用随机模拟
 * @param barCount 柱状条数量
 * @param maxBarHeight 最大柱子高度（百分比 of container height）
 * @param modifier Modifier
 */
@Composable
fun VisualEqualizer(
    isPlaying: Boolean,
    spectrumData: FloatArray? = null,
    barCount: Int = 32,
    maxBarHeight: Float = 0.85f,
    modifier: Modifier = Modifier
) {
    val srcBarCount = spectrumData?.size ?: barCount
    Log.d("VisualEqualizer", "Composed: isPlaying=$isPlaying, barCount=$barCount, " +
            "realData=${spectrumData != null}, srcSize=${spectrumData?.size}")

    val barHeights = remember { Array(barCount) { 0f } }
    val targetHeights = remember { Array(barCount) { 0f } }
    // 用 tick 计数器强制 Canvas 重绘（barHeights 是普通数组，不会触发重组）
    var tick by remember { mutableIntStateOf(0) }
    // rememberUpdatedState 让 while(true) 循环总能读到最新的 spectrumData 值
    val currentSpectrumData by rememberUpdatedState(spectrumData)

    // 动画更新：定时刷新柱子高度
    LaunchedEffect(isPlaying) {
        while (true) {
            kotlinx.coroutines.delay(if (isPlaying) 33 else 400)
            val data = currentSpectrumData

            if (isPlaying && data != null && data.isNotEmpty()) {
                // 真实频谱数据：SpectrumAnalyzer 已输出 32 柱感知映射，
                // 直接取值 + 频域平滑 + 动态门限
                val rawTargets = FloatArray(barCount) { i ->
                    if (i < data.size) data[i].coerceIn(0.02f, 1f) else 0.02f
                }

                // ① 频域三角平滑：3点汉宁窗 [0.25, 0.5, 0.25]，消除柱间犬牙交错
                val smoothedTargets = applyFrequencySmoothing(rawTargets)

                // ② 动态噪声门限：低于当前帧均值 15% 的柱子置零
                val gatedTargets = applyNoiseGate(smoothedTargets)

                for (i in 0 until barCount) {
                    targetHeights[i] = gatedTargets[i]
                }
            } else if (isPlaying) {
                // 随机回退（预览或 SpectrumAnalyzer 未就绪时）
                for (i in 0 until barCount) {
                    val freqRatio = i.toFloat() / barCount
                    val baseGain = 1.0f - freqRatio * 0.7f
                    val main = Random.nextFloat() * 0.6f
                    val spike = if (Random.nextFloat() > 0.75f) Random.nextFloat() * 0.8f else 0f
                    targetHeights[i] = (main + spike * baseGain).coerceIn(0.02f, 1.0f)
                }
            } else {
                // 暂停：柱子逐渐衰减归零
                for (i in 0 until barCount) {
                    targetHeights[i] *= 0.85f
                    if (targetHeights[i] < 0.01f) targetHeights[i] = 0f
                }
            }

            // ③ 分区 Attack/Release 双系数滤波（帧率 33ms 优化值）
            //
            //   [ 0-4] 极低频:     迟钝      (0.60/0.40)
            //   [ 5-19] 鼓点核弹区: 极快冲+慢放 (0.96/0.12)
            //   [20-27] 人声核心区: 标准      (0.80/0.20)
            //   [28-31] 超高频区:   迟钝      (0.60/0.40)
            for (i in 0 until barCount) {
                val diff = targetHeights[i] - barHeights[i]
                val isRising = diff > 0
                val (attack, release) = when (i) {
                    in 5..19 -> 0.96f to 0.12f  // 鼓点区：极快攻击，极慢释放
                    in 20..27 -> 0.80f to 0.20f // 人声区：标准
                    else -> 0.60f to 0.40f      // 极低/超高频：迟钝
                }
                barHeights[i] += diff * (if (isRising) attack else release)
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
        Log.d("VisualEqualizer", "Box rendered, isPlaying=$isPlaying")
        // 暂停时没有覆盖文字——柱子通过 decay 逻辑逐渐降到 0，
        // 恢复播放后 SpectrumAnalyzer 的数据会重新使柱子跳动
        Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            // 读 tick 建立重组依赖，驱动 Canvas 重绘
            if (tick >= 0) { /* no-op, just read tick */ }
            val paddingPx = 8f
            val usableWidth = size.width - paddingPx * 2
            val startX = paddingPx
            val bottomY = size.height - 2f
            val usableHeight = size.height * maxBarHeight

            // 根据分区调整每根柱子的宽度（低频更宽 → 视觉重量更大，高频更细 → 不抢镜）
            // 总宽需保持 = usableWidth
            val baseW = usableWidth / barCount
            val barWidths = FloatArray(barCount) { i ->
                when (i) {
                    in 5..19 -> baseW * 1.3f  // 鼓点区：加宽 30%
                    in 20..27 -> baseW * 1.0f // 人声区：标准
                    else -> baseW * 0.7f      // 极低/超高频：缩窄 30%
                }
            }
            // 归一化使总宽 = usableWidth
            val totalRaw = barWidths.sum()
            val scale = usableWidth / totalRaw
            for (i in 0 until barCount) {
                barWidths[i] *= scale
            }

            var currentX = startX
            for (i in 0 until barCount) {
                val barW = barWidths[i]
                val hNorm = barHeights[i].coerceIn(0.01f, 1f)
                val hPx = hNorm * usableHeight

                if (hPx > 1f) {
                    // 分区着色：低频暖色（青/绿），高频冷色（靛蓝/紫）
                    val color = when (i) {
                        in 5..19 -> lerpColor(
                            Color(0xFF34d399),  // 翠绿
                            Color(0xFF2dd4bf),  // 青
                            i.toFloat() / barCount
                        )
                        in 20..27 -> lerpColor(
                            Color(0xFF2dd4bf),  // 青
                            Color(0xFF60a5fa),  // 蓝
                            (i - 20) / 7f
                        )
                        else -> lerpColor(
                            Color(0xFF60a5fa),  // 蓝
                            Color(0xFF6366f1),  // 靛蓝
                            i.toFloat() / barCount
                        )
                    }
                    val alpha = (0.5f + hNorm * 0.5f).coerceIn(0.4f, 1f)

                    drawRoundRect(
                        color = color.copy(alpha = alpha),
                        topLeft = Offset(currentX, bottomY - hPx),
                        size = Size(barW + 1f, hPx),  // +1 消除亚像素缝隙
                        cornerRadius = CornerRadius(barW / 4f, barW / 4f)
                    )
                }
                currentX += barW
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

/**
 * 频域三角平滑——3点汉宁窗 [0.25, 0.5, 0.25]
 *
 * 每一根柱子融合左右邻居的信息：
 *   result[i] = left×0.25 + self×0.5 + right×0.25
 *
 * 孤立尖峰被拉低，只保留宏观频段起伏趋势，
 * 消除柱间「犬牙交错」的视觉噪声。
 */
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

/**
 * 动态噪声门限——低于当前帧均值一定比例的直接置零
 *
 * threshold = avg × 0.15：低于均值 15% 视为底噪，清除「满屏微亮」
 * 让画面背景纯净，只保留显著频段。
 */
private fun applyNoiseGate(data: FloatArray): FloatArray {
    val sum = data.sum()
    val avg = sum / data.size
    val threshold = avg * 0.15f
    return data.map { if (it < threshold) 0f else it }.toFloatArray()
}
