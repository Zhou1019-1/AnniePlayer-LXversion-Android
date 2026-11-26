package com.annieplayer.annie_music.kt.features.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.annieplayer.annie_music.kt.core.state.AppTab
import com.annieplayer.annie_music.kt.core.state.PlaybackProgress
import com.annieplayer.annie_music.kt.ui.theme.AppBorder
import com.annieplayer.annie_music.kt.ui.theme.AppColors
import com.annieplayer.annie_music.kt.ui.theme.AppDuration
import com.annieplayer.annie_music.kt.ui.theme.AppEasing
import com.annieplayer.annie_music.kt.ui.theme.AppFontSize
import com.annieplayer.annie_music.kt.ui.theme.AppIconSize
import com.annieplayer.annie_music.kt.ui.theme.AppRadius
import com.annieplayer.annie_music.kt.ui.theme.AppSize
import com.annieplayer.annie_music.kt.ui.theme.AppSpacing
import com.annieplayer.annie_music.kt.ui.theme.LocalCoverThemePalette
import com.annieplayer.annie_music.kt.features.tools.ToolsGlyph
import com.annieplayer.annie_music.kt.widgets.BarGlassSpec
import com.annieplayer.annie_music.kt.widgets.LocalLibraryGlyph
import com.annieplayer.annie_music.kt.widgets.MediaLibraryGlyph
import com.annieplayer.annie_music.kt.widgets.MiniProgressBar
import com.annieplayer.annie_music.kt.widgets.MovingText
import com.annieplayer.annie_music.kt.widgets.PlaylistParseGlyph
import com.annieplayer.annie_music.kt.widgets.HomeGlyph
import com.annieplayer.annie_music.kt.widgets.VinylRecord
import com.annieplayer.annie_music.kt.widgets.clickableNoRipple
import com.annieplayer.annie_music.kt.widgets.refractionGlass
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** 底栏左右按钮的横向内边距（10dp：贴边但不顶边，胶囊转角处也不会越界） */
private val BottomBarSidePadding = AppSpacing.sp10

/** 迷你态封面左侧起始位置（与 [BottomBarSidePadding] 对齐，左滑终点） */
private val BottomBarMiniCoverStart = AppSpacing.sp10

/** 迷你态封面与右侧文字之间的间距 */
private val BottomBarMiniGap = AppSpacing.sp12

/** 「正在播放」指示点直径 */
private val PlayingDotSize = 12.dp

/** 「正在播放」指示点外环宽度（取页面底色，把点从封面颜色里「抠」出来） */
private val PlayingDotRing = 2.dp

/** tab 视觉块高度 = 中间封面盒边长的 75%（60dp × 0.75 = 45dp） */
private val BottomBarTabContentHeight = AppSize.bottomBarCoverSize * 0.75f

/** tab 图标边长：整块 45dp 里去掉 10sp 标签与 2dp 间距后剩下的高度（≈30dp） */
private val BottomBarTabIconSize = BottomBarTabContentHeight * 0.66f

/** 同侧两颗 tab 之间的间距 */
private val BottomBarTabGroupGap = AppSpacing.sp6

private val BottomBarMiniTitleStyle = TextStyle(
    color = Color.White,
    fontSize = AppFontSize.size17,
    fontWeight = FontWeight.SemiBold,
)

