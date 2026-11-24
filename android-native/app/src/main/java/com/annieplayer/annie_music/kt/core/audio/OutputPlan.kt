package com.annieplayer.annie_music.kt.core.audio

import android.media.AudioFormat

enum class DecodeKind(val label: String) {
    ffmpegSoft("软解"),
    nativeFullChain("全链路"),
}

enum class Pace {
    devicePaced,
    selfPaced,
}

enum class OutputTier(val prefix: String) {
    bitPerfect("Bit Perfect"),
    ownSrc("我方重采样"),
    ownSrcFallback("我方重采样（兜底）"),
}

internal const val PLAN_FLOAT_DEPTH: Int = 32

internal data class OutputPlan(
    val mode: AudioOutputMode,
    val decode: DecodeKind,
    val sourceRate: Int,
    val sourceDepth: Int,
    val sampleRate: Int,
    val bitDepth: Int,
    val mixerTarget: Int,
    val fallback: RateFallback,
    val tier: OutputTier? = null,
    val dsdTargetRate: Int = 0,
    val aaudioExclusive: Boolean = false,
) {
    val didResample: Boolean
        get() = sampleRate > 0 && sourceRate > 0 && sampleRate != sourceRate

    val didConvertDepth: Boolean
        get() = bitDepth in 16..24 && sourceDepth > 0 && bitDepth != sourceDepth

    val deliveredRate: Int
        get() = if (sampleRate > 0) sampleRate else sourceRate

    val deliveredDepth: Int
        get() = if (bitDepth > 0) bitDepth else sourceDepth

    val isPassthrough: Boolean
        get() = mode == AudioOutputMode.transparent && sampleRate == 0 && bitDepth == 0
}

internal fun resolveOutputPlan(
    mode: AudioOutputMode,
    route: AudioRoute,
    nativeRate: Int,
    deviceRates: List<Int>,
    codecRate: Int = 0,
    sourceRate: Int,
    sourceDepth: Int,
    decodedRate: Int = 0,
    aaudioExclusive: Boolean = false,
    followSourceRate: Boolean = false,
    fullChain: Boolean,
    exclusiveSink: Boolean,
    bitExactSink: Boolean,
    exclusiveDepth: Int = 0,
    bitPerfectGranted: Boolean = false,
    dsdTargetRate: Int = 0,
    rawPassthrough: Boolean = false,
    canBuildRate: (Int) -> Boolean = ::platformCanBuildRate,
): OutputPlan {
    val tuned = mode == AudioOutputMode.tuned

    val decode = when {
        fullChain -> DecodeKind.nativeFullChain
        else -> DecodeKind.ffmpegSoft
    }

    if (rawPassthrough) {
        return OutputPlan(
            mode = mode,
            decode = decode,
            sourceRate = sourceRate,
            sourceDepth = sourceDepth,
            sampleRate = 0,
            bitDepth = 0,
            mixerTarget = 0,
            fallback = RateFallback.none,
            tier = null,
            dsdTargetRate = 0,
        )
    }

    val autoRate = if (exclusiveSink && sourceRate > 0) {
        sourceRate
    } else {
        autoRateFor(route, nativeRate, deviceRates, codecRate, sourceRate)
    }
    val systemRate = systemMixRateFor(route, nativeRate, deviceRates, codecRate, sourceRate)

    val acceptedRate = if (autoRate > 0 && !canBuildRate(autoRate)) systemRate else autoRate

    val followSourceRateNow = followSourceRateActive(exclusiveSink, followSourceRate)

    val sampleRate = when {
        dsdTargetRate > 0 -> dsdTargetRate
        followSourceRateNow && sourceRate > 0 -> 0
        !tuned && bitPerfectGranted -> 0
        !tuned && exclusiveSink && bitExactSink -> 0
        !tuned && aaudioExclusive && exclusiveSink && !bitExactSink -> 0
        !tuned -> systemRate
        else -> acceptedRate
    }

    val decoded = decodedRate.takeIf { it > 0 } ?: sourceRate
    val resampling = sampleRate > 0 && decoded > 0 && sampleRate != decoded

    val bitDepth = when {
        tuned || resampling -> PLAN_FLOAT_DEPTH
        aaudioExclusive && exclusiveSink && !bitExactSink &&
            exclusiveDepth in 16..24 && sourceDepth > exclusiveDepth -> exclusiveDepth
        else -> 0
    }

    val mixerTarget = when {
        decode == DecodeKind.nativeFullChain -> 0
        route == AudioRoute.bluetooth -> 0
        tuned && sampleRate > 0 -> sampleRate
        else -> 0
    }

    val tier = when {
        !tuned && exclusiveSink && bitExactSink -> OutputTier.bitPerfect
        !tuned && aaudioExclusive && exclusiveSink && !bitExactSink -> OutputTier.bitPerfect
        sampleRate > 0 && sourceRate > 0 && sampleRate != sourceRate -> OutputTier.ownSrc
        decode == DecodeKind.nativeFullChain && sampleRate > 0 -> OutputTier.ownSrc
        else -> null
    }

    val fallback = when {
        !tuned -> RateFallback.none
        acceptedRate > 0 && deviceRates.isNotEmpty() &&
            acceptedRate !in deviceRates && acceptedRate != nativeRate ->
            RateFallback.deviceNotDeclared
        else -> RateFallback.none
    }

    return OutputPlan(
        mode = mode,
        decode = decode,
        sourceRate = sourceRate,
        sourceDepth = sourceDepth,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        mixerTarget = mixerTarget,
        fallback = fallback,
        tier = tier,
        dsdTargetRate = dsdTargetRate,
        aaudioExclusive = aaudioExclusive && exclusiveSink && !bitExactSink,
    )
}

