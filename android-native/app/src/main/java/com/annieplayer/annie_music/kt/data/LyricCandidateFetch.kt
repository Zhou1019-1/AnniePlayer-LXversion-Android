package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.kkbox.kkboxSidProvider
import com.annieplayer.annie_music.kt.data.sources.lyric.AppleLyricSource
import com.annieplayer.annie_music.kt.data.sources.lyric.LyricRequest
import com.annieplayer.annie_music.kt.data.sources.lyric.LyricSources

data class LyricCandidate(val lineLrc: String, val wordLrc: String?)

suspend fun fetchLyricCandidate(song: Song): LyricCandidate {
    if (song.platform == MusicPlatform.kkbox) {
        val r = fetchLyric(song, kkboxSidProvider(), autoPickTranslate = true)
        if (r.lrc.isBlank() || r.lrc.contains("暂无歌词")) {
            error("KKBOX 未登录，或这首歌没有歌词")
        }
        return LyricCandidate(
            lineLrc = DownloadService.mergeLyrics(r.lrc, r.tlyric, null),
            wordLrc = null,
        )
    }
    val source = if (song.platform == MusicPlatform.apple) {
        AppleLyricSource
    } else {
        LyricSources.of(song) ?: error("这个平台没有歌词源")
    }
    val r = source.fetch(song, LyricRequest(autoPickTranslate = true))
    return LyricCandidate(
        lineLrc = DownloadService.mergeLyrics(r.lrc, r.tlyric, null),
        wordLrc = r.wordLyric?.let {
            DownloadService.mergeLyrics(r.lrc, r.tlyric, it, forceEnhanced = true)
        },
    )
}
