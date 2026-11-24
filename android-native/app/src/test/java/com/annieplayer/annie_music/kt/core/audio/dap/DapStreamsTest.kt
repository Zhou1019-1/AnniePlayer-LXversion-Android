package com.annieplayer.annie_music.kt.core.audio.dap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上游字节形状」与「我们要开的容器」必须对得上 —— 对不上宁可判不成立。
 */
class DapStreamsTest {

    private fun working(
        format: Int = AUDIO_FORMAT_DSD_VENDOR_PACKED,
        sampleRate: Int = 88_200,
        outputFlags: Int = AUDIO_OUTPUT_FLAG_DIRECT,
        bytesPerFrame: Int = 8,
        positionOk: Boolean = true,
    ) = DapFormatVerdict(
        tag = "t",
        format = format,
        sampleRate = sampleRate,
        outputFlags = outputFlags,
        bytesPerFrame = bytesPerFrame,
        openResult = NativeDapDac.OK,
        setStatus = 0,
        writeBytes = 512 * 1024,
        framesBefore = 0L,
        framesAfter = if (positionOk) 1_000L else 0L,
    )

    @Test
    fun `32 bit 容器接受 PCM_32BIT 且字节形状一致`() {
        val spec = dapStreamSpecFor(88_200, 22, 2, working())
        assertNotNull(spec)
        spec!!
        assertEquals(AUDIO_FORMAT_DSD_VENDOR_PACKED, spec.encoding)
        assertEquals(8, spec.bytesPerFrame)
        // `frameCount` 是 **framework 帧**（这个私有 format 被当成 1 字节一帧），
        // 所以 0.2 秒容量 = (88200/5) × 8 字节。旧期望是裸的 17640 —— 那正好是 17640 字节
        // = 25 ms，实测写 1017 次里 976 次撞 EWOULDBLOCK。
        assertEquals(88_200 / 5 * 8, spec.frameCount)
        assertEquals(AUDIO_OUTPUT_FLAG_DIRECT, spec.outputFlags)
        assertEquals(AUDIO_CHANNEL_OUT_STEREO, spec.channelMask)
        assertEquals(2, spec.channelCount)
    }

    @Test
    fun `16 bit 容器要 PCM_16BIT，每帧 4 字节`() {
        val w = working(
            format = AUDIO_FORMAT_DSD_MSB,
            sampleRate = 176_400,
            bytesPerFrame = 4,
        )
        val spec = dapStreamSpecFor(176_400, 2, 2, w)
        assertNotNull(spec)
        assertEquals(4, spec!!.bytesPerFrame)
        assertEquals(AUDIO_FORMAT_DSD_MSB, spec.encoding)
    }

    @Test
    fun `编码与容器不匹配就判不成立——不能拿 32 bit word 当 16 bit 喂`() {
        // 32 bit 容器却声称送 16 bit
        assertNull(dapStreamSpecFor(88_200, 2, 2, working()))
        // 16 bit 容器却声称送 32 bit
        val short16 = working(format = AUDIO_FORMAT_DSD_MSB, sampleRate = 176_400, bytesPerFrame = 4)
        assertNull(dapStreamSpecFor(176_400, 22, 2, short16))
    }

    @Test
    fun `率必须与实测那一组一致`() {
        // 探测测的是 88.2k，上游却按 176.4k 送
        assertNull(dapStreamSpecFor(176_400, 22, 2, working()))
    }

    @Test
    fun `只接受立体声——候选表里全是 STEREO`() {
        assertNull(dapStreamSpecFor(88_200, 22, 1, working()))
        assertNull(dapStreamSpecFor(88_200, 22, 6, working()))
    }

