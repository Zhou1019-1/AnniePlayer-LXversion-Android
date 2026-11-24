package com.annieplayer.annie_music.kt.data.local

import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑页两个旁路导出按钮的落地行为：`保存 .lrc` / `保存封面`。
 *
 * 它们**不写文件标签**，只往音频同目录丢一份文件 —— 所以这里只断言文件系统效果。
 */
class SidecarExportTest {

    @Test
    fun `封面导出成同目录的 cover jpg`() {
        val dir = Files.createTempDirectory("sidecar-cover").toFile()
        val audio = File(dir, "a.flac").apply { writeBytes(ByteArray(32)) }
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01, 0x02, 0x03)

        assertTrue(LocalLibraryStore.writeSidecarCover(audio.absolutePath, CoverBytes(jpg, "image/jpeg")))
        val out = File(dir, "cover.jpg")
        assertTrue("应当生成 cover.jpg", out.isFile)
        assertArrayEquals(jpg, out.readBytes())
    }

    @Test
    fun `PNG 封面按内容写 cover png`() {
        val dir = Files.createTempDirectory("sidecar-cover-png").toFile()
        val audio = File(dir, "b.flac").apply { writeBytes(ByteArray(32)) }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        assertTrue(LocalLibraryStore.writeSidecarCover(audio.absolutePath, CoverBytes(png, "image/png")))
        assertTrue("应当生成 cover.png", File(dir, "cover.png").isFile)
        assertFalse("不该同时留一张 jpg", File(dir, "cover.jpg").isFile)
    }

    @Test
    fun `没有封面时什么都不写`() {
        val dir = Files.createTempDirectory("sidecar-cover-empty").toFile()
        val audio = File(dir, "c.flac").apply { writeBytes(ByteArray(8)) }
        assertFalse(LocalLibraryStore.writeSidecarCover(audio.absolutePath, null))
        assertFalse(File(dir, "cover.jpg").exists())
        assertFalse(File(dir, "cover.png").exists())
    }

    @Test
    fun `歌词导出成同名 lrc 并在清空时删掉`() {
        val dir = Files.createTempDirectory("sidecar-lrc").toFile()
        val audio = File(dir, "d.flac").apply { writeBytes(ByteArray(8)) }
        val lrc = File(dir, "d.lrc")

        assertTrue(LocalLibraryStore.writeSidecarLrc(audio.absolutePath, "[00:01.00]hello"))
        assertTrue(lrc.isFile)
        assertTrue(lrc.readText().contains("hello"))

        // 清空歌词再点一次 = 删掉旧文件
        assertTrue(LocalLibraryStore.writeSidecarLrc(audio.absolutePath, ""))
        assertFalse(lrc.exists())
    }
}
