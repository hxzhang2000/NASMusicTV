package com.nasmusic.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ShapeDefaults
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme

/**
 * 项目级别的颜色系统（与 HTML index.html 中的 CSS 变量一一对应）
 */
object NasMusicColors {
    // 背景
    val Background = Color(0xFF0C1222)        // var(--bg)
    val Surface = Color(0xFF162032)            // var(--card-bg)
    val SurfaceVariant = Color(0xFF1E2D42)     // var(--card-hover)
    // 主色
    val Primary = Color(0xFF2DD4BF)            // var(--primary)
    val PrimaryVariant = Color(0xFF2DD4BF)
    val Secondary = Color(0xFF60A5FA)          // var(--secondary)
    // 文字
    val TextPrimary = Color(0xFFE8EDF5)        // var(--text-primary)
    val TextSecondary = Color(0xFF8899B0)      // var(--text-secondary)
    // 边框
    val Border = Color(0xFF2A3A52)             // var(--border)
    // 状态
    val Danger = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
    val Success = Color(0xFF34D399)
    // 焦点
    val FocusRing = Color(0xFF2DD4BF)          // var(--focus-ring)
    // glow / accent
    val AccentGlow = Color(0x262DD4BF)          // rgba(45, 212, 191, 0.15)
    val AccentGlowStrong = Color(0x4D2DD4BF)    // rgba(45, 212, 191, 0.30)
}

/**
 * 常用渐变 — 例如进度条、背景遮罩、渐隐 mask
 */
object NasMusicBrushes {
    val progressBar = Brush.horizontalGradient(
        colors = listOf(NasMusicColors.Primary, NasMusicColors.Secondary)
    )
    val topFadeMask = Brush.verticalGradient(
        colors = listOf(Color(0xCC0C1222), Color.Transparent)
    )
    val bottomFadeMask = Brush.verticalGradient(
        colors = listOf(Color.Transparent, Color(0xCC0C1222))
    )
    val overlayGradient = Brush.verticalGradient(
        colors = listOf(
            NasMusicColors.Background.copy(alpha = 0.85f),
            NasMusicColors.Background
        )
    )
    val coverGlow = Brush.radialGradient(
        colors = listOf(NasMusicColors.AccentGlowStrong, Color.Transparent)
    )
}

object NasMusicDimens {
    val PaddingSmall = 8.dp
    val PaddingMedium = 16.dp
    val PaddingLarge = 24.dp
    val PaddingXLarge = 40.dp
    val CornerRadius = 12.dp
    val CoverSize = 200.dp
    val CoverSizeLarge = 360.dp
}

/**
 * 类型系统
 */
private val DarkColorScheme = darkColorScheme(
    background = NasMusicColors.Background,
    surface = NasMusicColors.Surface,
    primary = NasMusicColors.Primary,
    onPrimary = Color(0xFF000000),
    onBackground = NasMusicColors.TextPrimary,
    onSurface = NasMusicColors.TextPrimary,
    onSurfaceVariant = NasMusicColors.TextSecondary
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF5F7FA),
    primary = Color(0xFF0D9488),
    onPrimary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A)
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 48.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontSize = 36.sp,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium
    ),
    titleMedium = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal
    )
)

private val AppShapes = Shapes(
    extraSmall = ShapeDefaults.ExtraSmall,
    small = ShapeDefaults.Small,
    medium = ShapeDefaults.Medium,
    large = ShapeDefaults.Large,
    extraLarge = ShapeDefaults.ExtraLarge
)

@Composable
fun NASMusicTVTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * 歌词相关主题
 */
object LyricsTheme {
    val currentLine = TextStyle(
        fontSize = 36.sp,
        fontWeight = FontWeight.SemiBold,
        color = NasMusicColors.Primary
    )
    val normalLine = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Normal,
        color = NasMusicColors.TextPrimary
    )
    val dimLine = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Normal,
        color = NasMusicColors.TextSecondary
    )
}
