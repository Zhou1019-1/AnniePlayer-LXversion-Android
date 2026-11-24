package com.annieplayer.annie_music.kt.features.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.apple.appleMusicStorefront
import com.annieplayer.annie_music.kt.data.sources.common.RankQuery
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.sources.lyric.LrclibHit
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.SearchBarMagnifierGlyph
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「搜索元数据」页（全屏，L1 页面栈）。
 *
 * 关键词取本地歌的**标题 + 歌手**，进来就打一次五平台并行搜索（用户不用先点一次按钮）。
 * 布局照参考图：顶栏（返回 / 标题 / 地区）→ 搜索框 + 搜索按钮 → Tab → 结果列表。
 * **没有排序控件**：顺序固定「AM 最匹配的那条置顶，其余按相关度」（`recommendedMetadata`）。
 *
 * 点一条结果 → **压栈进入导入页**（[LocalTagImportScreen]）→ 导入后回填给编辑页（不落盘）。
 *
 * `mode = Lyric` 时是同一个页面改成「搜索歌词」：平台范围完全一致（Apple / 网易 / QQ /
 * 酷狗 / 酷我 —— KKBOX 要会话、Qobuz 没有公开歌词接口，都不参与），
 * 只是点一条结果之后压的是歌词对比页。搜索逻辑与 UI 没有第二份。
 *
 * @param onOpenImport 点了某条结果：宿主负责压入 `LocalTagImportRoute` / `LocalLyricImportRoute`
 */