/**
 * 常驻底栏（Android-only 设计，Flutter 基线无对应实现）。
 *
 * ## 一个胶囊、两种内容
 * 底栏始终是**同一颗胶囊**（同宽同高同位置），只有「内容」在两种状态间变形：
 * - **底栏态**（主页面：主页 / 本地曲库）：左「主页」、中封面盒、右「本地曲库」；
 * - **迷你播放器态**（其余二级页面：播放页之前的抽屉 / 详情 sheet / 详情路由等）：
 *   封面**左滑**到左侧，左右两个 tab **淡出**，标题 / 歌手 / 播放暂停 / 进度条**淡入**。
 *
 * 切换动画由 [morph]（0=底栏态，1=迷你态）统一驱动，反向切换即原路返回：
 * 封面位移、tab 透明度、迷你元素透明度三者共用同一条 260ms 缓动。
 *
 * ## 几何（关键：玻璃铺到屏幕底边，底部不留缝）
 * 玻璃面板高度 = 内容区 [AppSize.bottomBarHeight] + 传入的 [bottomInset]（系统导航 / 手势条
 * 高度）。**必须由调用方传入与父层底部内缩同源的值**（宿主读 `WindowInsets.systemBars`），
 * 否则补偿量与实际内缩不一致，会在胶囊下方留出一条没被玻璃盖住的缝、下层列表透色。
 * 内容层只有 [AppSize.bottomBarHeight] 高并贴顶 → tab 与内容都在手势条之上。
 *
 * ## 结构（同一父 Box 下的**兄弟节点**，没有嵌套命中区，因此不会双触发）
 * ① 玻璃面板（非全宽 [AppSize.bottomBarWidthFraction]、半透明 + 高光渐变 + 细描边 + 柔和投影，
 * 并作为「触摸屏蔽层」消费空白点击）；② 内容层（含封面、迷你元素、两个 tab）。
 *
 * 毛玻璃说明：Compose 无法对节点「背后的内容」做真实模糊，`Modifier.blur` 只会糊掉自身内容，
 * 因此走 **alpha 合成**：半透明底（surfaceLight @0.55 ≈45% 透明）+ 顶部白色高光渐变 + 1dp 高光描边 +
 * 柔和外投影。不依赖 RenderEffect，**API < 31 不退化**，栏内文字 / 图标始终清晰。
 *
 * @param selectedTab 当前 tab（决定左右按钮选中态）。
 * @param onSelectTab 切换 tab（仅底栏态可点）。
 * @param isMiniPlayer true = 迷你播放器态（二级页面），false = 底栏态（主页面）。
 * @param bottomInset 系统底部内缩（必须与父层内缩同源，用于把玻璃铺到屏幕底边）。
 * @param coverUrl 当前歌曲封面（可为空）。
 * @param fallbackCoverUrl 封面兜底（取色器缓存的本地封面路径）。
 * @param title 当前歌曲标题（空串 = 无歌，迷你元素降级为占位文案）。
 * @param artist 当前歌曲歌手。
 * @param isPlaying 是否正在播放（驱动封面上的「在播」小点）。
 * @param progress 播放进度流。**传流不传值**：底栏整体（含 morph 动画、玻璃、跑马灯标题）
 *   不该因为 5 Hz 的进度而重组，只有内部的迷你进度条读它 —— 见 [MiniProgressBarHost]。
 * @param onOpenPlayer 打开全屏播放页（封面 / 迷你文字区均可触发）。
 */
