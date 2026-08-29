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
import androidx.compose.ui.platform.LocalContext
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
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.util.LinkUtils
import com.nasmusic.tv.util.QrCodeGenerator

/**
 * �ٶ������豸����Ȩ�Ի���
 *
 * �û����"��¼"�󵯳�����ʾ�豸�롢��֤�������ά�루���� [QrCodeGenerator]��
 * �� [com.nasmusic.tv.ui.screens.BackupTransferDialog] ͬ����ֻ�ɨ��/�����Ӳ�����
 * �豸�������Ȩ���ڼ��Զ���ѯ��
 *
 * ״̬��ת�� [connectionState] ������
 * - [MainViewModel.BaiduConnectionState.LoggedIn] �� ��Ȩ�ɹ����Զ��ر�
 * - [MainViewModel.BaiduConnectionState.Failed] �� ʧ�ܣ��ܾ�/��ʱ/�쳣������ʾ������Զ��ر�
 * - [MainViewModel.BaiduConnectionState.Connecting] �� �ȴ��û�ɨ����Ȩ
 *
 * @param deviceCode �豸������null ��ʾ�����л���ʧ�ܣ�
 * @param connectionState ��ǰ����״̬
 * @param onCancel �û�ȡ����Ȩ�����÷�����ֹͣ��ѯ [MainViewModel.cancelBaiduDeviceCode]��
 * @param onDismiss �رնԻ���
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BaiduAuthDialog(
    deviceCode: BaiduOAuthClient.DeviceCodeResult?,
    connectionState: MainViewModel.BaiduConnectionState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // ��ά�����ݣ������ȶ�����֤ҳ��verificationUrl����ɨ���򿪱�׼��֤ҳ�ֶ������豸�롣
    // ��ʹ�� qrcode_url����һ���� token ���ӣ��� .../device/qrcode/<token>��ɨ��󾭳��򲻿���
    val qrContent = remember(deviceCode) { deviceCode?.verificationUrl }
    LaunchedEffect(qrContent) {
        qrBitmap = qrContent?.let { QrCodeGenerator.generateQrBitmap(it, 360) }
    }

    // ��Ȩ�ɹ���ʧ�� �� ����չʾ���Զ��ر�
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
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.netdisk_auth_subtitle),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                when {
                    // �豸��������
                    deviceCode == null && connectionState is MainViewModel.BaiduConnectionState.Connecting -> {
                        Spacer(modifier = Modifier.height(36.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_fetching),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.button()
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                    }
                    // �ѻ�ȡ�豸�룺��ʾ�ֲ�ָ�� + ��ά��
                    deviceCode != null -> {
                        // �����̣��ֲ�˵�����ֻ��ֶ�����ַ�����豸�룬��ɿ�����Ȩ��ʽ��
                        Text(
                            text = stringResource(R.string.netdisk_auth_step1),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // ��֤ URL���ɵ��ֱ�Ӵ���������ֻ��˱�ݲ�����TV ���������������Ӧ��
                        FocusableSurface(
                            onClick = { LinkUtils.openInBrowser(context, deviceCode.verificationUrl) },
                            shape = RoundedCornerShape(6.dp),
                            focusedScale = 1.05f,
                            animationDurationMs = 150,
                            containerColor = NasMusicColors.Primary.copy(alpha = 0.08f),
                            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                            contentColor = NasMusicColors.Primary,
                            focusedContentColor = NasMusicColors.TextPrimary
                        ) {
                            Text(
                                text = deviceCode.verificationUrl,
                                color = NasMusicColors.Primary,
                                fontSize = FontSize.body(),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_step2),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body(),
                            textAlign = TextAlign.Center
                        )
                        // �豸�� + ���ư�ť
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = deviceCode.userCode,
                                color = NasMusicColors.Primary,
                                fontSize = FontSize.display(),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            FocusableSurface(
                                onClick = { LinkUtils.copyToClipboard(context, "�ٶ������豸��", deviceCode.userCode) },
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1.08f,
                                animationDurationMs = 120,
                                containerColor = NasMusicColors.Primary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContentColor = NasMusicColors.TextPrimary
                            ) {
                                Text(
                                    text = "����",
                                    fontSize = FontSize.body(),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))

                        // ��������ά�루ɨ������� App ����/���粻���ã��ʲ���Ϊ�����̣�
                        Text(
                            text = stringResource(R.string.netdisk_auth_qr_alt),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
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
                            fontSize = FontSize.body()
                        )
                    }
                    // ����ʧ��
                    else -> {
                        Spacer(modifier = Modifier.height(36.dp))
                        Text(
                            text = stringResource(R.string.netdisk_auth_fetch_failed),
                            color = NasMusicColors.Warning,
                            fontSize = FontSize.button()
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
                        focusedContentColor = NasMusicColors.TextPrimary
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(text = stringResource(R.string.common_cancel), color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
                        }
                    }
                }
            }
        }
    }
}