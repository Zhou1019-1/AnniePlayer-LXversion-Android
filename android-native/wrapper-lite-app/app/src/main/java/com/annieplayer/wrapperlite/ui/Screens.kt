package com.annieplayer.wrapperlite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.wrapperlite.qemu.WrapperLite
import com.annieplayer.wrapperlite.qemu.WrapperPaths

// ---------------------------------------------------------------- palette

private val MusicRed = Color(0xFFFA233B)
private val Bg = Color(0xFF000000)
private val Card = Color(0xFF1C1C1E)
private val FieldFill = Color(0xFF1C1C1E)
private val FieldBorder = Color(0xFF38383A)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFF98989D)
private val LogBackground = Color(0xFF0C0C0E)
private val LogText = Color(0xFF7EE787)
private val LogMuted = Color(0xFF5A5A60)

private val DarkColors = darkColorScheme(
    primary = MusicRed,
    onPrimary = Color.White,
    background = Bg,
    onBackground = TextPrimary,
    surface = Card,
    onSurface = TextPrimary,
)

@Composable
fun WrapperLiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

// ---------------------------------------------------------------- root

@Composable
fun RootScreen(
    phase: WrapperLite.Phase,
    logs: List<String>,
    account: String,
    region: String,
    tokenHealth: WrapperLite.TokenHealth,
    progress: Pair<Float, String>,
) {
    var showLogs by remember { mutableStateOf(false) }
    var show2fa by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity

    LaunchedEffect(phase) {
        when (phase) {
            WrapperLite.Phase.Need2FA -> show2fa = true
            WrapperLite.Phase.LoggedIn,
            WrapperLite.Phase.Serving,
            WrapperLite.Phase.Starting -> show2fa = false
            else -> Unit
        }
    }

    // 日志页打开时：返回手势 = 关日志
    BackHandler(enabled = showLogs) { showLogs = false }

    // 主界面返回：退出会连带停掉服务，先问一句
    BackHandler(enabled = !showLogs) { showExitConfirm = true }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        when (phase) {
            WrapperLite.Phase.Preparing -> PreparingScreen(progress, logs)
            WrapperLite.Phase.LoggedOut, WrapperLite.Phase.Failed -> LoginScreen(
                failed = phase == WrapperLite.Phase.Failed,
                logs = logs,
                onLogin = { id, pwd -> WrapperLite.login(id, pwd) },
                onShowLogs = { showLogs = true },
            )
            WrapperLite.Phase.LoggingIn -> LoggingScreen(
                title = "正在登录…",
                hint = "首次启动 QEMU 约需 20 秒",
                logs = logs,
                onShowLogs = { showLogs = true },
            )
            WrapperLite.Phase.Need2FA -> LoggingScreen(
                title = "等待双重认证验证码",
                hint = "验证码 60 秒内有效",
                logs = logs,
                onShowLogs = { showLogs = true },
                onEnter2fa = { show2fa = true },
            )
            WrapperLite.Phase.Starting -> LoggingScreen(
                title = "正在启动服务…",
                hint = "QEMU 启动到端口监听约 20 秒",
                logs = logs,
                onShowLogs = { showLogs = true },
            )
            WrapperLite.Phase.LoggedIn, WrapperLite.Phase.Serving -> HomeScreen(
                phase = phase,
                account = account,
                region = region,
                tokenHealth = tokenHealth,
                logs = logs,
                onServe = { WrapperLite.startServing() },
                onStopServe = { WrapperLite.stopServing() },
                onLogout = { showLogoutConfirm = true },
                onShowLogs = { showLogs = true },
            )
        }
    }

    if (show2fa) {
        TwoFaDialog(
            onSubmit = { code ->
                show2fa = false
                WrapperLite.submit2fa(code)
            },
        )
    }

    // 日志页：从底部轻微上滑 + 淡入（sheet 手感），250ms
    AnimatedVisibility(
        visible = showLogs,
        enter = slideInVertically(
            initialOffsetY = { it / 8 },
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        ) + fadeIn(animationSpec = tween(durationMillis = 250)),
        exit = slideOutVertically(
            targetOffsetY = { it / 8 },
            animationSpec = tween(durationMillis = 250, easing = FastOutLinearInEasing),
        ) + fadeOut(animationSpec = tween(durationMillis = 250)),
    ) {
        LogSheet(logs = logs, onClose = { showLogs = false })
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = Card,
            title = { Text("退出登录？", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "退出会清除本机保存的账号与登录凭证，下次需要重新输入 Apple ID 和密码（含双重认证）。",
                    fontSize = 14.sp,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        WrapperLite.logout()
                    },
                ) { Text("退出登录", color = MusicRed) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            containerColor = Card,
            title = { Text("退出应用？", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "退出会同时停止正在运行的服务，下次打开需要重新点「启动服务」。",
                    fontSize = 14.sp,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirm = false
                        WrapperLite.stopServing()
                        activity?.finish()
                    },
                ) { Text("退出", color = MusicRed) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
        )
    }
}

