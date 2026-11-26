package com.annieplayer.annie_music.kt.features.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.res.painterResource
import com.annieplayer.annie_music.kt.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistPickerRequest
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.local.LocalLibraryStore
import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import com.annieplayer.annie_music.kt.data.local.albumGroupKey
import com.annieplayer.annie_music.kt.data.local.localAlbumDateOf
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.apple.findAppleMotionCover
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.CoverPaletteBackdrop
import com.annieplayer.annie_music.kt.widgets.DetailHeartButton
import com.annieplayer.annie_music.kt.widgets.DetailMotionHeroForeground
import com.annieplayer.annie_music.kt.widgets.DetailMotionHeroVideo
import com.annieplayer.annie_music.kt.widgets.DetailPlayAllPill
import com.annieplayer.annie_music.kt.widgets.DetailRoundIconButton
import com.annieplayer.annie_music.kt.widgets.DetailTopBar
import com.annieplayer.annie_music.kt.widgets.MotionHeroScaffold
import com.annieplayer.annie_music.kt.widgets.MusicNoteGlyph
import com.annieplayer.annie_music.kt.widgets.PageTintEffect
import com.annieplayer.annie_music.kt.widgets.ResumePlayingPill
import com.annieplayer.annie_music.kt.widgets.SelectModeActions
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.heroCoverFade
import com.annieplayer.annie_music.kt.widgets.rememberCoverPalette
import com.annieplayer.annie_music.kt.widgets.rememberPlayingRowVisible
import com.annieplayer.annie_music.kt.widgets.shortScrollbar
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * **专辑详情页**（点专辑列表 / 歌手详情里的专辑进入）。
 *
 * 逐项照 `AlbumDetailSheet`：
 * - 外层：遮罩 + 满屏面板；背景 [CoverPaletteBackdrop] **跟封面取色**
 *   （专辑作品页跟封面走，这一点和"歌手/专辑列表不变色"是刻意区分）；
 * - 返回条：`DetailTopBar`（chevron 22 + 右侧多选簇）；
 * - Hero：封面 `fillMaxWidth(0.58f)` 方图 + 标题 **20sp Bold** + 艺术家 15sp `whiteA50`
 *   （可点，压一层歌手详情）+ 元信息 13sp `whiteA40` + 白底「播放」胶囊；
 * - 列表：直接铺在封面取色背景上（无玻璃底）+ 多碟插「CD n」+ 曲目行（序号列、40dp 封面、⋮）。
 *
 * 排序按用户要求：`CD 号 → 曲目号 → 标题`；**没有曲目号的排在最后、按标题字母序**。
 */
