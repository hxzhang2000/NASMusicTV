package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.components.common.CoverImage
import kotlinx.coroutines.launch
import com.nasmusic.tv.backend.download.model.stateOfSong

/**
 * 专辑详情屏幕
 * 顶部：专辑封面 + 元数据
 * 下方：曲目列表（可逐首播放）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: Album,
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onBack: () -> Unit,
    queueSongIds: Set<String> = emptySet(),
    onToggleQueue: (Song) -> Unit = {},
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    downloadStates: Map<String, DownloadState> = emptyMap(),
    onDownloadSong: (Song) -> Unit = {},
    onDeleteDownloadSong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val firstItemFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // Level 1.5: 曲目列表已滚动时按 BACK 先回顶并聚焦第一个
    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch {
                    listState.scrollToItem(0)
                    runCatching { firstItemFocusRequester.requestFocus() }
                }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    // v2.36.0 竖屏（方案 §4.7 / P0-16、P0-24）：头部拆两行（标题行 / 操作行）+ 封面置顶 + 曲目单列
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    val isPhoneLandscape = LocalUiMode.current == UiMode.PhoneLandscape

    Column(
        modifier = modifier.fillMaxSize().padding(
            horizontal = if (isPhoneLandscape) 16.dp else if (isPhonePortrait) 16.dp else 32.dp,
            vertical = if (isPhoneLandscape) 0.dp else if (isPhonePortrait) 12.dp else 20.dp
        )
    ) {
        // 返回 + 标题行 + 操作按钮 + 歌曲数
        // ⚠️ 竖屏下「返回+标题+播放全部+加入队列+歌曲数」同一行必被裁切（方案 §2.4）→ 拆两行
        val playAllButton: @Composable () -> Unit = {
            FocusableSurface(
                onClick = { if (songs.isNotEmpty()) onPlayAll(songs) },
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
        }
        val addQueueButton: @Composable () -> Unit = {
            FocusableSurface(
                onClick = { songs.forEach { song -> onToggleQueue(song) } },
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
        }

        if (isPhonePortrait) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onClick = onBack)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = album.name,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.title(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                playAllButton()
                Spacer(modifier = Modifier.width(10.dp))
                addQueueButton()
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.action_song_count, songs.size),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body()
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = onBack)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = album.name,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.title(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                playAllButton()
                Spacer(modifier = Modifier.width(12.dp))
                addQueueButton()
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.action_song_count, songs.size),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body()
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isPhonePortrait) 12.dp else 20.dp))

        // 专辑元数据 + 曲目列表（两种形态共用同一份实现）
        val metaAndTracks: @Composable (Modifier) -> Unit = { m ->
            Column(modifier = m) {
                // 专辑元数据
                Text(
                    text = album.artist.ifBlank { "—" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.button()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    if (album.year != null) {
                        Text(
                            text = "${album.year}",
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button()
                        )
                    }
                    if (!album.genre.isNullOrBlank()) {
                        if (album.year != null) {
                            Text(
                                text = " · ",
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.button()
                            )
                        }
                        Text(
                            text = album.genre,
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(if (isPhonePortrait) 10.dp else 16.dp))

                // 曲目列表
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        UnifiedSongRow(
                            song = song,
                            onClick = { onPlaySong(song) },
                            mode = SongRowMode.MODE_ROW,
                            index = index,
                            isFavorited = song.id in favoriteIds,
                            onToggleFavorite = { onToggleFavorite(song) },
                            isInQueue = song.id in queueSongIds,
                            onToggleQueue = { onToggleQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            downloadState = downloadStates.stateOfSong(song),
                            onDownload = { onDownloadSong(song) },
                            onDeleteDownload = onDeleteDownloadSong?.let { cb -> { cb(song) } },
                            focusRequester = if (index == 0) firstItemFocusRequester else null
                        )
                    }
                }
            }
        }

        if (isPhonePortrait) {
            // 竖屏：封面置顶（比例式，不再固定 280dp 与曲目列表抢横向空间）
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                CoverImage(
                    coverUrl = album.coverUrl,
                    contentDescription = album.name,
                    size = 160.dp,
                    cornerRadius = 16.dp
                )
                Spacer(modifier = Modifier.height(10.dp))
                metaAndTracks(Modifier.fillMaxWidth().weight(1f))
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 左侧：专辑封面
                CoverImage(
                    coverUrl = album.coverUrl,
                    contentDescription = album.name,
                    size = 280.dp,
                    cornerRadius = 16.dp
                )

                Spacer(modifier = Modifier.width(24.dp))

                // 右侧：专辑信息 + 曲目列表
                metaAndTracks(Modifier.weight(1f))
            }
        }
    }
}
