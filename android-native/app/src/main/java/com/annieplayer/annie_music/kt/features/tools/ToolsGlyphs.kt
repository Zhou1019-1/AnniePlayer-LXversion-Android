package com.annieplayer.annie_music.kt.features.tools

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun ToolsGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val cell = w * 0.34f
        val gap = w * 0.12f
        val start = w * 0.10f
        val corner = CornerRadius(w * 0.09f, w * 0.09f)
        val originY = h * 0.10f
        val columns = listOf(start, start + cell + gap)
        val rows = listOf(originY, originY + cell + gap)
        for (x in columns) {
            for (y in rows) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(x, y),
                    size = Size(cell, cell),
                    cornerRadius = corner,
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

@Composable
fun RemotePcGlyph(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.24f),
            size = Size(w * 0.52f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = stroke),
        )
        drawLine(
            tint,
            Offset(w * 0.34f, h * 0.68f),
            Offset(w * 0.34f, h * 0.82f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(w * 0.20f, h * 0.82f),
            Offset(w * 0.48f, h * 0.82f),
            stroke,
            StrokeCap.Round,
        )
        val cx = w * 0.60f
        val cy = h * 0.46f
        for (radius in listOf(0.14f, 0.28f)) {
            drawArc(
                color = tint,
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(cx - w * radius, cy - w * radius),
                size = Size(w * radius * 2f, w * radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
