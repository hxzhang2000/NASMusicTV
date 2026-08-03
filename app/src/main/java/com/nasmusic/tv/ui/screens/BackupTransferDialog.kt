package com.nasmusic.tv.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.nasmusic.tv.net.BackupTransferServer
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.NetworkUtils
import com.nasmusic.tv.util.QrCodeGenerator

/**
 * 备份扫码传输弹窗
 *
 * 打开时启动 [BackupTransferServer]，显示二维码供手机扫码。
 * 手机扫码后浏览器打开备份管理页面，可下载/上传/恢复备份。
 * 关闭弹窗时自动停止 server。
 *
 * @param onRestore 恢复备份的回调，传入备份 JSON 内容，返回是否成功。
 *   非挂起类型：调用方负责同步桥接 suspend 逻辑（server 在 NanoHTTPD 工作线程上调用）
 * @param onDismiss 关闭弹窗
 */
@Composable
fun BackupTransferDialog(
    onRestore: (String) -> Boolean,
    onBackupChanged: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("启动中...") }
    val server = remember { BackupTransferServer(context, onRestore, onBackupChanged) }
    val closeFocusRequester = remember { FocusRequester() }

    // 启动/停止服务器
    DisposableEffect(Unit) {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            val url = "http://$ip:${BackupTransferServer.DEFAULT_PORT}/"
            serverUrl = url
            qrBitmap = QrCodeGenerator.generateQrBitmap(url, 360)
            val started = server.start()
            status = if (started) "等待手机扫码连接" else "服务器启动失败（端口被占）"
        } else {
            status = "无法获取网络 IP，请检查 Wi-Fi 连接"
        }
        onDispose {
            server.stop()
        }
    }

    BackHandler {
        onDismiss()
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
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
                    .width(560.dp)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "扫码传输备份",
                    color = NasMusicColors.TextPrimary,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "备份传输二维码",
                        modifier = Modifier.size(280.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "手机扫码打开备份管理页",
                        color = NasMusicColors.TextPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "可下载备份到手机 / 上传备份到电视 / 恢复备份",
                        color = NasMusicColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    serverUrl?.let { url ->
                        Text(
                            text = url,
                            color = NasMusicColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                } ?: run {
                    // 无 QR 时显示状态
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = status,
                        color = if (status.startsWith("等待")) NasMusicColors.Primary
                               else NasMusicColors.Warning,
                        fontSize = 14.sp
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
                        contentColor = Color.Black,
                        focusedContentColor = Color.Black,
                        focusRequester = closeFocusRequester,
                        requestFocusOnLaunch = true
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "关闭",
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
