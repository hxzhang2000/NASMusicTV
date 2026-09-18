package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.FontSize

/**
 * 音质档位徽标（多码率方案 §3.5 / §5.2.3）。
 *
 * 显示的是**实际**档位（`Song.resolvedQuality` 或 `DownloadSongEntity.quality`），
 * 不是用户请求的档位 —— 静默降级后徽标应与文件名后缀、音频内容三者一致。
 *
 * @param quality 档位值；[QualityTiers.AUTO] 时返回空（不渲染徽标）
 * @param downgraded 是否标记为"降级结果"（可选角标，v1 由调用方决定是否传）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QualityBadge(
    quality: Int,
    modifier: Modifier = Modifier,
    downgraded: Boolean = false
) {
    if (quality == QualityTiers.AUTO) return
    val label = stringResource(QualityTiers.labelResOf(quality))
    val text = if (downgraded) "$label ↓" else label
    val bg = if (quality == QualityTiers.LOSSLESS) {
        NasMusicColors.AccentGlowStrong
    } else {
        NasMusicColors.SurfaceVariant
    }
    Text(
        text = text,
        color = NasMusicColors.TextSecondary,
        fontSize = FontSize.caption(),
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(PaddingValues(horizontal = 6.dp, vertical = 2.dp))
    )
}
