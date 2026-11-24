package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import androidx.media3.extractor.ExtractorInput
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * [ExtractorInput.skip] 的契约是"允许少跳"，而 `DefaultExtractorInput` 实测一次只退 4096 字节。
 *
 * 只调一次 `skip(written)` 的话，`ProgressiveMediaPeriod` 的"位置前进 50 KB"闸门几乎永不触发，
 * loader 会一口气把整首 DSD 读进 `SampleQueue`（实测 169 秒 DSD64 → 120 MB）。
 */
class FfmpegLocalAdvanceInputTest {

    /** 只实现 [advanceInput] 用到的那几个方法，其余直接抛（用不到就不该被调用） */
    private class FakeInput(
        private val maxSkipPerCall: Int,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : ExtractorInput {
        var pos: Long = 0
        var skipCalls = 0

        override fun skip(length: Int): Int {
            skipCalls++
            if (skipCalls > failAfter) throw IOException("boom")
            val n = minOf(length, maxSkipPerCall)
            pos += n
            return n
        }

        override fun getPosition(): Long = pos
        override fun getLength(): Long = -1L
        override fun getPeekPosition(): Long = pos
        override fun resetPeekPosition() = Unit
        override fun read(target: ByteArray, offset: Int, length: Int): Int = 0
        override fun peek(target: ByteArray, offset: Int, length: Int): Int = 0
        override fun readFully(target: ByteArray, offset: Int, length: Int): Unit = Unit
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) = false
        override fun skipFully(length: Int): Unit = Unit
        override fun skipFully(length: Int, allowEndOfInput: Boolean) = false
        override fun peekFully(target: ByteArray, offset: Int, length: Int): Unit = Unit
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean) = false
        override fun advancePeekPosition(length: Int): Unit = Unit
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean) = false
        override fun <E : Throwable> setRetryPosition(position: Long, e: E) = Unit
    }

    @Test
    fun `少跳时补满到请求的字节数`() {
        // 真实值：每次只退 4096，而我们要求推进 512 KB
        val input = FakeInput(maxSkipPerCall = 4096)
        advanceInput(input, 512 * 1024)
        assertEquals(512L * 1024L, input.pos)
        assertEquals(128, input.skipCalls)
    }

    @Test
    fun `一次就能跳满时只调用一次`() {
        val input = FakeInput(maxSkipPerCall = Int.MAX_VALUE)
        advanceInput(input, 4096)
        assertEquals(4096L, input.pos)
        assertEquals(1, input.skipCalls)
    }

    @Test
    fun `返回 0 立即收手不空转`() {
        val input = FakeInput(maxSkipPerCall = 0)
        advanceInput(input, 4096)
        assertEquals(0L, input.pos)
        assertEquals(1, input.skipCalls)
    }

    @Test
    fun `中途 IOException 不影响已推进的位置`() {
        val input = FakeInput(maxSkipPerCall = 4096, failAfter = 2)
        advanceInput(input, 4096 * 4)
        // 前两次成功、第三次抛异常 → 位置停在 8192，且不抛出
        assertEquals(8192L, input.pos)
    }

    @Test
    fun `要求 0 字节时不动`() {
        val input = FakeInput(maxSkipPerCall = 4096)
        advanceInput(input, 0)
        assertEquals(0L, input.pos)
        assertEquals(0, input.skipCalls)
    }
}
