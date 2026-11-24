package com.annieplayer.annie_music.kt.core.audio

data class OutputToggles(
    val followSourceRate: Boolean = false,
    val bitPerfect: Boolean = true,
)

fun tuningEnabled(
    exclusiveSink: Boolean,
    toggles: OutputToggles,
    bluetooth: Boolean = false,
    engine: TuningEngine = TuningEngine.graphic,
    rawDsd: Boolean = false,
): Boolean {
    if (rawDsd) return false
    if (engine == TuningEngine.none) return false
    if (bluetooth) return true
    return !exclusiveSink || !toggles.bitPerfect
}

fun audioModeOf(
    exclusiveSink: Boolean,
    toggles: OutputToggles,
    bluetooth: Boolean = false,
    engine: TuningEngine = TuningEngine.graphic,
): AudioOutputMode = if (tuningEnabled(exclusiveSink, toggles, bluetooth, engine)) {
    AudioOutputMode.tuned
} else {
    AudioOutputMode.transparent
}

fun followSourceRateActive(
    exclusiveSink: Boolean,
    followSourceRate: Boolean,
): Boolean = !exclusiveSink && followSourceRate

fun migrateLegacySettings(
    legacyModeKey: String?,
    legacyPathKey: String?,
): Pair<Boolean, OutputToggles> = (legacyPathKey == "usb-direct") to OutputToggles(
    followSourceRate = false,
    bitPerfect = true,
)
