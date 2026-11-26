package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

/**
 * DSD 容器接线：`.dsf`（Sony）与 `.dff`（Philips DSDIFF）。
 *
 * 两者都用 **ID3v2**（编解码在 [Id3v2Tag]），只是挂的位置不同 —— 而位置恰好都在**文件尾**，
 * 所以「改标签」永远不搬音频。这对几百 MB 的 DSD 是数量级的差别：
 * 5 分钟 DSD64 ≈ 200MB、DSD256 ≈ 800MB，整文件重封装要搬 0.4~1.6GB，这里只写几百 KB。
 *
 * ```
 * .dsf
 *   0   "DSD "                       4
 *   4   chunk size = 28              8  (小端)
 *   12  total file size              8  (小端)  ← 追加标签后必须更新
 *   20  pointer to ID3v2             8  (小端)  ← 没有则 0
 *   28  "fmt " chunk … "data" chunk … [ID3v2] 在 pointer 处
 *
 * .dff
 *   "FRM8" + size(8 大端) + "DSD " + 若干 chunk（ID + size 8 大端，奇数长度补 1 字节）
 *   [ "ID3 " chunk ]  payload 就是 ID3v2，通常在文件尾
 * ```
 *
 * **两套字节序混在一起**（dsf 小端、dff 大端）正是这类格式最容易翻车的点。
 */
internal object DsdCodec : TagCodec {

    override val format: String = "DSD"

    private const val DSF_HEADER = 28L
    private const val DSF_PTR_OFFSET = 20L
    private const val DSF_TOTAL_OFFSET = 12L

    /** ID3 chunk 不在文件尾时前移数据的搬运块大小（1 MB，重叠安全由 [moveForward] 保证） */
    private const val MOVE_BUFFER = 1 shl 20

    override fun sniff(head: ByteArray): Boolean {
        if (head.size < 4) return false
        return head.ascii(0, 4) == "DSD " || head.ascii(0, 4) == "FRM8"
    }

    private fun isDsf(head: ByteArray): Boolean = head.size >= 4 && head.ascii(0, 4) == "DSD "

    // ── 读 ──

    override fun read(file: File): TagBag? {
        if (!file.exists()) return null
        val head = file.readHead(8)
        val tagBytes = when {
            isDsf(head) -> readDsfTag(file)
            head.size >= 4 && head.ascii(0, 4) == "FRM8" -> readDffTag(file)
            else -> null
        } ?: return null
        return Id3v2Tag.decode(tagBytes)?.takeIf { !it.isEmpty() }
    }

