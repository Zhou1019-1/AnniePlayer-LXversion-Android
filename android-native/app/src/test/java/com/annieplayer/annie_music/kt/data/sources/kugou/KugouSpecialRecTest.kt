package com.annieplayer.annie_music.kt.data.sources.kugou

import com.annieplayer.annie_music.kt.data.sources.common.md5Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouSpecialRecTest {

    private val params = linkedMapOf(
        "dfid" to "-",
        "appid" to "3116",
        "mid" to "abc123",
        "uuid" to "-",
        "clientver" to "11450",
        "clienttime" to "1795277769",
    )

    @Test
    fun signature_ignores_body_after_first_256_bytes() {
        val head = "x".repeat(256)
        val a = kugouSpecialSignature(params, head + "AAAA")
        val b = kugouSpecialSignature(params, head + "ZZZZ")
        assertEquals(a, b)
    }

    @Test
    fun signature_changes_when_body_prefix_changes() {
        val head = "x".repeat(256)
        val a = kugouSpecialSignature(params, head)
        val b = kugouSpecialSignature(params, "y" + head.drop(1))
        assertNotEquals(a, b)
    }

    @Test
    fun signature_is_order_independent_on_params() {
        val reversed = LinkedHashMap<String, String>()
        params.keys.reversed().forEach { reversed[it] = params.getValue(it) }
        assertEquals(
            kugouSpecialSignature(params, "body"),
            kugouSpecialSignature(reversed, "body"),
        )
    }

    @Test
    fun signature_skips_body_truncation_under_256_bytes() {
        val short = "{}"
        val once = kugouSpecialSignature(params, short)
        assertEquals(once, kugouSpecialSignature(params, short))
    }

    @Test
    fun body_carries_key_from_appid_secret_clientver_clienttime() {
        val body = kugouSpecialBody(
            appId = "3116",
            clientVer = "11450",
            mid = "abc123",
            clientTime = 1795277769L,
            page = 1,
            pageSize = 30,
            categoryId = "0",
        )
        val expectedKey = md5Hex("3116" + "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA" + "11450" + "1795277769")
        assertTrue(body.contains("\"key\":\"$expectedKey\""))
        assertTrue(body.contains("\"appid\":3116"))
        assertTrue(body.contains("\"clienttime\":1795277769"))
        assertTrue(body.contains("\"clientver\":11450"))
        assertTrue(body.contains("\"mid\":\"abc123\""))
        assertTrue(body.contains("\"categoryid\":\"0\""))
        assertTrue(body.contains("\"pagesize\":30"))
    }

    @Test
    fun body_uses_requested_category_and_page() {
        val body = kugouSpecialBody("3116", "11450", "m", 1L, page = 3, pageSize = 20, categoryId = "9")
        assertTrue(body.contains("\"page\":3"))
        assertTrue(body.contains("\"pagesize\":20"))
        assertTrue(body.contains("\"categoryid\":\"9\""))
    }

    @Test
    fun body_carries_sort_dimension() {
        assertEquals(1, Regex("\"sort\":1").findAll(kugouSpecialBody("3116", "11450", "m", 1L, 1, 20, "0", sort = 1)).count())
        assertEquals(1, Regex("\"sort\":2").findAll(kugouSpecialBody("3116", "11450", "m", 1L, 1, 20, "0", sort = 2)).count())
        assertEquals(1, Regex("\"sort\":3").findAll(kugouSpecialBody("3116", "11450", "m", 1L, 1, 20, "0")).count())
    }

    @Test
    fun query_params_match_signed_set() {
        val q = kugouSpecialQueryParams("3116", "11450", "m", 42L)
        assertEquals(
            listOf("appid", "clienttime", "clientver", "dfid", "mid", "uuid"),
            q.keys.sorted(),
        )
        assertEquals("42", q["clienttime"])
    }

    @Test
    fun cover_size_placeholder_replaced() {
        assertEquals(
            "http://c1.kgimg.com/custom/400/20221116/x.jpg",
            kugouSpecialCover("http://c1.kgimg.com/custom/{size}/20221116/x.jpg"),
        )
    }

    @Test
    fun parses_real_special_list_payload() {
        val raw = mapOf(
            "status" to 1,
            "data" to mapOf(
                "special_list" to listOf(
                    mapOf(
                        "specialid" to 6240313L,
                        "specialname" to "听前奏就会爱上的宝藏歌曲",
                        "nickname" to "Are",
                        "imgurl" to "http://c1.kgimg.com/custom/{size}/20221116/a.jpg",
                        "play_count" to 26053097L,
                        "collectcount" to 8765L,
                        "intro" to "第一行\n第二行",
                        "tags" to listOf(mapOf("tag_name" to "流行", "tag_id" to 9)),
                    ),
                    mapOf("specialid" to 0L, "specialname" to ""),
                    mapOf("specialname" to "没有 id"),
                ),
            ),
        )
        val items = parseKugouSpecialRec(raw)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("6240313", item.id)
        assertEquals("听前奏就会爱上的宝藏歌曲", item.name)
        assertEquals("Are", item.creator)
        assertEquals("http://c1.kgimg.com/custom/400/20221116/a.jpg", item.cover)
        assertEquals(26053097L, item.playCount)
        assertEquals(8765L, item.collectCount)
        assertEquals("第一行\n第二行", item.intro)
    }

    @Test
    fun falls_back_to_flexible_cover() {
        val raw = mapOf(
            "data" to mapOf(
                "special_list" to listOf(
                    mapOf(
                        "specialid" to 1L,
                        "specialname" to "歌单",
                        "flexible_cover" to "http://c1.kgimg.com/custom/{size}/b.jpg",
                    ),
                ),
            ),
        )
        assertEquals("http://c1.kgimg.com/custom/400/b.jpg", parseKugouSpecialRec(raw)[0].cover)
    }

    @Test
    fun missing_special_list_yields_empty() {
        assertEquals(0, parseKugouSpecialRec(emptyMap()).size)
        assertEquals(0, parseKugouSpecialRec(mapOf("data" to mapOf<String, Any?>())).size)
        assertEquals(0, parseKugouSpecialRec(mapOf("data" to "oops")).size)
    }
}
