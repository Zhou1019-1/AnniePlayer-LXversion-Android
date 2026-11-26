package com.annieplayer.annie_music.kt.widgets

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.audio.apple.ApplePrefetch
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.core.routing.BackTarget
import com.annieplayer.annie_music.kt.core.routing.handleBack
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.AlbumRoute
import com.annieplayer.annie_music.kt.core.state.AlbumGroup
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.MultiSelectOrigin
import com.annieplayer.annie_music.kt.core.state.MultiSelectSheetState
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.resolveDownloadLyric
import com.annieplayer.annie_music.kt.core.util.downloadQualityFor
import com.annieplayer.annie_music.kt.features.downloads.DownloadMeta
import com.annieplayer.annie_music.kt.features.downloads.batchDownloadQualities
import com.annieplayer.annie_music.kt.features.downloads.downloadOneAndRegister
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.SingerDetailCache
import com.annieplayer.annie_music.kt.core.state.SingerDetailCacheState
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.mergeSongs
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.core.state.toOverlayFlags
import com.annieplayer.annie_music.kt.data.DownloadDirService
import com.annieplayer.annie_music.kt.data.enrichSong
import com.annieplayer.annie_music.kt.data.favorites.FavoriteKeys
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.data.artistSongsBatchSize
import com.annieplayer.annie_music.kt.data.fetchArtistAlbumsByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistAvatarByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistBioByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistSongsBatch
import com.annieplayer.annie_music.kt.data.fetchArtistSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchArtistSongsQuick
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.playQualityLabelOf
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.apple.appleArtistMotionById
import com.annieplayer.annie_music.kt.data.sources.apple.findAppleArtistMotionCover
import com.annieplayer.annie_music.kt.data.sources.apple.getAppleArtistReleases
import com.annieplayer.annie_music.kt.data.model.Album
import com.annieplayer.annie_music.kt.data.model.ALBUM_KIND_ALBUM
import com.annieplayer.annie_music.kt.data.model.ALBUM_KIND_EP
import com.annieplayer.annie_music.kt.data.model.ALBUM_KIND_SINGLE
import com.annieplayer.annie_music.kt.data.model.albumKind
import com.annieplayer.annie_music.kt.data.model.ArtistSongsPageSize
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.sources.kuwo.buildAlbumCoverUrl
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.R
import androidx.compose.ui.res.painterResource
import com.annieplayer.annie_music.kt.widgets.DetailRoundIconButton
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 歌手详情浮层（1:1 移植 Flutter `lib/widgets/singer_detail_sheet.dart`，对齐 RN 版
 * components/SingerDetailOverlay.tsx）。
 *
 * **一个路由 = 一页内容**：`page` 由宿主传（overview / 全部单曲 / 全部专辑），本组件内部
 * 不再有「页内 tab」这层状态。三个页面各是**独立的一层栈**（`SingerRoute` /
 * `SingerSongsRoute` / `SingerAlbumsRoute`），返回逐层退回，与本地曲库那套子页对称。
 * 数据通过 `SingerDetailCache` 按歌手共享，所以从 overview 点「全部」不会重新拉一次。
 *
 * 结构（自上而下）：返回条（多选时含三态全选 + 多选 / 取消 chip）→ 歌手头像 / 名字 / 歌曲数 →
 * （多选时）批量下载条 → 内容区（overview 分段预览 / 分页歌曲列表 / 2 列专辑网格）。
 *
 * 关键行为：
 * - 歌曲每页 20 首，底部渲染分页器（对齐 RN 的页段算法）。
 * - 行点击播放；长按进入多选（带震动反馈）；多选模式下点击切换选中。
 * - 单曲 / 批量下载均先弹出音质选择器（150ms 淡入）。
 * - 返回手势：多选态下只退多选（`BackHandler`），出栈由宿主统一做。
 * - 头像为空时按平台补拉一次（从播放页 ⋮ 跳进来时 data.picture 是空的）。
 *
 * 背景：整屏铺 [CoverPaletteBackdrop]（`sheen = true`，与详情页同一套液态玻璃高光）——封面与色板
 * 都取「正在播放」的歌曲：`PlayerViewModel.currentSong.cover` + AppShell 按当前播放歌曲写入的
 * `ColorViewModel` 取色；面板自身不再有不透明底色。
 *
 * 本文件同时收纳 [detailDownloadSong] 下载助手与本页私有的统一笔重自绘图标；
 * 音质选择器复用共用组件 [QualityPickerSheet]。
 */

/** 歌手详情的三页内容。页内不再切 tab，切页 = 压栈。 */
enum class SingerDetailPage { overview, songs, albums }

@Composable
fun SingerDetailSheet(
    data: SingerDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    /** 本实例渲染哪一页（由路由决定，进来之后不再变）。 */
    page: SingerDetailPage = SingerDetailPage.overview,
    /** 仅「全部专辑」页：Apple 只看某一段；其它平台传 null。 */
    albumSection: AlbumGroup? = null,
    /** overview 的「最热门 · 全部 ›」：宿主压 `SingerSongsRoute`。 */
    onOpenSongs: () -> Unit = {},
    /** overview 的专辑段「全部 ›」：宿主压 `SingerAlbumsRoute`；Apple 传对应段。 */
    onOpenAlbums: (AlbumGroup?) -> Unit = {},
    colorViewModel: ColorViewModel = activityViewModel(),
) {
    // 数据与可见性由宿主（详情真栈）传入：不再读 uiState 里的可见性/数据，也没有 early-return。
    key(data.id) {
        SingerDetailContent(
            data = data,
            isTop = isTop,
            onClose = onClose,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            colorViewModel = colorViewModel,
            modifier = modifier,
            page = page,
            albumSection = albumSection,
            onOpenSongs = onOpenSongs,
            onOpenAlbums = onOpenAlbums,
        )
    }
}

