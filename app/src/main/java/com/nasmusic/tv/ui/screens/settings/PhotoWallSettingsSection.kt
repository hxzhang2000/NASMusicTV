package com.nasmusic.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.nasmusic.tv.backend.photo.FaceScanManager
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.backend.photo.PhotoWallAccessPolicy
import com.nasmusic.tv.backend.photo.SafDirectoryPolicy
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.adaptiveColumns
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.util.PermissionHelper
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId
import com.nasmusic.tv.visualizer.photo.PhotoTransitionRegistry
import java.util.Locale

/**
 * 「照片墙」设置分区（§7.2 的 19 行布局，实现为 23 个控件）。
 *
 * ## 状态 / 动作分离
 *
 * 照 `DownloadSettingsSection` 的既有范式：[PhotoWallSettingsState] 只装数据，
 * [PhotoWallSettingsActions] 只装回调（全部可空 ⇒ 未接线的动作按「点了没反应」处理，
 * 而不是崩）。
 *
 * ⚠️ **17 个设置字段全部从 `state.settings`（`AppSettings`）读**，而不是拆成 17 个参数 ——
 * `SettingsScreen` 本来就持有整份 `AppSettings`，拆开只会让签名再膨胀 17 行。
 *
 * ⚠️ **运行时事实（权限 / 计数 / 进度）从 `state.runtime` 读**（阶段 9 引入
 * [PhotoWallRuntimeState]）—— 它们不落盘、不进备份，与那 17 个字段是两类东西。
 *
 * ## 平台差异（§7.2 / §7.3）
 *
 * | 行 | 电视 | 手机 |
 * |---|---|---|
 * | 「图库」开关 | **不渲染**（§7.3：仅手机） | 渲染（打开时先弹说明再走系统对话框） |
 * | 「重新选择照片」 | 不渲染 | 仅**部分授权**态出现（§9.6） |
 * | 「选择照片目录」 | 渲染（可留空走自动探测） | 渲染（**必填**） |
 * | 「仅扫描 DCIM / Pictures」 | 渲染（生效） | 渲染（说明「仅电视生效」，不置灰） |
 * | 「画面适配」 | ✅ 渲染 | ✅ 渲染（**四端均暴露**，与横竖屏无关） |
 *
 * ## 哪些动作在本阶段是「空接线」
 *
 * 「重新扫描」（阶段 10）、「开始人脸检测」/「清除检测结果」（阶段 11）在本阶段
 * 传入 `null` ⇒ 按钮可见但点击无反应。这是刻意的**分阶段推进**，不是遗漏；
 * 见 `docs/photo-spectrum-effect-plan.md` §15.3 阶段 8 的偏差记录。
 */

/**
 * 照片墙**运行时**派生状态（阶段 9 引入）
 *
 * ⛔ 与 `AppSettings` 里的 17 个字段**严格区分**：
 * - `AppSettings` 的字段是**设置**（用户选的、要落盘、要进备份）
 * - 这里的字段是**运行时事实**（权限、计数、进度）—— **不落盘、不进备份**
 *
 * 之所以单独成类：阶段 10（照片数）/ 阶段 11（人脸进度）还要继续往里加字段，
 * 而**每加一个字段都不该再动 `SettingsScreen` 的签名**（§14.1 的既定取舍）。
 */
