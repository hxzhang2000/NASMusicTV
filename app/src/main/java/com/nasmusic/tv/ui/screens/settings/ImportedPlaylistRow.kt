package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.PlaylistImportHistoryItem
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 最近导入记录行（docs/archive/playlist-import-feature-plan.md §4.4.4）。
 *
 * 2026-09-18 用户决策：历史记录行**无删除操作**，单「打开」→ 跳「我的」（歌单本身
 * 可由歌单卡片删除，删除时 consumeHistoryIfDeleted 联动清理本记录，避免死链）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ImportedPlaylistRow(
    item: PlaylistImportHistoryItem,
    onOpen: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusableSurface(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.03f,
            animationDurationMs = 250,
            containerColor = NasMusicColors.Surface,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.15f),
            focusedContentColor = NasMusicColors.TextPrimary,
            pressedScale = 0.98f,
            focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlaylistPlay,
                    contentDescription = null,
                    tint = NasMusicColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.playlistName, color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
                    Text(
                        text = stringResource(R.string.playlist_import_history_sub, item.importedCount) + " · " +
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(item.importedAt)),
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.body()
                    )
                }
                Text(
                    text = stringResource(R.string.mine_open),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.body()
                )
            }
        }
    }
}