// ---------------------------------------------------------------- screens

@Composable
private fun PreparingScreen(progress: Pair<Float, String>, logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppleMusicMark(72.dp)
        Spacer(Modifier.height(28.dp))
        Text("首次启动，正在释放运行环境", fontSize = 17.sp, color = TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(progress.second, fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .height(6.dp)
                .background(FieldBorder, RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.first.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(MusicRed, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.height(24.dp))
        LogPanel(logs = logs, modifier = Modifier.height(200.dp))
    }
}

@Composable
private fun LoginScreen(
    failed: Boolean,
    logs: List<String>,
    onLogin: (String, String) -> Unit,
    onShowLogs: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        AppleMusicMark(76.dp)
        Spacer(Modifier.height(22.dp))
        Text(
            text = "Apple Music",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "使用 Apple ID 登录",
            fontSize = 15.sp,
            color = TextSecondary,
        )

        Spacer(Modifier.height(36.dp))

        Field(
            value = email,
            onValueChange = { email = it.trim() },
            placeholder = "Apple ID",
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(12.dp))
        Field(
            value = password,
            onValueChange = { password = it },
            placeholder = "密码",
            keyboardType = KeyboardType.Password,
            isPassword = true,
            imeAction = ImeAction.Done,
        )

        if (failed) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "登录失败，请检查账号密码或稍后重试",
                fontSize = 13.sp,
                color = MusicRed,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onLogin(email, password) },
            enabled = email.isNotBlank() && password.isNotEmpty(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MusicRed,
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF5A1A22),
                disabledContentColor = Color(0xFF9B7076),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("登录", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))
        TextButton(onClick = onShowLogs) {
            Text("查看日志", fontSize = 14.sp, color = TextSecondary)
        }

        Spacer(Modifier.height(28.dp))
        Text(
            text = "登录信息只保存在本机，用于向 Apple 获取解密密钥",
            fontSize = 12.sp,
            color = LogMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun LoggingScreen(
    title: String,
    hint: String,
    logs: List<String>,
    onShowLogs: () -> Unit,
    onEnter2fa: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(color = MusicRed, strokeWidth = 3.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = 17.sp, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Text(hint, fontSize = 13.sp, color = TextSecondary)
        if (onEnter2fa != null) {
            Spacer(Modifier.height(16.dp))
            // 弹窗一旦被取消就没入口了，这里留一个重新打开的口子
            Button(
                onClick = onEnter2fa,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MusicRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier.height(44.dp),
            ) {
                Text("输入验证码", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(22.dp))
        LogPanel(logs = logs, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onShowLogs) {
            Text("全屏日志", fontSize = 14.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun HomeScreen(
    phase: WrapperLite.Phase,
    account: String,
    region: String,
    tokenHealth: WrapperLite.TokenHealth,
    logs: List<String>,
    onServe: () -> Unit,
    onStopServe: () -> Unit,
    onLogout: () -> Unit,
    onShowLogs: () -> Unit,
) {
    val serving = phase == WrapperLite.Phase.Serving
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        AppleMusicMark(60.dp)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (serving) "服务运行中" else "已登录",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            if (serving) {
                Spacer(Modifier.width(9.dp))
                StatusBadge("端口已就绪", Color(0xFF34C759))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = account,
                fontSize = 14.sp,
                color = TextSecondary,
            )
            if (region.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = region,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = MusicRed,
                    modifier = Modifier
                        .background(MusicRed.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        val clipboard = LocalClipboardManager.current
        var copiedPort by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(copiedPort) {
            if (copiedPort != null) {
                kotlinx.coroutines.delay(1600)
                copiedPort = null
            }
        }
        val corsUrl = "http://127.0.0.1:${WrapperPaths.PORT}"
        val directUrl = "http://127.0.0.1:${WrapperPaths.UPSTREAM_PORT}"
        CopyablePortRow(
            label = "CORS 端口（网页工具）",
            value = corsUrl,
            copied = copiedPort == corsUrl,
            onClick = {
                clipboard.setText(AnnotatedString(corsUrl))
                copiedPort = corsUrl
            },
        )
        CopyablePortRow(
            label = "直连端口（App）",
            value = directUrl,
            copied = copiedPort == directUrl,
            onClick = {
                clipboard.setText(AnnotatedString(directUrl))
                copiedPort = directUrl
            },
        )
        EndpointBlock(
            listOf("/m3u8", "/key", "/lyrics", "/webplayback", "/license", "/status"),
        )

        Spacer(Modifier.height(20.dp))

        if (tokenHealth == WrapperLite.TokenHealth.RefreshFailed) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MusicRed.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "登录态可能已失效",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MusicRed,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "后台续期 token 失败，播放/下载会拿不到密钥。建议退出后重新登录。",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        if (serving) {
            OutlinedButton(
                onClick = onStopServe,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MusicRed.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MusicRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("停止服务", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onServe,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MusicRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("启动服务", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(12.dp))

        LogPanel(logs = logs, modifier = Modifier.weight(1f))

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onShowLogs) {
                Text("全屏日志", fontSize = 14.sp, color = TextSecondary)
            }
            TextButton(onClick = { WrapperLite.openBatterySettings(ctx) }) {
                Text("耗电优化", fontSize = 14.sp, color = TextSecondary)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onLogout, modifier = Modifier.weight(1f)) {
                Text("退出登录", fontSize = 15.sp, color = MusicRed)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    var plain by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, fontSize = 16.sp, color = TextSecondary) },
        textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = TextPrimary),
        visualTransformation = if (isPassword && !plain) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { plain = !plain }) {
                    Text(
                        text = if (plain) "隐藏" else "显示",
                        fontSize = 12.sp,
                        color = MusicRed,
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MusicRed,
            unfocusedBorderColor = FieldBorder,
            focusedContainerColor = FieldFill,
            unfocusedContainerColor = FieldFill,
            cursorColor = MusicRed,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(value, fontSize = 13.sp, color = TextPrimary, fontFamily = FontFamily.Monospace)
    }
}

/** 端点清单：自动换行，一行放不下会折到下一行。 */
@Composable
private fun EndpointBlock(endpoints: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text("接口", fontSize = 13.sp, color = TextSecondary)
        Spacer(Modifier.height(5.dp))
        Text(
            text = endpoints.joinToString("  ·  "),
            fontSize = 12.sp,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            lineHeight = 19.sp,
        )
    }
}

/** 服务就绪小标：绿点 + 文案。 */@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/** 可点击复制的端口行。 */
@Composable
private fun CopyablePortRow(
    label: String,
    value: String,
    copied: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 12.sp,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (copied) "已复制" else "复制",
                fontSize = 11.sp,
                color = if (copied) Color(0xFF34C759) else MusicRed,
            )
        }
    }
}

@Composable
fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) runCatching { state.scrollToItem(logs.lastIndex) }
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(LogBackground, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (logs.isEmpty()) {
            Text("暂无日志", fontSize = 12.sp, color = LogMuted)
        } else {
            LazyColumn(state = state) {
                items(logs) { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        color = LogText,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogSheet(logs: List<String>, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "运行日志",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${logs.size} 行", fontSize = 12.sp, color = LogMuted)
                    TextButton(
                        enabled = logs.isNotEmpty(),
                        onClick = {
                            clipboard.setText(AnnotatedString(logs.joinToString("\n")))
                            copied = true
                        },
                    ) {
                        Text(
                            text = if (copied) "已复制" else "复制",
                            fontSize = 15.sp,
                            color = if (copied) Color(0xFF34C759) else TextSecondary,
                        )
                    }
                    TextButton(onClick = onClose) {
                        Text("关闭", fontSize = 15.sp, color = MusicRed)
                    }
                }
            }
            LogPanel(logs = logs, modifier = Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 双重认证输入框。
 *
 * **不可取消**：guest 在拿到验证码之前登录流程是卡住的，弹窗一旦能关掉就再没有入口
 * （60 秒后 guest 自己 abort）。所以：没有「取消」按钮，点窗外 / 返回键都不关，
 * 只有填满 6 位点「确认」才结束；guest 侧超时/失败时由 Phase 变化收掉这个弹窗。
 */
@Composable
private fun TwoFaDialog(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        containerColor = Card,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("双重认证", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "请输入受信任设备上显示的 6 位验证码。",
                    fontSize = 14.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    placeholder = { Text("6 位数字", color = TextSecondary) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, color = TextPrimary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MusicRed,
                        unfocusedBorderColor = FieldBorder,
                        focusedContainerColor = FieldFill,
                        unfocusedContainerColor = FieldFill,
                        cursorColor = MusicRed,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.length == 6) {
                Text("确认", color = MusicRed)
            }
        },
    )
}

/** Apple Music 音符标记（自绘，不依赖图标库）。 */
@Composable
private fun AppleMusicMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MusicRed, RoundedCornerShape(size * 0.24f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.52f)) {
            val w = this.size.width
            val h = this.size.height
            val stroke = Stroke(width = w * 0.12f)
            drawPath(
                Path().apply {
                    moveTo(w * 0.62f, h * 0.08f)
                    lineTo(w * 0.62f, h * 0.68f)
                },
                Color.White,
                style = stroke,
            )
            drawOval(
                color = Color.White,
                topLeft = Offset(w * 0.18f, h * 0.58f),
                size = androidx.compose.ui.geometry.Size(w * 0.46f, h * 0.34f),
            )
            drawPath(
                Path().apply {
                    moveTo(w * 0.62f, h * 0.08f)
                    quadraticBezierTo(w * 0.92f, h * 0.2f, w * 0.72f, h * 0.42f)
                },
                Color.White,
                style = stroke,
            )
        }
    }
}
