package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutletReloadTest {

    @Test
    fun `出口率与计划不符要重建`() {
        assertTrue(outletNeedsReload(requiredRate = 96_000, requiredDepth = 32, lastConfigureNote = "48000/4/2"))
    }

    @Test
    fun `蓝牙改协商率后必须重建`() {
        assertTrue(outletNeedsReload(requiredRate = 96_000, requiredDepth = 32, lastConfigureNote = "48000/2/2"))
    }

    @Test
    fun `率与位深都对就不用重建`() {
        assertFalse(outletNeedsReload(requiredRate = 96_000, requiredDepth = 32, lastConfigureNote = "96000/4/2"))
        assertFalse(outletNeedsReload(requiredRate = 48_000, requiredDepth = 16, lastConfigureNote = "48000/2/2"))
    }

    @Test
    fun `只有位深不符也要重建`() {
        assertTrue(outletNeedsReload(requiredRate = 48_000, requiredDepth = 32, lastConfigureNote = "48000/2/2"))
    }

    @Test
    fun `按源率时要求等于源率`() {
        assertTrue(outletNeedsReload(requiredRate = 44_100, requiredDepth = 0, lastConfigureNote = "48000/4/2"))
        assertFalse(outletNeedsReload(requiredRate = 44_100, requiredDepth = 0, lastConfigureNote = "44100/2/2"))
    }

    @Test
    fun `要求率为 0 时不动`() {
        assertFalse(outletNeedsReload(requiredRate = 0, requiredDepth = 0, lastConfigureNote = "48000/4/2"))
    }

    @Test
    fun `还没 configure 过不据此重建`() {
        assertFalse(outletNeedsReload(requiredRate = 96_000, requiredDepth = 32, lastConfigureNote = ""))
    }

    @Test
    fun `编号映射`() {
        assertTrue(depthOfPcmEncoding(2) == 16)
        assertTrue(depthOfPcmEncoding(21) == 24)
        assertTrue(depthOfPcmEncoding(4) == 32)
        assertTrue(depthOfPcmEncoding(999) == 0)
    }
}
