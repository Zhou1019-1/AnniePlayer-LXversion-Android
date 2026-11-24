package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.MetadataPlatformResult
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.RankQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索页的状态模型。
 *
 * 这一组测试锁的是两条**曾经出错**的语义：
 * 1. 「点搜索」要打的平台 = 五家 + 已搜过的懒加载 + **当前所在 Tab 的懒加载**
 *    （漏掉最后一条时：正看着 Qobuz/KKBOX 点搜索，它会被清空且不重搜，必须切走再切回才有）；
 * 2. 推荐结果由 `platforms` 派生，不再是一份单独的缓存（以前两条路径不同步，
 *    出现「推荐空、渠道有」）。
 */
class MetadataSearchStateTest {

    private fun state(lyricMode: Boolean = false) = MetadataSearchState(
        scope = CoroutineScope(Job()),
        rankQuery = RankQuery("晴天", "周杰伦", "叶惠美"),
        initialKeyword = "晴天 周杰伦",
        lyricMode = lyricMode,
    )

    private val appleSong = Song(
        id = "apple:1",
        title = "晴天",
        artist = "周杰伦",
        cover = "",
        duration = 270,
        platform = MusicPlatform.apple,
    )

    private val qobuzSong = Song(
        id = "qobuz:9",
        title = "晴天",
        artist = "周杰伦",
        cover = "",
        duration = 270,
        platform = MusicPlatform.qobuz,
    )

    @Test
    fun `平台结果到达后 推荐和渠道都看得到`() {
        val s = state()
        s.applyPlatformResult(
            MusicPlatform.apple,
            MetadataPlatformResult(MusicPlatform.apple, listOf(appleSong)),
        )
        assertEquals(1, s.rowsOf(MetadataTab.Apple).size)
        assertEquals(1, s.rowsOf(MetadataTab.Recommended).size)
    }

    @Test
    fun `Qobuz 的结果只进自己的 Tab 不进推荐`() {
        val s = state()
        s.applyPlatformResult(
            MusicPlatform.qobuz,
            MetadataPlatformResult(MusicPlatform.qobuz, listOf(qobuzSong)),
        )
        assertEquals(1, s.rowsOf(MetadataTab.Qobuz).size)
        assertEquals("推荐只由五家派生", 0, s.rowsOf(MetadataTab.Recommended).size)
    }

    @Test
    fun `点搜索时 当前懒加载 Tab 必须在目标平台里`() {
        assertTrue(MusicPlatform.qobuz in searchTargets(MetadataTab.Qobuz, emptySet()))
        assertTrue(MusicPlatform.kkbox in searchTargets(MetadataTab.Kkbox, emptySet()))
        assertFalse(MusicPlatform.qobuz in searchTargets(MetadataTab.Apple, emptySet()))
        assertFalse(MusicPlatform.kkbox in searchTargets(MetadataTab.Recommended, emptySet()))
    }

    @Test
    fun `已经搜过的懒加载平台下次搜索也一起重搜`() {
        val targets = searchTargets(MetadataTab.Apple, setOf(MusicPlatform.qobuz))
        assertTrue(MusicPlatform.qobuz in targets)
        assertTrue(MusicPlatform.apple in targets)
    }

    @Test
    fun `五家永远在目标里`() {
        val targets = searchTargets(MetadataTab.Recommended, emptySet())
        assertEquals(5, targets.size)
        assertTrue(targets.containsAll(com.annieplayer.annie_music.kt.data.MetadataSearchPlatforms))
    }

    @Test
    fun `元数据模式没有 LRCLIB 这个 Tab`() {
        assertFalse(state(lyricMode = false).tabs.any { it.lrclib })
    }

    @Test
    fun `歌词模式 LRCLIB 排在最后`() {
        val tabs = state(lyricMode = true).tabs
        assertEquals(MetadataTab.Lrclib, tabs.last())
    }

    @Test
    fun `推荐为空且平台失败时能给出原因`() {
        val s = state()
        s.applyPlatformResult(
            MusicPlatform.qq,
            MetadataPlatformResult(MusicPlatform.qq, emptyList(), "QQ 2001"),
        )
        assertEquals("QQ 2001", s.recommendedFallbackError)
    }

    @Test
    fun `有推荐结果时不再报兜底错误`() {
        val s = state()
        s.applyPlatformResult(
            MusicPlatform.apple,
            MetadataPlatformResult(MusicPlatform.apple, listOf(appleSong)),
        )
        s.applyPlatformResult(
            MusicPlatform.qq,
            MetadataPlatformResult(MusicPlatform.qq, emptyList(), "QQ 2001"),
        )
        assertEquals(null, s.recommendedFallbackError)
    }
}
