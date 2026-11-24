package com.annieplayer.annie_music.kt.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import com.annieplayer.annie_music.kt.R
import com.annieplayer.annie_music.kt.core.audio.apple.ApplePrefetch
import com.annieplayer.annie_music.kt.core.compose.activityViewModel
import com.annieplayer.annie_music.kt.data.favorites.FavoriteKeys
import com.annieplayer.annie_music.kt.core.routing.BackTarget
import com.annieplayer.annie_music.kt.core.routing.handleBack
import com.annieplayer.annie_music.kt.core.state.AlbumDetailData
import com.annieplayer.annie_music.kt.core.state.ColorViewModel
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.FavoriteViewModel
import com.annieplayer.annie_music.kt.core.state.MoreSettings
import com.annieplayer.annie_music.kt.core.state.SingerDetailData
import com.annieplayer.annie_music.kt.core.state.SingerRoute
import com.annieplayer.annie_music.kt.data.resolveArtist
import com.annieplayer.annie_music.kt.core.util.downloadQualityFor
import com.annieplayer.annie_music.kt.core.util.enqueueDownload
import com.annieplayer.annie_music.kt.data.resolveDownloadLyric
import com.annieplayer.annie_music.kt.data.sources.apple.findAppleMotionCover
import com.annieplayer.annie_music.kt.features.downloads.DownloadMeta
import com.annieplayer.annie_music.kt.features.downloads.batchDownloadQualities
import com.annieplayer.annie_music.kt.features.downloads.downloadOneAndRegister
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.MultiSelectOrigin
import com.annieplayer.annie_music.kt.core.state.MultiSelectSheetState
import com.annieplayer.annie_music.kt.core.state.PlaylistPickerRequest
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.favorites.PlaylistScope
import com.annieplayer.annie_music.kt.core.state.toOverlayFlags
import com.annieplayer.annie_music.kt.data.fetchAlbumSongsByPlatform
import com.annieplayer.annie_music.kt.data.fetchDownloadUrlBySong
import com.annieplayer.annie_music.kt.data.fetchPlayUrlBySong
import com.annieplayer.annie_music.kt.data.playQualityLabelOf
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.DownloadStatus
import com.annieplayer.annie_music.kt.data.model.DownloadTask
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Quality
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 详情页音质面板宿主状态（专辑 / 歌手 / 歌单详情共用）。
 *
 * 背景：`AppShell` 的覆盖层顺序把「迷你播放条」(`AppBottomBar`) 画在 `GlobalOverlays`
 * （三个详情 sheet）之上，因此若音质面板仍内联在详情 sheet 内部，下半截会被迷你条压住。
 * 为与搜索页观感一致（面板盖住迷你条），详情 sheet 不再内联渲染面板，而是把面板内容交给本
 * 宿主，由 `AppShell` 在迷你条**之后**（对齐 [com.annieplayer.annie_music.kt.core.routing.Overlay]
 * 的 QUALITY_PICKER 层）统一渲染。
 *
 * 每个详情 sheet 持有一个唯一 `owner`（`remember { Any() }`）：仅当自己的面板打开时才抢占
 * 槽位；关闭时不写槽位，保留内容让面板自带的淡出动画自然播完；sheet 退出时清理自己的槽位，
 * 避免面板残留在上层。
 */
class DetailQualityPickerHost {
    /** 当前由 AppShell 顶层渲染的面板内容；由打开的详情 sheet 提供。 */
    var slot by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    private var owner: Any? = null

    /** 详情 sheet 的面板打开时调用：抢占槽位。 */
    fun show(owner: Any, content: @Composable () -> Unit) {
        this.owner = owner
        slot = content
    }

    /** 详情 sheet 退出时调用：仅当槽位仍属于自己时清空（避免误清上层详情页的面板）。 */
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            slot = null
        }
    }
}

/**
 * 由 `AppShell` 提供。未提供时（例如独立整页版 [com.annieplayer.annie_music.kt.features.album.AlbumDetailScreen]
 * 单独渲染）详情页退化为内联渲染，行为与改造前一致。
 */
val LocalDetailQualityPickerHost = staticCompositionLocalOf<DetailQualityPickerHost?> { null }

/**
 * 专辑详情浮层（1:1 移植 Flutter `lib/widgets/album_detail_sheet.dart`，对齐 RN 版
 * components/AlbumDetailOverlay.tsx）。
 *
 * 结构：返回条（含「多选 / 全选」）→ 专辑头部（封面 100 + 名称 / 歌手 / 曲目数）
 * → 玻璃列表容器（曲目 60dp 行，行内展示下载任务状态）；多选时列表头追加「多选下载条」
 * （[MultiSelectDownloadBar]）。
 *
 * 视觉（液态玻璃链路，与整页版 AlbumDetailScreen 一致）：面板背景 = 封面模糊 + 取色染色氛围
 * （[CoverPaletteBackdrop]）；列表**直接铺在氛围背景上**（不套玻璃面板）；
 * 命中当前播放行 = 正在播放动效（[playingRowBackground] + [PlayingIndicator]）；进入时把全局
 * 色板临时切为该专辑色板、退出恢复（[SnapshotPaletteAndRestore]）。
 *
 * 关键行为：
 * - 打开后**延迟 150ms** 再请求曲目（对齐 RN 行为），失败显示错误态。
 * - 多选模式：头部「全选 / 取消全选」，批量下载支持进度文案。
 * - `AlbumSong` -> `Song` 必须携带 `artistNames` / `artistIds`（⋮「跳歌手」才能秒开），
 *   并带上 `albumArtist` / `trackNumber` / `releaseDate`。
 * - 返回手势：多选模式优先退出；更高层浮层可见时让位（见 core/routing/OverlayStack.kt）。
 */
