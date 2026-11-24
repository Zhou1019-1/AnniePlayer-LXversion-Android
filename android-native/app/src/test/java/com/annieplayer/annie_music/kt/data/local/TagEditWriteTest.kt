package com.annieplayer.annie_music.kt.data.local

import com.annieplayer.annie_music.kt.data.local.tag.AnnieTag
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「编辑元数据」写进**真实文件**之后，读回来是什么样（三种容器各一遍）。
 *
 * 这条链路的两个语义在纯逻辑测试里证明不了，只有落盘再读回才算数：
 * 1. **清空**（`TagBag.cleared`）—— 七个 codec 里任何一个漏改，对应格式就会静默保留旧值；
 * 2. **移除封面**（`TagBag.removeCover`）—— FLAC 的旧 `PICTURE` 块、MP4 的 `covr`、
 *    ID3 的 `APIC` 各有各的删除姿势，靠 `cover = null` 一律删不掉。
 *
 * 夹具与 `AnnieTagTest` 同一份（ffmpeg 生成的真实文件），所以读回来的是独立实现的产物格式。
 */
class TagEditWriteTest {

    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("缺少夹具 $name", stream)
        val dir = Files.createTempDirectory("tagedit").toFile()
        val out = File(dir, name)
        stream!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out.deleteOnExit()
        return out
    }

    private val formats = listOf("fix.mp3", "fix.flac", "fix.m4a")

    /**
     * 把写过的文件留一份到 `build/annietag-verify/`，供 **PC 上的 ffprobe** 反读。
     *
     * 只靠 `AnnieTag.read` 断言等于自证循环（同一个库写、同一个库读）；用独立实现反读
     * 才能发现「写成畸形帧，自己恰好能读回来」这一类问题 —— 历史上十个真 bug 都是这么抓到的。
     */
    private fun publish(name: String, file: File) {
        val dir = File("build/annietag-verify").apply { mkdirs() }
        file.copyTo(File(dir, name), overwrite = true)
    }

    @Test
    fun `清空一个字段在三格式上都会真的删掉`() {
        for (name in formats) {
            val file = fixture(name)
            val before = AnnieTag.read(file)!!
            // 先确定性地写入一个值（不依赖夹具原来有没有这个字段）
            assertTrue(
                "$name 预写失败",
                AnnieTag.write(file, TagBag.of(TagKey.Genre to "TestGenre")).ok,
            )
            assertEquals("TestGenre", AnnieTag.read(file)!![TagKey.Genre])

            val result = AnnieTag.write(file, TagBag.clearing(TagKey.Genre))
            assertTrue("$name 清空失败: ${result.message}", result.ok)

            val after = AnnieTag.read(file)!!
            assertNull("$name 的风格没被删掉", after[TagKey.Genre])
            // 只删这一个：其它字段必须原样还在（这是「编辑一个字段不顺手改写别的」的底线）
            assertEquals("$name 的标题被顺带改了", before[TagKey.Title], after[TagKey.Title])
            assertEquals("$name 的艺术家被顺带改了", before[TagKey.Artist], after[TagKey.Artist])
        }
    }

    @Test
    fun `清空与赋值可以同时发生`() {
        val file = fixture("fix.flac")
        val bag = TagBag(
            text = mapOf(TagKey.Genre to "K-Pop", TagKey.Composer to "某人"),
            cleared = setOf(TagKey.Comment, TagKey.Copyright),
        )
        // 夹具里没有 comment/copyright，但「删一个不存在的键」也必须是成功的空操作
        assertTrue(AnnieTag.write(file, bag).ok)
        val after = AnnieTag.read(file)!!
        assertEquals("K-Pop", after[TagKey.Genre])
        assertEquals("某人", after[TagKey.Composer])
        assertNull(after[TagKey.Comment])
    }

    @Test
    fun `移除内嵌封面在三格式上都生效`() {
        for (name in formats) {
            val file = fixture(name)
            val before = AnnieTag.read(file)!!
            assertTrue("$name 夹具应当自带封面", (before.cover?.bytes?.size ?: 0) > 0)

            val result = AnnieTag.write(file, TagBag(removeCover = true))
            assertTrue("$name 移除封面失败: ${result.message}", result.ok)

            val after = AnnieTag.read(file)!!
            assertNull("$name 的封面没被删掉", after.cover)
            // 封面删了，标签不能跟着没
            assertEquals("$name 的标题丢了", before[TagKey.Title], after[TagKey.Title])
            if (name == "fix.flac") publish("edit-nocover.flac", file)
        }
    }

    @Test
    fun `只改标题时封面与其它字段全部保留`() {
        for (name in formats) {
            val file = fixture(name)
            val before = AnnieTag.read(file)!!
            assertTrue(AnnieTag.write(file, TagBag.of(TagKey.Title to "只改标题")).ok)
            val after = AnnieTag.read(file)!!
            assertEquals("只改标题", after[TagKey.Title])
            assertTrue("$name 的封面被误删", (after.cover?.bytes?.size ?: 0) > 0)
            assertEquals("$name 的艺术家丢了", before[TagKey.Artist], after[TagKey.Artist])
            assertEquals("$name 的专辑丢了", before[TagKey.Album], after[TagKey.Album])
            assertEquals("$name 的轨号丢了", before[TagKey.Track], after[TagKey.Track])
        }
    }

    /**
     * 端到端：编辑表单 → 差异 → 写盘 → 读回。
     *
     * 这里同时覆盖了两件最容易出错的事：`A、B` 这种原始串**不会被归一化**成 `A / B`，
     * 以及没碰过的字段（轨号/碟号/年份/作曲/歌词）一个都不变。
     */
    @Test
    fun `表单差异写盘后读回与编辑结果一致`() {
        val file = fixture("fix.flac")
        val baseline = TagEditForm(
            title = "Fixture Title",
            artist = "Fixture Artist",
            album = "Fixture Album",
            albumArtist = "Fixture AlbumArtist",
            year = "2024-01-02",
            track = "3/12",
            disc = "1/2",
            genre = "Pop",
            composer = "Fixture Composer",
            lyric = "line one",
        )
        val edited = baseline.copy(title = "改过的标题", genre = "", artist = "A、B")

        val result = AnnieTag.write(file, edited.diffBag(baseline))
        assertTrue("写失败: ${result.message}", result.ok)

        val after = AnnieTag.read(file)!!
        assertEquals("改过的标题", after[TagKey.Title])
        // 多歌手原样写回：编辑一次不该把 `、` 变成 ` / `
        assertEquals("A、B", after[TagKey.Artist])
        assertNull("风格应当被清空", after[TagKey.Genre])
        // 没改的字段一个都不许变
        assertEquals("Fixture Album", after[TagKey.Album])
        assertEquals("Fixture AlbumArtist", after[TagKey.AlbumArtist])
        assertEquals("3/12", after[TagKey.Track])
        assertEquals("1/2", after[TagKey.Disc])
        assertEquals("2024-01-02", after[TagKey.Year])
        assertEquals("Fixture Composer", after[TagKey.Composer])
        assertTrue("封面被误删", (after.cover?.bytes?.size ?: 0) > 0)
        publish("edit-flac.flac", file)
    }

    @Test
    fun `空 bag 不落盘也不报错`() {
        val file = fixture("fix.flac")
        val before = file.length()
        val result = AnnieTag.write(file, TagBag())
        assertTrue(result.ok)
        assertEquals("空 bag 不该改动文件", before, file.length())
    }

    @Test
    fun `moov 在尾部且文件超过 64MB 时 读写都不丢字段`() {
        val file = fixture("fix.m4a")
        val before = AnnieTag.read(file)!!
        val big = inflateMdat(file, 68_000_000L)

        val readBack = AnnieTag.read(big)
        assertNotNull("moov 落在 64MB 之后就读不出标签了", readBack)
        assertEquals(before[TagKey.Title], readBack!![TagKey.Title])
        assertEquals(before[TagKey.Artist], readBack[TagKey.Artist])

        val result = AnnieTag.write(big, TagBag.of(TagKey.Copyright to "2024 Some Label"))
        assertTrue("写失败: ${result.message}", result.ok)

        val after = AnnieTag.read(big)!!
        assertEquals("2024 Some Label", after[TagKey.Copyright])
        assertEquals("只写版权把标题弄丢了", before[TagKey.Title], after[TagKey.Title])
        assertEquals("只写版权把歌手弄丢了", before[TagKey.Artist], after[TagKey.Artist])
        assertEquals("只写版权把专辑弄丢了", before[TagKey.Album], after[TagKey.Album])
        assertEquals("只写版权把轨号弄丢了", before[TagKey.Track], after[TagKey.Track])
        assertTrue("只写版权把封面弄丢了", (after.cover?.bytes?.size ?: 0) > 0)
    }

    private fun inflateMdat(src: File, paddedSize: Long): File {
        val out = File(src.parentFile, "big-${src.name}")
        var ftyp: ByteArray? = null
        var moov: ByteArray? = null
        var payload: ByteArray? = null
        RandomAccessFile(src, "r").use { input ->
            val len = input.length()
            val head = ByteArray(16)
            var pos = 0L
            while (pos + 8 <= len) {
                input.seek(pos)
                input.readFully(head, 0, 8)
                val size = (java.nio.ByteBuffer.wrap(head, 0, 4).int.toLong() and 0xFFFFFFFFL)
                val type = String(head, 4, 4, Charsets.ISO_8859_1)
                if (size < 8) break
                val body = ByteArray((size - 8).toInt())
                input.seek(pos + 8)
                input.readFully(body)
                when (type) {
                    "ftyp" -> ftyp = box(type, body)
                    "moov" -> moov = box(type, body)
                    "mdat" -> payload = body
                }
                pos += size
            }
        }
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(ftyp!!)
            raf.write(u32(paddedSize))
            raf.write("mdat".toByteArray(Charsets.ISO_8859_1))
            raf.write(payload!!)
            raf.setLength(ftyp.size + paddedSize)
            raf.seek(raf.length())
            raf.write(moov!!)
        }
        out.deleteOnExit()
        return out
    }

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(u32((body.size + 8).toLong()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun u32(value: Long): ByteArray = byteArrayOf(
        (value shr 24 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
