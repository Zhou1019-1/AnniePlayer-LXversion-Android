package com.annieplayer.annie_music.kt.ui.widgets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annieplayer.annie_music.kt.core.audio.ExclusiveVolume
import com.annieplayer.annie_music.kt.core.audio.SinkKind
import com.annieplayer.annie_music.kt.core.audio.effectiveBits
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 独占通路的音量条（按音量键才出现，4 秒后淡出）。
 *
 * 形状是一根**竖管**：填充段 = 当前衰减（白 80%），未填充部分是同一根管子的底（白 11%），
 * 管壁上按 1/24 留刻痕，所以"点亮到第几段"仍然一眼可读 —— 连续高度给手感，刻痕给余量。
 *
 * 位置：底端贴屏幕垂直中线，横向仍在右侧 14dp（与旧卡片同一列）。管子高度随屏幕高度收，
 * 保证顶端不会被状态栏压住。
 *
 * 配色**只用白**：金/蓝/灰那套编码（硬件衰减 / 我方衰减 / 锁死）改由文字承担 ——
 * 底部那行通路（DAC / USB / AA / DSD）说明衰减发生在哪，`≈N bit` 只在**我方**数字衰减时
 * 出现（硬件音量不丢位深，本来就不写）。
 */
@Composable
fun BoxScope.ExclusiveVolumeBar() {
    val state by ExclusiveVolume.state.collectAsState()
    var visible by remember { mutableStateOf(false) }
    // tick 每次按键自增 → 这里重启计时，连续按键不闪断
    LaunchedEffect(state.tick) {
        if (state.tick == 0L) return@LaunchedEffect
        visible = true
        delay(4000)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInHorizontally(tween(160)) { it / 8 },
        exit = fadeOut(tween(260)) + slideOutHorizontally(tween(260)) { it / 8 },
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
            val centerY = maxHeight / 2f
            val tubeHeight = minOf(TubeMaxHeight, centerY - statusTop - 12.dp)
                .coerceAtLeast(TubeMinHeight)
            val tubeTop = centerY - tubeHeight

            VolumeTube(
                state = state,
                tubeHeight = tubeHeight,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = TubeEndPadding, top = tubeTop),
            )

            // 管底方牌：四个字占两行，纯标注、不接交互
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = TubeEndPadding, top = centerY + TubeEndPadding)
                    .size(TubeWidth)
                    .clip(RoundedCornerShape(14.dp))
                    .background(TubeFill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "独占\n音量",
                    color = FillInk,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val TubeWidth = 44.dp

/** 管子最大长度。底端钉在屏幕中线，所以这就是「往上长多高」。 */
private val TubeMaxHeight = 256.dp
private val TubeMinHeight = 160.dp
private val TubeEndPadding = 14.dp

private val TubeFill = Color.White.copy(alpha = 0.80f)
private val TubeTrack = Color.White.copy(alpha = 0.50f)
private val TickInk = Color.Black.copy(alpha = 0.12f)
/** 落在白色填充上的墨色（通路 / 读数）。 */
private val FillInk = Color(0xFF17171A)
/** 落在未填充管壁上的同一簇元素：改用白 + 阴影，磨砂白底上才读得出来。 */
private val TrackInk = Color.White.copy(alpha = 0.92f)

/** 未填充段的羽化半径：让管子空的那截像磨砂玻璃，边界不发硬（API < 31 时为空操作）。 */
private val UnfilledBlur = 6.dp

/** 刻度数：与旧卡片一致，1 段 = 1/24 的 [ExclusiveVolume.MIN_DB]..[ExclusiveVolume.MAX_DB]。 */
private const val SEGMENTS = 24

@Composable
private fun VolumeTube(state: ExclusiveVolume.State, tubeHeight: Dp, modifier: Modifier) {
    val fraction = ((state.effectiveDb - ExclusiveVolume.MIN_DB) /
        (ExclusiveVolume.MAX_DB - ExclusiveVolume.MIN_DB)).coerceIn(0f, 1f)
    val fill = if (state.locked) 0f else fraction
    val pill = RoundedCornerShape(TubeWidth / 2)
    Box(modifier = modifier.width(TubeWidth).height(tubeHeight)) {
        // 未填充段：白 50% + 模糊。刻意**不放进 clip**，让模糊把边缘朝外羽化 ——
        // 看起来是磨砂玻璃而不是一圈硬边。API < 31 时 `blur` 是空操作，退化成实心管。
        Box(
            modifier = Modifier
                .matchParentSize()
                .blur(UnfilledBlur)
                .background(TubeTrack, pill),
        )
        // 填充与管内文字：裁成胶囊，边界保持锐利
        Box(modifier = Modifier.matchParentSize().clip(pill)) {
            if (fill > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(fill)
                        .background(TubeFill),
                )
            }
            // 刻痕只画在填充段里：先按整根管子的 1/24 算出绝对位置，再换算到画布坐标
            Canvas(modifier = Modifier.fillMaxSize()) {
                val full = size.height
                val filledPx = full * fill
                if (filledPx <= 0f) return@Canvas
                for (i in 1 until SEGMENTS) {
                    val y = full - full * i / SEGMENTS
                    if (y < full - filledPx) break
                    val major = i % 6 == 0
                    val inset = if (major) 6.dp.toPx() else 11.dp.toPx()
                    drawLine(
                        color = TickInk,
                        start = Offset(inset, y),
                        end = Offset(size.width - inset, y),
                        strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
                    )
                }
            }
            val onFill = tubeHeight * fill >= InkSwitchHeight
            val ink = if (onFill) FillInk else TrackInk
            val inkStyle = if (onFill) {
                TextStyle.Default
            } else {
                TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.40f), blurRadius = 5f))
            }
            // 管底那簇（自上而下）：通路 → 位深代价 → dB 读数。
            // dB 占据原来耳机图标的位置，单位另起一行（44dp 宽里 "−60 dB" 一行装不下）。
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = routeLabel(state),
                    color = ink.copy(alpha = 0.90f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    style = inkStyle,
                    maxLines = 1,
                )
                // 软件衰减的代价：每 −6 dB 约丢 1 bit。硬件音量不损失位深，所以不写这一行。
                if (state.hardware == null && !state.locked && state.db < -0.5f) {
                    Text(
                        text = "≈${effectiveBits(24, state.db)} bit",
                        color = ink.copy(alpha = 0.55f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        style = inkStyle,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${state.effectiveDb.roundToInt()}",
                    color = ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    style = inkStyle,
                    maxLines = 1,
                )
                Text(
                    text = "dB",
                    color = ink.copy(alpha = 0.60f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    style = inkStyle,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 填充高度超过这个值，管底那簇已经完全坐在白色上，改深色墨。
 *
 * 必须**贴着**那簇的实际高度（下内距 10 + 读数 19sp+9sp ≈ 35 + 间距 4 + 两行 9sp ≈ 24 ≈ 73dp），
 * 取大了中间会出现一段「已经全被白填住、却还是白墨」的隐形区。
 */
private val InkSwitchHeight = 73.dp

private fun routeLabel(state: ExclusiveVolume.State): String = when {
    state.hardware != null -> "DAC"
    state.locked -> "DSD"
    state.routeKey == SinkKind.usbUac.key -> "USB"
    state.routeKey == SinkKind.oboe.key -> "AA"
    else -> "—"
}
