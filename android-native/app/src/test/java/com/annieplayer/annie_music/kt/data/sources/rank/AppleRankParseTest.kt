package com.annieplayer.annie_music.kt.data.sources.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleRankParseTest {

    private fun song(id: String, name: String, artist: String) = mapOf(
        "id" to id,
        "type" to "songs",
        "attributes" to mapOf(
            "name" to name,
            "artistName" to artist,
            "artwork" to mapOf(
                "url" to "https://is1-ssl.mzstatic.com/image/thumb/$id/{w}x{h}bb.jpg",
            ),
        ),
    )

    private fun playlist(id: String, name: String, curator: String) = mapOf(
        "id" to id,
        "type" to "playlists",
        "attributes" to mapOf(
            "name" to name,
            "curatorName" to curator,
            "artwork" to mapOf(
                "url" to "https://is1-ssl.mzstatic.com/image/thumb/$id/{w}x{h}SC.DN02.jpg",
            ),
        ),
    )

    private fun album(id: String, name: String, artist: String) = mapOf(
        "id" to id,
        "type" to "albums",
        "attributes" to mapOf(
            "name" to name,
            "artistName" to artist,
            "artwork" to mapOf(
                "url" to "https://is1-ssl.mzstatic.com/image/thumb/$id/{w}x{h}bb.jpg",
            ),
        ),
    )

    @Test
    fun songs_chart_keeps_api_order() {
        val raw = mapOf(
            "results" to mapOf(
                "songs" to listOf(
                    mapOf(
                        "chart" to "most-played",
                        "name" to "热门歌曲排行",
                        "data" to listOf(
                            song("1", "我不难过", "孙燕姿"),
                            song("2", "晴天", "周杰伦"),
                            song("3", "七里香", "周杰伦"),
                        ),
                    ),
                ),
            ),
        )
        val section = parseAppleRankCharts(raw).single()
        assertEquals(AppleRankSongChartId, section.id)
        assertEquals("热门歌曲排行", section.title)
        assertEquals(AppleChartKind.Tracks, section.kind)
        assertEquals(listOf("我不难过", "晴天", "七里香"), section.entries.map { it.title })
        assertEquals(listOf("孙燕姿", "周杰伦", "周杰伦"), section.entries.map { it.subtitle })
        assertEquals("apple:1", section.entries[0].song?.id)
        assertNull(section.entries[0].target)
        assertTrue(section.entries[0].cover.endsWith("600x600bb.jpg"))
    }

    @Test
    fun playlist_chart_maps_to_playlist_target() {
        val raw = mapOf(
            "results" to mapOf(
                "playlists" to listOf(
                    mapOf(
                        "name" to "热门歌单排行",
                        "data" to listOf(
                            playlist("pl.aaa", "今日热门", "Apple Music 热门"),
                            playlist("pl.bbb", "国语情歌精选", "Apple Music 国语流行"),
                        ),
                    ),
                ),
            ),
        )
        val section = parseAppleRankCharts(raw).single()
        assertEquals(AppleRankPlaylistChartId, section.id)
        assertEquals(AppleChartKind.Cards, section.kind)
        assertEquals(listOf("pl.aaa", "pl.bbb"), section.entries.map { it.key.removePrefix("playlist:") })
        assertEquals("Apple Music 热门", section.entries[0].subtitle)
        assertTrue(section.entries[0].cover.endsWith("400x400SC.DN02.jpg"))
        val target = section.entries[0].target
        assertTrue(target is com.annieplayer.annie_music.kt.data.model.HomeTarget.Playlist)
    }

    @Test
    fun album_chart_maps_to_album_target() {
        val raw = mapOf(
            "results" to mapOf(
                "albums" to listOf(
                    mapOf(
                        "name" to "热门专辑排行",
                        "data" to listOf(album("6829612008", "PERFECT STORM - EP", "TOMORROW X TOGETHER")),
                    ),
                ),
            ),
        )
        val section = parseAppleRankCharts(raw).single()
        assertEquals(AppleRankAlbumChartId, section.id)
        assertEquals("TOMORROW X TOGETHER", section.entries[0].subtitle)
        val target = section.entries.single().target
        assertTrue(target is com.annieplayer.annie_music.kt.data.model.HomeTarget.Album)
    }

    @Test
    fun ranked_sections_keep_am_order_songs_playlists_albums() {
        val raw = mapOf(
            "results" to mapOf(
                "albums" to listOf(mapOf("name" to "热门专辑排行", "data" to listOf(album("3", "c", "x")))),
                "playlists" to listOf(mapOf("name" to "热门歌单排行", "data" to listOf(playlist("pl.a", "b", "x")))),
                "songs" to listOf(mapOf("name" to "热门歌曲排行", "data" to listOf(song("1", "a", "x")))),
            ),
        )
        val ids = parseAppleRankCharts(raw).map { it.id }
        assertEquals(
            listOf(AppleRankSongChartId, AppleRankPlaylistChartId, AppleRankAlbumChartId),
            ids,
        )
    }

    @Test
    fun empty_and_malformed_payloads_yield_nothing() {
        assertTrue(parseAppleRankCharts(emptyMap()).isEmpty())
        assertTrue(parseAppleRankCharts(mapOf("results" to "nope")).isEmpty())
        assertTrue(
            parseAppleRankCharts(
                mapOf("results" to mapOf("songs" to listOf(mapOf("name" to "空榜", "data" to emptyList<Any?>())))),
            ).isEmpty(),
        )
    }

    @Test
    fun missing_names_fall_back_to_am_titles() {
        val raw = mapOf(
            "results" to mapOf(
                "songs" to listOf(mapOf("data" to listOf(song("1", "a", "x")))),
                "playlists" to listOf(mapOf("data" to listOf(playlist("pl.a", "b", "x")))),
                "albums" to listOf(mapOf("data" to listOf(album("3", "c", "x")))),
            ),
        )
        assertEquals(
            listOf("热门歌曲排行", "热门歌单排行", "热门专辑排行"),
            parseAppleRankCharts(raw).map { it.title },
        )
    }

    @Test
    fun rows_without_id_or_name_are_dropped() {
        val raw = mapOf(
            "results" to mapOf(
                "playlists" to listOf(
                    mapOf(
                        "name" to "热门歌单排行",
                        "data" to listOf(
                            mapOf("attributes" to mapOf("name" to "没有 id")),
                            mapOf("id" to "pl.noname", "attributes" to mapOf("curatorName" to "Apple Music")),
                            playlist("pl.aaa", "今日热门", "热门"),
                        ),
                    ),
                ),
            ),
        )
        val section = parseAppleRankCharts(raw).single()
        assertEquals(listOf("playlist:pl.aaa"), section.entries.map { it.key })
    }
}
