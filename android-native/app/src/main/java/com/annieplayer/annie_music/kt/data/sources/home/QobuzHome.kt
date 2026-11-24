package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.network.cacheService
import com.annieplayer.annie_music.kt.data.sources.common.encodeComponent
import com.annieplayer.annie_music.kt.data.sources.qobuz.createSignature
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzAppId
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzBaseUrl
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzGetJson
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider

enum class QobuzDiscoverKind {
    AlbumRow,
    AlbumRowWide,
    AlbumRowLarge,
    PlaylistRow,
    TagRow,
    RankedRow,
}

data class QobuzDiscoverItem(
    val key: String,
    val title: String,
    val subtitle: String = "",
    val cover: String = "",
    val color: String = "",
    val slug: String = "",
    val rank: Int = 0,
    val target: HomeTarget? = null,
)

data class QobuzDiscoverSection(
    val id: String,
    val title: String,
    val baseline: String,
    val kind: QobuzDiscoverKind,
    val items: List<QobuzDiscoverItem>,
)

internal const val QobuzDiscoverCacheKey = "home:v3:qobuz:discover"

internal const val QobuzTagPlaylistCacheKey = "home:v3:qobuz:tag:"

internal const val QobuzTagPlaylistLimit = 60

internal const val QobuzDiscoverSectionNewReleases = "new_releases"
internal const val QobuzDiscoverSectionPlaylists = "playlists"
internal const val QobuzDiscoverSectionPlaylistTags = "playlists_tags"
internal const val QobuzDiscoverSectionIdealDiscography = "ideal_discography"
internal const val QobuzDiscoverSectionQobuzissimes = "qobuzissims"
internal const val QobuzDiscoverSectionAlbumOfTheWeek = "album_of_the_week"
internal const val QobuzDiscoverSectionPressAwards = "press_awards"
internal const val QobuzDiscoverSectionTopAlbums = "most_streamed"

internal val QobuzDiscoverOrder: List<String> = listOf(
    QobuzDiscoverSectionNewReleases,
    QobuzDiscoverSectionPlaylists,
    QobuzDiscoverSectionPlaylistTags,
    QobuzDiscoverSectionIdealDiscography,
    QobuzDiscoverSectionQobuzissimes,
    QobuzDiscoverSectionAlbumOfTheWeek,
    QobuzDiscoverSectionPressAwards,
    QobuzDiscoverSectionTopAlbums,
)

internal fun qobuzDiscoverTitle(sectionId: String): String = when (sectionId) {
    QobuzDiscoverSectionNewReleases -> "New Releases"
    QobuzDiscoverSectionPlaylists -> "Qobuz Playlists"
    QobuzDiscoverSectionPlaylistTags -> "Playlists by Category"
    QobuzDiscoverSectionIdealDiscography -> "Qobuz Essential Discography"
    QobuzDiscoverSectionQobuzissimes -> "Qobuzissime"
    QobuzDiscoverSectionAlbumOfTheWeek -> "Albums of the Week"
    QobuzDiscoverSectionPressAwards -> "Press Accolades"
    QobuzDiscoverSectionTopAlbums -> "Top Albums"
    else -> ""
}

internal fun qobuzDiscoverBaseline(sectionId: String): String = when (sectionId) {
    QobuzDiscoverSectionNewReleases -> "Handpicked by Qobuz."
    QobuzDiscoverSectionPlaylists -> "Curated by our experts."
    QobuzDiscoverSectionPlaylistTags -> "Perfect for every moment."
    QobuzDiscoverSectionIdealDiscography -> "Records that made history."
    QobuzDiscoverSectionQobuzissimes -> "Awarded to our favorite emerging artists."
    QobuzDiscoverSectionAlbumOfTheWeek -> "Weekly standout releases."
    QobuzDiscoverSectionPressAwards -> "Critically acclaimed releases."
    QobuzDiscoverSectionTopAlbums -> "Most streamed titles from the community."
    else -> ""
}