internal fun systemMixRateFor(
    route: AudioRoute,
    nativeRate: Int,
    deviceRates: List<Int>,
    codecRate: Int = 0,
    sourceRate: Int = 0,
): Int = when (route) {
    AudioRoute.bluetooth -> codecRate.takeIf {
        it > 0 && (deviceRates.isEmpty() || it in deviceRates)
    } ?: 0

    AudioRoute.usb -> if (nativeRate > 0) {
        nativeRate
    } else {
        deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }
            .minByOrNull { kotlin.math.abs(it - (sourceRate.takeIf { r -> r > 0 } ?: nativeRate)) }
            ?: 0
    }

    else -> if (nativeRate > 0) {
        nativeRate
    } else {
        deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }.maxOrNull() ?: 0
    }
}

internal fun autoRateFor(
    route: AudioRoute,
    nativeRate: Int,
    deviceRates: List<Int>,
    codecRate: Int = 0,
    sourceRate: Int = 0,
): Int = when (route) {
    AudioRoute.bluetooth -> {
        val usable = codecRate.takeIf {
            it > 0 && (deviceRates.isEmpty() || it in deviceRates)
        }
        usable ?: 0
    }

    AudioRoute.usb -> if (nativeRate > 0) {
        nativeRate
    } else {
        deviceRates.filter { it in MIN_PLAN_RATE..MAX_PLAN_RATE }.maxOrNull() ?: 0
    }

    else -> nativeRate
}

internal fun resolveDeviceDepth(
    route: AudioRoute,
    codecDepth: Int,
    mixerDepth: Int,
    mixerFloat: Boolean,
): Int {
    val sinkDepth = if (mixerFloat) 32 else mixerDepth
    if (route != AudioRoute.bluetooth) return sinkDepth
    return codecDepth.takeIf { it > 0 } ?: sinkDepth
}

internal fun shouldDither(outDepth: Int, rounds: Boolean): Boolean =
    rounds && outDepth in 16..23

internal fun platformCanBuildRate(rate: Int): Boolean = runCatching {
    AudioFormat.Builder()
        .setSampleRate(rate)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()
}.isSuccess

internal const val MIN_PLAN_RATE: Int = 8_000

internal const val AUDIO_FORMAT_MAX_RATE: Int = 192_000

internal const val MAX_PLAN_RATE: Int = 768_000
