package com.nasmusic.tv.ui.components.song

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.isDownloadableSong
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.sourceType
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.components.common.CoverImage
import com.nasmusic.tv.ui.components.common.SourceBadge
import com.nasmusic.tv.ui.components.isTVDevice
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.util.TimeUtils
import kotlinx.coroutines.launch

/**
 * 歌曲行布局模式
 */
enum class SongRowMode {
    /** 水平行：封面 + 文字 + 操作，用于列表 */
    MODE_ROW,
    /** 卡片：封面在上，文字在下，用于网格 */
    MODE_CARD,
    /** 紧凑行：无封面，仅文字，用于队列 */
    MODE_COMPACT
}

/**
 * 导入歌单歌曲的 URL 直链状态（docs/archive/playlist-import-feature-plan.md §4.5.2）。
 * 仅 MODE_ROW 在来源标签位渲染；非导入场景保持 NONE（不渲染）。
 */
/** 竖屏歌曲行：封面尺寸。比 TV 的 92dp 小，把宽度让给歌名 / 艺术家 */
private val PORTRAIT_SONG_COVER_SIZE = 64.dp

/** 竖屏歌曲行：第一行高度（封面 64dp + 上下各 12dp 内边距） */
private val PORTRAIT_SONG_ROW_LINE1_HEIGHT = 88.dp

enum class UrlStatus {
    /** 非导入歌曲 / 无 URL 直链 */
    NONE,
    /** 已捕获 URL 直链，待后台可达性测试（导入完成瞬间的短暂状态） */
    PENDING,
    /** 不可达（24h 判定窗口内，见 AppPreferences.songReachability） */
    UNREACHABLE,
}

