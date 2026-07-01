package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.Icon
import coil.compose.AsyncImage
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.delay

/**
 * 封面轮播组件
 *
 * - 多张封面时每 10 秒切换一张
 * - 仅播放时轮播，暂停时定格
 * - 单张封面时静态显示
 * - 当前 URL 加载失败自动尝试候选列表下一项（内层 fallback）
 * - 全部失败时显示音符占位符
 */
@Composable
fun CoverCarousel(
    coverCandidates: List<String>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onAllFailed: () -> Unit = {}
) {
    var permanentlyFailed by remember { mutableStateOf(false) }

    if (coverCandidates.isEmpty()) {
        PlaceholderCover(modifier)
        if (!permanentlyFailed) { permanentlyFailed = true; onAllFailed() }
        return
    }
    if (coverCandidates.size == 1) {
        // 单张静态显示（保留 onError fallback 到占位符）
        var singleFailed by remember(coverCandidates[0]) { mutableIntStateOf(0) }
        if (singleFailed == 0) {
            AsyncImage(
                model = coverCandidates[0],
                contentDescription = "Album Cover",
                contentScale = ContentScale.Fit,
                modifier = modifier.fillMaxSize(),
                onError = { singleFailed = 1 }
            )
        } else {
            PlaceholderCover(modifier)
            if (!permanentlyFailed) { permanentlyFailed = true; onAllFailed() }
        }
        return
    }

    // 多张封面轮播
    var carouselIndex by remember(coverCandidates) { mutableIntStateOf(0) }
    var fallbackOffset by remember { mutableIntStateOf(0) }

    // 仅播放时轮播
    LaunchedEffect(isPlaying, coverCandidates) {
        if (isPlaying) {
            while (true) {
                delay(10_000L)  // 10 秒/张
                carouselIndex = (carouselIndex + 1) % coverCandidates.size
                fallbackOffset = 0  // 重置内层 fallback
            }
        }
    }

    val effectiveIndex = (carouselIndex + fallbackOffset) % coverCandidates.size
    val effectiveUrl = coverCandidates.getOrNull(effectiveIndex)

    if (effectiveUrl != null) {
        key(effectiveUrl) {
            var currentFailed by remember(effectiveUrl) { mutableIntStateOf(0) }
            if (currentFailed == 0) {
                AsyncImage(
                    model = effectiveUrl,
                    contentDescription = "Album Cover",
                    contentScale = ContentScale.Fit,
                    modifier = modifier.fillMaxSize(),
                    onError = {
                        // 当前 URL 失败，尝试候选列表下一项
                        if (fallbackOffset < coverCandidates.size - 1) {
                            fallbackOffset++
                        } else {
                            currentFailed = 1
                        }
                    }
                )
            } else {
                PlaceholderCover(modifier)
                if (!permanentlyFailed) { permanentlyFailed = true; onAllFailed() }
            }
        }
    } else {
        PlaceholderCover(modifier)
        if (!permanentlyFailed) { permanentlyFailed = true; onAllFailed() }
    }
}

/**
 * 封面占位符（音符图标）
 */
@Composable
private fun PlaceholderCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NasMusicColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = NasMusicColors.TextSecondary,
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}
