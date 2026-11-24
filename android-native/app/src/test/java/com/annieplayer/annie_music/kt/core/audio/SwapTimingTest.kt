package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归护栏：换源**必须先回到 IDLE**。
 *
 * 两条字节码事实（`javap -p -c`，Media3 1.4.1）：
 * - `ExoPlayerImpl.prepare()` 第一段是 `if (playbackState != STATE_IDLE) return;`
 * - `ExoPlayerImpl.stop()` → `stopInternal()`：`copyWithPlaybackState(STATE_IDLE)` 之后
 *   立刻 `updatePlaybackInfo(...)`，而 `updatePlaybackInfo` 的第一段就是给
 *   `this.playbackInfo` 赋值 —— 所以 `stop()` 返回时状态**已经**是 IDLE。
 *
 * 因此 `stop(); setMediaSource(); prepare()` 在同一回合里成立，不需要挂起到
 * `onPlaybackStateChanged(IDLE)`（播放线程卡死时那个回调永远不来）。
 */
class SwapTimingTest {

    @Test
    fun `IDLE 时直接换源`() {
        assertEquals(SwapStep.setAndPrepare, swapStepFor(Player.STATE_IDLE))
    }

    @Test
    fun `播放中必须先 stop`() {
        assertEquals(SwapStep.stopThenSetAndPrepare, swapStepFor(Player.STATE_READY))
    }

    @Test
    fun `缓冲中必须先 stop —— 这正是 trace 里 swap-set state=2 的那一态`() {
        assertEquals(SwapStep.stopThenSetAndPrepare, swapStepFor(Player.STATE_BUFFERING))
    }

    @Test
    fun `播完也必须先 stop`() {
        assertEquals(SwapStep.stopThenSetAndPrepare, swapStepFor(Player.STATE_ENDED))
    }
}
