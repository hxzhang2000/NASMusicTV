package com.nasmusic.tv.ui.screens.network

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.WeatherData
import com.nasmusic.tv.data.model.WeatherMood
import com.nasmusic.tv.data.model.WeatherRadioQueue
import com.nasmusic.tv.ui.LocalListBackHandler
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.screens.SongRow
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 天气子 Tab — 完整的天气电台 UI
 *
 * 结构：
 * ┌─────────────────────────────────────┐
 * │  ☀️ 25°C  ·  深圳   ·  晴天            │
 * │  当前心情: 阳光·轻音乐                  │
 * ├─────────────────────────────────────┤
 * │  [阳光] [多云] [雨天] [雪天] [风天] [雷雨] │（mood 快捷切换）
 * ├─────────────────────────────────────┤
 * │  [全部播放] [刷新]  共 20 首            │
 * │  1. 歌曲A  — 歌手A                    │
 * │  2. 歌曲B  — 歌手B                    │
 * │  ...                                │
 * └─────────────────────────────────────┘
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WeatherSubTab(
    weatherData: WeatherData?,
    weatherRadioQueue: WeatherRadioQueue?,
    currentMood: WeatherMood,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    networkFavoriteIds: Set<String> = emptySet(),
    queueSongIds: Set<String> = emptySet(),
    onPlaySong: (Song) -> Unit = {},
    onPlayAll: (List<Song>) -> Unit = {},
    onSwitchMood: (WeatherMood) -> Unit = {},
    onRefresh: () -> Unit = {},
    onToggleFavorite: ((Song) -> Unit)? = null,
    onToggleQueue: ((Song) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    DisposableEffect(Unit) {
        val handler: () -> Boolean = {
            val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
            if (!atTop) {
                scope.launch { listState.scrollToItem(0) }
                true
            } else {
                false
            }
        }
        listBackHandler.value = handler
        onDispose { listBackHandler.value = null }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 天气信息展示区
        item(key = "weather_info") {
            WeatherInfoCard(
                weatherData = weatherData,
                currentMood = currentMood,
                isLoading = isLoading,
                errorMessage = errorMessage
            )
        }

        // 3. Mood 快捷切换行
        item(key = "mood_switcher") {
            MoodSwitcherRow(
                moods = WeatherMood.quickSwitches(),
                selectedMood = currentMood,
                onSelectMood = onSwitchMood
            )
        }

        // 4. 操作栏：全部播放 + 刷新
        item(key = "action_bar") {
            ActionBar(
                queue = weatherRadioQueue,
                isLoading = isLoading,
                onPlayAll = { weatherRadioQueue?.songs?.let { onPlayAll(it) } },
                onRefresh = onRefresh
            )
        }

        // 5. 歌曲列表
        val songs = weatherRadioQueue?.songs ?: emptyList()
        if (songs.isNotEmpty()) {
            items(songs, key = { "wr_${it.id}" }) { song ->
                SongRow(
                    song = song,
                    onClick = { onPlaySong(song) },
                    isFavorited = song.id in networkFavoriteIds,
                    isInQueue = song.id in queueSongIds,
                    onToggleFavorite = onToggleFavorite?.let { { it(song) } },
                    onToggleQueue = onToggleQueue?.let { { it(song) } }
                )
            }
        } else if (!isLoading) {
            item(key = "empty_hint") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (errorMessage != null) "⚠ $errorMessage"
                               else stringResource(R.string.common_loading),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // 底部间距
        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 天气信息卡片
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WeatherInfoCard(
    weatherData: WeatherData?,
    currentMood: WeatherMood,
    isLoading: Boolean,
    errorMessage: String?
) {
    val bgColor = weatherData?.let { moodToColor(WeatherMood.fromWeather(it)) }
        ?: NasMusicColors.Surface.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(14.dp))
            .padding(20.dp)
    ) {
        if (isLoading && weatherData == null) {
            Text(
                text = stringResource(R.string.common_loading),
                color = NasMusicColors.TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            return
        }

        Column {
            if (weatherData != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 天气图标
                    Text(
                        text = moodToEmoji(currentMood),
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        // 温度 + 城市
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${weatherData.temperature.toInt()}°C",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = NasMusicColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = weatherData.cityName,
                                fontSize = 16.sp,
                                color = NasMusicColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // 天气描述 + 湿度/风速
                        Text(
                            text = buildString {
                                append(weatherData.description)
                                append(" · 湿度 ${weatherData.humidity.toInt()}%")
                                append(" · ${weatherData.windSpeed.toInt()} km/h")
                            },
                            fontSize = 12.sp,
                            color = NasMusicColors.TextSecondary
                        )
                    }
                }
            } else if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = NasMusicColors.Warning,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 当前 mood 显示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前心情:",
                    fontSize = 12.sp,
                    color = NasMusicColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${currentMood.icon} ${currentMood.displayName}",
                    fontSize = 14.sp,
                    color = NasMusicColors.TextPrimary
                )
            }
        }
    }
}

