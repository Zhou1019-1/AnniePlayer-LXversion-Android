package com.annieplayer.annie_music.kt.data.sources.rank

import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.sources.apple.AppleSongFields
import com.annieplayer.annie_music.kt.data.sources.apple.appleApiGet
import com.annieplayer.annie_music.kt.data.sources.apple.appleArtworkUrl
import com.annieplayer.annie_music.kt.data.sources.apple.appleCatalogLang
import com.annieplayer.annie_music.kt.data.sources.apple.appleMusicStorefront
import com.annieplayer.annie_music.kt.data.sources.apple.appleSongOf
import com.annieplayer.annie_music.kt.data.sources.home.NewTabSection
import com.annieplayer.annie_music.kt.data.sources.home.fetchHomeNewTab

const val AppleRankChartLimit: Int = 50

internal const val AppleRankCoverSize: Int = 400

internal const val AppleRankCachePrefix = "applerank:v2:"

internal const val AppleRankCacheTtlMs: Long = 10 * 60 * 1000L

private const val AppleRankPlaylistFields = "name,curatorName,artwork,url"

private const val AppleRankAlbumFields = "name,artistName,artwork,url"

data class AppleChartEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val cover: String,
    val song: Song? = null,
    val target: HomeTarget? = null,
)

enum class AppleChartKind {
    Tracks,
    Cards,
}

data class AppleChartSection(
    val id: String,
    val title: String,
    val kind: AppleChartKind,
    val entries: List<AppleChartEntry>,
)

suspend fun fetchAppleRankSections(): List<AppleChartSection> {
    val editorial = runCatching { fetchHomeNewTab(MusicPlatform.apple) }.getOrDefault(emptyList())
    val charts = fetchAppleRankCharts()
    val out = mutableListOf<AppleChartSection>()
    out += charts.filter { it.id == AppleRankSongChartId }
    out += _editorialSection(editorial, AppleRankCityChartId, ::_isCityChartSection)
    out += _editorialSection(editorial, AppleRankWeeklyChartId, ::_isWeeklyChartSection)
    out += charts.filter { it.id != AppleRankSongChartId }
    return out
}

internal const val AppleRankSongChartId = "songs"

internal const val AppleRankPlaylistChartId = "playlists"

internal const val AppleRankAlbumChartId = "albums"

internal const val AppleRankCityChartId = "city"

internal const val AppleRankWeeklyChartId = "weekly"

private fun _editorialSection(
    sections: List<NewTabSection>,
    id: String,
    match: (String) -> Boolean,
): List<AppleChartSection> {
    val hit = sections.firstOrNull { match(it.title) } ?: return emptyList()
    val entries = hit.items.map { item ->
        AppleChartEntry(
            key = item.key,
            title = item.title,
            subtitle = item.subtitle,
            cover = item.cover,
            target = item.target,
        )
    }
    if (entries.isEmpty()) return emptyList()
    return listOf(
        AppleChartSection(
            id = id,
            title = hit.title,
            kind = AppleChartKind.Cards,
            entries = entries,
        ),
    )
}

private fun _isCityChartSection(title: String): Boolean =
    title.contains("城市排行榜") || title.contains("Top 25") || title.contains("City")

private fun _isWeeklyChartSection(title: String): Boolean =
    title.contains("每周热门") || title.contains("Daily Top 100") || title.contains("Top 100")

private suspend fun fetchAppleRankCharts(): List<AppleChartSection> {
    val storefront = appleMusicStorefront
    val cacheKey = AppleRankCachePrefix + storefront
    val cached = CacheService.get<Map<String, Any?>>(cacheKey)
    val raw = cached ?: runCatching {
        appleApiGet(
            "/v1/catalog/$storefront/charts",
            mapOf(
                "types" to "songs,playlists,albums",
                "limit" to AppleRankChartLimit.toString(),
                "platform" to "android",
                "l" to appleCatalogLang(),
                "fields[songs]" to AppleSongFields,
                "fields[playlists]" to AppleRankPlaylistFields,
                "fields[albums]" to AppleRankAlbumFields,
            ),
        )
    }.getOrNull()?.also { CacheService.set(cacheKey, it, AppleRankCacheTtlMs) }
    if (raw == null) return emptyList()
    return parseAppleRankCharts(raw)
}

