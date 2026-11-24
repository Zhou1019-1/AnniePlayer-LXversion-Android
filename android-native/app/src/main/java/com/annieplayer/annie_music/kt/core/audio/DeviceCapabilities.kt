package com.annieplayer.annie_music.kt.core.audio

import androidx.media3.common.C

data class DeviceCapabilities(
    val declaredEncodings: List<Int> = emptyList(),
    val aaudioExclusiveByRate: Map<Int, Boolean> = emptyMap(),
    val aaudioMatrix: Map<Int, Map<Int, Boolean>> = emptyMap(),
    val exclusiveNativeDepth: Int = 0,
    val sinkDepth: Int = 0,
    val sinkIsFloat: Boolean = false,
    val mixerDepth: Int = 0,
    val mixerIsFloat: Boolean = false,
    val supportedDepths: List<Int> = emptyList(),
    val usbStreamLine: String = "",
    val system: DirectCapabilities? = null,
) {
    val knowsDeclarations: Boolean get() = declaredEncodings.isNotEmpty()

    fun declares(encoding: Int): Boolean? =
        if (declaredEncodings.isEmpty()) null else encoding in declaredEncodings

    fun aaudioExclusiveAt(rate: Int): Boolean? =
        if (rate <= 0) null else aaudioExclusiveByRate[rate]

    companion object {

        fun depthFrom(declared: List<Int>): Int = when {
            declared.isEmpty() -> 0
            C.ENCODING_PCM_24BIT in declared -> 24
            C.ENCODING_PCM_FLOAT in declared -> 32
            C.ENCODING_PCM_16BIT in declared -> 16
            else -> 0
        }
    }
}
