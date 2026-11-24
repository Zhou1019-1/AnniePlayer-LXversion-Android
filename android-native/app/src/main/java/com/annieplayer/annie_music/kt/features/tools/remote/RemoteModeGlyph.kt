package com.annieplayer.annie_music.kt.features.tools.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing

@Composable
fun RemoteModeChip(
    modeId: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) AppColors.whiteA15 else AppColors.whiteA06,
        animationSpec = tween(durationMillis = 90),
        label = "remoteModeChipBg",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "remoteModeChipScale",
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(AppRadius.pill))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = AppSpacing.sp12, vertical = AppSpacing.sp8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteModeGlyph(
            modeId = modeId,
            modifier = Modifier.size(14.dp),
            tint = AppColors.white70,
        )
        Spacer(modifier = Modifier.width(AppSpacing.sp6))
        Text(
            text = label,
            color = AppColors.white70,
            fontSize = AppFontSize.xs,
            maxLines = 1,
        )
    }
}

@Composable
fun RemoteModeGlyph(modeId: String, modifier: Modifier = Modifier, tint: Color = AppColors.white70) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (modeId) {
            "all-rand", "list-rand" -> drawShuffle(tint, w, h, stroke, style)
            "repeat-one" -> {
                drawRepeat(tint, w, h, stroke, style)
                drawLine(
                    tint,
                    Offset(w * 0.50f, h * 0.38f),
                    Offset(w * 0.50f, h * 0.62f),
                    stroke,
                    StrokeCap.Round,
                )
                drawLine(
                    tint,
                    Offset(w * 0.42f, h * 0.45f),
                    Offset(w * 0.50f, h * 0.38f),
                    stroke,
                    StrokeCap.Round,
                )
            }
            "all-seq" -> {
                drawRepeat(tint, w, h, stroke, style)
                drawLine(
                    tint,
                    Offset(w * 0.36f, h * 0.50f),
                    Offset(w * 0.64f, h * 0.50f),
                    stroke,
                    StrokeCap.Round,
                )
            }
            "list-seq" -> {
                drawRepeat(tint, w, h, stroke, style)
            }
            else -> drawRepeat(tint, w, h, stroke, style)
        }
    }
}

private fun DrawScope.drawRepeat(tint: Color, w: Float, h: Float, stroke: Float, style: Stroke) {
    val path = Path().apply {
        moveTo(w * 0.40f, h * 0.26f)
        lineTo(w * 0.70f, h * 0.26f)
        quadraticBezierTo(w * 0.84f, h * 0.26f, w * 0.84f, h * 0.42f)
        lineTo(w * 0.84f, h * 0.58f)
        quadraticBezierTo(w * 0.84f, h * 0.74f, w * 0.68f, h * 0.74f)
        lineTo(w * 0.32f, h * 0.74f)
        quadraticBezierTo(w * 0.16f, h * 0.74f, w * 0.16f, h * 0.58f)
        lineTo(w * 0.16f, h * 0.42f)
        quadraticBezierTo(w * 0.16f, h * 0.26f, w * 0.30f, h * 0.26f)
        lineTo(w * 0.32f, h * 0.26f)
    }
    drawPath(path, tint, style = style)
    drawLine(tint, Offset(w * 0.40f, h * 0.19f), Offset(w * 0.32f, h * 0.26f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.40f, h * 0.33f), Offset(w * 0.32f, h * 0.26f), stroke, StrokeCap.Round)
}

private fun DrawScope.drawShuffle(tint: Color, w: Float, h: Float, stroke: Float, style: Stroke) {
    val top = Path().apply {
        moveTo(w * 0.14f, h * 0.32f)
        cubicTo(w * 0.40f, h * 0.32f, w * 0.58f, h * 0.68f, w * 0.82f, h * 0.68f)
    }
    val bottom = Path().apply {
        moveTo(w * 0.14f, h * 0.68f)
        cubicTo(w * 0.40f, h * 0.68f, w * 0.58f, h * 0.32f, w * 0.82f, h * 0.32f)
    }
    drawPath(top, tint, style = style)
    drawPath(bottom, tint, style = style)
    drawLine(tint, Offset(w * 0.72f, h * 0.60f), Offset(w * 0.84f, h * 0.68f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.72f, h * 0.76f), Offset(w * 0.84f, h * 0.68f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.72f, h * 0.24f), Offset(w * 0.84f, h * 0.32f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.72f, h * 0.40f), Offset(w * 0.84f, h * 0.32f), stroke, StrokeCap.Round)
}
