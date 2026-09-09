package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 通用设置分区状态（语言/主题/动画/字号） */
data class GeneralSettingsState(
    val settings: AppSettings,
    val language: String,
    val fontAdjustment: Int,
)

/** 通用设置分区动作 */
data class GeneralSettingsActions(
    val onChangeLanguage: ((String) -> Unit)?,
    val onToggleDarkTheme: (Boolean) -> Unit,
    val onToggleAnimations: (Boolean) -> Unit,
    val onChangeFontAdjustment: (Int) -> Unit,
)

/** 通用设置分区（原 SettingsScreen GENERAL 分支，逻辑逐行搬迁；由 SettingsScreen 以 LazyColumn item 包装） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun GeneralSettingsSection(
    state: GeneralSettingsState,
    actions: GeneralSettingsActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_general))
        SubSectionTitle(stringResource(R.string.settings_language))
        Text(
            text = stringResource(R.string.settings_language_desc),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.small(),
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "system" to stringResource(R.string.settings_language_system),
                "zh" to stringResource(R.string.settings_language_zh),
                "en" to stringResource(R.string.settings_language_en)
            ).forEach { (value, label) ->
                val selected = state.language == value
                FocusableSurface(
                    onClick = { actions.onChangeLanguage?.invoke(value) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.SurfaceVariant,
                    focusedContentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    focusedScale = 1.05f
                ) {
                    Text(
                        text = label,
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.body(),
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingSwitch(label = stringResource(R.string.settings_dark_theme), description = stringResource(R.string.settings_dark_theme_desc), checked = state.settings.darkTheme, onClick = { actions.onToggleDarkTheme(!state.settings.darkTheme) })
        SettingSwitch(label = stringResource(R.string.settings_animations), description = stringResource(R.string.settings_animations_desc), checked = state.settings.animationsEnabled, onClick = { actions.onToggleAnimations(!state.settings.animationsEnabled) })
        SubSectionTitle(stringResource(R.string.settings_font_size))
        // 字体字号调整（在当前Theme档位基础上增减）
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AdjustButton("-", onClick = { actions.onChangeFontAdjustment((state.fontAdjustment - 1).coerceAtLeast(-8)) })
            Text(
                text = if (state.fontAdjustment == 0) stringResource(R.string.settings_font_standard) else if (state.fontAdjustment > 0) "+${state.fontAdjustment}" else "${state.fontAdjustment}",
                color = NasMusicColors.Primary,
                fontSize = FontSize.title(),
                modifier = Modifier.widthIn(min = 100.dp).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center
            )
            AdjustButton("+", onClick = { actions.onChangeFontAdjustment((state.fontAdjustment + 1).coerceAtMost(8)) })
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}
