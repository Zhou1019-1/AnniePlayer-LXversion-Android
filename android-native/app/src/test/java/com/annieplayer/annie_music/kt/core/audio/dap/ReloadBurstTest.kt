package com.annieplayer.annie_music.kt.core.audio.dap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重建风暴护栏：**窗口 / 计数 / 用户操作**。
 *
 * 为什么它值得单独测：这条护栏拦的是"重建 → 判定变 → 再重建"的自激环（历史上 `cfg`
 * 涨到 1185、`_setSource` 被调 110 次）。而它自己一旦拦错了对象，症状和自激环一模一样 ——
 * 界面变了、音源没换、一个字节都不出声，区别只在"切一次通路"这一次是**必须**放行的。
 *
 * 真机证据（`audio-trace.txt` / `audio-status.txt`）：`want=system-mix eff=dap-direct cfg=1
 * cfglast=`（空）—— 说明重建确实跑进了 `_reloadCurrentSource`，但 `configure` 一次都没来。
 * 所以这里要钉住两条语义：窗口到期重新计数、**用户改通路时计数必须清零**。
 */
class ReloadBurstTest {

    private fun run(
        state: ReloadBurst,
        nowMs: Long,
        times: Int,
        windowMs: Long = RELOAD_BURST_WINDOW_MS,
        limit: Int = RELOAD_BURST_LIMIT,
    ): Pair<List<Boolean>, ReloadBurst> {
        var s = state
        val allowed = ArrayList<Boolean>(times)
        repeat(times) {
            val step = reloadBurstStep(s, nowMs, windowMs, limit)
            s = step.state
            allowed += step.allowed
        }
        return allowed to s
    }

    @Test
    fun `窗口内放行 limit 次，第 limit+1 次拦下`() {
        val (allowed, state) = run(ReloadBurst.EMPTY, nowMs = 1_000L, times = RELOAD_BURST_LIMIT + 1)
        assertTrue("前 $RELOAD_BURST_LIMIT 次必须放行", allowed.take(RELOAD_BURST_LIMIT).all { it })
        assertFalse("第 ${RELOAD_BURST_LIMIT + 1} 次必须拦下", allowed.last())
        assertEquals(RELOAD_BURST_LIMIT + 1, state.count)
        assertEquals("窗口起点不该被拦下的那次改掉", 1_000L, state.windowStartMs)
    }

    @Test
    fun `窗口过期后重新计数`() {
        val (_, saturated) = run(ReloadBurst.EMPTY, nowMs = 1_000L, times = RELOAD_BURST_LIMIT + 1)
        assertFalse(reloadBurstStep(saturated, 1_000L + RELOAD_BURST_WINDOW_MS - 1).allowed)
        val next = reloadBurstStep(saturated, 1_000L + RELOAD_BURST_WINDOW_MS + 1)
        assertTrue("窗口已过期，这一拍该放行", next.allowed)
        assertEquals("计数应从 1 重新开始", 1, next.state.count)
        assertEquals(1_000L + RELOAD_BURST_WINDOW_MS + 1, next.state.windowStartMs)
    }

    @Test
    fun `用户改通路必须能重建——护栏额度被吃光也要放行`() {
        // 上一条通路（内置 DAC）自己反复失败，把额度用光了
        val (_, saturated) = run(ReloadBurst.EMPTY, nowMs = 5_000L, times = RELOAD_BURST_LIMIT + 3)
        assertFalse("前提：此刻护栏是关着的", reloadBurstStep(saturated, 5_100L).allowed)

        // 用户切到「系统 · AudioTrack」：新意图 → 调用方先清
        val afterUserAction = reloadBurstReset(5_100L)
        val step = reloadBurstStep(afterUserAction, 5_100L)
        assertTrue("用户操作的那一次重建绝不能被上一条通路的失败次数拦下", step.allowed)
        assertEquals(1, step.state.count)
    }

    @Test
    fun `状态必须写回——不写回就等于没有护栏`() {
        // 这条钉住调用方的用法：护栏只在把 `step.state` 赋回去时才累计
        val step1 = reloadBurstStep(ReloadBurst.EMPTY, 0L)
        assertTrue(step1.allowed)
        assertEquals(1, step1.state.count)
        // 同一份旧状态反复喂进去 → 每次都从 1 算起（= 没有护栏），所以调用方必须写回
        assertEquals(1, reloadBurstStep(ReloadBurst.EMPTY, 0L).state.count)
    }
}
