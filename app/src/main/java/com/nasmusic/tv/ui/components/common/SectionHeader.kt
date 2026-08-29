package com.nasmusic.tv.ui.components.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 统一节标题组件
 *
 * 从 HomeScreen 的 SectionHeader 提取到公共组件包。
 * 显示标题 + 计数 + 可选"查看全部"按钮。
 *
 * @param title 标题文字
 * @param count 数量（显示在标题后括号中）
 * @param onViewAll 点击"查看全部"回调（null 时不显示按钮）
 * @param modifier 额外 Modifier
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionHeader(
    title: String,
    count: Int,
    onViewAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.Subtitle
        )
        Text(
            text = " ($count)",
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.Button
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onViewAll != null) {
            FocusableSurface(
                onClick = onViewAll,
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = Color.Transparent,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                contentColor = NasMusicColors.Primary,
                focusedContentColor = NasMusicColors.Primary
            ) {
                Text(
                    text = "查看全部 >",
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.Body,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
