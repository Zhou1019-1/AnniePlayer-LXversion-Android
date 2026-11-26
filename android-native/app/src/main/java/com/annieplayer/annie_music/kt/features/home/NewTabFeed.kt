package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.home.NewTabItem
import com.annieplayer.annie_music.kt.data.sources.home.NewTabSection
import com.annieplayer.annie_music.kt.data.sources.home.NewTabSectionKind
import com.annieplayer.annie_music.kt.data.sources.home.NewTabTrackRows
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.DetailChevronRight
import com.annieplayer.annie_music.kt.widgets.SkeletonLoader
import com.annieplayer.annie_music.kt.widgets.clickableRipple

private val NewTabSidePadding = 16.dp
private val NewTabItemGap = 10.dp
private val NewTabPeek = 36.dp
private val NewTabShelfGap = 16.dp
private val NewTabHeroShelfGap = 31.dp
private val NewTabHeaderTop = 13.dp
private val NewTabHeaderBottom = 10.dp
private val NewTabCoverRadiusM = 7.dp
private val NewTabCoverRadiusL = 10.dp
private val NewTabCoverRadiusS = 5.dp
private val NewTabTitleMarginTop = 7.dp
private val NewTabCardBottom = 12.dp
private val NewTabTrackCover = 48.dp
private val NewTabTrackPadV = 4.dp
private val NewTabHeroInset = 58.dp
private const val NewTabColumns = 2
private const val NewTabHeroAspect = 1.4545f
private const val NewTabTitleSize = 15f
private const val NewTabSubtitleSize = 15f
private const val NewTabTrackTitleSize = 17f
private const val NewTabTrackSubtitleSize = 12f
private const val NewTabEyebrowSize = 11f
private const val NewTabHeroTitleSize = 20f
private const val NewTabHeaderSize = 20f
private val AppleSystemGray = Color(0xFF8E8E93)
internal val AppleSeparator = Color(0xA6545458)
internal val AppleDividerStroke = 0.5.dp

@Composable
fun NewTabFeed(
    state: NewTabState,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
    onOpenRoom: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = state.sections
    when {
        sections.isEmpty() && state.loading -> SkeletonLoader(
            modifier = modifier.fillMaxSize(),
            columns = 1,
            largeRows = true,
        )

        sections.isEmpty() -> NewTabEmpty(modifier = modifier.fillMaxSize())

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
        ) {
            items(sections, key = { it.id }) { section ->
                NewTabSectionBlock(
                    section = section,
                    currentSongId = currentSongId,
                    onPlaySong = onPlaySong,
                    onMore = onMore,
                    onOpenTarget = onOpenTarget,
                    onOpenRoom = onOpenRoom,
                )
            }
        }
    }
}

@Composable
private fun NewTabSectionBlock(
    section: NewTabSection,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
    onOpenRoom: (String, String) -> Unit,
) {
    val headerClick: (() -> Unit)? = section.roomId
        .takeIf { it.isNotEmpty() }
        ?.let { roomId -> { onOpenRoom(roomId, section.title) } }
    Column(modifier = Modifier.fillMaxWidth()) {
        when (section.kind) {
            NewTabSectionKind.Hero -> NewTabHeroRow(
                section = section,
                onOpenTarget = onOpenTarget,
                onHeaderClick = headerClick,
            )

            NewTabSectionKind.Tracks -> NewTabTrackList(
                section = section,
                currentSongId = currentSongId,
                onPlaySong = onPlaySong,
                onMore = onMore,
                onHeaderClick = headerClick,
            )

            NewTabSectionKind.Cards -> NewTabCardShelf(
                section = section,
                onOpenTarget = onOpenTarget,
                onHeaderClick = headerClick,
            )
        }
        Spacer(
            modifier = Modifier.height(
                if (section.kind == NewTabSectionKind.Hero) NewTabHeroShelfGap else NewTabShelfGap,
            ),
        )
    }
}

@Composable
internal fun NewTabSectionHeader(
    title: String,
    showChevron: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickableRipple(onClick) else Modifier)
            .padding(
                start = NewTabSidePadding,
                end = NewTabSidePadding,
                top = NewTabHeaderTop,
                bottom = NewTabHeaderBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = AppColors.white,
            fontSize = NewTabHeaderSize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (showChevron) {
            Spacer(modifier = Modifier.width(AppSpacing.sp4))
            DetailChevronRight(
                modifier = Modifier.size(16.dp),
                tint = AppColors.textSecondary,
            )
        }
    }
}

@Composable
private fun NewTabHeroRow(
    section: NewTabSection,
    onOpenTarget: (HomeTarget) -> Unit,
    onHeaderClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (section.title.isNotEmpty()) {
            NewTabSectionHeader(
                title = section.title,
                showChevron = onHeaderClick != null,
                onClick = onHeaderClick,
            )
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = maxWidth - NewTabSidePadding * 2 - NewTabPeek
            val pagerState = rememberPagerState(pageCount = { section.items.size })
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(start = NewTabSidePadding, end = NewTabSidePadding),
                pageSpacing = NewTabItemGap,
                pageSize = PageSize.Fixed(cardWidth),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                ),
            ) { index ->
                val item = section.items[index]
                NewTabHeroCard(
                    item = item,
                    width = cardWidth,
                    onClick = { item.target?.let(onOpenTarget) },
                )
            }
        }
    }
}

