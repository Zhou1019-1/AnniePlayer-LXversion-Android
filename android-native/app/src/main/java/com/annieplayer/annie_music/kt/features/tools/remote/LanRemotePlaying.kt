package com.annieplayer.annie_music.kt.features.tools.remote

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import com.annieplayer.annie_music.kt.core.remote.LanRemoteViewModel
import com.annieplayer.annie_music.kt.core.remote.RemoteLyric
import com.annieplayer.annie_music.kt.core.remote.RemoteLyricLine
import com.annieplayer.annie_music.kt.core.remote.playModeLabel
import com.annieplayer.annie_music.kt.features.tools.RemotePcGlyph
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val RemoteSleeveSide = 112.dp
private val RemoteSleeveRadius = 16.dp
private val RemoteLyricRow = 40.dp
private val RemoteLyricFade = 22.dp
private val RemoteRailTouch = 26.dp
private val RemoteRailThickness = 2.dp
private val RemoteTransportHeight = 64.dp
private val RemotePlayGlyphSize = 42.dp
private val RemoteSkipGlyphSize = 26.dp

@Composable
fun LanRemotePlaying(
    remoteViewModel: LanRemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val now by remoteViewModel.now.collectAsState()
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (now.playing) "正在播放" else "已暂停",
                color = AppColors.white38,
                fontSize = AppFontSize.size10,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = "安妮遥控",
                color = AppColors.white24,
                fontSize = AppFontSize.size10,
                letterSpacing = 0.6.sp,
                maxLines = 1,
            )
        }
        RemoteSleeve(
            title = now.title,
            artist = now.artist,
            album = now.album,
            cover = now.cover,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sp24, vertical = AppSpacing.sp12),
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp8))
        RemoteLyricViewport(
            lyric = now.lyric,
            hasTitle = now.title.isNotEmpty(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.sp24),
        )
        Spacer(modifier = Modifier.height(AppSpacing.sp10))
        Column(modifier = Modifier.fillMaxWidth()) {
            RemoteRail(
                position = now.position,
                duration = now.duration,
                playing = now.playing,
                onSeek = remoteViewModel::seekTo,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp24),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatRemoteTime(now.position),
                    color = AppColors.white38,
                    fontSize = AppFontSize.size10,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    text = "-" + formatRemoteTime((now.duration - now.position).coerceAtLeast(0.0)),
                    color = AppColors.white38,
                    fontSize = AppFontSize.size10,
                    letterSpacing = 0.4.sp,
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RemoteTransportHeight)
                    .padding(horizontal = AppSpacing.sp16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                RemoteIconButton(size = 56.dp, onClick = remoteViewModel::previous) {
                    RemoteSkipGlyph(
                        forward = false,
                        tint = AppColors.white,
                        modifier = Modifier.size(RemoteSkipGlyphSize),
                    )
                }
                RemoteIconButton(size = RemoteTransportHeight, onClick = remoteViewModel::togglePlay) {
                    RemotePlayPauseGlyph(
                        playing = now.playing,
                        tint = AppColors.white,
                        modifier = Modifier.size(RemotePlayGlyphSize),
                    )
                }
                RemoteIconButton(size = 56.dp, onClick = remoteViewModel::next) {
                    RemoteSkipGlyph(
                        forward = true,
                        tint = AppColors.white,
                        modifier = Modifier.size(RemoteSkipGlyphSize),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sp24),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val mode = now.mode
                val modeId = mode?.id.orEmpty()
                RemoteModeChip(
                    modeId = modeId,
                    label = playModeLabel(modeId, mode?.label.orEmpty()),
                    onClick = remoteViewModel::cycleMode,
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp16))
                RemoteVolumeBar(
                    volume = now.volume,
                    onVolume = remoteViewModel::setVolume,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(AppSpacing.sp12))
                Text(
                    text = "${(now.volume.coerceIn(0.0, 1.0) * 100).toInt()}",
                    color = AppColors.white38,
                    fontSize = AppFontSize.xs,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.height(AppSpacing.sp12))
    }
}

@Composable
private fun RemoteSleeve(
    title: String,
    artist: String,
    album: String,
    cover: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        RemoteCoverSquare(cover = cover, modifier = Modifier.size(RemoteSleeveSide))
        Spacer(modifier = Modifier.width(AppSpacing.sp16))
        Column(modifier = Modifier.weight(1f)) {
            if (album.isNotEmpty()) {
                Text(
                    text = album.uppercase(),
                    color = AppColors.white38,
                    fontSize = AppFontSize.size10,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp6))
            }
            Text(
                text = title.ifEmpty { "暂无播放" },
                color = AppColors.white,
                fontSize = AppFontSize.size17,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(AppSpacing.sp4))
            Text(
                text = artist,
                color = AppColors.white54,
                fontSize = AppFontSize.sm,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RemoteCoverSquare(cover: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RemoteSleeveRadius)
    var bitmap by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cover) {
        bitmap = if (cover.startsWith("data:")) {
            withContext(Dispatchers.Default) { decodeDataUrl(cover) }
        } else {
            null
        }
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(AppColors.whiteA04),
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        when {
            loaded != null -> Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            cover.startsWith("http") -> AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> RemotePcGlyph(modifier = Modifier.size(36.dp), tint = AppColors.white12)
        }
    }
}

