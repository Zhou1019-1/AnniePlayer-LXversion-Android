package com.annieplayer.annie_music.kt.core.audio

enum class SinkKind(val key: String, val label: String) {
    usbUac("usb-direct", "USB 独占"),
    dsdDirect("dap-direct", "内置 DAC"),
    oboe("aaudio-exclusive", "AAudio 独占"),
    audioTrack("system-mix", "系统 · AudioTrack"),
}

data class SinkRequest(
    val route: AudioRoute,
    val usbExclusive: Boolean,
    val rawFrames: Boolean,
    val dsdNativeAvailable: Boolean,
    val aaudioExclusiveAvailable: Boolean,
    val dopFrames: Boolean = false,
)

fun resolveSinkChain(request: SinkRequest): List<SinkKind> = when {
    request.route == AudioRoute.bluetooth -> listOf(SinkKind.audioTrack)

    request.usbExclusive && request.route == AudioRoute.usb -> if (request.rawFrames) {
        listOf(SinkKind.usbUac)
    } else {
        listOf(SinkKind.usbUac, SinkKind.oboe, SinkKind.audioTrack)
    }

    request.rawFrames && request.dopFrames && request.aaudioExclusiveAvailable ->
        listOf(SinkKind.oboe)

    request.rawFrames && request.dsdNativeAvailable -> listOf(SinkKind.dsdDirect)

    request.rawFrames -> emptyList()

    request.aaudioExclusiveAvailable -> listOf(SinkKind.oboe, SinkKind.audioTrack)

    else -> listOf(SinkKind.audioTrack)
}
