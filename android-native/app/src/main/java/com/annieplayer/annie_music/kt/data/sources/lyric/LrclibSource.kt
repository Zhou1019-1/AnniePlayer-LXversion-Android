package com.annieplayer.annie_music.kt.data.sources.lyric

import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val _lrclibJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * LRCLIB 的一条歌词候选。
 *
 * [syncedLrc] 就是标准 LRC（`[00:33.44] Look at the stars`），可以直接写进文件的 `LYRICS`。
 * [wordSync] 是服务端的「是否逐字」标记（实测目前全是 false，留着以后能用）。
 */
data class LrclibHit(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val syncedLrc: String,
    val wordSync: Boolean = false,
)

/**
 * LRCLIB（`lrclib.net`）—— **只提供歌词**的公共库：直连、无需 key、无需登录。
 *
 * 为什么值得单独接一个：中文覆盖实测很好（周杰伦《晴天》20/20、《淘金小镇》13/13 全带同步歌词），
 * 而 QQ/网易/酷狗的歌词经常只有原文没有时间轴、或者干脆取不到。
 *
 * 两条约束：
 * - 服务方要求带**能识别应用的 User-Agent**（默认 UA 会被拒）；
 * - 一次搜索会回 20 条、每条都带全文歌词（约 200KB），这里只留前 [limit] 条，别把全文塞进 UI。
 *
 * 只收**带 `syncedLyrics`** 的条目：纯文本（`plainLyrics`）没有时间轴，写进文件会让播放页失去滚动同步。
 */
internal object LrclibSource {

    private const val BASE = "https://lrclib.net/api/search"

    private val headers = mapOf(
        "User-Agent" to "AnnieMusic/1.0 (Android; https://github.com/anniemusic)",
        "Accept" to "application/json",
    )

    suspend fun search(title: String, artist: String, limit: Int = 10): List<LrclibHit> {
        val t = title.trim()
        if (t.isEmpty()) return emptyList()
        val query = buildString {
            append("?track_name=").append(encodeComponent(t))
            if (artist.isNotBlank()) append("&artist_name=").append(encodeComponent(artist.trim()))
        }
        return parseLrclibSearch(httpGetText(BASE + query, headers), limit)
    }
}

/**
 * 解析 `/api/search` 的响应（纯函数，便于单测）。
 *
 * 只收带 `syncedLyrics` 的条目；`instrumental`（纯音乐的占位条目）也丢掉 ——
 * 它们写进文件只会变成一行行方言标记，没有可显示的内容。
 */
internal fun parseLrclibSearch(text: String, limit: Int = 10): List<LrclibHit> {
    val arr = _lrclibJson.parseToJsonElement(text).jsonArray
    return arr.mapNotNull { el ->
        val o = el.jsonObject
        if (o["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
        val synced = o["syncedLyrics"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        LrclibHit(
            id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
            title = o["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            artist = o["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            album = o["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            durationSec = (o["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0).toInt(),
            syncedLrc = synced,
            wordSync = o["hasWordSync"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }.take(limit)
}
