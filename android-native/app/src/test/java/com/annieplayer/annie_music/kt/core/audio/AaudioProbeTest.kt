package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AaudioProbeTest {

    @Test
    fun `拿到独占要认出三个字段`() {
        val r = parseAaudioProbe("EXCLUSIVE/48000/32/LOW_LATENCY")
        assertEquals(true, r?.exclusive)
        assertEquals(48_000, r?.sampleRate)
        assertEquals(32, r?.depth)
        assertEquals(true, r?.lowLatency)
        assertNull(r?.errorTag)
    }

    @Test
    fun `回来共享就不算独占`() {
        val r = parseAaudioProbe("SHARED/44100/16/NONE")
        assertEquals(false, r?.exclusive)
        assertEquals(44_100, r?.sampleRate)
        assertEquals(16, r?.depth)
        assertEquals(false, r?.lowLatency)
    }

    @Test
    fun `错误标记原样保留 不归一成 -1`() {
        assertEquals("-899", parseAaudioProbe("err:-899")?.errorTag)
        assertEquals("builder", parseAaudioProbe("err:builder")?.errorTag)
        assertEquals("lib", parseAaudioProbe("err:lib")?.errorTag)
        assertEquals("opensl", parseAaudioProbe("err:opensl")?.errorTag)
    }

    @Test
    fun `认不出的回包一律 null 而不是猜成功`() {
        assertNull(parseAaudioProbe(null))
        assertNull(parseAaudioProbe(""))
        assertNull(parseAaudioProbe("EXCLUSIVE/48000"))
        assertNull(parseAaudioProbe("EXCLUSIVE/xx/32/NONE"))
    }

    @Test
    fun `独占可用时不会误报成共享`() {
        val ok = parseAaudioProbe("EXCLUSIVE/44100/16/NONE")
        assertTrue(ok?.exclusive == true)
        assertFalse(ok?.lowLatency == true)
    }

    @Test
    fun `硬件格式与请求不符就不算逐字节`() {
        val floatOnI16 = parseAaudioProbe("EXCLUSIVE/48000/32/LOW_LATENCY/hw=1")
        assertEquals(AAUDIO_FORMAT_FLOAT, 2)
        assertTrue(floatOnI16?.exclusive == true)
        assertEquals(AAUDIO_FORMAT_I16, floatOnI16?.hardwareFormat)
        assertFalse(floatOnI16?.bitExactFor(AAUDIO_FORMAT_FLOAT) == true)
        assertTrue(floatOnI16?.bitExactFor(AAUDIO_FORMAT_I16) == true)
    }

    @Test
    fun `读不到硬件格式时不当作在转换`() {
        val unknown = parseAaudioProbe("EXCLUSIVE/44100/24/LOW_LATENCY/hw=0")
        assertEquals(0, unknown?.hardwareFormat)
        assertTrue(unknown?.bitExactFor(AAUDIO_FORMAT_I24) == true)
    }

    @Test
    fun `24 位被装进 32 位容器算逐字节`() {
        val widened = parseAaudioProbe("EXCLUSIVE/96000/24/LOW_LATENCY/hw=4")
        assertTrue(widened?.bitExactFor(AAUDIO_FORMAT_I24) == true)
        assertFalse(widened?.bitExactFor(AAUDIO_FORMAT_FLOAT) == true)
    }

    @Test
    fun `汇总分三组 各自按率列出`() {
        val text = exclusiveSummaryText(
            listOf(
                44_100 to true,
                48_000 to true,
                88_200 to false,
                96_000 to false,
                192_000 to null,
            ),
        )
        assertEquals("可用 44.1 kHz / 48 kHz · 不可用 88.2 kHz / 96 kHz · 未测 192 kHz", text)
    }

    @Test
    fun `一个率都没探过时明说未探测`() {
        assertEquals("未探测", exclusiveSummaryText(emptyList()))
        assertEquals("未探测", exclusiveSummaryText(listOf(44_100 to null, 48_000 to null)))
    }

    @Test
    fun `全部可用时不带多余的组`() {
        assertEquals("可用 44.1 kHz / 48 kHz", exclusiveSummaryText(listOf(44_100 to true, 48_000 to true)))
        assertEquals("不可用 48 kHz", exclusiveSummaryText(listOf(48_000 to false)))
    }

    @Test
    fun `硬件格式报 0 时记成未知 不当作已确认`() {
        val r = parseAaudioProbe("EXCLUSIVE/48000/24/LOW_LATENCY/hw=0")
        assertEquals(0, r?.hardwareFormat)
        assertFalse(r?.hardwareKnown == true)
        assertTrue(r?.sampleRateKnown == true)
        assertTrue(r?.formatKnown == true)
    }

    @Test
    fun `硬件格式缺省也算未知 老回包一样能读`() {
        val r = parseAaudioProbe("EXCLUSIVE/48000/32/LOW_LATENCY")
        assertEquals(0, r?.hardwareFormat)
        assertFalse(r?.hardwareKnown == true)
    }

    @Test
    fun `硬件格式有值时算已知`() {
        val r = parseAaudioProbe("EXCLUSIVE/48000/24/LOW_LATENCY/hw=3")
        assertEquals(AAUDIO_FORMAT_I24, r?.hardwareFormat)
        assertTrue(r?.hardwareKnown == true)
        assertEquals(0, r?.unknownMask)
    }

    @Test
    fun `末尾 unk 字段能解析 并与推出的事实合并`() {
        val r = parseAaudioProbe("EXCLUSIVE/96000/24/LOW_LATENCY/hw=2/unk=1")
        assertEquals(AAUDIO_UNKNOWN_RATE, r?.unknownMask)
        assertFalse(r?.sampleRateKnown == true)
        assertTrue(r?.formatKnown == true)
        assertTrue(r?.hardwareKnown == true)
    }

    @Test
    fun `采样率报 0 时记成未知`() {
        val r = parseAaudioProbe("EXCLUSIVE/0/24/LOW_LATENCY/hw=3")
        assertEquals(0, r?.sampleRate)
        assertFalse(r?.sampleRateKnown == true)
    }

    @Test
    fun `错误回包的未知位全置`() {
        val r = parseAaudioProbe("err:-38")
        assertEquals(
            AAUDIO_UNKNOWN_RATE or AAUDIO_UNKNOWN_FORMAT or AAUDIO_UNKNOWN_HW_FORMAT,
            r?.unknownMask,
        )
        assertEquals("-38", r?.errorTag)
    }

    @Test
    fun `每个候选的回调帧数落在 128 到 1024 之间`() {
        assertEquals(128, aaudioFramesPerCallback(44_100))
        assertEquals(128, aaudioFramesPerCallback(48_000))
        assertEquals(192, aaudioFramesPerCallback(96_000))
        assertEquals(384, aaudioFramesPerCallback(192_000))
        assertEquals(768, aaudioFramesPerCallback(384_000))
        assertEquals(1024, aaudioFramesPerCallback(768_000))
        assertEquals(128, aaudioFramesPerCallback(0))
    }

    @Test
    fun `缓冲容量是回调帧数的四倍`() {
        assertEquals(aaudioFramesPerCallback(48_000) * 4, aaudioBufferCapacityFrames(48_000))
        assertEquals(768, aaudioBufferCapacityFrames(96_000))
    }

    @Test
    fun `重试退避是 60 与 180 且第一次不退避`() {
        assertEquals(0L, aaudioOpenRetryDelayMs(0))
        assertEquals(60L, aaudioOpenRetryDelayMs(1))
        assertEquals(180L, aaudioOpenRetryDelayMs(2))
        assertEquals(180L, aaudioOpenRetryDelayMs(3))
    }

    @Test
    fun `一次 configure 的开流上界恰好等于两条候选各四次`() {
        assertEquals(4, AAUDIO_OPENS_PER_CANDIDATE)
        assertEquals(8, AAUDIO_OPENS_PER_CONFIGURE)
        assertEquals(AAUDIO_OPENS_PER_CONFIGURE, AAUDIO_OPENS_PER_CANDIDATE * 2)
    }
}
