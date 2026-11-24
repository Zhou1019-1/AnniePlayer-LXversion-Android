package com.annieplayer.annie_music.kt.core.audio

data class PlaybackPolicy(
    val usbExclusive: Boolean = false,
    val aaudioExclusive: Boolean = false,
    val toggles: OutputToggles = OutputToggles(),
    val tuningEngine: TuningEngine = TuningEngine.graphic,
) {
    val exclusiveSink: Boolean get() = usbExclusive || aaudioExclusive

    val mode: AudioOutputMode
        get() = audioModeOf(usbExclusive, toggles, engine = tuningEngine)

    fun modeAt(bluetooth: Boolean): AudioOutputMode =
        audioModeOf(usbExclusive, toggles, bluetooth = bluetooth, engine = tuningEngine)

    fun aaudioReady(caps: DeviceCapabilities, rate: Int): Boolean =
        aaudioExclusiveAllowed(aaudioExclusive, caps.aaudioExclusiveAt(rate))
}

fun aaudioExclusiveAllowed(enabled: Boolean, probedAvailable: Boolean?): Boolean =
    enabled && probedAvailable == true
