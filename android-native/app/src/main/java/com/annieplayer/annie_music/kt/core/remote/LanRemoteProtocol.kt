package com.annieplayer.annie_music.kt.core.remote

import com.annieplayer.annie_music.kt.data.network.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

const val RemoteDefaultPort: Int = 45823

data class RemoteLyricLine(
    val timeMs: Int = 0,
    val text: String = "",
    val translation: String = "",
)

data class RemoteLyric(
    val lines: List<RemoteLyricLine> = emptyList(),
    val current: Int = -1,
)

data class RemoteMode(
    val id: String = "",
    val icon: String = "",
    val label: String = "",
)

data class RemoteNowPlaying(
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val duration: Double = 0.0,
    val position: Double = 0.0,
    val volume: Double = 1.0,
    val mode: RemoteMode? = null,
    val lyric: RemoteLyric = RemoteLyric(),
)

data class RemoteLibRow(
    val path: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
)

data class RemoteLibraryPage(
    val total: Int = 0,
    val items: List<RemoteLibRow> = emptyList(),
)

sealed interface PairOutcome {
    data class Ok(val token: String) : PairOutcome
    data class Locked(val retryAfterSeconds: Int) : PairOutcome
    data object Rejected : PairOutcome
}

sealed interface StateFetch {
    data class Ok(val now: RemoteNowPlaying) : StateFetch
    data object Unauthorized : StateFetch
}

data class SseEvent(val event: String = "message", val data: String = "")

data class SseParseResult(val events: List<SseEvent>, val rest: String)

private fun JsonElement?.primitive(): JsonPrimitive? {
    val p = this as? JsonPrimitive ?: return null
    return if (p is JsonNull) null else p
}

private fun JsonElement?.text(): String = primitive()?.content.orEmpty()

private fun JsonElement?.numberOrNull(): Double? = primitive()?.content?.toDoubleOrNull()

private fun JsonElement?.number(): Double = numberOrNull() ?: 0.0

private fun JsonElement?.flag(): Boolean = primitive()?.content == "true"

fun normalizePairCode(raw: String): String = buildString {
    for (c in raw) {
        when {
            c in '0'..'9' -> append(c)
            c.code in 0xFF10..0xFF19 -> append(('0'.code + (c.code - 0xFF10)).toChar())
            else -> Unit
        }
    }
}

fun remoteBaseUrl(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    s = s.removePrefix("http://").removePrefix("https://")
    s = s.removePrefix("HTTP://").removePrefix("HTTPS://")
    s = s.substringBefore('/').substringBefore('?')
    if (s.isEmpty()) return null
    if (s.contains(' ')) return null
    val portText = s.substringAfterLast(':', "")
    val hasPort = portText.isNotEmpty() && portText.toIntOrNull() != null
    val hostPart = if (hasPort) s else "$s:$RemoteDefaultPort"
    return "http://$hostPart"
}

fun remoteDisplayEndpoint(baseUrl: String): String =
    baseUrl.removePrefix("http://").removePrefix("https://")

fun clampRemoteVolume(value: Double): Double = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 1.0

fun clampRemoteSeek(value: Double, duration: Double): Double = when {
    !value.isFinite() -> 0.0
    value < 0.0 -> 0.0
    duration.isFinite() && duration > 0.0 -> value.coerceAtMost(duration)
    else -> value
}

fun parsePairBody(statusCode: Int, body: String): PairOutcome {
    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    if (statusCode == 429) {
        val retryAfter = obj?.get("retryAfter").numberOrNull()?.toInt() ?: 30
        return PairOutcome.Locked(retryAfter)
    }
    val token = obj?.get("token").text().orEmpty()
    val ok = obj?.get("ok").flag() == true
    return if (statusCode == 200 && ok && token.isNotEmpty()) {
        PairOutcome.Ok(token)
    } else {
        PairOutcome.Rejected
    }
}

fun parseRemoteState(body: String): RemoteNowPlaying {
    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return RemoteNowPlaying()
    val lyricObj = obj["lyric"] as? JsonObject
    val lines = ArrayList<RemoteLyricLine>()
    (lyricObj?.get("lines") as? JsonArray)?.forEach { element ->
        val line = element as? JsonObject ?: return@forEach
        lines.add(
            RemoteLyricLine(
                timeMs = line["t"].number().toInt(),
                text = line["txt"].text(),
                translation = line["tly"].text(),
            ),
        )
    }
    val modeObj = obj["mode"] as? JsonObject
    return RemoteNowPlaying(
        playing = obj["playing"].flag(),
        title = obj["title"].text(),
        artist = obj["artist"].text(),
        album = obj["album"].text(),
        cover = obj["cover"].text(),
        duration = obj["duration"].number(),
        position = obj["position"].number(),
        volume = obj["volume"].numberOrNull()?.let { clampRemoteVolume(it) } ?: 1.0,
        mode = modeObj?.let {
            RemoteMode(
                id = it["id"].text(),
                icon = it["icon"].text(),
                label = it["label"].text(),
            )
        },
        lyric = RemoteLyric(
            lines = lines,
            current = (lyricObj?.get("cur").numberOrNull() ?: -1.0).toInt(),
        ),
    )
}

fun parseRemoteLibrary(body: String): RemoteLibraryPage {
    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return RemoteLibraryPage()
    val items = ArrayList<RemoteLibRow>()
    (obj["items"] as? JsonArray)?.forEach { element ->
        val row = element as? JsonObject ?: return@forEach
        items.add(
            RemoteLibRow(
                path = row["p"].text(),
                title = row["t"].text(),
                artist = row["ar"].text(),
                album = row["al"].text(),
            ),
        )
    }
    return RemoteLibraryPage(total = obj["total"].number().toInt(), items = items)
}

fun parseSseBlock(block: String): SseEvent {
    var event = "message"
    val data = StringBuilder()
    for (rawLine in block.split('\n')) {
        val line = rawLine.trimEnd('\r')
        when {
            line.isEmpty() || line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trimStart())
            }
        }
    }
    return SseEvent(event, data.toString())
}

fun parseSseChunk(buffer: String): SseParseResult {
    val events = ArrayList<SseEvent>()
    var rest = buffer
    while (true) {
        val idx = rest.indexOf("\n\n")
        if (idx < 0) break
        val block = rest.substring(0, idx)
        rest = rest.substring(idx + 2)
        val parsed = parseSseBlock(block)
        if (parsed.data.isNotEmpty()) events.add(parsed)
    }
    return SseParseResult(events, rest)
}

val RemotePlayModeOrder: List<String> = listOf("all-seq", "list-seq", "all-rand", "list-rand", "repeat-one")

fun playModeLabel(modeId: String, serverLabel: String): String = when (modeId) {
    "all-seq" -> "顺序"
    "list-seq" -> "列表"
    "all-rand" -> "全库随机"
    "list-rand" -> "列表随机"
    "repeat-one" -> "单曲"
    else -> serverLabel.ifEmpty { "顺序" }
}

fun nextPlayMode(currentId: String): RemoteMode {
    val index = RemotePlayModeOrder.indexOf(currentId)
    val next = RemotePlayModeOrder[if (index < 0) 0 else (index + 1) % RemotePlayModeOrder.size]
    return RemoteMode(id = next, icon = "", label = playModeLabel(next, ""))
}
