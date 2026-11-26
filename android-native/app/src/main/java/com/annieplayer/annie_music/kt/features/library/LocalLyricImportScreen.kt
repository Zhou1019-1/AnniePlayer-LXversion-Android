package com.annieplayer.annie_music.kt.features.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.state.LyricImportSource
import com.annieplayer.annie_music.kt.data.fetchLyricCandidate
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 歌词对比导入页（从「搜索歌词」结果点进来）。
 *
 * 布局照编辑页的歌词块：**上面是文件里现在的歌词，下面是要导入的那份**，两块都只读、
 * 各自滚动；右上角是逐字 / 逐行切换（没有逐字数据时「逐字」置灰）。
 *
 * 这一页**不写文件**：点「导入歌词」只是把选中的那份交给编辑页的歌词框，
 * 真正落盘仍然由编辑页右上角的保存负责（与「导入元数据」同一套规矩）。
 */
@Composable
fun LocalLyricImportScreen(
    path: String,
    source: LyricImportSource,
    onClose: () -> Unit,
    onImport: (lyric: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 当前歌词：以**文件**为准（.lrc / 内嵌标签），与编辑页同一个来源
    var currentLyric by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        currentLyric = withContext(Dispatchers.IO) { LocalLibraryStore.readLyric(path) }
    }

    var lineLrc by remember(source) { mutableStateOf("") }
    var wordLrc by remember(source) { mutableStateOf<String?>(null) }
    var loading by remember(source) { mutableStateOf(true) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var kind by remember(source) { mutableStateOf(LyricKind.Word) }

    LaunchedEffect(source) {
        loading = true
        error = null
        lineLrc = ""
        wordLrc = null
        when (source) {
            // LRCLIB 的歌词随搜索结果一起拿到，这里不再请求
            is LyricImportSource.Lrclib -> {
                lineLrc = source.hit.syncedLrc
                if (lineLrc.isBlank()) error = "这条没有同步歌词"
            }
            is LyricImportSource.Platform -> {
                try {
                    val r = withContext(Dispatchers.IO) { fetchLyricCandidate(source.song) }
                    if (r.lineLrc.isBlank()) error = "这首没有可用的歌词"
                    lineLrc = r.lineLrc
                    wordLrc = r.wordLrc
                } catch (e: Throwable) {
                    error = e.message ?: "歌词获取失败"
                }
            }
        }
        // 没有逐字数据时强制逐行：不给一个点了没反应的按钮
        if (wordLrc == null) kind = LyricKind.Line
        loading = false
    }

    // 候选身份：标题 / 副标题都从来源取，平台与 LRCLIB 两种形态
    val headTitle: String
    val headSubtitle: String
    when (source) {
        is LyricImportSource.Platform -> {
            val s = source.song
            headTitle = s.title
            headSubtitle = listOf(s.artist, s.album.orEmpty(), s.platform.label)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
        is LyricImportSource.Lrclib -> {
            val h = source.hit
            headTitle = h.title
            headSubtitle = listOf(h.artist, h.album, "LRCLIB")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        }
    }

    val chosen = if (kind == LyricKind.Word) wordLrc ?: lineLrc else lineLrc

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "导入歌词",
                count = when {
                    loading -> "读取中…"
                    chosen.isNotBlank() -> "${lineCount(chosen)} 行"
                    else -> null
                },
                onClose = onClose,
                accent = AppColors.white,
            )

            // 候选身份：标题 + 艺术家 · 平台
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp8),
            ) {
                Text(
                    text = headTitle.ifBlank { "未知标题" },
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = headSubtitle,
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size10,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp16),
            ) {
                LyricPreview(
                    title = "当前歌词",
                    text = currentLyric.orEmpty(),
                    emptyHint = "文件里没有歌词",
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                LyricPreview(
                    title = "要导入的歌词",
                    text = chosen,
                    emptyHint = error ?: if (loading) "读取歌词…" else "没有内容",
                    modifier = Modifier.weight(1f),
                    trailing = {
                        if (!loading && lineLrc.isNotBlank()) {
                            LyricKind.entries.forEach { k ->
                                val enabled = k != LyricKind.Word || wordLrc != null
                                SegmentChip(
                                    text = k.label,
                                    selected = kind == k,
                                    enabled = enabled,
                                    onClick = { if (enabled) kind = k },
                                )
                                Spacer(modifier = Modifier.width(AppSpacing.sp4))
                            }
                        }
                    },
                )
            }

            // 底部：取消 / 导入歌词（这一页隐藏了常驻底栏，按钮就占它原来的位置）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp16)
                    .padding(top = AppSpacing.sp8, bottom = AppSpacing.sp16),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .border(1.dp, AppColors.whiteA15, RoundedCornerShape(AppRadius.pill))
                        .clickableRipple(onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("取消", color = AppColors.textSecondary, fontSize = AppFontSize.size15)
                }
                val canImport = !loading && chosen.isNotBlank()
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(if (canImport) AppColors.white else AppColors.whiteA05)
                        .then(
                            if (canImport) Modifier.clickableRipple { onImport(chosen) } else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (loading) "读取歌词…" else "导入歌词",
                        color = if (canImport) AppColors.surface else AppColors.whiteA25,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 一块只读歌词：标题行（行数 + [trailing] + 复制按钮）+ 等宽文本区，内部自己滚 */
@Composable
private fun LyricPreview(
    title: String,
    text: String,
    emptyHint: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (text.isBlank()) title else "$title · ${lineCount(text)} 行",
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
            Spacer(modifier = Modifier.width(AppSpacing.sp6))
            CopyAction(
                enabled = text.isNotBlank(),
                copied = copied,
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("lyrics", text))
                    copied = true
                },
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA05)
                .border(1.dp, AppColors.whiteA05, RoundedCornerShape(AppRadius.r10))
                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp10),
        ) {
            if (text.isBlank()) {
                Text(
                    text = emptyHint,
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size13,
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        text = text,
                        color = AppColors.textPrimary,
                        style = TextStyle(
                            fontSize = AppFontSize.size13,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        }
    }
}

private fun lineCount(text: String): Int = text.count { it == '\n' } + 1

/**
 * 「复制」动作：28dp 热区 + 自绘复制图标（两张叠起来的圆角矩形）。
 *
 * 不自带 `ContentCopy`：本工程只依赖 `material-icons-core`，那一套里没有复制图标
 * （为了一枚图标引整包扩展图标集不划算）。画法与 `SearchBarMagnifierGlyph` 同一套路。
 * 复制成功后图标换成对勾并提亮 1.2s —— Android 13+ 的系统提示条不一定在所有机型上都出现，
 * 自己给一次明确的反馈更稳。
 */
@Composable
private fun CopyAction(enabled: Boolean, copied: Boolean, onClick: () -> Unit) {
    val tint = when {
        copied -> AppColors.textPrimary
        enabled -> AppColors.textSecondary
        else -> AppColors.white.copy(alpha = 0.22f)
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .then(if (enabled) Modifier.clickableRipple(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (copied) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已复制",
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
        } else {
            CopyGlyph(color = tint, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun CopyGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.3.dp.toPx()
        val radius = 2.dp.toPx()
        val box = Size(size.width * 0.62f, size.height * 0.62f)
        // 后一张（左上）
        drawRoundRect(
            color = color,
            topLeft = Offset(1f, 1f),
            size = box,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
        // 前一张（右下）
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - box.width - 1f, size.height - box.height - 1f),
            size = box,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke),
        )
    }
}