@Composable
fun AppBottomBar(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    isMiniPlayer: Boolean,
    bottomInset: Dp,
    coverUrl: String,
    fallbackCoverUrl: String,
    title: String,
    artist: String,
    isPlaying: Boolean,
    progress: StateFlow<PlaybackProgress>,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    glassCoverUrl: String = "",
) {
    val barHeight = AppSize.bottomBarHeight
    val barShape = RoundedCornerShape(percent = 50)
    val coverSize = AppSize.bottomBarCoverSize
    val panelHeight = barHeight + bottomInset

    val effectiveCover = coverUrl.ifEmpty { fallbackCoverUrl }
    val glassCover = glassCoverUrl.ifEmpty { effectiveCover }
    val hasSong = title.isNotEmpty()

    // 0 = 底栏态；1 = 迷你播放器态。三者（封面位移 / tab 淡出 / 迷你元素淡入）共用这一条进度。
    val morph = animateFloatAsState(
        targetValue = if (isMiniPlayer) 1f else 0f,
        animationSpec = tween(
            durationMillis = AppDuration.bottomBarMorph,
            easing = AppEasing.easeInOutQuad,
        ),
        label = "bottomBarMorph",
    )

    // 玻璃染色：跟随当前封面的主色锚点。
    //
    // 这一行是「透明变高级」的核心替换。原来底栏压的是 `surfaceLight @0.55` ——
    // 一块实色紫板，透出来的是固定紫色，不是背景的色，所以无论背景怎么流动，
    // 底栏永远是那块半透明的塑料。改成极淡的主色染色（8.5%）后，背景的色相
    // 才能透上来，等价于 CSS `backdrop-filter: saturate()` 的近似。
    val ambientTint = LocalCoverThemePalette.current.primary
    val glassTint = animateColorAsState(
        targetValue = ambientTint,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "bottomBarGlassTint",
    )

    Box(
        modifier = modifier
            .fillMaxWidth(AppSize.bottomBarWidthFraction)
            // 整块高度 = 内容区 + 底部内缩：玻璃一直铺到屏幕底边（消除底部缝隙）
            .requiredHeight(panelHeight),
    ) {
        // ── ① 液态玻璃面板（铺满整块，含底部 inset 带） ──
        BottomBarGlass(
            shape = barShape,
            tint = glassTint,
            coverUrl = glassCover,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isMiniPlayer) onOpenPlayer()
        }

        // ── ② 内容层：只有内容区那么高、贴顶 → 内容天然避开手势条 ──
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(barHeight),
        ) {
            val width = maxWidth
            val centerLeft = (width - coverSize) / 2

            // 迷你态文字 / 进度条的横向占用（避开左侧封面；右侧已无播放钮 → 直接贴右内边距）
            val miniTextStart = BottomBarMiniCoverStart + coverSize + BottomBarMiniGap
            val miniTextEnd = BottomBarSidePadding

            // ── ②-a 迷你播放器元素（淡入） ──
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = miniTextStart, end = miniTextEnd)
                    .graphicsLayer { alpha = morph.value }
                    .clickableNoRipple { if (isMiniPlayer) onOpenPlayer() },
                verticalArrangement = Arrangement.Center,
            ) {
                MovingText(
                    text = if (hasSong) title else "暂无播放",
                    threshold = 15,
                    style = BottomBarMiniTitleStyle,
                )
                Spacer(modifier = Modifier.height(AppSpacing.sp4))
                Text(
                    text = if (hasSong) artist else "搜索并播放一首歌",
                    color = AppColors.white38,
                    fontSize = AppFontSize.base,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = miniTextStart, end = miniTextEnd)
                    .graphicsLayer { alpha = morph.value },
            ) {
                MiniProgressBarHost(
                    progress = progress,
                    height = AppSize.bottomBarMiniProgressHeight,
                )
            }

            // ── ②-b 左右 tab（淡出；迷你态禁点，避免透明热区截走点击） ──
            //
            // 横向**贴两端**（不往中间挤）：左端「主页 + 收藏库」，右端「(第 4 个) + 本地」。
            // 右组从右往左排，本地固定在最右一格 —— 这样本地左侧永远留着一格给下一个按钮，
            // 新按钮加进来时不需要动本地、也不会顶到中间封面。
            BottomBarTab(
                label = "主页",
                selected = selectedTab == AppTab.Home,
                enabled = !isMiniPlayer,
                onClick = { onSelectTab(AppTab.Home) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = BottomBarSidePadding)
                    .graphicsLayer { alpha = 1f - morph.value },
                icon = { tint -> HomeGlyph(modifier = Modifier.size(BottomBarTabIconSize), tint = tint) },
            )
            // 主页右侧：收藏媒体库 tab（图标是「书页 + 书身 + 音符」的资料库形，
            // 与右侧本地曲库的「书身 + 音符」刻意区分）
            BottomBarTab(
                label = "收藏库",
                selected = selectedTab == AppTab.Favorites,
                enabled = !isMiniPlayer,
                onClick = { onSelectTab(AppTab.Favorites) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = BottomBarSidePadding + AppSize.bottomBarTabHitSize + BottomBarTabGroupGap,
                    )
                    .graphicsLayer { alpha = 1f - morph.value },
                icon = { tint -> MediaLibraryGlyph(modifier = Modifier.size(BottomBarTabIconSize), tint = tint) },
            )
            BottomBarTab(
                label = "工具",
                selected = selectedTab == AppTab.Tools,
                enabled = !isMiniPlayer,
                onClick = { onSelectTab(AppTab.Tools) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(
                        end = BottomBarSidePadding + AppSize.bottomBarTabHitSize + BottomBarTabGroupGap,
                    )
                    .graphicsLayer { alpha = 1f - morph.value },
                icon = { tint -> ToolsGlyph(modifier = Modifier.size(BottomBarTabIconSize), tint = tint) },
            )
            BottomBarTab(
                label = "本地",
                selected = selectedTab == AppTab.Library,
                enabled = !isMiniPlayer,
                onClick = { onSelectTab(AppTab.Library) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = BottomBarSidePadding)
                    .graphicsLayer { alpha = 1f - morph.value },
                icon = { tint -> LocalLibraryGlyph(modifier = Modifier.size(BottomBarTabIconSize), tint = tint) },
            )

            // ── ②-c 封面（两态共用**同一个**元素，靠位移实现「左滑」，保证视觉连续） ──
            BottomBarCover(
                coverUrl = effectiveCover,
                isPlaying = isPlaying,
                onOpenPlayer = onOpenPlayer,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val t = morph.value
                        val left = centerLeft * (1f - t) + BottomBarMiniCoverStart * t
                        IntOffset(left.roundToPx(), 0)
                    },
            )
        }
    }
}

