package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetJson
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.home.HomeCacheTtlMs
import com.annieplayer.annie_music.kt.data.sources.kuwo.kuwoMapSong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val kuwoRankListUrl =
    "http://qukudata.kuwo.cn/q.k?op=query&cont=tree&node=2&pn=0&rn=1000&fmt=json&level=2"
private const val kuwoWbdUrl = "https://wbd.kuwo.cn/api/bd/bang/bang_info"
private const val kuwoRankCachePrefix = "rank:kuwo:"
private const val kuwoRankTopTtlMs = 6L * 60 * 60 * 1000
private const val kuwoRankTopBudgetMs = 4000L
private const val kuwoRankTopConcurrency = 6

internal suspend fun fetchKuwoRankBoards(): List<RankBoard> {
    val boards = parseKuwoRankChild(_kuwoRankRawList())
    if (boards.isEmpty()) return boards
    val tops = _kuwoRankTops(boards.map { it.id })
    return boards.map { board -> board.copy(top = tops[board.id].orEmpty()) }
}

private suspend fun _kuwoRankTops(ids: List<String>): Map<String, List<RankPreview>> {
    val out = ConcurrentHashMap<String, List<RankPreview>>()
    val gate = Semaphore(kuwoRankTopConcurrency)
    withTimeoutOrNull(kuwoRankTopBudgetMs) {
        coroutineScope {
            for (id in ids) {
                launch {
                    gate.acquire()
                    try {
                        val previews = _kuwoRankTop(id)
                        if (previews.isNotEmpty()) out[id] = previews
                    } finally {
                        gate.release()
                    }
                }
            }
        }
    }
    return out
}

private suspend fun _kuwoRankTop(bangId: String): List<RankPreview> {
    val key = "$kuwoRankCachePrefix" + "top:$bangId"
    cacheService.get<List<Any?>>(key)?.let { cached ->
        val parsed = cached.mapNotNull { _kuwoPreviewOf(it) }
        if (parsed.isNotEmpty()) return parsed
    }
    val detail = fetchKuwoRankDetail(bangId, 1, 3) ?: return emptyList()
    val previews = detail.songs.take(3).mapIndexed { index, song ->
        RankPreview(rank = index + 1, title = song.title, artist = song.artist)
    }
    if (previews.isNotEmpty()) {
        cacheService.set(
            key,
            previews.map { mapOf("rank" to it.rank, "title" to it.title, "artist" to it.artist) },
            kuwoRankTopTtlMs,
        )
    }
    return previews
}

private fun _kuwoPreviewOf(raw: Any?): RankPreview? {
    val m = normRankMap(raw) ?: return null
    val title = rankStr(m["title"])
    if (title.isEmpty()) return null
    return RankPreview(rank = rankInt(m["rank"]), title = title, artist = rankStr(m["artist"]))
}

internal fun parseKuwoRankChild(raw: List<Any?>): List<RankBoard> {
    return raw.mapNotNull { entry ->
        val m = normRankMap(entry) ?: return@mapNotNull null
        if (rankStr(m["source"]) != "1") return@mapNotNull null
        val bangId = rankStr(m["sourceid"])
        if (bangId.isEmpty()) return@mapNotNull null
        RankBoard(
            id = bangId,
            name = decodeHtmlEntities(rankStr(m["name"]).ifEmpty { rankStr(m["disname"]) }),
            cover = rankStr(m["pic"]),
            subtitle = decodeHtmlEntities(rankStr(m["info"])),
            group = "",
            top = emptyList(),
        )
    }
}

internal suspend fun fetchKuwoRankDetail(
    boardId: String,
    page: Int,
    pageSize: Int,
): RankDetail? {
    val payload = "{\"uid\":\"\",\"devId\":\"\",\"sFrom\":\"kuwo_sdk\",\"user_type\":\"AP\"," +
        "\"carSource\":\"kwplayercar_ar_6.0.1.0_apk_keluze.apk\"," +
        "\"id\":\"$boardId\",\"pn\":${(page - 1).coerceAtLeast(0)},\"rn\":$pageSize}"
    val body = runCatching {
        httpGetText(
            "$kuwoWbdUrl?" + kuwoWbdBuildParam(payload, System.currentTimeMillis()),
        )
    }.getOrNull() ?: return null
    val plain = runCatching { kuwoWbdDecodeData(body) }.getOrNull() ?: return null
    val parsed = runCatching {
        jsonElementToAny(json.parseToJsonElement(plain)) as? Map<*, *>
    }.getOrNull() ?: return null
    if (rankInt(parsed["code"]) != 200) return null
    val data = normRankMap(parsed["data"]) ?: return null
    val list = (data["musiclist"] as? List<*>).orEmpty()
    val songs = list.mapNotNull { entry ->
        normRankMap(entry)?.let { _kuwoRankSong(it) }
    }
    val total = rankInt(data["total"]).takeIf { it > 0 } ?: songs.size
    return RankDetail(
        title = decodeHtmlEntities(rankStr(data["name"])),
        subtitle = rankSubtitle(listOf("$total 首", rankStr(data["releaseDate"]))),
        cover = rankStr(data["pic"]),
        songs = songs,
        total = total,
        page = page,
        pageSize = pageSize,
    )
}

private fun _kuwoRankSong(m: Map<String, Any?>): Song? {
    val id = rankStr(m["id"])
    if (id.isEmpty()) return null
    val normalized = m + mapOf(
        "MINFO" to (m["MINFO"] ?: m["minfo"]),
        "N_MINFO" to (m["N_MINFO"] ?: m["nMinfo"] ?: m["n_minfo"]),
        "albumid" to (m["albumid"] ?: m["albumId"]),
        "artistid" to (m["artistid"] ?: m["artistId"]),
    )
    return kuwoMapSong(normalized)
}

private suspend fun _kuwoRankRawList(): List<Any?> {
    val key = kuwoRankCachePrefix + "boards"
    cacheService.get<Map<String, Any?>>(key)?.let { cached ->
        (cached["child"] as? List<*>)?.let { return it }
    }
    val resp = runCatching { httpGetJson(kuwoRankListUrl) }.getOrNull() ?: return emptyList()
    val child = (resp["child"] as? List<*>).orEmpty()
    if (child.isNotEmpty()) cacheService.set(key, resp, HomeCacheTtlMs)
    return child
}