data class PhotoWallRuntimeState(
    /**
     * 图库权限三态（§9.6）；`null` = 尚未查询（此时按「未授权」处理，不猜）
     *
     * ⚠️ 它**不是**设置项：官方明确禁止把权限状态存进 `SharedPreferences` / `DataStore`
     * （§9.4），所以它只能来自每次现查。
     */
    val permissionState: PermissionHelper.PhotoPermissionState? = null,
    /** 最近一次 SAF 目录选择被拒的原因（`null` = 无待提示的拒绝） */
    val directoryReject: SafDirectoryPolicy.RejectReason? = null,
    /** 各来源照片数（阶段 10 由聚合器填；本阶段恒 0） */
    val galleryCount: Int = 0,
    val externalCount: Int = 0,
    val jellyfinCount: Int = 0,
    /** 合并去重后的总数（阶段 10 填） */
    val mergedCount: Int = 0,
    /** 人脸检测进度（阶段 11 填；`total <= 0` ⇒ 不渲染进度行） */
    val faceScanDone: Int = 0,
    val faceScanTotal: Int = 0,
    /**
     * 人脸扫描任务阶段（阶段 11 填；`null` = 尚未初始化，按 IDLE 处理）。
     * ⚠️ 运行时事实，不落盘；「开始 / 停止」按钮的形态由它决定。
     */
    val faceScanPhase: FaceScanManager.Phase? = null,
    /**
     * 人脸检测**是否可用**（阶段 11）。
     * 两个来源都是 `false` ⇒ 「开始人脸检测」置灰（T11.4）：
     * - 低画质档（ARMv7 上跑不动推理，§10.2「老设备策略」）
     * - 模型加载失败（`YuNetFaceDetector.warmUp()` 返回 false）
     */
    val faceScanSupported: Boolean = true,
)

/** 照片墙设置分区状态 */
data class PhotoWallSettingsState(
    /** 17 个 `photoWall*` 字段都在这里（见类 KDoc 的说明） */
    val settings: AppSettings,
    /** 是否电视（决定「图库」行是否渲染） */
    val isTV: Boolean,
    /** NAS 是否已连接（未连接时「Jellyfin 照片库」置灰） */
    val nasConnected: Boolean = false,
    /** 运行时派生值（权限 / 计数 / 进度） */
    val runtime: PhotoWallRuntimeState = PhotoWallRuntimeState(),
)