/**
 * 底栏中键封面盒：底栏态居中、迷你态左滑到左侧（同一元素），点击打开全屏播放页。
 * 无歌 / 封面加载失败时显示自绘黑胶 [VinylRecord]。
 */
@Composable
private fun BottomBarCover(
    coverUrl: String,
    isPlaying: Boolean,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverShape = RoundedCornerShape(AppRadius.r16)
    // 封面加载失败 → 回退黑胶占位；换 URL 后自动复位（key = 封面地址）
    var coverFailed by remember(coverUrl) { mutableStateOf(false) }
    val showVinyl = coverUrl.isEmpty() || coverFailed

    Box(
        modifier = modifier
            .size(AppSize.bottomBarCoverSize)
            .clip(coverShape)
            .background(AppColors.whiteA03)
            .border(AppBorder.thin, AppColors.whiteA15, coverShape)
            .clickableNoRipple(onOpenPlayer),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 无歌 / 封面加载失败：黑胶占位（内缩 4dp，盘体不贴框边）
            showVinyl -> VinylRecord(
                modifier = Modifier.fillMaxSize().padding(AppSpacing.sp4),
            )
            else -> AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 「正在播放」小点：只在有真实封面时出现（黑胶态本就没有在播的歌）
        if (isPlaying && !showVinyl) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = AppSpacing.sp4, bottom = AppSpacing.sp4)
                    .size(PlayingDotSize)
                    .background(AppColors.primary, CircleShape)
                    .border(PlayingDotRing, AppColors.background, CircleShape),
            )
        }
    }
}