@Composable
fun LocalAlbumDetailScreen(
    albumKey: String,
    tracks: List<LocalTrackMeta>,
    currentSongId: String?,
    isTop: Boolean,
    onClose: () -> Unit,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
    onOpenArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 让宿主补抽封面（这些歌可能从没在主页滚到过，磁盘上还没有图） */
    onRequestCover: (String) -> Unit = {},
) {
    val members = remember(tracks, albumKey) { tracks.filter { albumGroupKey(it) == albumKey } }
    val ordered = remember(members) { sortAlbumTracks(members) }
    val heroCover = remember(ordered) {
        // 与 groupTracks 同一套兜底：记录的 coverPath 可能是 null（按需抽），磁盘上其实已有图
        ordered.firstNotNullOfOrNull { t -> t.coverPath ?: LocalLibraryStore.existingCover(t) }
    }
    // 详情页打开时把这张专辑的封面补抽一遍（限 80 首，后台 2 并发，不会卡）
    LaunchedEffect(ordered) { ordered.take(80).forEach { onRequestCover(it.path) } }
    val title = remember(albumKey) { albumKey.substringBefore(" · ") }
    val subtitle = remember(ordered) { ordered.firstOrNull()?.artistDisplay.orEmpty() }
    // 与流媒体 `AlbumDetailSheet` 完全一致：整页配色（背景染色 + 正在播放主色）都跟随专辑封面取色
    val heroCoverUrl = heroCover?.let { "file://$it" }

    // 动态封面：本地文件里没有，按「专辑艺术家 + 专辑名」去 Apple 国区搜一次（命中/未命中都缓存 7 天）。
    // 查到就填这两个字段，页面自动切成满屏 3:4 动态 hero；查不到就保持原来的方形封面。
    var tallCover by remember(albumKey) { mutableStateOf("") }
    var motionCover by remember(albumKey) { mutableStateOf("") }
    val heroImageUrl = tallCover.ifEmpty { heroCoverUrl.orEmpty() }
    val palette = rememberCoverPalette(heroImageUrl)
    val lookupArtist = remember(albumKey, ordered) {
        albumKey.substringAfter(" · ", "").ifEmpty {
            ordered.firstOrNull()?.albumArtist.orEmpty().ifEmpty {
                ordered.firstOrNull()?.artistDisplay.orEmpty()
            }
        }
    }
    LaunchedEffect(albumKey, title, lookupArtist, ordered.size) {
        if (motionCover.isNotEmpty()) return@LaunchedEffect
        if (title.isEmpty() || title == "未知专辑") return@LaunchedEffect
        val found = runCatching { findAppleMotionCover(lookupArtist, title) }.getOrNull()
            ?: return@LaunchedEffect
        if (motionCover.isEmpty()) {
            tallCover = found.tallCover
            motionCover = found.motionCover
        }
    }

    // 收藏：键直接用曲库算出来的 albumKey（`albumGroupKey` 用的是 albumArtist，
    // 与 `FavoriteKeys.album` 拼的 artistDisplay 不一定相同，不能自己重算）
    val favoriteViewModel: FavoriteViewModel = activityViewModel()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
    val favorited = remember(favoriteSnapshot, albumKey) {
        favoriteViewModel.isAlbumFavorite(albumKey)
    }
    // 多选：选中集与模式都留在页面本地（与流媒体专辑详情同一套语义）
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    fun toggleSelectMode() {
        selectMode = !selectMode
        selectedIds.clear()
    }
    fun toggleSelect(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
    }
    BackHandler(enabled = isTop && selectMode) { toggleSelectMode() }
    val uiViewModel: UiViewModel = activityViewModel()
    val pickedSongs = remember(selectedIds.toList(), ordered) {
        ordered.filter { it.id in selectedIds }.map { it.toSong() }
    }
    val addSelectedToPlaylist: () -> Unit = {
        if (pickedSongs.isNotEmpty()) {
            uiViewModel.openPlaylistPicker(
                PlaylistPickerRequest(pickedSongs, PlaylistScope.local),
            )
        }
    }
    PageTintEffect(uiViewModel, palette, coverUrl = heroCoverUrl)
    val deleteSelected: () -> Unit = {
        if (pickedSongs.isNotEmpty()) uiViewModel.setLocalDeleteBatch(pickedSongs)
    }
    val toggleFavorite: () -> Unit = {        favoriteViewModel.toggleAlbum(
            platform = MusicPlatform.local,
            albumId = null,
            name = title,
            artist = subtitle,
            cover = heroCoverUrl.orEmpty(),
            paths = ordered.map { it.path },
            key = albumKey,
        )
    }

    // 出栈由 AppShell 的全局 PAGE 处理器统一做

    val motionHero = motionCover.isNotEmpty()
    var barBottomPx by remember { mutableStateOf(0) }
    val playingRowInOrdered = remember(ordered, currentSongId) {
        if (currentSongId.isNullOrEmpty()) {
            null
        } else {
            ordered.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 }
        }
    }
    val resumeScope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        // 专辑作品页跟封面取色（与流媒体专辑详情一致）；渲染走 LunaBeat 那套（无 sheen）
        CoverPaletteBackdrop(
            coverUrl = heroImageUrl,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
        )
        if (motionHero) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                MotionHeroScaffold(
                    aspectRatio = 0.75f,
                    barBottomPx = barBottomPx,
                    listState = listState,
                    bottomInset = AppSpacing.listBottomInset,
                    video = {
                        LocalAlbumMotionHeroVideo(
                            heroCoverUrl = heroCoverUrl,
                            tallCover = tallCover,
                            motionCover = motionCover,
                        )
                    },
                    foreground = {
                        LocalAlbumMotionHero(
                            heroCoverUrl = heroCoverUrl,
                            tallCover = tallCover,
                            motionCover = motionCover,
                            title = title,
                            subtitle = subtitle,
                            ordered = ordered,
                            favorited = favorited,
                            onToggleFavorite = toggleFavorite,
                            onPlayAll = { if (ordered.isNotEmpty()) onPlayAt(ordered, 0) },
                            onShuffleAll = {
                                if (ordered.isNotEmpty()) onPlayAt(ordered, ordered.indices.random())
                            },
                            onOpenArtist = onOpenArtist,
                            selectMode = selectMode,
                            selectedCount = pickedSongs.size,
                            onAddToPlaylist = addSelectedToPlaylist,
                            onDeleteSelected = deleteSelected,
                        )
                    },
                ) {
                    localAlbumTrackItems(
                        ordered = ordered,
                        currentSongId = currentSongId,
                        palette = palette,
                        onPlayAt = onPlayAt,
                        onMore = onMore,
                        selectMode = selectMode,
                        selectedIds = selectedIds,
                        onToggleSelect = { toggleSelect(it) },
                    )
                    item(key = "album-meta") {
                        LocalAlbumMetaFooter(tracks = ordered)
                    }
                }
                val motionVisible = rememberPlayingRowVisible(listState, playingRowInOrdered)
                ResumePlayingPill(
                    visible = playingRowInOrdered != null && !motionVisible,
                    palette = palette,
                    onClick = {
                        val target = playingRowInOrdered
                        if (target != null) {
                            resumeScope.launch { listState.animateScrollToItem(target) }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = AppSpacing.sp16,
                            bottom = AppSpacing.bottomBarInset + AppSpacing.sp12,
                        ),
                )
            }
            DetailTopBar(
                onClose = onClose,
                actions = if (ordered.isNotEmpty()) {
                    {
                        SelectModeActions(
                            selectMode = selectMode,
                            selectedAll = ordered.isNotEmpty() && selectedIds.size == ordered.size,
                            onToggleSelectAll = {
                                if (selectedIds.size == ordered.size) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(ordered.map { it.id })
                                }
                            },
                            onToggleSelectMode = { toggleSelectMode() },
                            palette = palette,
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { barBottomPx = it.height },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                DetailTopBar(
                    onClose = onClose,
                    topInset = false,
                    actions = if (ordered.isNotEmpty()) {
                        {
                        SelectModeActions(
                            selectMode = selectMode,
                            selectedAll = ordered.isNotEmpty() && selectedIds.size == ordered.size,
                            onToggleSelectAll = {
                                if (selectedIds.size == ordered.size) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(ordered.map { it.id })
                                }
                            },
                            onToggleSelectMode = { toggleSelectMode() },
                            palette = palette,
                        )
                    }
                    } else {
                        null
                    },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                ) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                        contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                    ) {
                        item(key = "hero") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp20),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.68f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(AppRadius.panel)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    var coverFailed by remember(heroCover) { mutableStateOf(false) }
                                    if (heroCover == null || coverFailed) {
                                        MusicNoteGlyph(
                                            modifier = Modifier.size(AppIconSize.icon36),
                                            tint = AppColors.textDisabled,
                                        )
                                    } else {
                                        AsyncImage(
                                            model = "file://$heroCover",
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            onError = { coverFailed = true },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                                Text(
                                    text = title,
                                    color = AppColors.textPrimary,
                                    fontSize = AppFontSize.size20,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subtitle.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(AppSpacing.sp4))
                                    Text(
                                        text = subtitle,
                                        color = AppColors.whiteA50,
                                        fontSize = AppFontSize.size15,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(AppRadius.r6))
                                            .clickableRipple { onOpenArtist(subtitle) }
                                            .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp2),
                                    )
                                }
                                Spacer(modifier = Modifier.height(AppSpacing.sp16))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (selectMode) {
                                        DetailRoundIconButton(
                                            onClick = addSelectedToPlaylist,
                                            enabled = pickedSongs.isNotEmpty(),
                                        ) { tint ->
                                            Icon(
                                                painter = painterResource(
                                                    R.drawable.ic_action_playlist_add,
                                                ),
                                                contentDescription = "加入到歌单",
                                                tint = tint,
                                                modifier = Modifier.size(AppIconSize.icon22),
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        DetailHeartButton(
                                            selected = favorited,
                                            onClick = toggleFavorite,
                                        )
                                        Spacer(modifier = Modifier.width(AppSpacing.sp16))
                                        DetailPlayAllPill(
                                            onClick = { if (ordered.isNotEmpty()) onPlayAt(ordered, 0) },
                                            enabled = ordered.isNotEmpty(),
                                        )
                                        Spacer(modifier = Modifier.width(AppSpacing.sp16))
                                        DetailRoundIconButton(
                                            onClick = {
                                                if (ordered.isNotEmpty()) {
                                                    onPlayAt(ordered, ordered.indices.random())
                                                }
                                            },
                                            enabled = ordered.isNotEmpty(),
                                        ) { tint ->
                                            Icon(
                                                painter = painterResource(R.drawable.ic_action_shuffle),
                                                contentDescription = "随机播放",
                                                tint = tint,
                                                modifier = Modifier.size(AppIconSize.icon22),
                                            )
                                        }
                                    }
                                    if (selectMode) {
                                        DetailRoundIconButton(
                                            onClick = deleteSelected,
                                            enabled = pickedSongs.isNotEmpty(),
                                        ) { tint ->
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "删除所选",
                                                tint = tint,
                                                modifier = Modifier.size(AppIconSize.icon20),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        localAlbumTrackItems(
                            ordered = ordered,
                            currentSongId = currentSongId,
                            palette = palette,
                            onPlayAt = onPlayAt,
                            onMore = onMore,
                            selectMode = selectMode,
                            selectedIds = selectedIds,
                            onToggleSelect = { toggleSelect(it) },
                        )

                        item(key = "album-meta") {
                            LocalAlbumMetaFooter(tracks = ordered)
                        }
                    }

                    val staticRowIndex = playingRowInOrdered?.let { it + 1 }
                    val staticVisible = rememberPlayingRowVisible(listState, staticRowIndex)
                    ResumePlayingPill(
                        visible = staticRowIndex != null && !staticVisible,
                        palette = palette,
                        onClick = {
                            if (staticRowIndex != null) {
                                resumeScope.launch { listState.animateScrollToItem(staticRowIndex) }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = AppSpacing.sp16,
                                bottom = AppSpacing.bottomBarInset + AppSpacing.sp12,
                            ),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.localAlbumTrackItems(
    ordered: List<LocalTrackMeta>,
    selectMode: Boolean,
    selectedIds: List<String>,
    onToggleSelect: (String) -> Unit,
    currentSongId: String?,
    palette: com.annieplayer.annie_music.kt.ui.theme.ColorPalette,
    onPlayAt: (List<LocalTrackMeta>, Int) -> Unit,
    onMore: (LocalTrackMeta) -> Unit,
) {
    val multiDisc = ordered.mapNotNull { it.disc }.distinct().size > 1
    itemsIndexed(ordered, key = { _, t -> "t:${t.path}" }) { index, t ->
        val prev = ordered.getOrNull(index - 1)
        if (multiDisc && (prev == null || prev.disc != t.disc)) {
            Text(
                text = "CD ${t.disc ?: 1}",
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(
                    horizontal = AppSpacing.sp16,
                    vertical = AppSpacing.sp10,
                ),
            )
        }
        LocalSongRow(
            meta = t,
            index = index,
            isActive = currentSongId == t.id,
            // 专辑详情：正在播放用**封面取色的主色**（与流媒体 AlbumDetailSongRow 的
            // palette.readablePrimary() 一致），而不是单栏列表那种中性灰
            playingTint = palette.readablePrimary(),
            onPlay = { onPlayAt(ordered, index) },
            onMore = { onMore(t) },
            // 有曲目号就显示曲目号，没有就退化成行号
            trackLabel = t.track?.toString().orEmpty(),
            // 专辑页不放行内封面（与流媒体 AlbumDetailSongRow 一致）
            showCover = false,
            selectionMode = selectMode,
            selected = selectedIds.contains(t.id),
            onToggleSelect = { onToggleSelect(t.id) },
        )
    }
}

@Composable
private fun LocalAlbumMotionHeroVideo(
    heroCoverUrl: String?,
    tallCover: String,
    motionCover: String,
) {
    DetailMotionHeroVideo(
        cover = heroCoverUrl.orEmpty(),
        tallCover = tallCover,
        motionCover = motionCover,
    )
}

@Composable
private fun LocalAlbumMotionHero(
    heroCoverUrl: String?,
    tallCover: String,
    motionCover: String,
    title: String,
    subtitle: String,
    ordered: List<LocalTrackMeta>,
    favorited: Boolean,
    onToggleFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onOpenArtist: (String) -> Unit,
    selectMode: Boolean,
    selectedCount: Int,
    onAddToPlaylist: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DetailMotionHeroForeground(
            aspectRatio = 0.75f,
            title = title,
            subtitle = subtitle,
            meta = "${ordered.size} 首",
            onSubtitleClick = { onOpenArtist(subtitle) },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sp16),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectMode) {
                DetailRoundIconButton(
                    onClick = onAddToPlaylist,
                    enabled = selectedCount > 0,
                ) { tint ->
                    Icon(
                        painter = painterResource(R.drawable.ic_action_playlist_add),
                        contentDescription = "加入到歌单",
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DetailHeartButton(selected = favorited, onClick = onToggleFavorite)
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                DetailPlayAllPill(
                    onClick = onPlayAll,
                    enabled = ordered.isNotEmpty(),
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                DetailRoundIconButton(
                    onClick = onShuffleAll,
                    enabled = ordered.isNotEmpty(),
                ) { tint ->
                    Icon(
                        painter = painterResource(R.drawable.ic_action_shuffle),
                        contentDescription = "随机播放",
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                }
            }
            if (selectMode) {
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                DetailRoundIconButton(
                    onClick = onDeleteSelected,
                    enabled = selectedCount > 0,
                ) { tint ->
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除所选",
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon20),
                    )
                }
            }
        }
    }
}

/**
 * 曲目列表下面的专辑信息块（照 Apple Music 专辑页）：
 * `2026年11月6日` / `21 首歌曲，1 小时 4 分钟` / `FLAC · 96kHz / 24bit` / `℗ 2026 …`。
 *
 * 这四行原来挤在 Hero 的标题下面（`albumMetaLine` 拼成一行）；现在整体挪到曲目之后，
 * 每行一条，缺的就不画（本地曲库很多文件没有版权 tag）。
 */
@Composable
private fun LocalAlbumMetaFooter(tracks: List<LocalTrackMeta>) {
    val dateText = remember(tracks) { albumDateText(localAlbumDateOf(tracks)) }
    val countText = remember(tracks) { albumCountDurationText(tracks.size, tracks.sumOf { it.duration }) }
    val specText = remember(tracks) { albumSpecText(tracks) }
    val copyrightText = remember(tracks) { albumCopyrightText(tracks) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp20),
    ) {
        if (dateText.isNotEmpty()) {
            Text(text = dateText, color = AppColors.whiteA40, fontSize = AppFontSize.base)
        }
        if (countText.isNotEmpty()) {
            Text(text = countText, color = AppColors.whiteA40, fontSize = AppFontSize.base)
        }
        if (specText.isNotEmpty()) {
            Text(text = specText, color = AppColors.whiteA40, fontSize = AppFontSize.base)
        }
        if (copyrightText.isNotEmpty()) {
            Text(text = copyrightText, color = AppColors.whiteA40, fontSize = AppFontSize.base)
        }
    }
}
