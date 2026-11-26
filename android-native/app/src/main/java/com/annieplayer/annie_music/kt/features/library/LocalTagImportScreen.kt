package com.annieplayer.annie_music.kt.features.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.enrichMetadataCandidate
import com.annieplayer.annie_music.kt.data.fetchLyricCandidate
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.TagEditForm
import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagMapper
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetBytes
import com.annieplayer.annie_music.kt.data.sources.splitArtistNames
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 歌词导入的两种形态。Apple 的接口一次请求就能出两份，其它平台看各自实现。 */
internal enum class LyricKind(val label: String) {
    Word("逐字"),
    Line("逐行"),
}

/**
 * 一条可导入字段。
 *
 * [candidate] 为空 = 该平台没给这一项 → **强制不可勾选**（勾了就是把本地已有的值覆盖成空）。
 * [local] 只用于对比展示（"原：xxx"），不参与写入。
 */
private data class ImportField(
    val key: String,
    val label: String,
    val local: String,
    val candidate: String,
) {
    val enabled: Boolean get() = candidate.isNotBlank()
}

/** 字段 → 写回表单的 setter。加字段只改这一张表 + [buildFields]。 */
private val IMPORT_SETTERS: Map<String, (TagEditForm, String) -> TagEditForm> = mapOf(
    "title" to { f, v -> f.copy(title = v) },
    "artist" to { f, v -> f.copy(artist = v) },
    "album" to { f, v -> f.copy(album = v) },
    "albumArtist" to { f, v -> f.copy(albumArtist = v) },
    "year" to { f, v -> f.copy(year = v) },
    "track" to { f, v -> f.copy(track = v) },
    "disc" to { f, v -> f.copy(disc = v) },
    "genre" to { f, v -> f.copy(genre = v) },
    "composer" to { f, v -> f.copy(composer = v) },
    "isrc" to { f, v -> f.copy(isrc = v) },
    "comment" to { f, v -> f.copy(comment = v) },
    "copyright" to { f, v -> f.copy(copyright = v) },
)

/**
 * 用**专辑级**详情补齐候选身上缺的字段（导入页打开时一次请求）。
 *
 * 为什么需要它：各平台的**搜索**接口只给标题/歌手/专辑/封面/时长，专辑艺术家、轨号、碟号、
 * 发行日期通常要问一次专辑才拿得到（Apple 更是连版权都挂在专辑上）。
 *
 * 规则：**候选已有的值一律不覆盖**（平台搜索接口给的更接近用户搜的那条），
 * 只填空。`albumArtist` 例外 —— 以专辑为准，因为 QQ 的搜索 mapper 把曲目艺人串塞进了
 * 这个字段，那不是专辑艺术家。
 */
/**
 * 平台合并串的拆法（**导入专用**，比 `splitArtistNames` 保守）。
 *
 * `&` / `,` / `、` 任何位置都算分隔（平台协议就是这么拼的）；
 * `/` **只有带空格才算** —— `K/DA`、`AC/DC` 是名字的一部分，拆开就变成两个歌手了
 * （`splitArtistNames` 会无脑按 `/` 拆，所以这里不能直接用它）。
 */
private val IMPORT_ARTIST_SEPARATORS = Regex("""\s*[&,、]\s*|\s+/\s*|\s*/\s+""")

/**
 * 导入时写进「艺术家」的文本：**与下载打标同一条规则**（多歌手 `A / B`）。
 *
 * 平台给的常是合并串（Apple 的 `Kygo & Ellie Goulding`、酷我的 `A&B`），直接抄会把
 * 分隔符原样写进文件，与「下载下来的歌」不一致。优先用结构化名单 [extraNames]
 * （Apple 由 `enrichAppleSong` 补），没有才按 [IMPORT_ARTIST_SEPARATORS] 拆。
 *
 * 已知取舍：`Simon & Garfunkel` 这种名字里带 `&` 的乐队在**平台串**里会被拆开
 * （平台侧 `&` 就是分隔符）；`K/DA`、`AC/DC` 这种无空格斜杠不会被拆。
 */
