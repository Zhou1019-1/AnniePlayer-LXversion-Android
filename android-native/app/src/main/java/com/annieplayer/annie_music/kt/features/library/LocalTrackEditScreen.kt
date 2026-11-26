package com.annieplayer.annie_music.kt.features.library

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.TagEditForm
import com.annieplayer.annie_music.kt.data.local.diffBag
import com.annieplayer.annie_music.kt.data.local.hasBlockingError
import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.WriteResult
import com.annieplayer.annie_music.kt.data.local.warnings
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单曲「编辑歌曲元数据」页（全屏，L1 页面栈的一层）。
 *
 * 字段集合与打标端（`DownloadService.buildTagBag`）**逐项对齐**：13 个文本键 + 内嵌封面。
 * 参考图里还有「视频封面 / 伴奏 / MV / 评分 / TTML 歌词」——那几项 `AnnieTag` 做不到
 * （要么要往容器里塞新轨道，要么键不在任何一端会读的集合里），所以没有对应控件。
 *
 * ## 为什么是长滚动 + 两个二级入口，而不是一页塞满
 * 13 个字段加封面是一屏放不下的（参考图自身就是 1272×8497 的长截屏）。这里按语义分四段，
 * 歌词单独进全屏编辑页 —— 把整段 LRC 塞进滚动区会让页面多出二十屏，还挤掉键盘的空间。
 *
 * ## 数据源
 * [tracks] 直接来自 `LocalLibraryViewModel.state`，不是路由里带的快照：
 * 保存成功后 VM 一更新，本页顶部的身份卡（封面/标题/歌手）就跟着变，不会出现
 * 「保存成功、页面还显示旧值」。
 *
 * @param onApply 真正写盘的入口（宿主转给 `LocalLibraryViewModel.applyTagEdit`）。
 *   同步返回结果 —— 编辑页要据此决定「关页面」还是「留在页内显示失败原因」。
 */
