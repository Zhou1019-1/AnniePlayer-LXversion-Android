package com.annieplayer.annie_music.kt.core.audio

/**
 * 独占通路的**位置算术**：帧 → 微秒。
 *
 * 这里只剩一件事，而且是两次真机事故钉死的：**只做帧换算，不碰 offset**。
 *
 * - sink 报的位置必须待在"缓冲区时间戳"那个坐标系里。Media3 的参考实现 `DefaultAudioSink`
 *   连 `setOutputStreamOffsetUs` 都没有实现（接口默认空实现），它的时钟走
 *   `applyMediaPositionParameters`：checkpoint 里的 mediaTimeUs + 已播时长。sink 若自己
 *   再补一次 offset，Media3 在时间轴层又加减一次，误差正好是 ±1×10^12 µs ——
 *   界面 `16673:31 / 3:13`（多加）与卡在 `0:00`（少加）都是这么来的。
 * - 帧数这一侧只需要防溢出与防坏计数器。
 */
internal object ExclusiveClock {

    /** 帧换算的上限（24 h）。正常单曲远小于它，超了只可能是计数器坏了 */
    const val MAX_US: Long = 24L * 60L * 60L * 1_000_000L

    /** 已播帧 → 微秒。乘 10^6 会溢出，超上限的输入直接按上限算。 */
    fun framesToUs(frames: Long, sampleRate: Int): Long {
        if (sampleRate <= 0 || frames <= 0L) return 0L
        if (frames > Long.MAX_VALUE / 1_000_000L) return MAX_US
        return (frames * 1_000_000L / sampleRate).coerceIn(0L, MAX_US)
    }
}
