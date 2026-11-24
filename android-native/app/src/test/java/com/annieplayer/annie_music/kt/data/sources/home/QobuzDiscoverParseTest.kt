package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QobuzDiscoverParseTest {

    private fun album(
        id: String,
        title: String,
        version: String? = null,
        artist: String = "Caixa Cubo",
        large: String = "https://static.qobuz.com/images/covers/r1/ji/${id}_600.jpg",
        year: String = "2026-11-20",
    ): Map<String, Any?> = mapOf(
        "id" to id,
        "title" to title,
        "version" to version,
        "track_count" to 13,
        "image" to mapOf(
            "small" to "https://static.qobuz.com/images/covers/r1/ji/${id}_230.jpg",
            "thumbnail" to "https://static.qobuz.com/images/covers/r1/ji/${id}_50.jpg",
            "large" to large,
        ),
        "artists" to listOf(
            mapOf("id" to 1, "name" to artist, "roles" to listOf("main-artist")),
            mapOf("id" to 2, "name" to "Feature", "roles" to listOf("featured")),
        ),
        "dates" to mapOf("original" to year, "stream" to year),
    )

    private fun playlist(id: String, name: String, owner: String = "Qobuz BR e PT"): Map<String, Any?> =
        mapOf(
            "id" to id,
            "name" to name,
            "owner" to mapOf("id" to 7, "name" to owner),
            "image" to mapOf(
                "rectangle" to "https://static.qobuz.com/images/playlists/${id}_rectangle.jpg",
                "covers" to listOf("https://static.qobuz.com/images/covers/a_300.jpg"),
                "square" to mapOf("full" to "https://d16tqhufkg1ly0.cloudfront.net/${id}.jpg"),
            ),
        )

    private fun container(items: List<Any?>): Map<String, Any?> = mapOf(
        "id" to "x",
        "data" to mapOf("has_more" to true, "items" to items),
    )

    private fun rawPayload(): Map<String, Any?> = mapOf(
        "containers" to mapOf(
            "new_releases" to container(listOf(album("piro50vp9jir1", "Ultracooked"))),
            "playlists" to container(listOf(playlist("7818912", "Lançamentos"))),
            "playlists_tags" to container(listOf(mapOf("id" to 51, "slug" to "hi-res", "name" to "Hi-Res"))),
            "ideal_discography" to container(listOf(album("ideal1", "Kind of Blue"))),
            "qobuzissims" to container(listOf(album("qob1", "Qobuzissime Pick"))),
            "album_of_the_week" to container(listOf(album("aotw1", "Album of the Week"))),
            "press_awards" to container(listOf(album("press1", "Press Pick"))),
            "most_streamed" to container(listOf(album("top1", "Top One"), album("top2", "Top Two"))),
            "banners" to container(emptyList()),
            "recent_releases" to container(emptyList()),
        ),
    )

    @Test
    fun sections_follow_qobuz_editor_pick_order() {
        val sections = parseQobuzDiscover(rawPayload())
        assertEquals(
            listOf(
                "new_releases",
                "playlists",
                "playlists_tags",
                "ideal_discography",
                "qobuzissims",
                "album_of_the_week",
                "press_awards",
                "most_streamed",
            ),
            sections.map { it.id },
        )
    }

    @Test
    fun empty_containers_are_dropped() {
        val sections = parseQobuzDiscover(rawPayload())
        assertTrue(sections.none { it.id == "banners" })
        assertTrue(sections.none { it.id == "recent_releases" })
    }

    @Test
    fun titles_and_baselines_match_qobuz_strings() {
        val sections = parseQobuzDiscover(rawPayload()).associateBy { it.id }
        assertEquals("New Releases", sections.getValue("new_releases").title)
        assertEquals("Handpicked by Qobuz.", sections.getValue("new_releases").baseline)
        assertEquals("Qobuz Playlists", sections.getValue("playlists").title)
        assertEquals("Curated by our experts.", sections.getValue("playlists").baseline)
        assertEquals("Playlists by Category", sections.getValue("playlists_tags").title)
        assertEquals("Qobuz Essential Discography", sections.getValue("ideal_discography").title)
        assertEquals("Qobuzissime", sections.getValue("qobuzissims").title)
        assertEquals("Albums of the Week", sections.getValue("album_of_the_week").title)
        assertEquals("Press Accolades", sections.getValue("press_awards").title)
        assertEquals("Top Albums", sections.getValue("most_streamed").title)
        assertEquals(
            "Most streamed titles from the community.",
            sections.getValue("most_streamed").baseline,
        )
    }

    @Test
    fun kinds_match_measured_card_widths() {
        val sections = parseQobuzDiscover(rawPayload()).associateBy { it.id }
        assertEquals(QobuzDiscoverKind.AlbumRow, sections.getValue("new_releases").kind)
        assertEquals(QobuzDiscoverKind.PlaylistRow, sections.getValue("playlists").kind)
        assertEquals(QobuzDiscoverKind.TagRow, sections.getValue("playlists_tags").kind)
        assertEquals(QobuzDiscoverKind.AlbumRow, sections.getValue("ideal_discography").kind)
        assertEquals(QobuzDiscoverKind.AlbumRowWide, sections.getValue("qobuzissims").kind)
        assertEquals(QobuzDiscoverKind.AlbumRow, sections.getValue("album_of_the_week").kind)
        assertEquals(QobuzDiscoverKind.AlbumRowLarge, sections.getValue("press_awards").kind)
        assertEquals(QobuzDiscoverKind.RankedRow, sections.getValue("most_streamed").kind)
    }

    @Test
    fun album_item_maps_title_subtitle_cover_and_target() {
        val sections = parseQobuzDiscover(rawPayload()).associateBy { it.id }
        val item = sections.getValue("new_releases").items.single()
        assertEquals("Ultracooked", item.title)
        assertEquals("Caixa Cubo • 2026", item.subtitle)
        assertEquals("https://static.qobuz.com/images/covers/r1/ji/piro50vp9jir1_600.jpg", item.cover)
        val target = item.target as HomeTarget.Album
        assertEquals("piro50vp9jir1", target.id)
        assertEquals("Ultracooked", target.name)
        assertEquals("Caixa Cubo", target.artist)
    }

    @Test
    fun album_version_is_appended_in_parentheses() {
        val payload = mapOf(
            "containers" to mapOf(
                "new_releases" to container(
                    listOf(album("v1", "TYLA", version = "Deluxe Edition")),
                ),
            ),
        )
        val item = parseQobuzDiscover(payload).first().items.single()
        assertEquals("TYLA (Deluxe Edition)", item.title)
    }

    @Test
    fun album_cover_prefers_large_then_falls_back() {
        assertEquals(
            "https://static.qobuz.com/images/covers/r1/ji/a_600.jpg",
            qobuzDiscoverAlbumCover(mapOf("large" to "https://static.qobuz.com/images/covers/r1/ji/a_600.jpg")),
        )
        assertEquals(
            "thumb.jpg",
            qobuzDiscoverAlbumCover(mapOf("thumbnail" to "thumb.jpg", "small" to "small.jpg")),
        )
        assertEquals("", qobuzDiscoverAlbumCover(null))
    }

    @Test
    fun playlist_item_uses_square_cover_and_owner() {
        val sections = parseQobuzDiscover(rawPayload()).associateBy { it.id }
        val item = sections.getValue("playlists").items.single()
        assertEquals("Lançamentos", item.title)
        assertEquals("Qobuz BR e PT", item.subtitle)
        assertEquals("https://d16tqhufkg1ly0.cloudfront.net/7818912.jpg", item.cover)
        val target = item.target as HomeTarget.Playlist
        assertEquals("7818912", target.id)
        assertEquals("Lançamentos", target.name)
    }

    @Test
    fun playlist_cover_falls_back_to_rectangle_then_covers() {
        assertEquals(
            "rect.jpg",
            qobuzDiscoverPlaylistCover(mapOf("rectangle" to "rect.jpg")),
        )
        assertEquals(
            "cover0.jpg",
            qobuzDiscoverPlaylistCover(mapOf("covers" to listOf("cover0.jpg"))),
        )
        assertEquals("", qobuzDiscoverPlaylistCover(null))
    }

    @Test
    fun tag_item_has_no_target_and_no_cover() {
        val sections = parseQobuzDiscover(rawPayload()).associateBy { it.id }
        val item = sections.getValue("playlists_tags").items.single()
        assertEquals("Hi-Res", item.title)
        assertEquals("51", item.key)
        assertEquals("hi-res", item.slug)
        assertEquals("", item.cover)
        assertNull(item.target)
    }

    @Test
    fun ranked_items_get_one_based_rank() {
        val items = parseQobuzDiscover(rawPayload()).associateBy { it.id }.getValue("most_streamed").items
        assertEquals(listOf(1, 2), items.map { it.rank })
        assertEquals(listOf("top1", "top2"), items.map { it.key })
    }

    @Test
    fun unranked_items_have_zero_rank() {
        val items = parseQobuzDiscover(rawPayload()).associateBy { it.id }.getValue("new_releases").items
        assertEquals(0, items.single().rank)
    }

    @Test
    fun artist_prefers_main_artist_role() {
        val artists = listOf(
            mapOf("name" to "Feature", "roles" to listOf("featured")),
            mapOf("name" to "Main", "roles" to listOf("main-artist")),
        )
        assertEquals("Main", qobuzDiscoverArtistName(artists))
        assertEquals("", qobuzDiscoverArtistName(emptyList<Any?>()))
        assertEquals("", qobuzDiscoverArtistName(null))
    }

    @Test
    fun year_prefers_original_date() {
        assertEquals(
            "1999",
            qobuzDiscoverYear(mapOf("original" to "1999-05-01", "stream" to "2020-01-01")),
        )
        assertEquals("2020", qobuzDiscoverYear(mapOf("stream" to "2020-01-01")))
        assertEquals("", qobuzDiscoverYear(null))
    }

    @Test
    fun items_without_id_or_title_are_skipped() {
        val payload = mapOf(
            "containers" to mapOf(
                "new_releases" to container(
                    listOf(
                        mapOf("id" to "", "title" to "no id"),
                        mapOf("id" to "ok", "title" to ""),
                        album("keep", "Kept"),
                    ),
                ),
            ),
        )
        val items = parseQobuzDiscover(payload).first().items
        assertEquals(listOf("keep"), items.map { it.key })
    }

    @Test
    fun payload_without_containers_is_empty() {
        assertTrue(parseQobuzDiscover(emptyMap()).isEmpty())
        assertTrue(parseQobuzDiscover(mapOf("containers" to null)).isEmpty())
    }
}
