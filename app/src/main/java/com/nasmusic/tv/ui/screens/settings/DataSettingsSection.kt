package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.BackupMessage
import com.nasmusic.tv.util.BackupFileUtils
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 数据管理分区状态 */
data class DataSettingsState(
    val backupFiles: List<BackupFileUtils.BackupFile>,
    val backupMessage: BackupMessage?,
)

/** 数据管理分区动作（删除确认弹窗由宿主持有，经 onDeleteRequested 上抛） */
data class DataSettingsActions(
    val onExportBackup: (() -> Unit)?,
    val onImportBackup: ((android.net.Uri) -> Unit)?,
    val onScanTransferBackup: (() -> Unit)?,
    /** F2-1：打开播放统计面板 */
    val onOpenPlayStats: (() -> Unit)? = null,
)

/** 数据管理分区（原 SettingsScreen DATA 分支，逻辑逐行搬迁） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DataSettingsSection(
    state: DataSettingsState,
    actions: DataSettingsActions,
    onDeleteRequested: (BackupFileUtils.BackupFile) -> Unit
) {
    androidx.compose.foundation.layout.Column {
        SectionTitle(stringResource(R.string.settings_data))
        Text(
            text = stringResource(R.string.settings_data_desc),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        // 导出备份
        if (actions.onExportBackup != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_export_backup),
                description = stringResource(R.string.settings_export_backup_desc),
                onClick = { actions.onExportBackup?.invoke() }
            )
        }
        // 扫码传输（手机下载/上传备份）
        if (actions.onScanTransferBackup != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_scan_transfer),
                description = stringResource(R.string.settings_scan_transfer_desc),
                onClick = { actions.onScanTransferBackup?.invoke() }
            )
        }
        // F2-1：播放统计面板入口
        if (actions.onOpenPlayStats != null) {
            SettingActionButton(
                label = stringResource(R.string.pstats_title),
                description = stringResource(R.string.pstats_entry_desc),
                onClick = { actions.onOpenPlayStats?.invoke() }
            )
        }
        // 备份文件列表
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_backup_list),
            color = NasMusicColors.Primary,
            fontSize = FontSize.subtitle(),
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
        )
        if (state.backupFiles.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_backup_empty),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(start = 4.dp)
            )
        } else {
            state.backupFiles.forEach { file ->
                BackupFileRow(
                    file = file,
                    onRestore = { actions.onImportBackup?.invoke(file.uri) },
                    onDelete = { onDeleteRequested(file) }
                )
            }
        }
        // 从备份列表恢复（电视无系统文件选择器，恢复入口即上方备份文件列表）
        // 备份结果消息
        if (state.backupMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = state.backupMessage.text,
                color = if (state.backupMessage.isError)
                    NasMusicColors.Warning else NasMusicColors.Primary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
