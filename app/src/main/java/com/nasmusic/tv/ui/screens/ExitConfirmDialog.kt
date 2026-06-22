package com.nasmusic.tv.ui.screens

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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.LocalDialogBackHandler

@Composable
fun ExitConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
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

    // 焦点管理：弹窗打开时，焦点聚焦在"确定"按钮上
    val confirmFocusRequester = remember { FocusRequester() }

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
                text = stringResource(R.string.exit_title),
                color = NasMusicColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.exit_confirm_message),
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                ExitButton(
                    label = stringResource(R.string.common_cancel),
                    onClick = onDismiss,
                    isPrimary = false
                )
                ExitButton(
                    label = stringResource(R.string.common_confirm),
                    onClick = onConfirm,
                    isPrimary = true,
                    focusRequester = confirmFocusRequester,
                    requestFocusOnLaunch = true
                )
            }
        }
    }
}

@Composable
private fun ExitButton(
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
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