/**
 * 底栏液态玻璃面板（移植 LunaBeat `GlobalMiniPlayerBar` 的背景栈）。
 *
 * ## 为什么之前那套不够「高级」
 * 旧配方是「`surfaceLight @0.55` 实色底 + 全高白色渐变 + 1px 白描边 + 单层重投影」。
 * 四样叠加出来的是**一块半透明的塑料板**：`surfaceLight` 是固定紫色，55% 的权重压上去，
 * 透出来的永远是紫色，背景怎么流动都看不见。
 *
 * 后来改成「白 4.2% + 主色染色 8.5% + 椭圆受光 + Fresnel + 颗粒」，透明对了，
 * 但还是「一层膜」——因为它**没有任何内容可以透**。
 *
 * ## LunaBeat 的做法：让封面真的透上来，但压住它
 *
 * 深色 / 有封面分支（smali line 158~177）：
 *
 * | # | 层 | 值 | 出处 |
 * |---|---|---|---|
 * | ① | 封面（盒式模糊 12，宽 40~80px） | alpha **0.86** | jadx 749 / 416 |
 * | ② | 压黑 | Black **0.32** | smali 158 |
 * | ③ | 竖向渐变 | White 0.035 → T → Black 0.07 | smali 161-163 |
 * | ④ | 竖向渐变（取色） | Black 0.18 → 取色 **0.22** → Black 0.24 | smali 173-175 |
 *
 * 封面 86% 乘压黑 68% ≈ 0.585 的可见强度 —— 这就是「不是很透明」的来源。
 * 取色那层和 LunaBeat 页面背景用的是同一个套路：取色永远只出现在中间那个 0.22 的停靠点。
 *
 * ## 加在整块玻璃上的两样东西
 *
 * - **边缘 SDF 折射**（[refractionGlass]）：距边 24dp 以内的内容被向内偏移采样，
 *   像透过玻璃杯壁。这才是「玻璃水杯」的观感来源，和模糊无关。
 * - **投影**：LunaBeat `am4.f` → 半径 24dp、下偏 4dp、黑 12%。
 *
 * @param tint 取色染色来源，跟随当前封面主色锚点（由调用方做 900ms 补间）。
 * @param coverUrl 当前封面；空串时跳过封面层，只留遮罩与渐变。
 */
private const val BarGlassCoverCrossFadeMs = 900

@Composable
private fun BottomBarGlass(
    shape: Shape,
    tint: State<Color>,
    coverUrl: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { context.imageLoader }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var fadingOut by remember { mutableStateOf<ImageBitmap?>(null) }
    val crossFade = remember { Animatable(1f) }

    LaunchedEffect(coverUrl) {
        val next = if (coverUrl.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(coverUrl)
                            .size(BarGlassSpec.BACKDROP_MAX_WIDTH * 2)
                            .allowHardware(false)
                            .build(),
                    ).drawable?.toBitmap()?.let { BarGlassSpec.backdrop(it).asImageBitmap() }
                }.getOrNull()
            }
        }
        val old = shown
        shown = next
        if (old != null && next != null && old !== next) {
            fadingOut = old
            crossFade.snapTo(0f)
            crossFade.animateTo(
                targetValue = 1f,
                animationSpec = tween(BarGlassCoverCrossFadeMs, easing = LinearEasing),
            )
            fadingOut = null
        } else {
            crossFade.snapTo(1f)
        }
    }

    fun DrawScope.drawCover(source: ImageBitmap, w: Float, h: Float, alpha: Float) {
        if (w <= 1f || h <= 1f) return
        val srcW = source.width
        val srcH = source.height
        val dstRatio = w / h
        val srcRatio = srcW.toFloat() / srcH
        val cropW: Int
        val cropH: Int
        if (srcRatio > dstRatio) {
            cropH = srcH
            cropW = min(srcW, (srcH * dstRatio).roundToInt()).coerceAtLeast(1)
        } else {
            cropW = srcW
            cropH = min(srcH, (srcW / dstRatio).roundToInt()).coerceAtLeast(1)
        }
        drawImage(
            image = source,
            srcOffset = IntOffset((srcW - cropW) / 2, (srcH - cropH) / 2),
            srcSize = IntSize(cropW, cropH),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }

    Box(
        modifier = modifier
            // 投影（LunaBeat `am4.f`：radius 24dp / offset (0,4dp) / black 12%）已全局移除：
            // 它是 RenderEffect，每次都渲染屏 + 模糊 + resolve，单层 1–2 ms/帧
            // 玻璃形状裁剪（原 `refractionGlass` 的 AGSL 折射同样已移除，只剩 clip）
            .refractionGlass(
                shape = shape,
                height = BarGlassSpec.REFRACTION_HEIGHT_DP.dp,
                amount = BarGlassSpec.REFRACTION_AMOUNT_DP.dp,
            )
            .drawWithCache {
                val overlayBrush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = BarGlassSpec.OVERLAY_TOP_WHITE),
                        Color.Transparent,
                        Color.Black.copy(alpha = BarGlassSpec.OVERLAY_BOTTOM_BLACK),
                    ),
                )
                val tintBrush = Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = BarGlassSpec.TINT_TOP_BLACK),
                        tint.value.copy(alpha = BarGlassSpec.TINT_MIDDLE),
                        Color.Black.copy(alpha = BarGlassSpec.TINT_BOTTOM_BLACK),
                    ),
                )
                onDrawBehind {
                    val w = size.width
                    val h = size.height

                    fadingOut?.let { drawCover(it, w, h, BarGlassSpec.COVER_ALPHA) }
                    shown?.let {
                        drawCover(it, w, h, BarGlassSpec.COVER_ALPHA * crossFade.value)
                    }

                    drawRect(Color.Black.copy(alpha = BarGlassSpec.SCRIM_ALPHA))
                    drawRect(overlayBrush)
                    drawRect(tintBrush)
                }
            }
            // 迷你态：整条底栏任意位置都进播放页（元素之间的空白也是）；
            // 底栏态仍是空实现，只屏蔽穿透到下层列表。
            .clickableNoRipple(onClick),
    )
}

