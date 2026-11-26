package com.annieplayer.annie_music.kt.data.sources.update

sealed interface UpdateNoteLine {
    data class Heading(val text: String, val level: Int) : UpdateNoteLine

    data class Bullet(val text: String) : UpdateNoteLine

    data class Code(val text: String) : UpdateNoteLine

    data class Plain(val text: String) : UpdateNoteLine
}

private val NOTE_HEADING = Regex("^(#{1,6})\\s+(.*)$")

private val NOTE_BULLET = Regex("^[-*+]\\s+(.*)$")

fun parseUpdateNotes(markdown: String): List<UpdateNoteLine> {
    if (markdown.isBlank()) return emptyList()
    val lines = mutableListOf<UpdateNoteLine>()
    var inCode = false
    markdown.lines().forEach { raw ->
        val trimmed = raw.trim()
        if (trimmed.startsWith("```")) {
            inCode = !inCode
            return@forEach
        }
        if (inCode) {
            if (trimmed.isNotEmpty()) lines += UpdateNoteLine.Code(raw.trimEnd())
            return@forEach
        }
        if (trimmed.isEmpty()) return@forEach
        if (isVersionCodeLine(trimmed)) return@forEach
        val heading = NOTE_HEADING.matchEntire(trimmed)
        if (heading != null) {
            lines += UpdateNoteLine.Heading(
                text = cleanNoteInline(heading.groupValues[2]),
                level = heading.groupValues[1].length,
            )
            return@forEach
        }
        val bullet = NOTE_BULLET.matchEntire(trimmed)
        if (bullet != null) {
            lines += UpdateNoteLine.Bullet(cleanNoteInline(bullet.groupValues[1]))
            return@forEach
        }
        lines += UpdateNoteLine.Plain(cleanNoteInline(trimmed))
    }
    return lines
}

private fun cleanNoteInline(text: String): String {
    var out = text
    out = out.replace("**", "")
    out = out.replace("__", "")
    out = out.replace("`", "")
    out = out.replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
    return out.trim()
}