internal fun importArtistText(source: Song, extraNames: List<String>): String {
    val names = extraNames.ifEmpty {
        source.artist.split(IMPORT_ARTIST_SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    return TagMapper.artistOf(names, source.artist)
}

private fun buildFields(
    original: TagEditForm,
    source: Song,
    /** 多歌手的结构化名单（Apple 要靠 `enrichAppleSong` 补，其它平台搜索结果自带） */
    artistNames: List<String>,
): List<ImportField> = listOf(
    ImportField(
        "title",
        "标题",
        original.title,
        source.title,
    ),
    ImportField(
        "artist",
        "艺术家",
        original.artist,
        importArtistText(source, artistNames),
    ),
    ImportField("album", "专辑", original.album, source.album.orEmpty()),
    ImportField("albumArtist", "专辑艺术家", original.albumArtist, source.albumArtist.orEmpty()),
    ImportField("year", "年份", original.year, source.releaseDate.orEmpty()),
    ImportField("track", "音轨号", original.track, source.trackNumber.orEmpty()),
    ImportField("disc", "碟号", original.disc, source.discNumber.orEmpty()),
    ImportField("genre", "风格", original.genre, source.genre.orEmpty()),
    ImportField("composer", "作曲", original.composer, source.composer.orEmpty()),
    ImportField("isrc", "ISRC", original.isrc, source.isrc.orEmpty()),
    ImportField("comment", "注释 / 版本", original.comment, source.version.orEmpty()),
    ImportField("copyright", "版权", original.copyright, source.copyright.orEmpty()),
)

/**
 * 导入元数据页（**独立页面，压进 pageStack**，不是浮层）。
 *
 * 为什么是页面而不是 sheet：
 * 1. 内容比半屏多得多（14 项 + 双封面），sheet 里必然要内部滚动，还会被迷你播放条压住；
 * 2. 它有"选完就走"的语义，返回键应该退回搜索页，和编辑页/搜索页串成一条栈最自然。
 *
 * 视觉分三段：**身份**（候选标题/艺术家/平台）→ **封面对比**（当前 ←→ 候选，随内容一起滚）
 * → **清单**（分区标题 + 值块 + 复选框，勾选态用主色淡底 + 描边）。
 *
 * 只负责"合成一份表单"，**不落盘**：写文件仍由编辑页的保存按钮负责。
 */
@Composable
fun LocalTagImportScreen(
    path: String,
    candidate: Song,
    tracks: List<LocalTrackMeta>,
    onClose: () -> Unit,
    onImport: (TagEditForm) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fromState = tracks.firstOrNull { it.path == path }
    var fallback by remember(path) { mutableStateOf<LocalTrackMeta?>(null) }
    LaunchedEffect(path, fromState) {
        if (fromState != null) return@LaunchedEffect
        fallback = withContext(Dispatchers.IO) { LocalLibraryStore.trackOf(path) }
    }
    val meta = fromState ?: fallback
    // 歌词以**文件**为准：索引里那份来自 TagLib，dff / dsf 的 ID3 块 TagLib 不解析，
    // 只用索引会出现「文件里明明有歌词，这页却显示 0 行」。
    var fileLyric by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path, meta?.mtime) {
        fileLyric = withContext(Dispatchers.IO) { LocalLibraryStore.readLyric(path) }
    }
    val original = remember(meta?.path, meta?.mtime, fileLyric) {
        meta?.let { TagEditForm.of(it).copy(lyric = fileLyric ?: it.lyric.orEmpty()) } ?: TagEditForm()
    }
    val originalCoverPath = meta?.coverPath

    // 详情补全：轨号/碟号/专辑艺术家/发行日期/版权/结构化歌手，全在 data 层的门面里
    // （各平台分散在不同接口上，导入页不该自己拼）。
    var enriched by remember(candidate.id) { mutableStateOf(candidate) }
    LaunchedEffect(candidate.id) {
        enriched = runCatching {
            withContext(Dispatchers.IO) { enrichMetadataCandidate(candidate) }
        }.getOrDefault(candidate)
    }

    // 兜底：详情也拿不到结构化名单时，按**平台字段**规则拆一次（认 `&`），
    // 至少能写出 `A / B`；再不行才退回原串
    val effectiveArtistNames = enriched.artistNames.ifEmpty {
        if (candidate.platform == MusicPlatform.apple) splitArtistNames(enriched.artist)
        else emptyList()
    }

    val fields = remember(enriched, original, effectiveArtistNames) {
        buildFields(original, enriched, effectiveArtistNames)
    }
    var checked by remember(candidate, fields) {
        mutableStateOf(fields.filter { it.enabled }.map { it.key }.toSet())
    }
    var includeLyric by remember(candidate) { mutableStateOf(true) }
    var includeCover by remember(candidate) { mutableStateOf(true) }
    var lyricKind by remember(candidate) { mutableStateOf(LyricKind.Word) }

    var lineLrc by remember(candidate) { mutableStateOf("") }
    var wordLrc by remember(candidate) { mutableStateOf<String?>(null) }
    var lyricLoading by remember(candidate) { mutableStateOf(false) }
    var lyricError by remember(candidate) { mutableStateOf<String?>(null) }

    var coverBytes by remember(candidate) { mutableStateOf<CoverBytes?>(null) }
    var coverLoading by remember(candidate) { mutableStateOf(false) }
    var coverError by remember(candidate) { mutableStateOf<String?>(null) }

    // 歌词：统一走 `fetchLyricCandidate`（Apple 中转 → wrapper 兜底、Qobuz token、KKBOX 会话、
    // 其它平台各自的 LyricSource 都在那个门面里）。以前这一段自己拼了 60 行，
    // 结果「歌词搜索」那条链路成了第二份实现 —— 加一个平台就得改两处。
    LaunchedEffect(candidate) {
        lyricLoading = true
        lyricError = null
        lineLrc = ""
        wordLrc = null
        try {
            val r = withContext(Dispatchers.IO) { fetchLyricCandidate(candidate) }
            if (r.lineLrc.isBlank()) error("歌词为空")
            lineLrc = r.lineLrc
            wordLrc = r.wordLrc
        } catch (e: Throwable) {
            lyricError = e.message ?: "歌词获取失败"
        }
        if (wordLrc == null) lyricKind = LyricKind.Line
        lyricLoading = false
    }

    // 封面：先下好，用户勾的时候不用等
    LaunchedEffect(candidate) {
        val url = candidate.cover
        if (url.isBlank() || !url.startsWith("http")) {
            coverBytes = null
            return@LaunchedEffect
        }
        coverLoading = true
        coverError = null
        runCatching { httpGetBytes(url) }
            .onSuccess { bytes ->
                coverBytes = AnnieTag.coverOf(bytes)
                if (coverBytes == null) coverError = "封面格式不支持"
            }
            .onFailure { coverError = it.message ?: "封面下载失败" }
        coverLoading = false
    }

    val chosenLyric: String = when {
        !includeLyric -> ""
        lyricKind == LyricKind.Word -> wordLrc ?: lineLrc
        else -> lineLrc
    }
    val selectableKeys = fields.filter { it.enabled }.map { it.key }.toSet()
    val allSelected = selectableKeys.isNotEmpty() && checked.containsAll(selectableKeys)
    val selectedCount = checked.size +
        (if (includeLyric && chosenLyric.isNotBlank()) 1 else 0) +
        (if (includeCover && coverBytes != null) 1 else 0)
    val selectableCount = selectableKeys.size +
        (if (lineLrc.isNotBlank()) 1 else 0) +
        (if (coverBytes != null) 1 else 0)

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = "导入元数据",
                count = if (selectableCount > 0) "已选 $selectedCount" else null,
                onClose = onClose,
                accent = AppColors.white,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.sp16),
            ) {
                IdentityHeader(candidate)

                // 封面对比：跟着内容一起滚（它在长列表里是"参考信息"，不是常驻工具条）
                val candidateModel: Any? = coverBytes?.bytes ?: candidate.cover
                val candidateSize = remember(coverBytes) { coverBytes?.let { boundsOfBytes(it.bytes) } }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sp12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverCell(
                        label = "当前",
                        model = originalCoverPath?.let { "file://$it" },
                        sizeText = null,
                        selected = null,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "→",
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.size17,
                        modifier = Modifier.padding(horizontal = AppSpacing.sp10),
                    )
                    CoverCell(
                        label = when {
                            coverLoading -> "下载中"
                            coverError != null -> "获取失败"
                            coverBytes == null -> "无封面"
                            else -> "候选 · ${candidate.platform.label}"
                        },
                        model = candidateModel,
                        sizeText = candidateSize?.let { "${it.first} × ${it.second}" },
                        selected = includeCover && coverBytes != null,
                        onClick = { if (coverBytes != null) includeCover = !includeCover },
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionHeader(
                    title = "元数据",
                    trailing = if (selectableKeys.isEmpty()) null else {
                        "已选 ${checked.size}/${selectableKeys.size} · " + if (allSelected) "全不选" else "全选"
                    },
                    onTrailingClick = if (selectableKeys.isEmpty()) null else {
                        { checked = if (allSelected) emptySet() else selectableKeys }
                    },
                )

                for (field in fields) {
                    FieldBlock(
                        label = field.label,
                        candidate = field.candidate,
                        originalValue = field.local,
                        checked = field.key in checked,
                        enabled = field.enabled,
                        onToggle = {
                            checked = if (field.key in checked) checked - field.key
                            else checked + field.key
                        },
                    )
                }

                SectionHeader(title = "歌词", trailing = null, onTrailingClick = null)
                ValueBlock(
                    label = "内容",
                    originalValue = lineCountText(original.lyric),
                    enabled = lineLrc.isNotBlank(),
                    checked = includeLyric,
                    onToggle = { includeLyric = !includeLyric },
                ) {
                    Text(
                        text = when {
                            lyricLoading -> "获取中…"
                            lyricError != null -> "获取失败"
                            else -> lineCountText(lineLrc)
                        },
                        color = when {
                            lyricError != null -> AppColors.danger
                            lineLrc.isBlank() -> AppColors.whiteA25
                            else -> AppColors.textPrimary
                        },
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.Medium,
                    )
                    if (!lyricLoading && lineLrc.isNotBlank()) {
                        Spacer(modifier = Modifier.height(AppSpacing.sp6))
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp8)) {
                            LyricKind.entries.forEach { kind ->
                                val kindEnabled = kind != LyricKind.Word || wordLrc != null
                                SegmentChip(
                                    text = kind.label,
                                    selected = lyricKind == kind,
                                    enabled = kindEnabled,
                                    onClick = { lyricKind = kind },
                                )
                            }
                        }
                    }
                    if (lyricError != null) {
                        Spacer(modifier = Modifier.height(AppSpacing.sp4))
                        Text(
                            text = lyricError.orEmpty(),
                            color = AppColors.danger,
                            fontSize = AppFontSize.size10,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(AppSpacing.sp16))
            }

            // ── 底部按钮：贴到屏幕底（这一页隐藏了常驻底栏，按钮就占它原来的位置）──
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
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(if (selectedCount > 0) AppColors.white else AppColors.whiteA05)
                        .then(
                            if (selectedCount > 0) {
                                Modifier.clickableRipple {
                                    onImport(
                                        applySelection(
                                            fields, checked, original,
                                            chosenLyric, includeCover, coverBytes,
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedCount > 0) "导入 $selectedCount / $selectableCount 项" else "导入",
                        color = if (selectedCount > 0) AppColors.surface else AppColors.whiteA25,
                        fontSize = AppFontSize.size15,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 顶部身份块：候选的标题 + 艺术家 · 平台角标。 */
@Composable
private fun IdentityHeader(source: Song) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sp4)) {
        Text(
            text = source.title.ifBlank { "未知标题" },
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = source.artist.ifBlank { "未知艺术家" },
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            val label = source.platform.label
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size10,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.white12)
                        .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp2),
                )
            }
        }
    }
}

