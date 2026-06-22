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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.BackendRegistry
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlinx.coroutines.launch

private enum class InputField {
    BASE_URL, USERNAME, PASSWORD, API_TOKEN
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ServerConnectScreen(
    initialConfig: ServerConfig,
    isConnected: Boolean,
    serverDisplayName: String,
    isConnecting: Boolean,
    onConnect: (ServerConfig) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var backendType by remember { mutableStateOf(initialConfig.backendType) }
    var baseUrl by remember {
        mutableStateOf(
            if (initialConfig.baseUrl.isNotBlank()) TextFieldValue(initialConfig.baseUrl)
            else TextFieldValue("http://")
        )
    }
    var username by remember {
        mutableStateOf(
            if (initialConfig.username.isNotBlank()) TextFieldValue(initialConfig.username)
            else TextFieldValue("")
        )
    }
    var password by remember {
        mutableStateOf(
            if (initialConfig.password.isNotBlank()) TextFieldValue(initialConfig.password)
            else TextFieldValue("")
        )
    }
    var apiToken by remember {
        mutableStateOf(
            if (initialConfig.apiToken.isNotBlank()) TextFieldValue(initialConfig.apiToken)
            else TextFieldValue()
        )
    }
    var statusMessage by remember { mutableStateOf("") }
    var activeInputField by remember { mutableStateOf<InputField?>(null) }

    // 连接测试状态
    var isTesting by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("") }  // "" | "success:xxx" | "error:xxx"
    val testScope = rememberCoroutineScope()
    val appContext = LocalContext.current
    val backendRegistry = remember { (appContext.applicationContext as NasMusicApp).backendRegistry }
    val context = LocalContext.current

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 900.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(NasMusicColors.Surface)
                .verticalScroll(rememberScrollState())
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部服务器图标
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        NasMusicColors.Primary,
                        RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.server_config_title),
                color = NasMusicColors.TextPrimary,
                fontSize = 36.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.server_connect_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            // 已连接状态
            if (isConnected) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            NasMusicColors.Primary.copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态指示点
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                NasMusicColors.Primary,
                                RoundedCornerShape(6.dp)
                            )
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_connected),
                            color = NasMusicColors.Primary,
                            fontSize = 18.sp
                        )
                        if (serverDisplayName.isNotBlank()) {
                            Text(
                                text = serverDisplayName,
                                color = NasMusicColors.TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                    // 断开按钮
                    FocusableSurface(
                        onClick = {
                            onDisconnect()
                            statusMessage = context.getString(R.string.server_disconnected)
                        },
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Danger,
                        contentColor = Color.White,
                        focusedContainerColor = NasMusicColors.Danger.copy(alpha = 0.85f),
                        focusedContentColor = Color.White,
                        pressedScale = 0.95f
                    ) {
                        Text(
                            text = stringResource(R.string.server_disconnect),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 服务器类型选择
            Text(
                text = stringResource(R.string.server_type_label),
                color = NasMusicColors.TextPrimary,
                fontSize = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TypeCard(
                    text = "Jellyfin",
                    selected = backendType == ServerConfig.TYPE_JELLYFIN,
                    onClick = { backendType = ServerConfig.TYPE_JELLYFIN },
                    modifier = Modifier.weight(1f)
                )
                TypeCard(
                    text = "Navidrome",
                    selected = backendType == ServerConfig.TYPE_NAVIDROME,
                    onClick = { backendType = ServerConfig.TYPE_NAVIDROME },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 表单字段 - 服务器地址（带测试按钮）
            ServerAddressField(
                baseUrl = baseUrl,
                onBaseUrlChange = { baseUrl = it; testStatus = "" },
                onOpenKeyboard = { activeInputField = InputField.BASE_URL },
                isTesting = isTesting,
                testStatus = testStatus,
                onTestClick = {
                    if (baseUrl.text.isBlank()) {
                        testStatus = "error:请先填写服务器地址"
                    } else {
                        isTesting = true
                        testStatus = ""
                        testScope.launch {
                            val config = ServerConfig(
                                backendType = backendType,
                                baseUrl = baseUrl.text.trim().removeSuffix("/"),
                                apiToken = apiToken.text.trim(),
                                username = username.text.trim(),
                                password = password.text.trim()
                            )
                            val (success, message) = backendRegistry.testConnection(config)
                            testStatus = if (success) "success:$message" else "error:$message"
                            isTesting = false
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (backendType == ServerConfig.TYPE_JELLYFIN) {
                FormField(
                    label = stringResource(R.string.server_username),
                    hint = stringResource(R.string.server_username_hint),
                    value = username,
                    onValueChange = { username = it },
                    onOpen = { activeInputField = InputField.USERNAME }
                )
                Spacer(modifier = Modifier.height(16.dp))
                FormField(
                    label = stringResource(R.string.server_password),
                    hint = stringResource(R.string.server_password_hint),
                    value = password,
                    onValueChange = { password = it },
                    masked = true,
                    onOpen = { activeInputField = InputField.PASSWORD }
                )
            } else {
                FormField(
                    label = stringResource(R.string.server_username),
                    hint = stringResource(R.string.server_navidrome_username_hint),
                    value = username,
                    onValueChange = { username = it },
                    onOpen = { activeInputField = InputField.USERNAME }
                )
                Spacer(modifier = Modifier.height(16.dp))
                FormField(
                    label = stringResource(R.string.server_password),
                    hint = stringResource(R.string.server_navidrome_password_hint),
                    value = password,
                    onValueChange = { password = it },
                    masked = true,
                    onOpen = { activeInputField = InputField.PASSWORD }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    color = NasMusicColors.TextSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // 连接按钮
            FocusableSurface(
                onClick = {
                    if (baseUrl.text.isBlank()) {
                        statusMessage = context.getString(R.string.server_address_required)
                    } else {
                        statusMessage = ""
                        val config = ServerConfig(
                            backendType = backendType,
                            baseUrl = baseUrl.text.trim().removeSuffix("/"),
                            apiToken = apiToken.text.trim(),
                            username = username.text.trim(),
                            password = password.text.trim()
                        )
                        onConnect(config)
                    }
                },
                shape = RoundedCornerShape(14.dp),
                focusedScale = 1.08f,
                animationDurationMs = 150,
                containerColor = NasMusicColors.Primary,
                contentColor = Color.Black,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.85f),
                focusedContentColor = Color.Black,
                pressedScale = 0.95f
            ) {
                Text(
                    text = if (isConnecting) stringResource(R.string.server_connecting) else stringResource(R.string.server_connect_action),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.server_privacy_notice),
                color = NasMusicColors.TextSecondary,
                fontSize = 13.sp
            )
        }

        // 文本输入对话框
        if (activeInputField != null) {
            val dialogTitle: String
            val dialogHint: String
            val dialogValue: String
            val dialogMasked: Boolean
            when (activeInputField) {
                InputField.BASE_URL -> {
                    dialogTitle = stringResource(R.string.server_address_dialog_title)
                    dialogHint = "https://jellyfin.example.com 或 http://192.168.1.100:8096"
                    dialogValue = baseUrl.text
                    dialogMasked = false
                }
                InputField.USERNAME -> {
                    dialogTitle = stringResource(R.string.server_username_dialog_title)
                    dialogHint = stringResource(R.string.server_username_dialog_hint)
                    dialogValue = username.text
                    dialogMasked = false
                }
                InputField.PASSWORD -> {
                    dialogTitle = stringResource(R.string.server_password_dialog_title)
                    dialogHint = stringResource(R.string.server_password_dialog_hint)
                    dialogValue = password.text
                    dialogMasked = true
                }
                InputField.API_TOKEN -> {
                    dialogTitle = stringResource(R.string.server_token_dialog_title)
                    dialogHint = stringResource(R.string.server_token_dialog_hint)
                    dialogValue = apiToken.text
                    dialogMasked = true
                }
                else -> {
                    dialogTitle = ""
                    dialogHint = ""
                    dialogValue = ""
                    dialogMasked = false
                }
            }
            TextInputDialog(
                title = dialogTitle,
                hint = dialogHint,
                initialValue = dialogValue,
                masked = dialogMasked,
                onConfirm = { newText ->
                    when (activeInputField) {
                        InputField.BASE_URL -> baseUrl = TextFieldValue(newText)
                        InputField.USERNAME -> username = TextFieldValue(newText)
                        InputField.PASSWORD -> password = TextFieldValue(newText)
                        InputField.API_TOKEN -> apiToken = TextFieldValue(newText)
                        else -> {}
                    }
                    activeInputField = null
                },
                onDismiss = { activeInputField = null }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypeCard(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Surface(
        onClick = onClick,
        modifier = modifier
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else if (selected) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing
                        else if (selected) NasMusicColors.Primary
                        else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(if (isFocused) 1.08f else 1f, tween(150))
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp),
            focusedShape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,
            contentColor = if (selected) Color.Black else NasMusicColors.TextPrimary,
            focusedContainerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Primary.copy(alpha = 0.3f),
            focusedContentColor = if (selected) Color.Black else NasMusicColors.TextPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.96f
        )
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormField(
    label: String,
    hint: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onOpen: () -> Unit = {},
    masked: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = NasMusicColors.TextPrimary,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        FocusableSurface(
            onClick = { onOpen() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            focusedScale = 1.05f,
            animationDurationMs = 150,
            containerColor = NasMusicColors.SurfaceVariant,
            contentColor = NasMusicColors.TextPrimary,
            focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.TextPrimary,
            pressedScale = 0.96f
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = if (value.text.isEmpty()) hint
                           else if (masked) "*".repeat(value.text.length)
                           else value.text,
                    color = if (value.text.isEmpty()) NasMusicColors.TextSecondary
                            else NasMusicColors.TextPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        Text(
            text = stringResource(R.string.server_press_ok_edit),
            color = NasMusicColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TestConnectionButton(
    isTesting: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val animScale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    Surface(
        onClick = onClick,
        enabled = !isTesting,
        modifier = Modifier
            .width(100.dp)
            .height(56.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                scope.launch {
                    animScale.animateTo(if (isFocused) 1.05f else 1f, tween(150))
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(12.dp),
            focusedShape = RoundedCornerShape(12.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isTesting) NasMusicColors.SurfaceVariant else NasMusicColors.Primary,
            contentColor = if (isTesting) NasMusicColors.TextSecondary else Color.Black,
            focusedContainerColor = if (isTesting) NasMusicColors.SurfaceVariant else NasMusicColors.Primary.copy(alpha = 0.85f),
            focusedContentColor = if (isTesting) NasMusicColors.TextSecondary else Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.96f
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isTesting) stringResource(R.string.server_testing) else stringResource(R.string.server_connection_test),
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerAddressField(
    baseUrl: TextFieldValue,
    onBaseUrlChange: (TextFieldValue) -> Unit,
    onOpenKeyboard: () -> Unit,
    isTesting: Boolean,
    testStatus: String,
    onTestClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.server_address),
                color = NasMusicColors.TextPrimary,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 输入框
            FocusableSurface(
                onClick = onOpenKeyboard,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                focusedScale = 1.02f,
                animationDurationMs = 150,
                containerColor = NasMusicColors.SurfaceVariant,
                contentColor = NasMusicColors.TextPrimary,
                focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                focusedContentColor = NasMusicColors.TextPrimary,
                pressedScale = 0.96f
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (baseUrl.text.isEmpty()) "https://jellyfin.example.com 或 http://192.168.1.100:8096"
                               else baseUrl.text,
                        color = if (baseUrl.text.isEmpty()) NasMusicColors.TextSecondary
                                else NasMusicColors.TextPrimary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // 测试按钮
            TestConnectionButton(
                isTesting = isTesting,
                onClick = onTestClick
            )
        }

        // 提示文字
        Text(
            text = stringResource(R.string.server_press_ok_edit),
            color = NasMusicColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
        )

        // 测试状态显示
        if (testStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            val isSuccess = testStatus.startsWith("success:")
            val message = if (isSuccess) testStatus.removePrefix("success:") else testStatus.removePrefix("error:")
            Text(
                text = if (isSuccess) "✓ 连接成功：$message" else "✗ $message",
                color = if (isSuccess) NasMusicColors.Primary else NasMusicColors.Warning,
                fontSize = 13.sp
            )
        }
    }
}
