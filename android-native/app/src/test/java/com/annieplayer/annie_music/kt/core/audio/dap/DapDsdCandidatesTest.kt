package com.annieplayer.annie_music.kt.core.audio.dap

import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DapDsdCandidatesTest {

    @Test
    fun `word 率是 bitRate 除以 32——不是 DoP 的除以 16`() {
        assertEquals(88_200, DsdLevel.dsd64.dapWordRate)
        assertEquals(176_400, DsdLevel.dsd128.dapWordRate)
        assertEquals(352_800, DsdLevel.dsd256.dapWordRate)
        assertEquals(705_600, DsdLevel.dsd512.dapWordRate)
        assertEquals(DsdLevel.dsd64.dapWordRate * 2, DsdLevel.dsd64.bitRate / 16)
    }

    @Test
    fun `8 bit 容器的候选要传完整比特率——这是最容易搞错的一条`() {
        // 反编译：entry = {format, rate, flags, variant}，rate 来自 entry+4。
        // 0x1A000000 / 0x1F000000 的 rate 是**完整比特率**，不是 bitRate/32。
        // 全表按 bitRate/32 传的话这两条永远被 set() 拒（率与容器宽度不自洽）。
        for (level in DsdLevel.entries) {
            for (c in dapDsdCandidates(level)) {
                if (c.container != DapContainer.byte8) continue
                assertEquals("level=${level.label} tag=${c.tag}", level.bitRate, c.sampleRate)
            }
        }
    }

    @Test
    fun `32 bit 容器传 word 率、16 bit 容器传 bitRate 除以 16`() {
        val list = dapDsdCandidates(DsdLevel.dsd128)
        val word = list.filter { it.container == DapContainer.word32 }
        val half = list.filter { it.container == DapContainer.short16 }
        assertTrue(word.isNotEmpty())
        assertTrue(half.isNotEmpty())
        for (c in word) assertEquals("tag=${c.tag}", DsdLevel.dsd128.dapWordRate, c.sampleRate)
        for (c in half) assertEquals("tag=${c.tag}", DsdLevel.dsd128.bitRate / 16, c.sampleRate)
    }

    @Test
    fun `缓冲帧数是 sampleRate 除以 5`() {
        // 反编译里用 0xCCCCCCCD 魔数除以 5，即 0.2 秒
        assertEquals(17_640, dapFrameCount(88_200))
        assertEquals(9_600, dapFrameCount(48_000))
        assertEquals(0, dapFrameCount(0))
        assertEquals(0, dapFrameCount(-1))
    }

    @Test
    fun `每帧字节数按容器宽度算，立体声翻倍`() {
        val list = dapDsdCandidates(DsdLevel.dsd64, vendor = DapVendor.fiio)
        for (c in list) {
            assertEquals("tag=${c.tag}", c.container.bytesPerSample * 2, c.bytesPerFrame)
        }
        // 1.1 MB 这一档来自 8 bit 容器 + 完整比特率；它不是笔误，是那张表的原样
        val wide = list.first { it.container == DapContainer.byte8 }
        assertEquals(DsdLevel.dsd64.bitRate / 5 * 2, wide.bufferBytes)
    }

    @Test
    fun `飞傲多两条 flags 为 0 的先试版本`() {
        val fiio = dapDsdCandidates(DsdLevel.dsd64, vendor = DapVendor.fiio)
        val plain = fiio.filter { it.outputFlags == 0 }
        val tags = plain.map { it.tag }.toSet()
        assertTrue("飞傲应有 dsd-vendor/plain 与 dsd-msb/plain，实际 $tags", tags.contains("dsd-vendor/plain"))
        assertTrue(tags.contains("dsd-msb/plain"))
        assertEquals(2, plain.size)

        val other = dapDsdCandidates(DsdLevel.dsd64)
        assertTrue(other.none { it.outputFlags == 0 })
    }

    @Test
    fun `飞傲的 dsd-vendor plain 排在同格式的 DIRECT 之前`() {
        val fiio = dapDsdCandidates(DsdLevel.dsd64, vendor = DapVendor.fiio)
        val firstVendor = fiio.first { it.format == AUDIO_FORMAT_DSD_VENDOR_PACKED }
        assertEquals("dsd-vendor/plain", firstVendor.tag)
        assertEquals(0, firstVendor.outputFlags)
    }

    @Test
    fun `smartaudio 族的 16 bit 容器用 word 率，其它用 bitRate 除以 16`() {
        val smart = dapDsdCandidates(DsdLevel.dsd64, isSmartAudioFamily = true)
        val msb = smart.first { it.format == AUDIO_FORMAT_DSD_MSB }
        assertEquals(88_200, msb.sampleRate)
        assertEquals(DapContainer.word32, msb.container)

        val plain = dapDsdCandidates(DsdLevel.dsd64)
        val plainMsb = plain.first { it.format == AUDIO_FORMAT_DSD_MSB }
        assertEquals(176_400, plainMsb.sampleRate)
        assertEquals(DapContainer.short16, plainMsb.container)
    }

    @Test
    fun `山灵与 Onix 先试一对 SDK33 私有值，先试哪个看 sdk`() {
        for (vendor in listOf(DapVendor.shanling, DapVendor.onix)) {
            val sdk34 = dapDsdCandidates(DsdLevel.dsd64, vendor = vendor, sdkInt = 34)
            assertEquals(AUDIO_FORMAT_SDK33_A, sdk34.first().format)
            assertEquals(4, sdk34.takeWhile { it.format in setOf(AUDIO_FORMAT_SDK33_A, AUDIO_FORMAT_SDK33_B) }.size)

            val sdk30 = dapDsdCandidates(DsdLevel.dsd64, vendor = vendor, sdkInt = 30)
            assertEquals(AUDIO_FORMAT_SDK33_B, sdk30.first().format)

            // 四条里两种 flag 各两条
            val pair = sdk34.take(4)
            assertEquals(2, pair.count { it.outputFlags == AUDIO_OUTPUT_FLAG_DIRECT })
            assertEquals(
                2,
                pair.count { it.outputFlags == AUDIO_OUTPUT_FLAG_DIRECT or AUDIO_OUTPUT_FLAG_DIRECT_PCM },
            )
        }
        // 不是这两家就没有那一段
        assertTrue(dapDsdCandidates(DsdLevel.dsd64).none { it.format == AUDIO_FORMAT_SDK33_A })
    }

    @Test
    fun `Cayin 在末位兜底之前多一条 0x40001`() {
        val cayin = dapDsdCandidates(DsdLevel.dsd64, vendor = DapVendor.cayin)
        val extra = cayin[cayin.size - 2]
        assertEquals(AUDIO_FORMAT_DSD_LSB, extra.format)
        assertEquals(AUDIO_OUTPUT_FLAG_CAYIN_EXTRA, extra.outputFlags)
        assertEquals(AUDIO_FORMAT_DAP_FALLBACK, cayin.last().format)
    }

    @Test
    fun `FPGA 机器的整张表都不一样——连兜底都换成 0x1A000000`() {
        val fpga = dapDsdCandidates(DsdLevel.dsd64, hasFpgaAudioNode = true)
        assertEquals(AUDIO_FORMAT_IEC61937, fpga.first().format)
        // 首条不带任何 flag（反编译里 IEC61937 那条 flags 为 0）
        assertEquals(0, fpga.first().outputFlags)
        assertTrue(fpga.any { it.format == AUDIO_FORMAT_DSD_VENDOR_PACKED })
        // FPGA 表的最后一条是 0x1A000000，不是 0x1F000000
        assertEquals(AUDIO_FORMAT_DSD_LSB, fpga.last().format)
        assertFalse(fpga.any { it.format == AUDIO_FORMAT_DAP_FALLBACK })
    }

    @Test
    fun `未知厂商的默认表就是反编译里的三条加兜底`() {
        val list = dapDsdCandidates(DsdLevel.dsd64)
        assertEquals(
            listOf(
                AUDIO_FORMAT_DSD_VENDOR_PACKED,
                AUDIO_FORMAT_DSD_LSB,
                AUDIO_FORMAT_DSD_MSB,
                AUDIO_FORMAT_DAP_FALLBACK,
            ),
            list.map { it.format },
        )
        // 三条的 flags 都是 DIRECT
        assertTrue(list.all { it.outputFlags == AUDIO_OUTPUT_FLAG_DIRECT })
    }

    @Test
    fun `tag 唯一——诊断按 tag 去重`() {
        val combos = buildList {
            for (vendor in DapVendor.entries) {
                for (smart in listOf(false, true)) {
                    for (fpga in listOf(false, true)) {
                        add(dapDsdCandidates(DsdLevel.dsd256, vendor, smart, fpga, 34))
                    }
                }
            }
        }
        for (list in combos) {
            val tags = list.map { it.tag }
            assertEquals("tag 重复：$tags", tags.size, tags.toSet().size)
        }
    }

    @Test
    fun `不同档位的表结构相同、只有率不同`() {
        val a = dapDsdCandidates(DsdLevel.dsd64)
        val b = dapDsdCandidates(DsdLevel.dsd512)
        assertEquals(a.map { it.format }, b.map { it.format })
        assertEquals(a.map { it.tag }, b.map { it.tag })
        assertNotEquals(a.map { it.sampleRate }, b.map { it.sampleRate })
    }
}