/**
 * 统一歌曲行组件
 *
 * 替代 HomeScreen.HomeSongCard 等重复实现。
 * 支持三种布局模式，通过参数控制显示内容。
 *
 * @param song 歌曲数据
 * @param onClick 点击回调（播放歌曲）
 * @param mode 布局模式
 * @param index 列表序号（从 0 开始，显示为 index+1）。null 时显示播放图标
 * @param isFavorited 是否已收藏
 * @param onToggleFavorite 收藏切换回调（null 时不显示收藏按钮）
 * @param isInQueue 是否在播放队列中
 * @param onToggleQueue 队列切换回调（null 时不显示队列按钮）
 * @param onAddToPlaylist 添加到歌单回调（null 时不显示按钮）
 * @param onDelete 删除回调（null 时不显示按钮）。仅用于"可删除"上下文
 *                   （如歌单内移除歌曲），搜索/发现/曲库页不应传入
 * @param downloadState 下载状态（null 默认 None，不显示下载按钮）
 * @param onDownload 下载/删除下载回调（null 时不显示下载按钮）
 * @param onDeleteDownload 删除已下载文件回调（null 时 Completed 状态点击不响应）
 * @param focusRequester 焦点请求器
 * @param isStub 是否导入歌单的裸条目（id 以 imported_ 开头；true 时来源标签显示「待补全」）
 * @param urlStatus URL 直链可达性状态（§4.5.2；UNREACHABLE 时优先显示「URL 失效」）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UnifiedSongRow(
    song: Song,
    onClick: () -> Unit,
    mode: SongRowMode = SongRowMode.MODE_ROW,
    index: Int? = null,
    isFavorited: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    isInQueue: Boolean = false,
    onToggleQueue: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    downloadState: DownloadState = DownloadState.None,
    onDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    isStub: Boolean = false,
    urlStatus: UrlStatus = UrlStatus.NONE,
    modifier: Modifier = Modifier
) {
    when (mode) {
        SongRowMode.MODE_ROW -> SongRowModeRow(
            song = song,
            onClick = onClick,
            index = index,
            isFavorited = isFavorited,
            onToggleFavorite = onToggleFavorite,
            isInQueue = isInQueue,
            onToggleQueue = onToggleQueue,
            onAddToPlaylist = onAddToPlaylist,
            onDelete = onDelete,
            downloadState = downloadState,
            onDownload = onDownload,
            onDeleteDownload = onDeleteDownload,
            focusRequester = focusRequester,
            isStub = isStub,
            urlStatus = urlStatus,
            modifier = modifier
        )
        SongRowMode.MODE_CARD -> SongRowModeCard(
            song = song,
            onClick = onClick,
            downloadState = downloadState,
            focusRequester = focusRequester
        )
        SongRowMode.MODE_COMPACT -> SongRowModeCompact(
            song = song,
            onClick = onClick,
            index = index,
            focusRequester = focusRequester
        )
    }
}

/**
 * MODE_ROW：水平行布局（封面 + 文字 + 操作按钮）
 * 与现有 LibraryScreen.SongRow 样式一致
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowModeRow(
    song: Song,
    onClick: () -> Unit,
    index: Int?,
    isFavorited: Boolean,
    onToggleFavorite: (() -> Unit)?,
    isInQueue: Boolean,
    onToggleQueue: (() -> Unit)?,
    onAddToPlaylist: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    downloadState: DownloadState,
    onDownload: (() -> Unit)?,
    onDeleteDownload: (() -> Unit)?,
    focusRequester: FocusRequester?,
    isStub: Boolean = false,
    urlStatus: UrlStatus = UrlStatus.NONE,
    modifier: Modifier = Modifier
) {
    var isRowFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // P0-12: 未下载过的可下载歌曲 fallback 到 Idle 而非 None，使下载按钮可见
    val effectiveDownloadState = if (downloadState is DownloadState.None && isDownloadableSong(song)) {
        DownloadState.Idle
    } else {
        downloadState
    }

    // P1-16: Completed 状态点击弹出删除确认而非死按钮
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // v2.36.0 竖屏体验修复：焦点视觉只在 TV 上生效。
    // 手机触摸会让 `clickable`/`focusable` 节点获得焦点且**焦点粘住** → 行背景永久高亮 +
    // 永久放大 2%。与 FocusableSurface 的同款处理保持一致（TV 行为逐字不变）。
    val tvDevice = isTVDevice()
    val isPortraitPhone = LocalUiMode.current == UiMode.PhonePortrait
    val activeFocus = isRowFocused && tvDevice

    // v2.36.0 竖屏体验修复：时长与操作按钮要**单独一行**，不能挤占歌名/艺术家的宽度。
    // 抽成局部 composable 由两个布局分支共用：
    // - 非竖屏（TV / 手机横屏）：仍作为行内右侧第二列，与改动前逐字等价
    // - 竖屏：放到第一行下方的独立操作行
    val actionButtons: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 下载按钮（None 不渲染；Idle ⬇ 下载 / Queued ⋯ / Downloading ⇣ / Completed ✓ 删除 / Failed ✕ 重试）
            if (onDownload != null && effectiveDownloadState !is DownloadState.None) {
                val button: Triple<String, Color, Boolean>? = when (effectiveDownloadState) {
                    DownloadState.Idle -> Triple("⬇", NasMusicColors.TextPrimary, true)
                    DownloadState.Queued -> Triple("⋯", NasMusicColors.TextSecondary, false)
                    is DownloadState.Downloading -> Triple("⇣", NasMusicColors.Primary, false)
                    // Completed 可点击 → 弹出「删除已下载文件」二次确认（§8.7.1）
                    is DownloadState.Completed -> Triple("✓", NasMusicColors.Success, true)
                    is DownloadState.Failed -> Triple("✕", NasMusicColors.Warning, true)
                    DownloadState.None -> null
                }
                if (button != null) {
                    RowActionButton(
                        text = button.first,
                        color = button.second,
                        onClick = {
                            if (effectiveDownloadState is DownloadState.Completed && onDeleteDownload != null) {
                                showDeleteConfirm = true
                            } else {
                                onDownload.invoke()
                            }
                        },
                        enabled = button.third
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
            }
            if (onToggleFavorite != null) {
                RowActionButton(
                    text = if (isFavorited) "♥" else "♡",
                    color = if (isFavorited) NasMusicColors.Warning else NasMusicColors.TextPrimary,
                    onClick = onToggleFavorite
                )
            }
            if (onToggleQueue != null) {
                Spacer(modifier = Modifier.width(10.dp))
                RowActionButton(
                    text = if (isInQueue) "✓" else "☰",
                    color = if (isInQueue) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    onClick = onToggleQueue
                )
            }
            if (onAddToPlaylist != null) {
                Spacer(modifier = Modifier.width(10.dp))
                RowActionButton(
                    text = "+",
                    color = NasMusicColors.TextPrimary,
                    onClick = onAddToPlaylist
                )
            }
            if (onDelete != null) {
                Spacer(modifier = Modifier.width(10.dp))
                RowActionButton(
                    text = "✕",
                    color = NasMusicColors.Warning,
                    onClick = onDelete
                )
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (activeFocus) NasMusicColors.Primary.copy(alpha = 0.2f)
                else NasMusicColors.Surface.copy(alpha = 0.5f)
            )
            .border(
                width = if (activeFocus) 2.dp else 0.dp,
                color = if (activeFocus) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged { state ->
                isRowFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        // ⚠️ 用 `state.hasFocus` 而不是外层的 activeFocus：
                        // onFocusChanged 的 lambda 捕获的是**本次组合**的 activeFocus，
                        // 焦点刚变化时它还是旧值（与 tvDevice 分档叠加会读错）。
                        if (state.hasFocus && tvDevice) 1.02f else 1f,
                        tween(200)
                    )
                }
            }
    ) {
        // v2.36.0 竖屏体验修复：外层用 Column 承载「第二行（时长 + 操作按钮）」。
        // 非竖屏下 Column 只有原 Row 一个子项 → 渲染结果与改动前逐字等价（B1）。
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    // 竖屏用 `heightIn(min)` 而非固定高度：字号调节（-8 ~ +8 sp）放大后
                    // 「歌名 + 艺术家」两行可能超过 88dp，固定高度会把文字裁掉。
                    // 非竖屏仍走固定 120dp —— 与改动前逐字等价（B1）。
                    if (isPortraitPhone) Modifier.heightIn(min = PORTRAIT_SONG_ROW_LINE1_HEIGHT)
                    else Modifier.height(120.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：封面 + 序号/图标 + 文字（可点击播放）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (focusRequester != null) Modifier.focusRequester(focusRequester)
                        else Modifier
                    )
                    .focusable()
                    .clickable { onClick() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面缩略图：已下载 → 内嵌封面（提取到缓存）→ 旁路 .jpg → 后端 URL
                // 本地歌曲（下载后以 LOCAL 源出现）：downloadKey 不匹配但 song.path 有效
                // 2026-09-25 审查修复（#14）：封面提取此前在组合期直接执行且无缓存 ——
                // 每次重组都重新读文件头/查盘（负结果连 EmbeddedCoverExtractor 的 LRU 都不进），
                // 并每行每次重组打 3 条 debug 日志。改为 remember 按 (song.id, downloadState,
                // coverUrl) 缓存，重组零 IO；负结果由 extractor 侧 missCache 兜底。
                val rowContext = androidx.compose.ui.platform.LocalContext.current
                val effectiveCoverUrl = remember(song.id, downloadState, song.coverUrl) {
                    when {
                        downloadState is DownloadState.Completed -> {
                            val embedded = com.nasmusic.tv.backend.local.EmbeddedCoverExtractor.extractCoverUri(
                                downloadState.path, rowContext.cacheDir, rowContext
                            )
                            when {
                                embedded != null -> embedded
                                !downloadState.coverPath.isNullOrBlank() &&
                                    java.io.File(downloadState.coverPath).exists() ->
                                    "file://${downloadState.coverPath}"
                                else -> song.coverUrl
                            }
                        }
                        song.isLocalSong && !song.path.isNullOrBlank() -> {
                            com.nasmusic.tv.backend.local.EmbeddedCoverExtractor.extractCoverUri(
                                song.path!!, rowContext.cacheDir, rowContext
                            ) ?: song.coverUrl
                        }
                        else -> song.coverUrl
                    }
                }
                CoverImage(
                    coverUrl = effectiveCoverUrl,
                    contentDescription = song.title,
                    size = if (isPortraitPhone) PORTRAIT_SONG_COVER_SIZE else 92.dp,
                    cornerRadius = 4.dp
                )
                Spacer(modifier = Modifier.width(14.dp))

                // 序号或播放图标
                if (index != null) {
                        Text(
                        text = String.format("%02d", index + 1),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button(),
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                        Text(
                        text = "▶",
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button(),
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))

                // 歌曲信息 + 来源标签
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = song.title,
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // 来源标签：URL 失效（红）> 待补全（橙）> 正常来源。均非导入歌单歌曲时保持原样。
                        when {
                            urlStatus == UrlStatus.UNREACHABLE -> Text(
                                text = stringResource(com.nasmusic.tv.R.string.playlist_url_invalid),
                                color = NasMusicColors.Warning,
                                fontSize = FontSize.small()
                            )
                            isStub -> Text(
                                text = stringResource(com.nasmusic.tv.R.string.playlist_stub_badge),
                                color = NasMusicColors.Primary,
                                fontSize = FontSize.small()
                            )
                            else -> SourceBadge(song = song)
                        }
                        // v2.35.0 多码率（§5.2.3）：已下载歌曲显示**实际落盘档位**徽标。
                        // 同曲多档并存时（无损 FLAC / 极高 320 / 标准 128 各一行），
                        // 靠它区分，否则用户会误以为重复下载了同一首歌。
                        // 优先取 downloadState 的档位；网络歌曲未下载但已解析过则回退 resolvedQuality。
                        val badgeQuality = when (effectiveDownloadState) {
                            is DownloadState.Completed -> effectiveDownloadState.quality
                            else -> song.resolvedQuality
                        }
                        if (badgeQuality != com.nasmusic.tv.backend.network.QualityTiers.AUTO) {
                            Spacer(modifier = Modifier.width(8.dp))
                            com.nasmusic.tv.ui.components.QualityBadge(quality = badgeQuality)
                        }
                    }
                        Text(
                        text = song.artist.ifBlank { "-" },
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 非竖屏：时长仍在行内右侧；竖屏见下方独立操作行
                if (!isPortraitPhone) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = TimeUtils.formatDuration(song.durationMs),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button()
                    )
                }
            }

            // 非竖屏（TV / 手机横屏）：操作按钮仍在行内右侧 —— 与改动前逐字等价
            if (!isPortraitPhone) {
                actionButtons()
            }
        }

        if (isPortraitPhone) {
            // 第二行：时长 + 操作按钮（独立一行，不再挤占歌名 / 艺术家的宽度）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TimeUtils.formatDuration(song.durationMs),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.button()
                )
                Spacer(modifier = Modifier.weight(1f))
                actionButtons()
            }
        }
        }   // Column（竖屏两行布局的外层容器）
    }

    // P1-16: Completed 状态点击弹出删除确认
    if (showDeleteConfirm) {
        Dialog(
            onDismissRequest = { showDeleteConfirm = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            ConfirmDialog(
                title = "删除下载",
                message = "确认删除「${song.title}」的已下载文件？",
                destructive = true,
                onConfirm = {
                    onDeleteDownload?.invoke()
                    showDeleteConfirm = false
                },
                onDismiss = { showDeleteConfirm = false }
            )
        }
    }
}

/**
 * 行内操作小按钮（收藏 / 队列 / 歌单）— Box + focusable + clickable，
 * 触屏与遥控器（D-Pad）均可操作，带焦点放大反馈与触控目标尺寸。
 */
