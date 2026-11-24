package com.annieplayer.annie_music.kt.data.local

import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「编辑元数据」的两个核心规则：**只写改过的字段**、**清空真的能清掉**。
 *
 * 这两条是这个功能唯一容易做错的地方：
 * - 全量提交会把「用户没碰过的 12 个字段」按界面上的展示格式重写一遍
 *   （`A、B` 变 `A / B`、年份被规范化、注释被覆盖）；
 * - 用空串表示清空则完全无效 —— 合并时会被当成"没有值"，而 `AnnieTag.write` 见到
 *   空 bag 会直接早退，一个字节都不写，界面还显示"保存成功"。
 */
class LocalTagEditTest {

    private val meta = LocalTrackMeta(
        path = "/storage/emulated/0/Music/Goodbye - 디아.flac",
        mtime = 1_755_000_000_000L,
        size = 30_000_000L,
        title = "안녕 Goodbye",
        artist = "디아、김바사",
        album = "Stardust",
        albumArtist = "디아",
        duration = 231,
        track = 3,
        disc = 1,
        year = "2023-05-12",
        genre = "Ballad",
        composer = "김바사",
        isrc = "KRA382300123",
        version = "Remastered 2023",
        copyright = "2023 Stardust Music",
        probed = true,
    )

    private fun baseline(): TagEditForm = TagEditForm.of(meta)

    // ── 预填 ──

    @Test
    fun `预填用的是标签原始串 不是界面展示串`() {
        // 多歌手在界面上显示成 `디아 / 김바사`，但写回必须是文件里那一串
        assertEquals("디아、김바사", baseline().artist)
        assertEquals(meta.artistDisplay, "디아 / 김바사")
    }

    @Test
    fun `预填把全部 13 个字段都取出来`() {
        val f = baseline()
        assertEquals("안녕 Goodbye", f.title)
        assertEquals("Stardust", f.album)
        assertEquals("디아", f.albumArtist)
        assertEquals("2023-05-12", f.year)
        assertEquals("3", f.track)
        assertEquals("1", f.disc)
        assertEquals("Ballad", f.genre)
        assertEquals("김바사", f.composer)
        assertEquals("KRA382300123", f.isrc)
        assertEquals("Remastered 2023", f.comment)
        assertEquals("2023 Stardust Music", f.copyright)
        assertEquals("", f.lyric)
        assertNull(f.cover)
        assertFalse(f.removeCover)
    }

    // ── diffBag ──

    @Test
    fun `只改标题 只写标题`() {
        val bag = baseline().copy(title = "Goodbye").diffBag(baseline())
        assertEquals(mapOf(TagKey.Title to "Goodbye"), bag.text)
        assertTrue(bag.cleared.isEmpty())
        assertNull(bag.cover)
        assertFalse(bag.removeCover)
    }

    @Test
    fun `没改动时是空 bag（保存按钮据此置灰）`() {
        val bag = baseline().diffBag(baseline())
        assertTrue(bag.isEmpty())
        assertEquals(emptyMap<TagKey, String>(), bag.text)
    }

    @Test
    fun `清空字段走 cleared 而不是空串`() {
        val bag = baseline().copy(genre = "", copyright = "  ").diffBag(baseline())
        assertEquals(setOf(TagKey.Genre, TagKey.Copyright), bag.cleared)
        assertTrue(bag.text.isEmpty())
        // 关键：非空 → 否则 AnnieTag.write 会早退，什么都不写
        assertFalse(bag.isEmpty())
    }

    @Test
    fun `首尾空格不算改动`() {
        val bag = baseline().copy(title = "  안녕 Goodbye  ").diffBag(baseline())
        assertTrue(bag.isEmpty())
    }

    @Test
    fun `一次改多项只写这几项`() {
        val bag = baseline().copy(
            title = "Goodbye",
            genre = "K-Pop",
            comment = "",
        ).diffBag(baseline())
        assertEquals(
            mapOf(TagKey.Title to "Goodbye", TagKey.Genre to "K-Pop"),
            bag.text,
        )
        assertEquals(setOf(TagKey.Comment), bag.cleared)
    }

