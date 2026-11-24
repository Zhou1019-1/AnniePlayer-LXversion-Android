package com.annieplayer.annie_music.kt.core.audio.dap

import androidx.media3.common.C
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不碰 Android 的假流：水位、已播帧、写入结果都可控 */
private class FakeDapSink(private val bytesPerFrame: Int) : DapSink {

    var openResult = true
    var opens = 0
    private var alive = false

    /** 开流以来累计已播帧 */
    var playedFrames = 0L

    /** 一次 write 最多收多少字节（0 = 收不下） */
    var writeLimit = Int.MAX_VALUE

    /** 强制 write 的返回值（含负数 = 流坏了） */
    var forcedWriteResult: Int? = null

    var flushes = 0
    var closes = 0
    var plays = 0
    var pauses = 0

    override val isOpen: Boolean get() = alive

    override fun open(spec: DapStreamSpec): Boolean {
        opens++
        alive = openResult
        return openResult
    }

    override fun write(buffer: ByteBuffer): Int {
        forcedWriteResult?.let { return it }
        val frames = minOf(buffer.remaining(), writeLimit) / bytesPerFrame
        val bytes = frames * bytesPerFrame
        buffer.position(buffer.position() + bytes)
        return bytes
    }

    override fun play() {
        plays++
    }

    override fun pause() {
        pauses++
    }

    override fun flush() {
        flushes++
    }

    override fun playbackFrames(): Long = playedFrames

    override fun close() {
        closes++
        alive = false
    }
}

class DapDirectOutputTest {

    private val sampleRate = 44_100
    private val bytesPerFrame = 6 // 24 bit × 2ch

    private val spec = DapStreamSpec(
        sampleRate = sampleRate,
        channelCount = 2,
        channelMask = AUDIO_CHANNEL_OUT_STEREO, // native 值 3，不是 Java 的 0xC
        encoding = AUDIO_FORMAT_PCM_24_BIT_PACKED, // native audio_format_t（不是 Java 的 21）
        bytesPerFrame = bytesPerFrame,
        frameCount = 4096,
    )

    private fun openOutput(
        sink: FakeDapSink,
        advancing: () -> Unit = {},
    ): DapDirectOutput {
        val out = DapDirectOutput(sink, spec, advancing)
        assertTrue("open 应该成功", out.open())
        return out
    }

    private fun frames(n: Int): ByteBuffer = ByteBuffer.allocate(n * bytesPerFrame)

    @Test
    fun `位置不可用时时钟靠墙上时钟推进，且不超过已写量`() {
        val sink = FakeDapSink(bytesPerFrame)
        var now = 0L
        val out = DapDirectOutput(sink, spec, hardwarePosition = false, nanoTime = { now })
        assertTrue(out.open())
        out.play()
        assertTrue(out.write(frames(sampleRate), 0L, 0)) // 写满 1 秒

        now = 500_000_000L // 过了 0.5 秒
        assertEquals(500_000L, out.positionUs(false))

        // 时钟不能跑到数据前面：写进去的只有 1 秒
        now = 3_000_000_000L
        assertEquals(1_000_000L, out.positionUs(false))
    }

    @Test
    fun `暂停时软件时钟冻住，恢复后接着走`() {
        val sink = FakeDapSink(bytesPerFrame)
        var now = 0L
        val out = DapDirectOutput(sink, spec, hardwarePosition = false, nanoTime = { now })
        assertTrue(out.open())
        out.play()
        assertTrue(out.write(frames(sampleRate), 0L, 0))

        now = 200_000_000L
        out.pause()
        val frozen = out.positionUs(false)
        assertEquals(200_000L, frozen)

        // 暂停期间时间继续走，位置不动
        now = 5_000_000_000L
        assertEquals("暂停中位置必须冻住", frozen, out.positionUs(false))
    }

    @Test
    fun `硬件位置可用时不走软件时钟`() {
        val sink = FakeDapSink(bytesPerFrame)
        var now = 0L
        val out = DapDirectOutput(sink, spec, hardwarePosition = true, nanoTime = { now })
        assertTrue(out.open())
        out.play()
        assertTrue(out.write(frames(1000), 0L, 0))

        now = 5_000_000_000L // 墙上时钟走过 5 秒，但硬件说一帧没播
        assertEquals(0L, out.positionUs(false))

        sink.playedFrames = 441
        assertEquals(441L * 1_000_000L / sampleRate, out.positionUs(false))
    }

    @Test
    fun `首块建立时间轴——位置从它的时间戳起算`() {
        val sink = FakeDapSink(bytesPerFrame)
        var advancing = 0
        val out = openOutput(sink) { advancing++ }

        assertTrue(out.write(frames(1000), 1_000_000L, 0))
        assertEquals("首块必须上报 onPositionAdvancing", 1, advancing)
        // 一帧都还没播
        assertEquals(1_000_000L, out.positionUs(false))

        // 播到一半
        sink.playedFrames = 500
        assertEquals(1_000_000L + 500L * 1_000_000L / sampleRate, out.positionUs(false))
    }

    @Test
    fun `非首块不再上报 onPositionAdvancing`() {
        val sink = FakeDapSink(bytesPerFrame)
        var advancing = 0
        val out = openOutput(sink) { advancing++ }
        out.write(frames(100), 0L, 0)
        out.write(frames(100), 2_267L, 0)
        out.write(frames(100), 4_535L, 0)
        assertEquals(1, advancing)
    }