@Composable
fun LocalMetadataSearchScreen(
    path: String,
    tracks: List<LocalTrackMeta>,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenImport: (Song) -> Unit,
    /** 歌词模式下点了 LRCLIB 的一条命中：宿主压入歌词对比页 */
    onOpenLyricHit: (LrclibHit) -> Unit = {},
    modifier: Modifier = Modifier,
    mode: MetadataSearchMode = MetadataSearchMode.Metadata,
) {
    val fromState = tracks.firstOrNull { it.path == path }
    // 从播放页进来时曲库可能还没扫过（VM 的 tracks 为空）→ 用索引兜底，同编辑页
    var fallback by remember(path) { mutableStateOf<LocalTrackMeta?>(null) }
    var looked by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path, fromState) {
        if (fromState != null) return@LaunchedEffect
        fallback = withContext(Dispatchers.IO) { LocalLibraryStore.trackOf(path) }
        looked = true
    }
    val meta = fromState ?: fallback
    if (meta == null) {
        MetadataEmptyScaffold(
            onClose = onClose,
            message = if (looked) "这首歌已经不在曲库里了" else "读取文件信息…",
            modifier = modifier,
        )
        return
    }

    val rankQuery = remember(meta.title, meta.artist, meta.album) {
        RankQuery(meta.title, meta.artist, meta.album)
    }
    val initialKeyword = remember(meta.title, meta.artist) {
        listOfNotNull(
            meta.title.trim().ifBlank { null },
            meta.artist.trim().ifBlank { null },
        ).joinToString(" ")
    }
    val scope = rememberCoroutineScope()
    val state = remember(rankQuery) {
        MetadataSearchState(
            scope = scope,
            rankQuery = rankQuery,
            initialKeyword = initialKeyword,
            lyricMode = mode == MetadataSearchMode.Lyric,
        )
    }
    // 进来就搜：参考图就是"已经出结果"的状态，多一次点击没有意义
    LaunchedEffect(state) { state.search() }

    var regionSheet by remember(path) { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(
                title = if (mode == MetadataSearchMode.Lyric) "搜索歌词" else "搜索元数据",
                onClose = onClose,
                // 地区只对「搜元数据」有意义（它决定搜索结果来自哪个 storefront）。
                // 歌词模式不给这个入口：歌词按曲目 id 取，换地区反而让人以为歌词会变。
                action = if (mode == MetadataSearchMode.Metadata) {
                    appleMusicStorefront.uppercase() + " ▾"
                } else {
                    null
                },
                onAction = if (mode == MetadataSearchMode.Metadata) {
                    { regionSheet = true }
                } else {
                    null
                },
                accent = AppColors.white,
            )

            // 关键词 + 搜索（**只有一个搜索入口**：框内的放大镜只是装饰，不能点）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(AppRadius.r10))
                        .background(AppColors.whiteA05)
                        .padding(start = AppSpacing.sp10, end = AppSpacing.sp6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchBarMagnifierGlyph(
                        modifier = Modifier.size(17.dp),
                        color = AppColors.textTertiary,
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.keyword.isEmpty()) {
                            Text(
                                text = "标题 歌手 专辑",
                                color = AppColors.textTertiary,
                                fontSize = AppFontSize.base,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = state.keyword,
                            onValueChange = { state.keyword = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = AppColors.textPrimary,
                                fontSize = AppFontSize.base,
                            ),
                            cursorBrush = SolidColor(AppColors.white),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { state.search() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (state.keyword.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(AppRadius.pill))
                                .clickableRipple { state.keyword = "" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("✕", color = AppColors.whiteA40, fontSize = AppFontSize.size13)
                        }
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp8))
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(AppRadius.pill))
                        .background(AppColors.whiteA15)
                        .clickableRipple { state.search() }
                        .padding(horizontal = AppSpacing.sp16),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.busy) "搜索中" else "搜索",
                        color = AppColors.textPrimary,
                        fontSize = AppFontSize.size13,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Tab：**放大成可滑动的胶囊**（6 个平台一屏放不下，挤在一排既点不准也看不清）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (t in MetadataTab.entries) {
                    val selected = state.tab == t
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(AppRadius.pill))
                            .background(if (selected) AppColors.white12 else AppColors.whiteA03)
                            .clickableRipple { state.selectTab(t) }
                            .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp8),
                    ) {
                        Text(
                            text = t.label,
                            color = if (selected) AppColors.textPrimary else AppColors.textSecondary,
                            fontSize = AppFontSize.size15,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 结果区：切 Tab 有方向动画；左右滑动也能换 Tab
            // `state.tabs` 已按模式过滤（LRCLIB 只属于歌词模式）
            val tabs = state.tabs
            // 48dp 才算一次切页：太灵敏会在垂直滚动时误触
            val swipePx = with(LocalDensity.current) { 48.dp.toPx() }
            val switchBy: (Int) -> Unit = { dir ->
                val index = tabs.indexOf(state.tab)
                val next = (index + dir).coerceIn(0, tabs.lastIndex)
                if (next != index) state.selectTab(tabs[next])
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = state.tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (
                            slideInHorizontally(animationSpec = tween(220)) { w ->
                                if (forward) w / 4 else -w / 4
                            } + fadeIn(animationSpec = tween(220))
                            ) togetherWith (
                            slideOutHorizontally(animationSpec = tween(220)) { w ->
                                if (forward) -w / 4 else w / 4
                            } + fadeOut(animationSpec = tween(180))
                            )
                    },
                    label = "metadata-tab",
                ) { tab ->
                    val rows = state.rowsOf(tab)
                    // 滚动状态**每个 Tab 一份**：以前是页面级一个 `rememberLazyListState()`，
                    // 六个 Tab 共用，切过去就继承了上一个 Tab 的 index/offset ——
                    // 新结果少的时候会被 clamp 到底部，表现成「切了 Tab 不在顶部」。
                    // 放在这里（AnimatedContent 按 targetState 分组组合）意味着每次切到某个 Tab
                    // 都是全新的列表状态 = 从顶部开始，正是看新榜单该有的行为。
                    val listState = rememberLazyListState()
                    // 同一个 Tab 里重新搜一次：结果换了一批，还得从顶部开始看
                    LaunchedEffect(rows) {
                        if (rows.isNotEmpty()) listState.scrollToItem(0)
                    }
                    // 推荐 Tab 也要能报原因：五家全挂时旧实现只显示「没有匹配的结果」，
                    // 看起来像"搜不到"，其实是一次都没成功
                    val error = state.currentError
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 水平滑动切 Tab：LazyColumn 的垂直滚动不受影响（只接管水平手势）
                            .pointerInput(tab) {
                                var total = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { total = 0f },
                                    onHorizontalDrag = { _, delta -> total += delta },
                                    onDragEnd = {
                                        if (total <= -swipePx) switchBy(1)
                                        else if (total >= swipePx) switchBy(-1)
                                    },
                                    onDragCancel = { total = 0f },
                                )
                            },
                    ) {
                        when {
                            state.currentLoading && rows.isEmpty() ->
                                CenterHint("搜索中…", showSpinner = true)
                            rows.isEmpty() -> CenterHint(
                                when {
                                    error != null -> "${tab.label}：$error"
                                    state.searched -> "没有匹配的结果"
                                    else -> "输入关键词后点搜索"
                                },
                            )
                            else -> LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    top = AppSpacing.sp4,
                                    bottom = AppSpacing.sheetBottomInset,
                                ),
                            ) {
                                items(rows, key = { it.key }) { row ->
                                    when (row) {
                                        is SearchRow.Platform -> MetadataResultRow(
                                            song = row.song,
                                            onClick = { onOpenImport(row.song) },
                                        )
                                        is SearchRow.Lyric -> LrclibRow(
                                            hit = row.hit,
                                            onClick = { onOpenLyricHit(row.hit) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 歌词模式没有地区入口，这个弹窗自然也不该出现
    if (regionSheet && mode == MetadataSearchMode.Metadata) {
        AppleStorefrontSheet(
            visible = regionSheet,
            current = appleMusicStorefront,
            onClose = { regionSheet = false },
            onSelect = { code ->
                regionSheet = false
                // 与「AM 单曲搜索」共用同一份全局 storefront → 改了之后重新搜一次才有新结果
                com.annieplayer.annie_music.kt.data.sources.apple.AppleMusicSettings.setStorefront(code)
                state.search()
            },
        )
    }
}

@Composable
private fun CenterHint(text: String, showSpinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(
                    color = AppColors.white,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
            }
            Text(
                text = text,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
            )
        }
    }
}

@Composable
private fun MetadataEmptyScaffold(
    onClose: () -> Unit,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            LocalDetailTopBar(title = "搜索元数据", onClose = onClose)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(message, color = AppColors.textTertiary, fontSize = AppFontSize.base)
            }
        }
    }
}

