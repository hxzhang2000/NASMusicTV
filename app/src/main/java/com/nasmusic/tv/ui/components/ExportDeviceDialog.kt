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
 * 导出设备选择对话框（§8.8.9）
 *
 * 结构照抄 [ConfirmDialog]：注册 LocalDialogBackHandler 让 BACK 键关弹窗、
 * 焦点默认落在「取消」按钮（安全边界）。
 */
@Composable
fun ExportDeviceDialog(
    devices: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backHandler = LocalDialogBackHandler.current
    DisposableEffect(onDismiss) {
        backHandler.value = { onDismiss() }
        onDispose { backHandler.value = null }
    }

    val cancelFocusRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(cancelFocusRequester) {
        runCatching { cancelFocusRequester.requestFocus() }
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
                text = stringResource(R.string.dialog_export_device_title),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title(),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            devices.forEachIndexed { index, label ->
                FocusableSurface(
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.SurfaceVariant,
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                    contentColor = NasMusicColors.TextPrimary,
                    focusedContentColor = NasMusicColors.TextPrimary,
                    pressedScale = 0.95f
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = label,
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                FocusableSurface(
                    onClick = onDismiss,
                    modifier = Modifier
                        .width(140.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 150,
                    containerColor = NasMusicColors.Primary,
                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                    contentColor = Color.Black,
                    focusedContentColor = Color.Black,
                    pressedScale = 0.95f,
                    focusRequester = cancelFocusRequester,
                    requestFocusOnLaunch = true
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.common_cancel),
                            color = Color.Black,
                            fontSize = FontSize.button(),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
