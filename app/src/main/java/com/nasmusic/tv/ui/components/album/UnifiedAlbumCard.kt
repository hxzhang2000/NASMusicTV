package com.nasmusic.tv.ui.components.album

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.ui.components.common.CoverImage
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 统一专辑卡片组件
 *
 * 方形封面 + 专辑名 + 艺术家名，焦点态放大 + 高亮。
 * 替代 HomeScreen.HomeAlbumCard 和 LibraryScreen 中的专辑卡片。
 *
 * @param album 专辑数据
 * @param onClick 点击回调（进入详情）
 * @param modifier 额外 Modifier
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UnifiedAlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .width(160.dp)
            .scale(animScale.value)
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.15f)
                else NasMusicColors.Surface.copy(alpha = 0.6f)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.06f else 1f,
                        tween(200)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp)
        ) {
            // 正方形封面
            CoverImage(
                coverUrl = album.coverUrl,
                contentDescription = album.name,
                size = 148.dp,
                cornerRadius = 8.dp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album.name,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist.ifBlank { "-" },
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.Small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