    private fun readDsfTag(file: File): ByteArray? {
        if (file.length() <= DSF_HEADER) return null
        val h = ByteArray(DSF_HEADER.toInt())
        RandomAccessFile(file, "r").use { raf ->
            if (raf.read(h) != h.size) return null
        }
        val ptr = u64le(h, DSF_PTR_OFFSET.toInt())
        if (ptr <= 0 || ptr >= file.length()) return null
        val len = (file.length() - ptr).toInt()
        if (len <= Id3v2Tag.HEADER_SIZE) return null
        return ByteArray(len).also { buf ->
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(ptr)
                if (raf.read(buf) != len) return null
            }
        }
    }

    private fun readDffTag(file: File): ByteArray? {
        val located = locateDffId3(file) ?: return null
        if (located.size <= Id3v2Tag.HEADER_SIZE) return null
        return ByteArray(located.size).also { buf ->
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(located.payloadStart)
                if (raf.read(buf) != buf.size) return null
            }
        }
    }

    // ── 写 ──

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        if (!file.exists()) return WriteResult.fail(format, "文件不存在")
        val existing = runCatching { read(file) }.getOrNull()
        val merged = bag.mergedWith(existing)
        val tag = Id3v2Tag.encode(merged)
        val head = file.readHead(8)
        return when {
            isDsf(head) -> writeDsf(file, tag)
            head.size >= 4 && head.ascii(0, 4) == "FRM8" -> writeDff(file, tag)
            else -> WriteResult.fail(format, "不是 DSD 文件")
        }
    }

    private fun writeDsf(file: File, tag: ByteArray): WriteResult {
        val h = ByteArray(DSF_HEADER.toInt())
        RandomAccessFile(file, "r").use { raf ->
            if (raf.read(h) != h.size) return WriteResult.fail(format, "DSF 头太短")
        }
        val ptr = u64le(h, DSF_PTR_OFFSET.toInt())
        val audioEnd = if (ptr in 1 until file.length()) ptr else file.length()
        val newTotal = audioEnd + tag.size
        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(audioEnd)
                raf.seek(audioEnd)
                raf.write(tag)
                // 指针 + 总长度都得跟着改，否则播放器找不到标签 / 认为文件被截断
                raf.seek(DSF_PTR_OFFSET)
                raf.write(u64leBytes(audioEnd))
                raf.seek(DSF_TOTAL_OFFSET)
                raf.write(u64leBytes(newTotal))
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)
        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "DSF 写失败")
    }

    /**
     * 写 DFF 的 `ID3 ` chunk。
     *
     * 截断点必须是**旧 chunk 的起点**（`chunkStart`），不是 payload 起点 —— 早先按 payload
     * 起点截断，会把这 12 字节的 `"ID3 "` + size 头留在文件里，紧接着又追加一个新的完整 chunk，
     * 于是文件尾部变成「12 字节残渣 + 新 chunk」。第一次写看不出来，第二次写时
     * [locateDffId3] 命中的是那截残渣（size 是**上一次**的大小），读回的标签就是垃圾，
     * 表现成「歌词写进去了，再进编辑页又是旧的 / 空的」。
     */
    private fun writeDff(file: File, tag: ByteArray): WriteResult {
        val located = locateDffId3(file)
        val chunkLen = 12L + tag.size + (tag.size % 2)
        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()
                val chunkStart = located?.chunkStart ?: len
                // 老 chunk 之后如果还有字节（ID3 不在文件尾的畸形布局），整体前移，绝不截掉
                val tailStart = located?.let { it.chunkStart + 12 + it.size + (it.size % 2) } ?: len
                val moved = if (tailStart < len) moveForward(raf, tailStart, chunkStart) else 0L
                val end = chunkStart + moved
                raf.setLength(end)
                raf.seek(end)
                raf.write("ID3 ".toByteArray(Charsets.ISO_8859_1))
                raf.write(u64beBytes(tag.size.toLong()))
                raf.write(tag)
                if (tag.size % 2 != 0) raf.write(0) // DSDIFF 奇数长度补 1 字节（不计入 size）
                // FRM8 的总长度（8 字节大端，位于 offset 4）：文件总长 - 12
                raf.seek(4)
                raf.write(u64beBytes(end + chunkLen - 12))
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)
        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "DFF 写失败")
    }

    /** 把 `[from, len)` 搬到 `to`（`to < from`），返回搬了多少字节；两者重叠也不会踩坏未读数据。 */
    private fun moveForward(raf: RandomAccessFile, from: Long, to: Long): Long {
        val len = raf.length()
        val overlap = (from - to).coerceAtLeast(1L)
        val buf = ByteArray(minOf(MOVE_BUFFER.toLong(), overlap).toInt())
        var readPos = from
        var writePos = to
        while (readPos < len) {
            raf.seek(readPos)
            val n = raf.read(buf)
            if (n <= 0) break
            readPos += n
            raf.seek(writePos)
            raf.write(buf, 0, n)
            writePos += n
        }
        return writePos - to
    }

    /** DFF 里的 `ID3 ` chunk：[chunkStart] 是 12 字节头的位置，[payloadStart] 才是 ID3v2 本体 */
    private class DffId3(val chunkStart: Long, val payloadStart: Long, val size: Int)

    /**
     * 找 DFF 里的 `ID3 ` chunk。
     *
     * 命中后还要验一下 payload 是不是真的以 `ID3` 开头：历史版本可能留下「12 字节残渣头」，
     * 只看 chunk id 会把残渣当标签，读出来的就是垃圾。
     */
    private fun locateDffId3(file: File): DffId3? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 16) return null
            val head = ByteArray(16)
            raf.seek(0)
            if (raf.read(head) != 16) return null
            if (head.ascii(0, 4) != "FRM8") return null
            // FRM8(4) + size(8) + formType "DSD "(4) = 16 字节，第一个 chunk 从这里开始
            var pos = 16L
            val end = raf.length()
            val ch = ByteArray(12)
            val probe = ByteArray(3)
            while (pos + 12 <= end) {
                raf.seek(pos)
                if (raf.read(ch) != 12) return null
                val id = ch.ascii(0, 4)
                val size = u64be(ch, 4)
                if (size < 0) return null
                if (id == "ID3 ") {
                    if (size > Int.MAX_VALUE) return null
                    raf.seek(pos + 12)
                    val valid = raf.read(probe) == 3 && probe.ascii(0, 3) == "ID3"
                    if (valid) return DffId3(pos, pos + 12, size.toInt())
                }
                val next = pos + 12 + size + (size % 2)
                if (next <= pos) return null
                pos = next
            }
        }
        return null
    }

    // ── 字节序工具（dsf 小端 / dff 大端，故意分开写，防止混用） ──

    private fun u64le(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64leBytes(v: Long): ByteArray =
        ByteArray(8) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u64be(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64beBytes(v: Long): ByteArray =
        ByteArray(8) { i -> ((v ushr (8 * (7 - i))) and 0xFF).toByte() }
}
