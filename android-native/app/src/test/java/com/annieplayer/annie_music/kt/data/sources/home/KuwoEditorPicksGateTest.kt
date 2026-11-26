package com.annieplayer.annie_music.kt.data.sources.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuwoEditorPicksGateTest {

    private fun section(type: String, label: String, body: String): String =
        "<section type=\"$type\" label=\"$label\">$body</section>"

    private fun songlist(id: String, name: String): String =
        "<songlist id=\"$id\" name=\"$name\" type=\"songlist\"/>"

    @Test
    fun empty_editor_section_means_server_did_not_deliver() {
        val xml = section("scroll_songlist", "编辑精选", "")
        assertFalse(kuwoHasEditorSongs(xml))
    }

    @Test
    fun banner_only_does_not_count_as_editor() {
        val xml = section(
            "banner",
            "",
            "<songlist id=\"3741062749\" name=\"声生不息\" statistics_id=\"text_ad_68758\" type=\"songlist\"/>",
        )
        assertFalse(kuwoHasEditorSongs(xml))
    }

    @Test
    fun banner_plus_empty_editor_still_not_delivered() {
        val xml =
            section(
                "banner",
                "",
                "<songlist id=\"1\" name=\"广告位\" statistics_id=\"text_ad_1\" type=\"songlist\"/>",
            ) +
                section("scroll_songlist", "编辑精选", "")
        assertFalse(kuwoHasEditorSongs(xml))
    }

    @Test
    fun delivered_editor_section_is_detected() {
        val xml = section(
            "scroll_songlist",
            "编辑精选",
            songlist("100", "歌单一") + songlist("101", "歌单二"),
        )
        assertTrue(kuwoHasEditorSongs(xml))
    }

    @Test
    fun ultra_variant_is_detected() {
        val xml = section(
            "scroll_songlist_ultra",
            "编辑精选",
            songlist("200", "歌单一"),
        )
        assertTrue(kuwoHasEditorSongs(xml))
    }

    @Test
    fun null_or_blank_xml_is_false() {
        assertFalse(kuwoHasEditorSongs(null))
        assertFalse(kuwoHasEditorSongs(""))
    }

    @Test
    fun unrelated_sections_are_false() {
        val xml =
            section("tab_scroll_list", "数字专辑", "<music id=\"9\" name=\"歌\"/>") +
                section("r10_tuijian_bang", "排行榜", "<music id=\"10\" name=\"歌\"/>")
        assertFalse(kuwoHasEditorSongs(xml))
    }
}
