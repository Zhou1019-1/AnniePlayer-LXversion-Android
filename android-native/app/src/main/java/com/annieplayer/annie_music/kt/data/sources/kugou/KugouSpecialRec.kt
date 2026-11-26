package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.network.httpPostJson
import com.annieplayer.annie_music.kt.data.sources.common.asText
import com.annieplayer.annie_music.kt.data.sources.common.decodeHtmlEntities
import com.annieplayer.annie_music.kt.data.sources.common.md5Hex

private const val kugouSpecialSecret = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
private const val kugouSpecialAppId = "3116"
private const val kugouSpecialClientVer = "11450"
private const val kugouSpecialSignBodyBytes = 256
private const val kugouSpecialRecUrl =
    "https://gateway.kugou.com/specialrec.service/special_recommend"
private const val kugouSpecialUserAgent = "Android16-1070-11450-201-0-playlist-wifi"

internal data class KugouSpecialItem(
    val id: String,
    val name: String,
    val creator: String,
    val cover: String,
    val playCount: Long,
    val collectCount: Long,
    val intro: String,
)

internal fun kugouSpecialBody(
    appId: String,
    clientVer: String,
    mid: String,
    clientTime: Long,
    page: Int,
    pageSize: Int,
    categoryId: String,
    sort: Int = 3,
): String {
    val key = md5Hex("$appId$kugouSpecialSecret$clientVer$clientTime")
    return buildString {
        append("{\"platform\":\"android\",\"module_id\":\"1\",")
        append("\"appid\":").append(appId).append(',')
        append("\"clienttime\":").append(clientTime).append(',')
        append("\"clientver\":").append(clientVer).append(',')
        append("\"mid\":\"").append(mid).append("\",")
        append("\"key\":\"").append(key).append("\",")
        append("\"page\":").append(page).append(',')
        append("\"pagesize\":").append(pageSize).append(',')
        append("\"req_multi\":1,\"return_min\":5,\"return_special_flag\":1,")
        append("\"userid\":\"0\",")
        append("\"special_recommend\":{\"withtag\":1,\"sort\":").append(sort).append(',')
        append("\"ugc\":1,")
        append("\"categoryid\":\"").append(categoryId).append("\",")
        append("\"is_selected\":0}}")
    }
}

internal fun kugouSpecialSignature(params: Map<String, String>, body: String): String {
    val joined = params.keys.sorted().joinToString("") { "$it=${params[it]}" }
    val head = (kugouSpecialSecret + joined).toByteArray(Charsets.UTF_8)
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    val take = minOf(bodyBytes.size, kugouSpecialSignBodyBytes)
    val tail = kugouSpecialSecret.toByteArray(Charsets.UTF_8)
    val all = ByteArray(head.size + take + tail.size)
    bodyBytes.copyInto(all, head.size, 0, take)
    head.copyInto(all, 0)
    tail.copyInto(all, head.size + take)
    return md5Hex(all)
}

internal fun kugouSpecialQueryParams(appId: String, clientVer: String, mid: String, clientTime: Long): Map<String, String> =
    linkedMapOf(
        "dfid" to "-",
        "appid" to appId,
        "mid" to mid,
        "uuid" to "-",
        "clientver" to clientVer,
        "clienttime" to clientTime.toString(),
    )

internal suspend fun fetchKugouSpecialRecRaw(
    page: Int,
    pageSize: Int,
    categoryId: String,
    sort: Int = 3,
): Map<String, Any?> {
    val mid = kugouMid()
    val clientTime = System.currentTimeMillis() / 1000
    val body = kugouSpecialBody(
        appId = kugouSpecialAppId,
        clientVer = kugouSpecialClientVer,
        mid = mid,
        clientTime = clientTime,
        page = page,
        pageSize = pageSize,
        categoryId = categoryId,
        sort = sort,
    )
    val params = kugouSpecialQueryParams(kugouSpecialAppId, kugouSpecialClientVer, mid, clientTime)
    val signature = kugouSpecialSignature(params, body)
    val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
    return httpPostJson(
        "$kugouSpecialRecUrl?$query&signature=$signature",
        mapOf(
            "dfid" to "-",
            "clienttime" to clientTime.toString(),
            "mid" to mid,
            "kg-rc" to "1",
            "kg-thash" to "5d816a0",
            "kg-rec" to "1",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            "User-Agent" to kugouSpecialUserAgent,
            "Content-Type" to "application/json",
        ),
        body,
    )
}

internal fun kugouSpecialCover(raw: String): String =
    raw.trim().replace("{size}", "400")

internal fun parseKugouSpecialRec(raw: Map<String, Any?>): List<KugouSpecialItem> {
    val data = raw["data"] as? Map<*, *> ?: return emptyList()
    val list = data["special_list"] as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
        val m = entry as? Map<*, *> ?: return@mapNotNull null
        val id = asText(m["specialid"])
        val name = decodeHtmlEntities(asText(m["specialname"]))
        if (id.isEmpty() || name.isEmpty()) return@mapNotNull null
        val cover = kugouSpecialCover(
            asText(m["imgurl"]).ifEmpty { asText(m["flexible_cover"]) },
        )
        KugouSpecialItem(
            id = id,
            name = name,
            creator = decodeHtmlEntities(asText(m["nickname"])),
            cover = cover,
            playCount = asText(m["play_count"]).toLongOrNull() ?: 0L,
            collectCount = asText(m["collectcount"]).toLongOrNull() ?: 0L,
            intro = decodeHtmlEntities(asText(m["intro"])),
        )
    }
}
