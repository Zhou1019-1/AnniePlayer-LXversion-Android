package com.annieplayer.annie_music.kt.core.util

import com.annieplayer.annie_music.kt.data.DownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `//` 占位行。
 *
 * QQ 的 `trans` 把「这一行没有翻译」写成 `[00:14.39]//`（实测《淘金小镇》51 行里 40 多行如此）。
 * 不丢掉它会同时污染三处：播放页每句下面挂一个 `//`、`mergeLyrics` 把它写进标签、
 * 下载下来的文件被别的播放器原样显示。
 */
class LrcPlaceholderTest {

    @Test
    fun `占位翻译不产生翻译行`() {
        val lines = parseLrcWithTranslation(
            lrc = "[00:14.39]原文A\n[00:16.00]原文B",
            tlyric = "[00:14.39]//\n[00:16.00]翻译B",
        )
        assertNull("`//` 不是翻译", lines[0].translation)
        assertEquals("翻译B", lines[1].translation)
    }

    @Test
    fun `原文里的占位行整行丢掉`() {
        val lines = parseLrc("[00:14.39]//\n[00:16.00]真正的词")
        assertEquals(1, lines.size)
        assertEquals("真正的词", lines[0].text)
    }

    @Test
    fun `真实文本里的斜杠不被误伤`() {
        val lines = parseLrc("[00:01.00]a / b\n[00:02.00]AC/DC\n[00:03.00]A/B")
        assertEquals(3, lines.size)
    }

    @Test
    fun `合并后的歌词不含占位行`() {
        val merged = DownloadService.mergeLyrics(
            lrc = "[00:14.39]原文A\n[00:16.00]原文B",
            tlyric = "[00:14.39]//\n[00:16.00]译文B",
            enhancedLyric = null,
        )
        assertFalse("不该出现 //\n$merged", merged.contains("//"))
        assertTrue("译文要保留", merged.contains("译文B"))
        assertTrue("原文要保留", merged.contains("原文A"))
    }
}
