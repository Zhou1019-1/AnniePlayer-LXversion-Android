package com.annieplayer.annie_music.kt.data.local

import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「写完标签 → 索引里的记录」这一步的补齐逻辑。
 *
 * TagLib 解析不了 DSDIFF / DSF 的 ID3 块，`LocalTagLibProbe.probe` 只能把文件名当标题、
 * 父目录名当专辑。这些回落值一旦进索引，改完元数据播放页 / 列表都不会变 ——
 * [LocalLibraryStore.withAnnieTagFields] 就是用来把它们换成文件里真实的值的。
 */
class LocalMetaFallbackTest {

    @Test
    fun `AnnieTag 读到的文本字段覆盖文件名回落值`() {
        val file = copyFixture("fix.flac")
        val written = AnnieTag.write(
            file,
            TagBag.of(
                TagKey.Title to "Real Title",
                TagKey.Artist to "A / B",
                TagKey.Album to "Real Album",
                TagKey.Track to "5/12",
                TagKey.Copyright to "(P) 2024",
                TagKey.Lyric to "[00:01.00]hi",
            ),
        )
        assertTrue("写标签失败: ${written.message}", written.ok)

        // 模拟 TagLib 读不出来的记录：标题 = 文件名，专辑 = 父目录名，其余空
        val fallback = LocalTrackMeta(
            path = file.absolutePath,
            title = file.nameWithoutExtension,
            album = file.parentFile?.name.orEmpty(),
        )
        val fixed = LocalLibraryStore.withAnnieTagFields(fallback)

        assertEquals("Real Title", fixed.title)
        assertEquals("A / B", fixed.artist)
        assertEquals("Real Album", fixed.album)
        assertEquals("5/12 要取前面的序号", 5, fixed.track)
        assertEquals("(P) 2024", fixed.copyright)
        assertEquals("[00:01.00]hi", fixed.lyric)
    }

    @Test
    fun `读不到标签时一个字段都不动`() {
        val missing = LocalTrackMeta(path = "/nonexistent/foo.flac", title = "Keep Me", artist = "X")
        val fixed = LocalLibraryStore.withAnnieTagFields(missing)
        assertEquals("Keep Me", fixed.title)
        assertEquals("X", fixed.artist)
    }

    private fun copyFixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
            ?: throw AssertionError("缺少夹具 $name")
        val dir = Files.createTempDirectory("meta-fallback").toFile()
        val out = File(dir, name)
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }
}
