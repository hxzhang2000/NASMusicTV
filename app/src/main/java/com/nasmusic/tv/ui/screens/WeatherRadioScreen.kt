package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.ui.components.BackButton
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.common.LoadingIndicator
import com.nasmusic.tv.ui.components.common.SectionHeader
import com.nasmusic.tv.ui.components.song.UnifiedSongRow
import com.nasmusic.tv.ui.components.song.SongRowMode
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.backend.download.model.stateOfSong

/**
 * 天气电台页面
 *
 * 上方：天气信息条(1/3) + 心情选择按钮(2/3) 并列
 * 下方：电台歌曲列表（占满宽度）
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeatherRadioScreen(
    weatherRadioQueue: WeatherRadioQueue?,
    weatherData: WeatherData?,
    currentMood: WeatherMood,
    isLoading: Boolean = false,
    onPlaySong: (Song, Int) -> Unit,
    onPlayAll: () -> Unit,
    onSwitchMood: (WeatherMood) -> Unit,
    onBack: () -> Unit,
    downloadStates: Map<String, DownloadState> = emptyMap(),
    onDownloadSong: (Song) -> Unit = {},
    // ── v2.36.2：内嵌操作按钮与曲库 / 专辑详情等页面对齐 ──────────────────────
    // ⚠️ [UnifiedSongRow] 的按钮是**按回调是否为 null 决定是否渲染**的：
    // 此前本页只传了 `downloadState` + `onDownload`，于是每行只剩一个「⬇」下载按钮，
    // 与「曲库 → 歌曲」等页面的「⬇ ♡ ☰ +」完全不一致（真机反馈）。
    // 组件本来就是同一个，缺的是这几个回调。
    favoriteIds: Set<String> = emptySet(),
    queueSongIds: Set<String> = emptySet(),
    onToggleFavorite: (Song) -> Unit = {},
    onToggleQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit = {},
    onDeleteDownloadSong: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // v2.36.0 竖屏：页 padding 32→16
    val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = if (isPhonePortrait) 16.dp else 32.dp,
                vertical = if (isPhonePortrait) 12.dp else 20.dp
            )
    ) {
        // 返回 + 标题 + 播放全部
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.home_weather_radio),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title()
            )
            Spacer(modifier = Modifier.weight(1f))
            if (weatherRadioQueue != null && weatherRadioQueue.songs.isNotEmpty()) {
                FocusableSurface(
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.05f,
                    animationDurationMs = 200,
                    containerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.35f),
                    contentColor = NasMusicColors.Primary,
                    focusedContentColor = NasMusicColors.Primary
                ) {
                    Text(
                        text = "\u25B6 " + stringResource(R.string.common_play_all),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = FontSize.button()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ===== 天气信息(1/3) + 心情按钮(2/3) 并列 =====
        if (weatherData != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 左侧：天气信息条（占 1/3 宽度）
                FocusableSurface(
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.0f,
                    animationDurationMs = 200,
                    containerColor = NasMusicColors.Surface.copy(alpha = 0.3f),
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.1f),
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Primary
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentMood.icon,
                            fontSize = FontSize.display(),
                            color = LocalFocusableContentColor.current
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "${weatherData.temperature.toInt()}\u00B0C",
                                fontSize = FontSize.title(),
                                fontWeight = FontWeight.Bold,
                                color = NasMusicColors.TextPrimary
                            )
                            Text(
                                text = "${weatherData.cityName}  \u00B7  ${weatherData.description}",
                                fontSize = FontSize.small(),
                                color = LocalFocusableContentColor.current
                            )
                        }
                    }
                }

                // 右侧：心情选择按钮（占 2/3 宽度，两行排列）
                Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.Center) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeatherMood.quickSwitches().forEach { mood ->
                            val isSelected = currentMood == mood
                            FocusableSurface(
                                onClick = { onSwitchMood(mood) },
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1.05f,
                                animationDurationMs = 200,
                                containerColor = if (isSelected) NasMusicColors.Primary.copy(alpha = 0.25f)
                                                else NasMusicColors.Surface.copy(alpha = 0.5f),
                                focusedContainerColor = if (isSelected) NasMusicColors.Primary.copy(alpha = 0.35f)
                                                        else NasMusicColors.Primary.copy(alpha = 0.15f),
                                contentColor = if (isSelected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                                focusedContentColor = NasMusicColors.Primary
                            ) {
                                Text(
                                    text = "${mood.icon} ${mood.displayName}",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    fontSize = FontSize.small(),
                                    color = if (isSelected) NasMusicColors.Primary else LocalFocusableContentColor.current
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // ===== 电台歌曲列表（占满宽度）=====
        if (isLoading && (weatherRadioQueue == null || weatherRadioQueue.songs.isEmpty())) {
            LoadingIndicator()
        } else if (weatherRadioQueue != null && weatherRadioQueue.songs.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.weather_radio_songs_title),
                count = weatherRadioQueue.songs.size
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(
                    items = weatherRadioQueue.songs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    UnifiedSongRow(
                        song = song,
                        onClick = { onPlaySong(song, index) },
                        mode = SongRowMode.MODE_ROW,
                        index = index,
                        isFavorited = song.id in favoriteIds,
                        onToggleFavorite = { onToggleFavorite(song) },
                        isInQueue = song.id in queueSongIds,
                        onToggleQueue = { onToggleQueue(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        downloadState = downloadStates.stateOfSong(song),
                        onDownload = { onDownloadSong(song) },
                        onDeleteDownload = onDeleteDownloadSong?.let { cb -> { cb(song) } }
                    )
                }
            }
        } else if (!isLoading) {
            // 空状态
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = currentMood.icon,
                    fontSize = FontSize.displayLarge(),
                    color = LocalFocusableContentColor.current
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.weather_radio_no_songs),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.button()
                )
            }
        }
    }
}
