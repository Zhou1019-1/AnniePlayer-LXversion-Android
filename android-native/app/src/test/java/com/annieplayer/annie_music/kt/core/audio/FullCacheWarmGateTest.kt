package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullCacheWarmGateTest {

    private fun gate(
        state: Int = Player.STATE_READY,
        playing: Boolean = true,
        buffered: Long = 30_000L,
        position: Long = 10_000L,
        min: Long = 5_000L,
    ): Boolean = shouldStartFullCacheWarm(state, playing, buffered, position, min)

    @Test
    fun ready_and_playing_with_enough_buffer_starts() {
        assertTrue(gate())
    }

    @Test
    fun buffering_state_blocks() {
        assertFalse(gate(state = Player.STATE_BUFFERING))
    }

    @Test
    fun idle_state_blocks() {
        assertFalse(gate(state = Player.STATE_IDLE))
    }

    @Test
    fun ended_state_blocks() {
        assertFalse(gate(state = Player.STATE_ENDED))
    }

    @Test
    fun paused_blocks() {
        assertFalse(gate(playing = false))
    }

    @Test
    fun buffer_exactly_at_threshold_starts() {
        assertTrue(gate(buffered = 15_000L, position = 10_000L, min = 5_000L))
    }

    @Test
    fun buffer_below_threshold_blocks() {
        assertFalse(gate(buffered = 14_999L, position = 10_000L, min = 5_000L))
    }

    @Test
    fun buffer_already_consumed_blocks() {
        assertFalse(gate(buffered = 10_000L, position = 10_000L, min = 5_000L))
    }

    @Test
    fun hi_res_threshold_uses_same_rule() {
        assertFalse(gate(buffered = 15_000L, position = 10_000L, min = 6_000L))
        assertTrue(gate(buffered = 16_000L, position = 10_000L, min = 6_000L))
    }
}
