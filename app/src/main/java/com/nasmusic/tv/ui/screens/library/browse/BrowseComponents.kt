package com.nasmusic.tv.ui.screens.library.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.screens.LibraryTab
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalHighContrast
import com.nasmusic.tv.ui.theme.LocalPhoneCompact
import com.nasmusic.tv.ui.theme.HighContrastColors
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.PinyinUtils

/**
 * R-3 拆分：NAS 曲库浏览子包共享组件（原 LibraryScreen.kt 私有组件迁出并放开可见性）。
 *
 * v2.36.0：`adaptiveColumns()` 已上移至 `ui/components/CommonComponents.kt`（与
 * `songGridColumns()` 并列，改为 `UiMode + widthDp` 双输入），本文件及同包调用点
 * 一律 `import com.nasmusic.tv.ui.components.adaptiveColumns`。
 */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedContainerColor = NasMusicColors.Background,
        focusRequester = focusRequester
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(NasMusicColors.SurfaceVariant)
            ) {
                if (!album.coverUrl.isNullOrBlank()) {
                    AsyncImage(model = album.coverUrl, contentDescription = album.name, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "♪", color = LocalFocusableContentColor.current, fontSize = FontSize.displayLarge())
                    }
                }
                // Task 9: 右上角来源徽标
                if (album.sourceType != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(album.sourceType.color.copy(alpha = 0.9f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = album.sourceType.displayName, color = androidx.compose.ui.graphics.Color.White, fontSize = 9.sp)
                    }
                } else {
                    // 无来源时仍显示歌曲数
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .background(NasMusicColors.Primary.copy(alpha = 0.95f), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = stringResource(R.string.library_song_count_short, album.songCount), color = NasMusicColors.TextPrimary, fontSize = FontSize.small())
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = album.name, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // Task 9: 年份 + 流派行
            val infoText = buildString {
                if (album.year != null) append(album.year.toString())
                if (!album.genre.isNullOrBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(album.genre)
                }
            }
            if (infoText.isNotEmpty()) {
                Text(text = infoText, color = NasMusicColors.TextSecondary, fontSize = FontSize.caption(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = album.artist.ifBlank { "—" }, color = LocalFocusableContentColor.current, fontSize = FontSize.small(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onPlay != null) {
                    Text(text = "▶" + stringResource(R.string.player_play), color = NasMusicColors.Primary, fontSize = FontSize.small(), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ArtistCard(
    artist: String,
    coverUrl: String? = null,
    songCount: Int,
    albumCount: Int = 0,
    primaryGenre: String? = null,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        pressedContainerColor = NasMusicColors.Background,
        focusRequester = focusRequester
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = artist,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = artist.firstOrNull()?.uppercase() ?: "?",
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.displayLarge()
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = artist, color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // Task 10: 专辑数 + 歌曲数
            val countText = buildString {
                if (albumCount > 0) append(stringResource(R.string.library_album_count_short, albumCount))
                if (songCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.library_song_count_short, songCount))
                }
            }
            if (countText.isNotEmpty()) {
                Text(text = countText, color = NasMusicColors.TextSecondary, fontSize = FontSize.small(), maxLines = 1)
            }
            // Task 10: 流派标签
            if (!primaryGenre.isNullOrBlank()) {
                Text(text = primaryGenre, color = NasMusicColors.TextSecondary.copy(alpha = 0.7f), fontSize = FontSize.caption(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (onPlay != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "▶" + stringResource(R.string.player_play), color = NasMusicColors.Primary, fontSize = FontSize.small())
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ButtonChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Primary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.95f
    ) {
        Text(text = text, color = NasMusicColors.TextPrimary, fontSize = FontSize.button(), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/**
 * 库内容为空时的提示组件
 *
 * 根据连接状态和可用数据源显示不同提示：
 * - 未连接 NAS 且无任何数据源 → 提示连接服务器或扫描本地音乐
 * - 已连接 NAS 但库为空 → 提示库为空
 */
@Composable
internal fun EmptyHint(isConnected: Boolean, tab: LibraryTab = LibraryTab.ALBUMS) {
    val (icon, title, subtitle) = if (!isConnected) {
        Triple(
            Icons.Default.CloudOff,
            stringResource(R.string.common_not_connected),
            stringResource(R.string.library_connect_or_local_hint)
        )
    } else when (tab) {
        LibraryTab.ARTISTS -> Triple(
            Icons.Default.Person,
            stringResource(R.string.library_empty_artists),
            stringResource(R.string.library_empty_artists_hint)
        )
        LibraryTab.SONGS -> Triple(
            Icons.Default.MusicNote,
            stringResource(R.string.library_empty_songs),
            stringResource(R.string.library_empty_songs_hint)
        )
        else -> Triple(
            Icons.Default.Album,
            stringResource(R.string.library_empty_albums),
            stringResource(R.string.library_empty_albums_hint)
        )
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = NasMusicColors.TextSecondary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.title()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                fontSize = FontSize.button()
            )
        }
    }
}

/**
 * 侧边 A-Z 索引条
 *
 * - 触摸：拖拽选择字母，松手回弹
 * - 遥控器：聚焦后上下键选择，确认键跳转
 * - currentLetter：当前可见区域的首字母，高亮显示
 * - activeLetters：实际有数据的字母集合，其余灰显
 * - onLetterSelect：点击/拖拽到某字母时回调，调用方负责 scroll
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SideLetterIndex(
    currentLetter: Char?,
    activeLetters: Set<Char>,
    onLetterSelect: (Char) -> Unit,
    contentFocusRequester: FocusRequester,
    letterFocusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier
) {
    val allLetters = remember { PinyinUtils.getAllGroupLetters() }
    var focusedLetter by remember { mutableStateOf<Char?>(null) }
    val focusManager = LocalFocusManager.current

    // 手机横屏时字母排不下，用更小尺寸 + 可滚动
    val isPhone = LocalPhoneCompact.current
    val letterSize = if (isPhone) 14.dp else 20.dp
    val letterFontSize = if (isPhone) 7.sp else 10.sp
    val scrollState = rememberScrollState()

    // 固定每项高度，使触摸映射精确（不再依赖 SpaceBetween 坐标计算）
    val itemHeight = letterSize

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(letterSize + 8.dp)
            .padding(end = 4.dp)
            .then(if (isPhone) Modifier.verticalScroll(scrollState) else Modifier)
            .focusRequester(letterFocusRequester)
            .focusTarget()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val current = focusedLetter ?: currentLetter ?: return@onKeyEvent false
                val idx = allLetters.indexOf(current)
                when (event.key) {
                    Key.DirectionUp -> {
                        val prev = allLetters.getOrNull(idx - 1)
                        if (prev != null) { focusedLetter = prev; onLetterSelect(prev) }
                        true
                    }
                    Key.DirectionDown -> {
                        val next = allLetters.getOrNull(idx + 1)
                        if (next != null) { focusedLetter = next; onLetterSelect(next) }
                        true
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        focusedLetter?.let(onLetterSelect)
                        true
                    }
                    Key.DirectionLeft -> {
                        // 从字母索引条返回左侧内容区
                        // firstItemFocusRequester 绑定在 index 1，列表滚动后该 item 可能不在视口内
                        // （未组合 → requestFocus 抛 IllegalStateException），用 runCatching 防崩溃；
                        // 失败时用 moveFocus(Left) 回退到最近的可聚焦 grid item
                        val focused = runCatching { contentFocusRequester.requestFocus() }.isSuccess
                        if (!focused) focusManager.moveFocus(FocusDirection.Left)
                        true
                    }
                    else -> false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        allLetters.forEach { letter ->
            val isActive = letter in activeLetters
            val isCurrent = letter == (focusedLetter ?: currentLetter)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable {
                        focusedLetter = letter
                        onLetterSelect(letter)
                    }
                    .then(
                        if (isCurrent) Modifier.background(
                            NasMusicColors.Primary.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        ) else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter.toString(),
                    color = when {
                        isCurrent -> NasMusicColors.Primary
                        isActive -> NasMusicColors.TextSecondary
                        else -> NasMusicColors.TextSecondary.copy(alpha = 0.3f)
                    },
                    fontSize = letterFontSize,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
