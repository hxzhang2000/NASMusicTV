package com.nasmusic.tv.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.SearchHistoryItem
import com.nasmusic.tv.net.LocalInputServer
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.responsiveDialogSize
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode
import com.nasmusic.tv.util.NetworkUtils
import com.nasmusic.tv.util.QrCodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 键盘行定义 -- 26个字母按ABC顺序排列，大小写通过Shift键切换
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

/**
 * 自定义键盘输入对话框
 *
 * 全 app 共享的文本输入组件。支持：
 * - 自制 D-Pad 键盘（字母/符号/大小写切换）
 * - 系统 IME 模式（需设备安装中文输入法）
 * - 二维码扫码输入（[showQrCode]=true 时右侧显示 QR，手机扫码后浏览器输入文字推送到输入框）
 * - 搜索历史建议（[showHistory]=true 时输入框下方显示最近/热门搜索，D-Pad 选择后直接搜索）
 *
 * @param showQrCode 是否显示二维码扫码面板（仅搜索入口启用）
 * @param showHistory 是否显示搜索历史建议（仅搜索入口启用）
 * @param historyItems 搜索历史列表（按 lastSearchedAt 降序）
 * @param onHistorySelect 历史项被选中时的回调（触发搜索；调用方负责关闭弹窗。
 *   选中后弹窗立即销毁，对话框内 `text` 状态不再可见，故不在此写入）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    masked: Boolean = false,
    showQrCode: Boolean = true,
    showHistory: Boolean = false,
    historyItems: List<SearchHistoryItem> = emptyList(),
    onHistorySelect: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    var isUpperCase by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 手机端：强制使用系统 IME（不显示自定义键盘、不启动 QR）；TV 端保持原样
    // 与 MainActivity 一致，同时检查 leanback 和 television 特性
    val isTVDevice = context.packageManager.hasSystemFeature("android.software.leanback") ||
            context.packageManager.hasSystemFeature("android.hardware.type.television")
    // 是否切换到系统 IME 输入模式
    var showSystemIme by remember {
        mutableStateOf(!isTVDevice)  // 手机端默认系统 IME，TV 端默认自定义键盘
    }
    // 二维码扫码：仅 TV 启用（手机端直接触摸系统键盘输入）
    val effectiveShowQrCode = if (isTVDevice) showQrCode else false
    val effectiveShowHistory = showHistory  // 搜索历史手机端也可用
    // IME 不可用时的提示消息（null 表示无提示）
    var imeUnavailableMsg by remember { mutableStateOf<String?>(null) }

    // QR 扫码输入状态
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    var qrText by remember { mutableStateOf<String?>(null) }
    val server = remember { LocalInputServer() }
    val qrScope = rememberCoroutineScope()

    val keyboardController = LocalSoftwareKeyboardController.current
    val textFieldFocusRequester = remember { FocusRequester() }

    // 焦点管理：弹窗打开时，焦点聚焦在"确认"按钮上
    val confirmFocusRequester = remember { FocusRequester() }
    // IME 模式下"返回键盘"按钮的焦点
    val backToKeyboardFocusRequester = remember { FocusRequester() }

    // 启动/停止本地输入服务器（仅 TV 启用）
    DisposableEffect(effectiveShowQrCode) {
        if (effectiveShowQrCode) {
            val ip = NetworkUtils.getLocalIpAddress()
            if (ip != null) {
                val url = "http://$ip:${LocalInputServer.DEFAULT_PORT}/"
                serverUrl = url
                // P3：QR 位图改到后台线程生成——ZXing 编码 + 位图分配在组合期同步跑会掉帧
                qrScope.launch(Dispatchers.Default) {
                    val bmp = QrCodeGenerator.generateQrBitmap(url, 360)
                    withContext(Dispatchers.Main) { qrBitmap = bmp }
                }
                server.start { received ->
                    // NanoHTTPD 后台线程回调，mutableStateOf 支持跨线程写入
                    qrText = received
                }
            }
        }
        onDispose {
            server.stop()
        }
    }

    // 手机提交的文字 -> 填入输入框
    LaunchedEffect(qrText) {
        qrText?.let {
            text = it
            qrText = null
        }
    }

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

        // v2.36.0 竖屏（方案 §2.4 / P1-27）：940/720dp 双栏在 360dp 屏上必溢出 → 竖屏改单栏纵排
        // v2.36.2：判定上提到 Dialog 之前，供 BoxWithConstraints 的 imePadding 使用
        val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000))
                // v2.36.2 竖屏：系统键盘弹出时把内容顶到键盘上方，避免「确认/取消」被键盘压住。
                // 若系统已按 IME 缩小了本 Dialog 的窗口（`DialogProperties.decorFitsSystemWindows`
                // 默认为 true 的那条路径），此时 `WindowInsets.ime` 恒为 0 → 本行是 no-op；
                // 只有窗口未被缩小时才真正生效 —— 两种情况不会叠加，故无条件加在竖屏上是安全的。
                .then(if (isPhonePortrait) Modifier.imePadding() else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val showQrPanel = effectiveShowQrCode && qrBitmap != null && serverUrl != null
            Column(
                modifier = Modifier
                    .then(responsiveDialogSize(if (showQrPanel) 940.dp else 720.dp))
                    .heightIn(max = maxHeight - 32.dp)
                    .verticalScroll(rememberScrollState())
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textInputMainColumn: @Composable (Modifier) -> Unit = { m ->
                    // ===== 左侧：标题 + 输入框 + 历史 + 键盘 =====
                    Column(
                        modifier = m,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 标题
                        Text(
                            text = title,
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.subtitle()
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
                                    // 52dp 在竖屏只有 42.6 物理 dp ❌ → §2.7 换算抬到 56dp
                                    .height(portraitTouchTarget(52.dp))
                                    .background(
                                        NasMusicColors.SurfaceVariant,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .focusRequester(textFieldFocusRequester)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                textStyle = TextStyle(
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = FontSize.button()
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
                                                fontSize = FontSize.button()
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
                                    // 52dp 在竖屏只有 42.6 物理 dp ❌ → §2.7 换算抬到 56dp
                                    .height(portraitTouchTarget(52.dp))
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
                                    fontSize = FontSize.button(),
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
                                fontSize = FontSize.body()
                            )
                        }

                        // 搜索历史建议
                        if (showHistory && historyItems.isNotEmpty()) {
                            val recent = remember(historyItems) {
                                historyItems.sortedByDescending { it.lastSearchedAt }.take(5)
                            }
                            val top = remember(historyItems) {
                                historyItems.sortedByDescending { it.count }.take(5)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (recent.isNotEmpty()) {
                                    HistoryRow(
                                        label = stringResource(R.string.text_input_history_recent),
                                        items = recent,
                                        wrap = isPhonePortrait
                                    ) { query ->
                                        onHistorySelect?.invoke(query)
                                    }
                                }
                                if (top.isNotEmpty()) {
                                    HistoryRow(
                                        label = stringResource(R.string.text_input_history_top),
                                        items = top,
                                        wrap = isPhonePortrait
                                    ) { query ->
                                        onHistorySelect?.invoke(query)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (showSystemIme) {
                            // ===== 系统 IME 模式：显示返回键盘 + 操作按钮 =====
                            // v2.36.2：竖屏换行 —— 四个按钮固定宽合计 426dp，竖屏可用宽仅 ~291dp，
                            // 用 Row 会把「返回键盘」「确认」直接裁到屏幕外（真机反馈）
                            WrapButtonRow(wrap = isPhonePortrait, spacing = 6.dp) {
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
                                    width = 84.dp,
                                    color = NasMusicColors.Warning
                                )
                                ActionButton(
                                    label = stringResource(R.string.common_cancel),
                                    onClick = { onDismiss() },
                                    width = 84.dp,
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
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                currentRows.forEach { row ->
                                    // v2.36.2：竖屏换行 —— 每行 10 个 56dp 键 + 9×6 间距 = 614dp，
                                    // 竖屏可用宽仅 ~291dp（只放得下 4 个键），用 Row 会裁掉右侧 6 个键。
                                    // 按逻辑分组保留：数字行 / a-j / k-t / u-z+符号 各自内部换行。
                                    WrapButtonRow(wrap = isPhonePortrait, spacing = 6.dp) {
                                        row.forEach { ch ->
                                            KeyButton(label = ch, onClick = { text += ch })
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // 底部功能行：Shift切换 / 中文输入 / @ / 空格 / 删除 / 清除 / 取消 / 确认
                                // v2.36.2：竖屏换行 —— 八个按钮固定宽合计 688dp，竖屏必然溢出（同上）
                                WrapButtonRow(wrap = isPhonePortrait, spacing = 4.dp) {
                                    ActionButton(
                                        label = if (isUpperCase) "shift↓" else "SHIFT↑",
                                        onClick = { isUpperCase = !isUpperCase },
                                        width = 80.dp,
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
                                        width = 84.dp,
                                        color = NasMusicColors.Primary.copy(alpha = 0.2f)
                                    )
                                    ActionButton(label = "@", onClick = { text += "@" }, width = 44.dp)
                                    ActionButton(
                                        label = stringResource(R.string.text_input_space),
                                        onClick = { text += " " },
                                        width = 116.dp
                                    )
                                    ActionButton(
                                        label = stringResource(R.string.common_delete),
                                        onClick = { if (text.isNotEmpty()) text = text.dropLast(1) },
                                        width = 80.dp,
                                        color = NasMusicColors.Warning
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
                                        width = 96.dp,
                                        color = NasMusicColors.Primary,
                                        isPrimary = true,
                                        focusRequester = confirmFocusRequester,
                                        requestFocusOnLaunch = true
                                    )
                                }
                            }
                        }
                    } // end 左侧 Column
                } // end textInputMainColumn

                // ===== 右侧：QR 扫码面板 =====
                val textInputQrPanel: @Composable (Modifier) -> Unit = { m ->
                    Column(
                        modifier = m,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Image(
                            bitmap = qrBitmap?.asImageBitmap() ?: return@Column,
                            contentDescription = stringResource(R.string.text_input_scan_code),
                            modifier = Modifier.size(180.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.text_input_scan_phone_hint),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.text_input_phone_search),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                    }
                }

                if (isPhonePortrait) {
                    // 竖屏：单栏纵排，QR 面板下移
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        textInputMainColumn(Modifier.fillMaxWidth())
                        if (showQrPanel) {
                            Spacer(modifier = Modifier.height(16.dp))
                            textInputQrPanel(Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Row {
                        textInputMainColumn(Modifier.width(720.dp))
                        if (showQrPanel) {
                            Spacer(modifier = Modifier.width(20.dp))
                            textInputQrPanel(Modifier.width(180.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索历史行（最近 / 热门）
 *
 * 每行一个标签 + 最多 5 个可聚焦的历史项，D-Pad 选中后触发 [onSelect] 回调
 * （调用方负责执行搜索并关闭弹窗）。
 *
 * @param wrap v2.36.2：是否允许换行。竖屏下「标签 28dp + 5 × 120dp 历史项」合计 ≈ 630dp，
 *   而竖屏对话框可用宽只有 ~291dp —— 用 [Row] 会把右侧 3~4 个历史项直接裁掉
 *   （真机反馈「文字输入窗口展示不完全」），故竖屏改 [FlowRow]。
 *   `false` 时仍是 [Row]，与改动前**逐字等价**（B1）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    label: String,
    items: List<SearchHistoryItem>,
    wrap: Boolean,
    onSelect: (String) -> Unit
) {
    val labelSlot: @Composable () -> Unit = {
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.body(),
            modifier = Modifier.width(28.dp)
        )
    }
    val itemSlots: @Composable () -> Unit = {
        items.forEach { item ->
            FocusableSurface(
                onClick = { onSelect(item.query) },
                modifier = Modifier
                    .width(120.dp)
                    // 32dp 在竖屏只有 ≈26 物理 dp ❌ → §2.7 换算抬到 56dp
                    // （TV / 手机横屏原样返回 32dp，B1）
                    .height(portraitTouchTarget(32.dp)),
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1.05f,
                animationDurationMs = 100,
                containerColor = NasMusicColors.SurfaceVariant.copy(alpha = 0.5f),
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                contentColor = NasMusicColors.TextPrimary,
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.95f
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = item.query,
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.body(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }
        }
    }
    if (wrap) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            labelSlot()
            itemSlots()
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            labelSlot()
            itemSlots()
        }
    }
}

/**
 * 按钮组容器：宽度不够时是否自动换行（v2.36.2）。
 *
 * ⚠️ **为什么竖屏必须换行**：竖屏对话框可用宽度只有 `0.92 × 屏宽 − 40dp`
 * （360dp 屏 ≈ 291dp），而本对话框的两行按钮是**固定宽度**的：
 * - 系统 IME 模式操作行 = `140 + 84 + 84 + 100` + 3×6 = **426dp**
 * - 自制键盘底部功能行 = `80+84+44+116+80+80+80+96` + 7×4 = **688dp**
 * 用 [Row] 时 `Arrangement` 只能把整组居中，首尾按钮会被推到屏幕外**直接裁掉**。
 * [FlowRow] 会在超宽时自动换到下一行。
 *
 * - `wrap = false`（TV / 手机横屏）：仍是 [Row]，与改动前**逐字等价**（B1）
 * - `wrap = true`（手机竖屏）：[FlowRow]
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrapButtonRow(
    wrap: Boolean,
    spacing: Dp,
    content: @Composable () -> Unit
) {
    if (wrap) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) { content() }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
        ) { content() }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.10f,
        animationDurationMs = 120,
        containerColor = NasMusicColors.SurfaceVariant,
        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f
    ) {
        Text(
            text = label,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.button(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(vertical = 14.dp)
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
            // 52dp 在竖屏只有 42.6 物理 dp ❌ → §2.7 换算抬到 56dp（TV/横屏保持 52dp，B1）
            .height(portraitTouchTarget(52.dp)),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1.08f,
        animationDurationMs = 120,
        containerColor = color,
        focusedContainerColor = if (isPrimary) NasMusicColors.Primary.copy(alpha = 0.85f)
                                else NasMusicColors.Primary.copy(alpha = 0.25f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f,
        focusRequester = focusRequester,
        requestFocusOnLaunch = requestFocusOnLaunch
    ) {
        Text(
            text = label,
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.body(),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(vertical = 9.dp, horizontal = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
