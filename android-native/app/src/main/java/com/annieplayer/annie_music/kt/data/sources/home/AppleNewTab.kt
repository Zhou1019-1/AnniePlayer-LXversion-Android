package com.annieplayer.annie_music.kt.data.sources.home

import com.annieplayer.annie_music.kt.data.model.HomeTarget
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.network.CacheService
import com.annieplayer.annie_music.kt.data.sources.apple.appleApiGet
import com.annieplayer.annie_music.kt.data.sources.apple.appleArtworkUrl
import com.annieplayer.annie_music.kt.data.sources.apple.appleCatalogLang
import com.annieplayer.annie_music.kt.data.sources.apple.appleMusicStorefront
import com.annieplayer.annie_music.kt.data.sources.apple.appleSongOf

private const val AppleNewTabHeroKind = 316
private const val AppleNewTabCardsKind = 326
private const val AppleNewTabTracksKind = 327

private const val AppleNewTabCoverSize = 400

internal suspend fun fetchHomeNewTabApple(): List<NewTabSection> {
    val storefront = appleMusicStorefront
    val cacheKey = NewTabCachePrefix + storefront
    val cached = CacheService.get<Map<String, Any?>>(cacheKey)
    val raw = cached ?: runCatching {
        appleApiGet(
            "/v1/editorial/$storefront/groupings",
            mapOf(
                "name" to "music",
                "platform" to "android",
                "l" to appleCatalogLang(),
            ),
        )
    }.getOrNull()?.also { CacheService.set(cacheKey, it, HomeCacheTtlMs) }
    if (raw == null) return emptyList()
    return parseAppleNewTab(raw)
}

internal fun parseAppleNewTab(raw: Map<String, Any?>): List<NewTabSection> {
    val grouping = jsonMap(jsonList(raw["data"]).firstOrNull()) ?: return emptyList()
    val groupingRel = jsonMap(grouping["relationships"]) ?: return emptyList()
    val tab = jsonMap(jsonList(jsonMap(groupingRel["tabs"])?.get("data")).firstOrNull())
        ?: return emptyList()
    val tabRel = jsonMap(tab["relationships"]) ?: return emptyList()
    val elements = jsonList(jsonMap(tabRel["children"])?.get("data"))
    return elements.mapNotNull(::_appleNewTabSection)
}

private fun _appleNewTabSection(value: Any?): NewTabSection? {
    val element = jsonMap(value) ?: return null
    val id = jsonStr(element["id"])
    if (id.isEmpty()) return null
    val attrs = jsonMap(element["attributes"]) ?: emptyMap()
    val kind = jsonStr(attrs["editorialElementKind"]).toIntOrNull() ?: return null
    val title = jsonStr(attrs["name"])
    val expanded = jsonStr(attrs["displayStyle"]) == "expanded"
    val rel = jsonMap(element["relationships"]) ?: emptyMap()
    val roomId = jsonStr(
        jsonMap(jsonList(jsonMap(rel["room"])?.get("data")).firstOrNull())?.get("id"),
    )
    return when (kind) {
        AppleNewTabTracksKind -> _appleNewTabTracks(id, title, rel, expanded, roomId)
        AppleNewTabHeroKind -> _appleNewTabHero(id, title, rel, roomId)
        AppleNewTabCardsKind -> _appleNewTabCards(id, title, rel, expanded, roomId)
        else -> null
    }
}

private fun _appleNewTabTracks(
    id: String,
    title: String,
    rel: Map<String, Any?>,
    expanded: Boolean,
    roomId: String,
): NewTabSection? {
    val songs = jsonList(jsonMap(rel["contents"])?.get("data"))
        .mapNotNull(::appleSongOf)
    if (songs.isEmpty()) return null
    return NewTabSection(
        id = id,
        title = title,
        kind = NewTabSectionKind.Tracks,
        expanded = expanded,
        roomId = roomId,
        songs = songs,
    )
}

private fun _appleNewTabHero(
    id: String,
    title: String,
    rel: Map<String, Any?>,
    roomId: String,
): NewTabSection? {
    val items = jsonList(jsonMap(rel["children"])?.get("data")).mapNotNull { raw ->
        val child = jsonMap(raw) ?: return@mapNotNull null
        val childAttrs = jsonMap(child["attributes"]) ?: emptyMap()
        val childRel = jsonMap(child["relationships"]) ?: emptyMap()
        val content = jsonList(jsonMap(childRel["contents"])?.get("data")).firstOrNull()
        val card = _appleNewTabCard("$id:", content) ?: return@mapNotNull null
        card.copy(eyebrow = jsonStr(childAttrs["designBadge"]))
    }
    if (items.isEmpty()) return null
    return NewTabSection(
        id = id,
        title = title,
        kind = NewTabSectionKind.Hero,
        roomId = roomId,
        items = items,
    )
}

