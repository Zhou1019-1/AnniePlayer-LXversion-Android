package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.apple.searchAppleMusic
import com.annieplayer.annie_music.kt.data.sources.kkbox.searchKkboxSongs
import com.annieplayer.annie_music.kt.data.sources.kugou.searchKugouMusic
import com.annieplayer.annie_music.kt.data.sources.kuwo.searchMusic
import com.annieplayer.annie_music.kt.data.sources.netease.searchNeteaseMusic
import com.annieplayer.annie_music.kt.data.sources.qq.searchQQMusic
import com.annieplayer.annie_music.kt.data.sources.qobuz.searchQobuzTracks

/**
 * 单曲搜索的统一分发（与 [searchAlbumByPlatform] 同构图）。
 *
 * 抽出来只有一个理由：**「搜索页」和「搜索元数据页」必须走同一份分支**。以前只有搜索页
 * 有一份 `when`，第二个调用方就得再抄一遍 —— 抄的那一刻起，两边就会在「加了一个平台」
 * 或「改了分页参数」时悄悄分叉。
 *
 * 分页参数由调用方给：搜索页要 60/25 那种长列表，元数据搜索只要 30 条。
 */
suspend fun searchSongsByPlatform(
    platform: MusicPlatform,
    query: String,
    page: Int = 1,
    pageSize: Int = 60,
    appleLimit: Int = 25,
): List<Song> = when (platform) {
    MusicPlatform.kuwo -> searchMusic(query, page = page)
    MusicPlatform.netease -> searchNeteaseMusic(query, page = page, pageSize = pageSize)
    MusicPlatform.qq -> searchQQMusic(query, page = page, pageSize = pageSize)
    MusicPlatform.kugou -> searchKugouMusic(query, page = page, pageSize = pageSize)
    MusicPlatform.qobuz -> searchQobuzTracks(
        query,
        limit = pageSize,
        offset = (page - 1) * pageSize,
    )
    MusicPlatform.apple -> searchAppleMusic(query, page = page, limit = appleLimit)
    MusicPlatform.kkbox -> searchKkboxSongs(query, page = page, limit = pageSize)
    // 本地曲库不参与云端搜索（自己那一页自带筛选框）
    MusicPlatform.local -> emptyList()
}