internal fun qobuzDiscoverKind(sectionId: String): QobuzDiscoverKind = when (sectionId) {
    QobuzDiscoverSectionPlaylists -> QobuzDiscoverKind.PlaylistRow
    QobuzDiscoverSectionPlaylistTags -> QobuzDiscoverKind.TagRow
    QobuzDiscoverSectionQobuzissimes -> QobuzDiscoverKind.AlbumRowWide
    QobuzDiscoverSectionPressAwards -> QobuzDiscoverKind.AlbumRowLarge
    QobuzDiscoverSectionTopAlbums -> QobuzDiscoverKind.RankedRow
    else -> QobuzDiscoverKind.AlbumRow
}

suspend fun fetchHomeQobuz(force: Boolean = false): List<QobuzDiscoverSection> {
    val token = qobuzTokenProvider().orEmpty()
    if (!force) {
        val cached = cacheService.get<Map<String, Any?>>(QobuzDiscoverCacheKey)
        if (cached != null) {
            val memo = parseQobuzDiscover(cached)
            if (memo.isNotEmpty()) return memo
        }
    }
    val params = mapOf("app_id" to qobuzAppId)
    val s = createSignature("discover/index", params)
    val url =
        "${qobuzBaseUrl}discover/index?app_id=$qobuzAppId&request_ts=${s.ts}&request_sig=${s.sig}"
    val raw = qobuzGetJson(url, token)
    val sections = parseQobuzDiscover(raw)
    if (sections.isNotEmpty()) cacheService.set(QobuzDiscoverCacheKey, raw, HomeCacheTtlMs)
    return sections
}

suspend fun fetchHomeQobuzTagPlaylists(slug: String): List<QobuzDiscoverItem> {
    if (slug.isEmpty()) return emptyList()
    val cacheKey = QobuzTagPlaylistCacheKey + slug
    val cached = cacheService.get<Map<String, Any?>>(cacheKey)
    if (cached != null) {
        val memo = qobuzDiscoverPlaylistItems(jsonList(cached["items"]))
        if (memo.isNotEmpty()) return memo
    }
    val token = qobuzTokenProvider().orEmpty()
    val params = mapOf(
        "app_id" to qobuzAppId,
        "tags" to slug,
        "limit" to QobuzTagPlaylistLimit.toString(),
    )
    val s = createSignature("discover/playlists", params)
    val url = "${qobuzBaseUrl}discover/playlists?app_id=$qobuzAppId" +
        "&tags=${encodeComponent(slug)}&limit=$QobuzTagPlaylistLimit" +
        "&request_ts=${s.ts}&request_sig=${s.sig}"
    val raw = qobuzGetJson(url, token)
    val items = qobuzDiscoverPlaylistItems(jsonList(raw["items"]))
    if (items.isNotEmpty()) cacheService.set(cacheKey, raw, HomeCacheTtlMs)
    return items
}

internal fun parseQobuzDiscover(raw: Map<String, Any?>): List<QobuzDiscoverSection> {
    val containers = jsonMap(raw["containers"]) ?: return emptyList()
    val out = ArrayList<QobuzDiscoverSection>(QobuzDiscoverOrder.size)
    for (sectionId in QobuzDiscoverOrder) {
        val items = qobuzDiscoverSectionItems(sectionId, containers[sectionId])
        if (items.isEmpty()) continue
        out += QobuzDiscoverSection(
            id = sectionId,
            title = qobuzDiscoverTitle(sectionId),
            baseline = qobuzDiscoverBaseline(sectionId),
            kind = qobuzDiscoverKind(sectionId),
            items = items,
        )
    }
    return out
}

private fun qobuzDiscoverSectionItems(
    sectionId: String,
    container: Any?,
): List<QobuzDiscoverItem> {
    val wrapper = jsonMap(container) ?: return emptyList()
    val data = jsonMap(wrapper["data"]) ?: return emptyList()
    val raw = jsonList(data["items"])
    return when (sectionId) {
        QobuzDiscoverSectionPlaylists -> qobuzDiscoverPlaylistItems(raw)
        QobuzDiscoverSectionPlaylistTags -> qobuzDiscoverTagItems(raw)
        QobuzDiscoverSectionTopAlbums -> qobuzDiscoverAlbumItems(raw, ranked = true)
        else -> qobuzDiscoverAlbumItems(raw, ranked = false)
    }
}

