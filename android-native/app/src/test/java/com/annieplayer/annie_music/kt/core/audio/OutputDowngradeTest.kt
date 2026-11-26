package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputDowngradeTest {

    @Test
    fun `只有 USB 独占才给降级提示`() {
        val n = downgradeNoticeFor(SinkKind.usbUac, SinkKind.audioTrack)
        assertEquals(DowngradeReason.UsbOpenFailed, n?.reason)
        assertEquals("USB 独占 → 系统 · AudioTrack", n?.transition)
    }

    @Test
    fun `内置 DAC 与 AAudio 的落差不再弹窗`() {
        assertNull(downgradeNoticeFor(SinkKind.dsdDirect, SinkKind.audioTrack))
        assertNull(downgradeNoticeFor(SinkKind.oboe, SinkKind.audioTrack))
    }

    @Test
    fun `意图与生效一致时没有落差`() {
        assertNull(downgradeNoticeFor(SinkKind.usbUac, SinkKind.usbUac))
        assertNull(downgradeNoticeFor(SinkKind.dsdDirect, SinkKind.dsdDirect))
    }

    @Test
    fun `非 USB 的 wanted 一律不提示`() {
        assertNull(downgradeNoticeFor(SinkKind.audioTrack, SinkKind.audioTrack))
        assertNull(downgradeNoticeFor(SinkKind.audioTrack, SinkKind.usbUac))
    }

    @Test
    fun `调用方给了具体原因时以它为准`() {
        val n = downgradeNoticeFor(
            wanted = SinkKind.usbUac,
            actual = SinkKind.audioTrack,
            reason = DowngradeReason.BluetoothTakesOver,
            detail = "a2dp",
        )
        assertEquals(DowngradeReason.BluetoothTakesOver, n?.reason)
        assertEquals("蓝牙接管了链路（a2dp）", n?.reasonText)
    }
}
