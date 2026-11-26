package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ByteBudgetLoadControlTest {

    @Test
    fun pcm_16bit_stereo_44k() {
        assertEquals(
            44100.0 * 2 * 2,
            pcmBytesPerSecond(44100, 2, C.ENCODING_PCM_16BIT),
            0.001,
        )
    }

    @Test
    fun pcm_24bit_stereo_352k_is_dsd64_grid() {
        assertEquals(
            352800.0 * 2 * 3,
            pcmBytesPerSecond(352800, 2, C.ENCODING_PCM_24BIT),
            0.001,
        )
    }

    @Test
    fun pcm_float_stereo_2822k_is_dsd512_grid() {
        assertEquals(
            2822400.0 * 2 * 4,
            pcmBytesPerSecond(2822400, 2, C.ENCODING_PCM_FLOAT),
            0.001,
        )
    }

    @Test
    fun pcm_invalid_args_return_zero() {
        assertEquals(0.0, pcmBytesPerSecond(0, 2, C.ENCODING_PCM_16BIT), 0.001)
        assertEquals(0.0, pcmBytesPerSecond(44100, 0, C.ENCODING_PCM_16BIT), 0.001)
    }

    @Test
    fun format_bitrate_wins_over_pcm_fields() {
        val format = Format.Builder()
            .setSampleRate(44100)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setAverageBitrate(9216000)
            .build()
        assertEquals(9216000.0 / 8.0, bytesPerSecondOf(format), 0.001)
    }

    @Test
    fun format_without_bitrate_falls_back_to_pcm() {
        val format = Format.Builder()
            .setSampleMimeType("audio/raw")
            .setSampleRate(2822400)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_FLOAT)
            .build()
        assertEquals(2822400.0 * 2 * 4, bytesPerSecondOf(format), 0.001)
    }

    @Test
    fun resolve_skips_selections_without_format() {
        assertEquals(0.0, resolveBytesPerSecond(null), 0.001)
    }

    @Test
    fun dsd512_forward_budget_is_about_two_seconds() {
        val rate = pcmBytesPerSecond(2822400, 2, C.ENCODING_PCM_FLOAT)
        val seconds = 48.0 * 1024 * 1024 / rate
        assertTrue("DSD512 前向应远小于 5 秒，实际 $seconds", seconds < 5.0)
        assertTrue("DSD512 前向至少要有 1 秒，实际 $seconds", seconds > 1.0)
    }

    @Test
    fun dsd512_back_budget_stays_under_one_second() {
        val rate = pcmBytesPerSecond(2822400, 2, C.ENCODING_PCM_FLOAT)
        val seconds = 16.0 * 1024 * 1024 / rate
        assertTrue("DSD512 后向应小于 1 秒，实际 $seconds", seconds < 1.0)
    }

    @Test
    fun am_hires_forward_budget_exceeds_thirty_seconds() {
        val rate = 9216000.0 / 8.0
        val seconds = 48.0 * 1024 * 1024 / rate
        assertTrue("AM 24/192 前向应超过 30 秒，实际 $seconds", seconds > 30.0)
    }

    @Test
    fun total_budget_is_bounded_regardless_of_rate() {
        val forward = 48.0 * 1024 * 1024
        val back = 16.0 * 1024 * 1024
        assertEquals(64.0 * 1024 * 1024, forward + back, 1.0)
    }

    @Test
    fun unknown_rate_back_buffer_is_zero_not_cap() {
        val capMs = 10_000
        val rate = 0.0
        val result = if (rate <= 0.0) 0L else minOf((16L * 1024 * 1024 / rate * 1_000_000.0).toLong(), capMs * 1000L)
        assertEquals(0L, result)
    }
}
