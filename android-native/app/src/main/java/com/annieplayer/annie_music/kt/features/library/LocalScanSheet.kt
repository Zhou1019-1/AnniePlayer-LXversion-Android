package com.annieplayer.annie_music.kt.features.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.core.platform.MediaStoreScan
import com.annieplayer.annie_music.kt.data.local.LocalScanExclusions
import com.annieplayer.annie_music.kt.data.local.LocalScanIndex
import com.annieplayer.annie_music.kt.data.local.LocalScanSettings
import com.annieplayer.annie_music.kt.data.local.friendlyDirPath
import com.annieplayer.annie_music.kt.data.local.isStorageRootPath
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography
import com.annieplayer.annie_music.kt.widgets.SheetBackButton
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val MinSecondsSteps = listOf(5, 10, 20, 30)

@Composable
fun LocalScanSheet(
    visible: Boolean,
    scanning: Boolean,
    scannedFiles: Int,
    onClose: () -> Unit,
    onRescan: () -> Unit,
    onApplyScope: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val excluded by LocalScanExclusions.excluded.collectAsState()
    val skipShort by LocalScanSettings.skipShortAudio.collectAsState()
    val minSeconds by LocalScanSettings.minAudioSeconds.collectAsState()
    val skipHidden by LocalScanSettings.skipHiddenDirs.collectAsState()
    val respectNoMedia by LocalScanSettings.respectNoMedia.collectAsState()

    var cacheDirs by remember { mutableStateOf(0) }
    var flash by remember { mutableStateOf<String?>(null) }
    var rescanning by remember { mutableStateOf(false) }
    var sawScanning by remember { mutableStateOf(false) }

    LaunchedEffect(scanning) {
        if (scanning) {
            sawScanning = true
        } else if (sawScanning) {
            sawScanning = false
            rescanning = false
        }
    }

    LaunchedEffect(rescanning) {
        if (!rescanning) return@LaunchedEffect
        delay(800)
        if (!sawScanning) {
            rescanning = false
            return@LaunchedEffect
        }
        delay(15_000)
        rescanning = false
    }

    val spin = rememberInfiniteTransition(label = "rescanSpin")
    val spinAngle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rescanAngle",
    )

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val path = MediaStoreScan.resolveTreeToPath(uri)
            when {
                path == null -> flash = "这个目录无法转换成可扫描路径"
                isStorageRootPath(path) -> flash = "不能排除整个存储，请选择里面的文件夹"
                else -> {
                    LocalScanExclusions.addDir(path)
                    flash = "已排除 ${friendlyDirPath(path)}"
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        cacheDirs = withContext(Dispatchers.IO) { LocalScanIndex.load().size }
    }

    LaunchedEffect(flash) {
        if (flash == null) return@LaunchedEffect
        delay(1_800)
        flash = null
    }

    var scopeSettled by remember { mutableStateOf(false) }
    LaunchedEffect(excluded, skipShort, minSeconds, skipHidden, respectNoMedia) {
        if (!scopeSettled) {
            scopeSettled = true
            return@LaunchedEffect
        }
        delay(700)
        onApplyScope()
    }

    BackHandler(enabled = visible) { onClose() }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "localScanScrim",
    )
    val slide by animateFloatAsState(
        targetValue = if (visible) 0f else 1f,
        animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        label = "localScanSlide",
    )
    if (!visible && scrimAlpha == 0f) return

    val ordered = remember(excluded) { excluded.sorted() }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .clickableNoRipple { }
                .background(AppColors.scrim50),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide * size.height }
                .background(AppColors.background)
                .clickableNoRipple { }
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
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
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                Text(
                    text = "文件扫描",
                    modifier = Modifier.weight(1f),
                    style = AnnieTypography.screenTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (rescanning) {
                    Text(
                        text = "重扫中",
                        color = AppColors.white38,
                        fontSize = AppFontSize.xs,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp6))
                }
                Box(
                    modifier = Modifier
                        .clickableNoRipple {
                            if (!rescanning && !scanning) {
                                rescanning = true
                                onRescan()
                            }
                        }
                        .padding(AppSpacing.sp4),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_rescan),
                        contentDescription = if (rescanning || scanning) "重扫中" else "重新扫描",
                        tint = if (rescanning || scanning) AppColors.white38 else AppColors.white,
                        modifier = Modifier
                            .size(AppIconSize.icon24)
                            .graphicsLayer { rotationZ = if (rescanning || scanning) spinAngle else 0f },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.sp16,
                    end = AppSpacing.sp16,
                    top = AppSpacing.sp4,
                    bottom = AppSpacing.sheetBottomInset,
                ),
            ) {
                item(key = "rules") {
                    Column(modifier = Modifier.padding(top = AppSpacing.sp4)) {
                        Text(
                            text = "扫描规则",
                            color = AppColors.white70,
                            fontSize = AppFontSize.size13,
                            fontWeight = AppFontWeight.semiBold,
                            modifier = Modifier.padding(start = AppSpacing.sp4, bottom = AppSpacing.sp8),
                        )
                        ScanRulesCard(
                            skipShortAudio = skipShort,
                            minSeconds = minSeconds,
                            skipHiddenDirs = skipHidden,
                            respectNoMedia = respectNoMedia,
                            cacheDirs = cacheDirs,
                            scanning = scanning,
                            scannedFiles = scannedFiles,
                            onToggleSkipShort = { LocalScanSettings.setSkipShortAudio(it) },
                            onCycleMinSeconds = {
                                val index = MinSecondsSteps.indexOf(minSeconds)
                                val next = MinSecondsSteps[(index + 1) % MinSecondsSteps.size]
                                LocalScanSettings.setMinAudioSeconds(next)
                            },
                            onToggleSkipHidden = { LocalScanSettings.setSkipHiddenDirs(it) },
                            onToggleRespectNoMedia = { LocalScanSettings.setRespectNoMedia(it) },
                            onClearCache = {
                                LocalScanIndex.clear()
                                cacheDirs = 0
                                flash = "目录缓存已清空，下次扫描会重新读盘"
                            },
                        )
                    }
                }

                if (flash != null) {
                    item(key = "flash") {
                        Text(
                            text = flash.orEmpty(),
                            color = AppColors.primary,
                            fontSize = AppFontSize.xs,
                            modifier = Modifier.padding(start = AppSpacing.sp4, top = AppSpacing.sp8),
                        )
                    }
                }

                item(key = "excluded-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = AppSpacing.sp4, end = AppSpacing.sp4, top = AppSpacing.sp4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "排除的文件夹",
                            modifier = Modifier.weight(1f),
                            color = AppColors.white70,
                            fontSize = AppFontSize.size13,
                            fontWeight = AppFontWeight.semiBold,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.r8))
                                .background(AppColors.primary.copy(alpha = 0.15f))
                                .clickableRipple { picker.launch(null) }
                                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp6),
                        ) {
                            Text(
                                text = "＋ 添加",
                                color = AppColors.white,
                                fontSize = AppFontSize.sm,
                                fontWeight = AppFontWeight.semiBold,
                            )
                        }
                    }
                }

                if (ordered.isEmpty()) {
                    item(key = "excluded-empty") {
                        Text(
                            text = "还没有排除任何文件夹 · 点「添加」从存储里选，选中的目录整棵不参与扫描",
                            color = AppColors.textTertiary,
                            fontSize = AppFontSize.xs,
                            modifier = Modifier.padding(
                                start = AppSpacing.sp4,
                                top = AppSpacing.sp10,
                                end = AppSpacing.sp4,
                                bottom = AppSpacing.sp12,
                            ),
                        )
                    }
                } else {
                    items(ordered, key = { "x:$it" }) { path ->
                        ExcludedDirRow(
                            path = path,
                            onRemove = { LocalScanExclusions.removeDir(path) },
                        )
                    }
                    item(key = "excluded-clear") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickableRipple { LocalScanExclusions.clear() }
                                .padding(
                                    start = AppSpacing.sp4,
                                    top = AppSpacing.sp6,
                                    bottom = AppSpacing.sp10,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "全部恢复（${ordered.size}）",
                                color = AppColors.primary,
                                fontSize = AppFontSize.sm,
                                fontWeight = AppFontWeight.semiBold,
                            )
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun ExcludedDirRow(path: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppSpacing.sp6)
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .padding(start = AppSpacing.sp16, top = AppSpacing.sp10, end = AppSpacing.sp8, bottom = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friendlyDirPath(path),
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.semiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = path,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.xs,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .clickableNoRipple(onRemove)
                .padding(AppSpacing.sp8),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "恢复此文件夹",
                tint = AppColors.glyphMid,
                modifier = Modifier.size(AppIconSize.icon16),
            )
        }
    }
}

