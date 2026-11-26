package com.annieplayer.annie_music.kt.core.platform

const val STANDBY_BUCKET_RARE = 40
const val STANDBY_BUCKET_RESTRICTED = 45

private const val DAY_MILLIS = 86_400_000L

data class FreezeStats(
    val dayKey: Long,
    val count: Int,
    val totalMillis: Long,
    val lastMillis: Long,
) {
    companion object {
        val EMPTY = FreezeStats(dayKey = 0L, count = 0, totalMillis = 0L, lastMillis = 0L)
    }
}

data class FreezeReport(val frozenMillis: Long, val sleepMillis: Long)

data class BackgroundState(
    val dozeExempt: Boolean?,
    val standbyBucket: Int?,
    val backgroundOpsAllowed: Boolean?,
    val freeze: FreezeStats? = null,
    val oemPanelRequired: Boolean = false,
) {
    val bucketRestricted: Boolean
        get() = standbyBucket != null && standbyBucket >= STANDBY_BUCKET_RARE

    val opsBlocked: Boolean
        get() = backgroundOpsAllowed == false

    val unrestricted: Boolean
        get() = dozeExempt == true && !bucketRestricted && !opsBlocked

    val unknown: Boolean
        get() = dozeExempt == null && standbyBucket == null && backgroundOpsAllowed == null

    fun describe(nowMillis: Long = System.currentTimeMillis()): String {
        if (unknown) return "检查中..."
        val head = when {
            opsBlocked -> "后台运行权限被禁"
            bucketRestricted -> "系统把后台判为受限"
            dozeExempt != true -> if (oemPanelRequired) {
                "未进白名单 · 系统耗电管理需设无限制"
            } else {
                "未加入 Doze 白名单"
            }
            oemPanelRequired -> "白名单已加 · 系统耗电管理需设无限制"
            else -> "Doze 白名单已加"
        }
        val stats = freeze
        val frozenToday = stats != null &&
            stats.dayKey == dayKeyOf(nowMillis) &&
            stats.count > 0
        if (!frozenToday && oemPanelRequired) return head
        return "$head · ${freezeTail(stats, nowMillis)}"
    }
}

fun dayKeyOf(nowMillis: Long): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(nowMillis).toLong()
    return Math.floorDiv(nowMillis + offset, DAY_MILLIS)
}

fun freezeReport(
    expectedMillis: Long,
    uptimeDeltaMillis: Long,
    elapsedDeltaMillis: Long,
    toleranceMillis: Long = 1_500L,
): FreezeReport {
    val uptimeDelta = uptimeDeltaMillis.coerceAtLeast(0L)
    val elapsedDelta = elapsedDeltaMillis.coerceAtLeast(0L)
    val sleep = (elapsedDelta - uptimeDelta - toleranceMillis).coerceAtLeast(0L)
    val frozen = (uptimeDelta - expectedMillis - toleranceMillis).coerceAtLeast(0L)
    return FreezeReport(frozenMillis = frozen, sleepMillis = sleep)
}

fun FreezeStats.merge(
    report: FreezeReport,
    nowMillis: Long,
    minFrozenMillis: Long = 5_000L,
): FreezeStats {
    val key = dayKeyOf(nowMillis)
    val base = if (dayKey == key) this else FreezeStats.EMPTY.copy(dayKey = key)
    if (report.frozenMillis < minFrozenMillis) return base
    return base.copy(
        count = base.count + 1,
        totalMillis = base.totalMillis + report.frozenMillis,
        lastMillis = nowMillis,
    )
}

fun freezeTail(stats: FreezeStats?, nowMillis: Long): String {
    if (stats == null) return "冻结记录未采集"
    if (stats.count == 0 || stats.dayKey != dayKeyOf(nowMillis)) return "今日无冻结记录"
    val minutes = (stats.totalMillis + 59_999L) / 60_000L
    return if (minutes <= 0L) {
        "今日被冻结 ${stats.count} 次"
    } else {
        "今日被冻结 ${stats.count} 次 · 共 $minutes 分钟"
    }
}
