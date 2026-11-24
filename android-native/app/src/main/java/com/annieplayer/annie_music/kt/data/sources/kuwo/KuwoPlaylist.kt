package com.annieplayer.annie_music.kt.data.sources.kuwo

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Playlist
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.firstTruthy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

// ================================================================================================
// 酷我歌单（`pl.svc`，按 100 条分页累加）
// ================================================================================================

private suspend fun _fetchKuwoPage(playlistId: String, pn: Int, pageSize: Int): KuwoPage? {
    // 官方歌单接口（对齐 LX kw/songList.js）：返回标准 JSON，顶层 result=="ok"，歌曲在 musiclist
    // 注意 pn 是 0 基；旧版 m.kuwo.cn/newh5app/wapi 那条接口已废弃（无 code、字段为 musicList）
    val url =
        "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId" +
            "&pn=${pn - 1}&rn=$pageSize&encode=utf8&keyset=pl2012" +
            "&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1"
    return try {
        val resp = httpGetJson(url, mapOf("User-Agent" to kuwoDesktopUa))
        if (kuwoStrOr(resp["result"], "") != "ok") return null
        KuwoPage(
            songs = _applyKuwoArtistCredits(kuwoMapList(resp["musiclist"])),
            total = kuwoInt(resp["total"]),
            name = kuwoStrOr(resp["title"], ""),
            creator = kuwoStrOr(resp["uname"], ""),
            cover = kuwoStrOr(resp["pic"], ""),
        )
    } catch (_: Throwable) {
        null
    }
}

/** 获取酷我歌单（分页 100 条累加）；失败返回 null */
suspend fun getKuwoPlaylist(playlistId: String): Playlist? {
    val pageSize = 100
    val first = _fetchKuwoPage(playlistId, 1, pageSize)
    if (first == null || first.songs.isEmpty()) return null

    val total = first.total
    val pages = if (total <= 0) 0 else (total + pageSize - 1) / pageSize

    var allSongs = first.songs
    if (pages > 1) {
        for (i in 0 until pages - 1) {
            val page = _fetchKuwoPage(playlistId, i + 2, pageSize)
            if (page != null) allSongs = allSongs + page.songs
        }
    }

    return Playlist(
        id = playlistId,
        name = first.name,
        creator = first.creator,
        cover = kuwoCoverAtSize(first.cover),
        platform = MusicPlatform.kuwo,
        songs = allSongs,
        songCount = allSongs.size,
    )
}

private val kuwoMultiArtistSeparators = Regex("[&,、/]")

private suspend fun _fetchKuwoArtistCredits(
    rids: List<String>,
): Map<String, List<Pair<String, String>>> {
    if (rids.isEmpty()) return emptyMap()
    val out = ConcurrentHashMap<String, List<Pair<String, String>>>()
    val gate = Semaphore(4)
    coroutineScope {
        for (rid in rids) {
            launch {
                gate.acquire()
                try {
                    runCatching {
                        val url =
                            "http://sartist.kuwo.cn/qi.s?rid=$rid&newver=3&encoding=utf8&isMultiArtists=1"
                        val resp = httpGetJson(url, mapOf("User-Agent" to kuwoDesktopUa))
                        val artists = resp["artists"] as? List<*> ?: return@runCatching
                        val pairs = artists.mapNotNull { entry ->
                            val m = entry as? Map<*, *> ?: return@mapNotNull null
                            val name = decodeHtmlEntities(asText(m["artistName"]))
                            if (name.isEmpty()) return@mapNotNull null
                            name to asText(m["artistID"])
                        }
                        if (pairs.isNotEmpty()) out[rid] = pairs
                    }
                } finally {
                    gate.release()
                }
            }
        }
    }
    return out
}

private suspend fun _applyKuwoArtistCredits(items: List<Map<String, Any?>>): List<Song> {
    val rids = items.mapNotNull { item ->
        if (!kuwoMultiArtistSeparators.containsMatchIn(asText(item["artist"]))) return@mapNotNull null
        asText(firstTruthy(listOf(item["id"], item["rid"], item["musicrid"])))
            .replaceFirst("MUSIC_", "")
            .takeIf { it.isNotEmpty() }
    }.distinct()
    val credits = _fetchKuwoArtistCredits(rids)
    return items.map { item ->
        val song = kuwoMapSong(item)
        val credit = credits[song.id]
        if (credit == null) {
            song
        } else {
            song.copy(
                artist = credit.joinToString(" / ") { it.first },
                artistNames = credit.map { it.first },
                artistIds = credit.map { it.second },
            )
        }
    }
}
