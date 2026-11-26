package com.annieplayer.annie_music.kt.data.sources.common

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「搜索元数据」推荐结果的排序规则。
 *
 * 用户定的规则只有两条：**Apple Music 恒排第一**、其余按 `标题/艺术家/专辑` 三者匹配度。
 * 这里锁住的都是「排错了用户第一眼就会点错」的情形。
 */
class MetadataRankTest {

    private val query = RankQuery(
        title = "안녕 Goodbye",
        artist = "디아",
        album = "Stardust",
    )

    private fun c(
        title: String,
        artist: String = "",
        album: String = "",
        platform: MusicPlatform = MusicPlatform.netease,
    ) = RankCandidate(title, artist, album, platform)

    @Test
    fun `Apple 里最匹配的那一条置顶 哪怕别家更匹配`() {
        val items = listOf(
            c("안녕 Goodbye", "디아", "Stardust", MusicPlatform.netease), // 三项全中（全局最佳）
            c("안녕 Goodbye", "别的歌手", "", MusicPlatform.apple), // Apple 里最好的
        )
        val ranked = MetadataRank.rank(query, items) { it }
        assertEquals(MusicPlatform.apple, ranked.first().platform)
        assertEquals(MusicPlatform.netease, ranked[1].platform)
    }

    @Test
    fun `Apple 只抬一条 其余 Apple 结果回到匹配度队列里`() {
        val items = listOf(
            c("完全无关", "某人", "", MusicPlatform.apple), // Apple 里较差
            c("안녕 Goodbye", "디아", "Stardust", MusicPlatform.apple), // Apple 里最好 → 置顶
            c("안녕 Goodbye", "디아", "", MusicPlatform.netease), // 次好
            c("完全无关2", "某人", "", MusicPlatform.kugou),
        )
        val ranked = MetadataRank.rank(query, items) { it }
        assertEquals("안녕 Goodbye", ranked[0].title)
        assertEquals(MusicPlatform.apple, ranked[0].platform)
        // 第二条是网易（Apple 剩下的那条分数更低，排在后面）
        assertEquals(MusicPlatform.netease, ranked[1].platform)
    }

    @Test
    fun `没有 Apple 结果时 其它平台按匹配度排`() {
        val items = listOf(
            c("完全无关的歌", "某人"),
            c("안녕 Goodbye", "디아", "Stardust"),
        )
        assertEquals("안녕 Goodbye", MetadataRank.rank(query, items) { it }.first().title)
    }

    @Test
    fun `只有一条 Apple 结果时不会重复出现`() {
        val items = listOf(
            c("안녕 Goodbye", "디아", "Stardust", MusicPlatform.apple),
            c("别的歌", "别人"),
        )
        val ranked = MetadataRank.rank(query, items) { it }
        assertEquals(2, ranked.size)
        assertEquals(1, ranked.count { it.platform == MusicPlatform.apple })
    }

    @Test
    fun `同平台内完全匹配排在包含匹配之前`() {
        val items = listOf(
            c("안녕 Goodbye (Live)", "디아"), // 剥括号后同名，但原文不同
            c("안녕 Goodbye", "디아"), // 原文就是同名
        )
        val ranked = MetadataRank.rank(query, items) { it }
        assertEquals("안녕 Goodbye", ranked.first().title)
    }

    @Test
    fun `标题相同看艺术家 艺术家也对的花钱更少`() {
        val items = listOf(
            c("안녕 Goodbye", "别的歌手"),
            c("안녕 Goodbye", "디아"),
        )
        assertEquals("디아", MetadataRank.rank(query, items) { it }.first().artist)
    }

    @Test
    fun `标题与艺术家都对时 再按专辑决定`() {
        val items = listOf(
            c("안녕 Goodbye", "디아", "Totally Different"),
            c("안녕 Goodbye", "디아", "Stardust"),
        )
        assertEquals("Stardust", MetadataRank.rank(query, items) { it }.first().album)
    }

    @Test
    fun `同分时保持接口给的原始顺序（稳定排序）`() {
        // 用与 query 完全没有字面重叠的值，保证三条真是同分
        val items = listOf(
            c("111", "111"),
            c("222", "222"),
            c("333", "333"),
        )
        val ranked = MetadataRank.rank(query, items) { it }
        assertEquals(listOf("111", "222", "333"), ranked.map { it.title })
    }

    @Test
    fun `原文一致 优先于 剥掉括号后一致`() {
        val items = listOf(
            c("안녕 Goodbye (Live)", "디아"),
            c("안녕 Goodbye", "디아"),
        )
        assertEquals("안녕 Goodbye", MetadataRank.rank(query, items) { it }.first().title)
    }

    @Test
    fun `归一化会剥掉括号内容与标点`() {
        assertEquals(
            MetadataRank.normalize("안녕 Goodbye"),
            MetadataRank.normalize("안녕 Goodbye (feat. Someone) [Remastered 2023]"),
        )
    }

    @Test
    fun `中文标题按字符重叠度排序 而不是完全不相关`() {
        val q = RankQuery("爱琴海", "周杰伦", "太阳之子")
        val items = listOf(
            c("完全无关", "某乐队"),
            c("爱琴海", "周杰伦", "太阳之子"),
            c("爱琴海传说", "别人"),
        )
        val ranked = MetadataRank.rank(q, items) { it }
        assertEquals("爱琴海", ranked.first().title)
        assertTrue(ranked.map { it.title }.indexOf("爱琴海传说") <= 2)
    }

    @Test
    fun `三者全空时不崩且保持原序`() {
        val empty = RankQuery("", "", "")
        val items = listOf(c("x"), c("y"))
        assertEquals(listOf("x", "y"), MetadataRank.rank(empty, items) { it }.map { it.title })
        assertEquals(0, MetadataRank.score(empty, c("x")))
    }
}
