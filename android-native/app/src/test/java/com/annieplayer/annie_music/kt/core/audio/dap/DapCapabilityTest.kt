package com.annieplayer.annie_music.kt.core.audio.dap

import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DapCapabilityTest {

    private fun verdict(
        tag: String = "dsd-vendor",
        format: Int = AUDIO_FORMAT_DSD_VENDOR_PACKED,
        sampleRate: Int = 88_200,
        outputFlags: Int = AUDIO_OUTPUT_FLAG_DIRECT,
        openResult: Int = 0,
        setStatus: Int = 0,
        // `played` 要求**半秒音频**（88200 × 8 / 2 = 352 800 字节），所以默认刚好给半秒。
        // 曾经这里给三块（49 152 字节 ≈ 0.07 秒）就够了 —— 那个判据在 JM21 上判过假阳性：
        // 88200 那条候选只吃 0.07 秒就不再取，播放时 buffer 填满、进度钉在 0.00。
        writeBytes: Int = 352_800,
        framesBefore: Long = 0L,
        framesAfter: Long = 1_764L,
        stalledAt: String = "",
        bytesPerFrame: Int = 8,
        notes: String = "",
    ) = DapFormatVerdict(
        tag = tag,
        format = format,
        sampleRate = sampleRate,
        outputFlags = outputFlags,
        bytesPerFrame = bytesPerFrame,
        openResult = openResult,
        setStatus = setStatus,
        writeBytes = writeBytes,
        framesBefore = framesBefore,
        framesAfter = framesAfter,
        stalledAt = stalledAt,
        notes = notes,
    )

    private fun capability(
        verdicts: List<DapFormatVerdict>,
        nativeStatus: Int = NativeDapDac.OK,
    ) = DapCapability(
        fingerprint = "fp",
        nativeStatus = nativeStatus,
        abi = 3,
        probeLevel = DsdLevel.dsd64,
        verdicts = verdicts,
    )

    @Test
    fun `能播的判据是持续消费够半秒，不是播放头前进也不是能写进几块`() {
        assertTrue(verdict().played)
        // 建流被拒
        assertFalse(verdict(openResult = NativeDapDac.ERR_SET_REJECTED, setStatus = -22).played)
        // 建流成功但一个字节都没排上
        assertFalse(verdict(writeBytes = 0).played)
        // 写进两个缓冲：**不再算能播**。实测 JM21 的 88200 那条正是只吃到这个量级就停，
        // 旧判据（两个缓冲 + 播放头动过）把它判成通过，播放时进度条钉在 0.00。
        assertFalse(verdict(writeBytes = 8 * DAP_PROBE_WRITE_FRAMES * 2).played)
        // 刚好半秒 = 最低线
        assertTrue(verdict(writeBytes = 88_200 * 8 / 2).played)
        // 比半秒少一个字节就不算
        assertFalse(verdict(writeBytes = 88_200 * 8 / 2 - 1).played)
        // **播放头不动仍然算能播**：厂商 DSD 端口上 getPosition 不可靠
        // （实测同一条候选两次跑，一次 +879 帧、一次 -38）
        val noPosition = verdict(framesAfter = 0L)
        assertTrue(noPosition.played)
        assertFalse(noPosition.positionOk)
    }

    @Test
    fun `播放头倒退不能当位置可用`() {
        val v = verdict(framesBefore = 5_000L, framesAfter = 10L)
        assertEquals(0L, v.framesAdvanced)
        assertFalse(v.positionOk)
    }

    @Test
    fun `选路优先 DIRECT，其次位置可用，同档保持候选表顺序`() {
        val plainFirst = verdict(tag = "plain", outputFlags = 0)
        val directNoPos = verdict(tag = "direct-nopos", framesAfter = 0L)
        val directWithPos = verdict(tag = "direct-pos")

        // 有位置可用的 DIRECT 赢
        assertEquals(
            "direct-pos",
            capability(listOf(plainFirst, directNoPos, directWithPos)).working?.tag,
        )
        // 都没有位置可用时，退到"能播的 DIRECT"，仍然不要 plain
        assertEquals(
            "direct-nopos",
            capability(listOf(plainFirst, directNoPos)).working?.tag,
        )
        // 连 DIRECT 都没有时才用 plain
        assertEquals("plain", capability(listOf(plainFirst)).working?.tag)
    }

    /**
     * **能喂的容器压过"位置可用"。**
     *
     * 实测踩到：严格判据下 `0x1A000001 @ 176400`（16 bit 容器、4 B/帧）因为 `getPosition`
     * 可用，把 `0x1A000002 @ 88200`（32 位字、8 B/帧）挤出 `working`。可 raw 管线只会产
     * 32 位字，形状对不上 → 内置 DAC 开不出来 → DSD 帧掉进系统 mixer → **满幅噪声**。
     */
    @Test
    fun `容器喂得进去压过位置可用`() {
        val word32NoPos = verdict(
            tag = "u32-nopos",
            format = AUDIO_FORMAT_DSD_VENDOR_PACKED,
            sampleRate = 88_200,
            bytesPerFrame = 8,
            writeBytes = 88_200 * 8 / 2,
            framesAfter = 0L,
        )
        val short16WithPos = verdict(
            tag = "s16-pos",
            format = AUDIO_FORMAT_DSD_MSB,
            sampleRate = 176_400,
            bytesPerFrame = 4,
            writeBytes = 176_400 * 4 / 2,
            framesAfter = 4_399L,
        )
        assertEquals(
            "u32-nopos",
            capability(listOf(short16WithPos, word32NoPos)).working?.tag,
        )
        // 但"没在持续消费"的 32 位字仍然不算 —— 能喂不等于能播
        assertEquals(
            "s16-pos",
            capability(listOf(short16WithPos, word32NoPos.copy(writeBytes = 0)))
                .working?.tag,
        )
    }

    @Test
    fun `第一组真的播起来的就是结论`() {
        val cap = capability(
            listOf(
                verdict(tag = "a", openResult = NativeDapDac.ERR_SET_REJECTED, setStatus = -22),
                verdict(tag = "b", writeBytes = 0),
                verdict(tag = "c"),
            ),
        )
        assertEquals("c", cap.working?.tag)
        assertFalse(cap.exhaustedWithoutLuck)
    }

    @Test
    fun `测过了但一组都不成立，与还没测要分开`() {
        val tried = capability(listOf(verdict(openResult = NativeDapDac.ERR_SET_REJECTED)))
        assertNull(tried.working)
        assertTrue(tried.exhaustedWithoutLuck)

        // native 那条整条路不通：verdicts 空，不算"测过都不行"
        val unreachable = capability(emptyList(), nativeStatus = NativeDapDac.ERR_NO_LIB)
        assertFalse(unreachable.reachable)
        assertFalse(unreachable.exhaustedWithoutLuck)
    }

    @Test
    fun `文本编解码往返不丢字段`() {
        val original = capability(
            listOf(
                verdict(tag = "sanity/pcm16", format = AUDIO_FORMAT_PCM_16_BIT, sampleRate = 48_000),
                verdict(tag = "dsd-lsb", format = AUDIO_FORMAT_DSD_LSB, sampleRate = 2_822_400),
                verdict(tag = "rat", openResult = NativeDapDac.ERR_SET_REJECTED, setStatus = -22),
            ),
        )
        val round = DapCapability.decode(original.encode())
        assertEquals(original, round)
    }

    @Test
    fun `版本不符或字段缺失一律解码失败——宁可重测也不要错结论`() {
        val text = capability(listOf(verdict())).encode()
        assertNull(DapCapability.decode(text.replace("dap-capability|1", "dap-capability|2")))
        assertNull(DapCapability.decode(""))
        assertNull(DapCapability.decode("dap-capability|1\nfingerprint=fp\nnative=0\nabi=3\n"))
        // level 写成不认识的名字也不行
        assertNull(DapCapability.decode(text.replace("level=dsd64", "level=dsd999")))
        // 某一行字段数不对
        assertNull(DapCapability.decode(text + "v|a|1\n"))
    }

    @Test
    fun `静音图案按位序分两套，绝不用 0`() {
        // DSD 全 0 是一段直流，真机会响；两个标准静音图案是 0x69 / 0x96
        assertEquals(0x69.toByte(), dapSilencePattern(AUDIO_FORMAT_DSD_VENDOR_PACKED))
        assertEquals(0x69.toByte(), dapSilencePattern(AUDIO_FORMAT_DSD_LSB))
        assertEquals(0x96.toByte(), dapSilencePattern(AUDIO_FORMAT_DSD_MSB))
    }

    @Test
    fun `超时不是被拒也不是崩——必须单独看得出来`() {
        val v = verdict(openResult = NativeDapDac.ERR_TIMEOUT, setStatus = 2_500, writeBytes = -1)
        assertFalse(v.played)
        // 既不报具体的 set() 返回码（那会是 -22 之类），也不能落进别的分支
        assertTrue(v.diagnostic.contains("超时"))
        assertFalse(v.diagnostic.contains("set=-22"))
    }

    @Test
    fun `卡在哪一段要能分辨——卡在 close 其实已经能出声`() {
        val stalledPlay = verdict(openResult = NativeDapDac.OK, writeBytes = -1, stalledAt = "play")
        assertFalse(stalledPlay.played)
        assertTrue(stalledPlay.diagnostic.contains("play"))

        // 卡在 close：写入与播放头都拿到了，只是收尾收不干净 —— 结论仍然成立
        val stalledClose = verdict(stalledAt = "close")
        assertTrue(stalledClose.diagnostic.contains("close"))
        assertTrue(stalledClose.writeBytes > 0)
        assertTrue(stalledClose.framesAdvanced > 0L)
    }

    @Test
    fun `诊断行把三段结果都说清楚`() {
        assertTrue(verdict(openResult = -4, setStatus = -22).diagnostic.contains("set=-22"))
        assertTrue(verdict(writeBytes = 0).diagnostic.contains("写不进"))
        assertTrue(verdict(framesAfter = 0L).diagnostic.contains("播放头不动"))
        assertTrue(verdict().diagnostic.contains("+1764"))
    }

    @Test
    fun `缓存键含 ROM 指纹——换固件必须失效`() {
        val a = dapCapabilityFingerprint("brand/model/build:14/x", "PLQ110", 36)
        val b = dapCapabilityFingerprint("brand/model/build:15/y", "PLQ110", 36)
        val c = dapCapabilityFingerprint("brand/model/build:14/x", "JM13", 36)
        assertEquals(3, setOf(a, b, c).size)
    }
}
