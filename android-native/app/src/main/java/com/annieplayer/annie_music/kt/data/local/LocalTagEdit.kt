package com.annieplayer.annie_music.kt.data.local

import com.annieplayer.annie_music.kt.data.local.tag.CoverBytes
import com.annieplayer.annie_music.kt.data.local.tag.TagBag
import com.annieplayer.annie_music.kt.data.local.tag.TagKey
import java.util.Locale

/**
 * 编辑元数据的**表单模型**（纯数据 + 纯函数，不碰文件、不碰 Compose）。
 *
 * 为什么不直接拿 [TagBag] 当表单：
 * - 表单要表达「用户把这个字段清空了」，而 `TagBag` 里空串表示"保留"（见 [TagBag.cleared]）；
 * - 表单里每个字段都得有当前值可以预填，而 `TagBag` 是「**改动**」的载体，没改的字段压根不出现。
 *
 * 于是流程是：[of] 预填 → 用户改 → [diffBag] 只把**真正改过**的字段变成 `TagBag` → 写入。
 * 这是「编辑一个字段不会顺手改写另外十二个」的唯一保证。
 *
 * 字段集合与打标端（`DownloadService.buildTagBag` / `TagMapper.ofSong`）**逐项对齐**：
 * 少一个 = 下载写了编辑看不到，多一个 = 写了没有任何一端会读。
 */
data class TagEditForm(
    val title: String = "",
    /** 艺术家：**原始标签串**，不做多歌手归一化（`A、B` 不能因为编辑一次就变成 `A / B`） */
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    /** 音轨号：`3` 或 `3/12`（各格式都按这个串编码） */
    val track: String = "",
    val disc: String = "",
    val genre: String = "",
    val composer: String = "",
    val isrc: String = "",
    /** 「注释 / 版本」：打标写进 `comment`（Qobuz 的版本说明、`ExactAudioCopy` 的编码器签名都在这里） */
    val comment: String = "",
    val copyright: String = "",
    val lyric: String = "",
    /** **本次新选的**封面字节；null = 不动封面（文件里现有的保持原样） */
    val cover: CoverBytes? = null,
    /** 用户点了「移除封面」：写入时会把内嵌图删掉 */
    val removeCover: Boolean = false,
) {

    companion object {
        /** 用索引里的记录预填（文件里现在是什么，就显示什么）。 */
        fun of(meta: LocalTrackMeta): TagEditForm = TagEditForm(
            title = meta.title,
            // 用 `artist` 而不是 `artistDisplay`：展示格式（`A / B`）只是界面约定，
            // 原样写回才不会被「编辑一次」悄悄改写标签
            artist = meta.artist,
            album = meta.album,
            albumArtist = meta.albumArtist.orEmpty(),
            year = meta.year.orEmpty(),
            track = meta.track?.takeIf { it > 0 }?.toString().orEmpty(),
            disc = meta.disc?.takeIf { it > 0 }?.toString().orEmpty(),
            genre = meta.genre.orEmpty(),
            composer = meta.composer.orEmpty(),
            isrc = meta.isrc.orEmpty(),
            comment = meta.version.orEmpty(),
            copyright = meta.copyright.orEmpty(),
            lyric = meta.lyric.orEmpty(),
        )
    }
}

/**
 * 表单 → 待写入的 [TagBag]：**只有改动过的字段才会出现**。
 *
 * - 改过的非空值 → `text`
 * - 改成了空 → `cleared`（不能靠空串，见 [TagBag.cleared] 的注释）
 * - 选了新封面 → `cover`；点了移除 → `removeCover`
 *
 * 无改动时返回 `TagBag()`，`isEmpty()` 为 true（保存按钮据此置灰）。
 */
fun TagEditForm.diffBag(baseline: TagEditForm): TagBag {
    val text = LinkedHashMap<TagKey, String>()
    val cleared = LinkedHashSet<TagKey>()

    fun diff(key: TagKey, now: String, before: String) {
        val a = now.trim()
        val b = before.trim()
        if (a == b) return
        if (a.isEmpty()) cleared += key else text[key] = a
    }

    diff(TagKey.Title, title, baseline.title)
    diff(TagKey.Artist, artist, baseline.artist)
    diff(TagKey.Album, album, baseline.album)
    diff(TagKey.AlbumArtist, albumArtist, baseline.albumArtist)
    diff(TagKey.Year, year, baseline.year)
    diff(TagKey.Track, track, baseline.track)
    diff(TagKey.Disc, disc, baseline.disc)
    diff(TagKey.Genre, genre, baseline.genre)
    diff(TagKey.Composer, composer, baseline.composer)
    diff(TagKey.Isrc, isrc, baseline.isrc)
    diff(TagKey.Comment, comment, baseline.comment)
    diff(TagKey.Copyright, copyright, baseline.copyright)
    // 歌词整段比较：LRC 动一个时间戳都算改动，不做逐行拆分
    diff(TagKey.Lyric, lyric, baseline.lyric)

    // 新封面优先于「移除」：先点移除再选图时界面会把 removeCover 复位，这里再兜一次
    val newCover = cover
    return TagBag(
        text = text,
        cover = newCover,
        cleared = cleared,
        removeCover = removeCover && newCover == null,
    )
}

/** 一条非阻塞提示：值不规范但**允许保存**（文件里本来就可能有历史脏数据）。 */
data class TagEditWarning(val field: String, val message: String)

private val TRACK_RE = Regex("""^\d{1,3}(/\d{1,3})?$""")
private val ISRC_RE = Regex("""^[A-Za-z]{2}[A-Za-z0-9]{3}\d{7}$""")

/**
 * 表单校验：只给提示，**不阻止保存**。
 *
 * 硬拦的只有音轨号 / 碟号（各格式都按数字编码，塞字母进去会写成畸形帧），
 * 其余给黄字提示即可 —— 用户可能正是在修一份本来就不规范的文件，
 * 「因为 ISRC 少一位就不让改标题」是最招人烦的那种设计。
 */
fun TagEditForm.warnings(): List<TagEditWarning> {
    val out = ArrayList<TagEditWarning>(3)
    fun check(field: String, value: String, ok: Boolean, message: String) {
        if (value.isNotBlank() && !ok) out += TagEditWarning(field, message)
    }
    check("音轨号", track, TRACK_RE.matches(track.trim()), "只能填数字，或 `3/12` 这种「曲目/总数」")
    check("碟号", disc, TRACK_RE.matches(disc.trim()), "只能填数字，或 `1/2` 这种「碟号/总数」")
    check(
        "年份",
        year,
        normalizeReleaseDate(year) != null,
        "认不出日期，建议 `2023` 或 `2023-05-12`",
    )
    check(
        "ISRC",
        isrc,
        ISRC_RE.matches(isrc.trim().uppercase(Locale.ROOT)),
        "ISRC 是 12 位（如 `USRC17607839`）",
    )
    return out
}

/** 音轨号 / 碟号填错时不允许保存（只这两项是硬校验）。 */
fun TagEditForm.hasBlockingError(): Boolean =
    (track.isNotBlank() && !TRACK_RE.matches(track.trim())) ||
        (disc.isNotBlank() && !TRACK_RE.matches(disc.trim()))
