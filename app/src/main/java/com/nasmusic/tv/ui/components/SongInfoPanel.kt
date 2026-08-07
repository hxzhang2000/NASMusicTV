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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                .verticalScroll(rememberScrollState())
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
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                FocusableSurface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(6.dp),
                    focusedScale = 1.1f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.Surface.copy(alpha = 0.6f),
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                    contentColor = NasMusicColors.TextSecondary,
                    focusedContentColor = NasMusicColors.Primary
                ) {
                    Text(
                        text = "关闭",
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 基本信息 ----
            InfoRow("标题", song.title)
            InfoRow("艺术家", song.artist.ifBlank { "—" })
            InfoRow("专辑", song.album.ifBlank { "—" })
            if (song.year != null) InfoRow("年份", "${song.year}")
            if (song.genre != null) InfoRow("风格", song.genre)
            InfoRow("时长", TimeUtils.formatDuration(song.durationMs))
            InfoRow("音轨号", if (song.trackNumber > 0) "${song.trackNumber}" else "—")

            Spacer(modifier = Modifier.height(12.dp))

            // ---- 技术参数 ----
            if (technicalInfo != null) {
                Text(
                    text = "技术参数",
                    color = NasMusicColors.Primary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                InfoRow("编码格式", technicalInfo.codec.ifBlank { "—" })
                InfoRow("比特率", if (technicalInfo.bitrate > 0) "${technicalInfo.bitrate} kbps" else "—")
                InfoRow("采样率", if (technicalInfo.sampleRate > 0) "${technicalInfo.sampleRate / 1000} kHz" else "—")

                val channelText = when (technicalInfo.channels) {
                    1 -> "单声道"
                    2 -> "立体声"
                    6 -> "5.1 环绕"
                    8 -> "7.1 环绕"
                    else -> if (technicalInfo.channels > 0) "${technicalInfo.channels} 声道" else "—"
                }
                InfoRow("声道", channelText)

                if (technicalInfo.fileSize > 0L) {
                    val sizeMb = technicalInfo.fileSize / (1024.0 * 1024.0)
                    InfoRow("文件大小", "%.1f MB".format(sizeMb))
                }

                InfoRow("容器格式", technicalInfo.format.ifBlank { "—" })
            }

            // 网络歌曲来源
            if (song.isNetworkSong) {
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("网络来源", song.networkSource?.uppercase() ?: "未知")
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
            fontSize = 18.sp,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            color = NasMusicColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.65f)
        )
    }
}