@Composable
private fun SingerDetailContent(
    data: SingerDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    colorViewModel: ColorViewModel,
    page: SingerDetailPage,
    albumSection: AlbumGroup?,
    onOpenSongs: () -> Unit,
    onOpenAlbums: (AlbumGroup?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by uiViewModel.state.collectAsState()
    // 数据按歌手共享（overview 与两个子页是三个栈内页面，各自有组合实例）
    val st = SingerDetailCache.of(data.platform, data.id)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val playerState by playerViewModel.state.collectAsState()
    val tasks by downloadViewModel.state.collectAsState()
    // 全局取色（AppShell 按当前播放歌曲写入；本页背景直接复用，保证与播放页同一套色板）
    val colorState by colorViewModel.state.collectAsState()

    val artistName = data.name

    /** 收藏校准用（首页 / 详情页的爱心另有实例，同一个 Activity 作用域 VM） */
    val favoriteViewModel: FavoriteViewModel = activityViewModel()

    /**
     * 酷我 / 酷狗的「歌手单曲」接口不带结构化歌手字段：用本页歌手补上，
     * 这样行尾 ⋮ 的「跳转到歌手」有确定 id（秒开），也不会把组合名按 & 拆成两个人。
     */
    fun withArtist(s: Song): Song =
        if (s.artistNames.isEmpty()) {
            s.copy(artistNames = listOf(data.name), artistIds = listOf(data.id))
        } else {
            s
        }

    /**
     * 首屏一页（20 首）：overview 的「最热门」预览，也是「全部单曲」页的第一帧。
     *
     * 已有内容就直接返回 —— 续拉过的歌手再进来不能被打回 20 首。
     */
    suspend fun ensureFirstPage() {
        if (st.songs.isNotEmpty()) return
        // 第一阶段：KKBOX 的 `v3/artist` 自带 20 首热门（约 2 秒），先把它们摆上去，
        // 别让整页空着等全量扫描（扫 800 条搜索索引要 7 秒）。
        runCatching { fetchArtistSongsQuick(data.platform, data.id) }.getOrNull()?.let { quick ->
            if (quick.isNotEmpty()) {
                st.songs = quick.map(::withArtist)
                st.songTotal = quick.size
            }
        }
        st.songLoading = true
        st.songError = ""
        try {
            val result = fetchArtistSongsByPlatform(data.platform, data.id, 1)
            st.songs = result.songs.map(::withArtist)
            st.songTotal = if (result.total > 0) result.total else st.songs.size
            // 第 1 页正好等于平台一批（Apple 的 100）时，续拉不必再从头拉一遍；
            // 其它平台第 1 页只有 20 首，必须从 0 开始，靠 `mergeSongs` 去重吃掉重叠
            val batch = artistSongsBatchSize(data.platform)
            st.songLoadedThrough = if (batch > 0 && st.songs.size >= batch) st.songs.size else 0
            st.songComplete = false
        } catch (e: Exception) {
            st.songError = e.message ?: "加载失败"
        } finally {
            st.songLoading = false
        }
    }

    /**
     * 把剩下的曲目一批一批拉完。**只有进了「全部单曲」页才会调用**，overview 绝不触发。
     *
     * 每批追加到尾部（按 id 去重），所以列表变长时滚动位置不动、overview 的「最热门」
     * 前 20 首也不变。退出页面 = 协程作用域被取消，已拉到的留在 [SingerDetailCache] 里，
     * 下次进来从 `songLoadedThrough` 接着拉。
     */
    suspend fun loadRemainingSongs() {
        if (st.songComplete) return
        st.songError = ""
        if (artistSongsBatchSize(data.platform) <= 0) {
            // Qobuz 这类没有分页接口的平台，第一页就是全部
            st.songComplete = true
            return
        }
        while (true) {
            val offset = st.songLoadedThrough
            val batch = runCatching {
                fetchArtistSongsBatch(data.platform, data.id, offset)
            }.getOrNull()
            if (batch == null) {
                st.songError = "加载中断"
                return
            }
            if (batch.songs.isEmpty()) {
                st.songComplete = true
                return
            }
            st.songs = mergeSongs(st.songs, batch.songs.map(::withArtist))
            st.songLoadedThrough = offset + batch.songs.size
            st.songTotal = maxOf(st.songTotal, st.songs.size)
            if (!batch.hasMore) {
                st.songComplete = true
                return
            }
        }
    }

    suspend fun loadAlbums() {
        st.albumLoading = true
        try {
            if (data.platform == MusicPlatform.apple) {
                val releases = getAppleArtistReleases(data.id)
                st.appleSingles = releases.singles
                st.appleAlbums = releases.albums
                st.albums = releases.singles + releases.albums
            } else {
                st.albums = fetchArtistAlbumsByPlatform(data.platform, data.id, artistName = data.name)
            }
        } catch (_: Exception) {
            st.albums = emptyList()
            st.appleSingles = emptyList()
            st.appleAlbums = emptyList()
        } finally {
            st.albumLoading = false
            // 空结果也算「拉过」：否则没有专辑的歌手每次进页面都再请求一次
            st.albumsLoaded = true
        }
    }

    suspend fun loadAvatarIfNeeded() {
        // 已收藏的歌手：**即使 `data.picture` 非空也拉一次** —— 收藏记录里的头像是收藏那一刻的，
        // 打开歌手详情页正是校准它的时机（与专辑 / 歌单的曲目数校准同一套思路）。
        // 未收藏且入参已有头像时照旧跳过，不多打一次请求。
        val favorited = favoriteViewModel.isArtistFavorite(
            FavoriteKeys.artist(data.platform, data.id, data.name),
        )
        if (data.picture.isNotEmpty() && !favorited) return
        try {
            val url = fetchArtistAvatarByPlatform(data.platform, data.id, data.name)
            if (url.isNotEmpty()) {
                st.avatarUrl = url
                // 校准收藏记录（未收藏时内部直接返回，不写库）
                favoriteViewModel.syncArtistMeta(
                    platform = data.platform,
                    artistId = data.id,
                    name = data.name,
                    picture = url,
                )
            }
        } catch (_: Exception) {
            // 对齐 Dart：补头像失败保持占位
        }
    }

    suspend fun loadMotionIfNeeded() {
        if (st.motionChecked) return
        st.motionChecked = true
        val found = if (data.platform == MusicPlatform.apple) {
            runCatching { appleArtistMotionById(data.id) }.getOrNull()
        } else {
            runCatching { findAppleArtistMotionCover(data.name) }.getOrNull()
        } ?: return
        if (found.motionCover.isNotEmpty()) st.motionCover = found.motionCover
    }

    // 简介必须独立于头像请求：从「跳转到歌手」进来时 data.picture 非空，头像那一支会直接跳过
    suspend fun loadBioIfNeeded() {
        if (st.bio.isNotEmpty()) return
        try {
            val bio = fetchArtistBioByPlatform(data.platform, data.id, data.name)
            if (bio.isNotEmpty()) st.bio = bio
        } catch (_: Exception) {
            // 拿不到就整段不显示
        }
    }

    /**
     * 切换一首的选中。勾到一首不剩就**自动退出多选** ——
     * 与搜索结果页同一条规则：那里「多选态」本身就等于「已选非空」，
     * 这里若留在多选态，行还是「点了变选中」而不是播放，用户会以为列表坏了。
     */
    fun toggleSelect(song: Song) {
        val idx = st.selectedSongs.indexOfFirst { it.id == song.id }
        if (idx >= 0) {
            st.selectedSongs.removeAt(idx)
            if (st.selectedSongs.isEmpty()) st.selectMode = false
        } else {
            st.selectedSongs.add(song)
        }
    }

    suspend fun playSong(song: Song) {
        val idx = st.songs.indexOfFirst { it.id == song.id }
        if (idx < 0) return
        playerViewModel.setQueue(st.songs, idx)
        try {
            val url = fetchPlayUrlBySong(song)
            playerViewModel.updateSongPlayUrl(song.id, url)
            // 歌词**不在这里取**：`setQueue` 已经让 `PlayerState._ensureLyric` 发起了（它知道
            // KKBOX 要带 sid）。这里再取一次是不带 sid 的版本，KKBOX 会拿到「暂无歌词」占位，
            // 而它跑在取链之后（高音质要 1.5s+）—— 正好把已经拿到的真歌词**覆盖掉**。
            // 首次播放没歌词、第二次重播才有（取链命中磁盘变快，占位先到、真歌词后到）就是这个。
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun openAlbum(album: Album) {
        // 从歌手页跳专辑：把专辑详情**压栈**（歌手页留在下层、返回可逐层退回），
        // 而不再先关掉自己（旧行为会收起歌手页）。
        uiViewModel.pushRoute(
            AlbumRoute(
                AlbumDetailData(
                    id = album.albumid,
                    cover = buildAlbumCoverUrl(album),
                    name = album.name,
                    artist = album.artist,
                    platform = album.platform,
                    // 这批专辑本来就是这个歌手的，平台没给 album.artistId 时用当前歌手 id 兜底
                    artistId = album.artistId.ifEmpty { data.id },
                ),
            ),
        )
    }

    /**
     * 打开「多选操作」抽屉（宿主渲染）。
     *
     * 页面只交出快照，下载 / 加入到歌单都由 `AppShell` 执行；动作做完由宿主回调
     * [SingerDetailCacheState.clearSelection] 收尾。
     */
    fun openMultiSelectSheet() {
        if (st.selectedSongs.isEmpty()) return
        uiViewModel.openMultiSelectSheet(
            MultiSelectSheetState(
                songs = st.selectedSongs.toList(),
                scope = PlaylistScope.online,
                origin = MultiSelectOrigin.ArtistSongs,
                artistPlatform = data.platform,
                artistId = data.id,
            ),
        )
    }

    // 首帧只拉「本页 + 公共头图」需要的东西；缓存里已有的不重复拉
    // （overview 与两个子页共享 `SingerDetailCache`，从 overview 点「全部」不该再等一轮请求）。
    LaunchedEffect(page) {
        coroutineScope {
            launch { if (st.avatarUrl.isEmpty()) loadAvatarIfNeeded() }
            launch { loadBioIfNeeded() }
            launch { loadMotionIfNeeded() }
            when (page) {
                SingerDetailPage.overview -> {
                    // 本页只要「最热门 前几首」+「专辑横滑」—— **绝不续拉全量**
                    launch { ensureFirstPage() }
                    launch { if (!st.albumsLoaded) loadAlbums() }
                }

                // 「全部单曲」页：先保证有一页可看，再把剩下的在后台续拉完。
                // 必须串在**同一个协程**里：并行的话续拉可能先拿到 1000 首，
                // 随后 `ensureFirstPage` 用第 1 页把它覆盖掉。
                SingerDetailPage.songs -> launch {
                    ensureFirstPage()
                    loadRemainingSongs()
                }

                SingerDetailPage.albums -> launch { if (!st.albumsLoaded) loadAlbums() }
            }
        }
    }

    // 退出这一页时清掉多选态：`selectMode` 存在共享缓存里，靠「页面在组合里」来收尾
    // 会留下一个回到子页就自动全选的怪状态。
    DisposableEffect(page) {
        onDispose {
            if (page == SingerDetailPage.songs) {
                st.selectMode = false
                st.selectedSongs.clear()
            }
        }
    }

    // 进出场：150ms + 线性（AppDuration.sheet + AppEasing.linear，与其它浮层同规格）
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        slide.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
        )
    }

    // 关闭入口：先反向滑出（与滑入完全对称），动画结束后才真正出栈；动画期间重入忽略。
    // slide 是 remember，closing 也 remember，生命周期对齐。
    var closing by remember { mutableStateOf(false) }
    fun requestClose() {
        if (closing) return
        closing = true
        scope.launch {
            slide.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = AppDuration.sheet, easing = AppEasing.linear),
            )
            onClose()
        }
    }

    // 把「带动画出栈」注册给返回裁决（AppShell 收到返回时优先走这里，而不是直接 popRoute）
    com.annieplayer.annie_music.kt.core.routing.RememberPageClose { requestClose() }

    // 返回手势：本页**只管多选退出**这一层，出栈（含从子页退回 overview）由 AppShell 的
    // PAGE 处理器统一做 —— 子页现在是独立路由，不需要本页替它分派。
    //
    // **必须带 `isTop`**：被压在别的页面下面时本 handler 若仍启用，会抢走上层的返回，
    // 而它只清掉下层的选中，表现为「返回键毫无反应」。
    BackHandler(enabled = isTop && st.selectMode) {
        st.selectMode = false
        st.selectedSongs.clear()
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（Flutter: Color(0x80000000)，点击不关闭） ──
        Box(modifier = Modifier.fillMaxSize().background(AppColors.scrim50))

        // ── 面板：150ms 从底部滑入（对齐 TweenAnimationBuilder 的 Offset(0, t)） ──
        // 面板自身不再铺不透明底色（原 AppColors.background），由底层氛围背景直接可见。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 吞掉面板空白处的点击：本浮层盖在歌手搜索结果之上，若不吞，未命中可点子元素的
                // 触摸会**漏给下层列表**（真机复现：点「待下载歌曲」那片区域跳到了下一个歌手）。
                // 整屏的指针拦截由宿主页面栈统一做；面板自己再吃一层，面板内部的空白同样不漏。
                .clickableNoRipple { }
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            // ── 背景：跟随**歌手自己的封面**的氛围色（LunaBeat 规则），不跟正在播放的歌曲。
            //    头像优先用已解析的 avatarUrl，回落到后端给的 data.picture。
            val singerCoverUrl = st.avatarUrl.ifEmpty { data.picture }
            val singerPalette = rememberCoverPalette(singerCoverUrl)
            PageTintEffect(uiViewModel, singerPalette, coverUrl = singerCoverUrl)
            CoverPaletteBackdrop(
                coverUrl = singerCoverUrl,
                palette = singerPalette,
                modifier = Modifier.fillMaxSize(),
            )
            val heroListState = rememberLazyListState()
            val density = LocalDensity.current
            var barBottomPx by remember { mutableStateOf(0) }
            Box(modifier = Modifier.fillMaxSize()) {
                    when (page) {
                        SingerDetailPage.overview -> SingerDetailOverview(
                            st = st,
                            data = data,
                            artistName = artistName,
                            apple = data.platform == MusicPlatform.apple,
                            activeSongId = playerState.currentSong?.id,
                            listState = heroListState,
                            barBottomPx = barBottomPx,
                            onPlay = { scope.launch { playSong(it) } },
                            onDownload = { uiViewModel.setSongActionsVisible(true, it) },
                            onOpenAlbum = { openAlbum(it) },
                            onSeeAllSongs = onOpenSongs,
                            onSeeAllSingles = { onOpenAlbums(AlbumGroup.singlesEps) },
                            onSeeAllAlbums = { onOpenAlbums(AlbumGroup.albums) },
                        )

                        SingerDetailPage.songs -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = with(density) { barBottomPx.toDp() }),
                        ) {
                            SingerDetailSongs(
                                st = st,
                                tasks = tasks,
                                activeSongId = playerState.currentSong?.id,
                                onPlay = { scope.launch { playSong(it) } },
                                onDownload = { uiViewModel.setSongActionsVisible(true, it) },
                                onToggle = { toggleSelect(it) },
                            )
                        }

                        SingerDetailPage.albums -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = with(density) { barBottomPx.toDp() }),
                        ) {
                            SingerDetailAlbums(
                                st = st,
                                apple = data.platform == MusicPlatform.apple,
                                filter = albumSection,
                                onSortToggle = { st.albumSortAsc = !st.albumSortAsc },
                                onOpenAlbum = { openAlbum(it) },
                            )
                        }
                    }
                    DetailTopBar(
                        onClose = { requestClose() },
                        title = when (page) {
                            SingerDetailPage.songs -> "全部单曲"
                            SingerDetailPage.albums -> "全部专辑"
                            SingerDetailPage.overview -> null
                        },
                        count = if (page != SingerDetailPage.songs || st.songs.isEmpty()) {
                            null
                        } else {
                            "${maxOf(st.songTotal, st.songs.size)} 首"
                        },
                        actions = {
                            MultiSelectHeaderActions(
                                count = if (page == SingerDetailPage.songs) st.selectedSongs.size else 0,
                                onClear = { st.clearSelection() },
                                onOpenActions = { openMultiSelectSheet() },
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .onSizeChanged { barBottomPx = it.height },
                    )
            }
        }
    }
}

