package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputMatrixTest {

    @Test
    fun `raw DSD 一票否决调音`() {
        assertFalse(tuningEnabled(exclusiveSink = false, OutputToggles(), rawDsd = true))
        assertFalse(tuningEnabled(exclusiveSink = true, OutputToggles(), rawDsd = true))
    }

    @Test
    fun `引擎选无时一律不调音`() {
        assertFalse(tuningEnabled(false, OutputToggles(), engine = TuningEngine.none))
        assertFalse(
            tuningEnabled(true, OutputToggles(bitPerfect = false), engine = TuningEngine.none),
        )
    }

    @Test
    fun `蓝牙下恒启用调音`() {
        assertTrue(tuningEnabled(exclusiveSink = true, OutputToggles(), bluetooth = true))
        assertTrue(tuningEnabled(exclusiveSink = false, OutputToggles(), bluetooth = true))
    }

    @Test
    fun `独占 sink 下 Bit Perfect 关掉才允许调音`() {
        assertFalse(tuningEnabled(exclusiveSink = true, OutputToggles(bitPerfect = true)))
        assertTrue(tuningEnabled(exclusiveSink = true, OutputToggles(bitPerfect = false)))
    }

    @Test
    fun `非独占 sink 总是允许调音`() {
        assertTrue(tuningEnabled(exclusiveSink = false, OutputToggles(bitPerfect = true)))
        assertTrue(tuningEnabled(exclusiveSink = false, OutputToggles(bitPerfect = false)))
    }

    @Test
    fun `audioModeOf 与 tuningEnabled 同源`() {
        assertEquals(AudioOutputMode.transparent, audioModeOf(true, OutputToggles(bitPerfect = true)))
        assertEquals(AudioOutputMode.tuned, audioModeOf(true, OutputToggles(bitPerfect = false)))
        assertEquals(AudioOutputMode.tuned, audioModeOf(false, OutputToggles(bitPerfect = true)))
        assertEquals(
            AudioOutputMode.transparent,
            audioModeOf(true, OutputToggles(), engine = TuningEngine.none),
        )
    }

    @Test
    fun `按源率只对非独占 sink 生效`() {
        assertTrue(followSourceRateActive(exclusiveSink = false, followSourceRate = true))
        assertFalse(followSourceRateActive(exclusiveSink = false, followSourceRate = false))
        assertFalse(followSourceRateActive(exclusiveSink = true, followSourceRate = true))
    }

    @Test
    fun `老存档只有 usb-direct 迁成开`() {
        assertTrue(migrateLegacySettings(null, "usb-direct").first)
        assertFalse(migrateLegacySettings(null, "system-mix").first)
        assertFalse(migrateLegacySettings(null, "dap-direct").first)
        assertFalse(migrateLegacySettings(null, "aaudio-exclusive").first)
        assertFalse(migrateLegacySettings(null, null).first)
    }
}
