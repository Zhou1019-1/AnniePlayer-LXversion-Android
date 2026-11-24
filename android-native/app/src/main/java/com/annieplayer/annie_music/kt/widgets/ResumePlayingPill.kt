package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppFontWeight
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.ColorPalette
import kotlinx.coroutines.flow.distinctUntilChanged

private val ResumePillHeight = 40.dp

@Composable
fun rememberPlayingRowVisible(listState: LazyListState, index: Int?): Boolean {
    if (index == null || index < 0) return true
    var visible by remember(listState, index) { mutableStateOf(true) }
    LaunchedEffect(listState, index) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.any { item ->
                item.index == index &&
                    item.offset + item.size > info.viewportStartOffset &&
                    item.offset < info.viewportEndOffset
            }
        }
            .distinctUntilChanged()
            .collect { visible = it }
    }
    return visible
}

@Composable
fun ResumePlayingPill(
    visible: Boolean,
    palette: ColorPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "正在播放",
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 2 },
    ) {
        val shape = RoundedCornerShape(AppRadius.pill)
        Row(
            modifier = Modifier
                .height(ResumePillHeight)
                .glassPanel(shape = shape, tint = AppColors.surfaceLight.copy(alpha = 0.5f))
                .clickableRipple(onClick)
                .padding(horizontal = AppSpacing.sp16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            PlayIcon(modifier = Modifier.size(AppIconSize.icon16), tint = AppColors.white)
            Spacer(modifier = Modifier.width(AppSpacing.sp8))
            Text(
                text = label,
                color = AppColors.white,
                fontSize = AppFontSize.size13,
                fontWeight = AppFontWeight.semiBold,
                maxLines = 1,
            )
        }
    }
}
