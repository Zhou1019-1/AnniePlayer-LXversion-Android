package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.DownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入歌词必须**带译文**，而且与下载写入走同一套合并。
 *
 * 真实 bug：导入页以前只取 `LyricResult.lrc`、把 `tlyric` 丢了 —— 于是「下载下来的歌有译文、
 * 手动导入的没有」，同一首歌两种标签。这里锁住合并行为本身。
 *
 * 注：只测 `tlyric` 的合并与 `forceEnhanced`（两条路径都在调全局设置之前短路，
 * JVM 单测里不会碰 DataStore）。
 */
class ImportLyricMergeTest {

    private val lrc = "[00:01.00]第一句\n[00:05.00]第二句\n"
    private val tlyric = "[00:01.00]first line\n[00:05.00]second line\n"

    @Test
    fun `逐行导入会带上译文 且译文紧跟原文行`() {
        val merged = DownloadService.mergeLyrics(lrc, tlyric, enhancedLyric = null)
        val lines = merged.lines().filter { it.isNotBlank() }
        assertEquals("第一句", lines[0].substringAfter(']'))
        assertEquals("first line", lines[1].substringAfter(']'))
        assertEquals("第二句", lines[2].substringAfter(']'))
        assertEquals("second line", lines[3].substringAfter(']'))
        // 译文行与原行同时间戳
        assertEquals(lines[0].substringBefore(']'), lines[1].substringBefore(']'))
    }

    @Test
    fun `没有译文时原样返回`() {
        val merged = DownloadService.mergeLyrics(lrc, null, enhancedLyric = null)
        assertEquals(lrc, merged)
    }

    @Test
    fun `逐字导入强制用增强格式 且同样带译文`() {
        val word = "[00:01.00]<00:01.00>第<00:01.50>一句\n[00:05.00]<00:05.00>第二句\n"
        val merged = DownloadService.mergeLyrics(
            lrc,
            tlyric,
            enhancedLyric = word,
            forceEnhanced = true,
        )
        // 增强格式的行首固定是 `<00:00.000>` 零标记（见 `_toEnhancedLrc`）
        assertTrue("应当输出增强格式（带逐字时间标记）:\n$merged", merged.contains("<00:00.000>"))
        assertTrue("译文不能丢:\n$merged", merged.contains("first line"))
    }

    @Test
    fun `逐字结果里每行只有一个行首时间标签`() {
        val word = "[00:01.00]<00:01.00>第<00:01.50>一句\n"
        val merged = DownloadService.mergeLyrics(lrc, tlyric, word, forceEnhanced = true)
        val lyricLine = merged.lines().first { it.startsWith("[00:01") }
        assertEquals(1, Regex("""^\[\d{2}:\d{2}\.\d{2,3}\]""").findAll(lyricLine).count())
    }
}