/**
 * 一格封面：方形、圆角、可选勾选徽标。
 *
 * [selected] 为 null = 不可勾（"当前"那一格）；非 null 时右上角画徽标，整格可点。
 */
@Composable
private fun CoverCell(
    label: String,
    model: Any?,
    sizeText: String?,
    selected: Boolean?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(AppRadius.card))
                .background(AppColors.whiteA05)
                .then(
                    if (selected == true) {
                        Modifier.border(1.5.dp, AppColors.white, RoundedCornerShape(AppRadius.card))
                    } else {
                        Modifier
                    },
                )
                .then(if (onClick != null) Modifier.clickableRipple(onClick) else Modifier),
        ) {
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "无",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.size10,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (selected != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(AppSpacing.sp6)
                        .size(22.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(
                            if (selected) AppColors.white else AppColors.surface.copy(alpha = 0.6f),
                        )
                        .border(
                            1.dp,
                            if (selected) AppColors.textPrimary else AppColors.whiteA25,
                            RoundedCornerShape(AppRadius.pill),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Text("✓", color = AppColors.surface, fontSize = AppFontSize.size10)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (selected == true) AppColors.textPrimary else AppColors.textTertiary,
                fontSize = AppFontSize.size10,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!sizeText.isNullOrBlank()) {
                Text(
                    text = sizeText,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.size10,
                )
            }
        }
    }
}

