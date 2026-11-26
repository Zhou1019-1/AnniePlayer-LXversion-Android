package com.annieplayer.annie_music.kt.widgets

internal const val HeroCoverFadeSpanRatio = 0.8f

internal fun heroCoverFade(scrolledPx: Int, heroPx: Int): Float {
    if (heroPx <= 0) return 1f
    val span = (heroPx * HeroCoverFadeSpanRatio).coerceAtLeast(1f)
    val t = (scrolledPx.toFloat() / span).coerceIn(0f, 1f)
    val eased = t * t * (3f - 2f * t)
    return 1f - eased
}
