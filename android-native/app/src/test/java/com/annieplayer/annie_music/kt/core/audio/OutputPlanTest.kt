package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputPlanTest {

    private val anyRate: (Int) -> Boolean = { true }

    private fun plan(
        mode: AudioOutputMode = AudioOutputMode.transparent,
        route: AudioRoute = AudioRoute.wired,
        nativeRate: Int = 48_000,
        deviceRates: List<Int> = listOf(44_100, 48_000, 96_000),
        codecRate: Int = 0,
        sourceRate: Int = 44_100,
        sourceDepth: Int = 16,
        decodedRate: Int = 0,
        aaudioExclusive: Boolean = false,
        followSourceRate: Boolean = false,
        fullChain: Boolean = false,
        exclusiveSink: Boolean = false,
        bitExactSink: Boolean = false,
        exclusiveDepth: Int = 0,
        bitPerfectGranted: Boolean = false,
        dsdTargetRate: Int = 0,
        rawPassthrough: Boolean = false,
        canBuild: (Int) -> Boolean = anyRate,
    ): OutputPlan = resolveOutputPlan(
        mode = mode,
        route = route,
        nativeRate = nativeRate,
        deviceRates = deviceRates,
        codecRate = codecRate,
        sourceRate = sourceRate,
        sourceDepth = sourceDepth,
        decodedRate = decodedRate,
        aaudioExclusive = aaudioExclusive,
        followSourceRate = followSourceRate,
        fullChain = fullChain,
        exclusiveSink = exclusiveSink && route != AudioRoute.bluetooth,
        bitExactSink = bitExactSink && route != AudioRoute.bluetooth,
        exclusiveDepth = exclusiveDepth,
        bitPerfectGranted = bitPerfectGranted,
        dsdTargetRate = dsdTargetRate,
        rawPassthrough = rawPassthrough,
        canBuildRate = canBuild,
    )

    @Test
    fun `raw 帧的计划不碰任何参数——字节由封装决定 不是我方转换`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            sourceDepth = 32,
            decodedRate = 88_200,
            dsdTargetRate = 176_400,
            nativeRate = 48_000,
            rawPassthrough = true,
        )
        assertEquals(0, p.sampleRate)
        assertEquals(0, p.bitDepth)
        assertEquals(0, p.mixerTarget)
        assertEquals(0, p.dsdTargetRate)
        assertNull(p.tier)
        assertFalse(p.didResample)
        assertEquals(RateFallback.none, p.fallback)
        assertEquals(352_800, p.sourceRate)
        assertEquals(32, p.sourceDepth)
    }

    @Test
    fun `raw 帧在调音模式下也不接管位深`() {
        val p = plan(mode = AudioOutputMode.tuned, rawPassthrough = true)
        assertEquals(0, p.sampleRate)
        assertEquals(0, p.bitDepth)
        assertNull(p.tier)
    }

    @Test
    fun `非独占透明模式由我方转到系统率 而不是去读档位`() {        val p = plan(mode = AudioOutputMode.transparent)
        assertEquals(48_000, p.sampleRate)
        assertEquals(OutputTier.ownSrc, p.tier)
        assertTrue(p.didResample)
        assertEquals(0, p.mixerTarget)
        assertFalse(p.isPassthrough)
    }

    @Test
    fun `源率本来就等于系统率时什么都不写`() {
        val p = plan(mode = AudioOutputMode.transparent, sourceRate = 48_000)
        assertEquals(48_000, p.sampleRate)
        assertFalse(p.didResample)
        assertNull(p.tier)
    }

    @Test
    fun `透明模式重采样时交出 float 全精度`() {
        val p = plan(mode = AudioOutputMode.transparent)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(32, p.deliveredDepth)
        assertFalse(p.didConvertDepth)
    }

    @Test
    fun `透明模式不重采样时一位都不碰`() {
        val p = plan(mode = AudioOutputMode.transparent, sourceRate = 48_000)
        assertEquals(0, p.bitDepth)
        assertEquals(16, p.deliveredDepth)
        assertFalse(p.didResample)
    }

    @Test
    fun `全链路按解码率判断要不要接管位深`() {
        val same = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 176_400,
            decodedRate = 176_400,
            nativeRate = 176_400,
            deviceRates = listOf(176_400),
        )
        assertEquals(0, same.bitDepth)
        assertFalse(same.didResample)
    }

    @Test
    fun `DSD 走自己的目标率 位深交 float 且如实报我方 SRC`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, p.sampleRate)
        assertEquals(176_400, p.dsdTargetRate)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertTrue(p.didResample)
    }

    @Test
    fun `DSD 目标率压过按源率与独占`() {
        val follow = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
            followSourceRate = true,
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, follow.sampleRate)

        val exclusive = plan(
            mode = AudioOutputMode.transparent,
            exclusiveSink = true,
            aaudioExclusive = true,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, exclusive.sampleRate)
    }

    @Test
    fun `DSD 源率超过 AudioFormat 上限时 不许把源率当目标`() {
        val withoutTarget = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            decodedRate = 352_800,
            nativeRate = 48_000,
        )
        assertEquals(48_000, withoutTarget.sampleRate)
        assertEquals(0, withoutTarget.dsdTargetRate)
    }

    @Test
    fun `透明模式拿到 AAudio 独占时不重采样 而是原样送出`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            exclusiveSink = true,
            sourceRate = 44_100,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            aaudioExclusive = true,
        )
        assertEquals(0, p.sampleRate)
        assertEquals(OutputTier.bitPerfect, p.tier)
        assertFalse(p.didResample)
        assertEquals(0, p.bitDepth)
    }

    @Test
    fun `透明模式拿不到独占时才落到我方 SRC`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 44_100,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            aaudioExclusive = false,
        )
        assertEquals(48_000, p.sampleRate)
        assertEquals(OutputTier.ownSrc, p.tier)
    }

    @Test
    fun `调音模式不受 AAudio 独占影响`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            nativeRate = 96_000,
            sourceRate = 44_100,
            aaudioExclusive = true,
        )
        assertEquals(96_000, p.sampleRate)
    }

    @Test
    fun `独占端点只到 16 bit 时交出 16 bit 而不是 float`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            exclusiveSink = true,
            aaudioExclusive = true,
            sourceRate = 48_000,
            sourceDepth = 24,
            exclusiveDepth = 16,
        )
        assertEquals(0, p.sampleRate)
        assertEquals(16, p.bitDepth)
        assertEquals(16, p.deliveredDepth)
        assertTrue(p.didConvertDepth)
        assertTrue(shouldDither(outDepth = p.bitDepth, rounds = true))
    }

    @Test
    fun `端点能到 24 bit 时不动位深`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            exclusiveSink = true,
            aaudioExclusive = true,
            sourceRate = 48_000,
            sourceDepth = 24,
            exclusiveDepth = 24,
        )
        assertEquals(0, p.bitDepth)
        assertEquals(24, p.deliveredDepth)
        assertFalse(p.didConvertDepth)
    }

    @Test
    fun `USB 独占不按端点能力降位深`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            exclusiveSink = true,
            bitExactSink = true,
            sourceRate = 48_000,
            sourceDepth = 24,
            exclusiveDepth = 16,
        )
        assertEquals(0, p.bitDepth)
    }

    @Test
    fun `蓝牙也交 float`() {
        val p = plan(
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 44_100,
            sourceDepth = 24,
        )
        assertEquals(48_000, p.sampleRate)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertFalse(p.didConvertDepth)
    }

    @Test
    fun `什么都不做的时候不接管位深`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            sourceRate = 48_000,
        )
        assertEquals(0, p.bitDepth)
    }

    @Test
    fun `tuned resamples to the device rate and requests the same mixer rate`() {
        val p = plan(mode = AudioOutputMode.tuned, nativeRate = 96_000)
        assertEquals(96_000, p.sampleRate)
        assertTrue(p.didResample)
        assertEquals(p.sampleRate, p.mixerTarget)
    }

    @Test
    fun `tuned follow-source keeps the source rate`() {
        val p = plan(mode = AudioOutputMode.tuned, followSourceRate = true)
        assertEquals(0, p.sampleRate)
        assertNull(p.tier)
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `tuned follow-device uses the auto rate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.wired,
            nativeRate = 48_000,
        )
        assertEquals(48_000, p.sampleRate)
        assertEquals(48_000, p.mixerTarget)
    }

    @Test
    fun `调音模式下我方永远只换容器 不重量化位深`() {
        val p = plan(mode = AudioOutputMode.tuned)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(32, p.deliveredDepth)
        assertFalse(p.didConvertDepth)
    }

    @Test
    fun `解码轴只剩软解与全链路`() {
        assertEquals(DecodeKind.ffmpegSoft, plan().decode)
        assertEquals(DecodeKind.nativeFullChain, plan(fullChain = true).decode)
        assertEquals(2, DecodeKind.entries.size)
    }

    @Test
    fun `full chain skips the mixer request but still resamples`() {
        val p = plan(fullChain = true, mode = AudioOutputMode.tuned, nativeRate = 96_000)
        assertEquals(DecodeKind.nativeFullChain, p.decode)
        assertEquals(96_000, p.sampleRate)
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `bluetooth forces system mix and no mixer request`() {
        val p = plan(route = AudioRoute.bluetooth, exclusiveSink = true)
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `非独占通路取系统率`() {
        val p = plan(exclusiveSink = false)
        assertEquals(48_000, p.sampleRate)
    }

    @Test
    fun `auto rate follows the route`() {
        assertEquals(48_000, autoRateFor(AudioRoute.usb, 48_000, listOf(44_100, 48_000, 96_000)))
        assertEquals(48_000, autoRateFor(AudioRoute.wired, 48_000, emptyList()))
        assertEquals(44_100, autoRateFor(AudioRoute.usb, 44_100, emptyList()))
    }

    @Test
    fun `usb 自动率取 mixer 实际率 不跟着声明档走`() {
        val declared = listOf(44_100, 48_000, 96_000, 192_000)
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.usb, 48_000, declared, sourceRate = 44_100),
        )
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.usb, 48_000, listOf(96_000, 192_000), sourceRate = 44_100),
        )
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.usb, 48_000, listOf(48_000, 96_000, 192_000), sourceRate = 384_000),
        )
        assertEquals(
            192_000,
            autoRateFor(AudioRoute.usb, 0, listOf(48_000, 96_000, 192_000), sourceRate = 48_000),
        )
    }

    @Test
    fun `bluetooth auto rate uses the negotiated codec rate`() {
        assertEquals(0, autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000)))
        assertEquals(
            48_000,
            autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000), codecRate = 48_000),
        )
        assertEquals(
            96_000,
            autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000, 96_000), codecRate = 96_000),
        )
        assertEquals(
            0,
            autoRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000), codecRate = 192_000),
        )
    }

    @Test
    fun `bluetooth follow-device resamples to the codec rate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
        )
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.didResample)
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `bluetooth follow-source keeps the source rate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            followSourceRate = true,
            codecRate = 48_000,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `unknown source rate does not fabricate a resample`() {
        val p = plan(mode = AudioOutputMode.tuned, nativeRate = 96_000, sourceRate = 0)
        assertEquals(96_000, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `AAudio 独占成立时目标率为 0（原样送出）`() {
        val p = plan(exclusiveSink = true, aaudioExclusive = true)
        assertEquals(0, p.sampleRate)
        assertEquals(OutputTier.bitPerfect, p.tier)
    }

    @Test
    fun `AAudio 独占不成立时退回系统率`() {
        val p = plan(exclusiveSink = true, aaudioExclusive = false)
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.tier != OutputTier.bitPerfect)
    }

    @Test
    fun `按源率输出 一律不预转换`() {
        val p = plan(
            followSourceRate = true,
            nativeRate = 48_000,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `按源率输出 主率与源率一致时同样不预转换`() {
        val p = plan(
            followSourceRate = true,
            nativeRate = 44_100,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `按源率输出 源率未知时不生效`() {
        val p = plan(
            followSourceRate = true,
            nativeRate = 48_000,
            sourceRate = 0,
        )
        assertEquals(48_000, p.sampleRate)
    }

    @Test
    fun `调音与按源率可以同时成立`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            followSourceRate = true,
            nativeRate = 48_000,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(0, p.mixerTarget)
    }

    @Test
    fun `独占通路的调音工作在源率上 不用 nativeRate`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            exclusiveSink = true,
            followSourceRate = true,
            nativeRate = 96_000,
            sourceRate = 44_100,
        )
        assertEquals(44_100, p.sampleRate)
        assertFalse(p.didResample)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
    }

    @Test
    fun `调音时重采样目标照常生效 位深恒交 float`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            nativeRate = 96_000,
            exclusiveSink = false,
        )
        assertEquals(96_000, p.sampleRate)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
        assertEquals(96_000, p.mixerTarget)
    }

    @Test
    fun `bluetooth forces system mix so bit-perfect cannot switch the SRC off`() {
        val p = plan(
            mode = AudioOutputMode.tuned,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 44_100,
            sourceDepth = 16,
            exclusiveSink = true,
        )
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.didResample)
        assertEquals(0, p.mixerTarget)
        assertEquals(PLAN_FLOAT_DEPTH, p.bitDepth)
    }

    @Test
    fun `蓝牙下独占探测不再生效 转率归我方 且不报 Bit Perfect`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            sourceRate = 44_100,
            aaudioExclusive = true,
            exclusiveSink = true,
        )
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.didResample)
        assertEquals(OutputTier.ownSrc, p.tier)
    }

    @Test
    fun `蓝牙下按源率同样生效 我方不预转换`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.bluetooth,
            codecRate = 48_000,
            deviceRates = listOf(44_100, 48_000),
            sourceRate = 44_100,
            followSourceRate = true,
        )
        assertEquals(0, p.sampleRate)
        assertFalse(p.didResample)
    }

    @Test
    fun `USB 独占生效时不做我方 SRC`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            nativeRate = 96_000,
            exclusiveSink = true,
            bitExactSink = true,
            sourceRate = 44_100,
        )
        assertEquals(0, p.sampleRate)
        assertEquals(0, p.bitDepth)
        assertFalse(p.didResample)
    }

    @Test
    fun `非独占 sink 一律按系统率规划`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            nativeRate = 176_400,
            deviceRates = listOf(44_100, 48_000, 176_400),
            exclusiveSink = false,
            sourceRate = 44_100,
        )
        assertEquals(176_400, p.sampleRate)
        assertEquals(176_400, p.deliveredRate)
    }

    @Test
    fun `DSD 目标率压过系统率与调音`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            sourceRate = 352_800,
            dsdTargetRate = 176_400,
        )
        assertEquals(176_400, p.sampleRate)
        assertEquals(176_400, p.deliveredRate)
    }

    @Test
    fun `直通已整条删除 计划里没有任何直通字段`() {
        val names = OutputPlan::class.java.declaredFields.map { it.name }
        assertTrue(
            "OutputPlan 里还有直通字段：$names",
            names.none { it.contains("direct", ignoreCase = true) },
        )
    }

    @Test
    fun `bluetooth device depth comes from the codec not the mixer`() {
        assertEquals(
            24,
            resolveDeviceDepth(
                route = AudioRoute.bluetooth,
                codecDepth = 24,
                mixerDepth = 16,
                mixerFloat = true,
            ),
        )
    }

    @Test
    fun `bluetooth device depth falls back to the mixer when codec is unknown`() {
        assertEquals(
            32,
            resolveDeviceDepth(
                route = AudioRoute.bluetooth,
                codecDepth = 0,
                mixerDepth = 16,
                mixerFloat = true,
            ),
        )
        assertEquals(
            16,
            resolveDeviceDepth(
                route = AudioRoute.bluetooth,
                codecDepth = 0,
                mixerDepth = 16,
                mixerFloat = false,
            ),
        )
    }

    @Test
    fun `non bluetooth device depth ignores the codec and uses the mixer`() {
        assertEquals(
            32,
            resolveDeviceDepth(
                route = AudioRoute.speaker,
                codecDepth = 24,
                mixerDepth = 16,
                mixerFloat = true,
            ),
        )
        assertEquals(
            24,
            resolveDeviceDepth(
                route = AudioRoute.usb,
                codecDepth = 32,
                mixerDepth = 24,
                mixerFloat = false,
            ),
        )
    }

    @Test
    fun `usb 目标率取 mixer 实际率 不取声明档`() {
        assertEquals(
            48_000,
            systemMixRateFor(
                AudioRoute.usb, 48_000, listOf(44_100, 48_000, 96_000), sourceRate = 44_100,
            ),
        )
        assertEquals(
            48_000,
            systemMixRateFor(
                AudioRoute.usb, 48_000, listOf(48_000, 96_000, 192_000), sourceRate = 96_000,
            ),
        )
        assertEquals(
            48_000,
            systemMixRateFor(AudioRoute.usb, 0, listOf(44_100, 48_000, 96_000), sourceRate = 50_000),
        )
        assertEquals(0, systemMixRateFor(AudioRoute.usb, 0, emptyList(), sourceRate = 44_100))
    }

    @Test
    fun `扬声器与有线用 primary 率 蓝牙只认协商率`() {
        assertEquals(48_000, systemMixRateFor(AudioRoute.speaker, 48_000, listOf(44_100, 48_000)))
        assertEquals(44_100, systemMixRateFor(AudioRoute.wired, 44_100, emptyList()))
        assertEquals(
            48_000,
            systemMixRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000), codecRate = 48_000),
        )
        assertEquals(0, systemMixRateFor(AudioRoute.bluetooth, 48_000, listOf(48_000)))
    }

    @Test
    fun `透明 USB 也要我方转 不把 SRC 留给系统`() {
        val p = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.usb,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 44_100,
        )
        assertEquals(48_000, p.sampleRate)
        assertTrue(p.didResample)
        assertEquals(OutputTier.ownSrc, p.tier)
        val same = plan(
            mode = AudioOutputMode.transparent,
            route = AudioRoute.usb,
            nativeRate = 48_000,
            deviceRates = listOf(44_100, 48_000, 96_000),
            sourceRate = 48_000,
        )
        assertEquals(48_000, same.sampleRate)
        assertFalse(same.didResample)
        assertNull(same.tier)
    }

    @Test
    fun `全新安装默认透明模式`() {
        assertEquals(AudioOutputMode.transparent, AudioOutputMode.fromKey(null))
        assertEquals(AudioOutputMode.transparent, AudioOutputMode.fromKey("garbage"))
        assertEquals(AudioOutputMode.transparent, AudioOutputMode.fromKey("transparent"))
        assertEquals(AudioOutputMode.tuned, AudioOutputMode.fromKey("tuned"))
    }

    @Test
    fun `真的会舍入且落到 16 位就要抖动`() {
        assertTrue(shouldDither(outDepth = 16, rounds = true))
    }

    @Test
    fun `不舍入就不许抖动`() {
        assertFalse(shouldDither(outDepth = 16, rounds = false))
        assertFalse(shouldDither(outDepth = 24, rounds = true))
        assertFalse(shouldDither(outDepth = 32, rounds = true))
        assertFalse(shouldDither(outDepth = 0, rounds = true))
    }

    @Test
    fun `有损源不再单独成为抖动理由`() {
        assertFalse(shouldDither(outDepth = 16, rounds = false))
        assertTrue(shouldDither(outDepth = 16, rounds = true))
        assertFalse(shouldDither(outDepth = 24, rounds = true))
    }
}
