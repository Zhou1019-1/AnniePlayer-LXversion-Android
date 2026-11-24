package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.sources.common.MetadataRank
import com.annieplayer.annie_music.kt.data.sources.common.RankCandidate
import com.annieplayer.annie_music.kt.data.sources.common.RankQuery

/**
 * 「进搜索页就并发打」的平台集合 —— 也是**「推荐结果」的全部来源**。
 *
 * 只有这五家。`qobuz`（要 token）与 `kkbox`（要登录会话）**不在里面**：
 * 它们是 Tab 里的两个懒加载页，切过去才发请求，而且不参与推荐排序
 * （未配置帐号时它们必然失败，混进推荐只会让最上面那几条变成错误）。
 * `local` 当然更不在其中。
 */
val MetadataSearchPlatforms: List<MusicPlatform> = listOf(
    MusicPlatform.apple,
    MusicPlatform.netease,
    MusicPlatform.qq,
    MusicPlatform.kugou,
    MusicPlatform.kuwo,
)

/**
 * 单个平台的搜索结果。
 *
 * [error] 非空 = 这个平台这次失败了（超时/被墙/接口变更）。**只影响它自己那一栏**：
 * 用户还能拿其它平台的结果导入，不会因为酷狗挂了就整页空白。
 */
data class MetadataPlatformResult(
    val platform: MusicPlatform,
    val songs: List<Song>,
    val error: String? = null,
)

/**
 * 「推荐结果」：**Apple Music 恒在第一段**，其余平台混在一起按相关度排。
 *
 * 相关度只看标题/艺术家/专辑（见 [MetadataRank]）—— 不看时长、不看平台顺序。
 */
fun recommendedMetadata(
    query: RankQuery,
    results: List<MetadataPlatformResult>,
): List<Song> = MetadataRank.rank(
    query = query,
    items = results.flatMap { it.songs },
) { song ->
    RankCandidate(
        title = song.title,
        artist = song.artist,
        album = song.album.orEmpty(),
        platform = song.platform,
    )
}
