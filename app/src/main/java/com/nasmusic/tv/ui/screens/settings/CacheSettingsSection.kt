package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
        val cacheDirSize = try {
            val cacheDir = context.cacheDir
            val sizeBytes = cacheDir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            if (sizeBytes > 1048576L) "${sizeBytes / 1048576} MB"
            else if (sizeBytes > 1024L) "${sizeBytes / 1024} KB"
            else "$sizeBytes B"
        } catch (_: Exception) { "—" }
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
                onClick = actions.onClearLyricsCache
            )
        }
        if (actions.onClearCoverCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_cover_cache),
                description = stringResource(R.string.settings_clear_coil_cache),
                onClick = actions.onClearCoverCache
            )
        }
        if (actions.onClearMvCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_mv_cache),
                description = stringResource(R.string.settings_clear_mv_cache_desc),
                onClick = actions.onClearMvCache
            )
        }
        if (actions.onClearAccompanimentCache != null) {
            SettingActionButton(
                label = stringResource(R.string.settings_clear_all_cache),
                description = stringResource(R.string.settings_clear_all_cache_desc),
                onClick = actions.onClearAccompanimentCache
            )
        }
    }
}
