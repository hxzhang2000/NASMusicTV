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
import com.nasmusic.tv.ui.RegisterDialogBackHandler
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 导出设备选择对话框（§8.8.9）
 *
 * 结构照抄 [ConfirmDialog]：注册 RegisterDialogBackHandler 让 BACK 键关弹窗、
 * 焦点默认落在「取消」按钮（安全边界）。
 */
@Composable
fun ExportDeviceDialog(
    devices: List<String>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    RegisterDialogBackHandler(onDismiss)

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
                .then(responsiveDialogSize(480.dp, scrollable = true))
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
                        // 52dp 竖屏仅 42.6 物理 dp，且 `padding` 在 `clickable` 之前会把热区再削到 36 ❌
                        // → 竖屏抬到 56dp **并取消垂直 padding**（TV/横屏逐字不变，B1）
                        .height(portraitTouchTarget(52.dp))
                        .padding(vertical = if (LocalUiMode.current == UiMode.PhonePortrait) 0.dp else 4.dp),
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
                        // 52dp 在竖屏只有 42.6 物理 dp ❌ → §2.7 换算抬到 56dp（TV/横屏保持 52dp，B1）
                        .height(portraitTouchTarget(52.dp)),
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
