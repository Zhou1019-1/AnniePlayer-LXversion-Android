package com.annieplayer.annie_music.kt.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroCoverFadeTest {

    @Test
    fun 没滚动时封面完全不透明() {
        assertEquals(1f, heroCoverFade(0, 1000), 0.0001f)
    }

    @Test
    fun 划到八成高度时完全淡出() {
        assertEquals(0f, heroCoverFade(800, 1000), 0.0001f)
    }

    @Test
    fun 中途单调变淡() {
        val a = heroCoverFade(200, 1000)
        val b = heroCoverFade(400, 1000)
        val c = heroCoverFade(600, 1000)
        assertEquals(true, a > b && b > c)
        assertEquals(true, a < 1f && a > 0f)
    }

    @Test
    fun 划过头也不会变负() {
        assertEquals(0f, heroCoverFade(5000, 1000), 0.0001f)
    }

    @Test
    fun 封面高度为零时不除零() {
        assertEquals(1f, heroCoverFade(300, 0), 0.0001f)
    }
}
