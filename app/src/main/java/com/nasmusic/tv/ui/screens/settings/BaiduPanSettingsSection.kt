package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 百度网盘设置分区状态 */
data class BaiduPanSettingsState(
    val baiduEnabled: Boolean,
    val baiduLoggedIn: Boolean,
    val baiduConnectionState: NetworkMusicViewModel.BaiduConnectionState,
    val baiduMusicRootDirLocal: String,
    val baiduMvDirLocal: String?,
    val baiduIndexScanned: Int,
    val baiduIndexScanning: Boolean,
    val baiduApicExtracting: Boolean,
    val baiduApicExtracted: Int,
    val baiduApicTotal: Int,
)

/** 百度网盘设置分区动作（目录选择/授权对话框由宿主持有） */
data class BaiduPanSettingsActions(
    val onToggleBaiduEnabled: ((Boolean) -> Unit)?,
    val onStartBaiduDeviceCode: (() -> Unit)?,
    val onLogoutBaidu: (() -> Unit)?,
    val onRebuildBaiduIndex: (() -> Unit)?,
)

/** 百度网盘设置分区对话框触发回调 */
data class BaiduPanDialogActions(
    val onShowBaiduAuthDialog: () -> Unit,
    val onShowMusicRootDialog: () -> Unit,
    val onShowMvDirDialog: () -> Unit,
)

/** 网盘设置分区（原 SettingsScreen NETDISK 分支，逻辑逐行搬迁） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BaiduPanSettingsSection(
    state: BaiduPanSettingsState,
    actions: BaiduPanSettingsActions,
    dialogs: BaiduPanDialogActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_netdisk))

        // ── 百度网盘（已支持）分组 ──
        SubSectionTitle(stringResource(R.string.settings_netdisk_group_baidu))
        SettingSwitch(label = stringResource(R.string.settings_netdisk_enable), description = stringResource(R.string.settings_netdisk_enable_desc), checked = state.baiduEnabled, onClick = { actions.onToggleBaiduEnabled?.invoke(!state.baiduEnabled) })

        // 授权失败时，在登录按钮上方持续显示失败原因（即使对话框关闭也能看到）
        val baiduFailedState = state.baiduConnectionState as? NetworkMusicViewModel.BaiduConnectionState.Failed
        if (baiduFailedState != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.netdisk_auth_failed),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = baiduFailedState.message,
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small()
                )
            }
        }

        // 已登录但音乐根目录不存在，提示用户重新设置
        val baiduDirMissing = state.baiduConnectionState is NetworkMusicViewModel.BaiduConnectionState.DirMissing
        if (baiduDirMissing) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.netdisk_dir_missing),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.netdisk_dir_missing_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.small()
                )
            }
        }

        if (actions.onStartBaiduDeviceCode != null) {
            Spacer(modifier = Modifier.height(16.dp))
            if (state.baiduLoggedIn) {
                SettingActionButton(
                    label = stringResource(R.string.settings_netdisk_logged_in),
                    description = stringResource(R.string.settings_netdisk_logout_desc),
                    onClick = { actions.onLogoutBaidu?.invoke() }
                )
            } else {
                SettingActionButton(
                    label = stringResource(R.string.settings_netdisk_login),
                    description = stringResource(R.string.settings_netdisk_login_desc),
                    onClick = {
                        dialogs.onShowBaiduAuthDialog()
                        actions.onStartBaiduDeviceCode?.invoke()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingActionButton(
            label = stringResource(R.string.settings_netdisk_music_root),
            description = state.baiduMusicRootDirLocal,
            onClick = { dialogs.onShowMusicRootDialog() }
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingActionButton(
            label = stringResource(R.string.settings_netdisk_mv_dir),
            description = state.baiduMvDirLocal?.takeIf { it.isNotBlank() } ?: stringResource(R.string.settings_netdisk_mv_dir_desc),
            onClick = { dialogs.onShowMvDirDialog() }
        )
        if (actions.onRebuildBaiduIndex != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                else stringResource(R.string.settings_netdisk_index_desc, state.baiduIndexScanned),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            // APIC 封面提取进度（放在已扫描歌曲数量下方、重建索引按钮上方）
            if (state.baiduApicExtracting || state.baiduApicTotal > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.baiduApicExtracting) {
                        "封面提取中… ${state.baiduApicExtracted}/${state.baiduApicTotal}"
                    } else {
                        "封面提取完成：${state.baiduApicTotal} 首"
                    },
                    color = if (state.baiduApicExtracting) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                if (state.baiduApicExtracting && state.baiduApicTotal > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 16.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(NasMusicColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((state.baiduApicExtracted.toFloat() / state.baiduApicTotal).coerceIn(0f, 1f))
                                .height(4.dp)
                                .background(NasMusicColors.Primary, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_index_rebuild),
                description = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                               else stringResource(R.string.settings_netdisk_index_rebuild_desc),
                onClick = { if (!state.baiduIndexScanning) actions.onRebuildBaiduIndex?.invoke() }
            )
        }

        // ── 其他网盘（占位）分组 ──
        Spacer(modifier = Modifier.height(24.dp))
        SubSectionTitle(stringResource(R.string.settings_netdisk_group_others))
        com.nasmusic.tv.data.model.CloudDriveType.PLACEHOLDER.forEach { type ->
            PlaceholderRow(name = type.displayName)
        }
    }
}
