package com.annieplayer.annie_music.kt.data.sources.rank

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RankParseTest {

    @Test
    fun netease_boards_carry_top3_and_subtitle() {
        val raw = listOf(
            mapOf(
                "id" to 19723756L,
                "name" to "飙升榜",
                "coverImgUrl" to "http://p1.music.126.net/x.jpg",
                "updateFrequency" to "刚刚更新",
                "trackCount" to 100L,
                "tracks" to listOf(
                    mapOf("name" to "冬天又来临", "artists" to listOf(mapOf("name" to "罗言"))),
                    mapOf("name" to "第二首", "artists" to listOf(mapOf("name" to "A"), mapOf("name" to "B"))),
                    mapOf("name" to "第三首", "artists" to emptyList<Any?>()),
                ),
            ),
            mapOf("name" to "没有 id 的条目"),
        )
        val boards = parseNeteaseRankBoards(raw)
        assertEquals(1, boards.size)
        val board = boards[0]
        assertEquals("19723756", board.id)
        assertEquals("飙升榜", board.name)
        assertEquals("http://p1.music.126.net/x.jpg", board.cover)
        assertEquals("刚刚更新 · 100 首", board.subtitle)
        assertEquals(3, board.top.size)
        assertEquals(1, board.top[0].rank)
        assertEquals("冬天又来临", board.top[0].title)
        assertEquals("罗言", board.top[0].artist)
        assertEquals("A / B", board.top[1].artist)
        assertEquals("", board.top[2].artist)
    }

    @Test
    fun netease_boards_accept_first_second_preview_shape() {
        val raw = listOf(
            mapOf(
                "id" to 19723756L,
                "name" to "飙升榜",
                "updateFrequency" to "刚刚更新",
                "trackCount" to 100L,
                "tracks" to listOf(
                    mapOf("first" to "任天堂", "second" to "G.E.M.邓紫棋"),
                    mapOf("first" to "City Blue", "second" to "Mili"),
                    mapOf("first" to "降温", "second" to "Zzh"),
                ),
            ),
        )
        val boards = parseNeteaseRankBoards(raw)
        assertEquals(1, boards.size)
        assertEquals(3, boards[0].top.size)
        assertEquals("任天堂", boards[0].top[0].title)
        assertEquals("G.E.M.邓紫棋", boards[0].top[0].artist)
        assertEquals(1, boards[0].top[0].rank)
        assertEquals("降温", boards[0].top[2].title)
        assertEquals(3, boards[0].top[2].rank)
    }

    @Test
    fun netease_preview_without_any_title_is_dropped() {
        val raw = listOf(
            mapOf(
                "id" to 1L,
                "name" to "榜",
                "tracks" to listOf(
                    mapOf("first" to "有名字", "second" to "歌手"),
                    mapOf("second" to "只有歌手"),
                ),
            ),
        )
        val boards = parseNeteaseRankBoards(raw)
        assertEquals(1, boards[0].top.size)
        assertEquals("有名字", boards[0].top[0].title)
    }

    @Test
    fun qq_boards_keep_group_and_jsonp_shape() {
        val groups = listOf(
            mapOf(
                "GroupID" to 0,
                "GroupName" to "QQ音乐巅峰榜",
                "List" to listOf(
                    mapOf(
                        "ListName" to "巅峰榜·流行指数",
                        "topID" to 4,
                        "pic_v12" to "http://y.gtimg.cn/a.jpg",
                        "showtime" to "2026-11-17",
                        "listennum" to 7953220,
                        "songlist" to listOf(
                            mapOf("songname" to "慌啥子嘛 (赵本山版)", "singername" to "赵本山"),
                            mapOf("songname" to "阴天也会有烦恼吗", "singername" to "TF_ING穆祉丞"),
                        ),
                    ),
                ),
            ),
            mapOf(
                "GroupName" to "全球榜",
                "List" to listOf(
                    mapOf("ListName" to "美国公告牌", "topID" to "26", "pic" to "http://y.gtimg.cn/b.jpg"),
                ),
            ),
        )
        val boards = parseQqRankGroups(groups)
        assertEquals(2, boards.size)
        assertEquals("4", boards[0].id)
        assertEquals("巅峰榜·流行指数", boards[0].name)
        assertEquals("QQ音乐巅峰榜", boards[0].group)
        assertEquals("http://y.gtimg.cn/a.jpg", boards[0].cover)
        assertTrue(boards[0].subtitle.contains("2026-11-17"))
        assertEquals(2, boards[0].top.size)
        assertEquals("赵本山", boards[0].top[0].artist)
        assertEquals("26", boards[1].id)
        assertEquals("全球榜", boards[1].group)
        assertEquals("http://y.gtimg.cn/b.jpg", boards[1].cover)
    }

    @Test
    fun kugou_boards_replace_size_placeholder() {
        val info = listOf(
            mapOf(
                "rankid" to 8888,
                "rankname" to "TOP500",
                "banner_9" to "http://imge.kugou.com/mcommon/{size}/2022.jpg",
                "update_frequency" to "每日更新",
                "intro" to "数据来源：全曲库歌曲",
                "songinfo" to listOf(
                    mapOf(
                        "songname" to "HOYO-MiX、當山みれい - 无中生有 Ex Nihilo",
                        "author" to "HOYO-MiX、當山みれい",
                    ),
                ),
            ),
        )
        val boards = parseKugouRankInfo(info)
        assertEquals(1, boards.size)
        assertEquals("8888", boards[0].id)
        assertEquals("TOP500", boards[0].name)
        assertEquals("http://imge.kugou.com/mcommon/400/2022.jpg", boards[0].cover)
        assertEquals(1, boards[0].top.size)
        assertEquals("无中生有 Ex Nihilo", boards[0].top[0].title)
        assertEquals("HOYO-MiX、當山みれい", boards[0].top[0].artist)
    }

    @Test
    fun kuwo_boards_filter_non_source_items() {
        val child = listOf(
            mapOf(
                "source" to "1",
                "sourceid" to "93",
                "name" to "酷我飙升榜",
                "pic" to "http://img3.kwcdn.kuwo.cn/x.png",
                "info" to "更新于2024-08-15",
            ),
            mapOf("source" to "0", "sourceid" to "1", "name" to "不是榜单"),
            mapOf("source" to "1", "name" to "缺 sourceid"),
        )
        val boards = parseKuwoRankChild(child)
        assertEquals(1, boards.size)
        assertEquals("93", boards[0].id)
        assertEquals("酷我飙升榜", boards[0].name)
        assertEquals("http://img3.kwcdn.kuwo.cn/x.png", boards[0].cover)
        assertEquals("更新于2024-08-15", boards[0].subtitle)
    }

    @Test
    fun rank_detail_has_more_flag() {
        val full = RankDetail(
            title = "t",
            subtitle = "",
            cover = "",
            songs = List(100) { dummySong(it) },
            total = 300,
            page = 1,
            pageSize = 100,
        )
        assertTrue(full.hasMore)
        val last = full.copy(page = 3)
        assertTrue(!last.hasMore)
        val empty = full.copy(songs = emptyList())
        assertTrue(!empty.hasMore)
    }

    private fun dummySong(index: Int) = com.annieplayer.annie_music.kt.data.model.Song(
        id = "kugou:$index",
        title = "song $index",
        artist = "artist",
        cover = "",
        duration = 200,
        platform = com.annieplayer.annie_music.kt.data.model.MusicPlatform.kugou,
    )
}
