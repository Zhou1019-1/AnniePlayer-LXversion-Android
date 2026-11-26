package com.annieplayer.annie_music.kt.features.tools.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.remote.LanRemoteSettings
import com.annieplayer.annie_music.kt.core.remote.LanRemoteViewModel
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.widgets.SheetBackButton
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple

private val RemoteFieldHeight = 46.dp
private val RemoteCodeCellHeight = 56.dp
private val RemoteStatusDot = 6.dp
private const val RemoteCodeLength = 4

@Composable
fun LanRemoteSheet(
    visible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = activityViewModel(),
    remoteViewModel: LanRemoteViewModel = activityViewModel(),
) {
    val ctx = LocalContext.current
    remember(ctx) { LanRemoteSettings.init(ctx) }

    val ui by remoteViewModel.ui.collectAsState()
    var resumeLocal by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(visible) {
        if (visible) {
            val playing = playerViewModel.state.value.isPlaying
            resumeLocal = playing
            if (playing) playerViewModel.togglePlay()
            remoteViewModel.open()
        } else {
            remoteViewModel.close()
            if (resumeLocal && !playerViewModel.state.value.isPlaying) playerViewModel.togglePlay()
            resumeLocal = false
            tab = 0
        }
    }

    BackHandler(enabled = visible) { onClose() }

    val lifecycleOwner = ctx as? LifecycleOwner
    DisposableEffect(lifecycleOwner, visible) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (visible) remoteViewModel.close()
                Lifecycle.Event.ON_START -> {
                    if (visible && remoteViewModel.ui.value.status == LanRemoteViewModel.Status.Idle) {
                        remoteViewModel.open()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "lanRemoteScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "lanRemoteSlide",
    )

    if (!visible && scrimAlpha == 0f) return

    val live = ui.status == LanRemoteViewModel.Status.Live

    Box(modifier = modifier.fillMaxSize()) {
        val scrimInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickable(
                    enabled = visible,
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = {},
                )
                .background(AppColors.scrim50),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                .clickableNoRipple { }
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            RemoteHeader(status = ui.status, onClose = onClose)
            if (live) {
                RemoteTabs(
                    tab = tab,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp10),
                )
                if (tab == 0) {
                    LanRemotePlaying(
                        remoteViewModel = remoteViewModel,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LanRemoteLibrary(
                        remoteViewModel = remoteViewModel,
                        modifier = Modifier.weight(1f),
                    )
                }
                RemoteFooter(
                    endpoint = ui.endpoint,
                    onForget = { remoteViewModel.forgetDevice() },
                    onCopy = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("安妮遥控", "http://${ui.endpoint}"))
                        Toast.makeText(ctx, "已复制地址", Toast.LENGTH_SHORT).show()
                    },
                )
            } else {
                ConnectPane(
                    ui = ui,
                    onHost = remoteViewModel::setHostInput,
                    onCode = remoteViewModel::setCodeInput,
                    onConnect = remoteViewModel::pair,
                    onRetry = remoteViewModel::retry,
                    onDiscover = remoteViewModel::discover,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RemoteHeader(status: LanRemoteViewModel.Status, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp24,
                top = AppSpacing.sp40,
                end = AppSpacing.sp24,
                bottom = AppSpacing.sp8,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetBackButton(onClose = onClose)
        Spacer(modifier = Modifier.size(AppSpacing.sp8))
        Text(
            text = "遥控 PC 端安妮播放器",
            modifier = Modifier.weight(1f),
            style = AnnieTypography.screenTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(RemoteStatusDot)
                    .background(statusDotColor(status), CircleShape),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            Text(
                text = statusLabel(status),
                color = AppColors.white38,
                fontSize = AppFontSize.size10,
                letterSpacing = 0.6.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RemoteTabs(tab: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf("正在播放", "曲库点播")
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(AppColors.whiteA04)
            .padding(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .background(if (selected) AppColors.white else Color.Transparent)
                    .clickableNoRipple { if (!selected) onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) AppColors.background else AppColors.white54,
                    fontSize = AppFontSize.size13,
                    fontWeight = if (selected) AppFontWeight.semiBold else AppFontWeight.medium,
                )
            }
        }
    }
}

@Composable
private fun RemoteFooter(endpoint: String, onForget: () -> Unit, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp24, end = AppSpacing.sp24, bottom = AppSpacing.sp12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = endpoint,
            color = AppColors.white24,
            fontSize = AppFontSize.size10,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RemoteTextAction(text = "复制地址", onClick = onCopy)
        Spacer(modifier = Modifier.width(AppSpacing.sp16))
        RemoteTextAction(text = "断开设备", onClick = onForget)
    }
}

@Composable
private fun ConnectPane(
    ui: LanRemoteViewModel.UiState,
    onHost: (String) -> Unit,
    onCode: (String) -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manual by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.sp24),
    ) {
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = if (ui.status == LanRemoteViewModel.Status.Connecting) "正在连接…" else "连接电脑",
            style = AnnieTypography.sectionTitle,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Text(
            text = "手机与电脑连同一个 Wi-Fi，电脑端打开 PC 版安妮播放器 → 设置 → 常规 → 启用局域网手机遥控。",
            color = AppColors.white54,
            fontSize = AppFontSize.sm,
            lineHeight = 18.sp,
        )

        Spacer(modifier = Modifier.height(AppSpacing.sp20))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(RemoteStatusDot)
                    .background(
                        when {
                            ui.discovering -> AppColors.white
                            ui.hostInput.isNotBlank() -> AppColors.white54
                            else -> AppColors.white24
                        },
                        CircleShape,
                    ),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = when {
                    ui.discovering -> "正在搜索局域网里的电脑…"
                    ui.hostInput.isNotBlank() -> ui.hostInput
                    else -> "还没找到电脑"
                },
                color = AppColors.white70,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RemoteTextAction(text = "重新搜索", onClick = onDiscover)
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        val total = ui.scanTotal
        val fraction = if (ui.discovering && total > 0) {
            (ui.scanned.toFloat() / total).coerceIn(0f, 1f)
        } else if (ui.discovering) {
            0f
        } else {
            0f
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColors.whiteA06),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(1.dp)
                    .background(AppColors.white),
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.sp28))
        Text(
            text = "4 位配对码",
            color = AppColors.white38,
            fontSize = AppFontSize.size10,
            letterSpacing = 1.2.sp,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp10))
        RemoteCodeField(
            value = ui.codeInput,
            onValueChange = onCode,
            enabled = ui.status != LanRemoteViewModel.Status.Connecting,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Text(
            text = "在电脑端「设置 → 常规」里查看",
            color = AppColors.white24,
            fontSize = AppFontSize.xs,
        )

        if (ui.error != null) {
            Spacer(modifier = Modifier.height(AppSpacing.sp12))
            Text(
                text = ui.error,
                color = AppColors.white70,
                fontSize = AppFontSize.sm,
                lineHeight = 18.sp,
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.sp28))
        if (ui.status == LanRemoteViewModel.Status.Failed) {
            RemotePrimaryAction(
                label = "重新连接",
                enabled = true,
                loading = ui.busy,
                onClick = onRetry,
            )
        } else {
            RemotePrimaryAction(
                label = if (ui.busy) "连接中…" else "连接",
                enabled = ui.codeInput.length == RemoteCodeLength,
                loading = ui.busy,
                onClick = onConnect,
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Box(
            modifier = Modifier
                .clickableNoRipple { manual = !manual }
                .padding(vertical = AppSpacing.sp6),
        ) {
            Text(
                text = if (manual) "收起手动地址" else "手动指定地址",
                color = AppColors.white24,
                fontSize = AppFontSize.xs,
            )
        }
        if (manual) {
            Spacer(modifier = Modifier.height(AppSpacing.sp8))
            RemoteField(
                value = ui.hostInput,
                onValueChange = onHost,
                placeholder = "192.168.1.5:45823",
                enabled = ui.status != LanRemoteViewModel.Status.Connecting,
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sheetBottomInset))
    }
}

@Composable
private fun RemoteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RemoteFieldHeight)
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(AppColors.whiteA05)
            .padding(horizontal = AppSpacing.sp14),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = AppColors.white,
                fontSize = AppFontSize.size15,
            ),
            cursorBrush = SolidColor(AppColors.white),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = AppColors.white24,
                            fontSize = AppFontSize.size15,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun RemoteCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(AppRadius.card)
    val activeIndex = value.length.coerceAtMost(RemoteCodeLength - 1)
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableNoRipple { runCatching { focus.requestFocus() } },
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
        ) {
            repeat(RemoteCodeLength) { index ->
                val digit = value.getOrNull(index)?.toString().orEmpty()
                val active = enabled && index == activeIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RemoteCodeCellHeight)
                        .clip(shape)
                        .background(if (active) AppColors.whiteA06 else AppColors.whiteA03)
                        .border(
                            width = AppBorder.thin,
                            color = if (active) AppColors.white else AppColors.white12,
                            shape = shape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = digit,
                        color = if (enabled) AppColors.white else AppColors.white38,
                        fontSize = AppFontSize.size20,
                        fontWeight = AppFontWeight.semiBold,
                    )
                }
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(fontSize = AppFontSize.size13),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .matchParentSize()
                .focusRequester(focus)
                .alpha(0f),
        )
    }
}

