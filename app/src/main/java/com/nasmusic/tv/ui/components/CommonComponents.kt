package com.nasmusic.tv.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 公共返回按钮组件
 * 带焦点动画的返回按钮，供各详情屏幕复用
 */
@Composable
fun BackButton(onClick: () -> Unit) {
    FocusableSurface(
        onClick = onClick,
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
            Text(text = "←", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
            Text(text = stringResource(R.string.common_back), fontSize = 14.sp)
        }
    }
}

