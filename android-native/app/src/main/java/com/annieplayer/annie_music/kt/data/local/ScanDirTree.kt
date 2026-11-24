package com.annieplayer.annie_music.kt.data.local

import java.util.Locale

internal val AudioFileExtensions = setOf(
    "mp3", "mp2", "mpga", "flac", "m4a", "m4b", "aac", "alac", "wav", "wave",
    "ogg", "oga", "opus", "ape", "wv", "tta", "wma", "mka", "aif", "aiff", "aifc",
    "dsf", "dff", "caf", "amr", "ac3", "eac3", "dts",
)

internal fun isAudioFileName(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    if (dot < 0 || dot == name.length - 1) return false
    return name.substring(dot + 1).lowercase(Locale.ROOT) in AudioFileExtensions
}

internal fun isPathUnder(path: String, dir: String): Boolean =
    path.length > dir.length && path.startsWith(dir) && path[dir.length] == '/'

internal fun isPathExcluded(path: String, excluded: Set<String>): Boolean {
    if (excluded.isEmpty()) return false
    for (dir in excluded) {
        if (path == dir || isPathUnder(path, dir)) return true
    }
    return false
}

internal fun parentOf(path: String): String {
    val i = path.lastIndexOf('/')
    return if (i <= 0) "" else path.substring(0, i)
}

internal fun friendlyDirPath(path: String): String {
    val emulated = "/storage/emulated/0"
    if (path == emulated) return "内置存储"
    if (path.startsWith("$emulated/")) return "内置存储 · ${path.substring(emulated.length + 1)}"
    if (path.startsWith("/storage/")) {
        val rest = path.removePrefix("/storage/")
        val id = rest.substringBefore('/')
        if (id == "emulated" || id.isEmpty()) return path
        val tail = rest.substringAfter('/', "").ifEmpty { id }
        return if (tail == id) "存储卡 $id" else "存储卡 $id · $tail"
    }
    return path
}

internal fun isStorageRootPath(path: String): Boolean {
    if (path == "/storage/emulated/0") return true
    if (!path.startsWith("/storage/")) return false
    return !path.removePrefix("/storage/").contains('/')
}