internal fun parseAppleRankCharts(raw: Map<String, Any?>): List<AppleChartSection> {
    val results = normRankMap(raw["results"]) ?: return emptyList()
    val out = mutableListOf<AppleChartSection>()
    val songGroup = rankList(results["songs"]).firstOrNull().let(::normRankMap)
    if (songGroup != null) {
        val entries = rankList(songGroup["data"]).mapNotNull(::appleChartSongEntry)
        if (entries.isNotEmpty()) {
            out += AppleChartSection(
                id = AppleRankSongChartId,
                title = rankStr(songGroup["name"]).ifEmpty { "热门歌曲排行" },
                kind = AppleChartKind.Tracks,
                entries = entries,
            )
        }
    }
    val playlistGroup = rankList(results["playlists"]).firstOrNull().let(::normRankMap)
    if (playlistGroup != null) {
        val entries = rankList(playlistGroup["data"]).mapNotNull(::appleChartPlaylistEntry)
        if (entries.isNotEmpty()) {
            out += AppleChartSection(
                id = AppleRankPlaylistChartId,
                title = rankStr(playlistGroup["name"]).ifEmpty { "热门歌单排行" },
                kind = AppleChartKind.Cards,
                entries = entries,
            )
        }
    }
    val albumGroup = rankList(results["albums"]).firstOrNull().let(::normRankMap)
    if (albumGroup != null) {
        val entries = rankList(albumGroup["data"]).mapNotNull(::appleChartAlbumEntry)
        if (entries.isNotEmpty()) {
            out += AppleChartSection(
                id = AppleRankAlbumChartId,
                title = rankStr(albumGroup["name"]).ifEmpty { "热门专辑排行" },
                kind = AppleChartKind.Cards,
                entries = entries,
            )
        }
    }
    return out
}

internal fun appleChartSongEntry(value: Any?): AppleChartEntry? {
    val song = appleSongOf(value) ?: return null
    return AppleChartEntry(
        key = "song:" + song.id,
        title = song.title,
        subtitle = song.artist,
        cover = song.cover,
        song = song,
    )
}

internal fun appleChartPlaylistEntry(value: Any?): AppleChartEntry? {
    val item = normRankMap(value) ?: return null
    val id = rankStr(item["id"])
    if (id.isEmpty()) return null
    val attrs = normRankMap(item["attributes"]) ?: return null
    val name = rankStr(attrs["name"])
    if (name.isEmpty()) return null
    val curator = rankStr(attrs["curatorName"])
    val cover = appleArtworkUrl(attrs["artwork"], AppleRankCoverSize)
    return AppleChartEntry(
        key = "playlist:$id",
        title = name,
        subtitle = curator,
        cover = cover,
        target = HomeTarget.Playlist(
            platform = MusicPlatform.apple,
            id = id,
            name = name,
            cover = cover,
        ),
    )
}

internal fun appleChartAlbumEntry(value: Any?): AppleChartEntry? {
    val item = normRankMap(value) ?: return null
    val id = rankStr(item["id"])
    if (id.isEmpty()) return null
    val attrs = normRankMap(item["attributes"]) ?: return null
    val name = rankStr(attrs["name"])
    if (name.isEmpty()) return null
    val artist = rankStr(attrs["artistName"])
    val cover = appleArtworkUrl(attrs["artwork"], AppleRankCoverSize)
    return AppleChartEntry(
        key = "album:$id",
        title = name,
        subtitle = artist,
        cover = cover,
        target = HomeTarget.Album(
            platform = MusicPlatform.apple,
            id = id,
            name = name,
            artist = artist,
            cover = cover,
        ),
    )
}

internal fun rankList(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()
