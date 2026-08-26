package com.nasmusic.tv.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 统一专辑封面组件
 *
 * 封装 Coil 异步加载 + 加载失败时显示音乐符号 fallback + 加载中骨架占位。
 * 支持自定义尺寸和圆角，供 UnifiedSongRow、UnifiedAlbumCard 等组件复用。
 *
 * @param coverUrl 封面 URL（null 或空白时直接显示 fallback）
 * @param contentDescription 无障碍描述
 * @param size 封面尺寸（正方形）
 * @param cornerRadius 圆角半径
 * @param modifier 额外 Modifier
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CoverImage(
    coverUrl: String?,
    contentDescription: String? = null,
    size: Dp = 92.dp,
    cornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(NasMusicColors.SurfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = "\u266A",
                color = NasMusicColors.TextSecondary,
                fontSize = (size.value * 0.4f).sp
            )
        }
    }
}
