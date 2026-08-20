package com.nasmusic.tv.ui.screens.netdisk

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.util.QrCodeGenerator

/**
 * 百度网盘设备码授权对话框
 *
 * 用户点击"登录"后弹出：显示设备码、验证链接与二维码（复用 [QrCodeGenerator]，
 * 与 [com.nasmusic.tv.ui.screens.BackupTransferDialog] 同款），手机扫码/打开链接并输入
 * 设备码完成授权，期间自动轮询。
 *
 * 状态流转由 [connectionState] 驱动：
 * - [MainViewModel.BaiduConnectionState.LoggedIn] → 授权成功，自动关闭
 * - [MainViewModel.BaiduConnectionState.Failed] → 失败（拒绝/超时/异常），显示错误后自动关闭
 * - [MainViewModel.BaiduConnectionState.Connecting] → 等待用户扫码授权
 *
 * @param deviceCode 设备码结果（null 表示请求中或已失败）
 * @param connectionState 当前连接状态
 * @param onCancel 用户取消授权（调用方负责停止轮询 [MainViewModel.cancelBaiduDeviceCode]）
 * @param onDismiss 关闭对话框
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BaiduAuthDialog(
    deviceCode: BaiduOAuthClient.DeviceCodeResult?,
    connectionState: MainViewModel.BaiduConnectionState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 二维码内容：编码稳定的验证页（verificationUrl），扫码后打开标准验证页手动输入设备码。
    // 不使用 qrcode_url：其一次性 token 链接（如 .../device/qrcode/<token>）扫码后经常打不开。
    val qrContent = remember(deviceCode) { deviceCode?.verificationUrl }
    LaunchedEffect(qrContent) {
        qrBitmap = qrContent?.let { QrCodeGenerator.generateQrBitmap(it, 360) }
    }

    // 授权成功或失败 → 短暂展示后自动关闭
    LaunchedEffect(connectionState) {
        if (connectionState is MainViewModel.BaiduConnectionState.LoggedIn) {
            kotlinx.coroutines.delay(600)
            onDismiss()
        } else if (connectionState is MainViewModel.BaiduConnectionState.Failed) {
            kotlinx.coroutines.delay(1500)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(600.dp)
                    .heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.netdisk_auth_title),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 23.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.netdisk_auth_subtitle),
                    color = NasMusicColors.TextSecondary,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                when {
                    // 设备码请求中
                    deviceCode == null && connectionState is MainViewModel.BaiduConnectionState.Connecting -> {
                        Spacer(modifier = Modifier.height(36.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_fetching),
                            color = NasMusicColors.Primary,
                            fontSize = 19.sp
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                    }
                    // 已获取设备码：显示分步指引 + 二维码
                    deviceCode != null -> {
                        // 主流程：分步说明（手机手动打开网址输入设备码，最可靠的授权方式）
                        Text(
                            text = stringResource(R.string.netdisk_auth_step1),
                            color = NasMusicColors.TextPrimary,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = deviceCode.verificationUrl,
                            color = NasMusicColors.Primary,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_step2),
                            color = NasMusicColors.TextPrimary,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = deviceCode.userCode,
                            color = NasMusicColors.Primary,
                            fontSize = 34.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // 辅助：二维码（扫码可能因 App 拦截/网络不可用，故不作为主流程）
                        Text(
                            text = stringResource(R.string.netdisk_auth_qr_alt),
                            color = NasMusicColors.TextSecondary,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        qrBitmap?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.netdisk_auth_qr_desc),
                                modifier = Modifier.size(200.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_waiting),
                            color = NasMusicColors.Primary,
                            fontSize = 18.sp
                        )
                    }
                    // 请求失败
                    else -> {
                        Spacer(modifier = Modifier.height(36.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_fetch_failed),
                            color = NasMusicColors.Warning,
                            fontSize = 19.sp
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusableSurface(
                        onClick = { onCancel() },
                        modifier = Modifier
                            .width(140.dp)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 120,
                        containerColor = NasMusicColors.SurfaceVariant,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = Color.Black
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(text = stringResource(R.string.common_cancel), fontSize = 19.sp)
                        }
                    }
                }
            }
        }
    }
}