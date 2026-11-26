package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataFlowVerifyTest {

    @Test
    fun `出口没了直接算停`() {
        assertTrue(dataFlowStalled(bufferCallsBefore = 100, bufferCallsNow = 140, outletAlive = false))
    }

    @Test
    fun `有出口且样本还在来算健康`() {
        assertFalse(dataFlowStalled(bufferCallsBefore = 100, bufferCallsNow = 140, outletAlive = true))
    }

    @Test
    fun `有出口但一个样本都没来算停`() {
        assertTrue(dataFlowStalled(bufferCallsBefore = 100, bufferCallsNow = 100, outletAlive = true))
    }
}
