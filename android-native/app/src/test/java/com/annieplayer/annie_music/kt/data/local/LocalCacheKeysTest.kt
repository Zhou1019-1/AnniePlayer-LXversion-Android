package com.annieplayer.annie_music.kt.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「改了标签 / 换了封面，重扫会不会更新」的两条规则。
 *
 * 这里锁住的是**用户能直接看到的两个症状**：
 * 1. 用别的软件重打了标签（标题 / 艺术家 / 内嵌封面），回到曲库重新进一次 —— 列表应不应该变；
 * 2. 换了内嵌封面之后，界面上是新图还是旧图（Coil 的缓存键就是封面文件路径）。
 *
 * 两条都以音频文件的 `mtime + size` 为准，所以这里的断言全是「戳动了就绝不能复用」。
 */
class LocalCacheKeysTest {

    private val path = "/storage/emulated/0/Music/Born Again - LISA.m4a"
    private val mtime = 1_755_000_000_000L
    private val size = 55_931_275L

    private fun meta(
        path: String = this.path,
        mtime: Long = this.mtime,
        size: Long = this.size,
        probed: Boolean = true,
    ) = LocalTrackMeta(path = path, mtime = mtime, size = size, probed = probed)

    // ── isCacheFresh ──

    @Test
    fun `戳没变且已解析过 → 直接复用缓存`() {
        assertTrue(isCacheFresh(meta(), mtime, size))
    }

    @Test
    fun `文件被重打标签（mtime 变了）→ 必须重读`() {
        // exiftool / foobar / 另一台播放器写完标签，mtime 一定往前走
        assertFalse(isCacheFresh(meta(mtime = mtime - 60_000), mtime, size))
    }

    @Test
    fun `文件被整体替换（size 变了）→ 必须重读`() {
        assertFalse(isCacheFresh(meta(size = size + 1), mtime, size))
    }

    @Test
    fun `原地打标 size 一样但 mtime 变了 → 依然要重读`() {
        // 复用补白写 tag 时文件大小可以完全不变，只有 mtime 能区分
        assertFalse(isCacheFresh(meta(mtime = mtime + 1), mtime, size))
    }

    @Test
    fun `上次扫到一半留下的占位行不算缓存`() {
        // probed=false 的记录里标题是从文件名猜的，拿它当缓存会永久显示错误信息
        assertFalse(isCacheFresh(meta(probed = false), mtime, size))
    }

    @Test
    fun `截断过的旧记录（mtime 为 0）不能当命中`() {
        assertFalse(isCacheFresh(meta(mtime = 0, size = 0), mtime, size))
    }

    // ── coverFileName ──

    @Test
    fun `同一路径同一戳 → 文件名稳定`() {
        assertEquals(
            coverFileName(path, mtime, size),
            coverFileName(path, mtime, size),
        )
    }

    @Test
    fun `换了内嵌封面（mtime 变）→ 文件名必须变`() {
        // 名字不变的话：旧 jpg 被当成有效封面复用，且 Coil 按路径命中旧 Bitmap
        assertNotEquals(
            coverFileName(path, mtime, size),
            coverFileName(path, mtime + 1, size),
        )
    }

    @Test
    fun `文件大小变了 → 文件名必须变`() {
        assertNotEquals(
            coverFileName(path, mtime, size),
            coverFileName(path, mtime, size + 1),
        )
    }

    @Test
    fun `不同文件 → 文件名不能撞（尾段相同、目录不同）`() {
        // 尾 24 字符按 24 截断，正好是两个「同名但不同专辑目录」的文件
        val a = "/storage/emulated/0/Music/CD1/Born Again - LISA.m4a"
        val b = "/storage/emulated/0/Music/CD2/Born Again - LISA.m4a"
        assertNotEquals(
            coverNamePrefix(a),
            coverNamePrefix(b),
        )
    }

    @Test
    fun `文件名是合法的 jpg 名字，且不会长到触发文件系统限制`() {
        val name = coverFileName(path, mtime, size)
        assertTrue(name.endsWith(".jpg"))
        assertFalse(name.contains('/'))
        assertTrue("名字过长: ${name.length}", name.length < 64)
    }

    @Test
    fun `删除用的前缀能命中每一代封面`() {
        // deleteCoversFor 只按前缀删：改了标签的旧图也必须是同前缀，
        // 否则删歌会在 covers/ 里留一张永远没人认领的孤儿
        val prefix = coverNamePrefix(path) + "_"
        assertTrue(coverFileName(path, mtime, size).startsWith(prefix))
        assertTrue(coverFileName(path, mtime + 999, size - 1).startsWith(prefix))
    }
}
