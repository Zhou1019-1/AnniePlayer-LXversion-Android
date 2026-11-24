package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.core.state.DownloadViewModel
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailData
import com.annieplayer.annie_music.kt.core.state.PlayerViewModel
import com.annieplayer.annie_music.kt.core.state.RankDetailRoute
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.data.sources.rank.RankPageSize
import com.annieplayer.annie_music.kt.data.sources.rank.fetchRankDetail
import com.annieplayer.annie_music.kt.features.library.LocalDetailTopBar
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.core.state.PlaylistDetailPreload
import com.annieplayer.annie_music.kt.widgets.PlaylistDetailSheet
import com.annieplayer.annie_music.kt.widgets.SkeletonLoader

@Composable
internal fun RankDetailScreen(
    route: RankDetailRoute,
    isTop: Boolean,
    onClose: () -> Unit,
    uiViewModel: UiViewModel,
    playerViewModel: PlayerViewModel,
    downloadViewModel: DownloadViewModel,
) {
    var preload by remember(route.key) { mutableStateOf(route.preload) }
    var loading by remember(route.key) { mutableStateOf(route.preload == null) }

    LaunchedEffect(route.key) {
        if (route.preload != null) return@LaunchedEffect
        loading = true
        val detail = runCatching {
            fetchRankDetail(route.platform, route.boardId, 1, RankPageSize)
        }.getOrNull()
        preload = detail?.takeIf { it.songs.isNotEmpty() }?.let {
            PlaylistDetailPreload(
                name = it.title.ifBlank { route.name },
                creator = it.subtitle.ifBlank { route.subtitle },
                cover = it.cover.ifBlank { route.cover },
                songs = it.songs,
            )
        }
        loading = false
    }

    val ready = preload
    if (ready != null) {
        PlaylistDetailSheet(
            data = PlaylistDetailData(
                id = route.boardId,
                platform = route.platform.name,
                name = ready.name,
                cover = ready.cover,
                isRank = true,
            ),
            preload = ready,
            isTop = isTop,
            onClose = onClose,
            uiViewModel = uiViewModel,
            playerViewModel = playerViewModel,
            downloadViewModel = downloadViewModel,
        )
        return
    }

    LocalOverlayFrame(onClose = onClose) { requestClose ->
        Box(modifier = Modifier.fillMaxSize()) {
            LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                LocalDetailTopBar(title = route.name, count = null, onClose = requestClose)
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SkeletonLoader(columns = 1, largeRows = true)
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "这个榜单暂时没有曲目",
                            color = AppColors.textSecondary,
                            fontSize = AppFontSize.size15,
                        )
                    }
                }
            }
        }
    }
}
