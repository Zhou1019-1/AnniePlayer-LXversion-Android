package com.annieplayer.annie_music.kt.data.sources.qq

import org.junit.Assert.assertEquals
import org.junit.Test

class QqAlbumArtistTest {

    private fun artistOf(item: Map<String, Any?>): String =
        qqMapAlbums(listOf(item)).first().artist

    @Test
    fun album_search_string_singer_uses_singer_list() {
        val item = mapOf(
            "albummid" to "0041WVfh2vtlJE",
            "name" to "太阳之子",
            "singer" to "<em>周杰伦</em>",
            "singer_list" to listOf(
                mapOf("id" to 4558, "name" to "周杰伦", "title" to "<em>周杰伦</em>"),
            ),
        )
        assertEquals("周杰伦", artistOf(item))
    }

    @Test
    fun multiple_singers_joined() {
        val item = mapOf(
            "albummid" to "0024bjiL2aocxT",
            "name" to "十一月的萧邦",
            "singer_list" to listOf(
                mapOf("name" to "周杰伦"),
                mapOf("name" to "方文山"),
            ),
        )
        assertEquals("周杰伦 / 方文山", artistOf(item))
    }

    @Test
    fun singer_array_form_still_works() {
        val item = mapOf(
            "albummid" to "000MkMni19ClKG",
            "albumName" to "叶惠美",
            "singer" to listOf(mapOf("name" to "周杰伦", "mid" to "0025NhlN2yWrP4")),
        )
        assertEquals("周杰伦", artistOf(item))
    }

    @Test
    fun highlight_tag_is_stripped_from_plain_singer_string() {
        val item = mapOf(
            "albummid" to "x",
            "name" to "专辑",
            "singer" to "<em>陈奕迅</em> &amp; <em>王菲</em>",
        )
        assertEquals("陈奕迅 & 王菲", artistOf(item))
    }

    @Test
    fun singer_name_field_used_last() {
        val item = mapOf("albummid" to "x", "name" to "专辑", "singerName" to "林俊杰")
        assertEquals("林俊杰", artistOf(item))
    }

    @Test
    fun missing_singer_yields_empty() {
        val item = mapOf("albummid" to "x", "name" to "专辑")
        assertEquals("", artistOf(item))
    }

    @Test
    fun album_name_highlight_stripped() {
        val item = mapOf(
            "albummid" to "x",
            "name" to "<em>范特西</em>",
            "singer_list" to listOf(mapOf("name" to "周杰伦")),
        )
        assertEquals("范特西", qqMapAlbums(listOf(item)).first().name)
    }

    @Test
    fun empty_singer_list_falls_through_to_singer() {
        val item = mapOf(
            "albummid" to "x",
            "name" to "专辑",
            "singer_list" to emptyList<Any?>(),
            "singer" to "<em>五月天</em>",
        )
        assertEquals("五月天", artistOf(item))
    }
}