/**
 * 一条 LRCLIB 命中：没有封面，用一块 `LRC` 占位（尺寸与其它行一致，列表不会跳）。
 *
 * 只出现在歌词模式。点它是进「导入歌词」对比页，而不是元数据导入页 ——
 * LRCLIB 只有歌词，没有可写的标签字段。
 */
@Composable
private fun LrclibRow(hit: LrclibHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRipple(onClick)
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "LRC",
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = hit.title.ifBlank { "未知标题" },
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = hit.artist.ifBlank { "未知艺术家" },
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.album.isNotBlank()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = hit.album,
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size10,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (hit.wordSync) "LRCLIB · 逐字" else "LRCLIB",
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = if (hit.durationSec > 0) {
                    "%d:%02d".format(hit.durationSec / 60, hit.durationSec % 60)
                } else {
                    ""
                },
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size10,
            )
        }
    }
}

/**
 * 一条搜索结果：封面 + 标题 + 艺术家 + 专辑 + 时长 + 平台角标。
 *
 * 封面尺寸角标（参考图的 `1080 × 1080`）走懒加载，见 `MetadataCoverSizeBadge` 的注释。
 */
@Composable
private fun MetadataResultRow(song: Song, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableRipple(onClick)
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(AppRadius.r8))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            if (song.cover.isNotBlank()) {
                AsyncImage(
                    model = song.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size15,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp2))
            Text(
                text = song.artist,
                color = AppColors.textSecondary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!song.album.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = "专辑：${song.album}",
                    color = AppColors.textTertiary,
                    fontSize = AppFontSize.size10,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp8))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = song.platform.label,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size10,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = if (song.duration > 0) "%d:%02d".format(song.duration / 60, song.duration % 60) else "",
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size10,
            )
        }
    }
}