@Composable
fun AlbumDetailSheet(
    data: AlbumDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    colorViewModel: ColorViewModel = activityViewModel(),
) {
    // 数据与可见性由宿主（详情真栈）传入：不再读 uiState 里的可见性/数据，也没有 early-return，
    // 因此栈内每一层详情各自渲染自己那份 data、互不干扰。
    key(data.id) {
        AlbumDetailContent(
            data = data,
            isTop = isTop,
            onClose = onClose,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
            modifier = modifier,
            colorViewModel = colorViewModel,
        )
    }
}

/** 专辑详情内部可变状态（对齐 Dart `_AlbumDetailViewState` 的字段） */
private class AlbumDetailState {
    var loading by mutableStateOf(true)
    var error by mutableStateOf("")
    var songs by mutableStateOf<List<AlbumSong>>(emptyList())
    var qualitySpec by mutableStateOf("")
    var releaseDate by mutableStateOf("")
    var tallCover by mutableStateOf("")
    var motionCover by mutableStateOf("")
    var lookupAlbum by mutableStateOf("")
    var lookupArtist by mutableStateOf("")

    var selectMode by mutableStateOf(false)
    val selectedIds = mutableStateListOf<String>()

    var pickerVisible by mutableStateOf(false)
    var batchDownloading by mutableStateOf(false)
    var batchProgress by mutableStateOf("")
}

