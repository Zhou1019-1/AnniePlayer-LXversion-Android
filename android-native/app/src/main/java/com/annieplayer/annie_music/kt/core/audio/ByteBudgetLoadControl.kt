package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * 按**字节预算**而不是按时间窗口管理缓冲。
 *
 * 原来前向用 45 秒、后向用 10 秒，两条都是时间。时间窗口对低码率没问题，对 PCM 直出的
 * 高倍 DSD 就是灾难：DSD512 解到 2822.4k 是 22.6 MB/s，10 秒后缓冲就是 226 MB 常驻，
 * 而 Media3 的 `Allocation.data` 是 `byte[]`，只能在 Java 堆里，256 MB 堆必然 OOM。
 *
 * 这里换成「占用字节数恒定」：
 * - 前向：[forwardBudgetBytes]，与码率无关 → 高码率自动换成更短的秒数
 * - 后向：[backBudgetBytes] 换算成秒，再用 [backBufferCapMs] 兜住低码率
 * - 起播门槛仍走父类的时间判据（1 秒），手感不变
 *
 * 码率从 `onTracksSelected` 的音轨 Format 推：能拿到 `bitrate` 就直接用，
 * 拿不到（本地 PCM 不带 bitrate）就按 `sampleRate × channelCount × 位深` 算 ——
 * PCM 是恒定码率，算出来是精确值。
 */
internal class ByteBudgetLoadControl(
    allocator: DefaultAllocator,
    private val maxBufferMsBudget: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    targetBufferBytes: Int,
    private val backBufferCapMs: Int,
    private val forwardBudgetBytes: Int,
    private val backBudgetBytes: Int,
) : DefaultLoadControl(
    allocator,
    maxBufferMsBudget,
    maxBufferMsBudget,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    targetBufferBytes,
    false,
    backBufferCapMs,
    false,
) {

    @Volatile
    private var bytesPerSecond: Double = 0.0

    override fun onTracksSelected(
        renderers: Array<out Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection>,
    ) {
        super.onTracksSelected(renderers, trackGroups, trackSelections)
        bytesPerSecond = resolveBytesPerSecond(trackSelections)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (bytesPerSecond <= 0.0) return super.shouldContinueLoading(parameters)
        return allowByBudget(parameters.bufferedDurationUs)
    }

    override fun shouldContinueLoading(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        playbackSpeed: Float,
    ): Boolean {
        if (bytesPerSecond <= 0.0) {
            return super.shouldContinueLoading(playbackPositionUs, bufferedDurationUs, playbackSpeed)
        }
        return allowByBudget(bufferedDurationUs)
    }

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = backBufferDurationUs()

    override fun getBackBufferDurationUs(): Long = backBufferDurationUs()

    private fun allowByBudget(bufferedDurationUs: Long): Boolean {
        val rate = bytesPerSecond
        if (rate <= 0.0) return bufferedDurationUs / 1000L < maxBufferMsBudget
        val bufferedBytes = bufferedDurationUs / 1_000_000.0 * rate
        if (bufferedBytes >= forwardBudgetBytes) return false
        return bufferedDurationUs / 1000L < maxBufferMsBudget
    }

    /**
     * 码率还未知时返回 **0**，不是上限。
     *
     * `getBackBufferDurationUs` 会在 `onTracksSelected` 之前被问到一次。那时若按
     * [backBufferCapMs]（10 秒）回答，DSD512（22.6 MB/s）第一次就会按 10 秒保留 ——
     * 226 MB，正好是要避免的那次 OOM。宁可先不保留，等音轨选中后按预算算。
     */
    private fun backBufferDurationUs(): Long {
        val rate = bytesPerSecond
        if (rate <= 0.0) return 0L
        val capUs = backBufferCapMs * 1000L
        val byBudgetUs = (backBudgetBytes / rate * 1_000_000.0).toLong()
        return minOf(byBudgetUs, capUs).coerceAtLeast(0L)
    }
}

internal fun resolveBytesPerSecond(selections: Array<out ExoTrackSelection>?): Double {
    if (selections == null) return 0.0
    for (selection in selections) {
        val index = selection.selectedIndex
        if (index < 0 || index >= selection.length()) continue
        val rate = bytesPerSecondOf(selection.getFormat(index))
        if (rate > 0.0) return rate
    }
    return 0.0
}

internal fun bytesPerSecondOf(format: Format): Double {
    if (format.bitrate > 0) return format.bitrate / 8.0
    return pcmBytesPerSecond(
        sampleRate = format.sampleRate,
        channelCount = format.channelCount,
        pcmEncoding = format.pcmEncoding,
    )
}

internal fun pcmBytesPerSecond(sampleRate: Int, channelCount: Int, pcmEncoding: Int): Double {
    if (sampleRate <= 0 || channelCount <= 0) return 0.0
    val bytesPerSample = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT -> 4
        C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }
    return sampleRate.toDouble() * channelCount * bytesPerSample
}
