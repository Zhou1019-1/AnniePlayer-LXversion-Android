package com.annieplayer.annie_music.kt.data.local.tag

import java.io.File
import java.io.RandomAccessFile

internal object Mp4Codec : TagCodec {

    override val format: String = "M4A"

    private const val TYPE_TRKN = "trkn"
    private const val TYPE_DISK = "disk"
    private const val TYPE_COVR = "covr"
    private const val TYPE_FREEFORM = "----"

    override fun sniff(head: ByteArray): Boolean =
        head.size >= 8 && head.ascii(4, 4) == "ftyp"

    private class Box(val type: String, val start: Int, val size: Int, val headerSize: Int) {
        val payloadStart: Int get() = start + headerSize
        val end: Int get() = start + size
    }

    private fun boxAt(buf: ByteArray, off: Int, end: Int): Box? {
        if (off + 8 > end) return null
        var size = buf.u32be(off)
        val type = buf.ascii(off + 4, 4)
        var header = 8
        if (size == 1) {
            if (off + 16 > end) return null
            size = buf.u32be(off + 8).toLong().shl(32).or(buf.u32be(off + 12).toLong() and 0xFFFFFFFFL).toInt()
            header = 16
        }
        if (size < header || off + size > end) return null
        return Box(type, off, size, header)
    }

    private fun children(buf: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        while (true) {
            val box = boxAt(buf, p, end) ?: break
            out.add(box)
            p = box.end
        }
        return out
    }

