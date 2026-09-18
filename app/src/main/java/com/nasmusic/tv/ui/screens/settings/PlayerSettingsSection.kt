package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 播放设置分区状态（自动播放/频谱/模式/分离/模型/封面滤镜） */
data class PlayerSettingsState(
    val settings: AppSettings,
    val visualizerTheme: VisualizerTheme,
    val separationMode: AppPreferences.SeparationMode,
    val modelDownloaded: Boolean,
    val modelDownloading: Boolean,
    val modelDownloadProgress: Float,
    val modelDownloadedMB: Long,
    val modelTotalMB: Long,
    val modelSizeMB: Double,
    val modelDownloadError: String?,
    val modelPath: String,
    val coverFilterEnabled: Boolean,
    val coverFilterBlurRadius: Float,
    val coverFilterDarkOverlay: Float,
    // F2-5：跨曲交叉淡入淡出
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSec: Int = 4,
    // F2-6：音质档位
    val qualityTier: Int = 0,
)

/** 播放设置分区动作 */
data class PlayerSettingsActions(
    val onToggleAutoPlayNext: (Boolean) -> Unit,
    val onChangeVisualizerTheme: (VisualizerTheme) -> Unit,
    val onChangePlayMode: (PlayMode) -> Unit,
    val onOpenEqualizer: (() -> Unit)?,
    val onChangeSeparationMode: ((AppPreferences.SeparationMode) -> Unit)?,
    val onDownloadModel: (() -> Unit)?,
    val onDeleteModel: (() -> Unit)?,
    val onScanTransferModel: (() -> Unit)?,
    val onToggleCoverFilter: (Boolean) -> Unit,
    val onChangeCoverBlurRadius: (Float) -> Unit,
    val onChangeCoverDarkOverlay: (Float) -> Unit,
    /** F2-5：crossfade 开关/时长 */
    val onToggleCrossfade: (Boolean) -> Unit = {},
    val onChangeCrossfadeDuration: (Int) -> Unit = {},
    /** v2.35.0 多码率：清除全部单曲音质覆盖（方案 §5.3） */
    val onClearQualityOverrides: (() -> Unit)? = null,
    /** F2-6：音质档位（AUTO=0/999/320/128） */
    val onChangeQualityTier: (Int) -> Unit = {},
)

