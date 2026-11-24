package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 帧 → 微秒的回归测试。
 *
 * 真机上出过两次同源事故（界面 `16673:31 / 3:13`、进度条卡 `0:00`），根因都是
 * **sink 自己参与了 offset 记账**，所以现在这一层只允许有帧换算：任何"顺手把 offset
 * 加进来"的改动都必须先改这组断言，等于强制再想一遍。
 */
class ExclusiveClockTest {

    @Test
    fun `常规换算`() {
        assertEquals(1_000_000L, ExclusiveClock.framesToUs(44_100L, 44_100))
        assertEquals(1_000_000L, ExclusiveClock.framesToUs(48_000L, 48_000))
        assertEquals(4_000_000L, ExclusiveClock.framesToUs(176_400L, 44_100))
    }

    @Test
    fun `非法输入归零`() {
        assertEquals(0L, ExclusiveClock.framesToUs(44_100L, 0))
        assertEquals(0L, ExclusiveClock.framesToUs(0L, 44_100))
        assertEquals(0L, ExclusiveClock.framesToUs(-1L, 44_100))
    }

    @Test
    fun `坏计数器不翻号，按上限截断`() {
        assertEquals(ExclusiveClock.MAX_US, ExclusiveClock.framesToUs(Long.MAX_VALUE, 44_100))
        assertEquals(ExclusiveClock.MAX_US, ExclusiveClock.framesToUs(Long.MAX_VALUE / 2, 1_000))
    }
}
