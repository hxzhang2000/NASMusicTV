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
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 服务器设置分区状态 */
data class ServerSettingsState(
    val isConnected: Boolean,
    val serverDisplayName: String,
)

/** 服务器设置分区动作 */
data class ServerSettingsActions(
    val onNavigateToServerConnect: (() -> Unit)?,
    val onDisconnect: (() -> Unit)?,
)

/** 服务器设置分区（原 SettingsScreen SERVER 分支，逻辑逐行搬迁） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ServerSettingsSection(
    state: ServerSettingsState,
    actions: ServerSettingsActions
) {
    androidx.compose.foundation.layout.Column {
        SectionTitle(stringResource(R.string.nav_server))
        Spacer(modifier = Modifier.height(12.dp))
        val statusText = if (state.isConnected)
            stringResource(R.string.server_connected, state.serverDisplayName)
        else
            stringResource(R.string.server_connect_desc)
        Text(statusText, color = NasMusicColors.TextPrimary, fontSize = FontSize.button(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        Spacer(modifier = Modifier.height(12.dp))
        SettingActionButton(
            label = stringResource(R.string.server_config_title),
            description = if (state.isConnected) stringResource(R.string.server_connected, state.serverDisplayName)
                else stringResource(R.string.server_connect_desc),
            onClick = { actions.onNavigateToServerConnect?.invoke() }
        )
        if (state.isConnected && actions.onDisconnect != null) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingActionButton(
                label = stringResource(R.string.server_disconnect),
                description = stringResource(R.string.settings_disconnect_desc),
                onClick = actions.onDisconnect
            )
        }
    }
}
