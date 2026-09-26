package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 缓存分区状态 */
data class CacheSettingsState(
    val settings: AppSettings,
)

/** 缓存分区动作 */
data class CacheSettingsActions(
    val onToggleCacheLyrics: (Boolean) -> Unit,
    val onToggleCacheCover: (Boolean) -> Unit,
    val onClearLyricsCache: (() -> Unit)?,
    val onClearCoverCache: (() -> Unit)?,
    val onClearMvCache: (() -> Unit)?,
    val onClearAccompanimentCache: (() -> Unit)?,
)

/** 缓存分区（原 SettingsScreen CACHE 分支，逻辑逐行搬迁） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CacheSettingsSection(
    state: CacheSettingsState,
    actions: CacheSettingsActions
) {
    androidx.compose.foundation.layout.Column {
        SectionTitle(stringResource(R.string.settings_cache))
        // 缓存目录大小（置顶，醒目可见）
        val context = LocalContext.current
        // 2026-09-25 审查修复（#13）：组合期同步 walkTopDown 遍历整个 cacheDir 且无 remember，
        // 每次重组全量重算（文件数可达数千，低端盒子明显掉帧）。改为 LaunchedEffect + IO
        // 计算一次回填 state，重组零成本。
        var cacheDirSize by remember { mutableStateOf("…") }
        // 2026-09-26 审查补修（#11 Low）：分区内清理缓存后数字不刷新。加 refreshKey 触发
        // 重算——每个清理按钮点击后 ++，LaunchedEffect(refreshKey) 重新执行。
        var refreshKey by remember { mutableStateOf(0) }
        LaunchedEffect(refreshKey) {
            // 清理动作是 fire-and-forget 异步（MainViewModel 里 viewModelScope.launch），
            // 重算前留 300ms 让其落盘，否则可能量到删除中途的尺寸。
            if (refreshKey > 0) delay(300)
            val formatted = withContext(Dispatchers.IO) {
                try {
                    val cacheDir = context.cacheDir
                    val sizeBytes = cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                    if (sizeBytes > 1048576L) "${sizeBytes / 1048576} MB"
                    else if (sizeBytes > 1024L) "${sizeBytes / 1024} KB"
                    else "$sizeBytes B"
                } catch (_: Exception) { "—" }
            }
            cacheDirSize = formatted
        }
        Text(
            text = stringResource(R.string.settings_cache_dir_size, cacheDirSize),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        )
        // ── 缓存开关（原歌词 tab 的歌词/封面缓存开关） ──
        SubSectionTitle(stringResource(R.string.settings_cache_switch))
        SettingSwitch(label = stringResource(R.string.settings_cache_lyrics), description = stringResource(R.string.settings_cache_lyrics_desc), checked = state.settings.cacheLyrics, onClick = { actions.onToggleCacheLyrics(!state.settings.cacheLyrics) })
        SettingSwitch(label = stringResource(R.string.settings_cache_cover), description = stringResource(R.string.settings_cache_cover_desc), checked = state.settings.cacheCover, onClick = { actions.onToggleCacheCover(!state.settings.cacheCover) })
        Spacer(modifier = Modifier.height(8.dp))
        // ── 缓存清理 ──
        SubSectionTitle(stringResource(R.string.settings_cache_clear))
        if (actions.onClearLyricsCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_lyrics_cache),
                description = stringResource(R.string.settings_clear_lyrics_cache_desc),
                onClick = {
                    actions.onClearLyricsCache?.invoke()
                    refreshKey++
                }
            )
        }
        if (actions.onClearCoverCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_cover_cache),
                description = stringResource(R.string.settings_clear_coil_cache),
                onClick = {
                    actions.onClearCoverCache?.invoke()
                    refreshKey++
                }
            )
        }
        if (actions.onClearMvCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_mv_cache),
                description = stringResource(R.string.settings_clear_mv_cache_desc),
                onClick = {
                    actions.onClearMvCache?.invoke()
                    refreshKey++
                }
            )
        }
        if (actions.onClearAccompanimentCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_all_cache),
                description = stringResource(R.string.settings_clear_all_cache_desc),
                onClick = {
                    actions.onClearAccompanimentCache?.invoke()
                    refreshKey++
                }
            )
        }
    }
}
