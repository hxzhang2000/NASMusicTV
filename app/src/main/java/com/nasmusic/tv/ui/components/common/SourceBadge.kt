package com.nasmusic.tv.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.sourceType

/**
 * 统一来源标签组件
 *
 * 以紧凑型 Chip 样式显示歌曲来源。不同来源使用不同颜色。
 * 当歌曲无来源标识（NAS 本地歌曲且无 networkSource）时隐藏。
 *
 * @param source 来源类型（null 时隐藏标签）
 * @param modifier 额外 Modifier
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourceBadge(
    source: MusicSourceType?,
    modifier: Modifier = Modifier
) {
    if (source == null) return

    Text(
        text = source.displayName,
        color = Color.White,
        fontSize = FontSize.Caption,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(source.color.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 便捷扩展：从 Song 直接创建 SourceBadge
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourceBadge(
    song: Song,
    modifier: Modifier = Modifier
) {
    SourceBadge(source = song.sourceType, modifier = modifier)
}
