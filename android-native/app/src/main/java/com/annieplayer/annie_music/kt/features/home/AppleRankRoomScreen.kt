package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.state.AppleRankRoomRoute
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.rank.AppleChartKind
import com.annieplayer.annie_music.kt.data.sources.rank.AppleChartSection
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

private val AppleRankRoomGridGap = 16.dp
private val AppleRankRoomGridRowGap = 8.dp

@Composable
internal fun AppleRankRoomScreen(
    route: AppleRankRoomRoute,
    onClose: () -> Unit,
    state: AppleRankState,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    LocalOverlayFrame(onClose = onClose) { requestClose ->
        val section = state.sections.firstOrNull { it.id == route.sectionId }
        Box(modifier = Modifier.fillMaxSize()) {
            LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(top = 8.dp),
            ) {
                if (section == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "加载失败",
                            color = AppColors.textSecondary,
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    val title = route.title.ifEmpty { section.title }
                    when (section.kind) {
                        AppleChartKind.Tracks -> AppleRankRoomTrackList(
                            title = title,
                            section = section,
                            currentSongId = currentSongId,
                            onPlaySong = onPlaySong,
                            onMore = onMore,
                        )

                        AppleChartKind.Cards -> AppleRankRoomCardGrid(
                            title = title,
                            section = section,
                            onOpenTarget = onOpenTarget,
                        )
                    }
                }
                RoomTopBar(onClose = requestClose)
            }
        }
    }
}

@Composable
private fun AppleRankRoomTrackList(
    title: String,
    section: AppleChartSection,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
) {
    val queue = section.entries.mapNotNull { it.song }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppSpacing.bottomBarInset),
    ) {
        item(key = "title") {
            RoomTitle(text = title, topPadding = 16.dp)
        }
        itemsIndexed(
            items = section.entries,
            key = { _, entry -> entry.key },
        ) { index, entry ->
            AppleChartEntryRow(
                entry = entry,
                rank = index + 1,
                currentSongId = currentSongId,
                queue = queue,
                drawDivider = index != section.entries.lastIndex,
                onPlaySong = onPlaySong,
                onMore = onMore,
                onOpenTarget = {},
            )
        }
    }
}

@Composable
private fun AppleRankRoomCardGrid(
    title: String,
    section: AppleChartSection,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(AppleChartColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AppleChartSidePadding,
            end = AppleChartSidePadding,
            bottom = AppSpacing.bottomBarInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(AppleRankRoomGridGap),
        verticalArrangement = Arrangement.spacedBy(AppleRankRoomGridRowGap),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            RoomTitle(text = title, topPadding = 8.dp)
        }
        items(section.entries, key = { it.key }) { entry ->
            NewTabSquareCard(
                item = entry.toNewTabItem(),
                width = null,
                onClick = { entry.target?.let(onOpenTarget) },
            )
        }
    }
}