/**
 * 底栏 tab 按钮：命中区撑满胶囊内容高度（外层，唯一 clickable）+ 居中的视觉块（内层）。
 *
 * 视觉块高度由 [BottomBarTabContentHeight] 定死 = 封面边长 75%（45dp），里面图标 [BottomBarTabIconSize]
 * 在上、10sp 标签在下，整块在 60dp 高的胶囊里垂直居中 —— 所以「按钮在胶囊中间」是几何保证，
 * 不靠 padding 凑。选中态用「主色 15% 胶囊 + 纯白图标」，未选中「透明 + whiteA45」。
 *
 * [enabled] 为 false 时（迷你播放器态）**完全不挂 clickable**（不是挂空实现）——
 * 否则淡出后仍是透明热区，会截走「点整条底栏进播放页」的点击。
 */
@Composable
private fun BottomBarTab(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (selected) AppColors.white else AppColors.whiteA45
    Box(
        modifier = modifier
            .width(AppSize.bottomBarTabHitSize)
            .height(AppSize.bottomBarHeight)
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = BottomBarTabContentHeight)
                .background(
                    color = if (selected) AppColors.primaryA15 else Color.Transparent,
                    shape = RoundedCornerShape(AppRadius.r10),
                )
                .padding(horizontal = AppSpacing.sp6)
                .padding(vertical = AppSpacing.sp2),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                icon(tint)
                Spacer(modifier = Modifier.height(AppSpacing.sp2))
                Text(
                    text = label,
                    color = tint,
                    fontSize = AppFontSize.size10,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 迷你进度条的**唯一**订阅者。
 *
 * 为什么要多包这一层：`AppBottomBar` 是个五百行、带 morph 动画 / 液态玻璃 / 跑马灯标题的
 * 重组件，而进度是 5 Hz 变的。若在 `AppBottomBar` 函数体内 `collectAsState()`，
 * 整块底栏每秒重组 5 次（含 `BoxWithConstraints` 的测量与 `MovingText` 的文本测量）。
 * 让这个「除了订阅什么都不做」的小函数去读，重组范围就只剩一条 4dp 高的进度线。
 *
 * 纪律：**别把 `collectAsState()` 挪回 `AppBottomBar` 体内**，也别往这里加别的东西。
 */
@Composable
private fun MiniProgressBarHost(
    progress: StateFlow<PlaybackProgress>,
    height: Dp,
) {
    val p by progress.collectAsState()
    MiniProgressBar(position = p.currentTime, duration = p.duration, height = height)
}