/** 播放设置分区（原 SettingsScreen PLAYBACK 分支，逻辑逐行搬迁） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerSettingsSection(
    state: PlayerSettingsState,
    actions: PlayerSettingsActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_playback))
        SettingSwitch(label = stringResource(R.string.settings_auto_play), description = stringResource(R.string.settings_auto_play_desc), checked = state.settings.autoPlayNext, onClick = { actions.onToggleAutoPlayNext(!state.settings.autoPlayNext) })
        PlayModeSelector(current = state.settings.defaultPlayMode, onSelect = { actions.onChangePlayMode(it) })
        // F2-5：跨曲交叉淡入淡出
        Spacer(modifier = Modifier.height(12.dp))
        SettingSwitch(
            label = stringResource(R.string.settings_crossfade),
            description = stringResource(R.string.settings_crossfade_desc),
            checked = state.crossfadeEnabled,
            onClick = { actions.onToggleCrossfade(!state.crossfadeEnabled) }
        )
        if (state.crossfadeEnabled) {
            // 时长档位 2/4/6/8/12 秒（TV 遥控适配的离散选择，比滑条更易 D-Pad 操作）
            val durations = listOf(2, 4, 6, 8, 12)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                durations.forEach { sec ->
                    SettingActionButton(
                        label = "${sec}s",
                        description = "",
                        onClick = { actions.onChangeCrossfadeDuration(sec) }
                    )
                }
            }
        }
        // F2-6：音质分级（仅 Meting 网络源生效；NAS 原品质直传）
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_quality_tier),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(start = 4.dp)
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            // v2.35.0：遍历单一真相源（含新增 192 档），避免"常量加了 UI 忘了加"的漂移
            QualityTiers.all.forEach { tier ->
                val label = stringResource(QualityTiers.labelResOf(tier))
                SettingActionButton(
                    label = if (state.qualityTier == tier) "▶ $label" else label,
                    description = "",
                    onClick = { actions.onChangeQualityTier(tier) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        SettingActionButton(
            label = stringResource(R.string.settings_equalizer),
            description = stringResource(R.string.settings_equalizer_desc),
            onClick = { actions.onOpenEqualizer?.invoke() }
        )
        // v2.35.0 多码率：清除全部单曲音质覆盖（方案 §5.3）
        Spacer(modifier = Modifier.height(8.dp))
        SettingActionButton(
            label = stringResource(R.string.quality_override_clear),
            description = stringResource(R.string.quality_override_clear_desc),
            onClick = { actions.onClearQualityOverrides?.invoke() }
        )
        // ── 人声分离模式 ──
        Spacer(modifier = Modifier.height(24.dp))
        SubSectionTitle(stringResource(R.string.settings_separation_mode_title))
        run {
            val isHq = state.separationMode == AppPreferences.SeparationMode.HIGH_QUALITY
            val hqLabel = if (state.modelDownloaded) stringResource(R.string.settings_hq_mode_downloaded) else stringResource(R.string.settings_hq_mode_not_downloaded)
            val hqDesc = when {
                !state.modelDownloaded -> stringResource(R.string.settings_download_model_hint)
                isHq -> stringResource(R.string.settings_onnx_inference)
                else -> stringResource(R.string.settings_dsp_inference)
            }
            SettingSwitch(
                label = hqLabel,
                description = hqDesc,
                checked = isHq,
                enabled = state.modelDownloaded,
                onClick = { actions.onChangeSeparationMode?.invoke(if (isHq) AppPreferences.SeparationMode.FAST else AppPreferences.SeparationMode.HIGH_QUALITY) }
            )
        }
        // 模型下载区
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            if (state.modelDownloading) {
                // 下载中：进度条
                Text(
                    text = stringResource(R.string.settings_downloading_model) + "：${(state.modelDownloadProgress * 100).toInt()}%  (${state.modelDownloadedMB}MB / ${state.modelTotalMB}MB)",
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.body()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NasMusicColors.SurfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(state.modelDownloadProgress.coerceIn(0f, 1f))
                            .height(8.dp)
                            .background(NasMusicColors.Primary, RoundedCornerShape(4.dp))
                    )
                }
            } else if (state.modelDownloaded) {
                // 已下载：显示路径 + 大小 + 删除按钮
                Column {
                    Text(
                        text = stringResource(R.string.settings_model_downloaded_size, state.modelSizeMB),
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.body()
                    )
                    Text(
                        text = stringResource(R.string.settings_model_path, state.modelPath),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingActionButton(
                                label = stringResource(R.string.settings_delete_model),
                                description = stringResource(R.string.settings_delete_model_desc),
                                onClick = { actions.onDeleteModel?.invoke() }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SettingActionButton(
                                label = stringResource(R.string.settings_scan_upload_model),
                                description = stringResource(R.string.settings_scan_upload_model_desc),
                                onClick = { actions.onScanTransferModel?.invoke() }
                            )
                        }
                    }
                }
            } else {
                // 未下载：显示下载按钮 + 路径 + 扫码上传
                Column {
                    Text(
                        text = stringResource(R.string.settings_network_model_not_downloaded),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.body()
                    )
                    Text(
                        text = stringResource(R.string.settings_storage_path, state.modelPath),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            SettingActionButton(
                                label = stringResource(R.string.settings_download_separation_model),
                                description = stringResource(R.string.settings_download_separation_model_desc),
                                onClick = { actions.onDownloadModel?.invoke() }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            SettingActionButton(
                                label = stringResource(R.string.settings_scan_upload_model),
                                description = stringResource(R.string.settings_scan_upload_model_desc),
                                onClick = { actions.onScanTransferModel?.invoke() }
                            )
                        }
                    }
                }
            }
            state.modelDownloadError?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = err, color = NasMusicColors.Danger, fontSize = FontSize.small())
            }
        }
        // ── 封面滤镜分组 ──
        Spacer(modifier = Modifier.height(24.dp))
        SubSectionTitle(stringResource(R.string.settings_cover))
        SettingSwitch(
            label = stringResource(R.string.settings_cover_filter),
            description = stringResource(R.string.settings_cover_filter_desc),
            checked = state.coverFilterEnabled,
            onClick = { actions.onToggleCoverFilter(!state.coverFilterEnabled) }
        )
        if (state.coverFilterEnabled) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.settings_cover_blur_radius, state.coverFilterBlurRadius.toInt()),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Blur radius buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdjustButton("-", onClick = {
                    val new = (state.coverFilterBlurRadius - 2f).coerceAtLeast(0f)
                    actions.onChangeCoverBlurRadius(new)
                })
                Text(
                    text = "%.0fpx".format(state.coverFilterBlurRadius),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.title(),
                    modifier = Modifier.width(64.dp).padding(horizontal = 8.dp)
                )
                AdjustButton("+", onClick = {
                    val new = (state.coverFilterBlurRadius + 2f).coerceAtMost(40f)
                    actions.onChangeCoverBlurRadius(new)
                })
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.settings_cover_dark_overlay, (state.coverFilterDarkOverlay * 100).toInt()),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdjustButton("-", onClick = {
                    val new = (state.coverFilterDarkOverlay - 0.1f).coerceAtLeast(0f)
                    actions.onChangeCoverDarkOverlay(new)
                })
                Text(
                    text = "${(state.coverFilterDarkOverlay * 100).toInt()}%",
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.title(),
                    modifier = Modifier.width(64.dp).padding(horizontal = 8.dp)
                )
                AdjustButton("+", onClick = {
                    val new = (state.coverFilterDarkOverlay + 0.1f).coerceAtMost(1f)
                    actions.onChangeCoverDarkOverlay(new)
                })
            }
        }
    }
}