// ================================================================================================
// 头部与信息区
// ================================================================================================

private const val SINGLES_EPS_TITLE = "单曲 / EP"

private const val ALBUMS_TITLE = "专辑"

/** overview 横向专辑卡尺寸（高 = 宽 + 文字区，与 [SingerDetailAlbums] 网格同比例） */
private val SingerDetailAlbumCardWidth = 140.dp
private val SingerDetailAlbumCardHeight = 208.dp

/** overview「最热门」每页显示的行数（横向翻页，一页 5 行） */
private const val SingerDetailPageRows = 5

/** overview「专辑」段最多展示的专辑数（按发行时间取最近的若干张） */
private const val SingerDetailOverviewAlbumCount = 10

/**
 * overview 页：大头像 Hero + 动作行 +「Most popular」前几首 +「Albums」横滑。
 *
 * 数据全部来自本页已有的 `st.songs`（第 1 页）与 `st.albums`；
 * 两个 section 右侧的「全部 ›」**压栈**（`SingerSongsRoute` / `SingerAlbumsRoute`），
 * 由宿主回调完成。
 */
@Composable
private fun SingerDetailOverview(
    st: SingerDetailCacheState,
    data: SingerDetailData,
    artistName: String,
    apple: Boolean,
    activeSongId: String?,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onSeeAllSongs: () -> Unit,
    onSeeAllSingles: () -> Unit,
    onSeeAllAlbums: () -> Unit,
    listState: LazyListState,
    barBottomPx: Int,
) {
    // 「专辑」段只展示最近的若干张（按 pub 倒序，空值排最后）；Apple 两段也各只放预览
    val recentAlbums = remember(st.albums, apple) {
        if (apple) {
            emptyList()
        } else {
            st.albums.sortedByDescending { it.pub }.take(SingerDetailOverviewAlbumCount)
        }
    }
    val appleSingles = remember(st.appleSingles) {
        st.appleSingles.sortedByDescending { it.pub }.take(SingerDetailOverviewAlbumCount)
    }
    val appleAlbums = remember(st.appleAlbums) {
        st.appleAlbums.sortedByDescending { it.pub }.take(SingerDetailOverviewAlbumCount)
    }
    val grouped = remember(st.albums) {
        val sorted = st.albums.sortedByDescending { it.pub }
        val singles = sorted.filter {
            it.albumKind() == ALBUM_KIND_SINGLE || it.albumKind() == ALBUM_KIND_EP
        }
        val albums = sorted.filter {
            it.albumKind() == ALBUM_KIND_ALBUM || it.albumKind() == null
        }
        singles.take(SingerDetailOverviewAlbumCount) to albums.take(SingerDetailOverviewAlbumCount)
    }
    val singlesShelf = if (apple) appleSingles else grouped.first
    val albumsShelf = if (apple) appleAlbums else grouped.second
    val latestRelease = remember(apple, st.albums, st.appleSingles, st.appleAlbums) {
        val pool = if (apple) st.appleSingles + st.appleAlbums else st.albums
        pool.filter { it.pub.isNotBlank() }.maxByOrNull { it.pub }
    }
    val overviewListState = listState
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        MotionHeroScaffold(
            aspectRatio = 0.92f,
            barBottomPx = barBottomPx,
            listState = overviewListState,
            bottomInset = AppSpacing.listBottomInset,
            video = {
                SingerDetailHeroVideo(
                    avatarUrl = st.avatarUrl.ifEmpty { data.picture },
                    motionCover = st.motionCover,
                )
            },
            foreground = {
                SingerDetailHero(
                    name = artistName,
                    avatarKey = data.id,
                    platform = data.platform,
                    onPlayAll = { st.songs.firstOrNull()?.let(onPlay) },
                    playEnabled = st.songs.isNotEmpty(),
                    onShuffleAll = { st.songs.randomOrNull()?.let(onPlay) },
                    avatarUrl = st.avatarUrl.ifEmpty { data.picture },
                )
            },
        ) {
            if (latestRelease != null) {
            item(key = "latest-release") {
                val favoriteViewModel: FavoriteViewModel = activityViewModel()
                val trackCount = latestRelease.musiccnt.toIntOrNull() ?: 0
                val coverUrl = buildAlbumCoverUrl(latestRelease)
                LatestReleaseCard(
                    cover = coverUrl.ifEmpty { null },
                    dateText = latestReleaseDateText(latestRelease.pub),
                    title = latestReleaseTitle(
                        latestRelease.name,
                        latestRelease.albumKind().orEmpty(),
                    ),
                    countText = latestReleaseTrackCountText(trackCount),
                    onClick = { onOpenAlbum(latestRelease) },
                    onAdd = {
                        favoriteViewModel.toggleAlbum(
                            platform = data.platform,
                            albumId = latestRelease.albumid,
                            name = latestRelease.name,
                            artist = latestRelease.artist,
                            cover = coverUrl,
                            trackCount = trackCount,
                            releaseDate = latestRelease.pub,
                        )
                    },
                )
            }
        }
        // 「全部」**恒定出现**（只要这一段有内容）：一首歌 / 一张专辑也能进去看，
        // 那里有排序与完整信息；预览段装得下就不给入口的旧规则会让「全部」变得不可预期。
        item {
            SingerDetailSectionHeader(
                title = "最热门",
                onSeeAll = if (st.songs.isEmpty()) null else onSeeAllSongs,
            )
        }
        item {
            // 只展示第 1 页的歌曲（st.songs 就是当前页，进 overview 时固定在第 1 页）；
            // 每页 [SingerDetailPageRows] 行，左右滑动翻页。
            // 容器几何（固定高度 / 页间距 / 左右留白）抽到 [SongRowPager]，主页歌曲区块共用同一份。
            SongRowPager(
                items = st.songs.take(ArtistSongsPageSize),
                rowsPerPage = SingerDetailPageRows,
                // 本页 hero 是 24dp，列表跟着对齐（容器 12 + 行内 12）
                startInset = 12.dp,
            ) { song, index ->
                SingerDetailSongRow(
                    song = song,
                    index = index,
                    isActive = song.id == activeSongId,
                    isDownloading = false,
                    selected = false,
                    selectionMode = false,
                    onPlay = onPlay,
                    onDownload = onDownload,
                    onToggle = {},
                    onLongSelect = {},
                    // 「最热门」是横滑分页、每页 5 行 —— 序号在这里是噪声
                    showIndex = false,
                    // 下载按钮贴到页右边界：页宽已比屏幕窄，再留 16dp 会显得缩在中间
                    trailingSpace = 0.dp,
                )
            }
        }
        if (singlesShelf.isNotEmpty()) {
            item {
                SingerDetailSectionHeader(
                    title = "单曲 / EP",
                    onSeeAll = onSeeAllSingles,
                )
            }
            item {
                SingerDetailReleaseShelf(
                    albums = singlesShelf,
                    loading = st.albumLoading,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
        if (albumsShelf.isNotEmpty()) {
            item {
                SingerDetailSectionHeader(
                    title = "专辑",
                    onSeeAll = onSeeAllAlbums,
                )
            }
            item {
                SingerDetailReleaseShelf(
                    albums = albumsShelf,
                    loading = st.albumLoading,
                    onOpenAlbum = onOpenAlbum,
                )
            }
        }
        }
    }
}

@Composable
private fun SingerDetailReleaseShelf(
    albums: List<Album>,
    loading: Boolean,
    onOpenAlbum: (Album) -> Unit,
) {
    if (loading && albums.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(SingerDetailAlbumCardHeight),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(AppIconSize.icon24),
                color = AppColors.primary,
            )
        }
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = AppSpacing.sp16),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
    ) {
        items(count = albums.size) { i ->
            val album = albums[i]
            SingerDetailAlbumCard(
                album = album,
                coverUrl = buildAlbumCoverUrl(album),
                modifier = Modifier
                    .width(SingerDetailAlbumCardWidth)
                    .height(SingerDetailAlbumCardHeight),
                onTap = onOpenAlbum,
            )
        }
    }
}