@Composable
private fun ScanRulesCard(
    skipShortAudio: Boolean,
    minSeconds: Int,
    skipHiddenDirs: Boolean,
    respectNoMedia: Boolean,
    cacheDirs: Int,
    scanning: Boolean,
    scannedFiles: Int,
    onToggleSkipShort: (Boolean) -> Unit,
    onCycleMinSeconds: () -> Unit,
    onToggleSkipHidden: (Boolean) -> Unit,
    onToggleRespectNoMedia: (Boolean) -> Unit,
    onClearCache: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
    ) {
        ScanSwitchRow(
            label = "跳过短音频",
            subtitle = "短于 ${minSeconds} 秒的铃声、提示音",
            checked = skipShortAudio,
            onToggle = onToggleSkipShort,
            valueLabel = "${minSeconds}s",
            onValueClick = onCycleMinSeconds,
        )
        ScanSwitchRow(
            label = "跳过隐藏文件夹",
            subtitle = "以 . 开头的目录",
            checked = skipHiddenDirs,
            onToggle = onToggleSkipHidden,
        )
        ScanSwitchRow(
            label = "尊重 .nomedia",
            subtitle = "带该标记的目录整棵跳过",
            checked = respectNoMedia,
            onToggle = onToggleRespectNoMedia,
        )
        ScanActionRow(
            label = "目录索引缓存",
            value = if (scanning) "扫描中 $scannedFiles" else if (cacheDirs == 0) "空" else "$cacheDirs 个目录",
            onClick = onClearCache,
        )
    }
}

@Composable
private fun ScanSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    valueLabel: String? = null,
    onValueClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple { onToggle(!checked) }
            .padding(vertical = AppSpacing.sp6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = AppFontWeight.semiBold,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = subtitle,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.xs,
            )
        }
        if (valueLabel != null && onValueClick != null) {
            Box(
                modifier = Modifier
                    .clickableRipple(onValueClick)
                    .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
            ) {
                Text(
                    text = valueLabel,
                    color = AppColors.primary,
                    fontSize = AppFontSize.sm,
                    fontWeight = AppFontWeight.semiBold,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp6))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppColors.background,
                checkedTrackColor = AppColors.white,
                uncheckedThumbColor = AppColors.white54,
                uncheckedTrackColor = AppColors.whiteA06,
                uncheckedBorderColor = AppColors.white24,
            ),
        )
    }
}

@Composable
private fun ScanActionRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRipple(onClick)
            .padding(vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size15,
            fontWeight = AppFontWeight.semiBold,
        )
        Text(
            text = value,
            color = AppColors.textTertiary,
            fontSize = AppFontSize.sm,
        )
    }
}
