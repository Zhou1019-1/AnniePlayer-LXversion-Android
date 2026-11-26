package com.annieplayer.annie_music.kt.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScanTest {

    private val root = "/storage/emulated/0"

    @Test
    fun `前缀相同但不是子目录的路径不算被排除`() {
        val excluded = setOf("$root/Music")
        assertTrue(isPathExcluded("$root/Music", excluded))
        assertTrue(isPathExcluded("$root/Music/Album", excluded))
        assertFalse(isPathExcluded("$root/Music2", excluded))
        assertFalse(isPathExcluded(root, excluded))
    }

    @Test
    fun `添加排除目录时折叠父子`() {
        LocalScanExclusions.replaceAll(emptySet())
        LocalScanExclusions.addDir("$root/Music")
        assertTrue(LocalScanExclusions.isDirExcluded("$root/Music/Album"))
        LocalScanExclusions.addDir("$root/Music/Album")
        assertEquals(setOf("$root/Music"), LocalScanExclusions.excluded.value)
        assertTrue(LocalScanExclusions.hasExcludedAncestor("$root/Music/Album"))

        LocalScanExclusions.addDir(root)
        assertEquals(setOf(root), LocalScanExclusions.excluded.value)

        LocalScanExclusions.removeDir(root)
        assertTrue(LocalScanExclusions.excluded.value.isEmpty())
        assertFalse(LocalScanExclusions.isDirExcluded("$root/Music"))
        LocalScanExclusions.replaceAll(emptySet())
    }

    @Test
    fun `删除父目录时子目录条目一起清掉`() {
        LocalScanExclusions.replaceAll(emptySet())
        LocalScanExclusions.addDir("$root/Music/A")
        LocalScanExclusions.addDir("$root/Music/B")
        assertEquals(2, LocalScanExclusions.excluded.value.size)
        LocalScanExclusions.addDir("$root/Music")
        assertEquals(setOf("$root/Music"), LocalScanExclusions.excluded.value)
        LocalScanExclusions.removeDir(root)
        assertTrue(LocalScanExclusions.excluded.value.isEmpty())
    }

    @Test
    fun `路径友好显示`() {
        assertEquals("内置存储", friendlyDirPath(root))
        assertEquals("内置存储 · Music/Old", friendlyDirPath("$root/Music/Old"))
        assertEquals("存储卡 1A2B-3C4D", friendlyDirPath("/storage/1A2B-3C4D"))
        assertEquals("存储卡 1A2B-3C4D · Download", friendlyDirPath("/storage/1A2B-3C4D/Download"))
    }

    @Test
    fun `音频扩展名判定`() {
        assertTrue(isAudioFileName("a.flac"))
        assertTrue(isAudioFileName("a.DSF"))
        assertTrue(isAudioFileName("a.m4a"))
        assertFalse(isAudioFileName("cover.jpg"))
        assertFalse(isAudioFileName("noext"))
        assertFalse(isAudioFileName("trailing."))
    }

    @Test
    fun `存储根判定`() {
        assertTrue(isStorageRootPath(root))
        assertTrue(isStorageRootPath("/storage/1A2B-3C4D"))
        assertFalse(isStorageRootPath("$root/Music"))
        assertFalse(isStorageRootPath("/storage/1A2B-3C4D/Download"))
    }

    @Test
    fun `短音频阈值关闭时返回 0`() {
        LocalScanSettings.setSkipShortAudio(true)
        LocalScanSettings.setMinAudioSeconds(10)
        assertEquals(10, LocalScanSettings.minDurationSecondsNow)
        LocalScanSettings.setSkipShortAudio(false)
        assertEquals(0, LocalScanSettings.minDurationSecondsNow)
        LocalScanSettings.setSkipShortAudio(true)
        LocalScanSettings.setMinAudioSeconds(1)
        assertEquals(5, LocalScanSettings.minDurationSecondsNow)
        LocalScanSettings.setMinAudioSeconds(10)
    }
}
