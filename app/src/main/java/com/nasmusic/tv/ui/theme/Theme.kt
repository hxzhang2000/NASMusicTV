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
    // Karaoke word highlight (B-3)
    val TextBrightHighlight = Color(0xFF5EEAD4)  // brighter teal for active karaoke words
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
 * 字号分级系统
 *
 * 所有字号统一归入 8 个档位，每个档位对应手机/TV 两套数值：
 * - 手机端保持设计稿原始值
 * - TV 端统一 +20sp（比手机端大 20 号，适配大屏）
 *
 * 使用方式：直接引用 FontSize.XX，例如 Text(fontSize = FontSize.Body)
 * 或通过 Composable 函数获取设备适配值：FontSize.body()
 */
object FontSize {
    // 档位 1: Caption（注释、徽章、最小文字）
    val Caption = 12.sp        // 手机
    val CaptionTv = 32.sp      // TV = 12 + 20

    // 档位 2: Small（次要文字、设置值、按钮小字）
    val Small = 14.sp
    val SmallTv = 34.sp        // TV = 14 + 20

    // 档位 3: Body（正文、列表项、主要文字）
    val Body = 17.sp
    val BodyTv = 37.sp         // TV = 17 + 20

    // 档位 4: Button（按钮文字、标签）
    val Button = 19.sp
    val ButtonTv = 39.sp       // TV = 19 + 20

    // 档位 5: Subtitle（副标题、卡片标题、对话框标题）
    val Subtitle = 23.sp
    val SubtitleTv = 43.sp     // TV = 23 + 20

    // 档位 6: Title（大标题、歌词普通行）
    val Title = 27.sp
    val TitleTv = 47.sp        // TV = 27 + 20

    // 档位 7: Display（展示文字、歌词当前行、大数字）
    val Display = 33.sp
    val DisplayTv = 53.sp      // TV = 33 + 20

    // 档位 8: DisplayLarge（超大展示）
    val DisplayLarge = 41.sp
    val DisplayLargeTv = 61.sp // TV = 41 + 20

    /** 根据当前设备返回对应档位的字号 */
    @Composable
    fun caption() = if (LocalPhoneCompact.current) Caption else CaptionTv
    @Composable
    fun small() = if (LocalPhoneCompact.current) Small else SmallTv
    @Composable
    fun body() = if (LocalPhoneCompact.current) Body else BodyTv
    @Composable
    fun button() = if (LocalPhoneCompact.current) Button else ButtonTv
    @Composable
    fun subtitle() = if (LocalPhoneCompact.current) Subtitle else SubtitleTv
    @Composable
    fun title() = if (LocalPhoneCompact.current) Title else TitleTv
    @Composable
    fun display() = if (LocalPhoneCompact.current) Display else DisplayTv
    @Composable
    fun displayLarge() = if (LocalPhoneCompact.current) DisplayLarge else DisplayLargeTv
}

/**
 * 按钮文字颜色系统
 *
 * 统一管理按钮在不同状态下的文字颜色：
 * - 默认（未选中）：TextPrimary（亮白色），确保可读性
 * - 聚焦：Primary（青色），视觉反馈
 * - 按下：Primary，与聚焦一致
 * - 禁用：TextSecondary（灰色），降低视觉权重
 */
object ButtonColors {
    /** 默认（未选中）文字颜色 —— 亮色，确保可读性 */
    val DefaultContent = NasMusicColors.TextPrimary

    /** 聚焦时文字颜色 —— Primary 青色 */
    val FocusedContent = NasMusicColors.Primary

    /** 按下时文字颜色 —— Primary 青色 */
    val PressedContent = NasMusicColors.Primary

    /** 禁用时文字颜色 —— TextSecondary 灰色 */
    val DisabledContent = NasMusicColors.TextSecondary
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
        fontSize = FontSize.DisplayLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontSize = FontSize.DisplayLarge,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = TextStyle(
        fontSize = FontSize.Display,
        fontWeight = FontWeight.SemiBold
    ),
    headlineMedium = TextStyle(
        fontSize = FontSize.Title,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = FontSize.Subtitle,
        fontWeight = FontWeight.Medium
    ),
    titleMedium = TextStyle(
        fontSize = FontSize.Subtitle,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = FontSize.Button,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = FontSize.Body,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = FontSize.Button,
        fontWeight = FontWeight.Medium
    ),
    labelMedium = TextStyle(
        fontSize = FontSize.Body,
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
    // TV 端字号由 FontSize.xx() @Composable 函数返回 +6sp 的 TV 值，无需 fontScale 缩放
    val lyricsTheme = LyricsThemeData(
        currentLine = LyricsTheme.currentLine,
        normalLine = LyricsTheme.normalLine,
        dimLine = LyricsTheme.dimLine
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLyricsTheme provides lyricsTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

/**
 * 歌词相关主题
 */
object LyricsTheme {
    val currentLine = TextStyle(
        fontSize = FontSize.DisplayLarge,
        fontWeight = FontWeight.SemiBold,
        color = NasMusicColors.Primary
    )
    val normalLine = TextStyle(
        fontSize = FontSize.Display,
        fontWeight = FontWeight.Normal,
        color = NasMusicColors.TextPrimary
    )
    val dimLine = TextStyle(
        fontSize = FontSize.Title,
        fontWeight = FontWeight.Normal,
        color = NasMusicColors.TextSecondary
    )
}

/** 歌词主题容器（TV/手机自动切换） */
data class LyricsThemeData(
    val currentLine: TextStyle,
    val normalLine: TextStyle,
    val dimLine: TextStyle
)

/** CompositionLocal：提供当前设备对应的歌词主题 */
val LocalLyricsTheme = androidx.compose.runtime.staticCompositionLocalOf {
    LyricsThemeData(
        currentLine = LyricsTheme.currentLine,
        normalLine = LyricsTheme.normalLine,
        dimLine = LyricsTheme.dimLine
    )
}

/**
 * 手机端紧凑 UI 尺度
 *
 * 1080p 手机横屏下整体字号/组件占位过大。通过全局 LocalDensity 缩放实现"一键紧凑"，
 * TV（大屏）不应用。
 *
 * - [PHONE_UI_SCALE]：手机端密度缩放系数（字号 sp 与尺寸 dp 同步缩小）
 * - [LocalPhoneCompact]：标记当前是否为"手机紧凑模式"，供歌词等例外组件恢复原始密度
 */
object CompactSizes {
    const val PHONE_UI_SCALE = 0.82f

    /** 歌词恢复系数 = 1 / PHONE_UI_SCALE */
    const val LYRICS_RECOVER_SCALE = 1.0f / PHONE_UI_SCALE
}

/** 当前是否处于手机紧凑模式（MainActivity 按设备类型提供） */
val LocalPhoneCompact = androidx.compose.runtime.staticCompositionLocalOf { false }