/**
 * Mood 快捷切换行
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MoodSwitcherRow(
    moods: List<WeatherMood>,
    selectedMood: WeatherMood,
    onSelectMood: (WeatherMood) -> Unit
) {
    Column {
        Text(
            text = "切换心情",
            color = NasMusicColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            moods.forEach { mood ->
                val isSelected = mood == selectedMood
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) NasMusicColors.Primary
                                  else NasMusicColors.Surface.copy(alpha = 0.6f),
                    label = "moodBg"
                )
                FocusableSurface(
                    onClick = { onSelectMood(mood) },
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.1f,
                    animationDurationMs = 150,
                    containerColor = bgColor,
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = if (isSelected) NasMusicColors.Surface
                                   else NasMusicColors.TextSecondary,
                    focusedContentColor = NasMusicColors.Surface
                ) {
                    Text(
                        text = "${mood.icon} ${mood.displayName}",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * 操作栏
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionBar(
    queue: WeatherRadioQueue?,
    isLoading: Boolean,
    onPlayAll: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val songs = queue?.songs ?: emptyList()
        val hasSongs = songs.isNotEmpty()

        // 全部播放
        val canPlayAll = hasSongs && !isLoading
        FocusableSurface(
            onClick = { if (canPlayAll) onPlayAll() },
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.08f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
            focusedContainerColor = NasMusicColors.Primary,
            contentColor = Color.Black,
            focusedContentColor = Color.Black
        ) {
            Text(
                text = "▶ ${stringResource(R.string.common_play_all)}",
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 刷新
        val canRefresh = !isLoading
        FocusableSurface(
            onClick = { if (canRefresh) onRefresh() },
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.08f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            contentColor = NasMusicColors.TextSecondary,
            focusedContentColor = NasMusicColors.Primary
        ) {
            Text(
                text = if (isLoading) "..."
                       else "刷新",
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 统计信息
        if (hasSongs) {
            Text(
                text = "共 ${songs.size} 首 · NAS ${queue?.nasCount ?: 0} · 网络 ${queue?.networkCount ?: 0}",
                color = NasMusicColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * WeatherMood → 颜色映射
 */
private fun moodToColor(mood: WeatherMood): Color = when (mood) {
    WeatherMood.SUNNY   -> Color(0x33FFB347)
    WeatherMood.CLOUDY  -> Color(0x3390A0C0)
    WeatherMood.RAINY   -> Color(0x334A6B8A)
    WeatherMood.SNOWY   -> Color(0x33D4E8F0)
    WeatherMood.WINDY   -> Color(0x33A0B0B8)
    WeatherMood.THUNDER -> Color(0x33383050)
    WeatherMood.NIGHT   -> Color(0x33203040)
}

/**
 * WeatherMood → Emoji 图标
 */
fun moodToEmoji(mood: WeatherMood): String = mood.icon
