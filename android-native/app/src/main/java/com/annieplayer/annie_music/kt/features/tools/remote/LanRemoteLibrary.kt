package com.annieplayer.annie_music.kt.features.tools.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.core.remote.LanRemoteViewModel
import com.annieplayer.annie_music.kt.core.remote.RemoteLibRow
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.widgets.drawEighthNote
import kotlinx.coroutines.delay

private const val RemoteSearchDebounceMs = 350L
private const val RemoteLoadMoreThreshold = 6
private val RemoteLibThumb = 44.dp

@Composable
fun LanRemoteLibrary(
    remoteViewModel: LanRemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by remoteViewModel.ui.collectAsState()
    val listState = rememberLazyListState()
    var appliedQuery by remember { mutableStateOf(ui.libraryQuery) }

    LaunchedEffect(Unit) {
        if (ui.library.isEmpty() && !ui.libraryLoading) remoteViewModel.searchLibrary()
    }

    LaunchedEffect(ui.libraryQuery) {
        if (ui.libraryQuery == appliedQuery) return@LaunchedEffect
        delay(RemoteSearchDebounceMs)
        appliedQuery = ui.libraryQuery
        remoteViewModel.searchLibrary()
    }

    LaunchedEffect(listState, ui.library.size, ui.libraryEnd) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastIndex ->
                if (lastIndex >= ui.library.size - RemoteLoadMoreThreshold && !ui.libraryEnd) {
                    remoteViewModel.loadMoreLibrary()
                }
            }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp24),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = ui.libraryQuery,
                    onValueChange = remoteViewModel::setLibraryQuery,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AppColors.white,
                        fontSize = AppFontSize.size15,
                    ),
                    cursorBrush = SolidColor(AppColors.white),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                            if (ui.libraryQuery.isEmpty()) {
                                Text(
                                    text = "搜索歌名 / 歌手 / 专辑",
                                    color = AppColors.white24,
                                    fontSize = AppFontSize.size15,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp8))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AppColors.white12),
                )
            }
            if (ui.libraryQuery.isNotEmpty()) {
                Text(
                    text = "清空",
                    color = AppColors.white38,
                    fontSize = AppFontSize.xs,
                    modifier = Modifier
                        .padding(start = AppSpacing.sp12)
                        .clickableNoRipple { remoteViewModel.setLibraryQuery("") },
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
        if (ui.library.isEmpty() && !ui.libraryLoading) {
            RemoteLibraryEmpty(
                query = ui.libraryQuery,
                onRetry = { remoteViewModel.searchLibrary() },
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = AppSpacing.sheetBottomInset),
            ) {
                items(ui.library, key = { row -> row.path.ifEmpty { row.title } }) { row ->
                    RemoteLibRowView(row = row, onClick = { remoteViewModel.playPath(row.path) })
                }
                item("status") {
                    Text(
                        text = libraryStatusText(ui),
                        color = AppColors.white30,
                        fontSize = AppFontSize.xs,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.sp16),
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteLibraryEmpty(query: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickableNoRipple(onRetry),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AppColors.whiteA04),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(26.dp)) {
                drawEighthNote(
                    tint = AppColors.white24,
                    w = size.width,
                    h = size.height,
                    centerX = size.width * 0.42f,
                    centerY = size.height * 0.50f,
                    stroke = size.width * 0.09f,
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp16))
        Text(
            text = if (query.isNotEmpty()) "没有匹配「$query」的歌曲" else "曲库为空或尚未同步",
            color = AppColors.white54,
            fontSize = AppFontSize.size13,
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp6))
        Text(
            text = "点此重新拉取",
            color = AppColors.white30,
            fontSize = AppFontSize.xs,
        )
    }
}

@Composable
private fun RemoteLibRowView(row: RemoteLibRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .drawBehind {
                val stroke = AppBorder.thin.toPx()
                val inset = AppSpacing.sp24.toPx()
                drawLine(
                    color = AppColors.whiteA04,
                    start = Offset(inset, size.height - stroke / 2f),
                    end = Offset(size.width - inset, size.height - stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(RemoteLibThumb)
                .clip(RoundedCornerShape(AppRadius.r10))
                .background(AppColors.whiteA05),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(20.dp)) {
                drawEighthNote(
                    tint = AppColors.white30,
                    w = size.width,
                    h = size.height,
                    centerX = size.width * 0.42f,
                    centerY = size.height * 0.50f,
                    stroke = size.width * 0.10f,
                )
            }
        }
        Spacer(modifier = Modifier.width(AppSpacing.sp12))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppSpacing.sp2)) {
            Text(
                text = row.title,
                color = AppColors.white,
                fontSize = AppFontSize.size15,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(row.artist, row.album).filter { it.isNotEmpty() }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = AppColors.white38,
                    fontSize = AppFontSize.sm,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun libraryStatusText(ui: LanRemoteViewModel.UiState): String = when {
    ui.libraryLoading -> "加载中…"
    ui.libraryEnd -> "共 ${ui.libraryTotal} 首"
    else -> "已加载 ${ui.library.size}/${ui.libraryTotal} · 上滑加载更多"
}
