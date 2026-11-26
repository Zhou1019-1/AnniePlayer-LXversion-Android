package com.annieplayer.annie_music.kt.core.audio.dap

/**
 * 音源重建的**风暴护栏**：窗口 + 计数 + 一次放行判定。
 *
 * ## 为什么独立成文件放在这里
 *
 * 判定必须能被 JVM 单测直接构造（`_reloadCurrentSource` 本身要 ExoPlayer，测不了），而
 * `MediaPlayerBackend.kt` 的顶层已经有一个 `AudioAttributes` 常量 —— 顶层常量编译进文件类的
 * `<clinit>`，从单测里碰那个文件类就会去建 Android 对象（`not mocked` 直接红）。
 * 所以纯状态与纯函数单独放：没有 Android 依赖，谁都能测。
 *
 * ## 为什么用户操作要单独清
 *
 * 护栏只该拦「**同一个原因**反复重建」。用户改通路是**新意图**，那时留下的计数属于上一条
 * 通路；拿它拦下新意图那一次重建，就是"切了通路没反应、只有重启 App 才生效"（实测）。
 * 清零是语义，不是宽容。
 *
 * @param windowStartMs 窗口起点（`SystemClock.elapsedRealtime()` 毫秒；0 = 还没有窗口）
 * @param count 本窗口内已经放行的重建次数（被拦下的那次也计入，只为日志里说清"第几次"）
 */
internal data class ReloadBurst(val windowStartMs: Long, val count: Int) {

    companion object {
        val EMPTY = ReloadBurst(0L, 0)
    }
}

/** 护栏走一拍的结果：这次能不能重建 + 必须写回的新状态 */
internal data class ReloadBurstStep(val allowed: Boolean, val state: ReloadBurst)

/** 重建风暴护栏：窗口与上限（正常操作 3 秒内最多一两次重建） */
internal const val RELOAD_BURST_WINDOW_MS: Long = 3_000L
internal const val RELOAD_BURST_LIMIT: Int = 8

/** 用户操作（改通路 = 新意图）：从这一刻起重新计数 */
internal fun reloadBurstReset(nowMs: Long): ReloadBurst = ReloadBurst(nowMs, 0)

/**
 * 走一拍护栏（纯函数）。
 *
 * - 窗口起点为 0（还没开过）或已超过 [windowMs] → 重新开窗，本次计数从 1 算起；
 * - 窗口内计数超过 [limit] → 拦下（`allowed = false`）。
 *
 * @param nowMs 单调时钟毫秒（`SystemClock.elapsedRealtime()`）
 */
internal fun reloadBurstStep(
    state: ReloadBurst,
    nowMs: Long,
    windowMs: Long = RELOAD_BURST_WINDOW_MS,
    limit: Int = RELOAD_BURST_LIMIT,
): ReloadBurstStep {
    val expired = state.windowStartMs == 0L || nowMs - state.windowStartMs > windowMs
    val base = if (expired) ReloadBurst(nowMs, 0) else state
    val next = base.copy(count = base.count + 1)
    return ReloadBurstStep(allowed = next.count <= limit, state = next)
}
