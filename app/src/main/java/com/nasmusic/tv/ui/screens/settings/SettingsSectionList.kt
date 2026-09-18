package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 竖屏设置页 **一级**：分区列表（v2.36.0，方案 §4.6）。
 *
 * 9 个分区各一行（图标 + 名称 + `›`），点击进入二级页。
 * 只读 `compose.foundation` + `androidx.tv.material3`（项目无 `androidx.compose.material3`
 * 编译类路径，见方案 C1）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsSectionList(
    onPick: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier.size(32.dp).background(
                        NasMusicColors.Primary,
                        shape = RoundedCornerShape(8.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = NasMusicColors.TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.nav_settings),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.title()
                )
            }
        }
        items(SettingsSection.entries.toList()) { section ->
            FocusableSurface(
                onClick = { onPick(section) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                animationDurationMs = 200,
                containerColor = NasMusicColors.Surface,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.22f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.Primary,
                pressedScale = 0.98f,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = section.icon,
                        contentDescription = null,
                        tint = NasMusicColors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(section.titleRes),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.button(),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "\u203A",
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.subtitle(),
                    )
                }
            }
        }
    }
}

/**
 * 竖屏设置页 **二级**顶部返回头（方案 §4.6）。
 *
 * ⚠️ 这里只做「回到设置列表」的 UI 动作；真正的 BACK 键语义由 `AppRoot` 的
 * `navVM.settingsSection != null` 分支消费（方案 §6.2）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsSectionBackHeader(
    section: SettingsSection,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FocusableSurface(
            onClick = onBack,
            shape = RoundedCornerShape(8.dp),
            containerColor = NasMusicColors.Surface,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            contentColor = NasMusicColors.TextPrimary,
            focusedContentColor = NasMusicColors.Primary,
            focusedScale = 1.08f,
            animationDurationMs = 200,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "\u2190",
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    text = stringResource(R.string.settings_section_back),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(section.titleRes),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.subtitle(),
            fontWeight = FontWeight.SemiBold,
        )
    }
}