@Composable
private fun NewTabHeroCard(item: NewTabItem, width: Dp, onClick: () -> Unit) {
    Column(modifier = Modifier.width(width)) {
        if (item.eyebrow.isNotEmpty()) {
            Text(
                text = item.eyebrow,
                color = AppleSystemGray,
                fontSize = NewTabEyebrowSize.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = item.title,
            color = AppColors.white,
            fontSize = NewTabHeroTitleSize.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                text = item.subtitle,
                color = AppleSystemGray,
                fontSize = NewTabHeroTitleSize.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(NewTabHeroAspect)
                .clip(RoundedCornerShape(NewTabCoverRadiusL))
                .background(AppColors.whiteA03)
                .clickableRipple(onClick),
        ) {
            NewTabCover(url = item.cover, placeholderSize = 40.sp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(86.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x99000000)),
                        ),
                    ),
            )
            if (item.atmos) {
                Text(
                    text = "DOLBY ATMOS",
                    color = AppColors.white,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(NewTabSidePadding)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color(0x66000000))
                        .padding(horizontal = AppSpacing.sp8, vertical = AppSpacing.sp4),
                )
            }
            if (item.inset.isNotEmpty()) {
                AsyncImage(
                    model = item.inset,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = NewTabItemGap, bottom = AppSpacing.sp8)
                        .size(NewTabHeroInset)
                        .clip(RoundedCornerShape(NewTabCoverRadiusS)),
                )
            }
        }
    }
}

@Composable
private fun NewTabCardShelf(
    section: NewTabSection,
    onOpenTarget: (HomeTarget) -> Unit,
    onHeaderClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NewTabSectionHeader(
            title = section.title,
            showChevron = true,
            onClick = onHeaderClick,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth - NewTabSidePadding * 2 - NewTabPeek -
                NewTabItemGap * (NewTabColumns - 1)) / NewTabColumns
            if (section.expanded) {
                val columns = section.items.chunked(2)
                LazyRow(
                    contentPadding = PaddingValues(start = NewTabSidePadding, end = NewTabSidePadding),
                    horizontalArrangement = Arrangement.spacedBy(NewTabItemGap),
                ) {
                    items(columns, key = { it.first().key }) { pair ->
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sp20)) {
                            pair.forEach { item ->
                                NewTabSquareCard(
                                    item = item,
                                    width = cardWidth,
                                    onClick = { item.target?.let(onOpenTarget) },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(start = NewTabSidePadding, end = NewTabSidePadding),
                    horizontalArrangement = Arrangement.spacedBy(NewTabItemGap),
                ) {
                    items(section.items, key = { it.key }) { item ->
                        NewTabSquareCard(
                            item = item,
                            width = cardWidth,
                            onClick = { item.target?.let(onOpenTarget) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NewTabSquareCard(item: NewTabItem, width: Dp?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .padding(bottom = NewTabCardBottom)
            .clickableRipple(onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(NewTabCoverRadiusM))
                .background(AppColors.whiteA03),
        ) {
            NewTabCover(url = item.cover, placeholderSize = 28.sp)
        }
        Spacer(modifier = Modifier.height(NewTabTitleMarginTop))
        Text(
            text = item.title,
            color = AppColors.white,
            fontSize = NewTabTitleSize.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                text = item.subtitle,
                color = AppColors.textSecondary,
                fontSize = NewTabSubtitleSize.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewTabTrackList(
    section: NewTabSection,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onHeaderClick: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NewTabSectionHeader(
            title = section.title,
            showChevron = true,
            onClick = onHeaderClick,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth - NewTabSidePadding * 2 - NewTabPeek
            val pages = section.songs.chunked(NewTabTrackRows)
            val pagerState = rememberPagerState(pageCount = { pages.size })
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(start = NewTabSidePadding, end = NewTabSidePadding),
                pageSpacing = NewTabItemGap,
                pageSize = PageSize.Fixed(pageWidth),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                ),
            ) { index ->
                Column(modifier = Modifier.width(pageWidth)) {
                    pages[index].forEach { song ->
                        NewTabTrackRow(
                            song = song,
                            isActive = song.id == currentSongId,
                            startPadding = NewTabSidePadding,
                            leadingInset = 0.dp,
                            onPlay = { onPlaySong(song, section.songs) },
                            onMore = { onMore(song) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NewTabTrackRow(
    song: Song,
    isActive: Boolean,
    startPadding: Dp,
    leadingInset: Dp,
    onPlay: () -> Unit,
    onMore: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding + leadingInset + NewTabTrackCover + AppSpacing.sp16)
                .height(AppleDividerStroke)
                .background(AppleSeparator),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRipple(onPlay)
                .padding(
                    start = startPadding,
                    end = NewTabSidePadding,
                    top = NewTabTrackPadV,
                    bottom = NewTabTrackPadV,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingInset > 0.dp) Spacer(modifier = Modifier.width(leadingInset))
            Box(
                modifier = Modifier
                    .size(NewTabTrackCover)
                    .clip(RoundedCornerShape(NewTabCoverRadiusS))
                    .background(AppColors.whiteA03),
            ) {
                NewTabCover(url = song.cover, placeholderSize = 18.sp)
            }
            Spacer(modifier = Modifier.width(AppSpacing.sp16))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    color = if (isActive) AppColors.playingGray else AppColors.white,
                    fontSize = NewTabTrackTitleSize.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.artist.isNotEmpty()) {
                    Text(
                        text = song.artist,
                        color = AppColors.textSecondary,
                        fontSize = NewTabTrackSubtitleSize.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(AppSpacing.sp44)
                    .clickableRipple(onMore),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⋮",
                    color = AppColors.textSecondary,
                    fontSize = 18.sp,
                )
            }
        }
    }
}

@Composable
internal fun NewTabCover(url: String, placeholderSize: androidx.compose.ui.unit.TextUnit) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url.isEmpty() || failed) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "♪", color = AppColors.whiteA15, fontSize = placeholderSize)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun NewTabEmpty(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "该平台暂无新发现内容",
            color = AppColors.textSecondary,
            fontSize = 14.sp,
        )
    }
}