    @Test
    fun `8 bit 容器没有对应的 Media3 编码，一律不成立`() {
        val lsb = working(format = AUDIO_FORMAT_DSD_LSB, sampleRate = 2_822_400, bytesPerFrame = 2)
        assertNull(dapStreamSpecFor(2_822_400, 22, 2, lsb))
        assertNull(dapStreamSpecFor(2_822_400, 2, 2, lsb))
        assertEquals(DapContainer.byte8, DapContainer.byte8)
        assertNull(dapContainerOf(AUDIO_FORMAT_DSD_LSB))
        assertNull(dapContainerOf(AUDIO_FORMAT_IEC61937))
    }

    @Test
    fun `容器映射与候选表一致`() {
        assertEquals(DapContainer.word32, dapContainerOf(AUDIO_FORMAT_DSD_VENDOR_PACKED))
        assertEquals(DapContainer.short16, dapContainerOf(AUDIO_FORMAT_DSD_MSB))
    }

    /**
     * 字节序由**容器**决定，不是全局常量。
     *
     * 打错方向不报错：`set()` 返回 0、轨道照样排空，DAC 解出来全是噪声。
     * 实测 JM21 按大端打 `0x1A000002`（= `DSD_U32_LE`）正是这个症状。
     */
    @Test
    fun `0x1A000002 是 DSD_U32_LE 必须按小端打`() {
        assertTrue(dapWordsAreLittleEndian(AUDIO_FORMAT_DSD_VENDOR_PACKED))
        // 16 bit 容器（DSD_MSB）不是 32 位字，字节序 = 时间序，按大端
        assertFalse(dapWordsAreLittleEndian(AUDIO_FORMAT_DSD_MSB))
        // 不是 DSD 段的 format 不参与这个判断
        assertFalse(dapWordsAreLittleEndian(AUDIO_FORMAT_IEC61937))
        assertFalse(dapWordsAreLittleEndian(0))
    }

    // ── PCM direct：普通曲目与 DoP 走的那条 ──

    @Test
    fun `DoP 的 24 bit 帧映射成 PCM_24_BIT_PACKED，每帧 6 字节`() {
        // DoP: 176400 Hz、`C.ENCODING_PCM_24BIT`(21)、立体声 → 6 字节/帧。
        // 这条以前没有端口可去（内置 DAC 只会开私有 DSD 容器），所以 DoP 必然静音。
        val spec = dapPcmStreamSpecFor(176_400, 21, 2)
        assertNotNull(spec)
        spec!!
        assertEquals(AUDIO_FORMAT_PCM_24_BIT_PACKED, spec.encoding)
        assertEquals(6, spec.bytesPerFrame)
        assertEquals(176_400, spec.sampleRate)
        assertEquals(AUDIO_OUTPUT_FLAG_DIRECT, spec.outputFlags)
        assertEquals(AUDIO_CHANNEL_OUT_STEREO, spec.channelMask)
        // **PCM 的 frameCount 是音频帧**，不是字节：framework 自己算得出每帧几字节。
        // 给字节数会开出 6 倍大的缓冲（0.2 s → 1.2 s），起播延迟与 seek 手感都会变。
        assertEquals(176_400 / 5, spec.frameCount)
        assertFalse(spec.frameCountInBytes)
    }

    @Test
    fun `普通 PCM 的四种编码各映射到自己的 audio_format_t`() {
        val cases = listOf(
            Triple(2, AUDIO_FORMAT_PCM_16_BIT, 4),
            Triple(21, AUDIO_FORMAT_PCM_24_BIT_PACKED, 6),
            Triple(22, AUDIO_FORMAT_PCM_32_BIT, 8),
            Triple(4, AUDIO_FORMAT_PCM_FLOAT, 8),
        )
        for ((media3, native, bytesPerFrame) in cases) {
            val spec = dapPcmStreamSpecFor(44_100, media3, 2)
            assertNotNull("enc=$media3", spec)
            assertEquals("enc=$media3", native, spec!!.encoding)
            assertEquals("enc=$media3", bytesPerFrame, spec.bytesPerFrame)
            // 源率原样交给端口：这条路是 bit-exact 的，我方不许预先转
            assertEquals("enc=$media3", 44_100, spec.sampleRate)
        }
    }

