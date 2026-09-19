package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.flow.StateFlow

/**
 * 竖屏迷你播放条（v2.36.0，方案 §4.0 / §8.5）。
 *
 * ⚠️⚠️ **进度/时长必须在 MiniPlayer 内部订阅**：
 * `AppRoot.kt` 的 F-2 修复明确禁止在 AppRoot 顶层收集 `progress`/`duration` ——
 * 进度由 `PlayerManager` 的 1000ms `Handler` 轮询驱动，顶层收集会**每秒驱动 AppRoot
 * 全树重组**（含 LazyColumn 状态与 D-Pad 焦点搜索）。因此这里接收 [StateFlow] 并在
 * 本 composable 内 `collectAsState` → 每秒只重组 MiniPlayer 自身。
 *
 * inset：外层 `navigationBarsPadding()` 处理三键导航（D4 / D9 前提下生效）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MiniPlayer(
    song: Song,
    coverCandidates: List<String>,
    progressFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 只在本 composable 内订阅（K1 / F-2）
    val progress by progressFlow.collectAsState(initial = 0L)
    val duration by durationFlow.collectAsState(initial = 0L)
    val fraction = if (duration > 0L) {
        (progress.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // v2.36.0（方案 §9 P2-33）：上滑展开播放页。
    // ⚠️ pointerInput(Unit) 不会随重组重启，直接捕获 onExpand 会拿到旧 lambda
    // → 用 rememberUpdatedState 持有最新回调（与 RegisterDialogBackHandler 同一手法）。
    val currentOnExpand by rememberUpdatedState(onExpand)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(NasMusicColors.Surface)
            .navigationBarsPadding()
    ) {
        // 顶部 2dp 进度细线
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(NasMusicColors.Primary)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 2.dp)
                .clickable(onClick = onExpand)
                .pointerInput(Unit) {
                    // 48dp 位移视为一次有效上滑（与 §2.7 触摸目标口径一致）
                    val thresholdPx = 48.dp.toPx()
                    var accumulated = 0f
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (accumulated <= -thresholdPx) currentOnExpand()
                            accumulated = 0f
                        },
                        onDragCancel = { accumulated = 0f },
                    ) { _, dragAmount ->
                        accumulated += dragAmount
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(12.dp))
            // 封面 48dp（复用 CoverCarousel，与 QueueScreen 侧卡同一套多候选 fallback）
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NasMusicColors.SurfaceVariant)
            ) {
                CoverCarousel(
                    coverCandidates = coverCandidates,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist.ifBlank { "—" },
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.caption(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            MiniPlayerIconButton(
                contentDescription = stringResource(
                    if (isPlaying) R.string.miniplayer_cd_pause else R.string.miniplayer_cd_play
                ),
                onClick = onPlayPause,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            MiniPlayerIconButton(
                contentDescription = stringResource(R.string.miniplayer_cd_next),
                onClick = onNext,
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

/**
 * **[PHONE_TOUCH_TARGET]（56 Compose dp ≈ 45.9 物理 dp ≥ 44）** 触摸目标，配 64dp 容器高度。
 *
 * ⚠️ 早期写成 48dp（≈39.4 物理 dp）不达标，见方案 §2.7 第 1 条 / P0-26。
 */
@Composable
private fun MiniPlayerIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .size(PHONE_TOUCH_TARGET)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(8.dp),
        containerColor = Color.Transparent,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        // P2-34：手机触摸没有焦点边框，按下高亮是唯一的"已响应"视觉反馈
        pressedContainerColor = NasMusicColors.Primary.copy(alpha = 0.35f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary,
        focusedScale = 1.06f,
        animationDurationMs = 150,
        pressedScale = 0.92f,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