    @Test
    fun `选新封面只带字节 不动其它字段`() {
        val cover = CoverBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3), "image/jpeg")
        val bag = baseline().copy(cover = cover).diffBag(baseline())
        assertTrue(bag.text.isEmpty())
        assertEquals(cover, bag.cover)
        assertFalse(bag.removeCover)
        assertFalse(bag.isEmpty())
    }

    @Test
    fun `移除封面是 removeCover 而不是 cover=null`() {
        val bag = baseline().copy(removeCover = true).diffBag(baseline())
        assertNull(bag.cover)
        assertTrue(bag.removeCover)
        assertFalse(bag.isEmpty())
    }

    @Test
    fun `同时移除又选了新图时以新图为准`() {
        val cover = CoverBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9), "image/jpeg")
        val bag = baseline().copy(cover = cover, removeCover = true).diffBag(baseline())
        assertEquals(cover, bag.cover)
        assertFalse(bag.removeCover)
    }

    @Test
    fun `歌词整段替换算一项改动`() {
        val bag = baseline().copy(lyric = "[00:00.00]第一行\n[00:01.00]第二行\n")
            .diffBag(baseline())
        assertEquals(
            mapOf(TagKey.Lyric to "[00:00.00]第一行\n[00:01.00]第二行"),
            bag.text,
        )
    }

    // ── 合并语义（七个 codec 共用一份） ──

    @Test
    fun `合并时 cleared 真的会删掉旧值`() {
        val existing = TagBag.of(TagKey.Genre to "Ballad", TagKey.Title to "旧标题")
        val merged = TagBag.clearing(TagKey.Genre).mergedWith(existing)
        assertNull(merged[TagKey.Genre])
        assertEquals("旧标题", merged[TagKey.Title])
    }

    @Test
    fun `合并时没给的字段一律保留`() {
        val existing = TagBag.of(
            TagKey.Genre to "Ballad",
            TagKey.Composer to "김바사",
            TagKey.Isrc to "KRA382300123",
        )
        val merged = TagBag.of(TagKey.Title to "新标题").mergedWith(existing)
        assertEquals("新标题", merged[TagKey.Title])
        assertEquals("Ballad", merged[TagKey.Genre])
        assertEquals("김바사", merged[TagKey.Composer])
        assertEquals("KRA382300123", merged[TagKey.Isrc])
    }

    @Test
    fun `合并时 removeCover 优先于 cover 并把旧封面丢掉`() {
        val existing = TagBag(cover = CoverBytes(byteArrayOf(1, 2, 3, 4), "image/jpeg"))
        assertNull(TagBag(removeCover = true).mergedWith(existing).cover)
        val fresh = CoverBytes(byteArrayOf(9, 9, 9, 9), "image/png")
        assertEquals(fresh, TagBag(cover = fresh).mergedWith(existing).cover)
    }

    @Test
    fun `只改标题时旧封面必须保留`() {
        val existing = TagBag(cover = CoverBytes(byteArrayOf(1, 2, 3, 4), "image/jpeg"))
        val merged = TagBag.of(TagKey.Title to "新标题").mergedWith(existing)
        assertEquals(existing.cover, merged.cover)
    }

    @Test
    fun `空 bag 仍然是空（写标签前会早退）`() {
        assertTrue(TagBag().isEmpty())
        assertFalse(TagBag.clearing(TagKey.Title).isEmpty())
        assertFalse(TagBag(removeCover = true).isEmpty())
    }

    // ── 校验 ──

    @Test
    fun `音轨号允许 n 与 n slash total`() {
        assertFalse(baseline().copy(track = "3").hasBlockingError())
        assertFalse(baseline().copy(track = "3/12").hasBlockingError())
        assertFalse(baseline().copy(track = "").hasBlockingError())
        assertTrue(baseline().copy(track = "3a").hasBlockingError())
        assertTrue(baseline().copy(track = "第三首").hasBlockingError())
        assertTrue(baseline().copy(disc = "1/2/3").hasBlockingError())
    }

    @Test
    fun `年份与 ISRC 不规范只给提示不拦保存`() {
        val warns = baseline().copy(year = "23年", isrc = "USRC1").warnings()
        assertEquals(setOf("年份", "ISRC"), warns.map { it.field }.toSet())
        // 不拦：用户可能正是在修一份历史脏数据
        assertFalse(baseline().copy(year = "23年", isrc = "USRC1").hasBlockingError())
    }

    @Test
    fun `规范值没有任何提示`() {
        assertTrue(baseline().warnings().isEmpty())
    }
}
