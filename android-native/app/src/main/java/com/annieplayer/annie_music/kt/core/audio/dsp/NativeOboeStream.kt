package com.annieplayer.annie_music.kt.core.audio.dsp

internal object NativeOboeStream {

    const val FORMAT_I16: Int = 1
    const val FORMAT_FLOAT: Int = 2
    const val FORMAT_I24: Int = 3

    @Volatile
    private var _loaded: Boolean? = null

    fun available(): Boolean {
        _loaded?.let { return it }
        val ok = runCatching { System.loadLibrary("annieoboe") }.isSuccess
        _loaded = ok
        return ok
    }

    fun open(
        sampleRate: Int,
        depth: Int,
        channels: Int,
        exclusive: Boolean,
        deviceId: Int = 0,
        framesPerCallback: Int = 0,
        bufferCapacityFrames: Int = 0,
    ): Long {
        if (!available()) return 0L
        return runCatching {
            nativeOpen(
                sampleRate,
                depth,
                channels,
                exclusive,
                deviceId,
                framesPerCallback,
                bufferCapacityFrames,
            )
        }.getOrDefault(0L)
    }

    fun lastError(): Int = if (available()) runCatching { nativeLastError() }.getOrDefault(0) else -1

    fun write(handle: Long, data: ByteArray, byteOffset: Int, byteCount: Int, frameBytes: Int): Int {
        if (handle == 0L || byteCount <= 0 || frameBytes <= 0) return 0
        return runCatching { nativeWrite(handle, data, byteOffset, byteCount, frameBytes) }
            .getOrDefault(-1)
    }

    fun pause(handle: Long) {
        if (handle != 0L) runCatching { nativePause(handle) }
    }

    fun resume(handle: Long) {
        if (handle != 0L) runCatching { nativeResume(handle) }
    }

    fun setGain(handle: Long, gain: Float) {
        if (handle == 0L) return
        val q16 = (gain.coerceIn(0f, 1f) * 65536f).toInt()
        runCatching { nativeSetGain(handle, q16) }
    }

    fun flush(handle: Long) {
        if (handle != 0L) runCatching { nativeFlush(handle) }
    }

    fun close(handle: Long) {
        if (handle != 0L) runCatching { nativeClose(handle) }
    }

    fun timestamp(handle: Long): LongArray? {
        if (handle == 0L) return null
        val out = LongArray(2)
        val ok = runCatching { nativeTimestamp(handle, out) }.getOrDefault(false)
        return if (ok) out else null
    }

    fun framesWritten(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeFramesWritten(handle) }.getOrDefault(0L)

    fun sharingMode(handle: Long): Int =
        if (handle == 0L) -1 else runCatching { nativeSharingMode(handle) }.getOrDefault(-1)

    fun sampleRate(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeSampleRate(handle) }.getOrDefault(0)

    fun format(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeFormat(handle) }.getOrDefault(0)

    fun framesRead(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeFramesRead(handle) }.getOrDefault(0L)

    fun bufferCapacity(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeBufferCapacity(handle) }.getOrDefault(0)

    fun bufferSize(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeBufferSize(handle) }.getOrDefault(0)

    fun streamState(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeStreamState(handle) }.getOrDefault(0)

    fun audioApi(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeAudioApi(handle) }.getOrDefault(0)

    fun hardwareFormat(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeHardwareFormat(handle) }.getOrDefault(0)

    fun hardwareSampleRate(handle: Long): Int =
        if (handle == 0L) 0 else runCatching { nativeHardwareSampleRate(handle) }.getOrDefault(0)

    fun probeExclusive(sampleRate: Int, pcmFormat: Int): String {
        if (!available() || sampleRate <= 0) return "err:lib"
        return runCatching { nativeProbeExclusive(sampleRate, pcmFormat) }.getOrNull() ?: "err:jni"
    }

    fun underrunCount(handle: Long): Long =
        if (handle == 0L) 0L else runCatching { nativeUnderrunCount(handle) }.getOrDefault(0L)

    private external fun nativeOpen(
        sampleRate: Int,
        depth: Int,
        channels: Int,
        exclusive: Boolean,
        deviceId: Int,
        framesPerCallback: Int,
        bufferCapacityFrames: Int,
    ): Long

    private external fun nativeLastError(): Int

    private external fun nativeWrite(
        handle: Long,
        data: ByteArray,
        byteOffset: Int,
        byteCount: Int,
        frameBytes: Int,
    ): Int

    private external fun nativePause(handle: Long)
    private external fun nativeResume(handle: Long)
    private external fun nativeSetGain(handle: Long, gainQ16: Int)
    private external fun nativeFlush(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativeTimestamp(handle: Long, out: LongArray): Boolean
    private external fun nativeFramesWritten(handle: Long): Long
    private external fun nativeSharingMode(handle: Long): Int
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFormat(handle: Long): Int
    private external fun nativeFramesRead(handle: Long): Long
    private external fun nativeBufferCapacity(handle: Long): Int
    private external fun nativeBufferSize(handle: Long): Int
    private external fun nativeStreamState(handle: Long): Int
    private external fun nativeAudioApi(handle: Long): Int
    private external fun nativeHardwareFormat(handle: Long): Int
    private external fun nativeHardwareSampleRate(handle: Long): Int
    private external fun nativeProbeExclusive(sampleRate: Int, pcmFormat: Int): String?
    private external fun nativeUnderrunCount(handle: Long): Long
}