@Composable
private fun RemotePrimaryAction(
    label: String,
    enabled: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = RoundedCornerShape(AppRadius.card),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.white,
            contentColor = AppColors.background,
            disabledContainerColor = AppColors.whiteA06,
            disabledContentColor = AppColors.white38,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = AppColors.white38,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
        }
        Text(
            text = label,
            fontSize = AppFontSize.base,
            color = if (enabled && !loading) AppColors.background else AppColors.white38,
            fontWeight = AppFontWeight.semiBold,
        )
    }
}

@Composable
private fun RemoteTextAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.r8))
            .clickableNoRipple(onClick)
            .padding(vertical = AppSpacing.sp6),
    ) {
        Text(
            text = text,
            color = AppColors.white54,
            fontSize = AppFontSize.sm,
        )
    }
}

private fun statusLabel(status: LanRemoteViewModel.Status): String = when (status) {
    LanRemoteViewModel.Status.Live -> "已连接"
    LanRemoteViewModel.Status.Connecting -> "连接中"
    LanRemoteViewModel.Status.Failed -> "未连接"
    LanRemoteViewModel.Status.Pairing -> "未连接"
    LanRemoteViewModel.Status.Idle -> "待连接"
}

private fun statusDotColor(status: LanRemoteViewModel.Status): Color = when (status) {
    LanRemoteViewModel.Status.Live -> AppColors.white
    LanRemoteViewModel.Status.Connecting -> AppColors.white54
    else -> AppColors.white24
}
