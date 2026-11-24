package com.annieplayer.annie_music.kt.core.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionTargetTest {

    private val t = PositionTarget()

    @Test
    fun `没有目标时直接显示引擎位置`() {
        assertEquals(0.0, t.display, 1e-9)
        assertEquals(42.5, t.onEvent(42.5, 0L), 1e-9)
        assertFalse(t.pending)
    }

    @Test
    fun `目标未落地时显示目标而不是引擎位置`() {
        t.begin(83.0, nowMs = 0L, windowMs = 2_000L)
        assertEquals(83.0, t.onEvent(0.0, 100L), 1e-9)
        assertEquals(83.0, t.onEvent(1.2, 200L), 1e-9)
        assertTrue(t.pending)
    }

    @Test
    fun `位置落到目标附近即交回引擎`() {
        t.begin(83.0, nowMs = 0L, windowMs = 10_000L)
        assertEquals(83.0, t.onEvent(0.0, 100L), 1e-9)
        assertEquals(83.2, t.onEvent(83.2, 400L), 1e-9)
        assertFalse(t.pending)
        assertEquals(90.0, t.onEvent(90.0, 500L), 1e-9)
    }

    @Test
    fun `窗口到期后交回引擎`() {
        t.begin(83.0, nowMs = 0L, windowMs = 2_000L)
        assertEquals(83.0, t.onEvent(0.0, 1_999L), 1e-9)
        assertEquals(0.1, t.onEvent(0.1, 2_000L), 1e-9)
        assertFalse(t.pending)
    }

    @Test
    fun `切歌把目标归零`() {
        t.onEvent(44.0, 0L)
        t.begin(0.0, nowMs = 100L, windowMs = 3_000L)
        assertEquals(0.0, t.onEvent(44.2, 200L), 1e-9)
        assertTrue(t.pending)
        assertEquals(0.1, t.onEvent(0.1, 300L), 1e-9)
        assertFalse(t.pending)
    }

    @Test
    fun `重置后不再覆盖引擎位置`() {
        t.begin(120.0, nowMs = 0L, windowMs = 5_000L)
        t.reset()
        assertEquals(30.0, t.onEvent(30.0, 100L), 1e-9)
        assertFalse(t.pending)
    }

    @Test
    fun `目标为负按 0 处理`() {
        t.begin(-5.0, nowMs = 0L, windowMs = 1_000L)
        assertEquals(0.0, t.onEvent(0.0, 10L), 1e-9)
        assertFalse(t.pending)
    }
}
