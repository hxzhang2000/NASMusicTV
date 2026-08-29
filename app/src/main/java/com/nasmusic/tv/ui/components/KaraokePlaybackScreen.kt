package com.nasmusic.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import com.nasmusic.tv.util.QrCodeGenerator
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * KARAOKE 全屏播放页面
 *
 * 布局：
 * - 全屏封面背景（ContentScale.Crop + blur + 暗色渐变遮罩）
 * - 中上部：歌曲名 + 歌手名
 * - 下方：歌词区域（置画面下方，字体放大，置于半透明黑色全宽色块中）
 * - 底部：左上角返回按钮 + 右下角控制栏（上一首/播放暂停/下一首/升降调/变速/原唱伴唱切换）
 *
 * 无进度条控制。原唱/伴唱按钮只切换音频，不退出页面。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaraokePlaybackScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    lyrics: Lyrics?,
    coverCandidates: List<String>,
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    /** 当前播放进度（毫秒），驱动歌词定位 —— 必须与播放页传入的同一 progressMs 来源 */
    progressMs: Long,
    /** 歌曲总时长（毫秒），用于底部细进度线 —— 与播放页传入的同一 durationMs 来源 */
    durationMs: Long,
    vocalRemovalEnabled: Boolean,
    onToggleVocalRemoval: () -> Unit,
    onExitKaraoke: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** 升降调（半音 -12~+12，0 = 原调） */
    pitchSemitones: Int = 0,
    /** 播放速度（0.5~2.0，1.0 = 原速） */
    playbackSpeed: Double = 1.0,
    /** 设置升降调回调 */
    onSetPitch: (Int) -> Unit = {},
    /** 设置播放速度回调 */
    onSetSpeed: (Double) -> Unit = {},
    /** 重置升降调回调 */
    onResetPitch: () -> Unit = {},
    /** 重置播放速度回调 */
    onResetSpeed: () -> Unit = {},
    /** 是否为高质量分离模式 */
    isHighQualityMode: Boolean = false,
    /** 高质量分离是否正在进行 */
    isSeparating: Boolean = false,
    /** 高质量分离进度（0f~1f）与阶段描述 */
    separationProgress: Pair<Float, String> = 0f to "",
    /** 高质量分离错误信息（非空时显示红色错误提示） */
    hqError: String? = null,
    /** 清除高质量分离错误 */
    onClearHqError: () -> Unit = {},
    /** 高质量分离成功信息（非空时显示绿色成功提示） */
    hqSuccess: String? = null,
    /** 清除高质量分离成功信息 */
    onClearHqSuccess: () -> Unit = {},
    /** 高质量分离模型是否已下载（未下载时禁用高质量切换） */
    modelDownloaded: Boolean = false,
    /** 切换分离模式回调（快速↔高质量） */
    onToggleSeparationMode: () -> Unit = {},
    playPauseFocusRequester: FocusRequester? = null,
    remoteControlUrl: String? = null
) {
    // ── 二维码自动显隐：5 秒无操作 -> 完全隐藏，操作时显化 ──
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun activateControls() { lastInteraction = System.currentTimeMillis() }
    LaunchedEffect(lastInteraction) {
        controlsVisible = true
        delay(5000)
        if (System.currentTimeMillis() - lastInteraction >= 5000) controlsVisible = false
    }

    // ── 升降调 / 变速选择弹窗状态 ──
    var showPitchPicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .onFocusChanged { if (it.hasFocus) activateControls() }
            .onPreviewKeyEvent { activateControls(); false }
    ) {
        // ── 手机遥控二维码（右上角，5 秒无操作完全隐藏）──
        val qrBitmap = remember(remoteControlUrl) {
            remoteControlUrl?.let { QrCodeGenerator.generateQrBitmap(it, 256) }
        }
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "扫码遥控",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 必须先于同 Box 中后声明的全屏背景/遮罩绘制，否则会被盖住
                    .zIndex(10f)
                    .padding(24.dp)
                    .size(80.dp)
                    .alpha(if (controlsVisible) 1f else 0f)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            )
        }

        // ── 高质量分离中浮层提示 ──
        if (isSeparating && isHighQualityMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(10f)
                    .background(Color(0xDD000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "正在转换伴奏…",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = NasMusicColors.TextPrimary
                    )
                    if (separationProgress.first > 0f) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${(separationProgress.first * 100).toInt()}% · ${separationProgress.second}",
                            fontSize = 13.sp,
                            color = NasMusicColors.TextSecondary
                        )
                    }
                }
            }
        }

        // ── 高质量分离错误提示（测试期间保持30分钟）──
        if (hqError != null && !isSeparating) {
            val errorKey = hqError
            LaunchedEffect(errorKey) {
                delay(30 * 60 * 1000L)  // 测试期间保持 30 分钟，正常后改为 3-5 秒
                onClearHqError()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(10f)
                    .background(Color(0xDD331111), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hqError,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFF8888)
                )
            }
        }

        // ── 高质量分离成功提示（绿色，测试期间保持30分钟）──
        if (hqSuccess != null && !isSeparating) {
            val successKey = hqSuccess
            LaunchedEffect(successKey) {
                delay(30 * 60 * 1000L)  // 测试期间保持 30 分钟，正常后改为 3-5 秒
                onClearHqSuccess()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(10f)
                    .background(Color(0xDD113311), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hqSuccess,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF88FF88)
                )
            }
        }

        // ── 全屏封面背景 ──
        val bgUrl = coverCandidates.firstOrNull() ?: currentSong?.coverUrl
        if (bgUrl != null) {
            val painter = rememberAsyncImagePainter(model = bgUrl)
            Image(
                painter = painter,
                contentDescription = "Karaoke Fullscreen Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (coverFilterEnabled && coverFilterBlurRadius > 0f)
                            Modifier.blur(coverFilterBlurRadius.dp)
                        else Modifier
                    )
            )
        }
        // 暗色渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC0C1222),
                            Color(0x990C1222),
                            Color(0xCC0C1222)
                        )
                    )
                )
        )

        // ── 前景内容 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上部：歌曲信息
            Column(
                modifier = Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentSong?.title ?: "",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentSong?.artist ?: "",
                    fontSize = 20.sp,
                    color = NasMusicColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            Spacer(Modifier.weight(1f))

            // 下部：歌词区域 —— 半透明黑色全宽框，上下保留空间；
            // 框的下沿有一条细进度线（青色→蓝色渐变），颜色/渐变与播放页进度条一致，
            // 但更细（2.dp）、无圆点滑块，仅作整曲进度指示，不参与焦点与 seek。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
            ) {
                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = progressMs,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp)
                )
                // 进度细线：背景轨道 + 渐变填充，贴半透明框下沿（下沿上方留 8dp 间距）
                val karaokeProgress = if (durationMs > 0L) {
                    (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .height(2.dp)
                ) {
                    // 轨道底色
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(1.dp))
                            .background(NasMusicColors.SurfaceVariant.copy(alpha = 0.6f))
                    )
                    // 渐变进度填充
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(karaokeProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(NasMusicBrushes.progressBar)
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }

        // ── 底部栏：左下角返回 + 右下角控制 ──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮（左下角）
            MiniIconButton(
                onClick = { activateControls(); onExitKaraoke() },
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )

            // 控制栏（右下角）
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniIconButton(
                    onClick = { activateControls(); onPrevious() },
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous"
                )
                Spacer(Modifier.width(20.dp))
                MiniIconButton(
                    onClick = { activateControls(); onPlayPause() },
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    primary = true,
                    focusRequester = playPauseFocusRequester
                )
                Spacer(Modifier.width(20.dp))
                MiniIconButton(
                    onClick = { activateControls(); onNext() },
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Next"
                )
                Spacer(Modifier.width(16.dp))

                // ── 升降调按钮 ──
                KaraokeSettingButton(
                    label = "调",
                    value = when (pitchSemitones) {
                        0 -> "原调"
                        in 1..12 -> "+${pitchSemitones}"
                        else -> "$pitchSemitones"
                    },
                    isModified = pitchSemitones != 0,
                    onClick = { activateControls(); showPitchPicker = true }
                )
                Spacer(Modifier.width(10.dp))

                // ── 变速按钮 ──
                KaraokeSettingButton(
                    label = "速",
                    value = when {
                        playbackSpeed == 1.0 -> "原速"
                        playbackSpeed < 1.0 -> String.format("%.1f", playbackSpeed)
                        else -> String.format("%.1f", playbackSpeed)
                    },
                    isModified = playbackSpeed != 1.0,
                    onClick = { activateControls(); showSpeedPicker = true }
                )
                Spacer(Modifier.width(16.dp))

                // 原唱/伴唱切换（只切换音频，不退出页面）
                VocalToggleButton(
                    label = if (vocalRemovalEnabled) "原唱" else "伴唱",
                    onClick = { activateControls(); onToggleVocalRemoval() }
                )
                Spacer(Modifier.width(10.dp))

                // ── 分离模式切换（快速/高质量）──
                KaraokeSettingButton(
                    label = "质量",
                    value = when {
                        !modelDownloaded -> "🔒"
                        isSeparating -> "转换中"
                        isHighQualityMode -> "高质"
                        else -> "快速"
                    },
                    isModified = isHighQualityMode,
                    onClick = {
                        activateControls()
                        // 模型未下载或正在转换中时禁止切换
                        if (isSeparating) return@KaraokeSettingButton
                        if (modelDownloaded || isHighQualityMode) {
                            onToggleSeparationMode()
                        }
                    }
                )
            }
        }
    }

    // 进入 KARAOKE 页面时自动聚焦播放/暂停按钮
    LaunchedEffect(Unit) {
        try {
            playPauseFocusRequester?.requestFocus()
        } catch (_: Exception) {
        }
    }

    // ── 升降调选择弹窗 ──
    if (showPitchPicker) {
        KaraokeStepPickerDialog(
            title = "升降调",
            steps = (-12..12).toList(),
            currentValue = pitchSemitones,
            formatLabel = { semitones ->
                when (semitones) {
                    0 -> "原调"
                    in 1..12 -> "+${semitones}"
                    else -> "$semitones"
                }
            },
            onConfirm = { onSetPitch(it) },
            onReset = { onResetPitch() },
            onDismiss = { showPitchPicker = false }
        )
    }

    // ── 变速选择弹窗 ──
    if (showSpeedPicker) {
        KaraokeStepPickerDialog(
            title = "播放速度",
            steps = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0),
            currentValue = playbackSpeed,
            formatLabel = { speed ->
                when (speed) {
                    1.0 -> "原速"
                    else -> String.format("%.1f", speed)
                }
            },
            onConfirm = { onSetSpeed(it) },
            onReset = { onResetSpeed() },
            onDismiss = { showSpeedPicker = false }
        )
    }
}

