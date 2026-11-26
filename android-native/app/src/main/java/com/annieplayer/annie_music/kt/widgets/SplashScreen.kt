package com.annieplayer.annie_music.kt.widgets

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlinx.coroutines.delay

private val SplashBackground = Color(0xFF16181D)
private val SplashLogoFrom = Color(0xFFFA2D55)
private val SplashLogoTo = Color(0xFFFF5C7A)
private val SplashNameColor = Color(0xFFF5F5F7)
private val SplashStatusColor = Color(0x73F5F5F7)
private val SplashGlow = Color(0x59FA2D55)

private val SplashEnterEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private const val SplashEnterMs = 700
private const val SplashTextMs = 600
private const val SplashNameDelayMs = 220
private const val SplashStatusDelayMs = 380
private const val SplashBarMs = 1000
private const val SplashBarCount = 5
private const val SplashStageMs = 700
private const val SplashMinShowMs = 1100
private const val SplashGiveUpMs = 8000
private const val SplashPollMs = 100
private const val SplashFadeMs = 500

private const val SplashStatusIgniting = "引擎点火中…"
private const val SplashStatusLoading = "曲库装载中…"
private const val SplashTitle = "安妮播放器"

@Composable
fun SplashScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    ready: Boolean = true,
    onPrepare: (() -> Unit)? = null,
) {
    var entering by remember { mutableStateOf(false) }
    var loadingStage by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }
    var minShown by remember { mutableStateOf(false) }
    val readyNow by rememberUpdatedState(ready)

    LaunchedEffect(Unit) {
        entering = true
        delay(SplashStageMs.toLong())
        loadingStage = true
        onPrepare?.invoke()
        delay((SplashMinShowMs - SplashStageMs).toLong())
        minShown = true
    }

    LaunchedEffect(Unit) {
        var waited = 0
        while (waited < SplashGiveUpMs) {
            if (minShown && readyNow) break
            delay(SplashPollMs.toLong())
            waited += SplashPollMs
        }
        fading = true
        delay(SplashFadeMs.toLong())
        onFinish()
    }

    val fade by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(SplashFadeMs, easing = LinearEasing),
        label = "splashFade",
    )
    val logoIn by animateFloatAsState(
        targetValue = if (entering) 1f else 0f,
        animationSpec = tween(SplashEnterMs, easing = SplashEnterEase),
        label = "splashLogoIn",
    )
    val nameIn by animateFloatAsState(
        targetValue = if (entering) 1f else 0f,
        animationSpec = tween(SplashTextMs, delayMillis = SplashNameDelayMs, easing = SplashEnterEase),
        label = "splashNameIn",
    )
    val statusIn by animateFloatAsState(
        targetValue = if (entering) 1f else 0f,
        animationSpec = tween(SplashTextMs, delayMillis = SplashStatusDelayMs, easing = SplashEnterEase),
        label = "splashStatusIn",
    )

    val beats = rememberInfiniteTransition(label = "splashBars")
    val barPhase by beats.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SplashBarMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "splashBarPhase",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade }
            .background(SplashBackground),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = logoIn
                        val grow = 0.78f + 0.22f * logoIn
                        scaleX = grow
                        scaleY = grow
                    }
                    .shadow(
                        elevation = 22.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = SplashGlow,
                        spotColor = SplashGlow,
                    )
                    .background(
                        brush = Brush.linearGradient(listOf(SplashLogoFrom, SplashLogoTo)),
                        shape = RoundedCornerShape(22.dp),
                    )
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                repeat(SplashBarCount) { index ->
                    val phase = ((barPhase - index * 0.12f) + 1f) % 1f
                    val barScale = 0.65f - 0.35f * cos(2f * Math.PI.toFloat() * phase)
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .height(34.dp)
                            .graphicsLayer {
                                scaleY = barScale
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .background(Color.White, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Text(
                text = SplashTitle,
                color = SplashNameColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 7.sp,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .graphicsLayer {
                        alpha = nameIn
                        translationY = (1f - nameIn) * 10.dp.toPx()
                    },
            )
            Text(
                text = if (loadingStage) SplashStatusLoading else SplashStatusIgniting,
                color = SplashStatusColor,
                fontSize = 12.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = statusIn
                    translationY = (1f - statusIn) * 10.dp.toPx()
                },
            )
        }
    }
}
