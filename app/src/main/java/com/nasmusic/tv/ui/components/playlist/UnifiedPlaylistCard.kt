package com.nasmusic.tv.ui.components.playlist

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.ui.components.CoverCarousel
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 统一歌单卡片组件
 *
 * 展示歌单封面（多张轮播）、歌单名、歌曲数量。
 * 焦点态放大 + 高亮边框。
 *
 * @param playlist 歌单数据
 * @param onClick 点击回调
 * @param modifier 额外 Modifier
 * @param width 卡片宽度，默认 180.dp
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UnifiedPlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 180
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.width(width.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            // 封面区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant)
            ) {
                CoverCarousel(
                    coverCandidates = playlist.coverUrls,
                    isPlaying = false,
                    autoCycle = true,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                // 歌曲数量角标
                if (playlist.songCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(
                                NasMusicColors.Primary.copy(alpha = 0.95f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${playlist.songCount}首",
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.Small
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = playlist.name,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${playlist.songCount}首",
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.Small
            )
        }
    }
}
