package com.annieplayer.annie_music.kt.core.audio

internal data class AaudioProbeResult(
    val exclusive: Boolean,
    val sampleRate: Int,
    val depth: Int,
    val lowLatency: Boolean,
    val hardwareFormat: Int = 0,
    val unknownMask: Int = 0,
    val errorTag: String? = null,
) {
    val sampleRateKnown: Boolean get() = (unknownMask and AAUDIO_UNKNOWN_RATE) == 0
    val formatKnown: Boolean get() = (unknownMask and AAUDIO_UNKNOWN_FORMAT) == 0
    val hardwareKnown: Boolean get() = (unknownMask and AAUDIO_UNKNOWN_HW_FORMAT) == 0
}

internal fun parseAaudioProbe(raw: String?): AaudioProbeResult? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.startsWith("err:")) {
        val tag = text.removePrefix("err:").trim().ifEmpty { "unknown" }
        return AaudioProbeResult(
            exclusive = false,
            sampleRate = 0,
            depth = 0,
            lowLatency = false,
            unknownMask = AAUDIO_UNKNOWN_RATE or AAUDIO_UNKNOWN_FORMAT or AAUDIO_UNKNOWN_HW_FORMAT,
            errorTag = tag,
        )
    }
    val parts = text.split('/')
    if (parts.size < 4) return null
    val rate = parts[1].toIntOrNull() ?: return null
    val depth = parts[2].toIntOrNull() ?: return null
    val tail = parts.drop(4)
    val hardware = tail.firstOrNull { it.startsWith("hw=") }
        ?.removePrefix("hw=")
        ?.toIntOrNull()
        ?: 0
    val declared = tail.firstOrNull { it.startsWith("unk=") }
        ?.removePrefix("unk=")
        ?.toIntOrNull()
        ?: 0
    val derived = (if (rate > 0) 0 else AAUDIO_UNKNOWN_RATE) or
        (if (depth > 0) 0 else AAUDIO_UNKNOWN_FORMAT) or
        (if (hardware > 0) 0 else AAUDIO_UNKNOWN_HW_FORMAT)
    return AaudioProbeResult(
        exclusive = parts[0] == "EXCLUSIVE",
        sampleRate = rate,
        depth = depth,
        lowLatency = parts[3] == "LOW_LATENCY",
        hardwareFormat = hardware,
        unknownMask = declared or derived,
    )
}

internal fun AaudioProbeResult.bitExactFor(requestedFormat: Int): Boolean =
    exclusive && (hardwareFormat == 0 || hardwareFormat == requestedFormat ||
        (requestedFormat == AAUDIO_FORMAT_I24 && hardwareFormat == AAUDIO_FORMAT_I32))

internal const val AAUDIO_FORMAT_I16: Int = 1
internal const val AAUDIO_FORMAT_FLOAT: Int = 2
internal const val AAUDIO_FORMAT_I24: Int = 3
internal const val AAUDIO_FORMAT_I32: Int = 4

internal const val AAUDIO_UNKNOWN_RATE: Int = 1
internal const val AAUDIO_UNKNOWN_FORMAT: Int = 2
internal const val AAUDIO_UNKNOWN_HW_FORMAT: Int = 4

internal const val AAUDIO_OPENS_PER_CANDIDATE: Int = 4
internal const val AAUDIO_OPENS_PER_CONFIGURE: Int = 8

internal fun aaudioFramesPerCallback(sampleRate: Int): Int {
    if (sampleRate <= 0) return 128
    val doubled = (sampleRate + 999) / 1000 * 2
    return doubled.coerceIn(128, 1024)
}

internal fun aaudioBufferCapacityFrames(sampleRate: Int): Int =
    aaudioFramesPerCallback(sampleRate) * 4

internal fun aaudioOpenRetryDelayMs(attempt: Int): Long = when {
    attempt <= 0 -> 0L
    attempt == 1 -> 60L
    else -> 180L
}

internal fun exclusiveSummaryText(perRate: List<Pair<Int, Boolean?>>): String {
    val usable = perRate.filter { it.second == true }
    val unusable = perRate.filter { it.second == false }
    val unknown = perRate.filter { it.second == null }
    if (usable.isEmpty() && unusable.isEmpty()) return "未探测"
    return buildList {
        if (usable.isNotEmpty()) {
            add("可用 ${usable.joinToString(" / ") { khzText(it.first) }}")
        }
        if (unusable.isNotEmpty()) {
            add("不可用 ${unusable.joinToString(" / ") { khzText(it.first) }}")
        }
        if (unknown.isNotEmpty()) {
            add("未测 ${unknown.joinToString(" / ") { khzText(it.first) }}")
        }
    }.joinToString(" · ")
}
