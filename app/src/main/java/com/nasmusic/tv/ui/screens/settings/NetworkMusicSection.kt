package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 网络音乐设置分区状态（Meting/Jamendo/MV/歌词端点/天气 Key/网络测试状态） */
data class NetworkMusicSettingsState(
    val metingApiBaseUrl: String,
    val jamendoClientId: String,
    val mvApiBaseUrl: String,
    val lyricsKugouBaseUrl: String,
    val lyricsNeteaseBaseUrl: String,
    val weatherApiKey: String,
    val isNetworkTesting: Boolean,
    val networkTestStatus: String,
)

/** 网络音乐设置分区动作（URL 编辑对话框由宿主持有） */
data class NetworkMusicSettingsActions(
    val onChangeMetingApiBaseUrl: ((String) -> Unit)?,
    val onChangeJamendoClientId: ((String) -> Unit)?,
    val onChangeMvApiBaseUrl: ((String) -> Unit)?,
    val onChangeLyricsKugouBaseUrl: ((String) -> Unit)?,
    val onChangeLyricsNeteaseBaseUrl: ((String) -> Unit)?,
    val onChangeWeatherApiKey: ((String) -> Unit)?,
    val onRunNetworkTest: () -> Unit,
)

/** URL 编辑对话框触发回调（对话框本体保持在 SettingsScreen，避免状态重复） */
data class NetworkMusicDialogActions(
    val onShowMetingUrlDialog: () -> Unit,
    val onShowJamendoClientIdDialog: () -> Unit,
    val onShowMvUrlDialog: () -> Unit,
    val onShowLyricsKugouDialog: () -> Unit,
    val onShowLyricsNeteaseDialog: () -> Unit,
    val onShowWeatherApiKeyDialog: () -> Unit,
)

/** 网络音乐设置分区（原 SettingsScreen NETWORK 分支，逻辑逐行搬迁） */
@Composable
internal fun NetworkMusicSection(
    state: NetworkMusicSettingsState,
    actions: NetworkMusicSettingsActions,
    dialogs: NetworkMusicDialogActions
) {
    androidx.compose.foundation.layout.Column {
        SectionTitle(stringResource(R.string.settings_network))
        Text(
            text = stringResource(R.string.settings_network_test_desc),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        NetworkTestCard(state = state, onRun = actions.onRunNetworkTest)

        // --- 网络搜索：Meting-API 端点配置 ---
        if (actions.onChangeMetingApiBaseUrl != null) {
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_network_search),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_meting_api_url_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_meting_preset_endpoints),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            )
            val currentNormalized = state.metingApiBaseUrl.trim().trimEnd('/')
            com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS.forEach { (name, url) ->
                val selected = currentNormalized == url.trimEnd('/')
                PresetEndpointRow(
                    name = name,
                    url = url,
                    selected = selected,
                    onClick = { actions.onChangeMetingApiBaseUrl(url) }
                )
            }
            // 自定义端点选项
            val isPreset = com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS
                .any { it.second.trimEnd('/') == currentNormalized }
            val customSelected = !isPreset
            PresetEndpointRow(
                name = stringResource(R.string.settings_meting_custom_endpoint),
                url = if (customSelected) state.metingApiBaseUrl else stringResource(R.string.settings_meting_custom_endpoint_desc),
                selected = customSelected,
                trailingLabel = stringResource(R.string.settings_meting_api_url_edit),
                onClick = { dialogs.onShowMetingUrlDialog() }
            )
        }

        // --- 网络搜索：Jamendo Client ID（CC 独立音乐）---
        if (actions.onChangeJamendoClientId != null) {
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_jamendo_client_id),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_jamendo_client_id_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            // 当前值状态卡 + 修改按钮
            com.nasmusic.tv.ui.components.FocusableSurface(
                onClick = { dialogs.onShowJamendoClientIdDialog() },
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                animationDurationMs = 250,
                containerColor = NasMusicColors.Surface,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.98f
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = if (state.jamendoClientId.isBlank())
                            stringResource(R.string.settings_not_configured_hint)
                        else state.jamendoClientId.take(24) + if (state.jamendoClientId.length > 24) "…" else "",
                        color = if (state.jamendoClientId.isBlank()) NasMusicColors.TextSecondary
                                else NasMusicColors.Primary,
                        fontSize = FontSize.button(),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.settings_tap_to_edit),
                        color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                        fontSize = FontSize.small(),
                        modifier = androidx.compose.ui.Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // --- 网络搜索：MTV 视频端点配置 ---
        if (actions.onChangeMvApiBaseUrl != null) {
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_mv_api_url),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_mv_api_url_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_mv_preset_endpoints),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            )
            val mvCurrentNormalized = state.mvApiBaseUrl.trim().trimEnd('/')
            com.nasmusic.tv.backend.network.mv.BilibiliMvService.PRESET_ENDPOINTS.forEach { (name, url) ->
                val selected = mvCurrentNormalized == url.trimEnd('/')
                PresetEndpointRow(
                    name = name,
                    url = url,
                    selected = selected,
                    onClick = { actions.onChangeMvApiBaseUrl(url) }
                )
            }
            // 自定义端点选项
            val mvIsPreset = com.nasmusic.tv.backend.network.mv.BilibiliMvService.PRESET_ENDPOINTS
                .any { it.second.trimEnd('/') == mvCurrentNormalized }
            val mvCustomSelected = !mvIsPreset
            PresetEndpointRow(
                name = stringResource(R.string.settings_mv_custom_endpoint),
                url = if (mvCustomSelected) state.mvApiBaseUrl else stringResource(R.string.settings_mv_custom_endpoint_desc),
                selected = mvCustomSelected,
                trailingLabel = stringResource(R.string.settings_mv_api_url_edit),
                onClick = { dialogs.onShowMvUrlDialog() }
            )
        }

        // --- 网络歌词端点配置 ---
        if (actions.onChangeLyricsKugouBaseUrl != null || actions.onChangeLyricsNeteaseBaseUrl != null) {
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_lyrics_endpoint),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            // 酷狗端点
            Text(
                text = stringResource(R.string.settings_lyrics_kugou_url),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            )
            com.nasmusic.tv.ui.components.FocusableSurface(
                onClick = { dialogs.onShowLyricsKugouDialog() },
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                animationDurationMs = 250,
                containerColor = NasMusicColors.Surface,
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.98f,
                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        Text(
                            text = state.lyricsKugouBaseUrl.ifBlank { stringResource(R.string.settings_lyrics_url_reset) },
                            color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                            fontSize = FontSize.body()
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_lyrics_url_edit),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button()
                    )
                }
            }
            // 网易云端点
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_lyrics_netease_url),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
            )
            com.nasmusic.tv.ui.components.FocusableSurface(
                onClick = { dialogs.onShowLyricsNeteaseDialog() },
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                animationDurationMs = 250,
                containerColor = NasMusicColors.Surface,
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.98f,
                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        Text(
                            text = state.lyricsNeteaseBaseUrl.ifBlank { stringResource(R.string.settings_lyrics_url_reset) },
                            color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                            fontSize = FontSize.body()
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_lyrics_url_edit),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button()
                    )
                }
            }
        }

        // --- 天气 API Key 配置 ---
        if (actions.onChangeWeatherApiKey != null) {
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_weather_api_key),
                color = NasMusicColors.Primary,
                fontSize = FontSize.subtitle(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_weather_api_key_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.body(),
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
            com.nasmusic.tv.ui.components.FocusableSurface(
                onClick = { dialogs.onShowWeatherApiKeyDialog() },
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                animationDurationMs = 250,
                containerColor = NasMusicColors.Surface,
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.98f,
                focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        Text(
                            text = if (state.weatherApiKey.isNotBlank()) "···${state.weatherApiKey.takeLast(6)}"
                                   else stringResource(R.string.common_not_set),
                            color = if (state.weatherApiKey.isNotBlank()) NasMusicColors.TextPrimary
                                    else NasMusicColors.TextSecondary,
                            fontSize = FontSize.button()
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_weather_api_key_edit),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.button()
                    )
                }
            }
        }
    }
}

