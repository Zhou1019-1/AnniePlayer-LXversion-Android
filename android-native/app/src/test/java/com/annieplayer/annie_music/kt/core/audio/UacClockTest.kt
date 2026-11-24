package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴状态机的回归测试。
 *
 * 这一组字段在真机上错过三次，且每次都是**时机**问题：
 * 零点取在 flush 之前/之后、排空期间位置要不要涨、不连续时允许不允许退一步。
 * 所以断言直接按"时机"写，而不是按数值写。
 *
 * ⚠️ `UacClock` 是 USB 独占与内置 DAC **共用**的。2026-11-15 一次"差分域"改动
 * （把绝对时间戳减成相对）让 USB 独占的位置彻底不走，已整体回滚 ——
 * 下面这些断言就是那次回滚的护栏。
 */
class UacClockTest {

    @Test
    fun `没有收到数据之前不报位置`() {
        val clock = UacClock()
        assertNull(clock.positionUs(playedUs = 0L))
        assertNull(clock.positionUs(playedUs = 1_000_000L))
    }

    @Test
    fun `第一块立零点，位置 = 零点 + 已播`() {
        val clock = UacClock()
        assertTrue(clock.onWritten(ptsUs = 10_000_000L))
        assertEquals(10_000_000L, clock.positionUs(playedUs = 0L))
        assertEquals(11_000_000L, clock.positionUs(playedUs = 1_000_000L))
    }

    @Test
    fun `只有第一块返回 true`() {
        val clock = UacClock()
        assertTrue(clock.onWritten(ptsUs = 0L))
        assertEquals(false, clock.onWritten(ptsUs = 100_000L))
    }

    @Test
    fun `位置单调：已播回退（读数抖动）不会让位置倒退`() {
        val clock = UacClock()
        clock.onWritten(ptsUs = 0L)
        assertEquals(2_000_000L, clock.positionUs(playedUs = 2_000_000L))
        assertEquals(2_000_000L, clock.positionUs(playedUs = 1_500_000L))
    }

    @Test
    fun `不连续之后允许退一步，且只允许一次`() {
        val clock = UacClock()
        clock.onWritten(ptsUs = 60_000_000L)
        assertEquals(60_000_000L, clock.positionUs(playedUs = 0L))

        // 时间轴往前跳：基点被重设到 0
        clock.onDiscontinuity(nextBaseUs = 0L)
        assertEquals(0L, clock.positionUs(playedUs = 0L))
        // 后退的许可已经用掉：下一次仍然单调
        assertEquals(500_000L, clock.positionUs(playedUs = 500_000L))
    }

    @Test
    fun `不连续不清已播、不宣告结束`() {
        val clock = UacClock()
        clock.onWritten(ptsUs = 0L)
        clock.onDiscontinuity(nextBaseUs = 5_000_000L)
        assertEquals(false, clock.ended)
        // 基点仍可用，位置从新基点算
        assertEquals(5_000_000L, clock.positionUs(playedUs = 0L))
    }

    @Test
    fun `复位回到初始态，位置重新从零点算`() {
        val clock = UacClock()
        clock.onWritten(ptsUs = 1_000_000L)
        clock.positionUs(playedUs = 3_000_000L)
        clock.markEnded()
        clock.reset()

        assertEquals(false, clock.ended)
        assertEquals(null, clock.baseUs)
        assertNull(clock.positionUs(playedUs = 0L))

        clock.onWritten(ptsUs = 0L)
        assertEquals(0L, clock.positionUs(playedUs = 0L))
    }
}
