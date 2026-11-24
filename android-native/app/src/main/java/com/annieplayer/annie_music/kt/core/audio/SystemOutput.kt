package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
internal class SystemOutput(
    private val sink: AudioSink,
    private val deliveredUs: () -> Long,
    private val nanoTime: () -> Long = System::nanoTime,
) : OutputPort {

    override val path: SinkKind get() = SinkKind.audioTrack

    private var playing = false
    private var lastOutUs = Long.MIN_VALUE
    private var baseUs = 0L
    private var baseDeliveredUs = 0L
    private var needRebase = true
    private var ringUs = RING_FALLBACK_US
    private var wallUs = 0L
    private var wallAtNs = 0L

    override fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (needRebase) rebaseTo(presentationTimeUs)
        return sink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun positionUs(sourceEnded: Boolean): Long {
        val raw = sink.getCurrentPositionUs(sourceEnded)
        if (needRebase) {
            if (raw <= 0L) return raw
            rebaseTo(0L)
        }
        val ceilingUs = baseUs + (deliveredUs() - baseDeliveredUs)
        if (sourceEnded) {
            val held = if (lastOutUs == Long.MIN_VALUE) ceilingUs else lastOutUs
            lastOutUs = maxOf(held, ceilingUs)
            return lastOutUs
        }
        ringUs = refineRingUs(raw, ceilingUs, ringUs)
        val now = nanoTime()
        if (playing && wallAtNs != 0L) {
            wallUs += (now - wallAtNs).coerceAtLeast(0L) / 1000L
        }
        wallAtNs = now
        val out = composePositionUs(ceilingUs, ringUs, wallUs, lastOutUs)
        lastOutUs = out
        lastCorrectionMs = if (raw != 0L) (out - raw) / 1000L else 0L
        return out
    }

    private fun rebaseTo(mediaUs: Long) {
        baseUs = mediaUs
        baseDeliveredUs = deliveredUs()
        wallUs = mediaUs
        wallAtNs = nanoTime()
        needRebase = false
    }

    private fun dropClock() {
        needRebase = true
        lastOutUs = Long.MIN_VALUE
        wallAtNs = 0L
    }

    override fun play() {
        playing = true
        sink.play()
    }

    override fun pause() {
        playing = false
        sink.pause()
    }

    override fun flush(playRequested: Boolean) {
        sink.flush()
        dropClock()
        playing = playRequested
        if (playRequested) sink.play()
    }

    override fun onTimelineDiscontinuity() {
        sink.handleDiscontinuity()
    }

    override fun reset() {
        sink.reset()
        dropClock()
        playing = false
    }

    override fun release() {
        sink.release()
        dropClock()
        playing = false
    }

    override fun playToEndOfStream() {
        sink.playToEndOfStream()
    }

    override fun isEnded(): Boolean = sink.isEnded

    override fun hasPendingData(): Boolean = sink.hasPendingData()

    override fun isHealthy(): Boolean = true

    internal companion object {
        @Volatile
        var lastCorrectionMs: Long = 0L
            private set
    }
}

internal const val RING_MIN_US = 50_000L
internal const val RING_MAX_US = 2_000_000L
internal const val RING_FALLBACK_US = 500_000L

internal fun composePositionUs(
    ceilingUs: Long,
    ringUs: Long,
    wallUs: Long,
    floorUs: Long,
): Long {
    val natural = maxOf(ceilingUs - ringUs, wallUs)
    val capped = minOf(ceilingUs, natural)
    val floor = if (floorUs == Long.MIN_VALUE) 0L else floorUs
    return maxOf(floor, capped)
}

internal fun refineRingUs(rawUs: Long, ceilingUs: Long, ringUs: Long): Long {
    if (rawUs <= 0L) return ringUs
    val estimate = ceilingUs - rawUs
    if (estimate < RING_MIN_US || estimate > RING_MAX_US) return ringUs
    return (ringUs * 7 + estimate) / 8
}
