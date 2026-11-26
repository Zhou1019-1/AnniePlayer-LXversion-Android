package com.annieplayer.annie_music.kt.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumKindTest {

    @Test
    fun single_track_is_single() {
        assertEquals(ALBUM_KIND_SINGLE, albumKindByTrackCount(1))
    }

    @Test
    fun two_to_six_is_ep() {
        for (n in 2..6) assertEquals(ALBUM_KIND_EP, albumKindByTrackCount(n))
    }

    @Test
    fun seven_and_more_is_album() {
        assertEquals(ALBUM_KIND_ALBUM, albumKindByTrackCount(7))
        assertEquals(ALBUM_KIND_ALBUM, albumKindByTrackCount(24))
    }

    @Test
    fun unknown_count_yields_null() {
        assertNull(albumKindByTrackCount(0))
        assertNull(albumKindByTrackCount(-3))
    }

    @Test
    fun album_without_count_has_no_kind() {
        val album = Album(albumid = "x", name = "n", artist = "a", musiccnt = "0")
        assertNull(album.albumKind())
    }

    @Test
    fun album_with_numeric_count_has_kind() {
        val ep = Album(albumid = "x", name = "n", artist = "a", musiccnt = "4")
        assertEquals(ALBUM_KIND_EP, ep.albumKind())
        val garbage = Album(albumid = "y", name = "n", artist = "a", musiccnt = "abc")
        assertNull(garbage.albumKind())
    }

    @Test
    fun explicit_platform_kind_wins_over_count() {
        val apple = Album(
            albumid = "z",
            name = "n",
            artist = "a",
            musiccnt = "1",
            kind = ALBUM_KIND_SINGLE,
        )
        assertEquals(ALBUM_KIND_SINGLE, apple.albumKind())
        val forced = Album(
            albumid = "w",
            name = "n",
            artist = "a",
            musiccnt = "0",
            kind = ALBUM_KIND_EP,
        )
        assertEquals(ALBUM_KIND_EP, forced.albumKind())
    }
}
