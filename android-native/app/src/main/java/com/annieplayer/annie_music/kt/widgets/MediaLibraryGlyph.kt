package com.annieplayer.annie_music.kt.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.annieplayer.annie_music.kt.ui.theme.AppColors

@Composable
fun MediaLibraryGlyph(modifier: Modifier = Modifier, tint: Color = AppColors.white) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * GlyphStrokeRatio

        drawLine(tint, Offset(w * 0.36f, h * 0.12f), Offset(w * 0.64f, h * 0.12f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.20f), Offset(w * 0.70f, h * 0.20f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.24f, h * 0.28f), Offset(w * 0.76f, h * 0.28f), stroke, StrokeCap.Round)

        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.34f),
            size = Size(w * 0.76f, h * 0.54f),
            cornerRadius = CornerRadius(w * 0.15f, w * 0.15f),
            style = Stroke(width = stroke),
        )

        drawEighthNote(tint, w, h, centerX = w * 0.47f, centerY = h * 0.60f, stroke = stroke * 0.88f)
    }
}
