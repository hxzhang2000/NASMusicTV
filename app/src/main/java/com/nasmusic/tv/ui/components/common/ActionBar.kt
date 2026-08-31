package com.nasmusic.tv.ui.components.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 统一歌曲列表操作栏
 *
 * 提供"播放全部"、"加入队列"等操作按钮 + 歌曲计数。
 * 无业务逻辑，通过回调传递事件。
 *
 * @param songCount 列表中的歌曲数量
 * @param onPlayAll 点击"播放全部"
 * @param onAddAllToQueue 点击"加入队列"
 * @param onFavoriteAll 点击"收藏全部"（null 时不显示）
 * @param extraContent 右侧扩展区域（如"换一批"等）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ActionBar(
    songCount: Int,
    onPlayAll: () -> Unit,
    onAddAllToQueue: () -> Unit,
    onFavoriteAll: (() -> Unit)? = null,
    extraContent: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 播放全部
        FocusableSurface(
            onClick = onPlayAll,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.08f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
            focusedContainerColor = NasMusicColors.Primary,
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.TextPrimary
        ) {
            Text(
                text = stringResource(R.string.action_play_all),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 加入队列
        FocusableSurface(
            onClick = onAddAllToQueue,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.08f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.Primary
        ) {
            Text(
                text = stringResource(R.string.action_add_queue),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 收藏全部（可选）
        if (onFavoriteAll != null) {
            Spacer(modifier = Modifier.width(12.dp))
            FocusableSurface(
                onClick = onFavoriteAll,
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.Primary
            ) {
                Text(
                    text = stringResource(R.string.action_favorite_all),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // 扩展区域（如"换一批"）
        extraContent?.invoke()

        // 歌曲计数
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.action_song_count, songCount),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body()
        )
    }
}
