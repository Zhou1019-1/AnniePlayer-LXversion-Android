package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导入元数据时「艺术家」写什么。
 *
 * 规则：**与下载打标一致** —— 多歌手一律 `A / B`。平台给的合并串（`Kygo & Ellie Goulding`、
 * 酷我的 `A&B`）不能原样抄进文件，否则「导入进来的歌」和「下载下来的歌」两套标签。
 */
class LocalTagImportArtistTest {

    private fun song(
        artist: String,
        names: List<String> = emptyList(),
        platform: MusicPlatform = MusicPlatform.apple,
    ) = Song(
        id = "apple:123",
        title = "T",
        artist = artist,
        cover = "",
        duration = 0,
        platform = platform,
        artistNames = names,
    )

    @Test
    fun `有结构化名单时 用斜杠连接`() {
        assertEquals(
            "Kygo / Ellie Goulding",
            importArtistText(
                song("Kygo & Ellie Goulding", listOf("Kygo", "Ellie Goulding")),
                listOf("Kygo", "Ellie Goulding"),
            ),
        )
    }

    @Test
    fun `Apple 没给结构化名单时 按平台规则拆出斜杠`() {
        assertEquals(
            "Kygo / Ellie Goulding",
            importArtistText(song("Kygo & Ellie Goulding"), emptyList()),
        )
    }

    @Test
    fun `酷我的 amp 连接也变成斜杠`() {
        assertEquals(
            "Pet Shop Boys / Years & Years",
            importArtistText(
                song("Pet Shop Boys&Years & Years", platform = MusicPlatform.kuwo),
                listOf("Pet Shop Boys", "Years & Years"),
            ),
        )
    }

    @Test
    fun `本来就是斜杠的串不会变成双斜杠`() {
        assertEquals(
            "Kygo / Ellie Goulding",
            importArtistText(song("Kygo / Ellie Goulding"), emptyList()),
        )
    }

    @Test
    fun `单歌手原样保留`() {
        assertEquals("Adele", importArtistText(song("Adele"), emptyList()))
        assertEquals("周杰伦", importArtistText(song("周杰伦"), listOf("周杰伦")))
    }

    @Test
    fun `K DA 与 AC DC 这种无空格斜杠不会被拆成两个歌手`() {
        // 平台串里 `/` 也可能是名字的一部分，只有带空格的 `/` 才算分隔
        assertEquals("K/DA", importArtistText(song("K/DA"), emptyList()))
        assertEquals("AC/DC", importArtistText(song("AC/DC"), emptyList()))
        // 结构化名单里本身就是一段时也不动
        assertEquals("K/DA", importArtistText(song("K/DA", listOf("K/DA")), listOf("K/DA")))
    }

    @Test
    fun `带空格的斜杠仍然拆成多歌手`() {
        assertEquals(
            "K/DA / 某歌手",
            importArtistText(song("K/DA / 某歌手"), emptyList()),
        )
    }

    @Test
    fun `顿号分隔按同一规则变成斜杠`() {
        assertEquals("周杰伦 / 费玉清", importArtistText(song("周杰伦、费玉清"), emptyList()))
    }
}
