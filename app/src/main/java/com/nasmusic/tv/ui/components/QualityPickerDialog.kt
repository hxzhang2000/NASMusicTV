package com.nasmusic.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.QualityScope
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.ui.screens.settings.SettingActionButton
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 播放器音质选择面板（多码率方案 §5.1）。
 *
 * 5 档单选 + 范围（仅本次播放 / 全部歌曲）双选，TV 遥控方向键可达。
 *
 * - 「全部歌曲」→ 写全局默认档位 + 清直链缓存
 * - 「仅本次播放」→ 写单曲覆盖，仅当前曲生效
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun QualityPickerDialog(
    currentTier: Int,
    onConfirm: (tier: Int, scope: QualityScope) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(currentTier) { mutableIntStateOf(currentTier) }
    var scope by remember { mutableStateOf(QualityScope.ALL) }

    BackHandler { onDismiss() }

    Dialog(
        onDismissRequest = onDismiss,
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
                    .width(520.dp)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_quality_tier),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 5 档单选（遍历单一真相源，含 192）
                QualityTiers.all.forEach { tier ->
                    val label = stringResource(QualityTiers.labelResOf(tier))
                    val mark = if (selected == tier) "◉ " else "○ "
                    val desc = if (tier == QualityTiers.AUTO) {
                        stringResource(R.string.quality_tier_auto)
                    } else {
                        QualityTiers.descriptionOf(tier)
                    }
                    SettingActionButton(
                        label = "$mark$label  $desc",
                        description = "",
                        onClick = { selected = tier }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "─────────",
                    color = NasMusicColors.Border,
                    fontSize = FontSize.caption()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 生效范围
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingActionButton(
                        label = (if (scope == QualityScope.THIS_SONG) "◉ " else "○ ") +
                            stringResource(R.string.quality_scope_this_play),
                        description = "",
                        onClick = { scope = QualityScope.THIS_SONG }
                    )
                    SettingActionButton(
                        label = (if (scope == QualityScope.ALL) "◉ " else "○ ") +
                            stringResource(R.string.quality_scope_all_songs),
                        description = "",
                        onClick = { scope = QualityScope.ALL }
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    SettingActionButton(
                        label = stringResource(R.string.common_cancel),
                        description = "",
                        onClick = onDismiss
                    )
                    SettingActionButton(
                        label = stringResource(R.string.common_confirm),
                        description = "",
                        onClick = { onConfirm(selected, scope) }
                    )
                }
            }
        }
    }
}
