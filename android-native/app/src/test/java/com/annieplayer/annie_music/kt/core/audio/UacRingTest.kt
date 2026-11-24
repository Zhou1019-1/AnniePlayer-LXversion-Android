package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class UacRingTest {

    @Test
    fun `基准取当前读数，不是 0`() {
        val ring = UacRing()
        ring.rebase(4_410_000L)
        assertEquals(0L, ring.playedFrames(4_410_000L))
        assertEquals(4_410L, ring.playedFrames(4_414_410L))
    }

    @Test
    fun `写入累计与残留`() {
        val ring = UacRing()
        ring.rebase(0L)
        ring.onWritten(1_000L)
        ring.onWritten(500L)
        assertEquals(1_500L, ring.writtenFrames)
        assertEquals(1_500L, ring.pendingFrames(playedFrames = 0L))
        assertEquals(500L, ring.pendingFrames(playedFrames = 1_000L))
        assertEquals(0L, ring.pendingFrames(playedFrames = 2_000L))
    }

    @Test
    fun `读数倒退不许变负`() {
        val ring = UacRing()
        ring.rebase(5_000L)
        assertEquals(0L, ring.playedFrames(4_000L))
        assertEquals(0L, ring.playedFrames(0L))
    }

    @Test
    fun `在飞水深是一个 URB 环的容量`() {
        val ring = UacRing()
        assertEquals(2_778L, ring.inFlightFrames(44_100))
        assertEquals(0L, ring.inFlightFrames(0))
    }
}