private fun decodeDataUrl(raw: String): ImageBitmap? {
    val marker = ";base64,"
    val index = raw.indexOf(marker)
    if (index < 0) return null
    val payload = raw.substring(index + marker.length)
    val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
    return bitmap.asImageBitmap()
}

@Composable
private fun RemoteLyricViewport(
    lyric: RemoteLyric,
    hasTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    val lines = lyric.lines
    val current = lyric.current
    if (lines.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Text(
                text = if (hasTitle) "纯音乐 / 暂无歌词" else " ",
                color = AppColors.white24,
                fontSize = AppFontSize.sm,
                modifier = Modifier.padding(start = AppSpacing.sp2),
            )
        }
        return
    }

    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val heights = remember { mutableMapOf<Int, Float>() }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var needsInitialJump by remember { mutableStateOf(true) }
    val fallbackPx = with(density) { RemoteLyricRow.toPx() }

    fun sumBefore(index: Int): Float {
        var sum = 0f
        for (k in 0 until index) sum += heights[k] ?: fallbackPx
        return sum
    }

    fun currentScrollPx(): Float {
        var sum = 0f
        for (k in 0 until listState.firstVisibleItemIndex) sum += heights[k] ?: fallbackPx
        return sum + listState.firstVisibleItemScrollOffset
    }

    LaunchedEffect(current, viewportPx) {
        if (current < 0 || viewportPx <= 0f || current >= lines.size) return@LaunchedEffect
        val lineHeight = heights[current] ?: fallbackPx
        if (needsInitialJump) {
            needsInitialJump = false
            listState.scrollToItem(current, (lineHeight / 2f - viewportPx / 2f).toInt())
            return@LaunchedEffect
        }
        val target = sumBefore(current) + lineHeight / 2f - viewportPx / 2f
        val delta = target - currentScrollPx()
        if (kotlin.math.abs(delta) < 2f) return@LaunchedEffect
        listState.animateScrollBy(
            value = delta,
            animationSpec = tween(
                durationMillis = AppDuration.lyricScroll,
                easing = AppEasing.easeInOut,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewportPx = it.height.toFloat() }
            .clipToBounds(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items = lines) { index, line ->
                RemoteLyricRowView(
                    line = line,
                    active = index == current,
                    onHeight = { measured ->
                        val old = heights[index]
                        if (old == null || kotlin.math.abs(old - measured) > 0.5f) {
                            heights[index] = measured
                        }
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(RemoteLyricFade)
                .background(
                    Brush.verticalGradient(
                        listOf(AppColors.background, Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(RemoteLyricFade)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, AppColors.background),
                    ),
                ),
        )
    }
}

@Composable
private fun RemoteLyricRowView(
    line: RemoteLyricLine,
    active: Boolean,
    onHeight: (Float) -> Unit,
) {
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = AppDuration.lyricLineFade, easing = AppEasing.easeInOut),
        label = "remoteLyricEmphasis",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onHeight(it.size.height.toFloat()) }
            .padding(start = AppSpacing.sp2, top = AppSpacing.sp6, bottom = AppSpacing.sp6),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = line.text.ifEmpty { " " },
            color = lerpColor(AppColors.white24, AppColors.white, emphasis),
            fontSize = lerp(15f, 20f, emphasis).sp,
            lineHeight = lerp(20f, 26f, emphasis).sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Start,
            maxLines = if (active) 4 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (line.translation.isNotEmpty()) {
            Text(
                text = line.translation,
                color = lerpColor(AppColors.white24, AppColors.white54, emphasis),
                fontSize = lerp(10f, 11f, emphasis).sp,
                lineHeight = lerp(13f, 15f, emphasis).sp,
                textAlign = TextAlign.Start,
                maxLines = if (active) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = AppSpacing.sp2),
            )
        }
    }
}