@Composable
fun LocalTrackEditScreen(
    path: String,
    tracks: List<LocalTrackMeta>,
    isTop: Boolean,
    onClose: () -> Unit,
    /**
     * 写盘入口：`(标签, 要另存的 lrc 文本)`。
     *
     * 第二个参数留给宿主的兼容位（现在恒为 null）：`.lrc` 已改成「保存 .lrc」独立按钮，
     * 不再跟着整页保存一起写。
     */
    onApply: (TagBag, String?) -> WriteResult,
    /** 「保存 .lrc」按钮：把当前这版歌词立刻写成同目录 `{文件名}.lrc`，返回是否成功 */
    onExportLrc: (lyric: String) -> Boolean = { false },
    /** 「保存封面」按钮：把封面导出成同目录 `cover.jpg/png`，返回是否成功 */
    onExportCover: (cover: CoverBytes?) -> Boolean = { false },
    /** 顶栏搜索图标：压入「搜索元数据」页 */
    onOpenSearch: () -> Unit = {},
    /** 顶栏「词」按钮：压入「搜索歌词」页（同一批平台，只是拉到的是歌词） */
    onOpenLyricSearch: () -> Unit = {},
    /** 「搜索歌词」导入回来的一次性回填（**只替换歌词文本**，别的字段不动） */
    pendingLyric: String? = null,
    onLyricConsumed: () -> Unit = {},
    /** 搜索页导入回来的一次性回填（宿主从 UiState 取；编辑页取走后立刻回调清空） */
    importedForm: TagEditForm? = null,
    onImportedConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val fromState = tracks.firstOrNull { it.path == path }
    // 从播放页 / 队列的 ⋮ 进来时曲库可能还没扫过（VM 的 tracks 是空的）——
    // 用索引兜底，否则会误报「这首歌已经不在曲库里了」。
    var fallback by remember(path) { mutableStateOf<LocalTrackMeta?>(null) }
    var looked by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, fromState) {
        if (fromState != null) return@LaunchedEffect
        fallback = withContext(Dispatchers.IO) { LocalLibraryStore.trackOf(path) }
        looked = true
    }
    val data = fromState ?: fallback
    if (data == null) {
        EmptyEditState(
            onClose = onClose,
            message = if (looked) "这首歌已经不在曲库里了" else "读取文件信息…",
            modifier = modifier,
        )
        return
    }
    val meta = data

    // 歌词以**文件**为准，不用索引里那份：索引的 `lyric` 来自 TagLib，而 DSDIFF(.dff)/DSF 这类
    // 容器 TagLib 根本不解析它的 ID3 块 —— 结果就是「歌词写进去了、播放页也能显示，
    // 编辑页却是一片空白」。`readLyric` 走的是同一套「sidecar → AnnieTag → TagLib → ffprobe」，
    // 只针对这一首歌读一次，不影响扫描速度。
    var fileLyric by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, meta.mtime) {
        fileLyric = withContext(Dispatchers.IO) { LocalLibraryStore.readLyric(path) }
    }

    // 基线 = 文件里现在的值（`mtime` 变说明文件被改过，重新取一次基线）
    val baseline = remember(path, meta.mtime, fileLyric) {
        TagEditForm.of(meta).copy(lyric = fileLyric ?: meta.lyric.orEmpty())
    }
    var form by remember(path, meta.mtime, fileLyric) { mutableStateOf(baseline) }
    var showDiscard by remember(path) { mutableStateOf(false) }
    var forceClose by remember(path) { mutableStateOf(false) }
    var saving by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    /** 「保存 .lrc」按钮的短暂反馈（成功显示「已保存」，一会儿自己消失） */
    var lrcFlash by remember(path) { mutableStateOf<String?>(null) }
    /** 「保存封面」按钮的短暂反馈 */
    var coverFlash by remember(path) { mutableStateOf<String?>(null) }

    // 搜索元数据页导入回来的表单：直接替换（它就是「原值 + 选中字段」的合成结果）
    LaunchedEffect(importedForm) {
        val f = importedForm ?: return@LaunchedEffect
        form = f
        onImportedConsumed()
    }

    // 搜索歌词页导入回来的：**只换歌词这一格**，其它字段保持原样
    LaunchedEffect(pendingLyric) {
        val text = pendingLyric ?: return@LaunchedEffect
        form = form.copy(lyric = text)
        onLyricConsumed()
    }

    val bag = remember(form, baseline) { form.diffBag(baseline) }
    val dirty = !bag.isEmpty()
    val writable = remember(path) { AnnieTag.isWritable(File(path)) }
    val blocking = form.hasBlockingError()
    val warnByField = remember(form) { form.warnings().associate { it.field to it.message } }
    val scope = rememberCoroutineScope()

    val tryClose: () -> Unit = {
        if (!forceClose && dirty) showDiscard = true else onClose()
    }

    val doSave: () -> Unit = {
        if (!saving && writable && !blocking && dirty) {
            saving = true
            error = null
            scope.launch {
                // 原地写是几十毫秒级，但仍然是磁盘 I/O —— 不能压在主线程上
                val result = withContext(Dispatchers.IO) { onApply(bag, null) }
                saving = false
                if (result.ok) {
                    forceClose = true
                    onClose()
                } else {
                    error = result.message.ifBlank { "写入失败" }
                }
            }
        }
    }

    // ── 「保存 .lrc」/「保存封面」：各自独立的旁路导出，不参与整页保存 ──
    val exportLrc: () -> Unit = {
        val text = form.lyric
        when {
            text.isBlank() -> error = "歌词为空，没有可导出的内容"
            else -> scope.launch {
                val ok = withContext(Dispatchers.IO) { onExportLrc(text) }
                if (ok) {
                    error = null
                    lrcFlash = "已保存"
                } else {
                    error = "写入 .lrc 失败（目录不可写？）"
                    lrcFlash = "失败"
                }
            }
        }
    }
    val exportCover: () -> Unit = {
        val cover = form.cover
        when {
            form.removeCover -> error = "已标记移除封面，没有可导出的封面"
            else -> scope.launch {
                val ok = withContext(Dispatchers.IO) { onExportCover(cover) }
                if (ok) {
                    error = null
                    coverFlash = "已保存"
                } else {
                    error = "封面导出失败（文件里没有可用的封面？）"
                    coverFlash = "失败"
                }
            }
        }
    }
    // 按钮上的结果文字只停留一会儿，让「点过 / 成功」有个明确的视觉反馈
    LaunchedEffect(lrcFlash) {
        if (lrcFlash != null) {
            delay(1_400)
            lrcFlash = null
        }
    }
    LaunchedEffect(coverFlash) {
        if (coverFlash != null) {
            delay(1_400)
            coverFlash = null
        }
    }

    // 有未保存改动时，返回先弹确认（只改本页状态，绝不自己弹栈 —— AGENTS 7.3）
    BackHandler(enabled = isTop && dirty) { showDiscard = true }

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
                LocalDetailTopBar(
                    title = "编辑歌曲元数据",
                    // 三态：保存中 → 未保存 → 无事可做（不显示）
                    count = when {
                        saving -> "保存中…"
                        dirty -> "未保存"
                        else -> null
                    },
                    onClose = tryClose,
                    // 这页整体黑白：顶栏的动作强调色也跟着改成白
                    accent = AppColors.white,
                    // 两个图标动作：搜索（进搜索元数据页）、保存。
                    // 保存放在最右（拇指最容易够到，也是这页唯一的高频动作）。
                    trailing = {
                        // 「词」= 搜索歌词（文字按钮，和搜索/保存两个图标并排）
                        TopTextAction(
                            text = "词",
                            enabled = !saving,
                            onClick = onOpenLyricSearch,
                        )
                        TopIconAction(
                            icon = Icons.Default.Search,
                            description = "搜索元数据",
                            enabled = !saving,
                            onClick = onOpenSearch,
                        )
                        TopIconAction(
                            icon = Icons.Default.Check,
                            description = if (saving) "保存中" else "保存",
                            // 没改动 = 灰（点不动）；保存中同样灰，避免连点写两遍
                            enabled = dirty && !saving && writable && !blocking,
                            loading = saving,
                            onClick = { doSave() },
                        )
                    },
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.sp16),
                ) {
                    if (!writable) {
                        NoticeBar(
                            text = "此格式暂不支持写入（${meta.ext.ifBlank { "未知" }}）—— " +
                                "只能查看，保存已禁用",
                        )
                    }
                    if (error != null) {
                        NoticeBar(text = error.orEmpty(), tone = NoticeTone.Error)
                    }

                    // ── ① 封面 ──
                    EditSectionTitle("封面")
                    CoverEditor(
                        newCover = form.cover,
                        removeCover = form.removeCover,
                        existingCoverPath = meta.coverPath,
                        onPick = {
                            // 图片选择器：与「新建歌单封面」同一套（PickVisualMedia，无需读存储权限）
                            val bytes = it
                            if (bytes.size > MAX_COVER_BYTES) {
                                error = "图片超过 4 MB，请先压缩"
                            } else {
                                val cover = AnnieTag.coverOf(bytes)
                                if (cover == null) {
                                    error = "读不出这张图（只接受 JPEG / PNG）"
                                } else {
                                    error = null
                                    form = form.copy(cover = cover, removeCover = false)
                                }
                            }
                        },
                        onToggleRemove = {
                            form = if (form.removeCover) {
                                form.copy(removeCover = false)
                            } else {
                                form.copy(cover = null, removeCover = true)
                            }
                        },
                    )

                    // ── ② 基本信息 ──
                    // 右侧两个旁路动作：把当前歌词导出成 `.lrc`、把封面导出成同目录 `cover.jpg`。
                    // 它们不参与整页保存（原来那个「同时保存为 .lrc」开关就在这里，已改成按钮）。
                    EditSectionTitle(
                        text = "基本信息",
                        trailing = {
                            MiniPillButton(
                                text = lrcFlash ?: "保存 .lrc",
                                enabled = form.lyric.isNotBlank(),
                                highlight = lrcFlash != null,
                                onClick = exportLrc,
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sp6))
                            MiniPillButton(
                                text = coverFlash ?: "保存封面",
                                enabled = !form.removeCover,
                                highlight = coverFlash != null,
                                onClick = exportCover,
                            )
                        },
                    )
                    EditTextField(
                        label = "标题",
                        value = form.title,
                        hint = "写 `title`；留空时列表回落文件名",
                        onChange = { form = form.copy(title = it) },
                    )
                    EditTextField(
                        label = "艺术家",
                        value = form.artist,
                        hint = "多歌手用 `/` 或 `、` 分隔",
                        onChange = { form = form.copy(artist = it) },
                    )
                    EditTextField(
                        label = "专辑",
                        value = form.album,
                        hint = "写 `album`",
                        onChange = { form = form.copy(album = it) },
                    )
                    EditTextField(
                        label = "专辑艺术家",
                        value = form.albumArtist,
                        hint = "合辑填 `Various Artists`",
                        onChange = { form = form.copy(albumArtist = it) },
                    )

                    // ── ③ 曲目信息 ──
                    EditSectionTitle("曲目信息")
                    EditTextField(
                        label = "年份",
                        value = form.year,
                        hint = "`2023` 或 `2023-05-12`",
                        warning = warnByField["年份"],
                        onChange = { form = form.copy(year = it) },
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        EditTextField(
                            label = "音轨号",
                            value = form.track,
                            hint = "3 或 3/12",
                            numeric = true,
                            warning = warnByField["音轨号"],
                            onChange = { form = form.copy(track = it) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.sp12))
                        EditTextField(
                            label = "碟号",
                            value = form.disc,
                            hint = "1 或 1/2",
                            numeric = true,
                            warning = warnByField["碟号"],
                            onChange = { form = form.copy(disc = it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    EditTextField(
                        label = "风格",
                        value = form.genre,
                        hint = "写 `genre`",
                        onChange = { form = form.copy(genre = it) },
                    )

                    // ── ④ 版权与标注 ──
                    EditSectionTitle("版权与标注")
                    EditTextField(
                        label = "作曲",
                        value = form.composer,
                        hint = "写 `composer`",
                        onChange = { form = form.copy(composer = it) },
                    )
                    EditTextField(
                        label = "ISRC",
                        value = form.isrc,
                        hint = "12 位国际标准录音代码",
                        warning = warnByField["ISRC"],
                        onChange = { form = form.copy(isrc = it) },
                    )
                    EditTextField(
                        label = "注释 / 版本",
                        value = form.comment,
                        hint = "写 `comment`；Qobuz 的版本说明、编码器签名都在这里",
                        onChange = { form = form.copy(comment = it) },
                    )
                    EditTextField(
                        label = "版权信息",
                        value = form.copyright,
                        hint = "写 `copyright`",
                        onChange = { form = form.copy(copyright = it) },
                    )

                    // ── ⑤ 歌词：大文本框（不跳二级页，LRC 本来就该在这页里一起改）──
                    EditSectionTitle("歌词")
                    LyricMultiField(
                        lyric = form.lyric,
                        onChange = { form = form.copy(lyric = it) },
                    )

                    Spacer(modifier = Modifier.height(AppSpacing.sheetBottomInset))
                }
        }

        if (showDiscard) {
            AlertDialog(
                onDismissRequest = { showDiscard = false },
                containerColor = AppColors.surfaceLight,
                title = {
                    Text("放弃修改？", color = AppColors.textPrimary, fontSize = AppFontSize.size17)
                },
                text = {
                    Text(
                        "还有 ${bag.text.size + bag.cleared.size + if (bag.cover != null || bag.removeCover) 1 else 0} " +
                            "处改动没有写入文件。",
                        color = AppColors.textSecondary,
                        fontSize = AppFontSize.size13,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscard = false
                        forceClose = true
                        onClose()
                    }) {
                        Text("放弃", color = AppColors.danger, fontSize = AppFontSize.size13)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscard = false }) {
                        Text("继续编辑", color = AppColors.textSecondary, fontSize = AppFontSize.size13)
                    }
                },
            )
        }
    }
}

