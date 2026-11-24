package com.annieplayer.annie_music.kt.core.audio

internal class UacRing {

    @Volatile
    var writtenFrames: Long = 0L
        private set

    private var readBase: Long = 0L

    fun rebase(readFramesNow: Long) {
        writtenFrames = 0L
        readBase = readFramesNow
    }

    fun onWritten(frames: Long) {
        writtenFrames += frames
    }

    fun playedFrames(readFramesNow: Long): Long =
        (readFramesNow - readBase).coerceAtLeast(0L)

    fun pendingFrames(playedFrames: Long): Long =
        (writtenFrames - playedFrames).coerceAtLeast(0L)

    fun inFlightFrames(sampleRate: Int): Long =
        if (sampleRate > 0) sampleRate.toLong() * IN_FLIGHT_MS / 1000L else 0L

    companion object {
        const val IN_FLIGHT_MS = 63L
    }
}
