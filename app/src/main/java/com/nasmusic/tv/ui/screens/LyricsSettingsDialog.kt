package com.nasmusic.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 歌词设置对话框
 *
 * 提供歌词字体缩放调节。
 * 通过 D-Pad 导航选择缩放比例。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LyricsSettingsDialog(
    currentFontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val scales = listOf(
        0.7f to "小",
        1.0f to "中",
        1.3f to "大",
        1.6f to "超大"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth(0.5f)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "歌词设置",
            color = NasMusicColors.TextPrimary,
            fontSize = 25.sp
        )

        Text(
            text = "字体大小",
            color = NasMusicColors.TextSecondary,
            fontSize = 19.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            scales.forEach { (scale, label) ->
                val isSelected = currentFontScale == scale
                FocusableSurface(
                    onClick = { onFontScaleChange(scale) },
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.1f,
                    animationDurationMs = 150,
                    containerColor = if (isSelected)
                        NasMusicColors.Primary
                    else
                        NasMusicColors.Surface.copy(alpha = 0.7f),
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = if (isSelected)
                        NasMusicColors.Surface
                    else
                        NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Surface
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) NasMusicColors.Surface else NasMusicColors.TextPrimary,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // 显示当前预览文字
        val previewSize = 14 * currentFontScale
        Text(
            text = "预览文字",
            color = NasMusicColors.TextPrimary,
            fontSize = previewSize.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 关闭按钮
        FocusableSurface(
            onClick = onDismiss,
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.06f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.Primary
        ) {
            Text(
                text = "关闭",
                color = NasMusicColors.TextPrimary,
                fontSize = 19.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
    }
}