@Composable
private fun RowActionButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    // v2.36.0：焦点视觉（高亮底 / 边框 / 1.15 缩放）只在 TV 上生效 ——
    // 手机触摸会让 `focusable()`/`clickable` 节点获得焦点且焦点粘住，
    // 否则点过一次的按钮会**永久放大 15% 并永久高亮**（与 FocusableSurface 同款处理）。
    val tvDevice = isTVDevice()
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            // 48 / 42 dp 在竖屏分别只有 39.4 / 34.4 物理 dp ❌ → §2.7 换算抬到 56dp
            .widthIn(min = portraitTouchTarget(48.dp))
            .heightIn(min = portraitTouchTarget(42.dp))
            .scale(animScale.value)
            .clip(RoundedCornerShape(8.dp))
            .background(
                color = if (isFocused) color.copy(alpha = 0.25f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.hasFocus && tvDevice
                scope.launch {
                    animScale.animateTo(
                        if (state.hasFocus && tvDevice) 1.15f else 1f,
                        tween(150)
                    )
                }
            }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = FontSize.subtitle(),
            color = if (enabled) color else color.copy(alpha = 0.4f)
        )
    }
}

/**
 * MODE_CARD：卡片布局（封面在上，文字在下）
 * 与现有 HomeScreen.HomeSongCard 样式一致
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowModeCard(
    song: Song,
    onClick: () -> Unit,
    downloadState: DownloadState = DownloadState.None,
    focusRequester: FocusRequester?
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .width(160.dp)
            .scale(animScale.value)
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.15f)
                else NasMusicColors.Surface.copy(alpha = 0.6f)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.06f else 1f,
                        tween(200)
                    )
                }
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp)
        ) {
            // 封面：已下载 → 内嵌封面（提取到缓存）→ 旁路 .jpg → 后端 URL
            // 2026-09-25 审查修复（#14 同款）：组合期提取改 remember 缓存，重组零 IO
            // LocalContext.current 是 @Composable 调用，必须在 remember lambda 外读取
            val cardCtx = androidx.compose.ui.platform.LocalContext.current
            val cardCoverUrl = remember(song.id, downloadState, song.coverUrl) {
                when (downloadState) {
                    is DownloadState.Completed -> {
                        val embedded = com.nasmusic.tv.backend.local.EmbeddedCoverExtractor.extractCoverUri(
                            downloadState.path, cardCtx.cacheDir, cardCtx
                        )
                        when {
                            embedded != null -> embedded
                            !downloadState.coverPath.isNullOrBlank() &&
                                java.io.File(downloadState.coverPath).exists() ->
                                "file://${downloadState.coverPath}"
                            else -> song.coverUrl
                        }
                    }
                    else -> song.coverUrl
                }
            }
            CoverImage(
                coverUrl = cardCoverUrl,
                contentDescription = song.title,
                size = 148.dp,
                cornerRadius = 8.dp
            )
            Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.body(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist.ifBlank { "-" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * MODE_COMPACT：紧凑行（无封面，仅文字 + 序号）
 * 用于播放队列等空间紧张的场景
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongRowModeCompact(
    song: Song,
    onClick: () -> Unit,
    index: Int?,
    focusRequester: FocusRequester?
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale.value)
            .clip(RoundedCornerShape(6.dp))
            .background(
                color = if (isFocused) NasMusicColors.Primary.copy(alpha = 0.2f)
                else Color.Transparent
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing.copy(alpha = 0.6f)
                else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .onFocusChanged { state ->
                isFocused = state.hasFocus
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.02f else 1f,
                        tween(200)
                    )
                }
            }
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            if (index != null) {
                    Text(
                    text = "${index + 1}.",
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.width(30.dp)
                )
            }
            // 歌曲信息
            Column(modifier = Modifier.weight(1f)) {
                    Text(
                    text = song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist.ifBlank { "-" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 时长
            Text(
                text = TimeUtils.formatDuration(song.durationMs),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small()
            )
        }
    }
}