    @Test
    fun `设备暂时不收时由环吸收，不再打回 Media3`() {
        // **这条的期望被有意改过。**
        //
        // 旧行为：设备返回 0 → `write` 返回 false → Media3 用同一块无限重试。
        // 实测一次播放撞了 3883 次 `-11`（`EWOULDBLOCK`），把"设备卡顿"直接变成"播放器卡住"。
        // 现在由 `SelfPacedWriter` 的环吸收，后台线程按设备节奏喂 —— 上层只管交字节。
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.write(frames(100), 0L, 0)
        val deviceGot = sink.playedFrames

        sink.writeLimit = 0
        val buf = frames(100)
        assertTrue("环要接住（不再返回 false 打回 Media3）", out.write(buf, 0L, 0))
        assertEquals("整块进环，buffer 走到底", 0, buf.remaining())
        assertEquals("设备一个字节都没拿到", deviceGot, sink.playedFrames)
        assertTrue("这批数据仍算未播", out.hasPendingData())
    }

    @Test
    fun `设备报错时清空队列且不谎报有待播数据`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        sink.forcedWriteResult = -1
        // 环先接住（契约变了：不在 write 里阻塞调用方）
        out.write(frames(100), 0L, 0)
        // 写线程遇到 -1 会清空队列并上报致命错误，此后不许再声称"还有数据要播"
        Thread.sleep(80)
        assertFalse("流坏了就不该再报待播", out.hasPendingData())
    }

    @Test
    fun `不足一帧的尾巴丢掉但不要求重试——否则会无限循环`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        val buf = ByteBuffer.allocate(bytesPerFrame - 1)
        assertTrue(out.write(buf, 0L, 0))
        assertEquals("这截必须被吃掉", bytesPerFrame - 1, buf.position())
    }

    @Test
    fun `flush 之后基准取当前读数——不把累计帧数当本首已播`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.write(frames(1000), 0L, 0)
        sink.playedFrames = 1000
        assertEquals(1000L * 1_000_000L / sampleRate, out.positionUs(false))

        // seek：流没关，播放头继续往前累计（不归零）
        sink.playedFrames = 5000
        out.flush(playRequested = false)
        // 时间轴零点被清掉，位置回到"未建立"
        assertEquals(C.TIME_UNSET, out.positionUs(false))

        // 新的一段从 7 s 起算；已播帧的基准是 5000（不是 0），所以一帧都没播时就是 7 s
        out.write(frames(200), 7_000_000L, 0)
        assertEquals(7_000_000L, out.positionUs(false))
        sink.playedFrames = 5100
        assertEquals(7_000_000L + 100L * 1_000_000L / sampleRate, out.positionUs(false))
    }

    @Test
    fun `flush 带播放意图时补一次 play——否则 seek 后没声`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.flush(playRequested = true)
        assertTrue("AudioTrack.flush 之后必须重新 play", sink.plays > 0)
        out.flush(playRequested = false)
        val playsBefore = sink.plays
        out.flush(playRequested = false)
        assertEquals("暂停中 seek 不该误播", playsBefore, sink.plays)
    }

    @Test
    fun `hasPendingData 与 isEnded 互为反义的一半`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.write(frames(1000), 0L, 0)

        assertTrue(out.hasPendingData())
        assertFalse("还没宣告结束，不算播完", out.isEnded())

        sink.playedFrames = 400
        assertTrue(out.hasPendingData())

        sink.playedFrames = 1000
        assertFalse(out.hasPendingData())

        // 宣告结束 + 没 pending = 播完
        out.playToEndOfStream()
        assertTrue(out.isEnded())
    }

    @Test
    fun `playToEndOfStream 会等 pending 归零才返回`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.play() // 排空只在"要播"的状态下等进行
        out.write(frames(1000), 0L, 0)
        // 一口气把已播帧追平 → 循环立刻退出，不依赖真实时间
        sink.playedFrames = 1000
        out.playToEndOfStream()
        assertFalse(out.hasPendingData())
        assertTrue(out.isEnded())
    }

    @Test
    fun `时间戳不连续只改基点、不丢数据`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.write(frames(1000), 0L, 0)
        sink.playedFrames = 300

        out.onTimelineDiscontinuity()
        // 不连续**不该**清掉已写的 1000 帧：还要照播完
        assertTrue(out.hasPendingData())

        // 下一块重设基点（这次允许位置退一步）
        out.write(frames(100), 9_000_000L, 0)
        // 已播 300 帧相对新基点之后仍有 300 帧被算进去 —— 位置必须落在 9 s 之后，
        // 且允许比上一票（约 6.8 ms）大跳一步
        assertTrue(
            "新基点应生效：${out.positionUs(false)}",
            out.positionUs(false)!! >= 9_000_000L,
        )
    }

    @Test
    fun `开流失败时不算健康——上层据此降级`() {
        val sink = FakeDapSink(bytesPerFrame)
        sink.openResult = false
        val out = DapDirectOutput(sink, spec)
        assertFalse(out.open())
        assertFalse(out.isHealthy())
        assertEquals(C.TIME_UNSET, out.positionUs(false))
    }

    @Test
    fun `release 关流并且不再健康`() {
        val sink = FakeDapSink(bytesPerFrame)
        val out = openOutput(sink)
        out.release()
        assertEquals(1, sink.closes)
        assertFalse(out.isHealthy())
        assertFalse("流关了就不该再有 pending", out.hasPendingData())
    }

    @Test
    fun `bytesPerFrame 为 0 的 spec 一律不写——私有 format 的失败点`() {
        // 这条钉住"未知 encoding 不进 AudioTrack"：每帧字节数算不出来时
        // AudioTrack 内部会除零（native SIGFPE，Java 接不住），所以必须在上层挡住。
        val sink = FakeDapSink(bytesPerFrame)
        val bad = spec.copy(bytesPerFrame = 0)
        val out = DapDirectOutput(sink, bad)
        out.open()
        val buf = frames(10)
        assertFalse(out.write(buf, 0L, 0))
        assertEquals(0, buf.position())
    }
}
