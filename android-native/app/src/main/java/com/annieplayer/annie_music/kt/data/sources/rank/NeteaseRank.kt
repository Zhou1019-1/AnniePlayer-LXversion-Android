package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.home.HomeCacheTtlMs
import com.annieplayer.annie_music.kt.data.sources.netease.neteaseApiHeaders
import com.annieplayer.annie_music.kt.data.sources.netease.neteaseArtistSong

private const val neteaseRankBase = "https://music.163.com/api"
private const val neteaseRankCachePrefix = "rank:netease:"

internal suspend fun fetchNeteaseRankBoards(): List<RankBoard> =
    parseNeteaseRankBoards(_neteaseRankRawList())

internal fun parseNeteaseRankBoards(raw: List<Any?>): List<RankBoard> {
    return raw.mapNotNull { entry ->
        val m = normRankMap(entry) ?: return@mapNotNull null
        val id = rankStr(m["id"])
        if (id.isEmpty()) return@mapNotNull null
        val tracks = (m["tracks"] as? List<*>).orEmpty()
        RankBoard(
            id = id,
            name = decodeHtmlEntities(rankStr(m["name"])),
            cover = rankStr(m["coverImgUrl"]),
            subtitle = rankSubtitle(
                listOf(rankStr(m["updateFrequency"]), "${rankInt(m["trackCount"])} 首"),
            ),
            group = "",
            top = tracks.mapIndexedNotNull { index, track ->
                val tm = normRankMap(track) ?: return@mapIndexedNotNull null
                val artists = (tm["artists"] as? List<*>).orEmpty()
                    .mapNotNull { rankStr(normRankMap(it)?.get("name")) }
                    .filter { it.isNotEmpty() }
                val title = decodeHtmlEntities(
                    rankStr(tm["name"]).ifEmpty { rankStr(tm["first"]) },
                )
                if (title.isEmpty()) return@mapIndexedNotNull null
                RankPreview(
                    rank = index + 1,
                    title = title,
                    artist = decodeHtmlEntities(
                        artists.joinToString(" / ").ifEmpty { rankStr(tm["second"]) },
                    ),
                )
            },
        )
    }
}

internal suspend fun fetchNeteaseRankDetail(
    boardId: String,
    page: Int,
    pageSize: Int,
): RankDetail? {
    val resp = _neteaseRankDetailRaw(boardId) ?: return null
    val result = normRankMap(resp["result"]) ?: return null
    val tracks = (result["tracks"] as? List<*>).orEmpty()
    val songs = tracks.mapNotNull { track ->
        normRankMap(track)?.let { neteaseArtistSong(it) }
    }
    val total = rankInt(result["trackCount"]).takeIf { it > 0 } ?: songs.size
    return RankDetail(
        title = decodeHtmlEntities(rankStr(result["name"])),
        subtitle = rankSubtitle(
            listOf(
                rankStr(result["updateFrequency"]),
                "$total 首",
                "播放 ${rankStr(result["playCount"])}".takeIf { rankStr(result["playCount"]).isNotEmpty() }.orEmpty(),
            ),
        ),
        cover = rankStr(result["coverImgUrl"]),
        songs = _neteasePageSlice(songs, page, pageSize),
        total = total,
        page = page,
        pageSize = pageSize,
    )
}

private fun _neteasePageSlice(songs: List<Song>, page: Int, pageSize: Int): List<Song> {
    val from = (page - 1) * pageSize
    if (from <= 0 && songs.size <= pageSize) return songs
    if (from >= songs.size) return emptyList()
    return songs.subList(from, minOf(from + pageSize, songs.size))
}

private suspend fun _neteaseRankRawList(): List<Any?> {
    val key = neteaseRankCachePrefix + "boards"
    cacheService.get<Map<String, Any?>>(key)?.let { cached ->
        (cached["list"] as? List<*>)?.let { return it }
    }
    val resp = httpGetJson("$neteaseRankBase/toplist/detail", neteaseApiHeaders)
    if ((resp["list"] as? List<*>)?.isNotEmpty() == true) {
        cacheService.set(key, resp, HomeCacheTtlMs)
    }
    return (resp["list"] as? List<*>).orEmpty()
}

private suspend fun _neteaseRankDetailRaw(boardId: String): Map<String, Any?>? {
    val key = neteaseRankCachePrefix + "detail:$boardId"
    cacheService.get<Map<String, Any?>>(key)?.let { return it }
    val resp = httpGetJson("$neteaseRankBase/playlist/detail?id=$boardId", neteaseApiHeaders)
    if (resp["result"] is Map<*, *>) {
        cacheService.set(key, resp, HomeCacheTtlMs)
        return resp
    }
    return null
}