/** 照片墙设置分区动作（全部可空：未接线时按「点了没反应」处理） */
data class PhotoWallSettingsActions(
    val onToggleGallery: ((Boolean) -> Unit)? = null,
    val onToggleExternal: ((Boolean) -> Unit)? = null,
    val onToggleJellyfin: ((Boolean) -> Unit)? = null,
    val onToggleSourceBalance: ((Boolean) -> Unit)? = null,
    val onPickDirectory: (() -> Unit)? = null,
    val onToggleCommonDirsOnly: ((Boolean) -> Unit)? = null,
    val onRescan: (() -> Unit)? = null,
    val onToggleFacesOnly: ((Boolean) -> Unit)? = null,
    val onStartFaceScan: (() -> Unit)? = null,
    /** 停止正在跑的人脸扫描（阶段 11；已扫的部分保留 ⇒ 下次「开始」是续跑） */
    val onStopFaceScan: (() -> Unit)? = null,
    val onClearFaceScan: (() -> Unit)? = null,
    val onToggleRandomTransition: ((Boolean) -> Unit)? = null,
    val onChangeFixedTransition: ((PhotoTransitionId) -> Unit)? = null,
    val onChangeTransitionMs: ((Int) -> Unit)? = null,
    val onChangeHoldMs: ((Int) -> Unit)? = null,
    val onChangeScaleMode: ((PhotoScaleMode) -> Unit)? = null,
    val onToggleKenBurns: ((Boolean) -> Unit)? = null,
    val onToggleAudioReactive: ((Boolean) -> Unit)? = null,
    val onTogglePulseZoom: ((Boolean) -> Unit)? = null,
    val onToggleBreathe: ((Boolean) -> Unit)? = null,
    /**
     * 「重新选择照片」（阶段 9，§9.6 规则 3）
     *
     * 仅在**部分授权**态出现。走的是与「打开图库开关」**同一个**权限请求入口 ——
     * Android 14+ 下再次请求即唤起系统的 reselection UI。
     */
    val onReselectPhotos: (() -> Unit)? = null,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PhotoWallSettingsSection(
    state: PhotoWallSettingsState,
    actions: PhotoWallSettingsActions,
) {
    val s = state.settings
    val runtime = state.runtime

    // 「先弹说明再触发系统对话框」（§9.4 建议：提升通过率）
    // 用本地 state 而不是上层参数 —— 它纯属本分区的瞬时 UI 状态，不需要跨层传递。
    var showGalleryIntro by remember { mutableStateOf(false) }

    Column {
        SectionTitle(stringResource(R.string.settings_photo_wall))

        // ── 照片来源 ────────────────────────────────────────────────────────
        SubSectionTitle(stringResource(R.string.settings_photo_wall_sources))

        // 「图库」行**仅手机**渲染（§7.3）：电视没有系统相册，显示它是误导。
        if (!state.isTV) {
            // ⛔ 打开图库开关**才**申请权限（§6.2）。`null`（尚未查到）按「需要申请」处理：
            //    宁可多弹一次说明，也不要静默打开一个没有权限的开关。
            val needsGrant = runtime.permissionState
                ?.let { PhotoWallAccessPolicy.needsPermissionRequest(it) } ?: true
            SettingSwitch(
                label = stringResource(R.string.settings_photo_wall_gallery),
                description = stringResource(R.string.settings_photo_wall_gallery_desc),
                checked = s.photoWallGalleryEnabled,
                onClick = {
                    when {
                        s.photoWallGalleryEnabled -> actions.onToggleGallery?.invoke(false)
                        needsGrant -> showGalleryIntro = true
                        else -> actions.onToggleGallery?.invoke(true)
                    }
                }
            )

            // Android 14+「仅选择照片」：说明当前范围 + 给「重新选择照片」入口（§9.6 规则 3）。
            // 没有这个入口，用户想回到「允许全部」只能自己去系统设置里翻。
            val isPartial = runtime.permissionState
                ?.let { PhotoWallAccessPolicy.isPartial(it) } ?: false
            if (s.photoWallGalleryEnabled && isPartial) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    SettingsInfoRow(
                        stringResource(R.string.settings_photo_wall_gallery),
                        stringResource(R.string.settings_photo_wall_gallery_partial)
                    )
                }
                SettingActionButton(
                    label = stringResource(R.string.settings_photo_wall_reselect),
                    description = stringResource(R.string.settings_photo_wall_reselect_desc),
                    onClick = { actions.onReselectPhotos?.invoke() }
                )
            }
        }

        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_external),
            description = stringResource(R.string.settings_photo_wall_external_desc),
            checked = s.photoWallExternalEnabled,
            onClick = { actions.onToggleExternal?.invoke(!s.photoWallExternalEnabled) }
        )

        // 目录与「仅扫常见目录」只在「外接存储」开启时才出现 —— 关着的时候它们无从作用。
        if (s.photoWallExternalEnabled) {
            SettingActionButton(
                label = stringResource(R.string.settings_photo_wall_pick_dir),
                description = if (s.photoWallDirUri.isBlank()) {
                    stringResource(R.string.settings_photo_wall_pick_dir_desc)
                } else {
                    s.photoWallDirUri
                },
                onClick = { actions.onPickDirectory?.invoke() }
            )
            // ⛔ 选到内部存储必须**当场给理由**（§6.3）：SAF 选择器无法限制可选范围，
            //    用户点进来一路选到内部存储是常态，不解释就成了「点了没反应」。
            runtime.directoryReject?.let { reason ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    SettingsInfoRow(
                        stringResource(R.string.settings_photo_wall_pick_dir),
                        stringResource(rejectTextRes(reason))
                    )
                }
            }
            SettingSwitch(
                label = stringResource(R.string.settings_photo_wall_common_dirs),
                description = stringResource(R.string.settings_photo_wall_common_dirs_desc),
                checked = s.photoWallCommonDirsOnly,
                onClick = { actions.onToggleCommonDirsOnly?.invoke(!s.photoWallCommonDirsOnly) }
            )
        }

        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_source_balance),
            description = stringResource(R.string.settings_photo_wall_source_balance_desc),
            checked = s.photoWallSourceBalance,
            onClick = { actions.onToggleSourceBalance?.invoke(!s.photoWallSourceBalance) }
        )

        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_jellyfin),
            description = stringResource(R.string.settings_photo_wall_jellyfin_desc),
            checked = s.photoWallJellyfinEnabled,
            // 未连接 NAS 时置灰（§7.3）
            enabled = state.nasConnected,
            onClick = { actions.onToggleJellyfin?.invoke(!s.photoWallJellyfinEnabled) }
        )

        // 运行时可派生信息（阶段 9/10 才会有真实数字）
        Spacer(modifier = Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            SettingsInfoRow(
                stringResource(R.string.settings_photo_wall_enabled_sources),
                enabledSourcesText(s)
            )
            SettingsInfoRow(
                stringResource(R.string.settings_photo_wall_counts),
                stringResource(
                    R.string.settings_photo_wall_counts_value,
                    runtime.galleryCount,
                    runtime.externalCount,
                    runtime.jellyfinCount
                )
            )
            SettingsInfoRow(
                stringResource(R.string.settings_photo_wall_merged),
                stringResource(R.string.settings_photo_wall_count_unit, runtime.mergedCount)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingActionButton(
            label = stringResource(R.string.settings_photo_wall_rescan),
            description = stringResource(R.string.settings_photo_wall_rescan_desc),
            onClick = { actions.onRescan?.invoke() }
        )

        // ── 显示筛选 ────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_photo_wall_filter))
        // ⛔ T11.4：低画质档 / 模型不可用 ⇒ 人脸功能整体置灰（开关也一起灰，
        //   因为开着它等于「照片墙变空」—— 没有检测结果就没有照片可显示）
        val faceEnabled = runtime.faceScanSupported
        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_faces_only),
            description = stringResource(R.string.settings_photo_wall_faces_only_desc),
            checked = s.photoWallFacesOnly,
            enabled = faceEnabled,
            onClick = { actions.onToggleFacesOnly?.invoke(!s.photoWallFacesOnly) }
        )
        val running = runtime.faceScanPhase == FaceScanManager.Phase.RUNNING
        SettingActionButton(
            label = stringResource(
                if (running) R.string.settings_photo_wall_face_scan_stop
                else R.string.settings_photo_wall_face_scan
            ),
            description = stringResource(
                if (running) R.string.settings_photo_wall_face_scan_stop_desc
                else R.string.settings_photo_wall_face_scan_desc
            ),
            enabled = faceEnabled,
            onClick = {
                if (running) actions.onStopFaceScan?.invoke() else actions.onStartFaceScan?.invoke()
            }
        )
        if (runtime.faceScanTotal > 0) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                SettingsInfoRow(
                    stringResource(R.string.settings_photo_wall_face_scan),
                    stringResource(
                        R.string.settings_photo_wall_face_scan_progress,
                        runtime.faceScanDone,
                        runtime.faceScanTotal
                    )
                )
            }
        }
        if (runtime.faceScanPhase == FaceScanManager.Phase.UNAVAILABLE) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                SettingsInfoRow(
                    stringResource(R.string.settings_photo_wall_face_scan),
                    stringResource(R.string.settings_photo_wall_face_unavailable)
                )
            }
        }
        SettingActionButton(
            label = stringResource(R.string.settings_photo_wall_face_clear),
            description = stringResource(R.string.settings_photo_wall_face_clear_desc),
            enabled = faceEnabled,
            onClick = { actions.onClearFaceScan?.invoke() }
        )

        // ── 转场效果 ────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_photo_wall_transition))
        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_random_transition),
            description = stringResource(R.string.settings_photo_wall_random_transition_desc),
            checked = s.photoWallRandomTransition,
            onClick = { actions.onToggleRandomTransition?.invoke(!s.photoWallRandomTransition) }
        )
        // 「指定转场效果」只在随机关闭时才出现（§7.2）
        if (!s.photoWallRandomTransition) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.settings_photo_wall_fixed_transition),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button()
                )
                Spacer(modifier = Modifier.height(8.dp))
                PhotoTransitionChips(
                    selected = s.photoWallFixedTransition,
                    onSelect = { actions.onChangeFixedTransition?.invoke(it) }
                )
            }
        }
        DurationRow(
            label = stringResource(R.string.settings_photo_wall_transition_ms),
            valueMs = s.photoWallTransitionMs,
            stepMs = TRANSITION_STEP_MS,
            range = TRANSITION_RANGE_MS,
            onChange = actions.onChangeTransitionMs
        )
        DurationRow(
            label = stringResource(R.string.settings_photo_wall_hold_ms),
            valueMs = s.photoWallHoldMs,
            stepMs = HOLD_STEP_MS,
            range = HOLD_RANGE_MS,
            onChange = actions.onChangeHoldMs
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.settings_photo_wall_scale_mode),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionChip(
                    label = stringResource(R.string.settings_photo_wall_scale_crop),
                    selected = s.photoWallScaleMode == PhotoScaleMode.CROP,
                    onClick = { actions.onChangeScaleMode?.invoke(PhotoScaleMode.CROP) }
                )
                OptionChip(
                    label = stringResource(R.string.settings_photo_wall_scale_fit),
                    selected = s.photoWallScaleMode == PhotoScaleMode.FIT,
                    onClick = { actions.onChangeScaleMode?.invoke(PhotoScaleMode.FIT) }
                )
            }
        }

        // ── 停留期运动 ──────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_photo_wall_motion))
        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_ken_burns),
            description = stringResource(R.string.settings_photo_wall_ken_burns_desc),
            checked = s.photoWallKenBurns,
            onClick = { actions.onToggleKenBurns?.invoke(!s.photoWallKenBurns) }
        )

        // ── 音频反应（默认关） ───────────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        SubSectionTitle(stringResource(R.string.settings_photo_wall_audio))
        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_audio_reactive),
            description = stringResource(R.string.settings_photo_wall_audio_reactive_desc),
            checked = s.photoWallAudioReactive,
            onClick = { actions.onToggleAudioReactive?.invoke(!s.photoWallAudioReactive) }
        )
        // 下面两项**受上一项控制**：总开关关着时置灰（值本身保留，§7.3）
        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_pulse_zoom),
            description = stringResource(R.string.settings_photo_wall_pulse_zoom_desc),
            checked = s.photoWallPulseZoom,
            enabled = s.photoWallAudioReactive,
            onClick = { actions.onTogglePulseZoom?.invoke(!s.photoWallPulseZoom) }
        )
        SettingSwitch(
            label = stringResource(R.string.settings_photo_wall_breathe),
            description = stringResource(R.string.settings_photo_wall_breathe_desc),
            checked = s.photoWallBreathe,
            enabled = s.photoWallAudioReactive,
            onClick = { actions.onToggleBreathe?.invoke(!s.photoWallBreathe) }
        )
    }

    // 图库授权说明弹窗（§9.4「建议先弹一句说明再触发系统对话框」）
    //
    // ⚠️ 用 `androidx.compose.ui.window.Dialog` 包一层（与 SettingsScreen 的清空下载确认弹窗同款）：
    // 它自建窗口，因此**不受本分区所在 LazyColumn item 的布局约束**，
    // 否则 `fillMaxSize()` 在无限高约束下不会铺满，弹窗会缩在列表里。
    if (showGalleryIntro) {
        Dialog(
            onDismissRequest = { showGalleryIntro = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            ConfirmDialog(
                title = stringResource(R.string.settings_photo_wall_gallery_intro_title),
                message = stringResource(R.string.settings_photo_wall_gallery_intro_msg),
                confirmLabel = stringResource(R.string.settings_photo_wall_gallery_intro_confirm),
                onConfirm = {
                    showGalleryIntro = false
                    actions.onToggleGallery?.invoke(true)
                },
                onDismiss = { showGalleryIntro = false }
            )
        }
    }
}

