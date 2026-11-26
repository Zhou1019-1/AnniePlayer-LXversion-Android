package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.AlbumSong
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.apple.enrichAppleSong
import com.annieplayer.annie_music.kt.data.sources.kkbox.enrichKkboxSong
import com.annieplayer.annie_music.kt.data.sources.kugou.enrichKuGouSong
import com.annieplayer.annie_music.kt.data.sources.kuwo.enrichKuwoSong
import com.annieplayer.annie_music.kt.data.sources.netease.enrichNeteaseSong
import com.annieplayer.annie_music.kt.data.sources.qobuz.getTrack
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider
import com.annieplayer.annie_music.kt.data.sources.qq.enrichQQSong

/**
 * 用**专辑级**详情补齐候选身上缺的字段（纯合并，不联网）。
 *
 * 各平台**搜索**接口只给标题/歌手/专辑/封面/时长：专辑艺术家、轨号、碟号、发行日期
 * 都在专辑详情里。规则是**只填空**，候选已有的值一律不覆盖（搜索接口给的更接近用户搜的那条）。
 *
 * `albumArtist` 例外：以专辑为准 —— QQ 的搜索 mapper 曾经把曲目艺人串塞进这个字段，
 * 那不是专辑艺术家（那个编造值已在 mapper 里去掉）。
 */
internal fun applyAlbumDetail(song: Song, album: RepoAlbumSongs?): Song {
    val a = album ?: return song
    val track = a.songs.firstOrNull { it.id == song.id }
    val pub = a.pub.takeIf { it.isNotBlank() }
    return song.copy(
        trackNumber = song.trackNumber ?: track?.track?.toString(),
        discNumber = song.discNumber ?: track?.discNumber?.toString(),
        albumArtist = track?.albumArtist ?: song.albumArtist,
        composer = song.composer ?: track?.composer,
        genre = song.genre ?: track?.genre ?: a.genre.takeIf { it.isNotBlank() },
        isrc = song.isrc ?: track?.isrc,
        releaseDate = song.releaseDate ?: pub,
    )
}

/** 平台自有 enrich（专辑艺人更准，个别平台还会顺手补 albumId / 结构化歌手）。只补空。 */
private fun mergePlatformEnrich(
    song: Song,
    trackNumber: String? = null,
    discNumber: String? = null,
    releaseDate: String? = null,
    albumArtist: String? = null,
): Song = song.copy(
    trackNumber = song.trackNumber ?: trackNumber,
    discNumber = song.discNumber ?: discNumber,
    releaseDate = song.releaseDate ?: releaseDate,
    albumArtist = song.albumArtist ?: albumArtist,
)

/**
 * 「导入元数据」的详情补全门面。
 *
 * 为什么要有它：搜索接口拿不到的字段（轨号/碟号/专辑艺术家/发行日期/版权）各平台都分散在
 * 不同的详情接口上，导入页不该自己拼 —— 全部收在这里，一处维护。
 *
 * 每一步都 `runCatching`：任何一步失败只是少几个字段，**不影响其它字段和整个导入流程**。
 * 天生拿不到的（国产四家没有 genre / composer / isrc）就留空，不编造。
 */
suspend fun enrichMetadataCandidate(song: Song): Song {
    val albumId = song.albumId.orEmpty()
    var out = song

    // ① 专辑曲目：轨号 / 碟号 / 专辑艺术家 / 发行日期（五个平台同一套）
    if (albumId.isNotBlank()) {
        runCatching { fetchAlbumSongsByPlatform(out.platform, albumId) }
            .getOrNull()
            ?.let { out = applyAlbumDetail(out, it) }
    }

    // ② 平台自有 enrich
    out = runCatching {
        when (out.platform) {
            MusicPlatform.apple -> enrichAppleSong(out)
            MusicPlatform.netease -> enrichNeteaseSong(out.id, albumId).let {
                mergePlatformEnrich(out, it.trackNumber, it.discNumber, it.releaseDate, it.albumArtist)
            }
            MusicPlatform.qq -> enrichQQSong(out.id, albumId).let {
                mergePlatformEnrich(out, it.trackNumber, it.discNumber, it.releaseDate, it.albumArtist)
            }
            MusicPlatform.kugou -> enrichKuGouSong(out.id).let {
                mergePlatformEnrich(out, it.trackNumber, it.discNumber, it.releaseDate, it.albumArtist)
            }
            MusicPlatform.kuwo -> enrichKuwoSong(out.id, albumId).let {
                mergePlatformEnrich(out, it.trackNumber, null, it.releaseDate, it.albumArtist)
            }
            // Qobuz 的 `track/get` 一次给全：轨号 / 碟号 / 专辑艺术家 / 发行日期 / 流派 /
            // 作曲 / ISRC / 版本说明（写进 comment）/ 歌曲级版权 —— 与下载打标读的是同一个接口，
            // 所以导入进来的字段种类和"下载下来那份文件"完全一致。
            MusicPlatform.qobuz -> {
                val token = qobuzTokenProvider().orEmpty()
                val t = if (token.isEmpty()) {
                    null
                } else {
                    runCatching { getTrack(out.id.removePrefix("qobuz:"), token) }.getOrNull()
                }
                if (t == null) {
                    out
                } else {
                    out.copy(
                        albumArtist = out.albumArtist ?: t.albumArtist,
                        trackNumber = out.trackNumber ?: t.trackNumber.takeIf { it > 0 }?.toString(),
                        discNumber = out.discNumber ?: t.discNumber?.toString(),
                        releaseDate = out.releaseDate ?: t.releaseDateOriginal
                            ?: t.year.takeIf { it > 0 }?.toString(),
                        genre = out.genre ?: t.genre,
                        composer = out.composer ?: t.composer,
                        isrc = out.isrc ?: t.isrc,
                        copyright = out.copyright ?: t.copyright,
                        version = out.version ?: t.version,
                        artistNames = out.artistNames.ifEmpty { t.artistNames },
                    )
                }
            }
            // KKBOX 的专辑接口只给这四项（碟号本来就没有）—— 与下载打标走的 enrich 是同一个函数，
            // 拿不到的字段（流派 / 作曲 / ISRC / 版权）它自己也拿不到，这里不编造。
            MusicPlatform.kkbox -> enrichKkboxSong(out.id, albumId).let {
                mergePlatformEnrich(out, it.trackNumber, it.discNumber, it.releaseDate, it.albumArtist)
            }
            else -> out
        }
    }.getOrDefault(out)

    // ③ 版权：Apple 是专辑 `copyright`，国内四家是唱片公司名 —— 统一走 fetchAlbumCopyright
    //    （流媒体下载写入版权用的是同一个函数）
    out = runCatching {
        out.copy(copyright = out.copyright ?: fetchAlbumCopyright(out.platform, out.albumId))
    }.getOrDefault(out)

    return out
}

/** 专辑里这一首的曲目信息（给测试与调试用：`applyAlbumDetail` 的输入形态）。 */
internal fun albumTrackOf(song: Song, album: RepoAlbumSongs?): AlbumSong? =
    album?.songs?.firstOrNull { it.id == song.id }