/** 网络测试卡片（原 NETWORK 分支的网络测试 FocusableSurface，逻辑搬迁） */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkTestCard(state: NetworkMusicSettingsState, onRun: () -> Unit) {
    com.nasmusic.tv.ui.components.FocusableSurface(
        onClick = onRun,
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = NasMusicColors.Border,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        focusedScale = 1.03f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.SurfaceVariant,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.96f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.tv.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                contentDescription = null,
                tint = if (state.isNetworkTesting) NasMusicColors.TextSecondary else NasMusicColors.Primary,
                modifier = androidx.compose.ui.Modifier.size(20.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(12.dp))
            Text(
                text = if (state.isNetworkTesting) stringResource(R.string.settings_network_testing) else stringResource(R.string.settings_network_test),
                color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                fontSize = FontSize.button()
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.weight(1f))
            if (state.networkTestStatus.isNotBlank()) {
                val isNetSuccess = state.networkTestStatus.startsWith("success:")
                val netMessage = if (isNetSuccess) state.networkTestStatus.removePrefix("success:") else state.networkTestStatus.removePrefix("error:")
                Text(
                    text = if (isNetSuccess) "✓ $netMessage" else "✗ $netMessage",
                    color = if (isNetSuccess) NasMusicColors.Primary else NasMusicColors.Warning,
                    fontSize = FontSize.body()
                )
            }
        }
    }
}

/** 预设端点单选行（Meting/MV 端点列表共用，逻辑搬迁） */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun PresetEndpointRow(
    name: String,
    url: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingLabel: String? = null
) {
    com.nasmusic.tv.ui.components.FocusableSurface(
        onClick = onClick,
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        animationDurationMs = 250,
        containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else NasMusicColors.Surface,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.Primary.copy(alpha = 0.15f),
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.98f,
        focusBorderColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.5f) else NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    fontSize = FontSize.button()
                )
                Text(
                    text = url,
                    color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                    fontSize = FontSize.body(),
                    modifier = androidx.compose.ui.Modifier.padding(top = 2.dp)
                )
            }
            if (selected && trailingLabel == null) {
                Text(
                    text = "✓",
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.button()
                )
            }
            if (trailingLabel != null) {
                Text(
                    text = trailingLabel,
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.button()
                )
            }
        }
    }
}
