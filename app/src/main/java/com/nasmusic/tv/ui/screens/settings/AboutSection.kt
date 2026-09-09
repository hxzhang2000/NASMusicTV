package com.nasmusic.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicVersion
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 关于分区状态 */
data class AboutSettingsState(
    val isConnected: Boolean,
    val serverDisplayName: String,
    val backendApiVersion: String,
    val apiVersions: List<VersionInfo>,
)

/** 关于分区（原 SettingsScreen ABOUT 分支，逻辑逐行搬迁） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AboutSettingsSection(state: AboutSettingsState) {
    androidx.compose.foundation.layout.Column {
        SectionTitle(stringResource(R.string.settings_about))
        AboutRow(label = stringResource(R.string.settings_app_name), value = stringResource(R.string.app_name))
        AboutRow(label = stringResource(R.string.about_version), value = NasMusicVersion.DISPLAY)
        AboutRow(label = stringResource(R.string.settings_build_type), value = NasMusicVersion.BUILD_TYPE)
        AboutRow(label = stringResource(R.string.about_license), value = stringResource(R.string.about_license_value))
        AboutRow(label = stringResource(R.string.settings_supported_backends), value = stringResource(R.string.settings_supported_backends_value))
        // 当前连接的后端信息
        if (state.isConnected) {
            AboutRow(label = stringResource(R.string.settings_backend_type), value = state.serverDisplayName)
            AboutRow(label = stringResource(R.string.settings_api_version), value = state.backendApiVersion)
        } else {
            AboutRow(label = stringResource(R.string.settings_backend_type), value = stringResource(R.string.settings_not_connected))
        }
        // 全量 API 版本号（后端 + 外部服务）
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_api_versions),
            color = NasMusicColors.Primary,
            fontSize = FontSize.button(),
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
        )
        if (state.apiVersions.isEmpty()) {
            AboutRow(label = stringResource(R.string.settings_api_versions_empty), value = "")
        } else {
            state.apiVersions.forEach { v ->
                val (labelText, valueText) = formatVersionInfo(v)
                AboutRow(label = labelText, value = valueText)
            }
        }
        AboutRow(label = stringResource(R.string.settings_network_music_info), value = stringResource(R.string.settings_network_music_value))
        AboutRow(label = stringResource(R.string.settings_independent_music), value = stringResource(R.string.settings_independent_music_value))
        AboutRow(label = stringResource(R.string.settings_radio_info), value = stringResource(R.string.settings_radio_value))
        AboutRow(label = stringResource(R.string.settings_baidu_netdisk_info), value = stringResource(R.string.settings_baidu_netdisk_value))
        AboutRow(label = stringResource(R.string.settings_lyrics_info), value = stringResource(R.string.settings_lyrics_value))
        // 版权说明
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.settings_copyright),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.settings_copyright_text),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.small(),
            lineHeight = FontSize.small() * 1.5
        )
        // GitHub 项目链接
        Spacer(modifier = Modifier.height(20.dp))
        val context = LocalContext.current
        Text(
            text = stringResource(R.string.settings_star_prompt),
            color = NasMusicColors.Primary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "https://github.com/hxzhang2000/NasMusicTV",
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.small(),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hxzhang2000/NasMusicTV"))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                .padding(vertical = 4.dp)
        )
    }
}
