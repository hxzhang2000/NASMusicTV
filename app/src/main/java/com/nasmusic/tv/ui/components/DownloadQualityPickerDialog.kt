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
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 码率选择面板（多码率方案 §5.2.1.1）。
 *
 * 仅在**探测到多个可用档位**时弹出；只列出真正可用的档位，
 * 默认选中最高可用档，已下载档位置灰不可选。
 *
 * 交互说明（v2.35.0 手机端修复）：
 * - 点某一行 = **选中**该档（右侧显示「已选」，不是「确定」）
 * - 点底部「下载」= **确认并开始下载**
 *
 * 原实现直接复用了设置页的 `SettingActionButton`，该组件右侧**硬编码**显示「确定」文案
 * （它是设置页的行样式），导致对话框里每一行看起来都像确认按钮 ——
 * 用户点了行内的「确定」以为会开始下载，实际只切换了选中标记，表现为"点了没反应"。
 * 现改为专用行组件 `QualityOptionRow`，文案与语义一致。
 *
 * 手机端适配：面板宽度改为 `widthIn(max = 560.dp)` + 高度 `heightIn(max = 视口-32dp)`
 * + `verticalScroll`，避免多档位 + 已下载列表把底部按钮挤出屏幕（表现为"界面被截断"）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DownloadQualityPickerDialog(
    songTitle: String,
    available: List<Int>,
    downloaded: Set<Int>,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 默认选中最高可用档（而非全局默认档 —— 全局默认档可能在该曲不可用）
    val selectable = remember(available, downloaded) { available.filter { it !in downloaded } }
    var selected by remember(selectable) {
        mutableIntStateOf(selectable.firstOrNull() ?: QualityTiers.AUTO)
    }

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
            // 手机竖屏适配：
            // - 宽度取「560dp 与 视口 92%」的较小值（不能用 widthIn + fillMaxWidth 组合，
            //   在 BoxWithConstraints 全屏父级下会解析成异常宽度，导致底部按钮不可见）
            // - 高度封顶并允许纵向滚动，避免多档位把底部按钮挤出屏幕
            val panelWidth = minOf(560.dp, maxWidth * 0.92f)
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
                    text = stringResource(R.string.quality_picker_title, songTitle),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (selectable.isEmpty()) {
                    // 所有可用档都已下载 → 说明原因（「下载」按钮此时置灰而非消失）
                    Text(
                        text = stringResource(R.string.quality_picker_all_downloaded),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.body()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.quality_picker_all_downloaded_hint),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.caption()
                    )
                } else {
                    selectable.forEach { tier ->
                        QualityOptionRow(
                            label = stringResource(QualityTiers.labelResOf(tier)),
                            description = QualityTiers.descriptionOf(tier),
                            selected = selected == tier,
                            onClick = { selected = tier }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 已下载档位展示（不可选，避免重复下载）
                val downloadedList = available.filter { it in downloaded }
                if (downloadedList.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "─────────",
                        color = NasMusicColors.Border,
                        fontSize = FontSize.caption()
                    )
                    downloadedList.forEach { tier ->
                        Text(
                            text = stringResource(
                                R.string.quality_picker_downloaded,
                                stringResource(QualityTiers.labelResOf(tier))
                            ),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.caption(),
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                        )
                    }
                }

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
                    // v2.35.0 手机端修复：**「下载」按钮始终渲染**。
                    // 原实现是 `if (selectable.isNotEmpty())` —— 该曲所有可用档都已下载时
                    // 按钮**整个消失**，用户只看到「取消」，误以为界面坏了
                    // （实测反馈："底部没有「下载」，只有 取消"）。
                    // 现在始终显示，无可选档时置灰禁用。
                    QualityDialogButton(
                        label = stringResource(R.string.settings_download),
                        primary = true,
                        enabled = selectable.isNotEmpty(),
                        onClick = { if (selectable.isNotEmpty()) onConfirm(selected) }
                    )
                }
            }
        }
    }
}
