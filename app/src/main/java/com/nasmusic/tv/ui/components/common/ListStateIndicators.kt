package com.nasmusic.tv.ui.components.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 居中加载动画 + 可选文字
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LoadingIndicator(
    text: String? = null,
    modifier: Modifier = Modifier
) {
    val resolvedText = text ?: stringResource(R.string.list_loading)
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "⟳",
                color = NasMusicColors.Primary,
                fontSize = FontSize.display()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = resolvedText,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button()
            )
        }
    }
}

/**
 * 错误信息 + 重试按钮
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ErrorDisplay(
    message: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedMessage = message ?: stringResource(R.string.list_load_failed)
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "⚠",
                color = NasMusicColors.Warning,
                fontSize = FontSize.display()
            )
            Text(
                text = resolvedMessage,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                FocusableSurface(
                    onClick = onRetry,
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                    focusedContainerColor = NasMusicColors.Primary,
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.TextPrimary
                ) {
                    Text(
                        text = stringResource(R.string.list_retry),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.button(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 空状态图标 + 提示文字 + 可选操作按钮
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmptyState(
    message: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val resolvedMessage = message ?: stringResource(R.string.list_empty)
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "📭",
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.display()
            )
            Text(
                text = resolvedMessage,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                textAlign = TextAlign.Center
            )
            if (actionText != null && onAction != null) {
                FocusableSurface(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.Surface.copy(alpha = 0.7f),
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.Primary
                ) {
                    Text(
                        text = actionText,
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.button(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
