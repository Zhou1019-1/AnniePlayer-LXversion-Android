package com.annieplayer.annie_music.kt.core.audio

/**
 * **自方动作登记** —— 我们自己打开/关闭音频流、或改厂商 HAL 全局状态时留一个时间戳。
 *
 * ## 为什么需要它
 *
 * 我们自己的动作会让系统看到"输出设备增删"，于是 `onDevicesChanged` 被回调，
 * 而它的处理是"能力全部作废 + 重探 + 重建音源"。重探本身又会开关一批试探轨，
 * 于是又产生设备事件 —— **正反馈环**。
 *
 * 实测过三次，每次表现都不同：
 * - USB claim/release → 插着小尾巴却一直不生效；
 * - 内置 DAC 那条 DIRECT 流 → 切模式后必须重启 App；
 * - 探测矩阵（一次开二十来条轨）→ **启动即卡死**，logcat 里 `PlayerBase` 两个线程不停
 *   创建/析构。
 *
 * 所以判据统一收在这里：只要"最近 N 毫秒内我们自己动过音频设备"，设备回调就按
 * 自方回声处理（延迟再评估，不做重探与重建）。
 *
 * ## 纪律
 *
 * 每个会改变设备拓扑或厂商 HAL 全局状态的操作，**进入时**调 [mark]。
 * 宁可多标（最多让一次真实拔插晚 2 秒生效），也不要漏标（那会变成死循环）。
 */
internal object AudioSelfAction {

    /** 判定窗口：探测矩阵一轮约 1~2 s，取 3 s 留足余量 */
    const val WINDOW_MS: Long = 3_000L

    @Volatile
    private var lastAt = 0L

    /** 标记"我方刚动过音频设备" */
    fun mark() {
        lastAt = android.os.SystemClock.elapsedRealtime()
    }

    /** 最近 [windowMs] 毫秒内我方动过设备吗 */
    fun recent(windowMs: Long = WINDOW_MS): Boolean {
        val last = lastAt
        if (last == 0L) return false
        val dt = android.os.SystemClock.elapsedRealtime() - last
        return dt in 0..windowMs
    }
}
