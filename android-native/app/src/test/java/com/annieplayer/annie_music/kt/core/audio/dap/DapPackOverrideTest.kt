package com.annieplayer.annie_music.kt.core.audio.dap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 线格式判定的唯一事实源 [DapPackOverride.compose] 与"就地换组合"的触发判据 [DapPackOverride.changed]。
 *
 * 这两处一旦错，代价是"四种组合永远试不齐"或"touch 了文件却没换组合" —— 那两种都不会报错，
 * 只会让人以为 `rotate` 本来就不灵，所以逐条断言。
 */
class DapPackOverrideTest {

    private fun dir(): File = java.nio.file.Files.createTempDirectory("dsdpack").toFile()

    private fun packFile(dir: File, text: String?): File {
        val f = File(dir, "dsd-pack.txt")
        if (text != null) f.writeText(text)
        DapPackOverride.load(f)
        return f
    }

    private fun compose(): DapPackOverride.Choice = DapPackOverride.compose(true, true)

    @Test
    fun `没有文件时原样返回调用方的默认值`() {
        packFile(dir(), null)
        val c = compose()
        assertEquals("default", c.label)
        assertTrue(c.bigEndian)
        assertTrue(c.bitReverse)
    }

    @Test
    fun `认不出的值视同没有文件`() {
        packFile(dir(), "xyz")
        assertEquals("default", compose().label)
    }

    @Test
    fun `le-rev 是小端加位反转`() {
        packFile(dir(), "le-rev\n")
        val c = compose()
        assertFalse(c.bigEndian)
        assertTrue(c.bitReverse)
        assertEquals("le-rev", c.label)
    }

    @Test
    fun `be-rev 是大端加位反转`() {
        packFile(dir(), "BE-REV")
        val c = compose()
        assertTrue(c.bigEndian)
        assertTrue(c.bitReverse)
        assertEquals("be-rev", c.label)
    }

    @Test
    fun `norev 不被 rev 后缀误判成反转`() {
        packFile(dir(), "be-norev")
        val be = compose()
        assertTrue(be.bigEndian)
        assertFalse(be.bitReverse)

        packFile(dir(), "le-norev")
        val le = compose()
        assertFalse(le.bigEndian)
        assertFalse(le.bitReverse)
    }

    @Test
    fun `rotate 按固定顺序轮换并回环`() {
        // **顺序即实验设计**：前两种是"按字节交错"（此前从没试过的维度），
        // 中间两种是"关掉位反转"，最后两种是已实测排除的"按字交错 + 位反转"。
        val d = dir()
        packFile(d, "rotate")
        val got = (1..7).map { compose().label }
        assertEquals(
            listOf("byte-rev", "byte-le-rev", "be-norev", "le-norev", "be-rev", "le-rev", "byte-rev"),
            got,
        )
    }

    @Test
    fun `rotate 的指针写进 idx 文件供下次进程续上`() {
        val d = dir()
        packFile(d, "rotate")
        compose()
        assertEquals("1", File(d, "dsd-pack-idx.txt").readText().trim())

        // 下一次进程只 load 到"已经用到 1"，应当从第二种（byte-le-rev）继续
        DapPackOverride.load(File(d, "dsd-pack.txt"))
        assertEquals("byte-le-rev", compose().label)
    }

    @Test
    fun `idx 文件损坏或越界都从头轮换`() {
        val d = dir()
        packFile(d, "rotate")
        File(d, "dsd-pack-idx.txt").writeText("abc")
        DapPackOverride.load(File(d, "dsd-pack.txt"))
        assertEquals("byte-rev", compose().label)

        File(d, "dsd-pack-idx.txt").writeText("99")
        DapPackOverride.load(File(d, "dsd-pack.txt"))
        assertEquals("byte-rev", compose().label)
    }

    @Test
    fun `文件没动就不算变化`() {
        packFile(dir(), "be-rev")
        assertFalse(DapPackOverride.changed())
        assertFalse(DapPackOverride.changed())
    }

    @Test
    fun `byte 前缀 = 按字节交错，且与位序正交`() {
        packFile(dir(), "byte-rev")
        val a = compose()
        assertTrue("byte-rev 必须按字节交错", a.byteInterleaved)
        assertTrue(a.bitReverse)

        packFile(dir(), "byte-le-rev")
        val b = compose()
        assertTrue(b.byteInterleaved)
        assertTrue("byte-le 也走字节交错", b.byteInterleaved)

        packFile(dir(), "be-norev")
        val c = compose()
        assertFalse("be 家族是按字交错", c.byteInterleaved)
    }

    @Test
    fun `touch 一次只报一次变化，rotate 借此换下一组`() {
        val d = dir()
        val f = packFile(d, "rotate")
        assertEquals("byte-rev", compose().label)

        assertTrue(f.setLastModified(1_600_000_000_000L))
        assertTrue(DapPackOverride.changed())
        assertFalse(DapPackOverride.changed())
        assertEquals("byte-le-rev", compose().label)
    }

    @Test
    fun `同长度改内容靠 mtime 发现`() {
        val d = dir()
        val f = packFile(d, "le-rev")
        assertEquals("le-rev", compose().label)

        f.writeText("be-rev")                     // 长度相同
        f.setLastModified(1_600_000_000_000L)     // 编辑器写入必然动 mtime
        assertTrue(DapPackOverride.changed())
        assertEquals("be-rev", compose().label)
    }

    @Test
    fun `文件被删掉后回到默认值`() {
        val d = dir()
        val f = packFile(d, "le-rev")
        assertEquals("le-rev", compose().label)

        assertTrue(f.delete())
        assertTrue(DapPackOverride.changed())
        assertEquals("default", compose().label)
    }
}
