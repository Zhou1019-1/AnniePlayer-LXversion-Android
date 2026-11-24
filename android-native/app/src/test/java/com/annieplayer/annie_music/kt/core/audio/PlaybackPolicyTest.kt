package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    @Test
    fun `AAudio 独占默认关闭`() {
        assertFalse(PlaybackPolicy().aaudioExclusive)
        assertFalse(PlaybackPolicy().exclusiveSink)
    }

    @Test
    fun `关着的时候探测结果再好看也不许进独占`() {
        assertFalse(aaudioExclusiveAllowed(enabled = false, probedAvailable = true))
        assertFalse(aaudioExclusiveAllowed(enabled = false, probedAvailable = false))
        assertFalse(aaudioExclusiveAllowed(enabled = false, probedAvailable = null))
    }

    @Test
    fun `开着也要探测说行才进独占`() {
        assertTrue(aaudioExclusiveAllowed(enabled = true, probedAvailable = true))
        assertFalse(aaudioExclusiveAllowed(enabled = true, probedAvailable = false))
        assertFalse(aaudioExclusiveAllowed(enabled = true, probedAvailable = null))
    }

    @Test
    fun `关掉 AAudio 后普通 PCM 的链里没有 oboe`() {
        val policy = PlaybackPolicy(usbExclusive = false, aaudioExclusive = false)
        assertEquals(
            listOf(SinkKind.audioTrack),
            resolveSinkChain(
                SinkRequest(
                    route = AudioRoute.wired,
                    usbExclusive = policy.usbExclusive,
                    rawFrames = false,
                    dsdNativeAvailable = false,
                    aaudioExclusiveAvailable = policy.aaudioExclusive,
                ),
            ),
        )
    }

    @Test
    fun `USB 独占失败也不再自动落到 oboe`() {
        val policy = PlaybackPolicy(usbExclusive = true, aaudioExclusive = false)
        assertEquals(
            listOf(SinkKind.audioTrack),
            resolveSinkChain(
                SinkRequest(
                    route = AudioRoute.speaker,
                    usbExclusive = policy.usbExclusive,
                    rawFrames = false,
                    dsdNativeAvailable = false,
                    aaudioExclusiveAvailable = policy.aaudioExclusive,
                ),
            ),
        )
    }

    @Test
    fun `开了 AAudio 独占且探测通过——链首是 oboe`() {
        val policy = PlaybackPolicy(aaudioExclusive = true)
        assertEquals(
            listOf(SinkKind.oboe, SinkKind.audioTrack),
            resolveSinkChain(
                SinkRequest(
                    route = AudioRoute.wired,
                    usbExclusive = policy.usbExclusive,
                    rawFrames = false,
                    dsdNativeAvailable = false,
                    aaudioExclusiveAvailable =
                    aaudioExclusiveAllowed(policy.aaudioExclusive, probedAvailable = true),
                ),
            ),
        )
    }

    @Test
    fun `调音判定只看通路与开关——关掉 AAudio 不影响`() {
        val policy = PlaybackPolicy(usbExclusive = false, aaudioExclusive = false)
        assertEquals(AudioOutputMode.tuned, policy.mode)
        assertEquals(AudioOutputMode.tuned, policy.copy(aaudioExclusive = true).mode)
        assertEquals(
            AudioOutputMode.transparent,
            policy.copy(tuningEngine = TuningEngine.none).mode,
        )
        assertEquals(
            AudioOutputMode.transparent,
            policy.copy(usbExclusive = true, toggles = OutputToggles(bitPerfect = true)).mode,
        )
        assertEquals(
            AudioOutputMode.tuned,
            policy.copy(usbExclusive = true, toggles = OutputToggles(bitPerfect = false)).mode,
        )
    }

    @Test
    fun `蓝牙下 AAudio 独占一律不算`() {
        assertFalse(aaudioExclusiveAllowed(enabled = false, probedAvailable = true))
        assertEquals(
            listOf(SinkKind.audioTrack),
            resolveSinkChain(
                SinkRequest(
                    route = AudioRoute.bluetooth,
                    usbExclusive = false,
                    rawFrames = false,
                    dsdNativeAvailable = false,
                    aaudioExclusiveAvailable = false,
                ),
            ),
        )
    }
}
