package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.home.HomeCacheTtlMs
import com.annieplayer.annie_music.kt.data.sources.kugou.getKuGouQualities
import com.annieplayer.annie_music.kt.data.sources.kugou.getKuGouQualitySizes

private const val kugouRankBase = "http://mobilecdn.kugou.com/api/v3/rank"
private const val kugouRankCachePrefix = "rank:kugou:"

private fun _kugouCover(raw: String): String =
    raw.trim().replace("{size}", "400")

internal suspend fun fetchKugouRankBoards(): List<RankBoard> =
    parseKugouRankInfo(_kugouRankListRaw())

internal fun parseKugouRankInfo(info: List<Any?>): List<RankBoard> {
    return info.mapNotNull { entry ->
        val m = normRankMap(entry) ?: return@mapNotNull null
        val rankId = rankStr(m["rankid"])
        if (rankId.isEmpty()) return@mapNotNull null
        val previews = (m["songinfo"] as? List<*>).orEmpty().mapIndexedNotNull { index, song ->
            val sm = normRankMap(song) ?: return@mapIndexedNotNull null
            val author = decodeHtmlEntities(rankStr(sm["author"]))
            val title = decodeHtmlEntities(_kugouTitleOf(rankStr(sm["songname"]).ifEmpty { rankStr(sm["name"]) }, author))
            if (title.isEmpty()) return@mapIndexedNotNull null
            RankPreview(rank = index + 1, title = title, artist = author)
        }
        RankBoard(
            id = rankId,
            name = decodeHtmlEntities(rankStr(m["rankname"])),
            cover = _kugouCover(
                rankStr(m["banner_9"]).ifEmpty {
                    rankStr(m["img_9"]).ifEmpty { rankStr(m["img_cover"]) }
                },
            ),
            subtitle = rankSubtitle(
                listOf(rankStr(m["update_frequency"]), rankStr(m["intro"]).take(40)),
            ),
            group = "",
            top = previews,
        )
    }
}

internal suspend fun fetchKugouRankDetail(
    boardId: String,
    page: Int,
    pageSize: Int,
): RankDetail? {
    val resp = _kugouRankSongsRaw(boardId, page, pageSize) ?: return null
    val data = normRankMap(resp["data"]) ?: return null
    val info = (data["info"] as? List<*>).orEmpty()
    val songs = info.mapNotNull { entry ->
        val m = normRankMap(entry) ?: return@mapNotNull null
        _kugouRankSong(m)
    }
    val total = rankInt(data["total"]).takeIf { it > 0 } ?: songs.size
    return RankDetail(
        title = "",
        subtitle = rankSubtitle(listOf("$total 首")),
        cover = "",
        songs = songs,
        total = total,
        page = page,
        pageSize = pageSize,
    )
}

private fun _kugouTitleOf(songname: String, author: String): String {
    if (author.isEmpty()) return songname
    val prefix = "$author - "
    return if (songname.startsWith(prefix)) songname.removePrefix(prefix) else songname
}

private fun _kugouRankAuthor(m: Map<String, Any?>): String {
    val raw = m["authors"]
    val fromAuthors = when (raw) {
        is Map<*, *> -> rankStr(raw["author_name"])
        is List<*> -> raw
            .mapNotNull { rankStr((it as? Map<*, *>)?.get("author_name")) }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")

        else -> ""
    }
    return decodeHtmlEntities(
        fromAuthors.ifEmpty { rankStr(m["author"]).ifEmpty { rankStr(m["singername"]) } },
    )
}

private fun _kugouRankSong(m: Map<String, Any?>): Song? {
    val hash = rankStr(m["hash"]).uppercase()
    if (hash.isEmpty()) return null
    val author = _kugouRankAuthor(m)
    val title = decodeHtmlEntities(
        _kugouTitleOf(rankStr(m["songname"]).ifEmpty { rankStr(m["filename"]) }, author),
    )
    if (title.isEmpty()) return null
    val cover = _kugouCover(
        rankStr(m["album_sizable_cover"]).ifEmpty {
            rankStr(m["album_cover"]).ifEmpty {
                rankStr(m["cover"]).ifEmpty { rankStr(m["img"]) }
            }
        },
    )
    val albumId = rankStr(m["album_id"]).takeIf { it.isNotEmpty() && it != "0" }
    return Song(
        id = "kugou:$hash",
        title = title,
        artist = author.ifEmpty { "未知歌手" },
        cover = cover,
        album = null,
        albumId = albumId,
        duration = rankInt(m["duration"]),
        platform = MusicPlatform.kugou,
        playUrl = "",
        qualities = getKuGouQualities(m),
        qualitySizes = getKuGouQualitySizes(m),
        albumArtist = author.takeIf { it.isNotEmpty() },
        artistNames = if (author.isEmpty()) emptyList() else listOf(author),
        artistIds = emptyList(),
    )
}

private suspend fun _kugouRankListRaw(): List<Any?> {
    val key = kugouRankCachePrefix + "boards"
    cacheService.get<Map<String, Any?>>(key)?.let { cached ->
        val data = normRankMap(cached["data"]) ?: return@let
        (data["info"] as? List<*>)?.let { return it }
    }
    val resp = httpGetJson(
        "$kugouRankBase/list?apiver=2&plat=1&page=1&pagesize=60&withsong=1",
    )
    val info = (normRankMap(resp["data"])?.get("info") as? List<*>).orEmpty()
    if (info.isNotEmpty()) cacheService.set(key, resp, HomeCacheTtlMs)
    return info
}

private suspend fun _kugouRankSongsRaw(
    boardId: String,
    page: Int,
    pageSize: Int,
): Map<String, Any?>? {
    val key = kugouRankCachePrefix + "songs:$boardId:$page:$pageSize"
    cacheService.get<Map<String, Any?>>(key)?.let { return it }
    val resp = httpGetJson(
        "$kugouRankBase/song?rankid=$boardId&page=$page&pagesize=$pageSize" +
            "&plat=1&apiver=2&withsong=1",
    )
    if (resp["data"] is Map<*, *>) {
        cacheService.set(key, resp, HomeCacheTtlMs)
        return resp
    }
    return null
}