@Composable
private fun RemoteRail(
    position: Double,
    duration: Double,
    playing: Boolean,
    onSeek: (Double) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }
    var pendingSeconds by remember { mutableFloatStateOf(-1f) }
    var pendingUntil by remember { mutableLongStateOf(0L) }
    val clock = remember { mutableFloatStateOf((position * 1000).toFloat()) }
    val density = LocalDensity.current
    val glowPaints = remember(density) { remoteGlowPaints(density) }

    LaunchedEffect(position, playing, duration) {
        clock.floatValue = (position * 1000).toFloat()
        if (pendingSeconds >= 0f && kotlin.math.abs(position - pendingSeconds.toDouble()) < 0.8) {
            pendingSeconds = -1f
        }
        if (!playing || duration <= 0.0) return@LaunchedEffect
        val anchor = System.nanoTime()
        val startMs = clock.floatValue
        val endMs = (duration * 1000).toFloat()
        while (true) {
            withFrameNanos { now ->
                clock.floatValue = (startMs + (now - anchor) / 1_000_000f).coerceAtMost(endMs)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RemoteRailTouch)
            .padding(horizontal = AppSpacing.sp24)
            .pointerInput(duration) {
                if (duration <= 0.0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var ratio = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    dragging = true
                    dragRatio = ratio
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        if (moved != ratio) {
                            ratio = moved
                            dragRatio = moved
                        }
                        change.consume()
                    }
                    val target = ratio * duration
                    pendingSeconds = target.toFloat()
                    pendingUntil = android.os.SystemClock.elapsedRealtime() + 2500L
                    dragging = false
                    onSeek(target)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(RemoteRailThickness)) {
            val seconds = when {
                dragging -> (dragRatio * duration).toDouble()
                pendingSeconds >= 0f && android.os.SystemClock.elapsedRealtime() < pendingUntil ->
                    pendingSeconds.toDouble()
                else -> clock.floatValue / 1000.0
            }
            val ratio = if (duration > 0.0) (seconds / duration).toFloat().coerceIn(0f, 1f) else 0f
            val corner = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = AppColors.white12, cornerRadius = corner)
            if (playing && !dragging && ratio > 0f) {
                drawIntoCanvas { canvas ->
                    val r = size.height / 2f
                    for (p in glowPaints) {
                        canvas.nativeCanvas.drawRoundRect(
                            0f,
                            0f,
                            size.width * ratio,
                            size.height,
                            r,
                            r,
                            p,
                        )
                    }
                }
            }
            drawRoundRect(
                color = AppColors.white,
                size = Size(size.width * ratio, size.height),
                cornerRadius = corner,
            )
        }
    }
}

private fun remoteGlowPaints(density: androidx.compose.ui.unit.Density): List<android.graphics.Paint> {
    fun paint(blurDp: Float, alpha: Int): android.graphics.Paint =
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.WHITE
            this.alpha = alpha
            maskFilter = android.graphics.BlurMaskFilter(
                with(density) { blurDp.dp.toPx() },
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        }
    return listOf(paint(4f, 190), paint(12f, 96))
}

@Composable
private fun RemoteVolumeBar(volume: Double, onVolume: (Double) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }
    val ratio = if (dragging) dragRatio else volume.toFloat().coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(RemoteRailTouch)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onVolume((offset.x / size.width.toFloat()).coerceIn(0f, 1f).toDouble())
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        if (dragging) onVolume(dragRatio.toDouble())
                        dragging = false
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        if (dragging) {
                            dragRatio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            drawRect(color = AppColors.white12)
            drawRect(color = AppColors.whiteA40, size = Size(size.width * ratio, size.height))
        }
    }
}

@Composable
private fun RemoteIconButton(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "remoteIconAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "remoteIconScale",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun RemotePlayPauseGlyph(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val bar = Size(w * 0.18f, h * 0.66f)
            val corner = CornerRadius(w * 0.05f, w * 0.05f)
            drawRoundRect(tint, Offset(w * 0.25f, h * 0.17f), bar, corner)
            drawRoundRect(tint, Offset(w * 0.57f, h * 0.17f), bar, corner)
        } else {
            val path = Path().apply {
                moveTo(w * 0.32f, h * 0.14f)
                lineTo(w * 0.82f, h * 0.50f)
                lineTo(w * 0.32f, h * 0.86f)
                close()
            }
            drawPath(path, tint)
        }
    }
}

@Composable
private fun RemoteSkipGlyph(forward: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (forward) {
                moveTo(w * 0.22f, h * 0.16f)
                lineTo(w * 0.70f, h * 0.50f)
                lineTo(w * 0.22f, h * 0.84f)
            } else {
                moveTo(w * 0.78f, h * 0.16f)
                lineTo(w * 0.30f, h * 0.50f)
                lineTo(w * 0.78f, h * 0.84f)
            }
            close()
        }
        drawPath(path, tint)
        val barX = if (forward) w * 0.78f else w * 0.16f
        drawRoundRect(
            color = tint,
            topLeft = Offset(barX, h * 0.16f),
            size = Size(w * 0.06f, h * 0.68f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
        )
    }
}

fun formatRemoteTime(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0:00"
    val total = seconds.toInt()
    val minutes = total / 60
    val rest = total % 60
    return "$minutes:" + rest.toString().padStart(2, '0')
}
