package com.annieplayer.annie_music.kt.data.favorites

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class UserPlaylistCoverTest {

    private fun song(id: String, cover: String) = Song(
        id = id,
        title = id,
        artist = "",
        cover = cover,
        duration = 0,
        platform = MusicPlatform.netease,
    )

    private fun playlist(
        cover: String = "",
        songs: List<Song> = emptyList(),
    ) = UserPlaylist(id = "p", name = "p", cover = cover, createdAt = 1L, songs = songs)

    @Test
    fun `空歌单没有兜底封面`() {
        assertEquals("", playlist().fallbackCover)
    }

    @Test
    fun `取最新加入那首的封面`() {
        val p = playlist(songs = listOf(song("a", "url-a"), song("b", "url-b")))
        assertEquals("url-b", p.fallbackCover)
    }

    @Test
    fun `最新那首没封面就往前找`() {
        val p = playlist(songs = listOf(song("a", "url-a"), song("b", "")))
        assertEquals("url-a", p.fallbackCover)
    }

    @Test
    fun `显式封面优先于歌曲封面`() {
        val p = playlist(cover = "explicit", songs = listOf(song("a", "url-a")))
        assertEquals("explicit", p.fallbackCover)
    }

    @Test
    fun `加歌之后封面跟着变`() {
        val empty = playlist()
        assertEquals("", empty.fallbackCover)
        val first = empty.copy(songs = empty.songs + song("a", "url-a"))
        assertEquals("url-a", first.fallbackCover)
        val second = first.copy(songs = first.songs + song("b", "url-b"))
        assertEquals("url-b", second.fallbackCover)
    }

    @Test
    fun `删掉最新那首之后换回上一首`() {
        val p = playlist(songs = listOf(song("a", "url-a"), song("b", "url-b")))
        assertEquals("url-a", p.copy(songs = p.songs.dropLast(1)).fallbackCover)
    }

    @Test
    fun `列表最新加入的在顶部`() {
        val p = playlist(songs = listOf(song("a", "url-a"), song("b", "url-b"), song("c", "url-c")))
        assertEquals(listOf("c", "b", "a"), p.songsNewestFirst.map { it.id })
    }

    @Test
    fun `导入之后新歌在被追加的一侧`() {
        val existing = listOf(song("a", "url-a"))
        val merged = mergePlaylistSongs(existing, listOf(song("x", "url-x"), song("y", "url-y")))
        val p = playlist(songs = merged)
        assertEquals(listOf("y", "x", "a"), p.songsNewestFirst.map { it.id })
        assertEquals("url-y", p.fallbackCover)
    }
}
