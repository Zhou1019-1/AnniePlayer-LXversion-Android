package com.annieplayer.annie_music.kt.core.audio

/**
 * 独占通路的**时间轴状态机**：零点、第一块、位置、结束。
 *
 * 从 [ExclusiveOutput] 里剥出来的原因：这一组字段错了三次，每次都是**时机**问题
 * （零点取在 flush 之前/之后、位置在排空期间要不要涨、不连续时允许不允许退）。
 * 时机只在这里判断，外面拿不到这些字段，就不可能在别处再写一半。
 *
 * 不认识 Media3、不碰 native、不碰 Android：帧 → 微秒的换算由调用方做完再喂进来，
 * 所以可以直接单测（见 `UacClockTest`）。
 *
 * ⚠️ **这个类是 USB 独占（`ExclusiveOutput`）与内置 DAC（`DapDirectOutput`）共用的。**
 * 2026-11-15 曾在这里加过"差分域"（报位置时减掉开流第一个时间戳以消掉设备常量偏移），
 * 结果是**USB 独占的位置直接不走了**（它本来靠 `base + played` 与 Media3 的时间轴对齐）。
 * 已整体回滚。要处理内置 DAC 的位置偏移，必须在**那条通路自己身上**做，不许动这里。
 */
internal class UacClock {

    /** 时间轴零点（本段首块缓冲区的时间戳）；`null` = 还没建立 */
    var baseUs: Long? = null
        private set

    /** 本段是否已经收到过数据（位置能不能报的前提） */
    var firstWriteDone: Boolean = false
        private set

    /** 上游是否已宣告结束（`isEnded` 的一半） */
    var ended: Boolean = false
        private set

    /** 上一次对外报出去的位置（单调，除非遇到时间戳跳变） */
    private var reportedUs: Long = 0L

    private var allowBackwards: Boolean = false

    /** 全量复位：换曲 / seek / 重开流都走这里 */
    fun reset() {
        baseUs = null
        firstWriteDone = false
        ended = false
        reportedUs = 0L
        allowBackwards = false
    }

    /**
     * 时间戳跳变（不是 seek）：下一块重新立零点，并**允许位置退一步**。
     *
     * 只改基点和"允许退"这一个许可，不清已播、不宣告结束 —— 数据还要照播完。
     */
    fun onDiscontinuity(nextBaseUs: Long) {
        baseUs = nextBaseUs
        allowBackwards = true
    }

    /**
     * 收下一块数据。
     *
     * @return `true` = 这是本段第一块（调用方据此上报 `onPositionAdvancing`）
     */
    fun onWritten(ptsUs: Long): Boolean {
        if (baseUs == null) baseUs = ptsUs
        val first = !firstWriteDone
        firstWriteDone = true
        return first
    }

    fun markEnded() {
        ended = true
    }

    /**
     * 对外报的位置（微秒）；`null` = 时间轴还没建立。
     *
     * @param playedUs 设备已播出的时长（调用方按 `UacRing` 的帧数换算好）
     *
     * 单调递增，唯一例外是 [onDiscontinuity] 之后的第一票 —— 那一次必须允许退一步，
     * 否则时间轴往前跳的歌会永远停在旧位置。
     */
    fun positionUs(playedUs: Long): Long? {
        val base = baseUs ?: return null
        if (!firstWriteDone) return null
        val us = base + playedUs
        if (us > reportedUs || allowBackwards) {
            reportedUs = us
            allowBackwards = false
        }
        return reportedUs
    }
}
