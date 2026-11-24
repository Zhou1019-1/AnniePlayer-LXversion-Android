package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C

internal data class WireSpec(
    val sampleRate: Int,
    val channelCount: Int,
    val depth: Int,
    val encoding: Int,
    val bytesPerFrame: Int,
    val announceBits: Int,
    val aaudioFormat: Int,
) {
    companion object {

        fun of(
            sampleRate: Int,
            channelCount: Int,
            sourceDepth: Int,
            deliveredDepth: Int,
            oboeEngine: Boolean,
            wireBitsHint: Int,
        ): WireSpec {
            val depth = when {
                !oboeEngine -> sourceDepth
                deliveredDepth in 16..32 -> deliveredDepth
                else -> sourceDepth
            }
            return WireSpec(
                sampleRate = sampleRate,
                channelCount = channelCount,
                depth = depth,
                encoding = encodingOfDepth(depth),
                bytesPerFrame = bytesPerSample(depth) * channelCount,
                announceBits = wireBitsHint,
                aaudioFormat = aaudioFormatOf(depth),
            )
        }

        fun depthOf(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 32
        }

        fun bytesPerSample(depth: Int): Int = when (depth) {
            16 -> 2
            24 -> 3
            else -> 4
        }

        fun encodingOfDepth(depth: Int): Int = when (depth) {
            16 -> C.ENCODING_PCM_16BIT
            24 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_PCM_FLOAT
        }

        fun aaudioFormatOf(depth: Int): Int = when (depth) {
            16 -> AAUDIO_FORMAT_I16
            24 -> AAUDIO_FORMAT_I24
            else -> AAUDIO_FORMAT_FLOAT
        }

        fun depthOfAaudioFormat(format: Int): Int = when (format) {
            AAUDIO_FORMAT_I16 -> 16
            AAUDIO_FORMAT_I24 -> 24
            AAUDIO_FORMAT_I32 -> 32
            else -> 0
        }

        fun formatAcceptable(wanted: Int, actual: Int): Boolean =
            actual == 0 || actual == wanted ||
                (wanted == AAUDIO_FORMAT_I24 && actual == AAUDIO_FORMAT_I32)

        fun wireMatches(now: Int, then: Int): Boolean {
            if (now <= 0 || then <= 0) return true
            return now == then
        }
    }
}
