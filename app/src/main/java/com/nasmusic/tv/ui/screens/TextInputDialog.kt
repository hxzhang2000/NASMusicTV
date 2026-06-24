package com.nasmusic.tv.ui.screens

import android.content.Context
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors

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

/**
 * 检测系统是否有可用的输入法（IME）
 *
 * 用于判断"中文输入"按钮是否可用。Android TV 出厂可能只带英文 IME，
 * 用户需自行安装中文 IME（如搜狗输入法 TV 版）才能使用中文输入。
 *
 * @return true 表示系统至少有一个可用 IME
 */
fun hasAvailableIme(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    return imm?.enabledInputMethodList?.isNotEmpty() == true
}

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
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    var isUpperCase by remember { mutableStateOf(false) }
    // 是否切换到系统 IME 输入模式
    var showSystemIme by remember { mutableStateOf(false) }
    // IME 不可用时的提示消息（null 表示无提示）
    var imeUnavailableMsg by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val textFieldFocusRequester = remember { FocusRequester() }

    // 焦点管理：弹窗打开时，焦点聚焦在"确认"按钮上
    val confirmFocusRequester = remember { FocusRequester() }
    // IME 模式下"返回键盘"按钮的焦点
    val backToKeyboardFocusRequester = remember { FocusRequester() }

    // 切换到 IME 模式时，请求 TextField 焦点并弹出系统输入法
    LaunchedEffect(showSystemIme) {
        if (showSystemIme) {
            textFieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // 使用 Dialog 确保显示在系统级窗口层，不被搜索结果列表等下层内容覆盖
    Dialog(
        onDismissRequest = {
            // Dialog 的 onDismissRequest 不会触发（dismissOnBackPress=false），
            // BACK 键由内部 BackHandler 处理
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        // 在 Dialog 内部注册 BackHandler，处理 BACK 键
        // - 系统 IME 模式：BACK 先隐藏 IME，返回自制键盘
        // - 自制键盘模式：BACK 关闭对话框
        BackHandler {
            if (showSystemIme) {
                keyboardController?.hide()
                showSystemIme = false
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

            // 文本显示框 / IME 输入框
            if (showSystemIme) {
                // 系统 IME 模式：使用 BasicTextField 触发系统输入法
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(
                            NasMusicColors.SurfaceVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .focusRequester(textFieldFocusRequester)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    textStyle = TextStyle(
                        color = NasMusicColors.TextPrimary,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(NasMusicColors.Primary),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = hint,
                                    color = NasMusicColors.TextSecondary,
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            } else {
                // 自制键盘模式：只读显示框
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
            }

            // IME 不可用提示
            if (imeUnavailableMsg != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = imeUnavailableMsg!!,
                    color = NasMusicColors.Warning,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (showSystemIme) {
                // ===== 系统 IME 模式：显示返回键盘 + 操作按钮 =====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    ActionButton(
                        label = stringResource(R.string.text_input_back_keyboard),
                        onClick = {
                            keyboardController?.hide()
                            showSystemIme = false
                        },
                        width = 140.dp,
                        color = NasMusicColors.SurfaceVariant,
                        focusRequester = backToKeyboardFocusRequester,
                        requestFocusOnLaunch = true
                    )
                    ActionButton(
                        label = stringResource(R.string.common_clear),
                        onClick = { text = "" },
                        width = 80.dp,
                        color = NasMusicColors.Warning
                    )
                    ActionButton(
                        label = stringResource(R.string.common_cancel),
                        onClick = { onDismiss() },
                        width = 80.dp,
                        color = NasMusicColors.SurfaceVariant
                    )
                    ActionButton(
                        label = stringResource(R.string.common_confirm),
                        onClick = { onConfirm(text) },
                        width = 100.dp,
                        color = NasMusicColors.Primary,
                        isPrimary = true,
                        focusRequester = confirmFocusRequester
                    )
                }
            } else {
                // ===== 自制键盘模式：原有键盘 + 中文输入按钮 =====
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

                    // 底部功能行：Shift切换 / 中文输入 / @ / 空格 / 删除 / 清除 / 取消 / 确认
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        ActionButton(
                            label = if (isUpperCase) "shift↓" else "SHIFT↑",
                            onClick = { isUpperCase = !isUpperCase },
                            width = 70.dp,
                            color = if (isUpperCase) NasMusicColors.Primary.copy(alpha = 0.4f) else NasMusicColors.SurfaceVariant
                        )
                        // 中文输入按钮：切换到系统 IME 模式
                        ActionButton(
                            label = stringResource(R.string.text_input_chinese),
                            onClick = {
                                if (hasAvailableIme(context)) {
                                    imeUnavailableMsg = null
                                    showSystemIme = true
                                } else {
                                    imeUnavailableMsg = context.getString(R.string.text_input_no_ime)
                                }
                            },
                            width = 80.dp,
                            color = NasMusicColors.Primary.copy(alpha = 0.2f)
                        )
                        ActionButton(label = "@", onClick = { text += "@" }, width = 40.dp)
                        ActionButton(
                            label = stringResource(R.string.text_input_space),
                            onClick = { text += " " },
                            width = 110.dp
                        )
                        ActionButton(
                            label = stringResource(R.string.common_delete),
                            onClick = { if (text.isNotEmpty()) text = text.dropLast(1) },
                            width = 70.dp,
                            color = NasMusicColors.Warning
                        )
                        ActionButton(
                            label = stringResource(R.string.common_clear),
                            onClick = { text = "" },
                            width = 70.dp,
                            color = NasMusicColors.Warning
                        )
                        ActionButton(
                            label = stringResource(R.string.common_cancel),
                            onClick = { onDismiss() },
                            width = 70.dp,
                            color = NasMusicColors.SurfaceVariant
                        )
                        ActionButton(
                            label = stringResource(R.string.common_confirm),
                            onClick = { onConfirm(text) },
                            width = 90.dp,
                            color = NasMusicColors.Primary,
                            isPrimary = true,
                            focusRequester = confirmFocusRequester,
                            requestFocusOnLaunch = true
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.12f,
        animationDurationMs = 120,
        containerColor = NasMusicColors.SurfaceVariant,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    width: Dp,
    color: Color = NasMusicColors.SurfaceVariant,
    isPrimary: Boolean = false,
    focusRequester: FocusRequester? = null,
    requestFocusOnLaunch: Boolean = false
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .height(44.dp),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.1f,
        animationDurationMs = 120,
        containerColor = color,
        focusedContainerColor = if (isPrimary) NasMusicColors.Primary.copy(alpha = 0.85f)
                                else NasMusicColors.Primary.copy(alpha = 0.25f),
        contentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
        focusedContentColor = if (isPrimary) Color.Black else NasMusicColors.TextPrimary,
        pressedScale = 0.92f,
        focusRequester = focusRequester,
        requestFocusOnLaunch = requestFocusOnLaunch
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
        )
    }
}