/** 分区标题：`标题 ─────────  右侧动作` */
@Composable
private fun SectionHeader(
    title: String,
    trailing: String?,
    onTrailingClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.sp16, bottom = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AppColors.textSecondary,
            fontSize = AppFontSize.size10,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AppColors.whiteA05),
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = trailing,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(AppRadius.pill))
                    .then(if (onTrailingClick != null) Modifier.clickableRipple(onTrailingClick) else Modifier)
                    .padding(horizontal = AppSpacing.sp6, vertical = AppSpacing.sp2),
            )
        }
    }
}

/** 一个普通字段：`标签 + 原值小字` → `值块 + 复选框` */
@Composable
private fun FieldBlock(
    label: String,
    candidate: String,
    originalValue: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    ValueBlock(
        label = label,
        originalValue = originalValue,
        enabled = enabled,
        checked = checked,
        onToggle = onToggle,
    ) {
        Text(
            text = candidate.ifBlank { "—" },
            color = if (enabled) AppColors.textPrimary else AppColors.whiteA25,
            fontSize = AppFontSize.size15,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 值块：深色圆角 + 右侧复选框。
 *
 * 勾选态 = 主色淡底 + 主色描边（不用"整块卡片 + 阴影"，那在深色主题里会糊成一团）；
 * 上方一行是「标签 … 原：旧值」，把对比信息压在最小的视觉权重上。
 */
@Composable
private fun ValueBlock(
    label: String,
    originalValue: String,
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val active = checked && enabled
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.sp10)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size10,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "原：${originalValue.ifBlank { "—" }}",
                color = AppColors.whiteA40,
                fontSize = AppFontSize.size10,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(if (active) AppColors.white12 else AppColors.whiteA05)
                .then(
                    if (active) {
                        Modifier.border(1.dp, AppColors.white60, RoundedCornerShape(AppRadius.r10))
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = AppSpacing.sp12,
                    end = AppSpacing.sp4,
                    top = AppSpacing.sp6,
                    bottom = AppSpacing.sp6,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) { content() }
            Checkbox(
                checked = active,
                onCheckedChange = { if (enabled) onToggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = AppColors.white,
                    uncheckedColor = AppColors.whiteA25,
                    disabledCheckedColor = AppColors.whiteA15,
                    disabledUncheckedColor = AppColors.whiteA05,
                    checkmarkColor = AppColors.surface,
                ),
            )
        }
    }
}

/** 勾选的字段 + 歌词 + 封面 → 一份新的表单（未落盘）。 */
private fun applySelection(
    fields: List<ImportField>,
    checked: Set<String>,
    original: TagEditForm,
    chosenLyric: String,
    includeCover: Boolean,
    coverBytes: CoverBytes?,
): TagEditForm {
    var form = original
    for (field in fields) {
        if (field.key !in checked) continue
        val setter = IMPORT_SETTERS[field.key] ?: continue
        form = setter(form, field.candidate)
    }
    if (chosenLyric.isNotBlank()) form = form.copy(lyric = chosenLyric)
    if (includeCover && coverBytes != null) form = form.copy(cover = coverBytes, removeCover = false)
    return form
}

private fun lineCountText(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return "无"
    return "${t.count { it == '\n' } + 1} 行"
}

/** 只读图片头拿尺寸（不解码整张图） */
private fun boundsOfBytes(bytes: ByteArray): Pair<Int, Int>? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}

@Composable
internal fun SegmentChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = when {
        !enabled -> AppColors.whiteA25
        selected -> AppColors.textPrimary
        else -> AppColors.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(if (selected && enabled) AppColors.whiteA15 else AppColors.whiteA05)
            .then(if (enabled) Modifier.clickableRipple(onClick) else Modifier)
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp6),
    ) {
        Text(text = text, color = color, fontSize = AppFontSize.size10, fontWeight = FontWeight.Medium)
    }
}
