package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 播放列表管理屏幕
 * 左侧：播放列表示
 * 选中后右侧显示该播放列表的歌曲明细
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistManagementScreen(
    playlists: List<Playlist>,
    selectedPlaylistSongs: List<Song>,
    isLoading: Boolean,
    onSelectPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onRemoveSong: (String) -> Unit, // songId
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // 返回 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "播放列表管理",
                color = NasMusicColors.TextPrimary,
                fontSize = 24.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 左侧：播放列表示
            Column(
                modifier = Modifier.width(320.dp).fillMaxHeight()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "我的播放列表 (${playlists.size})",
                        color = NasMusicColors.TextPrimary,
                        fontSize = 16.sp
                    )
                    ButtonChip(text = "+ 新建") { onCreatePlaylist() }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "加载中...", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                    }
                } else if (playlists.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "暂无播放列表", color = NasMusicColors.TextSecondary, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(playlists) { playlist ->
                            var isFocused by remember { mutableStateOf(false) }
                            val animScale = remember { Animatable(1f) }
                            val scope = rememberCoroutineScope()

                            Surface(
                                onClick = { onSelectPlaylist(playlist) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .scale(animScale.value)
                                    .border(
                                        width = if (isFocused) 2.dp else 0.dp,
                                        color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .onFocusChanged {
                                        isFocused = it.isFocused
                                        scope.launch {
                                            animScale.animateTo(if (isFocused) 1.02f else 1f, tween(200))
                                        }
                                    },
                                shape = ClickableSurfaceDefaults.shape(
                                    shape = RoundedCornerShape(8.dp),
                                    focusedShape = RoundedCornerShape(8.dp)
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "♪",
                                        color = NasMusicColors.Primary,
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            color = NasMusicColors.TextPrimary,
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${playlist.songCount} 首曲目",
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Row {
                                        ButtonChipSmall(
                                            text = "播放",
                                            onClick = { onPlayPlaylist(playlist) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        ButtonChipSmall(
                                            text = "删除",
                                            onClick = { onDeletePlaylist(playlist) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // 右侧：选中播放列表的歌曲明细
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Text(
                    text = "曲目 (${selectedPlaylistSongs.size})",
                    color = NasMusicColors.TextPrimary,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (selectedPlaylistSongs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "选择一个播放列表查看曲目",
                            color = NasMusicColors.TextSecondary,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(selectedPlaylistSongs) { song ->
                            var isFocused by remember { mutableStateOf(false) }
                            val animScale = remember { Animatable(1f) }
                            val scope = rememberCoroutineScope()

                            Surface(
                                onClick = { onRemoveSong(song.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .scale(animScale.value)
                                    .border(
                                        width = if (isFocused) 2.dp else 0.dp,
                                        color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f) else Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .onFocusChanged {
                                        isFocused = it.isFocused
                                        scope.launch {
                                            animScale.animateTo(if (isFocused) 1.02f else 1f, tween(200))
                                        }
                                    },
                                shape = ClickableSurfaceDefaults.shape(
                                    shape = RoundedCornerShape(6.dp),
                                    focusedShape = RoundedCornerShape(6.dp)
                                ),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                                    focusedContentColor = Color.Black,
                                    pressedContainerColor = NasMusicColors.SurfaceVariant
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = NasMusicColors.TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist.ifBlank { "—" },
                                            color = NasMusicColors.TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = TimeUtils.formatDuration(song.durationMs),
                                        color = NasMusicColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackButton(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.Primary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.96f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "←", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            Text(text = "返回", fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ButtonChipSmall(text: String, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp),
            focusedShape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Primary.copy(alpha = 0.8f),
            contentColor = Color.Black,
            focusedContainerColor = NasMusicColors.Primary,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.95f)
    ) {
        Text(text = text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
}