/**
 * 目录拒绝原因 → 文案（§6.3）
 *
 * ⚠️ 数据层只给枚举（`SafDirectoryPolicy.RejectReason`），文案在这里映射 ——
 * 「数据层不产出面向用户的文案」是项目硬约定。
 */
@StringRes
private fun rejectTextRes(reason: SafDirectoryPolicy.RejectReason): Int = when (reason) {
    SafDirectoryPolicy.RejectReason.EMPTY -> R.string.photo_wall_notice_dir_empty
    SafDirectoryPolicy.RejectReason.MALFORMED -> R.string.photo_wall_notice_dir_malformed
    SafDirectoryPolicy.RejectReason.INTERNAL_STORAGE -> R.string.photo_wall_notice_dir_internal
}

/** 「已启用」文案：开着的来源用 ` + ` 连接；一个都没开时显示「未启用任何来源」（§7.2） */
@Composable
private fun enabledSourcesText(s: AppSettings): String {
    // ⚠️ `stringResource` 是 @Composable，**不能**写进 `buildList {}` 之类的普通 lambda
    val labels = listOf(
        stringResource(R.string.settings_photo_wall_gallery) to s.photoWallGalleryEnabled,
        stringResource(R.string.settings_photo_wall_external) to s.photoWallExternalEnabled,
        stringResource(R.string.settings_photo_wall_jellyfin) to s.photoWallJellyfinEnabled,
    ).filter { it.second }.map { it.first }

    return if (labels.isEmpty()) {
        stringResource(R.string.settings_photo_wall_no_source)
    } else {
        labels.joinToString(" + ")
    }
}

