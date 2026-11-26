package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import java.util.Locale

internal fun albumDateText(isoDate: String?): String {
    val s = isoDate?.trim().orEmpty()
    if (s.isEmpty()) return ""
    val parts = s.split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return ""
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    return when {
        month == null -> "${year}年"
        day == null -> "${year}年${month}月"
        else -> "${year}年${month}月${day}日"
    }
}

internal fun albumCountDurationText(count: Int, totalSeconds: Int): String {
    if (count <= 0) return ""
    val duration = albumDurationText(totalSeconds)
    return if (duration.isEmpty()) "$count 首歌曲" else "$count 首歌曲，$duration"
}

internal fun albumDurationText(totalSeconds: Int): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "$hours 小时 $minutes 分钟"
        minutes > 0 -> "$minutes 分钟"
        else -> "$seconds 秒"
    }
}

internal fun albumSpecText(tracks: List<LocalTrackMeta>): String {
    val best = tracks.maxByOrNull { it.bitDepth * 1_000_000L + it.sampleRate } ?: return ""
    val codec = best.codec.uppercase(Locale.ROOT)
    val detail = when {
        best.lossless -> best.formatText
        best.bitrate > 0 -> "${best.bitrate} kbps"
        else -> best.formatText
    }
    val parts = listOf(codec, detail).filter { it.isNotEmpty() }
    return parts.joinToString(" · ")
}

internal fun albumCopyrightText(tracks: List<LocalTrackMeta>): String {
    val raw = tracks.firstNotNullOfOrNull { it.copyright?.trim()?.takeIf { value -> value.isNotEmpty() } }
        ?: return ""
    return if (raw.startsWith("℗") || raw.startsWith("©") || raw.startsWith("(P)")) raw else "℗ $raw"
}
