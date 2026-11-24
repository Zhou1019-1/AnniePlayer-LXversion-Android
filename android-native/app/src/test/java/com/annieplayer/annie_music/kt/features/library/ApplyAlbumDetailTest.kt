package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.RepoAlbumSongs
import com.annieplayer.annie_music.kt.data.applyAlbumDetail
import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导入前的「专辑级补全」：哪些字段该由专辑补、哪些不许被覆盖。
 *
 * 现实约束（决定了这个补全存在）：各平台**搜索**接口只给标题/歌手/专辑/封面/时长，
 * 专辑艺术家、轨号、碟号、发行日期基本都在专辑详情里。所以导入页打开时会多打一次专辑。
 */
class ApplyAlbumDetailTest {

    private val song = Song(
        id = "netease:100",
        title = "T",
        artist = "A / B",
        cover = "",
        album = "专辑",
        albumId = "42",
        duration = 200,
        platform = MusicPlatform.netease,
    )

    private val track = AlbumSong(
        id = "netease:100",
        name = "T",
        artistName = "A / B",
        duration = 200,
        track = 3,
        discNumber = 1,
        albumArtist = "Various Artists",
        composer = "某人",
        genre = "Pop",
        isrc = "USRC17607839",
    )

    @Test
    fun `专辑详情把轨号 碟号 专辑艺术家 作曲 流派 ISRC 补齐`() {
        val out = applyAlbumDetail(song, RepoAlbumSongs(listOf(track), pub = "2023-05-12"))
        assertEquals("3", out.trackNumber)
        assertEquals("1", out.discNumber)
        assertEquals("Various Artists", out.albumArtist)
        assertEquals("某人", out.composer)
        assertEquals("Pop", out.genre)
        assertEquals("USRC17607839", out.isrc)
        assertEquals("2023-05-12", out.releaseDate)
    }

    @Test
    fun `候选已有的值不被专辑覆盖`() {
        val given = song.copy(
            trackNumber = "9",
            releaseDate = "2020",
            genre = "Rock",
        )
        val out = applyAlbumDetail(given, RepoAlbumSongs(listOf(track), pub = "2023-05-12"))
        assertEquals("9", out.trackNumber)
        assertEquals("2020", out.releaseDate)
        assertEquals("Rock", out.genre)
        // 空的那几项仍然被补上
        assertEquals("Various Artists", out.albumArtist)
    }

    @Test
    fun `专辑里找不到这一首时只补发行日期`() {
        val out = applyAlbumDetail(song, RepoAlbumSongs(emptyList(), pub = "2019"))
        assertEquals("2019", out.releaseDate)
        assertNull(out.trackNumber)
        assertNull(out.albumArtist)
    }

    @Test
    fun `曲目没给流派时用专辑级流派兜底`() {
        val noGenre = track.copy(genre = null)
        val out = applyAlbumDetail(
            song,
            RepoAlbumSongs(listOf(noGenre), pub = "", genre = "Pop 流行"),
        )
        assertEquals("Pop 流行", out.genre)
    }

    @Test
    fun `没有专辑详情时原样返回`() {
        assertEquals(song, applyAlbumDetail(song, null))
    }

    @Test
    fun `专辑艺术家只认专辑那一份 不会被曲目艺人顶替`() {
        // 候选自带一个专辑艺术家、专辑详情没给 → 保留候选的；反之用专辑的
        val noAlbumArtist = track.copy(albumArtist = null)
        val carrying = song.copy(albumArtist = "候选专辑艺术家")
        val out = applyAlbumDetail(carrying, RepoAlbumSongs(listOf(noAlbumArtist), pub = ""))
        assertEquals("候选专辑艺术家", out.albumArtist)
        assertNull(out.releaseDate)

        val replaced = applyAlbumDetail(carrying, RepoAlbumSongs(listOf(track), pub = ""))
        assertEquals("Various Artists", replaced.albumArtist)
    }
}
