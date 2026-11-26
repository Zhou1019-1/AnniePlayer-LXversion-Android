package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.home.NewTabItem
import com.annieplayer.annie_music.kt.data.sources.rank.AppleChartEntry
import com.annieplayer.annie_music.kt.data.sources.rank.AppleChartKind
import com.annieplayer.annie_music.kt.data.sources.rank.AppleChartSection
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.SkeletonLoader
import com.annieplayer.annie_music.kt.widgets.clickableRipple

internal val AppleChartSidePadding = 16.dp
internal val AppleChartCover = 48.dp
internal val AppleChartCoverRadius = 5.dp
internal val AppleChartRankWidth = 34.dp
internal val AppleChartRowPadV = 4.dp
internal val AppleChartTitleGap = 6.dp
internal val AppleChartPlainGap = 12.dp
internal val AppleChartDividerStart = 12.dp
internal val AppleChartSectionGap = 16.dp
internal val AppleChartPeek = 36.dp
internal val AppleChartItemGap = 10.dp
internal const val AppleChartRowsPerPage = 4
internal const val AppleChartColumns = 2
internal const val AppleChartTitleSize = 17f
internal const val AppleChartSubtitleSize = 12f
internal const val AppleChartRankSize = 17f

@Composable
internal fun AppleRankFeed(
    state: AppleRankState,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
    onOpenRoom: (AppleChartSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = state.sections
    when {
        sections.isEmpty() && (state.loading || !state.loaded) -> SkeletonLoader(
            modifier = modifier.fillMaxSize(),
            columns = 1,
            largeRows = true,
        )

        sections.isEmpty() -> AppleRankEmpty(modifier = modifier.fillMaxSize())

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AppSpacing.listBottomInset),
        ) {
            items(sections, key = { it.id }) { section ->
                AppleChartSectionBlock(
                    section = section,
                    currentSongId = currentSongId,
                    onPlaySong = onPlaySong,
                    onMore = onMore,
                    onOpenTarget = onOpenTarget,
                    onOpenRoom = { onOpenRoom(section) },
                )
            }
        }
    }
}

@Composable
private fun AppleChartSectionBlock(
    section: AppleChartSection,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
    onOpenRoom: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        NewTabSectionHeader(
            title = section.title,
            showChevron = true,
            onClick = onOpenRoom,
        )
        when (section.kind) {
            AppleChartKind.Tracks -> AppleChartTrackShelf(
                section = section,
                currentSongId = currentSongId,
                onPlaySong = onPlaySong,
                onMore = onMore,
            )

            AppleChartKind.Cards -> AppleChartCardShelf(
                entries = section.entries,
                onOpenTarget = onOpenTarget,
            )
        }
        Spacer(modifier = Modifier.height(AppleChartSectionGap))
    }
}

@Composable
private fun AppleChartTrackShelf(
    section: AppleChartSection,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
) {
    val queue = section.entries.mapNotNull { it.song }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val pageWidth = maxWidth - AppleChartSidePadding * 2 - AppleChartPeek
        val pages = section.entries.chunked(AppleChartRowsPerPage)
        val pagerState = rememberPagerState(pageCount = { pages.size })
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(
                start = AppleChartSidePadding,
                end = AppleChartSidePadding,
            ),
            pageSpacing = AppleChartItemGap,
            pageSize = PageSize.Fixed(pageWidth),
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
            ),
        ) { index ->
            Column(modifier = Modifier.width(pageWidth)) {
                pages[index].forEachIndexed { offset, entry ->
                    val globalIndex = index * AppleChartRowsPerPage + offset
                    AppleChartEntryRow(
                        entry = entry,
                        rank = globalIndex + 1,
                        currentSongId = currentSongId,
                        queue = queue,
                        drawDivider = globalIndex != section.entries.lastIndex,
                        onPlaySong = onPlaySong,
                        onMore = onMore,
                        onOpenTarget = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun AppleChartCardShelf(
    entries: List<AppleChartEntry>,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - AppleChartSidePadding * 2 - AppleChartPeek -
            AppleChartItemGap * (AppleChartColumns - 1)) / AppleChartColumns
        LazyRow(
            contentPadding = PaddingValues(
                start = AppleChartSidePadding,
                end = AppleChartSidePadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(AppleChartItemGap),
        ) {
            items(entries, key = { it.key }) { entry ->
                NewTabSquareCard(
                    item = entry.toNewTabItem(),
                    width = cardWidth,
                    onClick = { entry.target?.let(onOpenTarget) },
                )
            }
        }
    }
}

@Composable
internal fun AppleChartEntryRow(
    entry: AppleChartEntry,
    rank: Int?,
    currentSongId: String?,
    queue: List<Song>,
    drawDivider: Boolean,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    val song = entry.song
    AppleChartRow(
        rank = rank,
        cover = entry.cover,
        title = entry.title,
        subtitle = entry.subtitle,
        titleColor = if (song != null && song.id == currentSongId) {
            AppColors.playingGray
        } else {
            AppColors.white
        },
        drawDivider = drawDivider,
        onClick = {
            when {
                song != null -> onPlaySong(song, queue)
                entry.target != null -> onOpenTarget(entry.target)
            }
        },
        trailing = if (song != null) {
            {
                Box(
                    modifier = Modifier
                        .size(AppSpacing.sp44)
                        .clickableRipple { onMore(song) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⋮",
                        color = AppColors.textSecondary,
                        fontSize = 18.sp,
                    )
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun AppleChartRow(
    rank: Int?,
    cover: String,
    title: String,
    subtitle: String,
    titleColor: Color,
    drawDivider: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickableRipple(onClick)
                .padding(
                    start = AppleChartSidePadding,
                    end = AppleChartSidePadding,
                    top = AppleChartRowPadV,
                    bottom = AppleChartRowPadV,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(AppleChartCover)
                    .clip(RoundedCornerShape(AppleChartCoverRadius))
                    .background(AppColors.whiteA03),
            ) {
                NewTabCover(url = cover, placeholderSize = 18.sp)
            }
            if (rank != null) {
                Box(
                    modifier = Modifier.width(AppleChartRankWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = rank.toString(),
                        color = AppColors.white,
                        fontSize = AppleChartRankSize.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(AppleChartPlainGap))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (rank != null) {
                            Modifier.padding(start = AppleChartTitleGap)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = AppleChartTitleSize.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = AppColors.textSecondary,
                        fontSize = AppleChartSubtitleSize.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
        if (drawDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppleChartSidePadding + AppleChartCover + AppleChartDividerStart,
                        end = AppleChartSidePadding,
                    )
                    .height(AppleDividerStroke)
                    .background(AppleSeparator),
            )
        }
    }
}

internal fun AppleChartEntry.toNewTabItem(): NewTabItem = NewTabItem(
    key = key,
    title = title,
    subtitle = subtitle,
    cover = cover,
    target = target,
)

@Composable
private fun AppleRankEmpty(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "该地区暂无排行榜",
            color = AppColors.textSecondary,
            fontSize = 14.sp,
        )
    }
}