/** 索引里已经没有这首歌（文件被删 / 库被清）时的空态 —— 不给一个全空白页面。 */
@Composable
private fun EmptyEditState(
    onClose: () -> Unit,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            LocalDetailTopBar(title = "编辑歌曲元数据", onClose = onClose)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    message,
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.base,
                )
            }
        }
    }
}

// ── 封面 ──

private const val MAX_COVER_BYTES = 4 * 1024 * 1024

@Composable
private fun CoverEditor(
    newCover: CoverBytes?,
    removeCover: Boolean,
    existingCoverPath: String?,
    onPick: (ByteArray) -> Unit,
    onToggleRemove: () -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) onPick(bytes)
    }

    // 预览来源：新选的字节优先 → 否则磁盘上抽好的那张 → 都没有就占位
    val preview: Any? = when {
        newCover != null -> newCover.bytes
        removeCover -> null
        existingCoverPath != null -> "file://$existingCoverPath"
        else -> null
    }
    val sizeText = remember(newCover, existingCoverPath, removeCover) {
        when {
            newCover != null -> boundsOfBytes(newCover.bytes)
            !removeCover && existingCoverPath != null -> boundsOfFile(existingCoverPath)
            else -> null
        }?.let { (w, h) -> "$w × $h" }.orEmpty()
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(COVER_PREVIEW)
                .clip(RoundedCornerShape(AppRadius.card))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = if (removeCover) "保存后移除" else "无封面",
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.xs,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sp8),
        ) {
            PillButton(
                text = "选择图片封面",
                enabled = true,
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            PillButton(
                text = if (removeCover) "撤销移除" else "移除图片封面",
                enabled = removeCover || existingCoverPath != null,
                onClick = onToggleRemove,
            )
            if (sizeText.isNotBlank()) {
                Text(
                    text = sizeText,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.size10,
                )
            }
        }
    }
    // 只在「点了移除」时说明清楚：删的是内嵌图，不动同目录的 cover.jpg
    if (removeCover) {
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Text(
            text = "只删除文件内的封面，同目录的 cover.jpg 不会被动",
            color = AppColors.whiteA40,
            fontSize = AppFontSize.size10,
        )
    }
}

