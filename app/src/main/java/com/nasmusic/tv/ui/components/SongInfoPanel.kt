package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.data.model.SongTechnicalInfo
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.TimeUtils

/**
 * 歌曲详情信息面板
 *
 * 在 NowPlaying 页面点击信息按钮时弹出。
 * 展示：歌曲基本信息和编码技术参数。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SongInfoPanel(
    song: Song?,
    technicalInfo: SongTechnicalInfo?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                NasMusicColors.Surface.copy(alpha = 0.95f),
                RoundedCornerShape(12.dp)
            )
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            // 标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                    Text(
                        text = stringResource(R.string.home_song_info),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle(),
                    fontWeight = FontWeight.Bold
                )
                FocusableSurface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(6.dp),
                    focusedScale = 1.1f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Primary
                ) {
                    Text(
                        text = stringResource(R.string.song_info_close),
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.body(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 基本信息 ----
            InfoRow(stringResource(R.string.song_info_title_label), song.title)
            InfoRow(stringResource(R.string.song_info_artist_label), song.artist.ifBlank { "—" })
            InfoRow(stringResource(R.string.song_info_album_label), song.album.ifBlank { "—" })
            if (song.year != null) InfoRow(stringResource(R.string.song_info_year_label), "${song.year}")
            if (song.genre != null) InfoRow(stringResource(R.string.song_info_genre_label), song.genre)
            InfoRow(stringResource(R.string.song_info_duration_label), TimeUtils.formatDuration(song.durationMs))
            InfoRow(stringResource(R.string.song_info_track_label), if (song.trackNumber > 0) "${song.trackNumber}" else "—")

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 技术参数 ----
            if (technicalInfo != null) {
                Text(
                    text = stringResource(R.string.song_info_technical_params),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.button(),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                InfoRow(stringResource(R.string.song_info_codec_label), technicalInfo.codec.ifBlank { "—" })
                InfoRow(stringResource(R.string.song_info_bitrate_label), if (technicalInfo.bitrate > 0) "${technicalInfo.bitrate} kbps" else "—")
                InfoRow(stringResource(R.string.song_info_sample_rate_label), if (technicalInfo.sampleRate > 0) "${technicalInfo.sampleRate / 1000} kHz" else "—")

                val channelText = when (technicalInfo.channels) {
                    1 -> stringResource(R.string.song_info_channels_mono)
                    2 -> stringResource(R.string.song_info_channels_stereo)
                    6 -> stringResource(R.string.song_info_channels_51)
                    8 -> stringResource(R.string.song_info_channels_71)
                    else -> if (technicalInfo.channels > 0) stringResource(R.string.song_info_channels_format, technicalInfo.channels) else "—"
                }
                InfoRow(stringResource(R.string.song_info_channels_label), channelText)

                if (technicalInfo.fileSize > 0L) {
                    val sizeMb = technicalInfo.fileSize / (1024.0 * 1024.0)
                    InfoRow(stringResource(R.string.song_info_file_size_label), "%.1f MB".format(sizeMb))
                }

                InfoRow(stringResource(R.string.song_info_container_label), technicalInfo.format.ifBlank { "—" })
            }

            // 网络歌曲来源
            if (song.isNetworkSong) {
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow(stringResource(R.string.song_info_network_source_label), song.networkSource?.uppercase() ?: stringResource(R.string.song_info_unknown_source))
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.body(),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.65f)
        )
    }
}
