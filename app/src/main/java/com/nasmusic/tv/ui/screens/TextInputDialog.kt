package com.nasmusic.tv.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

// 键盘行定义 —— 26个字母按ABC顺序排列，大小写通过Shift键切换
// 小写：a-j, k-t, u-z + 符号
private val keyboardRowsLower = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"),
    listOf("k", "l", "m", "n", "o", "p", "q", "r", "s", "t"),
    listOf("u", "v", "w", "x", "y", "z", ".", "/", "-", ":"),
)
// 大写：A-J, K-T, U-Z + 符号
private val keyboardRowsUpper = listOf(
    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
    listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J"),
    listOf("K", "L", "M", "N", "O", "P", "Q", "R", "S", "T"),
    listOf("U", "V", "W", "X", "Y", "Z", ".", "/", "-", ":"),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    masked: Boolean = false,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(initialValue) }
    var isUpperCase by remember { mutableStateOf(false) }

    // 注册 BACK 键回调：当对话框打开时，按 BACK 键会关闭对话框
    val backHandler = com.nasmusic.tv.ui.LocalDialogBackHandler.current
    DisposableEffect(onDismiss) {
        backHandler.value = { onDismiss() }
        onDispose {
            backHandler.value = null
        }
    }

    // 焦点管理：弹窗打开时，焦点聚焦在"确认"按钮上
    val confirmFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            confirmFocusRequester.requestFocus()
        } catch (_: Exception) {
            // 忽略焦点请求异常
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB3000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            Text(
                text = title,
                color = NasMusicColors.TextPrimary,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 文本显示框（更紧凑）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        NasMusicColors.SurfaceVariant,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = if (text.isEmpty()) hint
                           else if (masked) "*".repeat(text.length)
                           else text,
                    color = if (text.isEmpty()) NasMusicColors.TextSecondary
                            else NasMusicColors.TextPrimary,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 键盘 —— 键位更小
            val currentRows = if (isUpperCase) keyboardRowsUpper else keyboardRowsLower
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                currentRows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                    ) {
                        row.forEach { ch ->
                            KeyButton(label = ch, onClick = { text += ch })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 底部功能行：Shift切换 / @ / 空格 / 删除 / 清除 / 取消 / 确认
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    ActionButton(
                        label = if (isUpperCase) "shift↓" else "SHIFT↑",
                        onClick = { isUpperCase = !isUpperCase },
                        width = 80.dp,
                        color = if (isUpperCase) NasMusicColors.Primary.copy(alpha = 0.4f) else NasMusicColors.SurfaceVariant
                    )
                    ActionButton(label = "@", onClick = { text += "@" }, width = 40.dp)
                    ActionButton(
                        label = "空格",
                        onClick = { text += " " },
                        width = 130.dp
                    )
                    ActionButton(
                        label = "删除",
                        onClick = { if (text.isNotEmpty()) text = text.dropLast(1) },
                        width = 80.dp,
                        color = NasMusicColors.Warning
                    )
                    ActionButton(
                        label = "清除",
                        onClick = { text = "" },
                        width = 80.dp,
                        color = NasMusicColors.Warning
                    )
                    ActionButton(
                        label = "取消",
                        onClick = { onDismiss() },
                        width = 80.dp,
                        color = NasMusicColors.SurfaceVariant
                    )
                    ActionButton(
                        label = "确认",
                        onClick = { onConfirm(text) },
                        width = 100.dp,
                        color = NasMusicColors.Primary,
                        isPrimary = true,
                        focusRequester = confirmFocusRequester
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(if (isFocused) 1.12f else 1f, tween(120))
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NasMusicColors.SurfaceVariant,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
            focusedContentColor = NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.92f
        )
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(vertical = 10.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    width: Dp,
    color: Color = NasMusicColors.SurfaceVariant,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .height(44.dp)
            .scale(animScale.value)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(if (isFocused) 1.1f else 1f, tween(120))
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(8.dp),
            focusedShape = RoundedCornerShape(8.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            contentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
            focusedContainerColor = if (isPrimary) NasMusicColors.Primary.copy(alpha = 0.85f)
                                    else NasMusicColors.Primary.copy(alpha = 0.25f),
            focusedContentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.92f
        )
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
        )
    }
}
