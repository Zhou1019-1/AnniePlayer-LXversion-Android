package com.annieplayer.annie_music.kt.core.audio

import com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream

data class MmapProfile(val sampleRate: Int, val format: Int)

private val MMAP_MIX_PORT = Regex(
    "<mixPort\\b[^>]*MMAP_NOIRQ[^>]*>(.*?)</mixPort>",
    RegexOption.DOT_MATCHES_ALL,
)

private val PROFILE_TAG = Regex("<profile\\b[^>]*>")

internal val POLICY_FILES: List<java.io.File> = listOf(
    java.io.File("/vendor/etc/audio/audio_policy_configuration.xml"),
    java.io.File("/vendor/etc/audio/audio_policy_configuration_gaming.xml"),
)

fun aaudioFormatOfPolicy(name: String): Int? = when (name) {
    "AUDIO_FORMAT_PCM_16_BIT" -> NativeOboeStream.FORMAT_I16
    "AUDIO_FORMAT_PCM_24_BIT_PACKED" -> NativeOboeStream.FORMAT_I24
    "AUDIO_FORMAT_PCM_FLOAT" -> NativeOboeStream.FORMAT_FLOAT
    else -> null
}

fun parseMmapProfiles(xml: String): List<MmapProfile> {
    val out = LinkedHashSet<MmapProfile>()
    for (port in MMAP_MIX_PORT.findAll(xml)) {
        for (profile in PROFILE_TAG.findAll(port.groupValues[1])) {
            val tag = profile.value
            val format = attrOf(tag, "format")?.let { aaudioFormatOfPolicy(it) } ?: continue
            val rates = attrOf(tag, "samplingRates").orEmpty()
            for (token in rates.split(' ')) {
                val rate = token.trim().toIntOrNull() ?: continue
                out.add(MmapProfile(rate, format))
            }
        }
    }
    return out.toList()
}

fun mmapProfilesFromPolicy(files: List<java.io.File> = POLICY_FILES): List<MmapProfile> {
    val out = LinkedHashSet<MmapProfile>()
    for (file in files) {
        val text = runCatching { file.readText() }.getOrNull() ?: continue
        out.addAll(parseMmapProfiles(text))
    }
    return out.toList()
}

private fun attrOf(tag: String, name: String): String? =
    Regex("$name=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
