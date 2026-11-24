package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {

    @Test
    fun `设备没声明任何编码时——所有判据都是不知道`() {
        val caps = DeviceCapabilities()
        assertFalse(caps.knowsDeclarations)
        assertNull(caps.declares(C.ENCODING_PCM_16BIT))
        assertNull(caps.aaudioExclusiveAt(48_000))
        assertEquals(0, caps.exclusiveNativeDepth)
    }

    @Test
    fun `端点深度按 24 优先于 float 优先于 16 取`() {
        assertEquals(
            16,
            DeviceCapabilities.depthFrom(listOf(C.ENCODING_PCM_16BIT)),
        )
        assertEquals(
            24,
            DeviceCapabilities.depthFrom(listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT)),
        )
        assertEquals(
            24,
            DeviceCapabilities.depthFrom(
                listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_FLOAT),
            ),
        )
        assertEquals(
            32,
            DeviceCapabilities.depthFrom(listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)),
        )
        assertEquals(0, DeviceCapabilities.depthFrom(emptyList()))
    }

    @Test
    fun `声明了却都不是我们认识的编码——一样按零处理`() {
        assertEquals(0, DeviceCapabilities.depthFrom(listOf(C.ENCODING_PCM_8BIT)))
    }

    @Test
    fun `逐率独占结论与设备声明是两件事`() {
        val caps = DeviceCapabilities(
            declaredEncodings = listOf(C.ENCODING_PCM_16BIT),
            aaudioExclusiveByRate = mapOf(48_000 to true, 44_100 to false),
            exclusiveNativeDepth = 16,
        )
        assertEquals(true, caps.aaudioExclusiveAt(48_000))
        assertEquals(false, caps.aaudioExclusiveAt(44_100))
        assertNull(caps.aaudioExclusiveAt(96_000))
        assertNull(caps.aaudioExclusiveAt(0))
        assertEquals(false, caps.declares(C.ENCODING_PCM_24BIT))
        assertEquals(true, caps.declares(C.ENCODING_PCM_16BIT))
    }

    @Test
    fun `策略 + 能力 = 能不能进独占——缺一个都不行`() {
        val caps = DeviceCapabilities(aaudioExclusiveByRate = mapOf(48_000 to true))
        assertFalse(PlaybackPolicy(aaudioExclusive = false).aaudioReady(caps, 48_000))
        assertTrue(PlaybackPolicy(aaudioExclusive = true).aaudioReady(caps, 48_000))
        assertFalse(PlaybackPolicy(aaudioExclusive = true).aaudioReady(caps, 44_100))
        assertFalse(PlaybackPolicy(aaudioExclusive = true).aaudioReady(DeviceCapabilities(), 48_000))
    }

    @Test
    fun `快照是不可变的——拿到之后探测结果再变也不影响这一份`() {
        val byRate = mutableMapOf(48_000 to true)
        val caps = DeviceCapabilities(aaudioExclusiveByRate = byRate.toMap())
        byRate[48_000] = false
        assertEquals(true, caps.aaudioExclusiveAt(48_000))
    }

    @Test
    fun `一份快照要装下所有能力事实`() {
        val system = DirectCapabilities(
            pcmRates = listOf(44_100, 48_000),
            sinkRates = listOf(384_000),
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            deviceLabel = "测试设备",
        )
        val caps = DeviceCapabilities(
            declaredEncodings = listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT),
            aaudioExclusiveByRate = mapOf(48_000 to true),
            aaudioMatrix = mapOf(48_000 to mapOf(3 to true, 1 to false)),
            exclusiveNativeDepth = 32,
            sinkDepth = 32,
            sinkIsFloat = true,
            mixerDepth = 24,
            mixerIsFloat = true,
            supportedDepths = listOf(0, 16, 24, 32),
            usbStreamLine = "流 raw=0 alt=3",
            system = system,
        )
        assertEquals(32, caps.exclusiveNativeDepth)
        assertEquals(32, caps.sinkDepth)
        assertTrue(caps.sinkIsFloat)
        assertEquals(24, caps.mixerDepth)
        assertTrue(caps.mixerIsFloat)
        assertEquals(listOf(0, 16, 24, 32), caps.supportedDepths)
        assertEquals("流 raw=0 alt=3", caps.usbStreamLine)
        assertEquals(system, caps.system)
        assertEquals(listOf(384_000), caps.system?.sinkRates)
    }

    @Test
    fun `逐格式矩阵与逐率结论互不干扰`() {
        val caps = DeviceCapabilities(
            aaudioExclusiveByRate = mapOf(48_000 to false),
            aaudioMatrix = mapOf(48_000 to mapOf(3 to true)),
        )
        assertEquals(false, caps.aaudioExclusiveAt(48_000))
        assertEquals(true, caps.aaudioMatrix[48_000]?.get(3))
    }
}
