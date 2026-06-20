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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.LocalDialogBackHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
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
    LaunchedEffect(Unit) {
        try {
            confirmFocusRequester.requestFocus()
        } catch (_: Exception) {
            // 忽略焦点请求异常
        }
    }

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
                text = "退出应用",
                color = NasMusicColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "确定要退出 NAS Music 吗？",
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                ExitButton(
                    label = "取消",
                    onClick = onDismiss,
                    isPrimary = false
                )
                ExitButton(
                    label = "确定",
                    onClick = onConfirm,
                    isPrimary = true,
                    focusRequester = confirmFocusRequester
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExitButton(
    label: String,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)
            .height(52.dp)
            .scale(animScale.value)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(if (isFocused) 1.08f else 1f, tween(150))
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(10.dp),
            focusedShape = RoundedCornerShape(10.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isPrimary) NasMusicColors.Primary else NasMusicColors.SurfaceVariant,
            contentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
            focusedContainerColor = if (isPrimary) NasMusicColors.Primary.copy(alpha = 0.85f)
                                    else NasMusicColors.Primary.copy(alpha = 0.25f),
            focusedContentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.95f
        )
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
