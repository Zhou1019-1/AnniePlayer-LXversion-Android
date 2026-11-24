package com.annieplayer.annie_music.kt.features.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.state.AppleRankRoomRoute
import com.annieplayer.annie_music.kt.core.state.HomeSection
import com.annieplayer.annie_music.kt.core.state.HomeShelfAllData
import com.annieplayer.annie_music.kt.core.state.HomeShelfRoute
import com.annieplayer.annie_music.kt.core.state.MultiSelectOrigin
import com.annieplayer.annie_music.kt.core.state.MultiSelectSheetState
import com.annieplayer.annie_music.kt.core.state.NewTabRoomRoute
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailPreload
import com.annieplayer.annie_music.kt.core.state.QobuzSeeAllData
import com.annieplayer.annie_music.kt.core.state.QobuzSeeAllRoute
import com.annieplayer.annie_music.kt.core.state.QobuzTagRoute
import com.annieplayer.annie_music.kt.core.state.RankDetailRoute
import com.annieplayer.annie_music.kt.core.state.SearchSelectionViewModel
import com.annieplayer.annie_music.kt.core.state.SelectionState
import com.annieplayer.annie_music.kt.core.state.UiState
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.state.toRoute
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.sources.rank.RankBoard
import com.annieplayer.annie_music.kt.data.sources.rank.RankPageSize
import com.annieplayer.annie_music.kt.data.sources.rank.fetchRankDetail
import com.annieplayer.annie_music.kt.features.downloads.DownloadOrchestrator
import com.annieplayer.annie_music.kt.features.search.HomeSourceSwitchButton
import com.annieplayer.annie_music.kt.features.search.SearchScreenState
import kotlinx.coroutines.launch
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppLetterSpacing
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.AppTopBar
import com.annieplayer.annie_music.kt.widgets.AppleCountryButton
import com.annieplayer.annie_music.kt.widgets.ModeDropdownMode
import com.annieplayer.annie_music.kt.widgets.MultiSelectHeaderActions
import com.annieplayer.annie_music.kt.widgets.SearchBarMagnifierGlyph
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import kotlinx.coroutines.launch

// ================================================================================================
// 主页（`AppTab.Home` 的根页）
// ================================================================================================
//
// ## 它有两个形态
//
// | 形态 | 顶栏 | 第二行 | 内容 |
// |---|---|---|---|
// | **浏览态** | 菜单 · 主页 | 右端一个孤立的 🔍 | 主页内容（`HomeFeed`） |
// | **搜索态** | 菜单 · 搜索 | 模式按钮 + 搜索框 | 搜索结果（`HomeSearchPanel`） |
//
// 搜索**不是页面**：它不进 `UiState.pageStack`，而是 `UiState.homeSearchExpanded` 一个开关。
// 返回键由 `BackTarget.SEARCH` 消费，优先级排在 `PAGE` 之后（见 `OverlayStack.kt` 的 `BACK_RULES`）。
//
// ## 那个放大镜只有一个
//
// 浏览态的孤立 🔍 与搜索态里搜索框右端的 🔍 **是同一个图形、同一个位置**
// （`SearchBarMagnifierGlyph`、17dp；两边的行内边距都是 `sp16 / sp8`、行高都是
// `AppSize.detailDownloadBarHeight` = 44dp），所以两个形态切换时它看起来「一直没动过」，
// 变的只是它左边的内容。改这两处任何一处内边距都要同步改另一处。
//
// ## 形态切换全部是淡入淡出
//
// 标题（`Crossfade`）、两层内容（`HomeFadeLayer`）用同一个
// [HomeSearchFadeMs]，所以节奏是一致的。没有任何位移 —— 不用处理滑动误差，
// 也不用担心键盘顶起时错位。
//
// ## 背景
//
// 与所有二级页面走**同一条链路**：`CoverPaletteBackdrop` + `LocalAmbientCoverUrl` +
// `LocalCoverThemePalette`，数据源是「正在播放」那首歌的封面与取色（由 `AppShell` 写入）。
// 两个形态共用这一层，所以切换时背景不会跳。
//
// 因此本文件（以及 `HomeFeed` / `HomeSearchPanel`）**不铺任何不透明底色** —— 铺了就把
// Aurora 背景闷死了。

