package com.annieplayer.annie_music.kt.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundStateTest {

    private val now = 1_700_000_000_000L
    private val tick = 30_000L

    @Test
    fun `按时醒来说明没被冻`() {
        val report = freezeReport(tick, tick + 200L, tick + 200L)
        assertEquals(0L, report.frozenMillis)
        assertEquals(0L, report.sleepMillis)
    }

    @Test
    fun `只有 elapsed 涨说明是深睡不是冻结`() {
        val report = freezeReport(tick, tick, tick + 300_000L)
        assertEquals(0L, report.frozenMillis)
        assertEquals(298_500L, report.sleepMillis)
    }

    @Test
    fun `uptime 跟着涨说明进程被冻`() {
        val report = freezeReport(tick, tick + 300_000L, tick + 300_000L)
        assertEquals(298_500L, report.frozenMillis)
        assertEquals(0L, report.sleepMillis)
    }

    @Test
    fun `冻结与深睡同时发生两项都要算出来`() {
        val report = freezeReport(tick, tick + 60_000L, tick + 360_000L)
        assertEquals(58_500L, report.frozenMillis)
        assertEquals(298_500L, report.sleepMillis)
    }

    @Test
    fun `容差内的抖动不算冻结`() {
        val report = freezeReport(tick, tick + 1_200L, tick + 1_200L)
        assertEquals(0L, report.frozenMillis)
    }

    @Test
    fun `同一天多次冻结累加`() {
        val first = FreezeStats.EMPTY.merge(FreezeReport(30_000L, 0L), now)
        val second = first.merge(FreezeReport(90_000L, 0L), now + 60_000L)
        assertEquals(2, second.count)
        assertEquals(120_000L, second.totalMillis)
        assertEquals(dayKeyOf(now), second.dayKey)
        assertEquals(now + 60_000L, second.lastMillis)
    }

    @Test
    fun `跨天把计数清零`() {
        val stale = FreezeStats(dayKey = dayKeyOf(now) - 1L, count = 7, totalMillis = 600_000L, lastMillis = now - 86_400_000L)
        val merged = stale.merge(FreezeReport(0L, 0L), now)
        assertEquals(0, merged.count)
        assertEquals(0L, merged.totalMillis)
        assertEquals(dayKeyOf(now), merged.dayKey)
    }

    @Test
    fun `不足五秒的停顿不记账`() {
        val merged = FreezeStats.EMPTY.merge(FreezeReport(2_000L, 0L), now)
        assertEquals(0, merged.count)
    }

    @Test
    fun `三条信号都读不到时显示检查中`() {
        val state = BackgroundState(dozeExempt = null, standbyBucket = null, backgroundOpsAllowed = null)
        assertEquals("检查中...", state.describe(now))
        assertFalse(state.unrestricted)
    }

    @Test
    fun `白名单加空冻结史说的是两句事实`() {
        val state = BackgroundState(
            dozeExempt = true,
            standbyBucket = 5,
            backgroundOpsAllowed = true,
            freeze = FreezeStats(dayKey = dayKeyOf(now), count = 0, totalMillis = 0L, lastMillis = 0L),
        )
        assertTrue(state.unrestricted)
        assertEquals("Doze 白名单已加 · 今日无冻结记录", state.describe(now))
    }

    @Test
    fun `白名单在但今天被冻过要直说`() {
        val state = BackgroundState(
            dozeExempt = true,
            standbyBucket = 5,
            backgroundOpsAllowed = true,
            freeze = FreezeStats(dayKey = dayKeyOf(now), count = 2, totalMillis = 150_000L, lastMillis = now),
        )
        assertEquals("Doze 白名单已加 · 今日被冻结 2 次 · 共 3 分钟", state.describe(now))
    }

    @Test
    fun `受限桶优先于白名单说话`() {
        val state = BackgroundState(dozeExempt = true, standbyBucket = STANDBY_BUCKET_RESTRICTED, backgroundOpsAllowed = true)
        assertTrue(state.bucketRestricted)
        assertFalse(state.unrestricted)
        assertTrue(state.describe(now).startsWith("系统把后台判为受限"))
    }

    @Test
    fun `后台运行被禁时它是第一顺位`() {
        val state = BackgroundState(dozeExempt = true, standbyBucket = STANDBY_BUCKET_RESTRICTED, backgroundOpsAllowed = false)
        assertTrue(state.opsBlocked)
        assertTrue(state.describe(now).startsWith("后台运行权限被禁"))
    }

    @Test
    fun `没进白名单就说没进`() {
        val state = BackgroundState(dozeExempt = false, standbyBucket = 20, backgroundOpsAllowed = true)
        assertTrue(state.describe(now).startsWith("未加入 Doze 白名单"))
    }

    @Test
    fun `昨天的冻结不算今天的`() {
        val state = BackgroundState(
            dozeExempt = true,
            standbyBucket = 5,
            backgroundOpsAllowed = true,
            freeze = FreezeStats(dayKey = dayKeyOf(now) - 1L, count = 5, totalMillis = 900_000L, lastMillis = now),
        )
        assertEquals("Doze 白名单已加 · 今日无冻结记录", state.describe(now))
    }

    @Test
    fun `国产 ROM 上白名单不能当结论`() {
        val state = BackgroundState(
            dozeExempt = true,
            standbyBucket = 5,
            backgroundOpsAllowed = true,
            oemPanelRequired = true,
        )
        assertEquals("白名单已加 · 系统耗电管理需设无限制", state.describe(now))
    }

    @Test
    fun `国产 ROM 上没进白名单要直接指到厂家面板`() {
        val state = BackgroundState(
            dozeExempt = false,
            standbyBucket = 20,
            backgroundOpsAllowed = true,
            oemPanelRequired = true,
        )
        assertEquals("未进白名单 · 系统耗电管理需设无限制", state.describe(now))
    }

    @Test
    fun `国产 ROM 上真被冻过就把冻结数字带上`() {
        val state = BackgroundState(
            dozeExempt = true,
            standbyBucket = 5,
            backgroundOpsAllowed = true,
            freeze = FreezeStats(dayKey = dayKeyOf(now), count = 1, totalMillis = 60_000L, lastMillis = now),
            oemPanelRequired = true,
        )
        assertEquals("白名单已加 · 系统耗电管理需设无限制 · 今日被冻结 1 次 · 共 1 分钟", state.describe(now))
    }
}
