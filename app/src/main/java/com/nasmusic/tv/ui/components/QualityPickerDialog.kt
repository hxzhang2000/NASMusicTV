package com.nasmusic.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 播放器音质选择面板（多码率方案 §5.1）。
 *
 * 5 档单选 + 范围（仅本次播放 / 全部歌曲）双选，TV 遥控方向键可达。
 *
 * - 「全部歌曲」→ 写全局默认档位 + 清直链缓存
 * - 「仅本次播放」→ 写单曲覆盖，仅当前曲生效
 *
 * 手机端适配（v2.35.0 修复）：面板宽度 `widthIn(max)` + 高度 `heightIn(max = 视口-32dp)`
 * + `verticalScroll`。原实现固定 `.width(520.dp)` 且无高度上限/滚动，
 * 手机竖屏下 5 档 + 范围行 + 底部按钮会超出屏幕（表现为"界面被截断、下面还有内容看不到"）。
 *
 * 交互：点行 = 选中；底部「确定」= 应用。行内右侧显示状态（已选/选择），
 * 不复用设置页 `SettingActionButton`（那个组件右侧硬编码「确定」，会让行看起来像确认按钮）。
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            // 同 DownloadQualityPickerDialog：宽度取「520dp 与 视口 92%」的较小值。
            // 不能组合 widthIn + fillMaxWidth（在全屏 BoxWithConstraints 下宽度解析异常，
            // 会把底部按钮挤出可视区）。
            val panelWidth = minOf(520.dp, maxWidth * 0.92f)
            val maxPanelHeight = maxHeight - 32.dp
            Column(
                modifier = Modifier
                    .width(panelWidth)
                    .heightIn(max = maxPanelHeight)
                    .verticalScroll(rememberScrollState())
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_quality_tier),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 5 档单选（遍历单一真相源，含 192）
                QualityTiers.all.forEach { tier ->
                    QualityOptionRow(
                        label = stringResource(QualityTiers.labelResOf(tier)),
                        description = if (tier == QualityTiers.AUTO) {
                            stringResource(R.string.quality_tier_auto)
                        } else {
                            QualityTiers.descriptionOf(tier)
                        },
                        selected = selected == tier,
                        onClick = { selected = tier }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "─────────",
                    color = NasMusicColors.Border,
                    fontSize = FontSize.caption()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.quality_scope_label),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body()
                )
                Spacer(modifier = Modifier.height(6.dp))

                // 生效范围（两个选项纵向排布，手机竖屏下不挤）
                QualityOptionRow(
                    label = stringResource(R.string.quality_scope_this_play),
                    description = stringResource(R.string.quality_scope_this_play_desc),
                    selected = scope == QualityScope.THIS_SONG,
                    onClick = { scope = QualityScope.THIS_SONG }
                )
                Spacer(modifier = Modifier.height(8.dp))
                QualityOptionRow(
                    label = stringResource(R.string.quality_scope_all_songs),
                    description = stringResource(R.string.quality_scope_all_songs_desc),
                    selected = scope == QualityScope.ALL,
                    onClick = { scope = QualityScope.ALL }
                )

                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    QualityDialogButton(
                        label = stringResource(R.string.common_cancel),
                        primary = false,
                        onClick = onDismiss
                    )
                    QualityDialogButton(
                        label = stringResource(R.string.common_confirm),
                        primary = true,
                        onClick = { onConfirm(selected, scope) }
                    )
                }
            }
        }
    }
}

/**
 * 通用选项行：左侧标签 + 右侧状态文案（已选 / 选择）。
 *
 * 与 `DownloadQualityPickerDialog` 内的同名行组件语义一致，
 * 区别是这里支持 description 副标题。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QualityOptionRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.02f,
        animationDurationMs = 200,
        containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f)
                         else NasMusicColors.SurfaceVariant,
        contentColor = NasMusicColors.TextPrimary,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.28f),
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.98f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (if (selected) "◉ " else "○ ") + label,
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button()
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.caption()
                    )
                }
            }
            Text(
                text = if (selected) stringResource(R.string.quality_option_selected)
                       else stringResource(R.string.quality_option_select),
                color = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                fontSize = FontSize.body()
            )
        }
    }
}

/** 对话框底部按钮（与下载面板保持一致） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun QualityDialogButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // 禁用态：降透明度 + 不响应点击。
    // 用于下载面板"所有档位均已下载"时，仍保留「下载」按钮的可见性
    // （原实现直接不渲染该按钮，用户只看到「取消」，误以为界面坏了）。
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .widthIn(min = 96.dp)
            .alpha(if (enabled) 1f else 0.35f),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.04f,
        animationDurationMs = 200,
        containerColor = if (primary) NasMusicColors.Primary.copy(alpha = 0.85f)
                         else NasMusicColors.SurfaceVariant,
        contentColor = if (primary) Color.Black else NasMusicColors.TextPrimary,
        focusedContainerColor = if (primary) NasMusicColors.Primary
                                else NasMusicColors.Primary.copy(alpha = 0.25f),
        focusedContentColor = if (primary) Color.Black else NasMusicColors.TextPrimary,
        pressedScale = 0.97f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = label, fontSize = FontSize.button())
        }
    }
}
