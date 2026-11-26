package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.state.NewTabRoomRoute
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.home.NewTabItem
import com.annieplayer.annie_music.kt.data.sources.home.NewTabSectionKind
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.DetailChevronLeft
import com.annieplayer.annie_music.kt.widgets.SkeletonLoader
import com.annieplayer.annie_music.kt.widgets.clickableRipple

private val RoomTopBarHeight = 48.dp
private val RoomTopInset = 8.dp
private val RoomBackSize = 38.dp
private val RoomBackStart = 10.dp
private val RoomSidePadding = 16.dp
private val RoomColumnGap = 16.dp
private val RoomRowGap = 8.dp
private val RoomTitleTop = 16.dp
private val RoomTitleBottom = 34.dp
private val RoomGridTitleTop = 8.dp
private val RoomLeadingInset = 16.dp
private val RoomBackFill = Color(0x24FFFFFF)
private const val RoomTitleSize = 17f

@Composable
fun NewTabRoomScreen(
    route: NewTabRoomRoute,
    onClose: () -> Unit,
    roomState: NewTabRoomState,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    LaunchedEffect(route.key) {
        roomState.load(route.platform, route.roomId)
    }

    LocalOverlayFrame(onClose = onClose) { requestClose ->
        val room = roomState.room
        val title = room?.title?.ifEmpty { null } ?: route.title
        Box(modifier = Modifier.fillMaxSize()) {
            LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
            Box(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(top = RoomTopInset)) {
                when {
                    room == null && roomState.loading -> SkeletonLoader(
                        modifier = Modifier.fillMaxSize(),
                        columns = 1,
                        largeRows = true,
                    )

                    room == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "加载失败",
                            color = AppColors.textSecondary,
                            fontSize = 14.sp,
                        )
                    }

                    room.kind == NewTabSectionKind.Tracks -> RoomSongList(
                        title = title,
                        songs = room.songs,
                        currentSongId = currentSongId,
                        onPlaySong = onPlaySong,
                        onMore = onMore,
                    )

                    else -> RoomCardGrid(
                        title = title,
                        items = room.items,
                        onOpenTarget = onOpenTarget,
                    )
                }
                RoomTopBar(onClose = requestClose)
            }
        }
    }
}

@Composable
internal fun RoomTopBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RoomTopBarHeight)
            .padding(start = RoomBackStart),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(RoomBackSize)
                .clip(CircleShape)
                .background(RoomBackFill)
                .clickableRipple(onClose),
            contentAlignment = Alignment.Center,
        ) {
            DetailChevronLeft(
                modifier = Modifier.size(20.dp),
                tint = AppColors.white,
            )
        }
    }
}

@Composable
private fun RoomSongList(
    title: String,
    songs: List<Song>,
    currentSongId: String?,
    onPlaySong: (Song, List<Song>) -> Unit,
    onMore: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = AppSpacing.bottomBarInset),
    ) {
        item(key = "title") {
            RoomTitle(text = title, topPadding = RoomTitleTop)
        }
        items(songs, key = { it.id }) { song ->
            NewTabTrackRow(
                song = song,
                isActive = song.id == currentSongId,
                startPadding = 0.dp,
                leadingInset = RoomLeadingInset,
                onPlay = { onPlaySong(song, songs) },
                onMore = { onMore(song) },
            )
        }
    }
}

@Composable
private fun RoomCardGrid(
    title: String,
    items: List<NewTabItem>,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = RoomSidePadding,
            end = RoomSidePadding,
            bottom = AppSpacing.bottomBarInset,
        ),
        horizontalArrangement = Arrangement.spacedBy(RoomColumnGap),
        verticalArrangement = Arrangement.spacedBy(RoomRowGap),
    ) {
        item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
            RoomTitle(text = title, topPadding = RoomGridTitleTop)
        }
        items(items, key = { it.key }) { item ->
            NewTabSquareCard(
                item = item,
                width = null,
                onClick = { item.target?.let(onOpenTarget) },
            )
        }
    }
}

@Composable
internal fun RoomTitle(text: String, topPadding: Dp) {
    Text(
        text = text,
        color = AppColors.white,
        fontSize = RoomTitleSize.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = RoomSidePadding,
                end = RoomSidePadding,
                top = topPadding,
                bottom = RoomTitleBottom,
            ),
    )
}
