package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleNewTabParseTest {

    @Test
    fun sections_map_to_renderers_in_page_order() {
        val raw = grouping(
            listOf(
                section(id = "hero", kind = 316, children = listOf(heroChild("h1", "年度最佳", playlist("pl.a", "A-List", "Apple Music")))),
                section(id = "tracks", kind = 327, name = "新歌精选", contents = listOf(song("1", "第一首", "歌手甲"))),
                section(id = "albums", kind = 326, name = "本周新发行", contents = listOf(album("a1", "专辑一", "歌手乙"))),
                section(id = "stations", kind = 326, name = "最新电台节目", contents = listOf(station("ra.1", "电台一"))),
                section(id = "genres", kind = 322, name = ""),
            ),
        )

        val sections = parseAppleNewTab(raw)

        assertEquals(3, sections.size)
        assertEquals(NewTabSectionKind.Hero, sections[0].kind)
        assertEquals(NewTabSectionKind.Tracks, sections[1].kind)
        assertEquals(NewTabSectionKind.Cards, sections[2].kind)
        assertEquals(listOf("hero", "tracks", "albums"), sections.map { it.id })
        assertEquals("新歌精选", sections[1].title)
    }

    @Test
    fun genres_and_station_only_sections_are_dropped() {
        val raw = grouping(
            listOf(
                section(id = "genres", kind = 322, links = listOf(mapOf("label" to "摇滚"))),
                section(id = "stations", kind = 326, contents = listOf(station("ra.1", "电台一"))),
                section(id = "videos", kind = 326, contents = listOf(video("v1", "MV 一"))),
            ),
        )
        assertTrue(parseAppleNewTab(raw).isEmpty())
    }

    @Test
    fun track_section_keeps_all_songs_and_maps_platform() {
        val raw = grouping(
            listOf(
                section(
                    id = "tracks",
                    kind = 327,
                    name = "正在流行中",
                    contents = (1..6).map { song("$it", "歌$it", "歌手$it") },
                ),
            ),
        )
        val section = parseAppleNewTab(raw).single()
        assertEquals(6, section.songs.size)
        assertEquals("apple:1", section.songs[0].id)
        assertEquals("歌1", section.songs[0].title)
        assertEquals("歌手1", section.songs[0].artist)
        assertEquals(MusicPlatform.apple, section.songs[0].platform)
        assertEquals("https://example.com/1/600x600bb.jpg", section.songs[0].cover)
    }

    @Test
    fun hero_card_carries_eyebrow_and_playlist_target() {
        val raw = grouping(
            listOf(
                section(
                    id = "hero",
                    kind = 316,
                    children = listOf(
                        heroChild("h1", "2026 年度最佳", playlist("pl.bd4", "国语流行", "Apple Music 国语流行")),
                    ),
                ),
            ),
        )
        val item = parseAppleNewTab(raw).single().items.single()
        assertEquals("2026 年度最佳", item.eyebrow)
        assertEquals("国语流行", item.title)
        assertEquals("Apple Music 国语流行", item.subtitle)
        assertEquals("https://example.com/pl.bd4/400x400bb.jpg", item.cover)
        assertTrue(item.atmos)
        val target = item.target
        assertTrue(target is HomeTarget.Playlist)
        assertEquals("pl.bd4", (target as HomeTarget.Playlist).id)
        assertEquals(MusicPlatform.apple, target.platform)
    }

    @Test
    fun mixed_card_section_keeps_albums_and_playlists_drops_stations() {
        val raw = grouping(
            listOf(
                section(
                    id = "mixed",
                    kind = 326,
                    name = "大家都在听",
                    contents = listOf(
                        album("a1", "专辑一", "歌手乙"),
                        station("ra.1", "电台一"),
                        playlist("pl.c", "歌单一", "Apple Music"),
                    ),
                ),
            ),
        )
        val section = parseAppleNewTab(raw).single()
        assertEquals(2, section.items.size)
        assertTrue(section.items[0].target is HomeTarget.Album)
        assertTrue(section.items[1].target is HomeTarget.Playlist)
        assertEquals("mixed:a1", section.items[0].key)
        assertEquals("mixed:pl.c", section.items[1].key)
    }

    @Test
    fun expanded_style_becomes_two_row_grid() {
        val raw = grouping(
            listOf(
                section(id = "a", kind = 326, contents = listOf(album("a1", "专辑一", "歌手")), displayStyle = "expanded"),
                section(id = "b", kind = 326, contents = listOf(album("a2", "专辑二", "歌手")), displayStyle = "compact"),
            ),
        )
        val sections = parseAppleNewTab(raw)
        assertTrue(sections[0].expanded)
        assertTrue(!sections[1].expanded)
    }

    @Test
    fun empty_payload_returns_nothing() {
        assertTrue(parseAppleNewTab(emptyMap()).isEmpty())
        assertTrue(parseAppleNewTab(mapOf("data" to emptyList<Any?>())).isEmpty())
    }

    @Test
    fun section_carries_room_id() {
        val raw = grouping(
            listOf(
                section(
                    id = "tracks",
                    kind = 327,
                    name = "新歌精选",
                    contents = listOf(song("1", "第一首", "歌手甲")),
                    roomId = "6834183219",
                ),
                section(id = "cards", kind = 326, contents = listOf(album("a1", "专辑一", "歌手"))),
            ),
        )
        val sections = parseAppleNewTab(raw)
        assertEquals("6834183219", sections[0].roomId)
        assertEquals("", sections[1].roomId)
    }

    @Test
    fun room_with_songs_becomes_track_room() {
        val raw = room(
            title = "新歌精选",
            contents = listOf(song("1", "第一首", "甲"), song("2", "第二首", "乙")),
        )
        val parsed = parseAppleNewTabRoom(raw)
        assertEquals("room1", parsed?.id)
        assertEquals("新歌精选", parsed?.title)
        assertEquals(NewTabSectionKind.Tracks, parsed?.kind)
        assertEquals(2, parsed?.songs?.size)
        assertEquals("apple:2", parsed?.songs?.get(1)?.id)
    }

    @Test
    fun room_with_cards_becomes_card_room() {
        val raw = room(
            title = "本周新发行",
            contents = listOf(
                album("a1", "专辑一", "歌手"),
                playlist("pl.c", "歌单一", "Apple Music"),
            ),
        )
        val parsed = parseAppleNewTabRoom(raw)
        assertEquals(NewTabSectionKind.Cards, parsed?.kind)
        assertEquals(2, parsed?.items?.size)
        assertTrue(parsed?.items?.get(0)?.target is HomeTarget.Album)
        assertTrue(parsed?.items?.get(1)?.target is HomeTarget.Playlist)
    }

    @Test
    fun empty_room_returns_null() {
        assertNull(parseAppleNewTabRoom(emptyMap()))
        assertNull(parseAppleNewTabRoom(mapOf("data" to listOf(mapOf("id" to "r")))))
    }

    private fun room(title: String, contents: List<Any?>): Map<String, Any?> = mapOf(
        "data" to listOf(
            mapOf(
                "id" to "room1",
                "attributes" to mapOf("title" to title),
                "relationships" to mapOf("contents" to mapOf("data" to contents)),
            ),
        ),
    )

    private fun grouping(sections: List<Any?>): Map<String, Any?> = mapOf(
        "data" to listOf(
            mapOf(
                "relationships" to mapOf(
                    "tabs" to mapOf(
                        "data" to listOf(
                            mapOf(
                                "relationships" to mapOf(
                                    "children" to mapOf("data" to sections),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun section(
        id: String,
        kind: Int,
        name: String = "",
        displayStyle: String = "compact",
        contents: List<Any?>? = null,
        children: List<Any?>? = null,
        links: List<Any?>? = null,
        roomId: String? = null,
    ): Map<String, Any?> {
        val attributes = buildMap<String, Any?> {
            put("editorialElementKind", kind.toString())
            put("name", name)
            put("displayStyle", displayStyle)
            if (links != null) put("links", links)
        }
        val relationships = buildMap<String, Any?> {
            if (contents != null) put("contents", mapOf("data" to contents))
            if (children != null) put("children", mapOf("data" to children))
            if (roomId != null) put("room", mapOf("data" to listOf(mapOf("id" to roomId))))
        }
        return buildMap {
            put("id", id)
            put("attributes", attributes)
            if (relationships.isNotEmpty()) put("relationships", relationships)
        }
    }

    private fun heroChild(id: String, badge: String, content: Any?): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "editorial-elements",
        "attributes" to mapOf(
            "designBadge" to badge,
            "editorialElementKind" to "317",
        ),
        "relationships" to mapOf("contents" to mapOf("data" to listOf(content))),
    )

    private fun song(id: String, name: String, artist: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "songs",
        "attributes" to mapOf(
            "name" to name,
            "artistName" to artist,
            "albumName" to "专辑",
            "artwork" to mapOf("url" to "https://example.com/$id/{w}x{h}bb.jpg"),
            "durationInMillis" to 200000,
            "audioTraits" to listOf("lossless", "atmos"),
            "trackNumber" to 1,
            "discNumber" to 1,
        ),
    )

    private fun album(id: String, name: String, artist: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "albums",
        "attributes" to mapOf(
            "name" to name,
            "artistName" to artist,
            "artwork" to mapOf("url" to "https://example.com/$id/{w}x{h}bb.jpg"),
            "trackCount" to 12,
            "audioTraits" to listOf("atmos"),
        ),
    )

    private fun playlist(id: String, name: String, curator: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "playlists",
        "attributes" to mapOf(
            "name" to name,
            "curatorName" to curator,
            "artwork" to mapOf("url" to "https://example.com/$id/{w}x{h}bb.jpg"),
            "audioTraits" to listOf("lossless", "atmos"),
        ),
    )

    private fun station(id: String, name: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "stations",
        "attributes" to mapOf(
            "name" to name,
            "artwork" to mapOf("url" to "https://example.com/$id/{w}x{h}bb.jpg"),
        ),
    )

    private fun video(id: String, name: String): Map<String, Any?> = mapOf(
        "id" to id,
        "type" to "music-videos",
        "attributes" to mapOf(
            "name" to name,
            "artwork" to mapOf("url" to "https://example.com/$id/{w}x{h}bb.jpg"),
        ),
    )
}
