package com.annieplayer.annie_music.kt.data.sources.update

private val WRAPPER_MARKERS = listOf(
    "wrapperlite",
    "wrapper-lite",
    "wrapper_lite",
)

private val MAIN_MARKERS = listOf(
    "annieplayer",
    "annie-player",
    "annie_player",
    "anniemusic",
    "annie_music",
    "annie-music",
)

private val GENERIC_MAIN_PREFIXES = listOf(
    "app-release",
    "app-debug",
    "app-unsigned",
)

private fun key(name: String): String = name.lowercase().replace(" ", "")

fun isApkAssetName(name: String): Boolean = name.trim().endsWith(".apk", ignoreCase = true)

fun isWrapperApkName(name: String): Boolean {
    if (!isApkAssetName(name)) return false
    val squashed = key(name)
    return WRAPPER_MARKERS.any { squashed.contains(it) }
}

fun isMainApkName(name: String): Boolean {
    if (!isApkAssetName(name)) return false
    if (isWrapperApkName(name)) return false
    val squashed = key(name)
    if (MAIN_MARKERS.any { squashed.contains(it) }) return true
    return GENERIC_MAIN_PREFIXES.any { squashed.startsWith(it) }
}

fun selectMainApkName(names: List<String>): String? {
    val apks = names.map { it.trim() }.filter { isApkAssetName(it) }
    if (apks.isEmpty()) return null
    val marked = apks.filter { isMainApkName(it) }
    if (marked.isNotEmpty()) return marked.first()
    return apks.filterNot { isWrapperApkName(it) }.singleOrNull()
}
