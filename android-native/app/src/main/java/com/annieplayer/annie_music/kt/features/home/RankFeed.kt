package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.data.sources.rank.RankBoard
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple
import com.annieplayer.annie_music.kt.widgets.shortScrollbar

private val RankCoverSize = 96.dp

@Composable
internal fun RankFeed(
    state: RankState,
    onOpenBoard: (RankBoard) -> Unit,
    onPlayTop: (RankBoard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val boards = state.boards
    when {
        boards.isNotEmpty() -> {
            val listState = rememberLazyListState()
            val groups = boards.groupBy { it.group }
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    .shortScrollbar(listState),
                contentPadding = PaddingValues(
                    start = AppSpacing.sp16,
                    end = AppSpacing.sp16,
                    top = AppSpacing.sp12,
                    bottom = AppSpacing.searchListBottomInset,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sp16),
            ) {
                groups.forEach { (group, groupBoards) ->
                    if (group.isNotEmpty()) {
                        item(key = "group:$group") {
                            Text(
                                text = group,
                                color = AppColors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = AppSpacing.sp8),
                            )
                        }
                    }
                    items(groupBoards, key = { "board:${it.id}" }) { board ->
                        RankBoardCard(
                            board = board,
                            onOpen = { onOpenBoard(board) },
                            onPlay = { onPlayTop(board) },
                        )
                    }
                }
            }
        }

        state.loading || !state.loaded -> RankFeedSkeleton(modifier = modifier)

        else -> RankFeedEmpty(modifier = modifier)
    }
}

@Composable
private fun RankBoardCard(
    board: RankBoard,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppRadius.r10))
            .clickableRipple(onOpen)
            .padding(vertical = AppSpacing.sp4),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(RankCoverSize)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA06),
        ) {
            if (board.cover.isNotEmpty()) {
                AsyncImage(
                    model = board.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppSpacing.sp6)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0x8C000000))
                    .clickableRipple(onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = AppColors.white,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = board.name,
                color = AppColors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (board.subtitle.isNotEmpty()) {
                Text(
                    text = board.subtitle,
                    color = AppColors.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (board.top.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                board.top.take(3).forEach { preview ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            text = "${preview.rank}",
                            color = AppColors.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(16.dp),
                        )
                        Column {
                            Text(
                                text = preview.title,
                                color = AppColors.textPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (preview.artist.isNotEmpty()) {
                                Text(
                                    text = preview.artist,
                                    color = AppColors.textSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RankFeedSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = AppSpacing.sp16, vertical = AppSpacing.sp12),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sp16),
    ) {
        repeat(5) {
            Row(modifier = Modifier.fillMaxWidth()) {
                RankSkeletonBlock(width = RankCoverSize, height = RankCoverSize)
                Spacer(modifier = Modifier.width(AppSpacing.sp12))
                Column(modifier = Modifier.weight(1f)) {
                    RankSkeletonBlock(width = 140.dp, height = 18.dp)
                    Spacer(modifier = Modifier.height(AppSpacing.sp8))
                    repeat(3) {
                        RankSkeletonBlock(width = 200.dp, height = 12.dp)
                        Spacer(modifier = Modifier.height(AppSpacing.sp6))
                    }
                }
            }
        }
    }
}

@Composable
private fun RankSkeletonBlock(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(AppRadius.r10))
            .background(Color(0x1AFFFFFF)),
    )
}

@Composable
private fun RankFeedEmpty(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "这个平台暂时没有排行榜",
            color = AppColors.textSecondary,
            fontSize = 14.sp,
        )
    }
}
