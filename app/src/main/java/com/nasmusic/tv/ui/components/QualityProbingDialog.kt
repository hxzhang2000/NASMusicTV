package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 可用音质探测中的过渡提示（多码率方案 §5.2.1）。
 *
 * 单曲下载点击后先探测可用档位（并发 4 档，总耗时 ≈1.2s 封顶），
 * 期间显示本提示，避免用户误以为点击无响应。
 *
 * 不可取消（探测很快结束，且取消后状态机无对应分支）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QualityProbingDialog(songTitle: String) {
    Dialog(
        onDismissRequest = { /* 探测期间不可取消 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(420.dp)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // TV Material3 无 CircularProgressIndicator，沿用项目惯例自绘不确定进度条
                val infinite = rememberInfiniteTransition(label = "quality-probe")
                val shift by infinite.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "shift"
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(NasMusicColors.SurfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(4.dp)
                            .offset(x = (shift * 300).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(NasMusicColors.Primary)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.quality_probe_loading),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.body()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = songTitle,
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.caption()
                )
            }
        }
    }
}
