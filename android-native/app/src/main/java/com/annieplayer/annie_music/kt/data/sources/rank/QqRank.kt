package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.network.httpGetText
import com.annieplayer.annie_music.kt.data.network.json
import com.annieplayer.annie_music.kt.data.network.jsonElementToAny
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.home.HomeCacheTtlMs
import com.annieplayer.annie_music.kt.data.sources.qq.qqArtistSong
import com.annieplayer.annie_music.kt.data.sources.qq.qqComm
import com.annieplayer.annie_music.kt.data.sources.qq.qqHeaders
import com.annieplayer.annie_music.kt.data.sources.qq.qqMusicuPost

private const val qqRankListUrl =
    "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_opt.fcg?page=index&format=html&tpl=macv4&v8debug=1"
private const val qqRankCachePrefix = "rank:qq:"

private val _qqJsonCallback by lazy { Regex("""jsonCallback\s*\(\s*([\s\S]*?)\s*\)\s*$""") }

internal suspend fun fetchQqRankBoards(): List<RankBoard> =
    parseQqRankGroups(_qqRankGroups())

internal fun parseQqRankGroups(groups: List<Any?>): List<RankBoard> {
    val out = mutableListOf<RankBoard>()
    for (group in groups) {
        val gm = normRankMap(group) ?: continue
        val groupName = decodeHtmlEntities(rankStr(gm["GroupName"]))
        val list = (gm["List"] as? List<*>).orEmpty()
        for (entry in list) {
            val m = normRankMap(entry) ?: continue
            val topId = rankStr(m["topID"])
            if (topId.isEmpty()) continue
            val previews = (m["songlist"] as? List<*>).orEmpty().mapIndexedNotNull { index, song ->
                val sm = normRankMap(song) ?: return@mapIndexedNotNull null
                RankPreview(
                    rank = index + 1,
                    title = decodeHtmlEntities(rankStr(sm["songname"])),
                    artist = decodeHtmlEntities(rankStr(sm["singername"])),
                )
            }
            out.add(
                RankBoard(
                    id = topId,
                    name = decodeHtmlEntities(rankStr(m["ListName"])),
                    cover = rankStr(m["pic_v12"]).ifEmpty { rankStr(m["pic"]) },
                    subtitle = rankSubtitle(
                        listOf(
                            rankStr(m["showtime"]),
                            "${rankInt(m["listennum"])}".takeIf { rankInt(m["listennum"]) > 0 }
                                ?.let { "听过 $it" }.orEmpty(),
                        ),
                    ),
                    group = groupName,
                    top = previews,
                ),
            )
        }
    }
    return out
}

internal suspend fun fetchQqRankDetail(
    boardId: String,
    page: Int,
    pageSize: Int,
): RankDetail? {
    val topId = boardId.toIntOrNull() ?: return null
    val offset = (page - 1).coerceAtLeast(0) * pageSize
    val resp = _qqRankDetailRaw(topId, offset, pageSize) ?: return null
    val req0 = normRankMap(resp["req_0"]) ?: return null
    val outer = normRankMap(req0["data"]) ?: return null
    val top = normRankMap(outer["data"]) ?: return null
    val infoList = (outer["songInfoList"] as? List<*>).orEmpty()
    val songs = infoList.mapNotNull { entry ->
        normRankMap(entry)?.let { qqArtistSong(it) }
    }
    val total = rankInt(top["totalNum"]).takeIf { it > 0 } ?: songs.size
    return RankDetail(
        title = decodeHtmlEntities(rankStr(top["title"])),
        subtitle = rankSubtitle(
            listOf(
                rankStr(top["period"]),
                "$total 首",
                rankStr(top["listenNum"]).takeIf { it.isNotEmpty() && it != "0" }
                    ?.let { "听过 $it" }.orEmpty(),
            ),
        ),
        cover = "",
        songs = songs,
        total = total,
        page = page,
        pageSize = pageSize,
    )
}

private suspend fun _qqRankGroups(): List<Any?> {
    val key = qqRankCachePrefix + "boards"
    cacheService.get<List<Any?>>(key)?.let { return it }
    val text = httpGetText(qqRankListUrl, qqHeaders)
    val body = _qqJsonCallback.find(text)?.groupValues?.getOrNull(1) ?: text
    val parsed = runCatching {
        jsonElementToAny(json.parseToJsonElement(body))
    }.getOrNull() as? List<*> ?: return emptyList()
    val list = parsed.toList()
    if (list.isNotEmpty()) cacheService.set(key, list, HomeCacheTtlMs)
    return list
}

private suspend fun _qqRankDetailRaw(
    topId: Int,
    offset: Int,
    num: Int,
): Map<String, Any?>? {
    val key = qqRankCachePrefix + "detail:$topId:$offset:$num"
    cacheService.get<Map<String, Any?>>(key)?.let { return it }
    val payload = mapOf(
        "req_0" to mapOf(
            "module" to "music.musicToplist.Toplist",
            "method" to "GetDetail",
            "param" to mapOf("topId" to topId, "offset" to offset, "num" to num),
        ),
        "comm" to qqComm(),
    )
    val resp = qqMusicuPost(payload)
    val req0 = normRankMap(resp["req_0"]) ?: return null
    if (rankInt(req0["code"]) != 0) return null
    cacheService.set(key, resp, HomeCacheTtlMs)
    return resp
}