internal fun qobuzDiscoverAlbumItems(
    raw: List<Any?>,
    ranked: Boolean,
): List<QobuzDiscoverItem> {
    val out = ArrayList<QobuzDiscoverItem>(raw.size)
    var index = 0
    for (entry in raw) {
        index += 1
        val item = jsonMap(entry) ?: continue
        val id = jsonStr(item["id"])
        if (id.isEmpty()) continue
        val baseTitle = jsonStr(item["title"])
        if (baseTitle.isEmpty()) continue
        val version = jsonStr(item["version"])
        val title = if (version.isEmpty()) baseTitle else "$baseTitle ($version)"
        val cover = qobuzDiscoverAlbumCover(item["image"])
        val artist = qobuzDiscoverArtistName(item["artists"])
        val year = qobuzDiscoverYear(item["dates"])
        val subtitle = listOf(artist, year).filter { it.isNotEmpty() }.joinToString(" • ")
        out += QobuzDiscoverItem(
            key = "$id",
            title = title,
            subtitle = subtitle,
            cover = cover,
            rank = if (ranked) index else 0,
            target = HomeTarget.Album(
                platform = MusicPlatform.qobuz,
                id = id,
                name = title,
                artist = artist,
                cover = cover,
            ),
        )
    }
    return out
}

internal fun qobuzDiscoverPlaylistItems(raw: List<Any?>): List<QobuzDiscoverItem> {
    val out = ArrayList<QobuzDiscoverItem>(raw.size)
    for (entry in raw) {
        val item = jsonMap(entry) ?: continue
        val id = jsonStr(item["id"])
        if (id.isEmpty()) continue
        val name = jsonStr(item["name"])
        if (name.isEmpty()) continue
        val cover = qobuzDiscoverPlaylistCover(item["image"])
        val owner = jsonMap(item["owner"])?.let { jsonStr(it["name"]) }.orEmpty()
        out += QobuzDiscoverItem(
            key = "$id",
            title = name,
            subtitle = owner,
            cover = cover,
            target = HomeTarget.Playlist(
                platform = MusicPlatform.qobuz,
                id = id,
                name = name,
                cover = cover,
            ),
        )
    }
    return out
}

internal fun qobuzDiscoverTagItems(raw: List<Any?>): List<QobuzDiscoverItem> {
    val out = ArrayList<QobuzDiscoverItem>(raw.size)
    for (entry in raw) {
        val item = jsonMap(entry) ?: continue
        val name = jsonStr(item["name"])
        if (name.isEmpty()) continue
        val slug = jsonStr(item["slug"])
        val id = jsonStr(item["id"])
        out += QobuzDiscoverItem(
            key = if (id.isEmpty()) slug else id,
            title = name,
            color = jsonStr(item["color"]),
            slug = slug,
        )
    }
    return out
}

internal fun qobuzDiscoverAlbumCover(image: Any?): String {
    val map = jsonMap(image) ?: return ""
    for (key in listOf("large", "thumbnail", "small", "extralarge", "mega")) {
        val url = jsonStr(map[key])
        if (url.isNotEmpty()) return url
    }
    return ""
}

internal fun qobuzDiscoverPlaylistCover(image: Any?): String {
    val map = jsonMap(image) ?: return ""
    val square = jsonMap(map["square"])
    if (square != null) {
        for (key in listOf("full", "large", "medium", "small")) {
            val url = jsonStr(square[key])
            if (url.isNotEmpty()) return url
        }
    }
    val rectangle = jsonStr(map["rectangle"])
    if (rectangle.isNotEmpty()) return rectangle
    val covers = jsonList(map["covers"])
    if (covers.isNotEmpty()) return jsonStr(covers.first())
    return ""
}

internal fun qobuzDiscoverArtistName(artists: Any?): String {
    val list = jsonList(artists)
    for (entry in list) {
        val map = jsonMap(entry) ?: continue
        val roles = jsonList(map["roles"]).map { jsonStr(it) }
        if (roles.isNotEmpty() && roles.none { it == "main-artist" }) continue
        val name = jsonStr(map["name"])
        if (name.isNotEmpty()) return name
    }
    return ""
}

internal fun qobuzDiscoverYear(dates: Any?): String {
    val map = jsonMap(dates) ?: return ""
    for (key in listOf("original", "stream", "download", "purchase")) {
        val value = jsonStr(map[key])
        if (value.length >= 4) return value.substring(0, 4)
    }
    return ""
}