/** overview 头部：正圆大头像（约 55% 屏宽）+ 居中艺人名 +「Artist」+ 播放胶囊 */
@Composable
private fun SingerDetailHeroVideo(
    avatarUrl: String,
    motionCover: String,
) {
    var avatarFailed by remember(avatarUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        0.62f to Color.Black,
                        0.86f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (motionCover.isNotEmpty()) {
            PlaylistMotionCover(
                videoUrl = motionCover,
                previewUrl = avatarUrl,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (avatarUrl.isEmpty() || avatarFailed) {
            SingerDetailPersonOutline(
                modifier = Modifier.size(AppIconSize.icon48),
                tint = AppColors.textDisabled,
            )
        } else {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { avatarFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SingerDetailHero(
    name: String,
    avatarKey: String,
    platform: MusicPlatform,
    onPlayAll: () -> Unit,
    playEnabled: Boolean,
    onShuffleAll: (() -> Unit)? = null,
    avatarUrl: String = "",
    modifier: Modifier = Modifier,
) {
    // 收藏状态就地取（Activity 作用域 VM），不穿透列表层
    val favoriteViewModel: FavoriteViewModel = activityViewModel()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
    val favoriteKey = remember(avatarKey, platform) {
        FavoriteKeys.artist(platform, avatarKey, name)
    }
    val favorited = remember(favoriteSnapshot, favoriteKey) {
        favoriteViewModel.isArtistFavorite(favoriteKey)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.92f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.42f to Color.Transparent,
                            0.70f to Color.Black.copy(alpha = 0.34f),
                            0.90f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = name,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size26,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = "歌手",
                    color = AppColors.whiteA50,
                    fontSize = AppFontSize.size13,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sp16),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailHeartButton(
                selected = favorited,
                onClick = {
                    favoriteViewModel.toggleArtist(
                        platform = platform,
                        artistId = avatarKey,
                        name = name,
                        picture = avatarUrl,
                    )
                },
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailPlayAllPill(onClick = onPlayAll, enabled = playEnabled)
            if (onShuffleAll != null) {
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                DetailRoundIconButton(onClick = onShuffleAll, enabled = playEnabled) { tint ->
                    Icon(
                        painter = painterResource(R.drawable.ic_action_shuffle),
                        contentDescription = "随机播放",
                        tint = tint,
                        modifier = Modifier.size(AppIconSize.icon22),
                    )
                }
            }
        }
    }
}

/**
 * section 标题行：左侧标题 + 右侧「全部 ›」玻璃 pill。
 *
 * [onSeeAll] 传 `null` 只在**这一段还没有数据**时用（加载中不出按钮）；
 * 有内容就一定给 —— 一首歌 / 一张专辑也能进「全部」页。
 */
@Composable
private fun SingerDetailSectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp16, end = AppSpacing.sp16, top = AppSpacing.sp16, bottom = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .then(if (onSeeAll != null) Modifier.clickableRipple(onSeeAll) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f, fill = false),
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size20,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onSeeAll != null) {
                Spacer(modifier = Modifier.width(AppSpacing.sp6))
                SingerDetailChevronRight(
                    modifier = Modifier.size(AppIconSize.icon16),
                    tint = AppColors.whiteA50,
                )
            }
        }
    }
}

// ================================================================================================
// 歌曲页
// ================================================================================================

@Composable
private fun SingerDetailSongs(
    st: SingerDetailCacheState,
    tasks: Map<String, DownloadTask>,
    activeSongId: String?,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (Song) -> Unit,
) {
    when {
        st.songLoading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.primary)
            }
        }

        // 只有「一首都没拿到」才整页报错：续拉中断时列表里已经有内容了，
        // 那种情况由列表底部的状态行说明，不能把整页换成错误页
        st.songError.isNotEmpty() && st.songs.isEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SingerDetailErrorOutline(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                Text(
                    text = st.songError,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.base,
                    textAlign = TextAlign.Center,
                )
            }
        }

        st.songs.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "暂无歌曲", color = AppColors.white30, fontSize = AppFontSize.base)
            }
        }

        else -> {
            val songsListState = rememberLazyListState()
            LazyColumn(
                state = songsListState,
                modifier = Modifier.fillMaxSize().shortScrollbar(songsListState),
                contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
            ) {
                items(count = st.songs.size) { index ->
                    val song = st.songs[index]
                    val task = tasks[song.id]
                    val isDownloading = task?.status == DownloadStatus.downloading ||
                        task?.status == DownloadStatus.pending
                    SingerDetailSongRow(
                        song = song,
                        index = index,
                        isActive = activeSongId == song.id,
                        isDownloading = isDownloading,
                        selected = st.selectedSongs.any { it.id == song.id },
                        selectionMode = st.selectMode,
                        onPlay = onPlay,
                        onDownload = onDownload,
                        onToggle = onToggle,
                        onLongSelect = { longPressed ->
                            // 长按 = 进入多选并选中该行（「已选中不重复触发」逻辑在行内保留）
                            st.selectMode = true
                            onToggle(longPressed)
                        },
                    )
                }
                // 底部状态行：续拉进度 / 到底了。列表在变长，滚动位置不受影响
                item {
                    SingerDetailSongsFooter(
                        loaded = st.songs.size,
                        total = st.songTotal,
                        complete = st.songComplete,
                        error = st.songError,
                    )
                }
            }
        }
    }
}

