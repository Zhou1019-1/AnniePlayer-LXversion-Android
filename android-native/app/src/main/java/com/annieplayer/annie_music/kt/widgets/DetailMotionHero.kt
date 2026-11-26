package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.widgets.clickableRipple

@Composable
internal fun DetailMotionHeroVideo(
    cover: String,
    tallCover: String,
    motionCover: String,
    placeholderIconSize: Dp = AppIconSize.icon48,
) {
    var coverFailed by remember(cover) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Black,
                        0.65f to Color.Black,
                        0.92f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            cover.isEmpty() || coverFailed -> MusicNoteGlyph(
                modifier = Modifier.size(placeholderIconSize),
                tint = AppColors.textDisabled,
            )

            motionCover.isNotEmpty() -> PlaylistMotionCover(
                videoUrl = motionCover,
                previewUrl = tallCover.ifEmpty { cover },
                modifier = Modifier.fillMaxSize(),
            )

            else -> AsyncImage(
                model = tallCover.ifEmpty { cover },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun DetailMotionHeroForeground(
    aspectRatio: Float,
    title: String,
    subtitle: String,
    meta: String,
    titleMaxLines: Int = 2,
    onSubtitleClick: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.42f to Color.Transparent,
                        0.70f to Color.Black.copy(alpha = 0.34f),
                        0.90f to Color.Black.copy(alpha = 0.28f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = AppColors.textPrimary,
                fontSize = AppFontSize.size26,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
                Text(
                    text = subtitle,
                    modifier = if (onSubtitleClick != null) {
                        Modifier.clickableRipple(onSubtitleClick)
                    } else {
                        Modifier
                    },
                    color = AppColors.whiteA50,
                    fontSize = AppFontSize.size15,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = meta,
                    color = AppColors.whiteA40,
                    fontSize = AppFontSize.size13,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