private const val HomeSearchFadeMs = 220

/**
 * 主页。
 *
 * @param st 搜索态状态机（在 `AppShell` 里 `remember(scope)` 持有，跨形态切换不丢输入和结果）。
 * @param dl 下载编排。
 * @param uiViewModel 抽屉 / 详情跳转 / 搜索态开关。
 * @param selectionViewModel 多选集合。
 * @param uiState 外壳状态（`homeSearchExpanded` 等）。
 * @param selection 多选快照。
 * @param downloadTasks 行内下载状态。
 * @param currentSong 当前播放歌曲。
 */
@Composable
fun HomeScreen(
    st: SearchScreenState,
    homeFeed: HomeFeedState,
    rankFeed: RankState,
    newTab: NewTabState,
    appleRank: AppleRankState,
    qobuzDiscover: QobuzDiscoverState,
    dl: DownloadOrchestrator,
    uiViewModel: UiViewModel,
    selectionViewModel: SearchSelectionViewModel,
    uiState: UiState,
    selection: SelectionState,
    downloadTasks: Map<String, DownloadTask>,
    currentSong: Song?,
    modifier: Modifier = Modifier,
) {
    val searchExpanded = uiState.homeSearchExpanded

    // 区块操作（换风格 / 换一批）要发请求，需要一个与页面同寿命的作用域
    val homeScope = rememberCoroutineScope()

    var refreshing by remember { mutableStateOf(false) }

    // 主页内容跟着「当前源」走：源变了就重新拉（同源且已加载时 HomeFeedState 内部短路）。
    // 放在这里而不是 browse 层内部，是因为搜索态期间也就顺手预热了，切回来不用等骨架屏。
    LaunchedEffect(st.searchPlatform) {
        homeFeed.load(st.searchPlatform)
    }
    LaunchedEffect(st.searchPlatform, st.appleCountry, uiState.homeSection) {
        if (uiState.homeSection != HomeSection.Rank) return@LaunchedEffect
        if (st.searchPlatform == MusicPlatform.apple) {
            appleRank.load(st.searchPlatform, st.appleCountry)
        } else {
            rankFeed.load(st.searchPlatform)
        }
    }
    LaunchedEffect(st.searchPlatform, st.appleCountry) {
        if (st.searchPlatform == MusicPlatform.apple) {
            st.loadAppleStorefronts()
            newTab.load(st.searchPlatform, st.appleCountry)
        }
    }
    LaunchedEffect(st.searchPlatform) {
        qobuzDiscover.load(st.searchPlatform)
    }

    Box(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                HomeTopBar(
                    uiViewModel = uiViewModel,
                    searchExpanded = searchExpanded,
                    // 多选簇只在「已选 > 0」时出现：多选入口是长按结果行，标题行不再兼任入口。
                    // 抽屉的两个动作都由宿主执行，这里只交快照。
                    selectionCount = selection.selectedSongs.size,
                    onClearSelection = { selectionViewModel.clearAll() },
                    onOpenSelectActions = {
                        uiViewModel.openMultiSelectSheet(
                            MultiSelectSheetState(
                                songs = selection.selectedSongs,
                                scope = PlaylistScope.online,
                                origin = MultiSelectOrigin.Search,
                            ),
                        )
                    },
                )

                // 两个形态叠在同一个 Box 里交叉淡化：exit 动画结束后旧的那层被移除，
                // 所以不会挡住新层的点击。
                // 两个形态各自包一层 SaveableStateProvider：`HomeFadeLayer` 用的是
                // `AnimatedVisibility`，淡出结束后会把子树**移出组合树**，里面的
                // `rememberLazyListState` / `rememberLazyGridState`（都是 rememberSaveable）
                // 会随之销毁 —— 表现就是「主页滑到一半 → 进搜索 → 切回来回到顶部」。
                // 用手持的 SaveableStateHolder 按形态把 saveable 状态存起来，切回来原样恢复。
                // 这与 `AppShell` 里给每个 tab 套 `SaveableStateProvider` 是同一套做法。
                val modeStateHolder = rememberSaveableStateHolder()
                Box(modifier = Modifier.weight(1f)) {
                    HomeFadeLayer(visible = !searchExpanded) {
                        modeStateHolder.SaveableStateProvider("home") {
                            val isApple = st.searchPlatform == MusicPlatform.apple
                            val isQobuz = st.searchPlatform == MusicPlatform.qobuz
                            val onRefreshHome: () -> Unit = {
                                if (!refreshing) {
                                    homeScope.launch {
                                        refreshing = true
                                        cacheService.openForceReadWindow()
                                        when {
                                            isQobuz ->
                                                qobuzDiscover.load(st.searchPlatform, force = true)

                                            isApple && uiState.homeSection == HomeSection.Rank ->
                                                appleRank.load(
                                                    st.searchPlatform,
                                                    st.appleCountry,
                                                    force = true,
                                                )

                                            isApple -> newTab.load(
                                                st.searchPlatform,
                                                st.appleCountry,
                                                force = true,
                                            )

                                            uiState.homeSection == HomeSection.Rank ->
                                                rankFeed.load(st.searchPlatform, force = true)

                                            else -> homeFeed.load(st.searchPlatform, force = true)
                                        }
                                        refreshing = false
                                    }
                                }
                            }
                            Column(modifier = Modifier.fillMaxSize()) {
                                HomeSectionRow(
                                    section = uiState.homeSection,
                                    appleOnly = isApple,
                                    rankHidden = isQobuz,
                                    st = st,
                                    onSelect = { uiViewModel.setHomeSection(it) },
                                    onOpenSearch = { uiViewModel.setHomeSearchExpanded(true) },
                                )
                                PullToRefreshBox(
                                    isRefreshing = refreshing,
                                    onRefresh = onRefreshHome,
                                    modifier = Modifier.weight(1f),
                                ) {
                                if (isQobuz) {
                                    QobuzDiscoverFeed(
                                        state = qobuzDiscover,
                                        onOpenTarget = { target ->
                                            uiViewModel.pushRoute(target.toRoute())
                                        },
                                        onSeeAll = { section ->
                                            uiViewModel.pushRoute(
                                                QobuzSeeAllRoute(
                                                    QobuzSeeAllData(
                                                        title = section.title,
                                                        baseline = section.baseline,
                                                        kind = section.kind,
                                                        items = section.items,
                                                    ),
                                                ),
                                            )
                                        },
                                        onOpenTag = { item ->
                                            uiViewModel.pushRoute(
                                                QobuzTagRoute(
                                                    slug = item.slug,
                                                    title = item.title,
                                                ),
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (isApple && uiState.homeSection == HomeSection.Recommend) {
                                    NewTabFeed(
                                        state = newTab,
                                        currentSongId = currentSong?.id,
                                        onPlaySong = { song, queue ->
                                            with(st) { handlePlaySong(song, queue) }
                                        },
                                        onMore = { song -> dl.openActions(song) },
                                        onOpenTarget = { target ->
                                            uiViewModel.pushRoute(target.toRoute())
                                        },
                                        onOpenRoom = { roomId, title ->
                                            uiViewModel.pushRoute(
                                                NewTabRoomRoute(
                                                    platform = st.searchPlatform,
                                                    roomId = roomId,
                                                    title = title,
                                                ),
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (isApple) {
                                    AppleRankFeed(
                                        state = appleRank,
                                        currentSongId = currentSong?.id,
                                        onPlaySong = { song, queue ->
                                            with(st) { handlePlaySong(song, queue) }
                                        },
                                        onMore = { song -> dl.openActions(song) },
                                        onOpenTarget = { target ->
                                            uiViewModel.pushRoute(target.toRoute())
                                        },
                                        onOpenRoom = { section ->
                                            uiViewModel.pushRoute(
                                                AppleRankRoomRoute(
                                                    sectionId = section.id,
                                                    title = section.title,
                                                ),
                                            )
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else if (uiState.homeSection == HomeSection.Rank) {
                                    RankFeed(
                                        state = rankFeed,
                                        onOpenBoard = { board ->
                                            homeScope.launch {
                                                val detail = runCatching {
                                                    fetchRankDetail(
                                                        platform = st.searchPlatform,
                                                        boardId = board.id,
                                                        page = 1,
                                                        pageSize = RankPageSize,
                                                    )
                                                }.getOrNull()
                                                uiViewModel.pushRoute(
                                                    RankDetailRoute(
                                                        platform = st.searchPlatform,
                                                        boardId = board.id,
                                                        name = board.name,
                                                        cover = board.cover,
                                                        subtitle = board.subtitle,
                                                        preload = detail
                                                            ?.takeIf { it.songs.isNotEmpty() }
                                                            ?.let {
                                                                PlaylistDetailPreload(
                                                                    name = it.title.ifBlank { board.name },
                                                                    creator = it.subtitle.ifBlank { board.subtitle },
                                                                    cover = it.cover.ifBlank { board.cover },
                                                                    songs = it.songs,
                                                                )
                                                            },
                                                    ),
                                                )
                                            }
                                        },
                                        onPlayTop = { board ->
                                            homeScope.launch {
                                                val detail = fetchRankDetail(
                                                    platform = st.searchPlatform,
                                                    boardId = board.id,
                                                    page = 1,
                                                    pageSize = RankPageSize,
                                                )
                                                val songs = detail?.songs.orEmpty()
                                                if (songs.isNotEmpty()) {
                                                    with(st) { handlePlaySong(songs.first(), songs) }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    HomeFeed(
                                        state = homeFeed,
                                        currentSongId = currentSong?.id,
                                        onPlay = { song, queue ->
                                            with(st) { handlePlaySong(song, queue) }
                                        },
                                        onMore = { song -> dl.openActions(song) },
                                        onOpenTarget = { target ->
                                            uiViewModel.pushRoute(target.toRoute())
                                        },
                                        onSeeAll = { cards ->
                                            uiViewModel.pushRoute(
                                                HomeShelfRoute(
                                                    HomeShelfAllData(
                                                        title = cards.title,
                                                        subtitle = cards.subtitle,
                                                        items = cards.items,
                                                    ),
                                                ),
                                            )
                                        },
                                        onShelfAction = { action ->
                                            homeScope.launch {
                                                when (action) {
                                                    HomeShelfAction.RefreshPicks ->
                                                        homeFeed.refreshKugouPicks()

                                                    HomeShelfAction.CycleStyle -> Unit
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                }
                            }
                        }
                    }
                    HomeFadeLayer(visible = searchExpanded) {
                        modeStateHolder.SaveableStateProvider("search") {
                            HomeSearchPanel(
                                st = st,
                                dl = dl,
                                uiViewModel = uiViewModel,
                                selection = selection,
                                downloadTasks = downloadTasks,
                                currentSong = currentSong,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // ⇄ 呼出「搜索选择」面板：只在搜索态出现，且被更高层让位
            with(st) {
                // 源切换按钮两个形态共用：浏览态切的是「主页内容源」，搜索态切的是「搜索源」，
                // 背后是同一个 `searchPlatform`，所以两处永远指向同一个平台。
                // 剩下的条件是「有浮层 / 进了子页面时让位」，与形态无关。
                if (!uiState.drawerVisible &&
                    !uiState.playerOverlayVisible &&
                    uiState.pageStack.isEmpty()
                ) {
                    HomeSourceSwitchButton(
                        // 按钮上直接显示当前生效的源分组 / 平台名（与「搜索选择」面板选项同源）
                        label = pickerOptions.getOrNull(pickerIndex) ?: "切换源",
                        onTap = { sourcePickerVisible = true },
                    )
                }
            }
        }
    }
}

/**
 * 主页顶栏。
 *
 * 标题在「主页 / 搜索」之间交叉淡化。
 *
 * 搜索态右侧的位置交给多选簇（`清空 · 已选 N 首 · ⇉`）：它的图标命中区与搜索框右端的
 * 放大镜同为「44dp + 右内缩 16dp」，两者同心 —— 图标正好落在放大镜那一列。
 */
@Composable
private fun HomeTopBar(
    uiViewModel: UiViewModel,
    searchExpanded: Boolean,
    selectionCount: Int,
    onClearSelection: () -> Unit,
    onOpenSelectActions: () -> Unit,
) {
    AppTopBar(
        title = "主页",
        onOpenMenu = { uiViewModel.setDrawerVisible(true) },
        titleContent = {
            Crossfade(
                targetState = searchExpanded,
                animationSpec = tween(HomeSearchFadeMs),
                label = "homeTitle",
            ) { expanded ->
                Text(
                    text = if (expanded) "搜索" else "主页",
                    color = AppColors.white,
                    fontSize = AppFontSize.size26,
                    fontWeight = AppFontWeight.bold,
                    letterSpacing = AppLetterSpacing.tight,
                )
            }
        },
        trailing = {
            if (searchExpanded) {
                MultiSelectHeaderActions(
                    count = selectionCount,
                    onClear = onClearSelection,
                    onOpenActions = onOpenSelectActions,
                )
            }
        },
    )
}

/**
 * 浏览态的搜索入口：第二行右端一个孤立的放大镜。
 *
 * 几何与 `HomeSearchPanel` 里搜索框右端的那个放大镜**逐项对齐**：
 * 行内边距 `sp16 / sp8`、命中区 `AppSize.detailDownloadBarHeight`（44dp）、
 * 图标 17dp、同一个 `SearchBarMagnifierGlyph`。改这里必须同步改那边。
 */
@Composable
private fun HomeSectionRow(
    section: HomeSection,
    appleOnly: Boolean,
    rankHidden: Boolean,
    st: SearchScreenState,
    onSelect: (HomeSection) -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.sp16,
                end = AppSpacing.sp16,
                bottom = AppSpacing.sp8,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp8),
    ) {
        HomeSectionPill(
            label = if (appleOnly) "新发现" else "推荐",
            selected = section == HomeSection.Recommend,
            onClick = { onSelect(HomeSection.Recommend) },
        )
        if (!rankHidden) {
            HomeSectionPill(
                label = "排行榜",
                selected = section == HomeSection.Rank,
                onClick = { onSelect(HomeSection.Rank) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(AppSize.detailDownloadBarHeight)
                .clickableRipple(onOpenSearch),
            contentAlignment = Alignment.Center,
        ) {
            SearchBarMagnifierGlyph(modifier = Modifier.size(17.dp))
        }
        if (appleOnly && st.showAppleCountry) {
            AppleCountryButton(
                code = st.appleCountry,
                storefronts = st.appleStorefronts,
                loading = st.appleStorefrontsLoading,
                error = st.appleStorefrontsError,
                onSelect = { st.selectAppleCountry(it) },
                onOpen = { st.loadAppleStorefronts() },
                onRetry = { st.loadAppleStorefronts(force = true) },
            )
        }
    }
}

@Composable
private fun HomeSectionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(if (selected) AppColors.white12 else AppColors.whiteA03)
            .clickableRipple(onClick)
            .padding(horizontal = AppSpacing.sp14, vertical = AppSpacing.sp8),
    ) {
        Text(
            text = label,
            color = if (selected) AppColors.textPrimary else AppColors.textSecondary,
            fontSize = AppFontSize.size15,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 一整层的淡入淡出。
 *
 * 独立成函数的原因：调用点在 `Column` / `Box` 里，直接写 `AnimatedVisibility(...)`
 * 会被解析成 `ColumnScope` / `BoxScope` 的扩展（接收者歧义，编译不过）。
 * 这里没有作用域接收者，用的是顶层版本。
 */
@Composable
private fun HomeFadeLayer(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(HomeSearchFadeMs)),
        exit = fadeOut(tween(HomeSearchFadeMs)),
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
    }
}
