package com.annieplayer.annie_music.kt.core.audio

import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport

data class OutputRequest(
    val transport: DsdTransport,
    val rawAlt: Boolean,
    val rawFrames: Boolean,
    val transportTag: String,
    val deliveredDepth: Int = 0,
    val aaudioExclusive: Boolean = false,
    val dopFrames: Boolean = false,
    val aaudioDopExclusive: Boolean = false,
) {
    companion object {
        val Conservative = OutputRequest(
            transport = DsdTransport.pcm,
            rawAlt = false,
            rawFrames = false,
            transportTag = "",
            deliveredDepth = 0,
            aaudioExclusive = false,
            dopFrames = false,
            aaudioDopExclusive = false,
        )
    }
}