/**
 * 简易图标按钮（用于 KARAOKE 控制栏）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    val buttonSize = if (primary) 72.dp else 56.dp
    val iconSize = if (primary) 36.dp else 28.dp

    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(50),
        focusedScale = 1.12f,
        animationDurationMs = 200,
        containerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Surface,
        focusedContainerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = if (primary) NasMusicColors.TextPrimary else NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f,
        focusBorderColor = NasMusicColors.FocusRing
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * K 歌页设置按钮（升降调 / 变速）
 *
 * 显示 标签 + 当前值，修改后高亮提示，点击弹出步进选择弹窗。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KaraokeSettingButton(
    label: String,
    value: String,
    isModified: Boolean,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(width = 84.dp, height = 56.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = if (isModified) NasMusicColors.Primary.copy(alpha = 0.25f) else NasMusicColors.Surface,
        focusedContainerColor = if (isModified) NasMusicColors.Primary.copy(alpha = 0.4f) else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f,
        focusBorderColor = NasMusicColors.FocusRing
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isModified) NasMusicColors.Primary else LocalFocusableContentColor.current
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = NasMusicColors.TextPrimary,
                maxLines = 1
            )
        }
    }
}

/**
 * K 歌步进选择弹窗（升降调 / 变速通用）
 *
 * TV 遥控器适配：D-Pad 左右切换选项，OK 键确认。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun <T> KaraokeStepPickerDialog(
    title: String,
    steps: List<T>,
    currentValue: T,
    formatLabel: (T) -> String,
    onConfirm: (T) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIndex = remember(steps, currentValue) {
        steps.indexOf(currentValue).coerceAtLeast(0)
    }
    var tempIndex by remember { mutableStateOf(selectedIndex) }
    val focusRequester = remember { FocusRequester() }

    // 弹窗打开时自动聚焦到选项区域
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x80000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .padding(24.dp)
                .background(NasMusicColors.Background, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 阻止点击穿透到遮罩层
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary
                )
                Spacer(Modifier.height(20.dp))

                // 选项区域：左右箭头 + 当前值
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    // 左箭头
                    FocusableSurface(
                        onClick = {
                            if (tempIndex > 0) tempIndex--
                        },
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(50),
                        focusedScale = 1.1f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.9f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "<", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NasMusicColors.TextPrimary)
                        }
                    }

                    // 当前值
                    FocusableSurface(
                        onClick = { onConfirm(steps[tempIndex]); onDismiss() },
                        modifier = Modifier
                            .width(160.dp)
                            .height(56.dp)
                            .then(if (tempIndex == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.4f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = formatLabel(steps[tempIndex]),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = NasMusicColors.TextPrimary
                            )
                        }
                    }

                    // 右箭头
                    FocusableSurface(
                        onClick = {
                            if (tempIndex < steps.lastIndex) tempIndex++
                        },
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(50),
                        focusedScale = 1.1f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.9f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = ">", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NasMusicColors.TextPrimary)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 重置 + 取消按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FocusableSurface(
                        onClick = { onReset(); onDismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.05f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "重置", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalFocusableContentColor.current)
                        }
                    }

                    FocusableSurface(
                        onClick = { onConfirm(steps[tempIndex]); onDismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.05f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Primary,
                        focusedContainerColor = NasMusicColors.Primary,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "确定", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = LocalFocusableContentColor.current)
                        }
                    }
                }
            }
        }
    }
}