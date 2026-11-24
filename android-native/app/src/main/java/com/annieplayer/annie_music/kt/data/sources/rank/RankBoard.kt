package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.home.withHomeCacheForce

const val RankPageSize: Int = 100

data class RankPreview(
    val rank: Int,
    val title: String,
    val artist: String,
)

data class RankBoard(
    val id: String,
    val name: String,
    val cover: String,
    val subtitle: String,
    val group: String,
    val top: List<RankPreview>,
)

data class RankDetail(
    val title: String,
    val subtitle: String,
    val cover: String,
    val songs: List<Song>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
) {
    val hasMore: Boolean get() = songs.isNotEmpty() && (total <= 0 || page * pageSize < total)
}

suspend fun fetchRankBoards(
    platform: MusicPlatform,
    force: Boolean = false,
): List<RankBoard> = withHomeCacheForce(force) {
    when (platform) {
        MusicPlatform.netease -> fetchNeteaseRankBoards()
        MusicPlatform.qq -> fetchQqRankBoards()
        MusicPlatform.kugou -> fetchKugouRankBoards()
        MusicPlatform.kuwo -> fetchKuwoRankBoards()
        else -> emptyList()
    }
}

suspend fun fetchRankDetail(
    platform: MusicPlatform,
    boardId: String,
    page: Int = 1,
    pageSize: Int = RankPageSize,
): RankDetail? = when (platform) {
    MusicPlatform.netease -> fetchNeteaseRankDetail(boardId, page, pageSize)
    MusicPlatform.qq -> fetchQqRankDetail(boardId, page, pageSize)
    MusicPlatform.kugou -> fetchKugouRankDetail(boardId, page, pageSize)
    MusicPlatform.kuwo -> fetchKuwoRankDetail(boardId, page, pageSize)
    else -> null
}

internal fun normRankMap(v: Any?): Map<String, Any?>? =
    (v as? Map<*, *>)?.entries?.associate { (k, value) -> k.toString() to value }

internal fun rankStr(v: Any?): String = v?.toString()?.trim().orEmpty()

internal fun rankInt(v: Any?): Int = when (v) {
    is Number -> v.toInt()
    is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0
    else -> 0
}

internal fun rankSubtitle(parts: List<String>): String =
    parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