/**
 * 时长调节行（`[ - ] 0.7 秒 [ + ]`）。
 *
 * ⚠️ 钳制在**这里**和 `AppPreferences` 的 setter 里**各做一次**：
 * 这里是为了让按钮点到底就停（不发出越界值），setter 那里是为了挡住**备份导入**这条路
 * （它不经过 UI）。两处都必要，不是重复。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DurationRow(
    label: String,
    valueMs: Int,
    stepMs: Int,
    range: IntRange,
    onChange: ((Int) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(text = label, color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AdjustButton("-", onClick = { onChange?.invoke((valueMs - stepMs).coerceIn(range)) })
            Text(
                text = stringResource(R.string.settings_photo_wall_seconds, secondsText(valueMs)),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.title()
            )
            AdjustButton("+", onClick = { onChange?.invoke((valueMs + stepMs).coerceIn(range)) })
        }
    }
}

/** 毫秒 → `"0.7"` 这样的秒字符串（不含单位，单位在 `settings_photo_wall_seconds` 里） */
private fun secondsText(ms: Int): String = String.format(Locale.US, "%.1f", ms / 1000f)

/**
 * 转场效果多选一。
 *
 * ⚠️ 列出的是 **`PhotoTransitionRegistry.available()`**（真有实现的那些），
 * **不是** `PhotoTransitionId.entries` —— 后者含 P1/P2 尚未实现的项，
 * 选中它们只会得到「什么都没发生」。
 *
 * ⚠️ 不用 `FlowRow`（`@ExperimentalLayoutApi`，本项目未启用）也不用 `horizontalScroll`
 * （超宽时 TV 焦点会跑到屏幕外）⇒ 按 [adaptiveColumns] 手动分行，二维焦点导航天然可用。
 */