    @Test
    fun `PCM 端口只接受立体声与已知编码`() {
        assertNull(dapPcmStreamSpecFor(44_100, 21, 1))
        assertNull(dapPcmStreamSpecFor(44_100, 21, 6))
        assertNull(dapPcmStreamSpecFor(0, 21, 2))
        // 8 bit 定点（Media3 的 ENCODING_PCM_8BIT = 3）与私有 DSD 段都不在这条路上
        assertNull(dapPcmStreamSpecFor(44_100, 3, 2))
        assertNull(dapPcmStreamSpecFor(88_200, AUDIO_FORMAT_DSD_VENDOR_PACKED, 2))
    }

    @Test
    fun `每样本字节数按 audio_format_t 取值`() {
        assertEquals(2, pcmBytesPerSample(AUDIO_FORMAT_PCM_16_BIT))
        assertEquals(3, pcmBytesPerSample(AUDIO_FORMAT_PCM_24_BIT_PACKED))
        assertEquals(4, pcmBytesPerSample(AUDIO_FORMAT_PCM_32_BIT))
        assertEquals(4, pcmBytesPerSample(AUDIO_FORMAT_PCM_FLOAT))
        assertEquals(0, pcmBytesPerSample(AUDIO_FORMAT_DSD_VENDOR_PACKED))
    }

    /**
     * flags 阶梯：首组是 `DIRECT`，**第二组必须是 `DIRECT|DIRECT_PCM`**。
     *
     * 依据是实测 + 参考实现（ReSoNāda `openDirectPcm` 的 `{0x1, 0x2001, 0x9, 0x2009}`）：
     * 少了 `0x2000`，`set()` 照样成功、播放头照样走，厂商 HAL 却没人接管那个端口
     * —— 症状是纯静音，而不是报错。
     */
    @Test
    fun `PCM 的 flags 阶梯第二组是 DIRECT_PCM`() {
        assertEquals(AUDIO_OUTPUT_FLAG_DIRECT, dapPcmStreamSpecFor(44_100, 2, 2)!!.outputFlags)
        assertEquals(
            AUDIO_OUTPUT_FLAG_DIRECT or AUDIO_OUTPUT_FLAG_DIRECT_PCM,
            dapPcmStreamSpecFor(44_100, 2, 2)!!.alternateFlags.first(),
        )
        assertTrue(DAP_PCM_FLAG_LADDER.contains(AUDIO_OUTPUT_FLAG_DIRECT_PCM or AUDIO_OUTPUT_FLAG_DIRECT))
    }

    /**
     * DoP 只许试**保真** flags。
     *
     * 一旦落到经 mixer 的组合（`PRIMARY` / `DEEP_BUFFER`），`0x05 / 0xFA` 帧标记会被那一次
     * float 音量乘法改掉 —— 听感从"静音"变成"满幅噪声"，比不出声更糟。
     */
    @Test
    fun `DoP 的 flags 阶梯不含任何 mixer 组合`() {
        for (flags in DAP_DOP_FLAG_LADDER) {
            assertEquals(
                "flags=$flags 带了 DIRECT 之外的东西",
                AUDIO_OUTPUT_FLAG_DIRECT,
                flags and AUDIO_OUTPUT_FLAG_DIRECT,
            )
            assertEquals("flags=$flags 带了 PRIMARY", 0, flags and AUDIO_OUTPUT_FLAG_PRIMARY)
            assertEquals("flags=$flags 带了 DEEP_BUFFER", 0, flags and AUDIO_OUTPUT_FLAG_DEEP_BUFFER)
        }
        // 但必须含 DIRECT_PCM 那一组，否则这条阶梯等于没试
        assertTrue(DAP_DOP_FLAG_LADDER.contains(AUDIO_OUTPUT_FLAG_DIRECT_PCM or AUDIO_OUTPUT_FLAG_DIRECT))
    }
}
