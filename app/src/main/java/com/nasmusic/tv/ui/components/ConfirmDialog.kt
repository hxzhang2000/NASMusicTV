package com.nasmusic.tv.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.LocalDialogBackHandler
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 通用确认对话框（§8.7.2）
 *
 * 结构照抄 [com.nasmusic.tv.ui.screens.ExitConfirmDialog]：注册 LocalDialogBackHandler
 * 让 BACK 键关弹窗、焦点默认落在按钮、FocusableSurface 提供放大反馈。
 * [destructive] = true 时焦点默认落在「取消」按钮（安全边界）。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.common_confirm),
    cancelLabel: String = stringResource(R.string.common_cancel),
    destructive: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Level 1: 注册 BACK 键回调 —— 打开对话框时，BACK 键关闭对话框
    val backHandler = LocalDialogBackHandler.current
    DisposableEffect(onDismiss) {
        backHandler.value = { onDismiss() }
        onDispose {
            backHandler.value = null
        }
    }

    // 焦点管理：破坏性操作默认聚焦「取消」，非破坏性默认聚焦「确定」
    val confirmFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    val primaryFocusRequester = if (destructive) cancelFocusRequester else confirmFocusRequester

    LaunchedEffectLater(primaryFocusRequester)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title(),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button()
            )
            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                ConfirmButton(
                    label = cancelLabel,
                    onClick = onDismiss,
                    isPrimary = destructive,
                    focusRequester = cancelFocusRequester,
                    requestFocusOnLaunch = destructive
                )
                ConfirmButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    isPrimary = !destructive,
                    focusRequester = confirmFocusRequester,
                    requestFocusOnLaunch = !destructive
                )
            }
        }
    }
}

@Composable
private fun ConfirmButton(
    label: String,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null,
    requestFocusOnLaunch: Boolean = false
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)
            .height(52.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.08f,
        animationDurationMs = 150,
        containerColor = if (isPrimary) NasMusicColors.Primary else NasMusicColors.SurfaceVariant,
        focusedContainerColor = if (isPrimary) NasMusicColors.Primary.copy(alpha = 0.85f)
                                 else NasMusicColors.Primary.copy(alpha = 0.25f),
        contentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
        focusedContentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
        pressedScale = 0.95f,
        focusRequester = focusRequester,
        requestFocusOnLaunch = requestFocusOnLaunch
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 启动时请求焦点（封装 LaunchedEffect 以避免顶级导入歧义） */
@Composable
private fun LaunchedEffectLater(focusRequester: FocusRequester) {
    androidx.compose.runtime.LaunchedEffect(focusRequester) {
        runCatching { focusRequester.requestFocus() }
    }
}
