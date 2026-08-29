package com.nasmusic.tv.ui.screens.library

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.radio.RadioBrowserClient
import com.nasmusic.tv.data.model.RadioStation
import com.nasmusic.tv.data.model.UiState
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.SearchField
import com.nasmusic.tv.ui.screens.TextInputDialog
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 电台 Tab（曲库子 Tab）
 *
 * 顶部：搜索按钮 + 快捷筛选行（中文电台 + 预置标签，可横向滑动）
 * 主体：2 列电台卡片网格（台标 / 名称 / 国家·标签 / 码率角标）
 * 点击卡片即点即播（直播流，进入 NowPlaying 显示"直播"态）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RadioTab(
    radioStations: UiState<List<RadioStation>>,
    radioActiveTag: String?,
    radioActiveQuery: String,
    onLoadDefault: () -> Unit,
    onLoadTag: (String) -> Unit,
    onSearch: (String) -> Unit,
    onPlayStation: (RadioStation) -> Unit
) {
    var showSearchDialog by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    LaunchedEffect(Unit) {
        onLoadDefault()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部筛选行 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchField(
                query = radioActiveQuery,
                placeholder = stringResource(R.string.network_search_hint),
                onOpenSearch = { showSearchDialog = true },
                onClear = { onLoadDefault() },
                modifier = Modifier.width(340.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            RadioBrowserClient.PRESET_TAGS.forEach { tag ->
                val isSelected = radioActiveTag == tag && radioActiveQuery.isBlank()
                FocusableSurface(
                    onClick = { onLoadTag(tag) },
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.05f,
                    animationDurationMs = 150,
                    containerColor = if (isSelected) NasMusicColors.Primary
                                    else NasMusicColors.Surface.copy(alpha = 0.6f),
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = if (isSelected) Color.Black else NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.TextPrimary
                ) {
                        Text(
                        text = tag,
                        color = if (isSelected) Color.Black else NasMusicColors.TextPrimary,
                        fontSize = FontSize.Small,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // ── 电台网格 ──
        when (radioStations) {
            is UiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.common_loading), color = NasMusicColors.TextSecondary, fontSize = FontSize.Button)
                }
            }
            is UiState.Error -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.network_radio_load_failed),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.Button
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    FocusableSurface(
                        onClick = onLoadDefault,
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.05f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        focusedContainerColor = NasMusicColors.Primary,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary
                    ) {
                        Text(
                            text = stringResource(R.string.network_radio_retry),
                            color = LocalFocusableContentColor.current,
                            fontSize = FontSize.Body,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }
                }
            }
            is UiState.Success -> {
                val stations = radioStations.data
                if (stations.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (radioActiveQuery.isNotBlank() || radioActiveTag != null)
                                stringResource(R.string.network_radio_no_results)
                            else stringResource(R.string.network_radio_load_failed),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.Button
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(stations, key = { _, s -> s.uuid }) { _, station ->
                            RadioStationCard(
                                station = station,
                                onClick = { onPlayStation(station) }
                            )
                        }
                        item(key = "bottom_spacer", span = { GridItemSpan(2) }) {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
            else -> {}
        }
    }

    if (showSearchDialog) {
        TextInputDialog(
            title = stringResource(R.string.network_radio_search_hint),
            hint = stringResource(R.string.network_radio_search_hint),
            initialValue = radioActiveQuery,
            onConfirm = { kw ->
                if (kw.isNotBlank()) onSearch(kw)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RadioStationCard(
    station: RadioStation,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.05f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedScale = 0.97f
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (station.faviconUrl != null) {
                AsyncImage(
                    model = station.faviconUrl,
                    contentDescription = station.name,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NasMusicColors.SurfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NasMusicColors.SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "\uD83D\uDCFB", fontSize = FontSize.Subtitle)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    fontSize = FontSize.Small,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = NasMusicColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOf(
                        station.country.takeIf { it.isNotBlank() },
                        station.tags.take(2).joinToString(" / ").takeIf { it.isNotBlank() }
                    ).filterNotNull().joinToString(" \u00B7 "),
                    color = LocalFocusableContentColor.current,
                    fontSize = FontSize.Caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (station.bitrate > 0) {
                Box(
                    modifier = Modifier
                        .background(NasMusicColors.SurfaceVariant, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${station.bitrate}kb/s",
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.Caption
                    )
                }
            }
        }
    }
}
