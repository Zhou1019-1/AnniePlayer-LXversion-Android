package com.annieplayer.annie_music.kt.features.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverItem
import com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverKind
import com.annieplayer.annie_music.kt.data.sources.home.fetchHomeQobuzTagPlaylists
import com.annieplayer.annie_music.kt.features.library.LocalDetailTopBar
import com.annieplayer.annie_music.kt.features.library.LocalOverlayFrame
import com.annieplayer.annie_music.kt.features.library.LocalStaticBackdrop
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

@Composable
internal fun QobuzTagScreen(
    slug: String,
    title: String,
    isTop: Boolean,
    onClose: () -> Unit,
    onOpenTarget: (HomeTarget) -> Unit,
) {
    var items by remember(slug) { mutableStateOf<List<QobuzDiscoverItem>>(emptyList()) }
    var loading by remember(slug) { mutableStateOf(false) }
    LaunchedEffect(slug) {
        if (slug.isEmpty()) return@LaunchedEffect
        if (items.isNotEmpty()) return@LaunchedEffect
        loading = true
        items = runCatching { fetchHomeQobuzTagPlaylists(slug) }.getOrDefault(emptyList())
        loading = false
    }
    LocalOverlayFrame(onClose = onClose) { requestClose ->
        val gridState = rememberLazyGridState()
        Box(modifier = Modifier.fillMaxSize()) {
            LocalStaticBackdrop(modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                LocalDetailTopBar(
                    title = title,
                    onClose = requestClose,
                )
                Spacer(modifier = Modifier.height(QobuzHeaderContentGap))
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = QobuzSectionPaddingH,
                        end = QobuzSectionPaddingH,
                        top = AppSpacing.sp4,
                        bottom = AppSpacing.listBottomInset,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(QobuzGridColumnGap),
                    verticalArrangement = Arrangement.spacedBy(QobuzGridRowGap),
                ) {
                    items(items, key = { it.key }) { item ->
                        QobuzCardBody(
                            item = item,
                            kind = QobuzDiscoverKind.PlaylistRow,
                            modifier = Modifier.fillMaxWidth(),
                            onOpenTarget = onOpenTarget,
                        )
                    }
                }
            }
        }
    }
}
