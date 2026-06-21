package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.ui.theme.Accent
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

/**
 * 均衡器屏幕
 * 预设选择 + 各频段增益调节（数字显示）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EqualizerScreen(
    presets: List<EqualizerPreset>,
    currentPreset: EqualizerPreset,
    currentBands: List<Float>,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onAdjustBand: (Int, Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        // 返回 + 标题
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackButton(onClick = onBack)
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "均衡器",
                color = NasMusicColors.TextPrimary,
                fontSize = 24.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 预设列表
            item {
                Text(
                    text = "预设",
                    color = NasMusicColors.TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(presets) { preset ->
                val isSelected = currentPreset?.name == preset.name
                var isFocused by remember { mutableStateOf(false) }
                val animScale = remember { Animatable(1f) }
                val scope = rememberCoroutineScope()
                Surface(
                    onClick = { onSelectPreset(preset) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .scale(animScale.value)
                        .border(
                            width = if (isFocused) 2.dp else if (isSelected) 1.dp else 0.dp,
                            color = if (isFocused) NasMusicColors.FocusRing
                                    else if (isSelected) NasMusicColors.Primary
                                    else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .onFocusChanged {
                            isFocused = it.isFocused
                            scope.launch {
                                animScale.animateTo(if (isFocused) 1.02f else 1f, tween(200))
                            }
                        },
                    shape = ClickableSurfaceDefaults.shape(
                        shape = RoundedCornerShape(8.dp),
                        focusedShape = RoundedCornerShape(8.dp)
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) NasMusicColors.Primary.copy(alpha = 0.2f)
                                        else NasMusicColors.Surface.copy(alpha = 0.5f),
                        contentColor = if (isSelected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        focusedContentColor = NasMusicColors.Primary
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isSelected) "✓  " else "   ",
                            color = NasMusicColors.Primary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = preset.displayName,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 频段调节（仅在自定义预设时显示）
            if (currentBands.size >= 5) {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "频段增益",
                        color = NasMusicColors.TextPrimary,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                val bandLabels = listOf("31Hz", "63Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
                items(bandLabels.size) { index ->
                    val band = currentBands.getOrElse(index) { 0f }
                    var isFocused by remember { mutableStateOf(false) }
                    val animScale = remember { Animatable(1f) }
                    val scope = rememberCoroutineScope()

                    Surface(
                        onClick = {
                            val newValue = when {
                                band <= -10f -> 0f
                                band >= 10f -> -10f
                                else -> band + 1f
                            }
                            onAdjustBand(index, newValue)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(animScale.value)
                            .border(
                                width = if (isFocused) 2.dp else 0.dp,
                                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .onFocusChanged {
                                isFocused = it.isFocused
                                scope.launch {
                                    animScale.animateTo(if (isFocused) 1.02f else 1f, tween(200))
                                }
                            },
                        shape = ClickableSurfaceDefaults.shape(
                            shape = RoundedCornerShape(6.dp),
                            focusedShape = RoundedCornerShape(6.dp)
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = NasMusicColors.Surface.copy(alpha = 0.5f),
                            contentColor = NasMusicColors.TextPrimary,
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f)
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bandLabels[index],
                                color = NasMusicColors.TextPrimary,
                                fontSize = 13.sp,
                                modifier = Modifier.width(60.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            val dbText = if (band > 0) "+${"%.1f".format(band)} dB"
                                         else "${"%.1f".format(band)} dB"
                            Text(
                                text = dbText,
                                color = if (band > 0) NasMusicColors.Primary
                                        else if (band < 0) Accent
                                        else NasMusicColors.TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackButton(onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch { animScale.animateTo(if (isFocused) 1.08f else 1f, tween(200)) }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.Primary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.96f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "←", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            Text(text = "返回", fontSize = 14.sp)
        }
    }
}
