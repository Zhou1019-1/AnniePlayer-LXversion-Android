package com.annieplayer.annie_music.kt.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.annieplayer.annie_music.kt.core.state.PageTint
import com.annieplayer.annie_music.kt.core.state.UiViewModel
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette

@Composable
fun PageTintEffect(
    uiViewModel: UiViewModel,
    palette: ColorPalette,
    coverUrl: String? = null,
) {
    val tint = if (palette.coverGridDim > 0) {
        PageTint(primary = palette.primary, coverUrl = coverUrl?.takeIf { it.isNotEmpty() })
    } else {
        null
    }
    SideEffect { uiViewModel.setPageTint(tint) }
    val overlayOpen by uiViewModel.state.collectAsState()
    LaunchedEffect(tint, overlayOpen.playerOverlayVisible) {
        if (!overlayOpen.playerOverlayVisible) uiViewModel.setPageTint(tint)
    }
    DisposableEffect(Unit) {
        onDispose { uiViewModel.setPageTint(null) }
    }
}
