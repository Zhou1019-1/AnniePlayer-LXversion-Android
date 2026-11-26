package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireSpecTest {

    private fun spec(
        sourceDepth: Int = 24,
        deliveredDepth: Int = 0,
        oboe: Boolean = false,
        hint: Int = 0,
        channels: Int = 2,
        rate: Int = 48000,
    ) = WireSpec.of(
        sampleRate = rate,
        channelCount = channels,
        sourceDepth = sourceDepth,
        deliveredDepth = deliveredDepth,
        oboeEngine = oboe,
        wireBitsHint = hint,
    )

    @Test
    fun `USB 通路只认源位深——deliveredDepth 是 AAudio 的概念`() {
        assertEquals(24, spec(sourceDepth = 24, deliveredDepth = 16).depth)
        assertEquals(16, spec(sourceDepth = 16, deliveredDepth = 32).depth)
        assertEquals(32, spec(sourceDepth = 32, deliveredDepth = 16).depth)
    }

    @Test
    fun `AAudio 通路优先交付位深——HAL 只能收 24 时就落地到 24`() {
        assertEquals(24, spec(sourceDepth = 32, deliveredDepth = 24, oboe = true).depth)
        assertEquals(16, spec(sourceDepth = 32, deliveredDepth = 16, oboe = true).depth)
        assertEquals(32, spec(sourceDepth = 24, deliveredDepth = 32, oboe = true).depth)
    }

    @Test
    fun `交付位深是 0 或离谱值时回落到源位深`() {
        assertEquals(32, spec(sourceDepth = 32, deliveredDepth = 0, oboe = true).depth)
        assertEquals(32, spec(sourceDepth = 32, deliveredDepth = 8, oboe = true).depth)
        assertEquals(32, spec(sourceDepth = 32, deliveredDepth = 64, oboe = true).depth)
    }

    @Test
    fun `位深决定的三个派生量必须同步`() {
        val s16 = spec(sourceDepth = 16)
        assertEquals(C.ENCODING_PCM_16BIT, s16.encoding)
        assertEquals(2 * 2, s16.bytesPerFrame)
        assertEquals(AAUDIO_FORMAT_I16, s16.aaudioFormat)

        val s24 = spec(sourceDepth = 24)
        assertEquals(C.ENCODING_PCM_24BIT, s24.encoding)
        assertEquals(3 * 2, s24.bytesPerFrame)
        assertEquals(AAUDIO_FORMAT_I24, s24.aaudioFormat)

        val s32 = spec(sourceDepth = 32)
        assertEquals(C.ENCODING_PCM_FLOAT, s32.encoding)
        assertEquals(4 * 2, s32.bytesPerFrame)
        assertEquals(AAUDIO_FORMAT_FLOAT, s32.aaudioFormat)
    }

    @Test
    fun `声道数只影响帧宽`() {
        assertEquals(3 * 1, spec(sourceDepth = 24, channels = 1).bytesPerFrame)
        assertEquals(3 * 6, spec(sourceDepth = 24, channels = 6).bytesPerFrame)
    }

    @Test
    fun `要向 native 声明的位深原样带出——raw DSD 由调用方传 0`() {
        assertEquals(24, spec(sourceDepth = 24, hint = 24).announceBits)
        assertEquals(0, spec(sourceDepth = 24, hint = 0).announceBits)
        assertEquals(48000, spec(rate = 48000).sampleRate)
    }

    @Test
    fun `端点回读格式的判定——不认识 I32 也不算丢了 24`() {
        assertTrue(WireSpec.formatAcceptable(AAUDIO_FORMAT_I24, AAUDIO_FORMAT_I24))
        assertTrue(WireSpec.formatAcceptable(AAUDIO_FORMAT_I24, AAUDIO_FORMAT_I32))
        assertTrue(WireSpec.formatAcceptable(AAUDIO_FORMAT_I24, 0))
        assertFalse(WireSpec.formatAcceptable(AAUDIO_FORMAT_I24, AAUDIO_FORMAT_I16))
        assertFalse(WireSpec.formatAcceptable(AAUDIO_FORMAT_FLOAT, AAUDIO_FORMAT_I24))
    }

    @Test
    fun `回读格式换算位深——未表态是 0 而不是 32`() {
        assertEquals(16, WireSpec.depthOfAaudioFormat(AAUDIO_FORMAT_I16))
        assertEquals(24, WireSpec.depthOfAaudioFormat(AAUDIO_FORMAT_I24))
        assertEquals(32, WireSpec.depthOfAaudioFormat(AAUDIO_FORMAT_I32))
        assertEquals(0, WireSpec.depthOfAaudioFormat(0))
        assertEquals(0, WireSpec.depthOfAaudioFormat(9))
    }

    @Test
    fun `同一组输入必须给出同一份规格`() {
        val a = spec(sourceDepth = 32, deliveredDepth = 24, oboe = true, hint = 24)
        val b = spec(sourceDepth = 32, deliveredDepth = 24, oboe = true, hint = 24)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `线宽变了必须重建流——否则输入字节会被按旧线宽解读`() {
        assertFalse(WireSpec.wireMatches(now = 24, then = 16))
        assertFalse(WireSpec.wireMatches(now = 16, then = 24))
        assertTrue(WireSpec.wireMatches(now = 24, then = 24))
    }

    @Test
    fun `线宽未知时不强制重建`() {
        assertTrue(WireSpec.wireMatches(now = 0, then = 24))
        assertTrue(WireSpec.wireMatches(now = 24, then = 0))
        assertTrue(WireSpec.wireMatches(now = 0, then = 0))
    }
}
