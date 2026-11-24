package com.annieplayer.annie_music.kt.core.state

internal class PositionTarget {

    private var targetSec: Double = NO_TARGET

    private var deadlineMs: Long = 0L

    private var lastSec: Double = 0.0

    val pending: Boolean get() = targetSec >= 0.0

    val display: Double get() = if (targetSec >= 0.0) targetSec else lastSec

    fun begin(sec: Double, nowMs: Long, windowMs: Long) {
        targetSec = sec.coerceAtLeast(0.0)
        deadlineMs = nowMs + windowMs
    }

    fun onEvent(sec: Double, nowMs: Long): Double {
        lastSec = sec
        if (targetSec >= 0.0) {
            val landed = kotlin.math.abs(sec - targetSec) <= LANDED_SEC
            if (landed || nowMs >= deadlineMs) targetSec = NO_TARGET
        }
        return display
    }

    fun reset() {
        targetSec = NO_TARGET
    }

    private companion object {
        const val NO_TARGET: Double = -1.0
    }
}

internal const val LANDED_SEC: Double = 0.6

internal const val SWITCH_WINDOW_MS: Long = 3_000L

internal const val SEEK_WINDOW_MS: Long = 2_000L

internal const val RESTORE_WINDOW_MS: Long = 10_000L
