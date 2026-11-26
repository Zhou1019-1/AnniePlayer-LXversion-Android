package com.annieplayer.annie_music.kt.data.sources.update

private val VERSION_TAG = Regex("^[vV]?(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

private val BODY_VERSION_CODE =
    Regex("(?im)^\\s*(?:version[-_ ]?code|版本号)\\s*[:：=]\\s*(\\d+)\\s*$")

private val ASSET_VERSION_CODE = Regex("(?i)vc[-_ ]?(\\d+)")

fun versionNameOfTag(tag: String): String? {
    val match = VERSION_TAG.matchEntire(tag.trim()) ?: return null
    return "${match.groupValues[1]}.${match.groupValues[2]}.${match.groupValues[3]}"
}

fun parseVersionCodeFromNotes(notes: String): Int? =
    BODY_VERSION_CODE.find(notes)?.groupValues?.get(1)?.toIntOrNull()

fun isVersionCodeLine(line: String): Boolean = BODY_VERSION_CODE.containsMatchIn(line)

fun parseVersionCodeFromAssets(names: List<String>): Int? =
    names.firstNotNullOfOrNull { name ->
        ASSET_VERSION_CODE.find(name)?.groupValues?.get(1)?.toIntOrNull()
    }

fun isNewerVersion(remoteCode: Int, localCode: Int): Boolean = remoteCode > localCode

fun compareVersionNames(left: String, right: String): Int {
    val a = left.split('.').map { it.toIntOrNull() ?: 0 }
    val b = right.split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(a.size, b.size)) {
        val diff = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}