    private fun boxBytes(type: String, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write((payload.size + 8).toU32be())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun fullBox(type: String, versionFlags: Int, payload: ByteArray): ByteArray =
        boxBytes(type, versionFlags.toU32be() + payload)

    override fun read(file: File): TagBag? {
        val moov = readTopLevelBox(file, "moov") ?: return null
        val bag = parseMoov(moov.first, moov.second)
        return bag.takeIf { !it.isEmpty() }
    }

    private fun readTopLevelBox(file: File, wanted: String): Pair<ByteArray, Int>? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val head = ByteArray(16)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                if (raf.read(head, 0, 8) != 8) return null
                var size = head.u32be(0).toLong()
                val type = head.ascii(4, 4)
                if (size == 1L) {
                    if (raf.read(head, 8, 8) != 8) return null
                    size = (head.u32be(8).toLong() shl 32) or (head.u32be(12).toLong() and 0xFFFFFFFFL)
                }
                if (size < 8) return null
                if (type == wanted) {
                    if (size > Int.MAX_VALUE) return null
                    val buf = ByteArray(size.toInt())
                    raf.seek(pos)
                    if (raf.read(buf) != buf.size) return null
                    return buf to buf.size
                }
                pos += size
            }
        }
        return null
    }

    private fun parseMoov(moov: ByteArray, size: Int): TagBag {
        val out = LinkedHashMap<TagKey, String>()
        val extra = LinkedHashMap<String, String>()
        var cover: CoverBytes? = null
        val moovBox = boxAt(moov, 0, size) ?: return TagBag()
        val meta = children(moov, moovBox.payloadStart, moovBox.end)
            .firstNotNullOfOrNull { child ->
                when (child.type) {
                    "udta" -> children(moov, child.payloadStart, child.end).firstOrNull { it.type == "meta" }
                    "meta" -> child
                    else -> null
                }
            } ?: return TagBag()
        val ilst = children(moov, meta.payloadStart + 4, meta.end).firstOrNull { it.type == "ilst" }
            ?: return TagBag()
        for (item in children(moov, ilst.payloadStart, ilst.end)) {
            val data = children(moov, item.payloadStart, item.end).firstOrNull { it.type == "data" } ?: continue
            if (data.payloadStart + 8 > data.end) continue
            val typeFlag = moov.u32be(data.payloadStart)
            val valueStart = data.payloadStart + 8
            val value = moov.copyOfRange(valueStart, data.end)
            when (item.type) {
                "©nam" -> put(out, TagKey.Title, utf8(value))
                "©ART" -> put(out, TagKey.Artist, utf8(value))
                "©alb" -> put(out, TagKey.Album, utf8(value))
                "aART" -> put(out, TagKey.AlbumArtist, utf8(value))
                "©day" -> put(out, TagKey.Year, utf8(value))
                "©gen" -> put(out, TagKey.Genre, utf8(value))
                "©wrt" -> put(out, TagKey.Composer, utf8(value))
                "©cmt" -> put(out, TagKey.Comment, utf8(value))
                "cprt" -> put(out, TagKey.Copyright, utf8(value))
                "©lyr" -> put(out, TagKey.Lyric, utf8(value))
                TYPE_TRKN -> put(out, TagKey.Track, formatSlashPair(pairAt(value, 2), pairAt(value, 4)))
                TYPE_DISK -> put(out, TagKey.Disc, formatSlashPair(pairAt(value, 2), pairAt(value, 4)))
                TYPE_COVR -> if (cover == null) {
                    cover = CoverBytes(value, if (typeFlag == 14) "image/png" else "image/jpeg")
                }
                TYPE_FREEFORM -> {
                    val name = freeformName(moov, item)
                    if (!name.isNullOrEmpty()) {
                        val text = utf8(value)
                        if (name.equals("ISRC", ignoreCase = true)) {
                            put(out, TagKey.Isrc, text)
                        } else if (text.isNotEmpty()) {
                            extra.putIfAbsent(name, text)
                        }
                    }
                }
            }
        }
        return TagBag(out, cover, extra)
    }

    private fun pairAt(value: ByteArray, off: Int): Int? =
        if (value.size >= off + 2) value.u16be(off) else null

    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).trimEnd('\u0000')

    private fun put(out: MutableMap<TagKey, String>, key: TagKey, value: String?) {
        if (!value.isNullOrEmpty() && out[key].isNullOrEmpty()) out[key] = value
    }

    private fun freeformName(buf: ByteArray, item: Box): String? =
        children(buf, item.payloadStart, item.end)
            .firstOrNull { it.type == "name" }
            ?.let { String(buf, it.payloadStart + 4, it.end - it.payloadStart - 4, Charsets.UTF_8) }

    override fun write(file: File, bag: TagBag, policy: WritePolicy): WriteResult {
        val located = locate(file) ?: return WriteResult.fail(format, "找不到 moov")
        val (moovOffset, moovSize) = located
        val moov = runCatching { readRange(file, moovOffset, moovSize) }.getOrNull()
            ?: return WriteResult.fail(format, "读 moov 失败")
        val existing = parseMoov(moov, moov.size)
        val merged = bag.mergedWith(existing)
        val newMoov = buildMoov(moov, merged) ?: return WriteResult.fail(format, "重建 moov 失败")

        val ok = runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                val atEnd = moovOffset + moovSize >= raf.length()
                if (atEnd) {
                    raf.setLength(moovOffset)
                } else {
                    raf.seek(moovOffset)
                    raf.write(moovSize.toU32be())
                    raf.write("free".toByteArray(Charsets.ISO_8859_1))
                }
                raf.seek(raf.length())
                raf.write(newMoov)
                raf.channel.force(true)
            }
            true
        }.getOrDefault(false)

        return if (ok) WriteResult.ok(format, inPlace = true) else WriteResult.fail(format, "写 moov 失败")
    }

    private fun locate(file: File): Pair<Long, Int>? {
        if (!file.exists()) return null
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val head = ByteArray(16)
            while (pos + 8 <= raf.length()) {
                raf.seek(pos)
                if (raf.read(head, 0, 8) != 8) return null
                var size = head.u32be(0).toLong()
                val type = head.ascii(4, 4)
                if (size == 1L) {
                    if (raf.read(head, 8, 8) != 8) return null
                    size = (head.u32be(8).toLong() shl 32) or (head.u32be(12).toLong() and 0xFFFFFFFFL)
                }
                if (size < 8) return null
                if (type == "moov") return pos to size.toInt()
                pos += size
            }
        }
        return null
    }

    private fun readRange(file: File, offset: Long, size: Int): ByteArray {
        val buf = ByteArray(size)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buf)
        }
        return buf
    }

    internal fun buildMoov(moov: ByteArray, bag: TagBag): ByteArray? {
        val moovBox = boxAt(moov, 0, moov.size) ?: return null
        val out = java.io.ByteArrayOutputStream()
        for (child in children(moov, moovBox.payloadStart, moovBox.end)) {
            if (child.type == "udta") continue
            out.write(moov.copyOfRange(child.start, child.end))
        }
        out.write(buildUdta(bag))
        return boxBytes("moov", out.toByteArray())
    }

    internal fun buildUdtaBox(bag: TagBag): ByteArray = buildUdta(bag)

    private fun buildUdta(bag: TagBag): ByteArray {
        val ilst = java.io.ByteArrayOutputStream()
        fun text(type: String, value: String?) {
            if (value.isNullOrEmpty()) return
            ilst.write(item(type, 1, value.toByteArray(Charsets.UTF_8)))
        }
        text("©nam", bag[TagKey.Title])
        text("©ART", bag[TagKey.Artist])
        text("©alb", bag[TagKey.Album])
        text("aART", bag[TagKey.AlbumArtist])
        text("©day", bag[TagKey.Year])
        text("©gen", bag[TagKey.Genre])
        text("©wrt", bag[TagKey.Composer])
        text("©cmt", bag[TagKey.Comment])
        text("cprt", bag[TagKey.Copyright])
        text("©lyr", bag[TagKey.Lyric])
        bag[TagKey.Track]?.let { ilst.write(item(TYPE_TRKN, 0, slashPairPayload(it))) }
        bag[TagKey.Disc]?.let { ilst.write(item(TYPE_DISK, 0, slashPairPayload(it))) }
        bag[TagKey.Isrc]?.let { ilst.write(freeformItem("ISRC", it)) }
        for ((name, value) in bag.extra) {
            if (value.isEmpty()) continue
            if (name.equals("ISRC", ignoreCase = true)) continue
            ilst.write(freeformItem(name, value))
        }
        bag.cover?.let { ilst.write(item(TYPE_COVR, if (it.isPng) 14 else 13, it.bytes)) }

        val hdlr = fullBox(
            "hdlr",
            0,
            ByteArray(4) +
                "mdir".toByteArray(Charsets.ISO_8859_1) +
                ByteArray(12) +
                byteArrayOf(0),
        )
        val meta = fullBox("meta", 0, hdlr + boxBytes("ilst", ilst.toByteArray()))
        return boxBytes("udta", meta)
    }

    private fun item(type: String, typeFlag: Int, payload: ByteArray): ByteArray =
        boxBytes(type, fullBox("data", typeFlag, ByteArray(4) + payload))

    private fun slashPairPayload(value: String): ByteArray {
        val (first, second) = parseSlashPair(value)
        val out = ByteArray(8)
        (first ?: 0).toU16be().copyInto(out, 2)
        (second ?: 0).toU16be().copyInto(out, 4)
        return out
    }

    private fun freeformItem(name: String, value: String): ByteArray {
        val mean = fullBox("mean", 0, "com.apple.iTunes".toByteArray(Charsets.UTF_8))
        val nameBox = fullBox("name", 0, name.toByteArray(Charsets.UTF_8))
        val data = fullBox("data", 1, ByteArray(4) + value.toByteArray(Charsets.UTF_8))
        return boxBytes(TYPE_FREEFORM, mean + nameBox + data)
    }
}
