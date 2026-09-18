package com.nasmusic.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * Shimmer 渐变 Modifier — 水平方向流动的高光效果。
 */
@Composable
fun Modifier.shimmer(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -200f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    return this.background(
        Brush.linearGradient(
            colors = listOf(
                NasMusicColors.SurfaceVariant,
                NasMusicColors.SurfaceVariant.copy(alpha = 0.5f),
                NasMusicColors.SurfaceVariant
            ),
            start = Offset(translateX, 0f),
            end = Offset(translateX + 300f, 0f)
        )
    )
}

/**
 * 专辑卡片骨架 — 与 AlbumCard 布局一致：方形封面 + 两行文字。
 */
@Composable
fun AlbumCardSkeleton() {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .shimmer()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )
    }
}

/**
 * 艺术家卡片骨架 — 与 ArtistCard 布局一致：圆形头像 + 两行文字。
 */
@Composable
fun ArtistCardSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .shimmer()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmer()
        )
    }
}

/**
 * 专辑骨架屏 — 12 张占位卡片。列数走 `adaptiveColumns`，与真数据一致
 * （v2.36.0 竖屏：原固定 6 列在 360dp 屏上每卡仅 ~50dp）。
 */
@Composable
fun AlbumSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(adaptiveColumns(tv = 6, phonePortrait = 3, medium = 6)),
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false
    ) {
        items(12) {
            AlbumCardSkeleton()
        }
    }
}

/**
 * 艺术家骨架屏 — 12 张占位卡片。列数走 `adaptiveColumns`，与真数据一致。
 */
@Composable
fun ArtistSkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(adaptiveColumns(tv = 6, phonePortrait = 3, medium = 6)),
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false
    ) {
        items(12) {
            ArtistCardSkeleton()
        }
    }
}
