package com.nasmusic.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 歌单选择弹窗
 *
 * 从任意歌曲行点击"＋加入歌单"时弹出，列出所有本地歌单供选择，
 * 也提供"新建歌单"入口（内部打开 TextInputDialog）。
 *
 * 焦点处理：弹窗打开时默认聚焦第一个歌单（或"新建歌单"），
 * BACK 键关闭弹窗。
 *
 * @param playlists 本地歌单列表（来自 MainViewModel.localPlaylists）
 * @param onPick 用户选中某个歌单
 * @param onCreate 用户新建歌单（输入名称后回调）
 * @param onDismiss 关闭弹窗
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistPickerDialog(
    playlists: List<LocalPlaylist>,
    onPick: (LocalPlaylist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 使用 Dialog 确保显示在系统级窗口层，不被下层内容覆盖
    Dialog(
        onDismissRequest = {
            // dismissOnBackPress=false，BACK 键由内部 BackHandler 处理
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        BackHandler {
            if (showCreateDialog) {
                showCreateDialog = false
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .fillMaxHeight(0.9f)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                    Text(
                    text = stringResource(R.string.mine_pick_playlist_title),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.Subtitle
                )
                Spacer(modifier = Modifier.height(8.dp))
                    Text(
                    text = stringResource(R.string.mine_pick_playlist_hint),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.Body
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 歌单列表（占满剩余空间，过长可滚动）
                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.mine_pick_playlist_empty),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.Button
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(playlists, key = { _, it -> it.id }) { index, playlist ->
                            FocusableSurface(
                                onClick = { onPick(playlist) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                focusedScale = 1.02f,
                                animationDurationMs = 150,
                                containerColor = NasMusicColors.SurfaceVariant.copy(alpha = 0.6f),
                                contentColor = NasMusicColors.TextPrimary,
                                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                                focusedContentColor = NasMusicColors.TextPrimary,
                                pressedScale = 0.98f,
                                requestFocusOnLaunch = index == 0
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "♪",
                                        color = NasMusicColors.Primary,
                                        fontSize = FontSize.Button,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            color = NasMusicColors.TextPrimary,
                                            fontSize = FontSize.Button,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = stringResource(R.string.mine_song_count, playlist.songs.size),
                                            color = LocalFocusableContentColor.current,
                                            fontSize = FontSize.Small
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
                ) {
                    FocusableSurface(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.06f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.8f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Primary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        requestFocusOnLaunch = playlists.isEmpty()
                    ) {
                        Text(
                            text = "+ " + stringResource(R.string.mine_create_playlist),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.Body,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp)
                        )
                    }
                    FocusableSurface(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.06f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.SurfaceVariant,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f
                    ) {
                        Text(
                            text = stringResource(R.string.common_cancel),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.Body,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }

    // 新建歌单输入弹窗
    if (showCreateDialog) {
        TextInputDialog(
            title = stringResource(R.string.mine_create_playlist),
            hint = stringResource(R.string.mine_playlist_name_hint),
            initialValue = "",
            onConfirm = { name ->
                if (name.isNotBlank()) {
                    onCreate(name)
                }
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }
}