/**
 * 「全部单曲」列表底部的续拉状态行。
 *
 * 列表是边拉边变长的，用户需要知道「还在加载」还是「已经到底」——
 * 没有这一行的话，翻到末尾会以为歌手就这么几首。
 */
@Composable
private fun SingerDetailSongsFooter(
    loaded: Int,
    total: Int,
    complete: Boolean,
    error: String,
) {
    val text = when {
        complete -> "共 $loaded 首"
        error.isNotEmpty() -> "已加载 $loaded 首 · $error"
        total > loaded -> "已加载 $loaded / 共 $total 首 · 加载中…"
        else -> "已加载 $loaded 首 · 加载中…"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.sp16),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = AppColors.white30, fontSize = AppFontSize.xs)
    }
}

/** 歌曲行：长按进多选 + 点击播放 + 行尾下载（对齐 Dart `_SingerSongRow`） */
@Composable
private fun SingerDetailSongRow(
    song: Song,
    index: Int,
    isActive: Boolean,
    isDownloading: Boolean,
    selected: Boolean,
    selectionMode: Boolean,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onToggle: (Song) -> Unit,
    onLongSelect: (Song) -> Unit,
    /**
     * 行首序号列是否显示。
     *
     * **只有「最热门」那一段（`SongRowPager` 里）传 false** —— 横向分页、每页 5 行，
     * 序号在那里只是噪声。全部歌曲页（长列表）照旧显示。
     *
     * 传 false 时，正在播放的等化器叠到封面中心（46% 黑罩）；否则仍在序号列原位。
     */
    showIndex: Boolean = true,
    /**
     * 行尾留白（下载按钮右侧到行右边界）。
     *
     * 长列表保持 16dp；`SongRowPager`（「最热门」分页）里传 0 —— 页宽本身比屏幕窄，
     * 再叠 16dp 会让按钮缩在中间、显得不够靠右。
     */
    trailingSpace: Dp = AppSpacing.sp16,
    /**
     * 行内左侧留白（封面到行左边界）。
     *
     * 长列表保持 12dp；`SongRowPager`（「最热门」分页）里传 16dp —— 分页容器
     * 的 contentPadding 是 0（为了不侵占右侧露出），不补这 4dp 封面会偏左。
     */
    leadingSpace: Dp = AppSpacing.sp12,
) {
    val haptic = LocalHapticFeedback.current
    // 与专辑详情 / 歌单详情 / 本地曲库对齐：单栏长列表的「正在播放」用中性灰，
    // 动效统一是序号列的等化器（PlayingIndicator），不再用封面上的白色三角。
    val playingTint = AppColors.playingGray

    // 以专辑详情行为基准对齐：右侧预留 sp16（与专辑行 padding 一致），
    // 并让下载按钮在整个行高内垂直居中（原先默认顶对齐，视觉偏上）。
    //
    // 选中 / 正在播放的高亮画在**整行**（含行尾的 ♥ · ⋮ / 勾选框）：
    // 画在内层内容盒上时，行尾那两个按钮不在高亮范围内，看起来「只选了一半」。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    // 竖条贴行左边界（内缩 2dp），与封面之间留出 10dp 空隙
                    isActive -> Modifier.playingRowBackground(tint = playingTint, startInset = 2.dp)
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            )
            .padding(end = trailingSpace),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(AppSize.detailRowHeight)
                .combinedClickableNoRipple(
                    onTap = { if (selectionMode) onToggle(song) else onPlay(song) },
                    onLongClick = {
                        // 对齐 Dart：已选中时长按不再触发（不重复震动 / 不取消选中）
                        if (!selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongSelect(song)
                        }
                    },
                )
                .padding(
                    start = leadingSpace,
                    end = AppSpacing.sp12,
                    top = AppSpacing.sp8,
                    bottom = AppSpacing.sp8,
                ),
        ) {
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                if (showIndex) {
                    Box(
                        modifier = Modifier.width(AppSpacing.sp28),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 播放中：序号换成等化器（与 AlbumDetailSongRow / LocalSongRow 一致）
                        if (isActive) {
                            PlayingIndicator(tint = playingTint)
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = if (selected) AppColors.primary else AppColors.white60,
                                fontSize = AppFontSize.size15,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(AppSpacing.sp8))
                }
                Box(contentAlignment = Alignment.Center) {
                    SongRowCoverArt(coverUrl = song.cover)
                    if (isActive && !showIndex) {
                        // 序号列不显示时，等化器叠在封面中心（46% 黑罩保证看得清）
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(AppRadius.r6))
                                .background(Color.Black.copy(alpha = 0.46f)),
                        )
                        PlayingIndicator(tint = playingTint)
                    }
                }
                Spacer(modifier = Modifier.width(AppSpacing.sp10))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = song.title,
                        color = if (isActive) playingTint else AppColors.textPrimary,
                        fontSize = AppFontSize.lg,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sp1))
                    Text(
                        text = if (song.album.isNullOrEmpty()) song.artist else "${song.artist} · ${song.album}",
                        color = AppColors.textTertiary,
                        fontSize = AppFontSize.size13,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 行尾槽位：多选态 = 勾选框；常态 = 下载中环 / ⋮
        if (selectionMode) {
            SelectCheckBox(checked = selected, onToggle = { onToggle(song) })
        } else {
            // ♥ · ⋮：收藏状态由宿主经 `LocalSongLike` 注入；下载中 ⋮ 换旋转环
            SongRowActions(
                song = song,
                onMore = { onDownload(song) },
                downloading = isDownloading,
            )
        }
    }
}

