package com.nasmusic.tv.ui.screens.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.nasmusic.tv.net.PlaylistUploadServer
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.responsiveDialogSize
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.LinkUtils
import com.nasmusic.tv.util.NetworkUtils
import com.nasmusic.tv.util.QrCodeGenerator

/**
 * 歌单扫码上传弹窗（阶段5.5，替代 SAF 文件选择器）
 *
 * 打开时启动 [PlaylistUploadServer]，显示二维码供手机扫码。
 * 手机扫码后浏览器打开上传页，选择 m3u/txt/json 歌单文件上传，
 * 电视端回调 [onFileReceived] 走 PlaylistImporter.importBytes 导入链路。
 * 关闭弹窗时自动停止 server。
 *
 * @param onFileReceived 收到歌单文件的回调（NanoHTTPD 工作线程同步调用，
 *   由 ViewModel 桥接 suspend 导入），返回非空 = 成功消息，null = 失败。
 * @param onDismiss 关闭弹窗
 */
@Composable
fun PlaylistImportUploadDialog(
    onFileReceived: (fileName: String, bytes: ByteArray) -> String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(context.getString(R.string.playlist_upload_starting)) }
    val server = remember { PlaylistUploadServer(context, onFileReceived) }
    val closeFocusRequester = remember { FocusRequester() }

    // 启动/停止服务器
    DisposableEffect(Unit) {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            val url = "http://$ip:${PlaylistUploadServer.DEFAULT_PORT}/"
            serverUrl = url
            qrBitmap = QrCodeGenerator.generateQrBitmap(url, 360)
            AppLog.i("PlaylistImportUploadDialog", "starting server at $url")
            val started = server.start()
            status = if (started) {
                context.getString(R.string.playlist_upload_waiting)
            } else {
                context.getString(R.string.playlist_upload_start_failed)
            }
        } else {
            AppLog.w("PlaylistImportUploadDialog", "no IP address")
            status = context.getString(R.string.playlist_upload_no_ip)
        }
        onDispose {
            server.stop()
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
        // BACK 键必须在 Dialog 内部注册（Dialog 独立窗口吞掉系统 BACK 事件）
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
                    .then(responsiveDialogSize(560.dp))
                    .heightIn(max = maxHeight - 32.dp)
                    .verticalScroll(rememberScrollState())
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.playlist_upload_title),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(8.dp))

                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.playlist_upload_qr_desc),
                        modifier = Modifier.size(280.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.playlist_upload_scan_hint),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.button()
                    )
                    Text(
                        text = stringResource(R.string.playlist_upload_formats_hint),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.body()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    serverUrl?.let { url ->
                        // URL 可点击：直接打开浏览器访问上传页（手机端便捷操作）
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
                    // 无 QR 时显示状态
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = status,
                        color = NasMusicColors.Warning,
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