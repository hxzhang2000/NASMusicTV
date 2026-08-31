package com.nasmusic.tv.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.net.ModelTransferServer
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.LinkUtils
import com.nasmusic.tv.util.NetworkUtils
import com.nasmusic.tv.util.QrCodeGenerator

/**
 * 模型文件扫码传输弹窗
 *
 * 打开时启动 [ModelTransferServer]，显示二维码供手机扫码。
 * 手机扫码后浏览器打开模型上传页面，可上传模型文件到 TV。
 * 关闭弹窗时自动停止 server。
 *
 * @param modelPath 模型文件路径（供 UI 显示）
 * @param modelSizeMB 模型文件大小（MB），未下载时为 0
 * @param onModelUploaded 模型上传成功回调
 * @param onDismiss 关闭弹窗
 */
@Composable
fun ModelTransferDialog(
    modelPath: String,
    modelSizeMB: Double,
    onModelUploaded: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(context.getString(R.string.model_transfer_starting)) }
    val server = remember { ModelTransferServer.create(context, onModelUploaded) }
    val closeFocusRequester = remember { FocusRequester() }

    // 启动/停止服务器
    DisposableEffect(Unit) {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            val url = "http://$ip:18082/"
            serverUrl = url
            qrBitmap = QrCodeGenerator.generateQrBitmap(url, 360)
            val started = server.startServer()
            status = if (started) context.getString(R.string.model_transfer_waiting) else context.getString(R.string.model_transfer_start_failed)
        } else {
            status = context.getString(R.string.model_transfer_no_ip)
        }
        onDispose {
            server.stopServer()
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler {
            onDismiss()
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .heightIn(max = maxHeight - 32.dp)
                    .verticalScroll(rememberScrollState())
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.model_transfer_title),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 模型信息
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(NasMusicColors.SurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.model_transfer_filename),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.small()
                    )
                    Text(
                        text = stringResource(R.string.model_transfer_expected_size),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                    if (modelSizeMB > 0) {
                        Text(
                            text = context.getString(R.string.model_transfer_downloaded, modelSizeMB),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.small()
                        )
                    }
                    Text(
                        text = stringResource(R.string.model_transfer_storage_path, modelPath),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.model_transfer_qr_desc),
                        modifier = Modifier.size(280.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.model_transfer_scan_hint),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.button()
                    )
                    Text(
                        text = stringResource(R.string.model_transfer_select_hint),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.body()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    serverUrl?.let { url ->
                        FocusableSurface(
                            onClick = { LinkUtils.openInBrowser(context, url) },
                            shape = RoundedCornerShape(6.dp),
                            focusedScale = 1.05f,
                            animationDurationMs = 150,
                            containerColor = NasMusicColors.Primary.copy(alpha = 0.08f),
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                            contentColor = NasMusicColors.Primary,
                            focusedContentColor = NasMusicColors.TextPrimary
                        ) {
                            Text(
                                text = url,
                                color = NasMusicColors.Primary,
                                fontSize = FontSize.small(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                } ?: run {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = status,
                        color = if (status.startsWith(stringResource(R.string.model_transfer_status_waiting_prefix))) NasMusicColors.Primary
                               else NasMusicColors.Warning,
                        fontSize = FontSize.button()
                    )
                    Spacer(modifier = Modifier.height(40.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 关闭按钮
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FocusableSurface(
                        onClick = { onDismiss() },
                        modifier = Modifier
                            .width(120.dp)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 120,
                        containerColor = NasMusicColors.Primary,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        focusRequester = closeFocusRequester,
                        requestFocusOnLaunch = true
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.common_close),
                                color = LocalFocusableContentColor.current,
                                fontSize = FontSize.button()
                            )
                        }
                    }
                }
            }
        }
    }
}