// ================================================================================================
// 专辑页
// ================================================================================================

@Composable
private fun SingerDetailAlbums(
    st: SingerDetailCacheState,
    apple: Boolean,
    filter: AlbumGroup?,
    onSortToggle: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
) {
    if (st.albumLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.primary)
        }
        return
    }

    fun sortedAlbums(list: List<Album>): List<Album> = list.sortedWith(
        Comparator { a, b ->
            val da = a.pub
            val db = b.pub
            when {
                da.isEmpty() && db.isEmpty() -> 0
                da.isEmpty() -> 1
                db.isEmpty() -> -1
                st.albumSortAsc -> da.compareTo(db)
                else -> db.compareTo(da)
            }
        },
    )
    val sorted = sortedAlbums(st.albums)
    val sortedSingles = sortedAlbums(st.appleSingles)
    val sortedAppleAlbums = sortedAlbums(st.appleAlbums)
    val singlesAll = if (apple) {
        sortedSingles
    } else {
        sorted.filter {
            it.albumKind() == ALBUM_KIND_SINGLE || it.albumKind() == ALBUM_KIND_EP
        }
    }
    val albumsAll = if (apple) {
        sortedAppleAlbums
    } else {
        sorted.filter { it.albumKind() == ALBUM_KIND_ALBUM || it.albumKind() == null }
    }
    val visibleGroups = when (filter) {
        AlbumGroup.singlesEps -> listOf(SINGLES_EPS_TITLE to singlesAll)
        AlbumGroup.albums -> listOf(ALBUMS_TITLE to albumsAll)
        null -> listOf(SINGLES_EPS_TITLE to singlesAll, ALBUMS_TITLE to albumsAll)
    }.filter { it.second.isNotEmpty() }
    if (visibleGroups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (filter) {
                    AlbumGroup.singlesEps -> "暂无单曲 / EP"
                    AlbumGroup.albums -> "暂无专辑"
                    null -> "暂无作品"
                },
                color = AppColors.white30,
                fontSize = AppFontSize.base,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 时间正序 / 倒序切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRipple(onSortToggle)
                .padding(start = AppSpacing.sp8, end = AppSpacing.sp8, bottom = AppSpacing.sp6),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (st.albumSortAsc) {
                SingerDetailArrowUp(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.glyphMid)
            } else {
                SingerDetailArrowDown(modifier = Modifier.size(AppIconSize.icon13), tint = AppColors.glyphMid)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
            Text(
                text = if (st.albumSortAsc) "时间正序" else "时间倒序",
                color = AppColors.whiteA45,
                fontSize = AppFontSize.sm,
            )
        }

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val cardWidth = (maxWidth - AppSpacing.sp16 - AppSpacing.sp10) / 2
            val cardHeight = cardWidth + 68.dp
            val gridState = rememberLazyGridState()
            // 网格按 albumid 做 key 锚定滚动位置：切正/倒序后原顶部那张会跑到末尾，
            // 网格为了保持它可见会一路滚到底 —— 所以排序一变就回到顶部。
            LaunchedEffect(st.albumSortAsc) { gridState.scrollToItem(0) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize().shortScrollbar(gridState),
                contentPadding = PaddingValues(
                    start = AppSpacing.sp8,
                    top = AppSpacing.sp4,
                    end = AppSpacing.sp8,
                    bottom = AppSpacing.listBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sp10),
            ) {
                for ((title, group) in visibleGroups) {
                    if (visibleGroups.size > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "hdr-$title") {
                            SingerDetailReleaseHeader(title)
                        }
                    }
                    items(items = group, key = { it.albumid }) { album ->
                        SingerDetailAlbumCard(
                            album = album,
                            coverUrl = buildAlbumCoverUrl(album),
                            modifier = Modifier.height(cardHeight),
                            onTap = onOpenAlbum,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingerDetailReleaseHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.sp8, top = AppSpacing.sp8, bottom = AppSpacing.sp2),
        color = AppColors.textPrimary,
        fontSize = AppFontSize.size15,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 专辑卡片（对齐 Dart `_SingerAlbumCard`）：封面 = 卡宽 - 12，圆角 12 / 封面圆角 8 */
@Composable
private fun SingerDetailAlbumCard(
    album: Album,
    coverUrl: String,
    modifier: Modifier = Modifier,
    onTap: (Album) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(AppRadius.card))
            .background(AppColors.whiteA03)
            .clickableRipple { onTap(album) },
    ) {
        val coverSize = maxWidth - AppSpacing.sp12
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.padding(start = AppSpacing.sp6, top = AppSpacing.sp6, end = AppSpacing.sp6)) {
                Box(
                    modifier = Modifier
                        .size(coverSize)
                        .clip(RoundedCornerShape(AppRadius.r8))
                        .background(AppColors.whiteA03),
                    contentAlignment = Alignment.Center,
                ) {
                    var coverFailed by remember(album.albumid) { mutableStateOf(false) }
                    if (coverUrl.isEmpty() || coverFailed) {
                        SingerDetailAlbumGlyph(modifier = Modifier.size(AppIconSize.icon40), tint = AppColors.white10)
                    } else {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { coverFailed = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(AppSpacing.sp8)) {
                Text(
                    text = album.name,
                    color = AppColors.textPrimary,
                    fontSize = AppFontSize.size13,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = album.artist,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 曲目数：平台没给就不画（KKBOX 的专辑列表接口没有这个字段，
                    // 逐张 `v1/album` 去数不划算）。见 `AlbumSearchResults` 同一处注释。
                    val kind = album.albumKind()
                    if (kind != null) {
                        Text(
                            text = kind,
                            color = AppColors.white70,
                            fontSize = AppFontSize.size10,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                    }
                    if (album.musiccnt.toIntOrNull()?.let { it > 0 } == true) {
                        SingerDetailMusicNote(modifier = Modifier.size(10.dp), tint = AppColors.glyphDim)
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                        Text(
                            text = "${album.musiccnt}首",
                            color = AppColors.white30,
                            fontSize = AppFontSize.size10,
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                    }
                    if (album.pub.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(AppSpacing.sp4))
                        Text(
                            text = album.pub,
                            modifier = Modifier.weight(1f, fill = false),
                            color = AppColors.white70,
                            fontSize = AppFontSize.size10,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}





