package com.nasmusic.tv.ui.screens.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
    forecast: List<com.nasmusic.tv.data.model.WeatherForecast> = emptyList(),
    weatherIconCode: String? = null,
    onPlaySong: (Song) -> Unit = {},
    onPlayAll: (List<Song>) -> Unit = {},
    onSwitchMood: (WeatherMood) -> Unit = {},
    onRefresh: () -> Unit = {},
    onToggleFavorite: ((Song) -> Unit)? = null,
    onToggleQueue: ((Song) -> Unit)? = null,
    onAddToPlaylist: (Song) -> Unit = {},
    onNavigateToNowPlaying: () -> Unit = {}
) {
    val listState = rememberLazyGridState()
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. 天气信息展示区
        item(key = "weather_info", span = { GridItemSpan(2) }) {
            WeatherInfoCard(
                weatherData = weatherData,
                currentMood = currentMood,
                isLoading = isLoading,
                errorMessage = errorMessage,
                iconCode = weatherIconCode
            )
        }

        // 2. 天气预报（仅当有预报数据时显示）
        if (forecast.isNotEmpty()) {
            item(key = "forecast", span = { GridItemSpan(2) }) {
                ForecastRow(forecast = forecast)
            }
        }

        // 3. Mood 快捷切换行
        item(key = "mood_switcher", span = { GridItemSpan(2) }) {
            MoodSwitcherRow(
                moods = WeatherMood.quickSwitches(),
                selectedMood = currentMood,
                onSelectMood = onSwitchMood
            )
        }

        // 4. 操作栏：全部播放 + 刷新
        item(key = "action_bar", span = { GridItemSpan(2) }) {
            ActionBar(
                queue = weatherRadioQueue,
                isLoading = isLoading,
                onPlayAll = { weatherRadioQueue?.songs?.let { onPlayAll(it) } },
                onRefresh = onRefresh
            )
        }

        // 5. 歌曲列表（含封面，与列表合二为一，两列展示）
        val songs = weatherRadioQueue?.songs ?: emptyList()
        if (songs.isNotEmpty()) {
            itemsIndexed(songs, key = { _, song -> "wr_${song.id}" }) { index, song ->
                SongRow(
                    song = song,
                    index = index,
                    onClick = { onPlaySong(song) },
                    isFavorited = song.id in networkFavoriteIds,
                    isInQueue = song.id in queueSongIds,
                    onToggleFavorite = onToggleFavorite?.let { { it(song) } },
                    onToggleQueue = onToggleQueue?.let { { it(song) } },
                    onAddToPlaylist = { onAddToPlaylist(song) }
                )
            }
        } else if (!isLoading) {
            item(key = "empty_hint", span = { GridItemSpan(2) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (errorMessage != null) "⚠ $errorMessage"
                               else stringResource(R.string.common_loading),
                        color = NasMusicColors.TextSecondary,
                        fontSize = 19.sp
                    )
                }
            }
        }

        // 底部间距
        item(key = "bottom_spacer", span = { GridItemSpan(2) }) {
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
    errorMessage: String?,
    iconCode: String? = null
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
                fontSize = 19.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            return
        }

        Column {
            if (weatherData != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 天气图标（emoji + OpenWeatherMap 图标）
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = moodToEmoji(currentMood),
                            fontSize = 53.sp
                        )
                        if (iconCode != null) {
                            Text(
                                text = iconCode,
                                color = NasMusicColors.TextSecondary,
                                fontSize = 15.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        // 温度 + 城市
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${weatherData.temperature.toInt()}°C",
                                fontSize = 41.sp,
                                fontWeight = FontWeight.Bold,
                                color = NasMusicColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = weatherData.cityName,
                                fontSize = 21.sp,
                                color = NasMusicColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // 天气描述 + 湿度/风速 + 体感温度
                        Text(
                            text = buildString {
                                append(weatherData.description)
                                append(" · 湿度 ${weatherData.humidity.toInt()}%")
                                append(" · ${weatherData.windSpeed.toInt()} km/h")
                                weatherData.feelsLike?.let { feel ->
                                    append(" · 体感 ${feel.toInt()}°C")
                                }
                            },
                            fontSize = 17.sp,
                            color = NasMusicColors.TextSecondary
                        )
                    }
                }
            } else if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = NasMusicColors.Warning,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 当前 mood 显示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前心情:",
                    fontSize = 17.sp,
                    color = NasMusicColors.TextSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${currentMood.icon} ${currentMood.displayName}",
                    fontSize = 19.sp,
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
            fontSize = 17.sp,
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
                                   else NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Surface
                ) {
                    Text(
                        text = "${mood.icon} ${mood.displayName}",
                        color = if (isSelected) NasMusicColors.Surface else NasMusicColors.TextPrimary,
                        fontSize = 17.sp,
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
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.TextPrimary
        ) {
            Text(
                text = "▶ ${stringResource(R.string.common_play_all)}",
                color = NasMusicColors.TextPrimary,
                fontSize = 18.sp,
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
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.Primary
        ) {
            Text(
                text = if (isLoading) "..."
                       else "刷新",
                color = NasMusicColors.TextPrimary,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 统计信息
        if (hasSongs) {
            Text(
                text = "共 ${songs.size} 首 · NAS ${queue?.nasCount ?: 0} · 网络 ${queue?.networkCount ?: 0}",
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * 天气预报行（水平滚动展示未来 3-5 天）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ForecastRow(
    forecast: List<com.nasmusic.tv.data.model.WeatherForecast>
) {
    Column {
                Text(
                    text = stringResource(R.string.stats_forecast),
            color = NasMusicColors.TextSecondary,
            fontSize = 17.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            forecast.take(5).forEach { day ->
                val mood = WeatherMood.fromWeather(com.nasmusic.tv.data.model.WeatherData(
                    temperature = (day.temperatureHigh + day.temperatureLow) / 2,
                    humidity = day.humidity,
                    windSpeed = 0.0,
                    weatherCode = day.weatherCode,
                    isDay = true,
                    description = day.description
                ))
                val bgColor = moodToColor(mood)

                FocusableSurface(
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.06f,
                    animationDurationMs = 150,
                    containerColor = bgColor,
                    focusedContainerColor = bgColor,
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.TextPrimary
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 日期（月/日）
                        val dateParts = day.date.split("-")
                        val displayDate = if (dateParts.size >= 3) {
                            "${dateParts[1]}/${dateParts[2]}"
                        } else day.date
                        Text(
                            text = displayDate,
                            color = NasMusicColors.TextPrimary.copy(alpha = 0.7f),
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 天气 emoji
                        Text(
                            text = mood.icon,
                            fontSize = 29.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // 温度
                        Text(
                            text = "${day.temperatureLow.toInt()}\u00B0/${day.temperatureHigh.toInt()}\u00B0",
                            color = NasMusicColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        // 简短描述
                        Text(
                            text = day.description.take(4),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                    }
                }
            }
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
 * 天气匹配歌曲封面小方块
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WeatherCoverTile(
    song: Song,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.06f,
        animationDurationMs = 150,
        containerColor = NasMusicColors.SurfaceVariant,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(2.dp)) {
            if (!song.coverUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = song.coverUrl,
                    contentDescription = song.title,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "\u266A", color = NasMusicColors.TextSecondary, fontSize = 23.sp)
                }
            }
            // 底部半透明渐变 + 歌曲名
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.7f)
                            )
                        )
                    )
                    .padding(4.dp)
            ) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * WeatherMood → Emoji 图标
 */
fun moodToEmoji(mood: WeatherMood): String = mood.icon