private val COVER_PREVIEW = 128.dp

/** 只读图片头拿尺寸（不解码整张图，几毫秒） */
private fun boundsOfBytes(bytes: ByteArray): Pair<Int, Int>? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}

private fun boundsOfFile(path: String): Pair<Int, Int>? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}

// ── 歌词 ──

/**
 * 歌词：**页内大文本框**，不跳二级页。
 *
 * 歌词是这页里唯一的长文本，但它是「跟其它字段一起改完再一起存」的一部分 ——
 * 跳出去单独一页会让「编辑歌词」和「编辑标签」变成两个提交路径，返回时还要处理
 * 「歌词改了但没保存」这种二级状态。给一个 200dp 高的框、内部自己滚动就够了。
 *
 * 用等宽字体 + 13sp：LRC 的时间戳对齐很有用，逐字歌词一行上百字符也看得清。
 */
@Composable
private fun LyricMultiField(lyric: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val lines = remember(lyric) { if (lyric.isBlank()) 0 else lyric.count { it == '\n' } + 1 }
    Column(modifier = Modifier.padding(bottom = AppSpacing.sp12)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (lines == 0) "未填写" else "$lines 行 · ${lyric.length} 字符",
                color = if (lines == 0) AppColors.textTertiary else AppColors.textSecondary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "写 `LYRICS` / `USLT` / `©lyr`",
                color = AppColors.whiteA40,
                fontSize = AppFontSize.size10,
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LYRIC_BOX_HEIGHT)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA05)
                .border(
                    width = 1.dp,
                    color = if (focused) AppColors.white60 else AppColors.whiteA05,
                    shape = RoundedCornerShape(AppRadius.r10),
                )
                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp10),
        ) {
            BasicTextField(
                value = lyric,
                onValueChange = onChange,
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { focused = it.isFocused },
                textStyle = TextStyle(
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size13,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(AppColors.white),
                decorationBox = { inner ->
                    Box {
                        if (lyric.isEmpty()) {
                            Text(
                                text = "粘贴或输入 LRC 歌词，例如\n[00:00.00]第一行",
                                color = AppColors.textTertiary,
                                fontSize = AppFontSize.size13,
                                lineHeight = 20.sp,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

private val LYRIC_BOX_HEIGHT = 200.dp

/**
 * 顶栏的文字动作（「词」）：44dp 热区 + 15sp 文字。
 *
 * 与 [TopIconAction] 尺寸、禁用态规则一致（同一枚白字，只是把透明度压到 22%），
 * 这样三个动作并排时基线对齐、不会一个高一个低。
 */
@Composable
private fun TopTextAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .then(if (enabled) Modifier.clickableRipple(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) AppColors.textPrimary else AppColors.white.copy(alpha = 0.22f),
            fontSize = AppFontSize.size15,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 顶栏的图标动作：44dp 热区 + 24dp 图标（比文字动作好按，也符合"按钮大一点"）。
 *
 * [loading] 为 true 时**换成转圈**（不再画图标）：保存是写磁盘，FLAC 全量重写 / 大 MP4
 * 能有几百毫秒，光把图标变灰用户会以为"点了没反应"。
 */
@Composable
private fun TopIconAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .then(if (enabled) Modifier.clickableRipple(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = AppColors.white,
                strokeWidth = 2.dp,
                modifier = Modifier.size(AppIconSize.icon20),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = description,
                // 不可用态**不用灰色**：同一枚白图标、把透明度压到很低。
                // （`whiteA25` 这类"白色档位"在本主题里实际是 70% 白，压不下去，
                //  所以这里直接给 alpha，保证"还能看见轮廓，但明显是关着的"）
                tint = if (enabled) AppColors.textPrimary else AppColors.white.copy(alpha = 0.22f),
                modifier = Modifier.size(AppIconSize.icon24),
            )
        }
    }
}

// ── 通用小件 ──

/**
 * 区块标题（`基本信息` 这种小字 + 一条分隔线）。
 *
 * [trailing] 画在同一行**靠右**：区块级的旁路动作放在这里，比挤在正文里更省纵向空间
 * （「保存 .lrc」/「保存封面」就是这么放的）。
 */
@Composable
private fun EditSectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Column(modifier = Modifier.padding(top = AppSpacing.sp20, bottom = AppSpacing.sp8)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (trailing != null) trailing()
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AppColors.whiteA05),
        )
    }
}

/**
 * 小号描边胶囊：与「选择图片封面」同款，只是高度 30dp、字号 10sp。
 *
 * 点击反馈靠 [clickableRipple]（本工程的白涟漪）；导出成功后由调用方把文字换成
 * 「已保存」并把描边提亮成纯白，1.4s 自己复位。
 */
@Composable
private fun MiniPillButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(AppRadius.pill))
            .border(
                width = 1.dp,
                color = when {
                    highlight -> AppColors.white
                    enabled -> AppColors.whiteA20
                    else -> AppColors.whiteA05
                },
                shape = RoundedCornerShape(AppRadius.pill),
            )
            .then(if (enabled) Modifier.clickableRipple(onClick) else Modifier)
            .padding(horizontal = AppSpacing.sp12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = when {
                highlight -> AppColors.textPrimary
                enabled -> AppColors.textSecondary
                else -> AppColors.whiteA25
            },
            fontSize = AppFontSize.size10,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun EditTextField(
    label: String,
    value: String,
    hint: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    warning: String? = null,
    numeric: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier.padding(bottom = AppSpacing.sp12)) {
        Text(
            text = label,
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size13,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA05)
                .border(
                    width = 1.dp,
                    color = if (focused) AppColors.white60 else AppColors.whiteA05,
                    shape = RoundedCornerShape(AppRadius.r10),
                )
                .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = TextStyle(
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size15,
                ),
                cursorBrush = SolidColor(AppColors.white),
                keyboardOptions = if (numeric) {
                    KeyboardOptions(keyboardType = KeyboardType.Number)
                } else {
                    KeyboardOptions.Default
                },
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = hint,
                                color = AppColors.textTertiary,
                                fontSize = AppFontSize.size15,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            if (value.isNotEmpty()) {
                Text(
                    text = "✕",
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.size13,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .clickableRipple { onChange("") }
                        .padding(AppSpacing.sp4),
                )
            }
        }
        if (warning != null) {
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(text = warning, color = AppColors.danger, fontSize = AppFontSize.size10)
        }
    }
}

@Composable
private fun PillButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.pill))
            .border(
                width = 1.dp,
                color = if (enabled) AppColors.whiteA20 else AppColors.whiteA05,
                shape = RoundedCornerShape(AppRadius.pill),
            )
            .then(if (enabled) Modifier.clickableRipple(onClick) else Modifier)
            .padding(vertical = AppSpacing.sp10),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) AppColors.textPrimary else AppColors.whiteA25,
            fontSize = AppFontSize.size13,
        )
    }
}

private enum class NoticeTone { Info, Error }

@Composable
private fun NoticeBar(text: String, tone: NoticeTone = NoticeTone.Info) {
    val color = if (tone == NoticeTone.Error) AppColors.danger else AppColors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.sp8)
            .clip(RoundedCornerShape(AppRadius.r8))
            .background(if (tone == NoticeTone.Error) AppColors.danger.copy(alpha = 0.12f) else AppColors.whiteA05)
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
    ) {
        Text(text = text, color = color, fontSize = AppFontSize.size13)
    }
}
