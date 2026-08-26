package com.nasmusic.tv.ui.components.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 统一歌单网格/横排组件
 *
 * 以 LazyRow 横向滚动展示歌单卡片列表，用于推荐歌单、我的歌单等场景。
 *
 * @param playlists 歌单数据列表（Pair<Playlist, List<Song>>）
 * @param onPlaylistClick 歌单点击回调
 * @param modifier 额外 Modifier
 * @param header 可选的头部标题
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UnifiedPlaylistGrid(
    playlists: List<Pair<Playlist, List<Song>>>,
    onPlaylistClick: (Pair<Playlist, List<Song>>) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = null
) {
    if (playlists.isEmpty()) return

    if (header != null) {
        Text(
            text = header,
            color = NasMusicColors.TextPrimary,
            fontSize = 21.sp,
            modifier = modifier.padding(bottom = 4.dp)
        )
    }

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(playlists, key = { it.first.id }) { (playlist, songs) ->
            UnifiedPlaylistCard(
                playlist = playlist,
                onClick = { onPlaylistClick(playlist to songs) }
            )
        }
    }
}
