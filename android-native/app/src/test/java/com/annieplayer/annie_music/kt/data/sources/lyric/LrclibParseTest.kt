package com.annieplayer.annie_music.kt.data.sources.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRCLIB 搜索响应的解析。
 *
 * 两条必须是硬规则：
 * - **只收带 `syncedLyrics` 的条目**（纯文本没有时间轴，写进文件会让播放页失去滚动同步）；
 * - `instrumental = true` 的占位条目丢掉。
 */
class LrclibParseTest {

    private val sample = """
        [
          {
            "id": 7242265,
            "trackName": "晴天",
            "artistName": "周杰伦",
            "albumName": "叶惠美",
            "duration": 270.0,
            "instrumental": false,
            "plainLyrics": "故事的小黄花",
            "syncedLyrics": "[00:33.44] 故事的小黄花\n[00:36.18] 从出生那年就飘着",
            "hasWordSync": false
          },
          {
            "id": 999,
            "trackName": "只有纯文本",
            "artistName": "X",
            "albumName": "Y",
            "duration": 100.0,
            "instrumental": false,
            "plainLyrics": "no timing",
            "syncedLyrics": null,
            "hasWordSync": false
          },
          {
            "id": 1000,
            "trackName": "纯音乐",
            "artistName": "X",
            "albumName": "Y",
            "duration": 60.0,
            "instrumental": true,
            "syncedLyrics": "[00:01.00] ♪",
            "hasWordSync": false
          }
        ]
    """.trimIndent()

    @Test
    fun `只收带同步歌词的条目`() {
        val hits = parseLrclibSearch(sample)
        assertEquals(1, hits.size)
        val h = hits.first()
        assertEquals("晴天", h.title)
        assertEquals("周杰伦", h.artist)
        assertEquals("叶惠美", h.album)
        assertEquals(270, h.durationSec)
        assertTrue(h.syncedLrc.startsWith("[00:33.44]"))
        assertFalse(h.wordSync)
    }

    @Test
    fun `limit 生效`() {
        val many = (1..20).joinToString(",", "[", "]") { i ->
            """{"id":$i,"trackName":"t$i","artistName":"a","albumName":"b","duration":100,
               "syncedLyrics":"[00:01.00]x","hasWordSync":false}""".replace("\n", "")
        }
        assertEquals(3, parseLrclibSearch(many, limit = 3).size)
    }
}
