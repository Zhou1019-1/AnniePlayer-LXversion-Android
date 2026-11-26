package com.annieplayer.annie_music.kt.widgets

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.state.LocalVersion
import com.annieplayer.annie_music.kt.data.sources.update.UpdateNoteLine
import com.annieplayer.annie_music.kt.data.sources.update.UpdateOutcome
import com.annieplayer.annie_music.kt.data.sources.update.UpdateStatus
import com.annieplayer.annie_music.kt.data.sources.update.parseUpdateNotes
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppLineHeight
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.AnnieTypography

private const val UpdateSheetFooter =
    "更新包由 GitHub Release 提供，覆盖安装不会清除播放列表与本地曲库"

@Composable
fun UpdateSheet(
    visible: Boolean,
    status: UpdateStatus,
    localVersion: LocalVersion,
    onCheckAgain: () -> Unit,
    onOpenPage: (String) -> Unit,
    onSkipVersion: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    BackHandler { onClose() }

    val info = status.info
    val notes = remember(info?.notes) { parseUpdateNotes(info?.notes.orEmpty()) }
    val notesMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.32f).dp

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.scrim60)
                .clickableNoRipple { },
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.88f)
                .background(AppColors.modalPanel, RoundedCornerShape(AppRadius.r20))
                .clickableNoRipple { }
                .padding(horizontal = AppSpacing.sp28, vertical = AppSpacing.sp40),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = updateSheetTitle(status), style = AnnieTypography.sheetTitle)
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                Text(
                    text = updateSheetVersionLine(status, localVersion),
                    textAlign = TextAlign.Center,
                    color = AppColors.white54,
                    fontSize = AppFontSize.xs,
                    lineHeight = AppLineHeight.body16,
                )

                if (notes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.sp20))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.whiteA06, RoundedCornerShape(AppRadius.r10))
                            .padding(AppSpacing.sp12),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = notesMaxHeight)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            notes.forEach { line -> UpdateNoteText(line) }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp24))
                UpdateSheetActions(
                    status = status,
                    onCheckAgain = onCheckAgain,
                    onOpenPage = onOpenPage,
                    onClose = onClose,
                )

                if (info != null && status.hasNewer && info.versionCode != null) {
                    val skippedCode = info.versionCode
                    Spacer(modifier = Modifier.height(AppSpacing.sp12))
                    Text(
                        text = "跳过此版本 v${info.version}",
                        modifier = Modifier
                            .clickableNoRipple { onSkipVersion(skippedCode) }
                            .padding(AppSpacing.sp4),
                        color = AppColors.white30,
                        fontSize = AppFontSize.xs,
                    )
                }

                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                Text(
                    text = UpdateSheetFooter,
                    textAlign = TextAlign.Center,
                    color = AppColors.white30,
                    fontSize = AppFontSize.xs,
                    lineHeight = AppLineHeight.body16,
                )
            }
        }
    }
}

@Composable
private fun UpdateSheetActions(
    status: UpdateStatus,
    onCheckAgain: () -> Unit,
    onOpenPage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val pageUrl = status.info?.pageUrl
    when {
        status.checking -> {
            UpdateSheetButton(label = "正在检查…", primary = false, onClick = onClose)
        }

        status.hasNewer && pageUrl != null -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
            ) {
                UpdateSheetButton(
                    label = "稍后",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onClose,
                )
                UpdateSheetButton(
                    label = "立即更新",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenPage(pageUrl) },
                )
            }
        }

        status.outcome == UpdateOutcome.FAILED || status.outcome == UpdateOutcome.UNKNOWN -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
            ) {
                UpdateSheetButton(
                    label = "关闭",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = onClose,
                )
                UpdateSheetButton(
                    label = "重试",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = onCheckAgain,
                )
            }
        }

        else -> {
            UpdateSheetButton(label = "关闭", primary = false, onClick = onClose)
        }
    }
}

@Composable
private fun UpdateSheetButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clickableNoRipple(onClick)
            .background(AppColors.glass, RoundedCornerShape(AppRadius.card))
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp10),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) AppColors.primary else AppColors.white,
            fontSize = AppFontSize.size15,
            fontWeight = if (primary) AppFontWeight.semiBold else AppFontWeight.medium,
        )
    }
}

@Composable
private fun UpdateNoteText(line: UpdateNoteLine) {
    when (line) {
        is UpdateNoteLine.Heading -> {
            Text(
                text = line.text,
                color = AppColors.white,
                fontSize = AppFontSize.base,
                fontWeight = AppFontWeight.semiBold,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp6))
        }

        is UpdateNoteLine.Bullet -> {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "·",
                    color = AppColors.white54,
                    fontSize = AppFontSize.xs,
                    lineHeight = AppLineHeight.body16,
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp2))
                Text(
                    text = line.text,
                    color = AppColors.white54,
                    fontSize = AppFontSize.xs,
                    lineHeight = AppLineHeight.body16,
                )
            }
        }

        is UpdateNoteLine.Code -> {
            Text(
                text = line.text,
                color = AppColors.white54,
                fontSize = AppFontSize.xs,
                fontFamily = FontFamily.Monospace,
                lineHeight = AppLineHeight.body16,
            )
        }

        is UpdateNoteLine.Plain -> {
            Text(
                text = line.text,
                color = AppColors.white54,
                fontSize = AppFontSize.xs,
                lineHeight = AppLineHeight.body16,
            )
        }
    }
}

private fun updateSheetTitle(status: UpdateStatus): String = when {
    status.checking -> "检查更新"
    status.hasNewer -> "发现新版本"
    status.outcome == UpdateOutcome.FAILED -> "检查更新"
    else -> "检查更新"
}

private fun updateSheetVersionLine(status: UpdateStatus, localVersion: LocalVersion): String {
    val local = localVersion.name.takeIf { it.isNotBlank() } ?: "—"
    return when {
        status.checking -> "当前版本 $local · 正在获取最新版本"
        status.hasNewer -> "当前版本 $local → 最新版本 ${status.info?.version.orEmpty()}"
        status.outcome == UpdateOutcome.UNKNOWN ->
            "当前版本 $local · 最新发布 ${status.info?.version.orEmpty()} 未标注 versionCode"
        status.outcome == UpdateOutcome.FAILED -> "当前版本 $local · 获取更新信息失败，请检查网络"
        else -> "当前版本 $local · 已是最新版本"
    }
}
