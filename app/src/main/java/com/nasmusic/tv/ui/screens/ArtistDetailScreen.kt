package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.util.TimeUtils

/**
 * 歌手详情屏幕
 * 顶部：歌手头像 + 名称
 * 下方：歌手所有歌曲列表
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artist: com.nasmusic.tv.data.model.Artist? = null,
    artistName: String,
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // 返回 + 标题行
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = artistName,
                color = NasMusicColors.TextPrimary,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 左侧：歌手头像
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(NasMusicColors.Primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (artist?.coverUrl != null) {
                    AsyncImage(
                        model = artist.coverUrl,
                        contentDescription = artistName,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Text(
                        text = artistName.firstOrNull()?.uppercase() ?: "?",
                        color = NasMusicColors.Primary,
                        fontSize = 72.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(24.dp))

            // 右侧：歌曲列表
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "共 ${songs.size} 首曲目",
                    color = NasMusicColors.TextSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 播放全部按钮
                ButtonChip(text = stringResource(R.string.common_play_all)) {
                    if (songs.isNotEmpty()) onPlayAll(songs)
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                        FocusableSurface(
                            onClick = { onPlaySong(song) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            focusedScale = 1.02f,
                            animationDurationMs = 200,
                            containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                            contentColor = NasMusicColors.TextPrimary,
                            focusedContentColor = Color.Black,
                            pressedScale = 0.98f,
                            pressedContainerColor = NasMusicColors.SurfaceVariant,
                            focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(28.dp),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        color = NasMusicColors.TextPrimary,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.album.ifBlank { "—" },
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
