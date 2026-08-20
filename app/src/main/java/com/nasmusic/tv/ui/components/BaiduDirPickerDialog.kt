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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 百度网盘目录树选择对话框
 *
 * 供设置页音乐根目录 / MV 目录选择使用：展示当前路径下的子目录列表，
 * 支持 D-Pad 上下导航、确定键进入子目录、返回键回到上级目录，
 * 底部「选择此目录」确认当前路径、「取消」关闭。
 *
 * 目录列表通过 [onListDirs] 挂起回调获取（内部复用 [com.nasmusic.tv.backend.network.baidu.BaiduPanApi.listDir]，
 * 由调用方决定实现，如 [com.nasmusic.tv.ui.viewmodel.MainViewModel.listBaiduDirs]）。
 * 回调抛异常时展示加载失败态并允许重试。
 *
 * @param initialPath 初始目录（如 "/音乐"；"/" 表示根目录）
 * @param onListDirs 挂起回调：返回指定路径下的文件列表（对话框仅展示其中的目录）
 * @param onConfirm 用户确认选中的目录路径
 * @param onDismiss 关闭对话框
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BaiduDirPickerDialog(
    initialPath: String,
    onListDirs: suspend (String) -> List<BaiduFile>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var currentPath by remember { mutableStateOf(initialPath) }
    var dirs by remember { mutableStateOf<List<BaiduFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var loadKey by remember { mutableStateOf(0) }

    // 加载当前路径下的子目录；失败置 failed 展示错误态（可重试）
    LaunchedEffect(currentPath, loadKey) {
        loading = true
        failed = false
        try {
            dirs = onListDirs(currentPath).filter { it.isDir }
        } catch (e: Exception) {
            failed = true
            dirs = emptyList()
        }
        loading = false
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
                    .width(640.dp)
                    .height(660.dp)
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(28.dp)
            ) {
                Text(
                    text = stringResource(R.string.netdisk_dir_picker_title),
                    color = NasMusicColors.TextPrimary,
                    fontSize = 23.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = currentPath,
                    color = NasMusicColors.Primary,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 返回上级（始终可见，不受目录加载/空状态影响）
                if (currentPath != "/" && currentPath.isNotBlank()) {
                    DirRow(
                        label = stringResource(R.string.netdisk_dir_picker_up),
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = { currentPath = parentPath(currentPath) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 目录列表区（弹性高度，内部滚动，保证底部按钮始终可见）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.netdisk_dir_picker_loading),
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 18.sp
                                )
                            }
                        }
                        failed -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = stringResource(R.string.netdisk_dir_picker_error),
                                    color = NasMusicColors.Warning,
                                    fontSize = 18.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                FocusableSurface(
                                    onClick = { loadKey++ },
                                    modifier = Modifier
                                        .width(160.dp)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    focusedScale = 1.08f,
                                    animationDurationMs = 120,
                                    containerColor = NasMusicColors.SurfaceVariant,
                                    focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                                    contentColor = NasMusicColors.TextPrimary,
                                    focusedContentColor = Color.Black
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(text = stringResource(R.string.netdisk_dir_picker_retry), fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                        dirs.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.netdisk_dir_picker_empty),
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 18.sp
                                )
                            }
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(dirs) { dir ->
                                    DirRow(
                                        label = dir.serverFilename,
                                        icon = Icons.Default.Folder,
                                        onClick = { currentPath = childPath(currentPath, dir.serverFilename) }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusableSurface(
                        onClick = { onConfirm(currentPath) },
                        modifier = Modifier
                            .width(200.dp)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 120,
                        containerColor = NasMusicColors.SurfaceVariant,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = Color.Black
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(text = stringResource(R.string.netdisk_dir_picker_select), fontSize = 19.sp)
                        }
                    }
                    FocusableSurface(
                        onClick = onDismiss,
                        modifier = Modifier
                            .width(140.dp)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 120,
                        containerColor = NasMusicColors.SurfaceVariant,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = Color.Black
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Text(text = stringResource(R.string.common_cancel), fontSize = 19.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 目录行（D-Pad 可聚焦） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DirRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = NasMusicColors.Primary, modifier = Modifier.width(22.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                color = NasMusicColors.TextPrimary,
                fontSize = 19.sp
            )
        }
    }
}

/** 上级路径："/音乐/周杰伦" → "/音乐"；已是根目录返回 "/" */
internal fun parentPath(path: String): String =
    path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }

/** 子路径："/音乐" + "周杰伦" → "/音乐/周杰伦"；根目录 + "音乐" → "/音乐" */
internal fun childPath(path: String, name: String): String =
    path.trimEnd('/') + "/" + name