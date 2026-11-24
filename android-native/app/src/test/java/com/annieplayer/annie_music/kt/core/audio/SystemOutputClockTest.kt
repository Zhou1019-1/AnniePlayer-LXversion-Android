package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemOutputClockTest {

    private val ms = 1_000L

    @Test
    fun `正常播放取自然位置`() {
        val out = composePositionUs(
            ceilingUs = 20_000 * ms,
            ringUs = 500 * ms,
            wallUs = 19_400 * ms,
            floorUs = Long.MIN_VALUE,
        )
        assertEquals(19_500 * ms, out)
    }

    @Test
    fun `写阻塞时按墙钟前进但不越过已交付`() {
        val out = composePositionUs(
            ceilingUs = 20_500 * ms,
            ringUs = 500 * ms,
            wallUs = 22_000 * ms,
            floorUs = Long.MIN_VALUE,
        )
        assertEquals(20_500 * ms, out)
    }

    @Test
    fun `播放头给出负数时环深不被污染`() {
        assertEquals(
            500 * ms,
            refineRingUs(rawUs = -999_973_925 * ms, ceilingUs = 26_000 * ms, ringUs = 500 * ms),
        )
    }

    @Test
    fun `环深按实测缓慢收敛`() {
        val refined = refineRingUs(rawUs = 19_300 * ms, ceilingUs = 20_000 * ms, ringUs = 500 * ms)
        assertEquals(525 * ms, refined)
    }

    @Test
    fun `超出合理范围的环深估计被忽略`() {
        assertEquals(
            500 * ms,
            refineRingUs(rawUs = 1_000 * ms, ceilingUs = 20_000 * ms, ringUs = 500 * ms),
        )
    }

    @Test
    fun `不倒退`() {
        val out = composePositionUs(
            ceilingUs = 5_000 * ms,
            ringUs = 500 * ms,
            wallUs = 4_000 * ms,
            floorUs = 9_000 * ms,
        )
        assertEquals(9_000 * ms, out)
    }
}
