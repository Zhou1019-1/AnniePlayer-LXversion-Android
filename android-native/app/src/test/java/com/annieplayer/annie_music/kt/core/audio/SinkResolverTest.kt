package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SinkResolverTest {

    private fun req(
        route: AudioRoute = AudioRoute.wired,
        usbExclusive: Boolean = false,
        rawFrames: Boolean = false,
        dsdNativeAvailable: Boolean = false,
        aaudioExclusiveAvailable: Boolean = false,
        dopFrames: Boolean = false,
    ) = SinkRequest(
        route,
        usbExclusive,
        rawFrames,
        dsdNativeAvailable,
        aaudioExclusiveAvailable,
        dopFrames,
    )

    @Test
    fun `DoP 帧在 AAudio 独占成立时走 oboe——不进内置 DAC 的私有格式`() {
        assertEquals(
            listOf(SinkKind.oboe),
            resolveSinkChain(
                req(
                    rawFrames = true,
                    dopFrames = true,
                    aaudioExclusiveAvailable = true,
                    dsdNativeAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun `DoP 帧 AAudio 不成立时按私有格式走 DSD 直出——由判定再降成 PCM`() {
        assertEquals(
            listOf(SinkKind.dsdDirect),
            resolveSinkChain(
                req(rawFrames = true, dopFrames = true, dsdNativeAvailable = true),
            ),
        )
    }

    @Test
    fun `DoP 帧既没有 AAudio 也没有私有格式就没有 sink`() {
        assertTrue(
            resolveSinkChain(req(rawFrames = true, dopFrames = true)).isEmpty(),
        )
    }

    @Test
    fun `native 帧不看 AAudio——它只有私有格式一条路`() {
        assertEquals(
            listOf(SinkKind.dsdDirect),
            resolveSinkChain(
                req(
                    rawFrames = true,
                    dopFrames = false,
                    dsdNativeAvailable = true,
                    aaudioExclusiveAvailable = true,
                ),
            ),
        )
    }

    @Test
    fun `USB 独占开着且 DAC 在——先走 UAC，再退 oboe 与 AudioTrack`() {
        assertEquals(
            listOf(SinkKind.usbUac, SinkKind.oboe, SinkKind.audioTrack),
            resolveSinkChain(req(route = AudioRoute.usb, usbExclusive = true)),
        )
    }

    @Test
    fun `USB 独占下 raw 帧只走 UAC——DoP 与 native 都不能落 mixer`() {
        assertEquals(
            listOf(SinkKind.usbUac),
            resolveSinkChain(
                req(route = AudioRoute.usb, usbExclusive = true, rawFrames = true),
            ),
        )
    }

    @Test
    fun `USB 独占开着但 DAC 不在——落回非独占链`() {
        assertEquals(
            listOf(SinkKind.audioTrack),
            resolveSinkChain(req(route = AudioRoute.speaker, usbExclusive = true)),
        )
    }

    @Test
    fun `非独占下 raw 帧有私有 format 就走 DSD 直出`() {
        assertEquals(
            listOf(SinkKind.dsdDirect),
            resolveSinkChain(req(rawFrames = true, dsdNativeAvailable = true)),
        )
    }

    @Test
    fun `非独占下 raw 帧没有私有 format 就没有 sink——必须由调用方降成 PCM`() {
        assertTrue(resolveSinkChain(req(rawFrames = true, dsdNativeAvailable = false)).isEmpty())
    }

    @Test
    fun `非独占普通 PCM：AAudio 拿得到就先用它`() {
        assertEquals(
            listOf(SinkKind.oboe, SinkKind.audioTrack),
            resolveSinkChain(req(aaudioExclusiveAvailable = true)),
        )
        assertEquals(
            listOf(SinkKind.audioTrack),
            resolveSinkChain(req(aaudioExclusiveAvailable = false)),
        )
    }

    @Test
    fun `蓝牙一律 AudioTrack——链路参数由 codec 定`() {
        assertEquals(
            listOf(SinkKind.audioTrack),
            resolveSinkChain(
                req(route = AudioRoute.bluetooth, usbExclusive = true, aaudioExclusiveAvailable = true),
            ),
        )
    }

    @Test
    fun `每个 sink 有自己的上报键与标签`() {
        assertEquals("usb-direct", SinkKind.usbUac.key)
        assertEquals("dap-direct", SinkKind.dsdDirect.key)
        assertEquals("aaudio-exclusive", SinkKind.oboe.key)
        assertEquals("system-mix", SinkKind.audioTrack.key)
        assertEquals("USB 独占", SinkKind.usbUac.label)
        assertEquals("系统 · AudioTrack", SinkKind.audioTrack.label)
    }
}