private fun _appleNewTabCards(
    id: String,
    title: String,
    rel: Map<String, Any?>,
    expanded: Boolean,
    roomId: String,
): NewTabSection? {
    val items = jsonList(jsonMap(rel["contents"])?.get("data"))
        .mapNotNull { raw -> _appleNewTabCard("$id:", raw) }
    if (items.isEmpty()) return null
    return NewTabSection(
        id = id,
        title = title,
        kind = NewTabSectionKind.Cards,
        expanded = expanded,
        roomId = roomId,
        items = items,
    )
}

private fun _appleNewTabCard(prefix: String, value: Any?): NewTabItem? {
    val item = jsonMap(value) ?: return null
    val entityId = jsonStr(item["id"])
    if (entityId.isEmpty()) return null
    val attrs = jsonMap(item["attributes"]) ?: return null
    val name = jsonStr(attrs["name"])
    if (name.isEmpty()) return null
    val cover = appleArtworkUrl(attrs["artwork"], AppleNewTabCoverSize)
    val atmos = jsonList(attrs["audioTraits"]).any { it == "atmos" }
    return when (jsonStr(item["type"])) {
        "albums" -> {
            val artist = jsonStr(attrs["artistName"])
            NewTabItem(
                key = prefix + entityId,
                title = name,
                subtitle = artist,
                cover = cover,
                atmos = atmos,
                target = HomeTarget.Album(
                    platform = MusicPlatform.apple,
                    id = entityId,
                    name = name,
                    artist = artist,
                    cover = cover,
                ),
            )
        }

        "playlists" -> NewTabItem(
            key = prefix + entityId,
            title = name,
            subtitle = jsonStr(attrs["curatorName"]),
            cover = cover,
            atmos = atmos,
            target = HomeTarget.Playlist(
                platform = MusicPlatform.apple,
                id = entityId,
                name = name,
                cover = cover,
            ),
        )

        else -> null
    }
}

internal suspend fun fetchHomeNewTabRoomApple(roomId: String): NewTabRoom? {
    if (roomId.isEmpty()) return null
    val storefront = appleMusicStorefront
    val cacheKey = NewTabRoomCachePrefix + storefront + ":" + roomId
    val cached = CacheService.get<Map<String, Any?>>(cacheKey)
    val raw = cached ?: runCatching {
        appleApiGet(
            "/v1/editorial/$storefront/rooms/$roomId",
            mapOf(
                "platform" to "android",
                "l" to appleCatalogLang(),
            ),
        )
    }.getOrNull()?.also { CacheService.set(cacheKey, it, HomeCacheTtlMs) }
    if (raw == null) return null
    return parseAppleNewTabRoom(raw)
}

internal fun parseAppleNewTabRoom(raw: Map<String, Any?>): NewTabRoom? {
    val room = jsonMap(jsonList(raw["data"]).firstOrNull()) ?: return null
    val id = jsonStr(room["id"]).ifEmpty { return null }
    val attrs = jsonMap(room["attributes"]) ?: emptyMap()
    val title = jsonStr(attrs["title"])
    val rel = jsonMap(room["relationships"]) ?: emptyMap()
    val contents = jsonList(jsonMap(rel["contents"])?.get("data"))
    val isSongs = contents.any { value -> jsonStr(jsonMap(value)?.get("type")) == "songs" }
    if (isSongs) {
        val songs = contents
            .filter { value -> jsonStr(jsonMap(value)?.get("type")) == "songs" }
            .mapNotNull(::appleSongOf)
        if (songs.isEmpty()) return null
        return NewTabRoom(
            id = id,
            title = title,
            kind = NewTabSectionKind.Tracks,
            songs = songs,
        )
    }
    val items = contents.mapNotNull { value -> _appleNewTabCard("$id:", value) }
    if (items.isEmpty()) return null
    return NewTabRoom(
        id = id,
        title = title,
        kind = NewTabSectionKind.Cards,
        items = items,
    )
}
