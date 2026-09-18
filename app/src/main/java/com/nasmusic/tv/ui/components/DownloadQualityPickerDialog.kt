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
import com.nasmusic.tv.ui.screens.settings.SettingActionButton
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 码率选择面板（多码率方案 §5.2.1.1）。
 *
 * 仅在**探测到多个可用档位**时弹出；只列出真正可用的档位，
 * 默认选中最高可用档，已下载档位置灰不可选。
 *
 * @param available 探测到的可用档位（降序）
 * @param downloaded 已下载档位集合（置灰）
 * @param onConfirm 用户确认的档位
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp)
            ) {
                Text(
                    text = stringResource(R.string.quality_picker_title, songTitle),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (selectable.isEmpty()) {
                    // 所有可用档都已下载 → 仅保留取消
                    Text(
                        text = stringResource(R.string.quality_picker_all_downloaded),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.body()
                    )
                } else {
                    selectable.forEach { tier ->
                        val label = stringResource(QualityTiers.labelResOf(tier))
                        val mark = if (selected == tier) "◉ " else "○ "
                        SettingActionButton(
                            label = "$mark$label  ${QualityTiers.descriptionOf(tier)}",
                            description = "",
                            onClick = { selected = tier }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // 已下载档位置灰展示（不可选，避免重复下载）
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    SettingActionButton(
                        label = stringResource(R.string.common_cancel),
                        description = "",
                        onClick = onDismiss
                    )
                    if (selectable.isNotEmpty()) {
                        SettingActionButton(
                            label = stringResource(R.string.settings_download),
                            description = "",
                            onClick = { onConfirm(selected) }
                        )
                    }
                }
            }
        }
    }
}
