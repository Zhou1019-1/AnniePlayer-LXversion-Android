package com.annieplayer.annie_music.kt.core.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FreezeWatch {

    private const val PREFS = "freeze_watch"
    private const val KEY_DAY = "day"
    private const val KEY_COUNT = "count"
    private const val KEY_TOTAL = "total"
    private const val KEY_LAST = "last"
    private const val TICK_MILLIS = 30_000L

    private val _stats = MutableStateFlow<FreezeStats?>(null)
    val stats: StateFlow<FreezeStats?> = _stats.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastUptime = 0L
    private var lastElapsed = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        _stats.value = load(app)
        lastUptime = SystemClock.uptimeMillis()
        lastElapsed = SystemClock.elapsedRealtime()
        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    tick(app)
                    handler.postDelayed(this, TICK_MILLIS)
                }
            },
            TICK_MILLIS,
        )
    }

    private fun tick(context: Context) {
        val uptime = SystemClock.uptimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val report = freezeReport(TICK_MILLIS, uptime - lastUptime, elapsed - lastElapsed)
        lastUptime = uptime
        lastElapsed = elapsed
        val before = _stats.value
        val merged = (before ?: FreezeStats.EMPTY).merge(report, System.currentTimeMillis())
        if (merged != before) {
            _stats.value = merged
            save(context, merged)
        }
    }

    private fun load(context: Context): FreezeStats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return FreezeStats(
            dayKey = prefs.getLong(KEY_DAY, 0L),
            count = prefs.getInt(KEY_COUNT, 0),
            totalMillis = prefs.getLong(KEY_TOTAL, 0L),
            lastMillis = prefs.getLong(KEY_LAST, 0L),
        )
    }

    private fun save(context: Context, stats: FreezeStats) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_DAY, stats.dayKey)
            .putInt(KEY_COUNT, stats.count)
            .putLong(KEY_TOTAL, stats.totalMillis)
            .putLong(KEY_LAST, stats.lastMillis)
            .apply()
    }
}
