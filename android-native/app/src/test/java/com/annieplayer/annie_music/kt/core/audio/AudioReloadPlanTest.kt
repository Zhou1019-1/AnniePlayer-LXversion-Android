package com.annieplayer.annie_music.kt.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「应用通路 / 计划变更」的动作判定与出口校验。
 *
 * 这两组判据是本次修复的全部决策逻辑，必须能离线验：真机上"该换引擎却原地换源"
 * 的表现是**完全静默**（界面正常、一个字节不出声），只有在这里穷举才看得见。
 */
class AudioReloadPlanTest {

    // ── 动作判定 ──

    @Test
    fun `通路变更一律换引擎`() {
        assertEquals(
            ReloadAction.rebuildEngine,
            reloadActionFor(pathChanged = true, planChanged = false, engineSuspect = false, hasSource = true),
        )
        assertEquals(
            ReloadAction.rebuildEngine,
            reloadActionFor(pathChanged = true, planChanged = true, engineSuspect = true, hasSource = true),
        )
    }

    @Test
    fun `引擎不可信一律换引擎 —— 卡住的播放线程不会自己好`() {
        assertEquals(
            ReloadAction.rebuildEngine,
            reloadActionFor(pathChanged = false, planChanged = true, engineSuspect = true, hasSource = true),
        )
    }

    @Test
    fun `只是计划变了就原地换源`() {
        assertEquals(
            ReloadAction.swapInPlace,
            reloadActionFor(pathChanged = false, planChanged = true, engineSuspect = false, hasSource = true),
        )
    }

    @Test
    fun `没有源就什么都不做 —— 直链没解析出来时换源没有对象`() {
        assertEquals(
            ReloadAction.none,
            reloadActionFor(pathChanged = true, planChanged = true, engineSuspect = true, hasSource = false),
        )
    }

    @Test
    fun `通路变更的优先级高于计划变更`() {
        // 穷举四态，确保"通路变更"永远落在换引擎上
        for (plan in listOf(true, false)) {
            for (suspect in listOf(true, false)) {
                assertEquals(
                    "path=true plan=$plan suspect=$suspect",
                    ReloadAction.rebuildEngine,
                    reloadActionFor(pathChanged = true, planChanged = plan, engineSuspect = suspect, hasSource = true),
                )
            }
        }
    }

    // ── 出口校验 ──

    @Test
    fun `出口计数动了就算成功`() {
        assertEquals(
            ConfigureVerdict.confirmed,
            configureVerdict(elapsedMs = 10, timeoutMs = 1200, configureCountBefore = 7, configureCountNow = 8),
        )
    }

    @Test
    fun `没到超时就继续等`() {
        assertEquals(
            ConfigureVerdict.pending,
            configureVerdict(elapsedMs = 400, timeoutMs = 1200, configureCountBefore = 7, configureCountNow = 7),
        )
    }

    @Test
    fun `超时且计数没动就升级成换引擎`() {
        assertEquals(
            ConfigureVerdict.escalate,
            configureVerdict(elapsedMs = 1200, timeoutMs = 1200, configureCountBefore = 7, configureCountNow = 7),
        )
    }

    @Test
    fun `超时但计数动了仍然算成功 —— 慢到超时也好过白换一条引擎`() {
        assertEquals(
            ConfigureVerdict.confirmed,
            configureVerdict(elapsedMs = 5000, timeoutMs = 1200, configureCountBefore = 7, configureCountNow = 9),
        )
    }

    @Test
    fun `一次 configure 都没发生过时同样能判成功`() {
        assertEquals(
            ConfigureVerdict.confirmed,
            configureVerdict(elapsedMs = 5, timeoutMs = 1200, configureCountBefore = 0, configureCountNow = 1),
        )
    }

    // ── 引擎可信度的时间窗 ──

    @Test
    fun `从未超时过就不怀疑引擎`() {
        assertFalse(engineSuspectAt(stalledAtMs = 0L, nowMs = 1_000_000L, windowMs = 20_000L))
    }

    @Test
    fun `窗口内的超时算不可信`() {
        assertTrue(engineSuspectAt(stalledAtMs = 100_000L, nowMs = 105_000L, windowMs = 20_000L))
    }

    @Test
    fun `窗口外的超时已经不该再拖住新意图`() {
        assertFalse(engineSuspectAt(stalledAtMs = 100_000L, nowMs = 130_000L, windowMs = 20_000L))
    }
}