@Composable
private fun PhotoTransitionChips(
    selected: PhotoTransitionId,
    onSelect: (PhotoTransitionId) -> Unit,
) {
    // 注册表内容进程内不变 ⇒ remember 住排序结果，避免每次重组重新排序
    val ids = remember { PhotoTransitionRegistry.available().sortedBy { it.ordinal } }
    val columns = adaptiveColumns(tv = 5, phonePortrait = 3, medium = 4)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.chunked(columns).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowIds.forEach { id ->
                    OptionChip(
                        label = id.displayName,
                        selected = id == selected,
                        onClick = { onSelect(id) }
                    )
                }
            }
        }
    }
}

/** 单个多选一 chip（照 `PlayModeSelector` 的选中样式） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
        // 48dp 在竖屏只有 39.4 物理 dp ⇒ 走 portraitTouchTarget（TV / 横屏原样返回 48dp）
        modifier = Modifier.height(portraitTouchTarget(48.dp)),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.06f,
        animationDurationMs = 200,
        containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
        contentColor = if (selected) Color.Black else NasMusicColors.TextPrimary,
        focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.2f),
        focusedContentColor = if (selected) Color.Black else NasMusicColors.TextPrimary,
        pressedScale = 0.96f,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected) Color.Black else LocalFocusableContentColor.current,
                fontSize = FontSize.button(),
                maxLines = 1
            )
        }
    }
}

/** 转场时长：步进 100ms，范围同 `AppPreferences.PHOTO_WALL_TRANSITION_MS_RANGE` */
private const val TRANSITION_STEP_MS = 100
private val TRANSITION_RANGE_MS = 300..2000

/** 停留时长：步进 1s，范围同 `AppPreferences.PHOTO_WALL_HOLD_MS_RANGE` */
private const val HOLD_STEP_MS = 1_000
private val HOLD_RANGE_MS = 3_000..30_000
