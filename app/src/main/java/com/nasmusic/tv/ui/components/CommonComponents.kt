package com.nasmusic.tv.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.theme.LocalPhoneCompact
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 公共返回按钮组件
 * 带焦点动画的返回按钮，供各详情屏幕复用
 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.Primary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.common_back_arrow), color = NasMusicColors.TextPrimary, fontSize = 19.sp, modifier = Modifier.padding(end = 6.dp))
            Text(text = stringResource(R.string.common_back), color = NasMusicColors.TextPrimary, fontSize = 19.sp)
        }
    }
}

/**
 * 公共搜索框组件（统一样式：胶囊形、无独立搜索按钮）
 *
 * 点击整个搜索框触发 [onOpenSearch]（调用方负责弹出输入对话框）；
 * 已有搜索词时框内显示 ✕ 清除按钮，点击触发 [onClear]。
 * 宽度由调用方通过 modifier 指定，高度统一 48dp。
 */
@Composable
fun SearchField(
    query: String,
    placeholder: String,
    onOpenSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    FocusableSurface(
        onClick = onOpenSearch,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(24.dp),
        containerColor = NasMusicColors.Surface,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = NasMusicColors.TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (query.isBlank()) placeholder else query,
                color = if (query.isBlank()) NasMusicColors.TextSecondary else NasMusicColors.TextPrimary,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotBlank()) {
                FocusableSurface(
                    onClick = onClear,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("✕", color = NasMusicColors.TextSecondary, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/**
 * 歌曲列表列数：TV 两列、手机单列（一行一个歌曲条目）
 *
 * 基于 [LocalPhoneCompact]（MainActivity 按设备类型提供）判定：
 * - TV（leanback）：2 列，维持现有排布
 * - 手机：1 列，SongRow 占满整行
 */
@Composable
fun songGridColumns(): GridCells =
    if (LocalPhoneCompact.current) GridCells.Fixed(1)
    else GridCells.Fixed(2)