package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings

/**
 * 「本地音乐」设置分区（§5.6 权限瘦身方案）。
 *
 * 照 [PhotoWallSettingsSection] 的 state / actions 分离范式：
 * [LocalMusicSettingsState] 只装数据（落盘字段从 `state.settings` 读），
 * [LocalMusicSettingsActions] 只装回调。
 *
 * ⛔ 开关是音乐权限的**唯一触发点**（§5.5）：打开未授权时由
 * `MainViewModel.setLocalMusicEnabled` 拉起系统对话框，被拒则回弹为关 ——
 * 本分区不做任何权限判断（分区只管渲染与转发）。
 * 「重新扫描」复用 `MainViewModel.refreshLocalMusic`（复活的原死代码），
 * 开关关闭时置灰（enabled=false，§5.6）。
 */
data class LocalMusicSettingsState(
    val settings: AppSettings = AppSettings(),
    /** Room 已入库曲目数（含已下载曲；开关状态不影响此值，D2） */
    val libraryCount: Int = 0,
)

/** 本地音乐分区动作 */
data class LocalMusicSettingsActions(
    val onSetEnabled: (Boolean) -> Unit = {},
    val onRescan: () -> Unit = {},
)

@Composable
internal fun LocalMusicSettingsSection(
    state: LocalMusicSettingsState,
    actions: LocalMusicSettingsActions,
) {
    val s = state.settings
    Column {
        SectionTitle(stringResource(R.string.settings_local_music))
        // ⛔ 总开关 = 音乐权限的唯一触发点（§5.5）：ViewModel 内部判
        //    「已授权直接开 / 未授权先申请、被拒回弹」，这里只转发目标值。
        SettingSwitch(
            label = stringResource(R.string.settings_local_music_source),
            description = stringResource(R.string.settings_local_music_source_desc),
            checked = s.localMusicEnabled,
            onClick = { actions.onSetEnabled(!s.localMusicEnabled) },
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            SettingsInfoRow(
                stringResource(R.string.settings_local_music_source),
                stringResource(R.string.settings_local_music_count, state.libraryCount),
            )
        }
        // 开关关闭时置灰（§5.6）：关着开关扫描没有意义 —— 结果也不会出现在曲库
        SettingActionButton(
            label = stringResource(R.string.settings_local_music_rescan),
            description = stringResource(R.string.settings_local_music_rescan_desc),
            onClick = actions.onRescan,
            enabled = s.localMusicEnabled,
        )
    }
}
