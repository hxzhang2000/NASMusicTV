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
import com.nasmusic.tv.ui.theme.FontSize
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
            Text(text = stringResource(R.string.common_back_arrow), color = NasMusicColors.TextPrimary, fontSize = FontSize.button(), modifier = Modifier.padding(end = 6.dp))
            Text(text = stringResource(R.string.common_back), color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
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
                fontSize = FontSize.body(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotBlank()) {
                FocusableSurface(
                    onClick = onClear,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("✕", color = NasMusicColors.TextPrimary, fontSize = FontSize.body(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

/**
 * 歌曲列表列数：TV 两列、手机单列（一行一个歌曲条目）
 *
 * 始终返回 1 列：歌曲条目信息较多（标题+艺术家+专辑+时长+操作按钮），
 * 两列布局下歌名被压缩不可读。统一用一列让 SongRow 占满整行。
 *
 * 注意："我的"页面不使用此函数，其 TV/手机版式差异保持不变。
 */
@Composable
fun songGridColumns(): GridCells = GridCells.Fixed(1)