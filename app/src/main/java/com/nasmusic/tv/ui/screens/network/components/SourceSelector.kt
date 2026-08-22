package com.nasmusic.tv.ui.screens.network.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.MusicSource
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 音乐平台来源选择器
 *
 * 5 个平台按钮（网易云 / QQ音乐 / 酷狗 / 酷我 / 咪咕），横向排列。
 * 选中状态使用 Primary 色，未选中使用 TextSecondary。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SourceSelector(
    selectedSource: MusicSource,
    onSelectSource: (MusicSource) -> Unit,
    modifier: Modifier = Modifier
) {
    data class SourceInfo(val source: MusicSource, val labelResId: Int)

    val sources = remember {
        listOf(
            SourceInfo(MusicSource.NETEASE, R.string.network_platform_netease),
            SourceInfo(MusicSource.QQ, R.string.network_platform_qq),
            SourceInfo(MusicSource.KUGOU, R.string.network_platform_kugou),
            SourceInfo(MusicSource.KUWO, R.string.network_platform_kuwo),
            SourceInfo(MusicSource.MIGU, R.string.network_platform_migu)
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        sources.forEach { (source, labelResId) ->
            val isSelected = source == selectedSource
            val label = stringResource(labelResId)
            FocusableSurface(
                onClick = { onSelectSource(source) },
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.1f,
                animationDurationMs = 150,
                containerColor = if (isSelected) NasMusicColors.Primary
                                 else NasMusicColors.Surface.copy(alpha = 0.8f),
                focusedContainerColor = if (isSelected) NasMusicColors.Primary
                                        else NasMusicColors.Primary.copy(alpha = 0.3f),
                contentColor = if (isSelected) Color.Black
                               else NasMusicColors.TextPrimary,
                focusedContentColor = if (isSelected) Color.Black else NasMusicColors.Primary
            ) {
                Text(
                    text = label,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
