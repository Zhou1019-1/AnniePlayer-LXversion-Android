package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

@Composable
internal fun MotionHeroScaffold(
    aspectRatio: Float,
    barBottomPx: Int,
    listState: LazyListState,
    bottomInset: Dp,
    video: @Composable () -> Unit,
    foreground: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    var heroHeightPx by remember { mutableStateOf(0) }
    val heroOffset by remember(listState, heroHeightPx) {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (first == null) 0 else (-first.offset).coerceIn(0, heroHeightPx)
        }
    }
    val alpha = heroCoverFade(heroOffset, heroHeightPx)
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .offset { IntOffset(0, -heroOffset) }
                .graphicsLayer { this.alpha = alpha },
        ) {
            video()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(
                        left = 0f,
                        top = barBottomPx.toFloat(),
                        right = size.width,
                        bottom = size.height,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().shortScrollbar(listState),
                contentPadding = PaddingValues(
                    top = with(density) { heroHeightPx.toDp() },
                    bottom = bottomInset,
                ),
                content = content,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, -heroOffset) }
                .graphicsLayer { this.alpha = alpha }
                .onSizeChanged { heroHeightPx = it.height },
        ) {
            foreground()
        }
    }
}
