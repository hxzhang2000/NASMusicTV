package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.DownloadStats
import com.nasmusic.tv.backend.export.ExportState
import com.nasmusic.tv.backend.export.ExportError
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 下载设置分区状态 */
data class DownloadSettingsState(
    val downloadStats: DownloadStats,
    val downloadEnabled: Boolean,
    val autoDownloadOnPlay: Boolean,
    val autoDownloadLimit: Int,
    val downloadLocation: String,
    val exportState: ExportState,
)

/** 下载设置分区动作 */
data class DownloadSettingsActions(
    val onToggleDownloadEnabled: ((Boolean) -> Unit)?,
    val onToggleAutoDownloadOnPlay: ((Boolean) -> Unit)?,
    val onChangeAutoDownloadLimit: ((Int) -> Unit)?,
    val onChangeDownloadLocation: ((String) -> Unit)?,
    val onClearAllDownloads: (() -> Unit)?,
    val onExportToDevice: (() -> Unit)?,
    val onCancelExport: (() -> Unit)?,
    val onResetExportState: (() -> Unit)?,
)

/**
 * 下载设置分区（原 SettingsScreen DOWNLOAD 分支，逻辑逐行搬迁）。
 * 清空确认弹窗由 [onClearAllDownloadsRequested] 回调上抛给宿主（弹窗保持单例）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DownloadSettingsSection(
    state: DownloadSettingsState,
    actions: DownloadSettingsActions,
    onClearAllDownloadsRequested: () -> Unit
) {
    Column {
        SectionTitle(stringResource(R.string.settings_download))
        // 下载统计信息
        SubSectionTitle(stringResource(R.string.settings_download_stats))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            val stats = state.downloadStats
            SettingsInfoRow(stringResource(R.string.settings_download_stats_songs), stats.songCount.toString())
            SettingsInfoRow(stringResource(R.string.settings_download_stats_lyrics), stats.lyricsCount.toString())
            SettingsInfoRow(stringResource(R.string.settings_download_stats_covers), stats.coverCount.toString())
            SettingsInfoRow(
                stringResource(R.string.settings_download_stats_size),
                formatBytes(stats.totalBytes)
            )
        }
        SubSectionTitle(stringResource(R.string.settings_download_basic))
        SettingSwitch(
            label = stringResource(R.string.settings_download_enabled),
            description = stringResource(R.string.settings_download_enabled_desc),
            checked = state.downloadEnabled,
            onClick = { actions.onToggleDownloadEnabled?.invoke(!state.downloadEnabled) }
        )
        SettingSwitch(
            label = stringResource(R.string.settings_auto_download_on_play),
            description = stringResource(R.string.settings_auto_download_on_play_desc),
            checked = state.autoDownloadOnPlay,
            enabled = state.downloadEnabled,
            onClick = { actions.onToggleAutoDownloadOnPlay?.invoke(!state.autoDownloadOnPlay) }
        )
        // 自动下载数量上限（手动下载不受限）
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                text = stringResource(R.string.settings_auto_download_limit, state.autoDownloadLimit),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button()
            )
            Text(
                text = stringResource(R.string.settings_auto_download_limit_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AdjustButton("-", onClick = {
                    actions.onChangeAutoDownloadLimit?.invoke((state.autoDownloadLimit - 10).coerceAtLeast(1))
                })
                Text(text = state.autoDownloadLimit.toString(), color = NasMusicColors.TextPrimary, fontSize = FontSize.title())
                AdjustButton("+", onClick = {
                    actions.onChangeAutoDownloadLimit?.invoke((state.autoDownloadLimit + 10).coerceAtMost(5000))
                })
            }
        }
        // 下载位置
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_download_location))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                text = if (state.downloadLocation == "CUSTOM") stringResource(R.string.settings_download_location_custom) else stringResource(R.string.settings_download_location_internal),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button()
            )
            Text(
                text = stringResource(R.string.settings_download_location_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AdjustButton("内", onClick = { actions.onChangeDownloadLocation?.invoke("INTERNAL") })
                AdjustButton("外", onClick = { actions.onChangeDownloadLocation?.invoke("CUSTOM") })
            }
        }
        // P1-17: 清空所有下载
        Spacer(modifier = Modifier.height(12.dp))
        SettingActionButton(
            label = "清空所有下载",
            description = "删除所有已下载的歌曲文件，此操作不可撤销",
            onClick = onClearAllDownloadsRequested
        )
        // 导出到外接设备（§8.8.9）
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_download_export))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                text = stringResource(R.string.settings_download_export_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body()
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (val exportState = state.exportState) {
                is ExportState.Running -> {
                    // 导出进行中：进度条 + 取消按钮
                    Text(
                        text = stringResource(R.string.status_export_progress, exportState.done, exportState.total),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button()
                    )
                    if (exportState.current.isNotEmpty()) {
                        Text(
                            text = exportState.current,
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 自定义进度条（TV Material3 无 LinearProgressIndicator）
                    val progress = if (exportState.total > 0) exportState.done.toFloat() / exportState.total else 0f
                    Box(
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(NasMusicColors.TextSecondary.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxHeight()
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .background(NasMusicColors.Primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AdjustButton("X", onClick = { actions.onCancelExport?.invoke() })
                }
                is ExportState.Completed -> {
                    Text(
                        text = stringResource(R.string.status_export_done, exportState.done, exportState.skipped),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AdjustButton(stringResource(R.string.common_confirm), onClick = { actions.onResetExportState?.invoke() })
                }
                is ExportState.Failed -> {
                    val errMsg = when (exportState.reason) {
                        ExportError.NO_DEVICE -> stringResource(R.string.dialog_export_no_device)
                        ExportError.NO_PERMISSION -> stringResource(R.string.dialog_export_no_device)
                        ExportError.NO_SPACE -> stringResource(R.string.msg_export_no_space)
                        ExportError.NOTHING_TO_EXPORT -> stringResource(R.string.msg_export_nothing)
                        else -> stringResource(R.string.status_export_cancelled)
                    }
                    Text(text = errMsg, color = androidx.compose.ui.graphics.Color.Red, fontSize = FontSize.button())
                    Spacer(modifier = Modifier.height(8.dp))
                    AdjustButton(stringResource(R.string.common_confirm), onClick = { actions.onResetExportState?.invoke() })
                }
                is ExportState.Cancelled -> {
                    Text(
                        text = stringResource(R.string.status_export_cancelled),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.button()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AdjustButton(stringResource(R.string.common_confirm), onClick = { actions.onResetExportState?.invoke() })
                }
                else -> {
                    // Idle / Preparing → 显示导出按钮
                    SettingActionButton(
                        label = stringResource(R.string.settings_download_export),
                        description = "",
                        onClick = { actions.onExportToDevice?.invoke() }
                    )
                }
            }
        }
    }
}