@Composable
private fun AlbumDetailContent(
    data: AlbumDetailData,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    colorViewModel: ColorViewModel,
) {
    val uiState by uiViewModel.state.collectAsState()
    val st = remember { AlbumDetailState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val tasks by downloadViewModel.state.collectAsState()
    val playerState by playerViewModel.state.collectAsState()

    // 收藏：读 snapshot 才能让爱心在点击后立刻变（只查 `isAlbumFavorite` 不会触发重组）
    val favoriteViewModel: FavoriteViewModel = activityViewModel()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
    val albumFavoriteKey = remember(data.id, data.platform) {
        FavoriteKeys.album(data.platform, data.id, data.name, data.artist)
    }
    val albumFavorited = remember(favoriteSnapshot, albumFavoriteKey) {
        favoriteViewModel.isAlbumFavorite(albumFavoriteKey)
    }

    /** 封面动态色板（与播放页同一取色链路；同 url 进程级缓存见 core/util/ColorExtractor.kt） */
    val heroImageUrl = st.tallCover.ifEmpty { data.cover }
    val palette = rememberCoverPalette(heroImageUrl)
    PageTintEffect(uiViewModel, palette, coverUrl = heroImageUrl)

    // 进入浮层把全局色板临时切为该专辑色板，退出恢复进入前的色板（与整页版同一套取色链路）
    // 不再写入全局色板（原 SnapshotPaletteAndRestore）：详情页只用本地 palette 渲染，
// 全局色板由 AppShell 按「当前播放歌曲」驱动；详情页写入会在退出时覆盖成陈旧值。

    /** `AlbumSong` -> `Song`（对齐 Dart `_toSong`，必须携带歌手 ids / 名单） */
    fun toSong(s: AlbumSong): Song = Song(
        id = s.id,
        title = s.name,
        artist = s.artistName,
        cover = data.cover,
        album = data.name,
        albumId = data.id,
        duration = s.duration,
        platform = data.platform,
        // 必须带上专辑接口给的真实音质（否则详情页下载候选永远回退 flac/320/128，Hi-Res 丢失）
        qualities = s.qualities.ifEmpty { listOf(Quality.flac, Quality.k320k, Quality.k128k) },
        qualitySizes = s.qualitySizes,
        albumArtist = data.artist,
        artistNames = if (s.artistNames.isNotEmpty()) s.artistNames else listOf(s.artistName),
        artistIds = s.artistIds,
        trackNumber = s.track?.toString(),
        discNumber = s.discNumber?.toString(),
        releaseDate = st.releaseDate.ifEmpty { null },
        composer = s.composer,
        genre = s.genre,
        isrc = s.isrc,
        mediaMid = s.mediaMid,
    )

    fun load() {
        st.loading = true
        st.error = ""
        st.songs = emptyList()
        st.selectMode = false
        st.selectedIds.clear()
        st.batchDownloading = false
        st.batchProgress = ""
        st.pickerVisible = false
        st.releaseDate = ""
        st.tallCover = ""
        st.motionCover = ""
        st.lookupAlbum = ""
        st.lookupArtist = ""
    }

    suspend fun play(target: AlbumSong) {
        val converted = st.songs.map { toSong(it) }
        val idx = converted.indexOfFirst { it.id == target.id }
        if (idx < 0) return
        playerViewModel.setQueue(converted, idx)
        try {
            val url = fetchPlayUrlBySong(converted[idx])
            // 歌词交给 `setQueue` → `PlayerState._ensureLyric`（它带 KKBOX 的 sid）。
            // 这里再取一次是不带 sid 的版本，会拿到「暂无歌词」占位并把真歌词覆盖掉。
            playerViewModel.updateSongPlayUrl(target.id, url)
        } catch (_: Exception) {
            // 对齐 Dart：拉取失败静默忽略
        }
    }

    fun toggleSelect(id: String) {
        if (!st.selectedIds.remove(id)) st.selectedIds.add(id)
    }

    fun toggleSelectAll() {
        if (st.selectedIds.size == st.songs.size) {
            st.selectedIds.clear()
        } else {
            st.selectedIds.clear()
            st.selectedIds.addAll(st.songs.map { it.id })
        }
    }

    fun toggleSelectMode() {
        st.selectMode = !st.selectMode
        st.selectedIds.clear()
    }

    suspend fun batchDownload(quality: Quality) {
        st.pickerVisible = false
        st.batchDownloading = true
        val selected = st.songs.filter { st.selectedIds.contains(it.id) }
        // 面板给的是整批并集：逐首夹到它真实可用的最高档（否则「只有 320k」的歌会被按 flac 请求）
        for (s in selected) {
            val song = toSong(s)
            downloadViewModel.addTask(song, downloadQualityFor(song, quality))
        }
        var success = 0
        for (i in selected.indices) {
            val s = selected[i]
            val song = toSong(s)
            st.batchProgress = "${i + 1}/${selected.size} ${s.name}"
            downloadViewModel.updateProgress(s.id, 0.0)
            // 前置预备：把后面两首的清单请求提前打掉（Apple session 串行，只能靠重叠）
            ApplePrefetch.ahead(selected.drop(i + 1).map { toSong(it) }, quality)
            val ok = downloadOneAndRegister(
                downloadViewModel = downloadViewModel,
                playerViewModel = playerViewModel,
                song = song,
                quality = downloadQualityFor(song, quality),
                meta = DownloadMeta(
                    coverUrl = data.cover,
                    album = data.name,
                    albumArtist = data.artist,
                    trackNumber = if (s.track != null) s.track.toString() else "${i + 1}",
                    releaseDate = st.releaseDate.ifEmpty { null },
                    composer = song.composer,
                    genre = song.genre,
                    isrc = song.isrc,
                ),
                onProgress = { p -> downloadViewModel.updateProgress(s.id, (p * 100).coerceIn(0.0, 100.0)) },
            )
            if (ok) success++
        }
        ApplePrefetch.drain()
        st.batchDownloading = false
        st.batchProgress = "下载完成 $success/${selected.size}"
        st.selectMode = false
        st.selectedIds.clear()
    }

    LaunchedEffect(Unit) {
        load()
        // RN 版打开后延迟 150ms 再请求
        delay(150)
        try {
            val result = fetchAlbumSongsByPlatform(data.platform, data.id)
            st.qualitySpec = result.qualitySpec
            st.songs = result.songs.sortedWith(
                compareBy({ it.discNumber ?: 1 }, { it.track ?: Int.MAX_VALUE }),
            )
            st.releaseDate = result.pub
            if (result.tallCover.isNotEmpty()) st.tallCover = result.tallCover
            if (result.motionCover.isNotEmpty()) st.motionCover = result.motionCover
            st.lookupAlbum = result.lookupAlbum
            st.lookupArtist = result.lookupArtist
            // 校准收藏记录：这张专辑如果已经收藏，把列表里显示的名字 / 歌手 / 封面 /
            // 曲目数 / 发行日期对齐到当前事实。
            // 只有在**打开了这张专辑**时才会走到这里 —— 收藏库的列表页从不发请求。
            // 曲目数与日期来自接口；名字/歌手/封面来自 `data`（从主页或搜索进来时是平台当前值，
            // 从收藏库进来时就是收藏记录本身 → 值不变 → 不写库）。
            // 未收藏、或值没变，`syncAlbumMeta` 都不会写库。
            favoriteViewModel.syncAlbumMeta(
                platform = data.platform,
                albumId = data.id,
                name = data.name,
                artist = data.artist,
                cover = data.cover,
                trackCount = st.songs.size,
                releaseDate = st.releaseDate,
                artistId = data.artistId,
            )
        } catch (e: Exception) {
            val msg = (e.message ?: "").replaceFirst("Exception: ", "")
            st.error = msg.ifEmpty { "加载失败" }
        } finally {
            st.loading = false
        }
    }

    LaunchedEffect(data.id, data.platform, st.loading, st.lookupAlbum, st.lookupArtist) {
        if (data.platform == MusicPlatform.apple) return@LaunchedEffect
        if (st.loading) return@LaunchedEffect
        if (st.motionCover.isNotEmpty()) return@LaunchedEffect
        val albumName = st.lookupAlbum.ifEmpty { data.name }
        val artistName = st.lookupArtist.ifEmpty { data.artist }
        val found = runCatching {
            findAppleMotionCover(artistName, albumName)
        }.getOrNull() ?: return@LaunchedEffect
        if (st.motionCover.isEmpty()) {
            st.tallCover = found.tallCover
            st.motionCover = found.motionCover
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
    // 返回手势：本页**只管多选退出**这一层。
    // 「出栈」由 `AppShell` 的全局 PAGE 处理器统一做 —— 页面不再需要知道有没有更高层浮层。
    //
    // **必须带 `isTop`**：被压在别的详情页下面时，本 handler 若仍启用，就会在
    // OnBackPressedDispatcher 的逆序派发里抢先吃掉返回（上层页面往往没有启用的 BackHandler），
    // 表现就是「返回键毫无反应」——它只把下层的多选退掉了。
    BackHandler(enabled = isTop && st.selectMode) { toggleSelectMode() }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（Flutter: Color(0x80000000)，点击不关闭） ──
        Box(modifier = Modifier.fillMaxSize().background(AppColors.scrim50))

        // ── 面板：150ms 从底部滑入；背景 = 封面模糊 + 取色染色氛围 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = slide.value * size.height },
        ) {
            // 封面取色氛围背景（含纯色底兜底；API < 31 时 blur 自动降级为原图，不崩）
            CoverPaletteBackdrop(
                coverUrl = heroImageUrl,
                palette = palette,
                modifier = Modifier.fillMaxSize(),
            )
            val motionHero = st.motionCover.isNotEmpty()
            var barBottomPx by remember { mutableStateOf(0) }
            val body: @Composable (Boolean, Int) -> Unit = { motion, barPx ->
                AlbumDetailBody(
                    st = st,
                    data = data,
                    tasks = tasks,
                    palette = palette,
                    currentSongId = playerState.currentSong?.id,
                    onPlay = { scope.launch { play(it) } },
                    onDownload = { uiViewModel.setSongActionsVisible(true, toSong(it)) },
                    // 行尾爱心的收藏键按 `Song` 算，转换只在这一处做（行内别现拼）
                    toSongForLike = { toSong(it) },
                    onToggle = { toggleSelect(it) },
                    onBatch = { st.pickerVisible = true },
                    onAddToPlaylist = {
                        val picked = st.songs.filter { st.selectedIds.contains(it.id) }.map(::toSong)
                        if (picked.isNotEmpty()) {
                            uiViewModel.openPlaylistPicker(
                                PlaylistPickerRequest(songs = picked, scope = PlaylistScope.online),
                            )
                        }
                    },
                    onOpenArtist = {
                        val name = data.artist.trim()
                        if (name.isNotEmpty()) {
                            scope.launch {
                                val resolved = resolveArtist(
                                    data.platform,
                                    name,
                                    data.artistId.ifEmpty { null },
                                )
                                if (resolved != null && resolved.id.isNotEmpty()) {
                                    uiViewModel.pushRoute(
                                        SingerRoute(
                                            SingerDetailData(
                                                id = resolved.id,
                                                name = resolved.name,
                                                picture = resolved.picture,
                                                platform = data.platform,
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    motionHero = motion,
                    barBottomPx = barPx,
                )
            }
            if (motionHero) {
                body(true, barBottomPx)
                DetailTopBar(
                    onClose = { requestClose() },
                    loading = st.loading,
                    actions = if (st.songs.isNotEmpty()) {
                        {
                            SelectModeActions(
                                selectMode = st.selectMode,
                                selectedAll = st.selectedIds.size == st.songs.size,
                                onToggleSelectAll = { toggleSelectAll() },
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
                        onClose = { requestClose() },
                        loading = st.loading,
                        topInset = false,
                        actions = if (st.songs.isNotEmpty()) {
                            {
                                SelectModeActions(
                                    selectMode = st.selectMode,
                                    selectedAll = st.selectedIds.size == st.songs.size,
                                    onToggleSelectAll = { toggleSelectAll() },
                                    onToggleSelectMode = { toggleSelectMode() },
                                    palette = palette,
                                )
                            }
                        } else {
                            null
                        },
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        body(false, 0)
                    }
                }
            }
        }

        // ── 音质选择器（批量下载，复用 QualityPickerSheet） ──
        // 面板内容交给 AppShell 在「迷你播放条之上」渲染（见 DetailQualityPickerHost 注释）；
        // 无宿主（独立整页版）时退化为内联渲染，行为不变。
        val pickerHost = LocalDetailQualityPickerHost.current
        val pickerOwner = remember { Any() }
        val pickerPanel: @Composable () -> Unit = {
            val pickerSongs = st.songs.filter { st.selectedIds.contains(it.id) }.map { toSong(it) }
            QualityPickerSheet(
                visible = st.pickerVisible,
                // 与曲目实际可用音质一致（Qobuz 专辑含 Hi-Res，其余三档）
                qualities = batchDownloadQualities(pickerSongs),
                sizeOf = { q -> if (pickerSongs.size == 1) pickerSongs.first().qualitySizes[q] else null },
                labelOf = { q -> playQualityLabelOf(data.platform, q) },
                    onSelect = { q ->
                        // 跑在全局串行队列上：本弹窗的作用域一关就作废，
                        // 若用 scope.launch，弹窗一关剩下的歌会全部抛 ForgottenCoroutineScopeException
                        enqueueDownload("albumSheet_batch_" + System.currentTimeMillis()) { batchDownload(q) }
                    },
                onCancel = { st.pickerVisible = false },
            )
        }
        if (pickerHost == null) {
            pickerPanel()
        } else {
            // **可见性必须在组合期读一次**。`SideEffect` 块里的读取不建立组合依赖：
            // 只在块里读的话，`pickerVisible` 从 false 变 true 不会让本页重组 →
            // `SideEffect` 不重跑 → 槽位永远不写 → 第一次点下载什么都不弹，
            // 要等下一次无关重组（例如退出多选）才把面板「迟到」地挂上去。
            val pickerOpen = st.pickerVisible
            // 只在面板打开时抢占槽位；关闭时不写，保留内容让面板自带淡出动画播完。
            SideEffect {
                if (pickerOpen) pickerHost.show(pickerOwner, pickerPanel)
            }
            DisposableEffect(pickerOwner) {
                onDispose { pickerHost.clear(pickerOwner) }
            }
        }
    }
}

@Composable
private fun AlbumDetailBody(
    st: AlbumDetailState,
    data: AlbumDetailData,
    tasks: Map<String, DownloadTask>,
    palette: ColorPalette,
    currentSongId: String?,
    onPlay: (AlbumSong) -> Unit,
    onDownload: (AlbumSong) -> Unit,
    toSongForLike: (AlbumSong) -> Song,
    onToggle: (String) -> Unit,
    onBatch: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: () -> Unit,
    motionHero: Boolean,
    barBottomPx: Int,
) {
    when {
        st.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.primary)
            }
        }

        st.error.isNotEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.sp24),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DetailErrorOutline(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
                Spacer(modifier = Modifier.height(AppSpacing.sp12))
                Text(
                    text = st.error,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.base,
                    textAlign = TextAlign.Center,
                )
            }
        }

        else -> {
            val multiDisc = st.songs.mapNotNull { it.discNumber }.distinct().size > 1
            if (motionHero) {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                    MotionHeroScaffold(
                        aspectRatio = 0.75f,
                        barBottomPx = barBottomPx,
                        listState = listState,
                        bottomInset = AppSpacing.listBottomInset,
                        video = { AlbumMotionHeroVideo(st = st, data = data) },
                        foreground = {
                            AlbumMotionHero(
                                st = st,
                                data = data,
                                toSongForLike = toSongForLike,
                                onPlayAll = { st.songs.firstOrNull()?.let(onPlay) },
                                onShuffleAll = { st.songs.randomOrNull()?.let(onPlay) },
                                onBatch = onBatch,
                                onAddToPlaylist = onAddToPlaylist,
                                onOpenArtist = onOpenArtist,
                            )
                        },
                    ) {
                        items(count = st.songs.size) { index ->
                            val song = st.songs[index]
                            val newDisc = multiDisc &&
                                (index == 0 || st.songs[index - 1].discNumber != song.discNumber)
                            Column {
                                if (newDisc) {
                                    Text(
                                        text = "CD ${song.discNumber ?: 1}",
                                        color = AppColors.textTertiary,
                                        fontSize = AppFontSize.size13,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(
                                            horizontal = AppSpacing.sp16,
                                            vertical = AppSpacing.sp10,
                                        ),
                                    )
                                }
                                AlbumDetailSongRow(
                                    song = song,
                                    songForLike = toSongForLike(song),
                                    index = index,
                                    selectMode = st.selectMode,
                                    selected = st.selectedIds.contains(song.id),
                                    isPlaying = song.id == currentSongId,
                                    playingTint = palette.readablePrimary(),
                                    task = tasks[song.id],
                                    onPlay = onPlay,
                                    onDownload = onDownload,
                                    onToggle = onToggle,
                                )
                            }
                        }
                    }

                    val resumeIndex = remember(st.songs, currentSongId) {
                        if (currentSongId.isNullOrEmpty()) {
                            null
                        } else {
                            st.songs.indexOfFirst { it.id == currentSongId }.takeIf { it >= 0 }
                        }
                    }
                    val resumeVisible = rememberPlayingRowVisible(listState, resumeIndex)
                    val resumeScope = rememberCoroutineScope()
                    ResumePlayingPill(
                        visible = resumeIndex != null && !resumeVisible,
                        palette = palette,
                        onClick = {
                            if (resumeIndex != null) {
                                resumeScope.launch { listState.animateScrollToItem(resumeIndex) }
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
            } else {
                // 列表直接铺在封面取色背景上（不套玻璃面板）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
                ) {
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                        // 底部内边距保留「迷你条 + 手势条」空间
                        contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
                    ) {
                        item {
                            AlbumDetailHero(
                                st = st,
                                data = data,
                                toSongForLike = toSongForLike,
                                onPlayAll = { st.songs.firstOrNull()?.let(onPlay) },
                                onShuffleAll = { st.songs.randomOrNull()?.let(onPlay) },
                                onBatch = onBatch,
                                onAddToPlaylist = onAddToPlaylist,
                                onOpenArtist = onOpenArtist,
                            )
                        }
                        items(count = st.songs.size) { index ->
                            val song = st.songs[index]
                            val newDisc = multiDisc &&
                                (index == 0 || st.songs[index - 1].discNumber != song.discNumber)
                            Column {
                                if (newDisc) {
                                    Text(
                                        text = "CD ${song.discNumber ?: 1}",
                                        color = AppColors.textTertiary,
                                        fontSize = AppFontSize.size13,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(
                                            horizontal = AppSpacing.sp16,
                                            vertical = AppSpacing.sp10,
                                        ),
                                    )
                                }
                                AlbumDetailSongRow(
                                    song = song,
                                    // 行尾爱心要一个 `Song`（收藏键由它算），转换在这里做一次
                                    songForLike = toSongForLike(song),
                                    index = index,
                                    selectMode = st.selectMode,
                                    selected = st.selectedIds.contains(song.id),
                                    isPlaying = song.id == currentSongId,
                                    playingTint = palette.readablePrimary(),
                                    task = tasks[song.id],
                                    onPlay = onPlay,
                                    onDownload = onDownload,
                                    onToggle = onToggle,
                                )
                            }
                        }
                    }

                    val staticRowIndex = remember(st.songs, currentSongId) {
                        if (currentSongId.isNullOrEmpty()) {
                            null
                        } else {
                            st.songs.indexOfFirst { it.id == currentSongId }
                                .takeIf { it >= 0 }
                                ?.plus(1)
                        }
                    }
                    val staticVisible = rememberPlayingRowVisible(listState, staticRowIndex)
                    val staticScope = rememberCoroutineScope()
                    ResumePlayingPill(
                        visible = staticRowIndex != null && !staticVisible,
                        palette = palette,
                        onClick = {
                            if (staticRowIndex != null) {
                                staticScope.launch { listState.animateScrollToItem(staticRowIndex) }
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

/**
 * 头部 Hero：大封面居中 + 居中标题/歌手/元信息 + 动作行（播放全部 / 下载）。
 *
 * 元信息行 =「年份 · N 首」，Qobuz 专辑再追加最高位深·采样率（[AlbumDetailState.qualitySpec]）；
 * 缺哪项自动省略、不留悬空分隔符。多选时在其下追加 [MultiSelectDownloadBar]（行为与原来一致）。
 */
@Composable
private fun AlbumDetailHero(
    st: AlbumDetailState,
    data: AlbumDetailData,
    toSongForLike: (AlbumSong) -> Song,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onBatch: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: () -> Unit,
) {
    // 收藏状态就地取（Activity 作用域的 VM，任何层级取到的都是同一份）；
    // 不用从列表层一路传参数 —— 收藏只跟「这一张专辑」有关，和列表状态无关。
    val favoriteViewModel: FavoriteViewModel = activityViewModel()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
    val favoriteKey = remember(data.id, data.platform) {
        FavoriteKeys.album(data.platform, data.id, data.name, data.artist)
    }
    val favorited = remember(favoriteSnapshot, favoriteKey) {
        favoriteViewModel.isAlbumFavorite(favoriteKey)
    }
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
                .clip(RoundedCornerShape(AppRadius.panel))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            var coverFailed by remember(data.id) { mutableStateOf(false) }
            if (data.cover.isEmpty() || coverFailed) {
                MusicNoteGlyph(modifier = Modifier.size(AppIconSize.icon36), tint = AppColors.textDisabled)
            } else {
                AsyncImage(
                    model = data.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { coverFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Text(
            text = data.name,
            color = AppColors.textPrimary,
            fontSize = AppFontSize.size20,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp4))
        Text(
            text = data.artist,
            // 有 id 就秒开（酷我专辑接口本来就给 artistid）；没有则退回按名字搜索一次
            modifier = Modifier.clickableRipple { onOpenArtist() },
            color = AppColors.whiteA50,
            fontSize = AppFontSize.size15,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = albumDetailMetaLine(st)
        if (meta.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = meta,
                color = AppColors.whiteA40,
                fontSize = AppFontSize.size13,
                textAlign = TextAlign.Center,
            )
        }
        // 间距与静态封面模式一致：动作行上下各 16dp（原来这里是裸 Spacer + 行自身无边距，
        // 两种模式切换时按钮会上下跳一格）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sp16),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumHeroActions(
                toSongForLike = toSongForLike,
                st = st,
                data = data,
                favorited = favorited,
                favoriteViewModel = favoriteViewModel,
                onPlayAll = onPlayAll,
                onShuffleAll = onShuffleAll,
                onBatch = onBatch,
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

@Composable
private fun AlbumMotionHeroVideo(
    st: AlbumDetailState,
    data: AlbumDetailData,
) {
    DetailMotionHeroVideo(
        cover = data.cover,
        tallCover = st.tallCover.ifEmpty { data.cover },
        motionCover = st.motionCover,
    )
}

@Composable
private fun AlbumMotionHero(
    st: AlbumDetailState,
    data: AlbumDetailData,
    toSongForLike: (AlbumSong) -> Song,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onBatch: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoriteViewModel: FavoriteViewModel = activityViewModel()
    val favoriteSnapshot by favoriteViewModel.snapshot.collectAsState()
    val favoriteKey = remember(data.id, data.platform) {
        FavoriteKeys.album(data.platform, data.id, data.name, data.artist)
    }
    val favorited = remember(favoriteSnapshot, favoriteKey) {
        favoriteViewModel.isAlbumFavorite(favoriteKey)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DetailMotionHeroForeground(
            aspectRatio = 0.75f,
            title = data.name,
            subtitle = data.artist,
            meta = albumDetailMetaLine(st),
            onSubtitleClick = { onOpenArtist() },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.sp16),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumMotionHeroActions(
                toSongForLike = toSongForLike,
                st = st,
                data = data,
                favorited = favorited,
                favoriteViewModel = favoriteViewModel,
                onPlayAll = onPlayAll,
                onShuffleAll = onShuffleAll,
                onBatch = onBatch,
                onAddToPlaylist = onAddToPlaylist,
            )
        }
    }
}

@Composable
private fun AlbumHeroActions(
    st: AlbumDetailState,
    data: AlbumDetailData,
    favorited: Boolean,
    favoriteViewModel: FavoriteViewModel,
    toSongForLike: (AlbumSong) -> Song,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onBatch: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    // 「加入到歌单」交给宿主打开「添加到」面板（见 PlaylistPickerRequest）：内联渲染会被底栏压住
    Row(
        modifier = Modifier.fillMaxWidth(),
        // **永不溢出**的排法：中间三颗放进一个 weight(1f) 的槽里自己居中，两端按钮贴槽外侧。
        // 之前是固定 16dp 间距的居中整组，多选态 5 件合计 333dp > 可用 315dp（hero 左右各 24dp）
        // 就会溢出：左边的挤进内边距、右边的被推出容器。用 weight 后中间槽会随可用宽度收缩，
        // 永远不会出现「内容比容器宽」。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (st.selectMode) {
            DetailRoundIconButton(
                onClick = onAddToPlaylist,
                enabled = st.selectedIds.isNotEmpty(),
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_action_playlist_add),
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
                onClick = {
                    favoriteViewModel.toggleAlbum(
                        platform = data.platform,
                        albumId = data.id,
                        name = data.name,
                        artist = data.artist,
                        cover = data.cover,
                        // 列表展示用：收藏那一刻的曲目数与发行日期（之后打开详情页会校准）
                        trackCount = st.songs.size,
                        releaseDate = st.releaseDate,
                        artistId = data.artistId,
                    )
                },
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailPlayAllPill(
                onClick = onPlayAll,
                enabled = st.songs.isNotEmpty(),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailRoundIconButton(
                onClick = onShuffleAll,
                enabled = st.songs.isNotEmpty(),
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_action_shuffle),
                    contentDescription = "随机播放",
                    tint = tint,
                    modifier = Modifier.size(AppIconSize.icon22),
                )
            }
        }
        if (st.selectMode) {
            DetailRoundIconButton(
                onClick = onBatch,
                enabled = st.selectedIds.isNotEmpty(),
            ) { tint ->
                DownloadIconPlaceholder(
                    modifier = Modifier.size(AppIconSize.icon20),
                    tint = tint,
                )
            }
        }
    }
}

/**
 * 动态封面 hero 的动作行：**旧排法**（固定 16dp 间距、整组居中）。
 *
 * 静态封面 hero 用 [AlbumHeroActions] 的「weight 中间槽」排法 —— 那一页 hero 左右各有 24dp
 * 内边距，多选态 5 件会超过可用宽度；动态封面 hero 没有这层内边距，宽度够用，
 * 所以这里保留原来的居中整组，两种模式各按自己的容器宽度办事。
 */
@Composable
private fun AlbumMotionHeroActions(
    st: AlbumDetailState,
    data: AlbumDetailData,
    favorited: Boolean,
    favoriteViewModel: FavoriteViewModel,
    toSongForLike: (AlbumSong) -> Song,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onBatch: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (st.selectMode) {
            DetailRoundIconButton(
                onClick = onAddToPlaylist,
                enabled = st.selectedIds.isNotEmpty(),
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
            DetailHeartButton(
                selected = favorited,
                onClick = {
                    favoriteViewModel.toggleAlbum(
                        platform = data.platform,
                        albumId = data.id,
                        name = data.name,
                        artist = data.artist,
                        cover = data.cover,
                        trackCount = st.songs.size,
                        releaseDate = st.releaseDate,
                        artistId = data.artistId,
                    )
                },
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailPlayAllPill(
                onClick = onPlayAll,
                enabled = st.songs.isNotEmpty(),
            )
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailRoundIconButton(
                onClick = onShuffleAll,
                enabled = st.songs.isNotEmpty(),
            ) { tint ->
                Icon(
                    painter = painterResource(R.drawable.ic_action_shuffle),
                    contentDescription = "随机播放",
                    tint = tint,
                    modifier = Modifier.size(AppIconSize.icon22),
                )
            }
        }
        if (st.selectMode) {
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            DetailRoundIconButton(
                onClick = onBatch,
                enabled = st.selectedIds.isNotEmpty(),
            ) { tint ->
                DownloadIconPlaceholder(
                    modifier = Modifier.size(AppIconSize.icon20),
                    tint = tint,
                )
            }
        }
    }
}

/** Hero 元信息行：「年份 · N 首 · 最高位深/采样率」；缺项自动省略 */
private fun albumDetailMetaLine(st: AlbumDetailState): String {
    val parts = mutableListOf<String>()
    val year = st.releaseDate.trim().take(4)
    if (year.length == 4 && year.all { it.isDigit() }) parts.add(year)
    parts.add("${st.songs.size} 首")
    if (st.qualitySpec.isNotEmpty()) parts.add(st.qualitySpec)
    return parts.joinToString(" · ")
}

/** 曲目行：序号 / 播放指示 + 名称 / 歌手 +（多选勾选框 | 任务徽标 | ⋮） */
@Composable
private fun AlbumDetailSongRow(
    song: AlbumSong,
    songForLike: Song,
    index: Int,
    selectMode: Boolean,
    selected: Boolean,
    isPlaying: Boolean,
    playingTint: Color,
    task: DownloadTask?,
    onPlay: (AlbumSong) -> Unit,
    onDownload: (AlbumSong) -> Unit,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(AppSize.detailRowHeight)
            // 正在播放 → 主色氛围底 + 左侧主色条；否则选中态浅高亮
            .then(
                when {
                    isPlaying -> Modifier.playingRowBackground(playingTint)
                    selected -> Modifier.background(AppColors.primaryA08)
                    else -> Modifier
                },
            )
            .clickableNoRipple { if (selectMode) onToggle(song.id) else onPlay(song) }
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(AppSpacing.sp28),
            contentAlignment = Alignment.Center,
        ) {
            if (isPlaying) {
                PlayingIndicator(tint = playingTint)
            } else {
                Text(
                    text = song.track?.toString() ?: "${index + 1}",
                    color = AppColors.white60,
                    fontSize = AppFontSize.size15,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp10))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = song.name,
                color = if (isPlaying) playingTint else AppColors.textPrimary,
                fontSize = AppFontSize.lg,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp1))
            Text(
                text = song.artistName,
                color = AppColors.textTertiary,
                fontSize = AppFontSize.size13,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 行尾槽位只有两种内容：多选态 = 勾选框，常态 = 任务徽标 / ⋮（二者不会同时出现）
        if (selectMode) {
            SelectCheckBox(
                checked = selected,
                onToggle = { onToggle(song.id) },
            )
        } else if (task != null && task.status != DownloadStatus.completed) {
            AlbumDetailTaskBadge(task = task)
        } else {
            // ♥ · ⋮：收藏状态由宿主经 `LocalSongLike` 注入
            SongRowActions(song = songForLike, onMore = { onDownload(song) })
        }
    }
}

/** 下载任务状态徽标（对齐 Dart `_TaskBadge`） */
@Composable
private fun AlbumDetailTaskBadge(task: DownloadTask) {
    when (task.status) {
        DownloadStatus.completed -> Unit

        DownloadStatus.error -> {
            DetailErrorSolid(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.danger)
        }

        DownloadStatus.downloading -> {
            Column(
                modifier = Modifier.width(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AppColors.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp1))
                Text(
                    text = "${task.progress.roundToInt()}%",
                    color = AppColors.primary,
                    fontSize = AppFontSize.xxs,
                )
            }
        }

        // pending / paused / cancelled 均按 RN 的「等待中」呈现
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .border(width = 2.dp, color = AppColors.whiteA15, shape = CircleShape),
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp2))
                Text(
                    text = "等待中",
                    color = AppColors.whiteA25,
                    fontSize = AppFontSize.size10,
                )
            }
        }
    }
}
