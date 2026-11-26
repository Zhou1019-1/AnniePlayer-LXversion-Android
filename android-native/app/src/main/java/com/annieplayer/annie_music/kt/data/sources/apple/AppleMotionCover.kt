package com.annieplayer.annie_music.kt.data.sources.apple

import com.annieplayer.annie_music.kt.data.network.CacheService

data class AppleMotionCover(
    val tallCover: String,
    val motionCover: String,
)

data class AppleAlbumCandidate(
    val id: String,
    val name: String,
    val artist: String,
)

internal const val AppleMotionLookupTtlMs: Long = 7L * 24 * 3600 * 1000

internal const val AppleMotionSearchLimit: Int = 10

internal const val AppleMotionDetailTries: Int = 3

private const val AppleMotionCachePrefix = "apple:motion:v2:"

internal const val AppleMotionStorefront = "cn"

internal const val AppleMotionLang = "zh-Hans"

private val AppleEditionKeywords = listOf(
    "deluxe",
    "edition",
    "remaster",
    "expanded",
    "anniversary",
    "bonus",
    "special",
    "version",
    "豪华",
    "特别",
    "纪念",
    "重制",
    "重录",
    "典藏",
    "版本",
)

private val AppleTrailingEdition = Regex(
    """\s*[-–—]\s*(single|ep)\s*$""",
    RegexOption.IGNORE_CASE,
)

private val AppleBracketBlock = Regex("""[（(\[【][^）)\]】]*[）)\]】]""")

private val AppleNonWord = Regex("""[\s\-–—_·・.,，。:：;；'’"“”!！?？&+/\\|@#*"'`~^()（）\[\]【】]""")

suspend fun findAppleMotionCover(artist: String, album: String): AppleMotionCover? {
    val albumName = album.trim()
    if (albumName.isEmpty()) return null
    val artistName = artist.trim()
    val cacheKey = AppleMotionCachePrefix + appleNormalizeKeyword(artistName + "|" + albumName)
    CacheService.get<String>(cacheKey)?.let { cached ->
        val parts = cached.split('\n')
        if (parts.size < 2 || parts[1].isEmpty()) return null
        return AppleMotionCover(tallCover = parts[0], motionCover = parts[1])
    }
    val found = _lookupAppleMotionCover(artistName, albumName)
    CacheService.set(
        cacheKey,
        if (found == null) "" else found.tallCover + "\n" + found.motionCover,
        AppleMotionLookupTtlMs,
    )
    return found
}

private suspend fun _lookupAppleMotionCover(artist: String, album: String): AppleMotionCover? {
    val term = listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" ")
    val raw = runCatching {
        appleApiGet(
            "/v1/catalog/$AppleMotionStorefront/search",
            mapOf(
                "term" to term,
                "types" to "albums",
                "limit" to AppleMotionSearchLimit.toString(),
                "platform" to "web",
                "with" to "serverBubbles",
                "omit[resource]" to "autos",
                "l" to AppleMotionLang,
            ),
        )
    }.getOrNull() ?: return null
    val all = appleAlbumCandidates(raw)
    val exact = all.filter { appleAlbumExactMatch(artist, album, it) }
    for (candidate in exact.take(AppleMotionDetailTries)) {
        val cover = _appleAlbumMotionCover(candidate.id) ?: continue
        if (cover.motionCover.isNotEmpty()) return cover
    }
    val relaxed = all
        .filter { candidate -> exact.none { it.id == candidate.id } }
        .mapIndexed { index, candidate -> candidate to appleAlbumMatchScore(artist, album, candidate, index) }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .map { it.first }
        .take(AppleMotionDetailTries)
    for (candidate in relaxed) {
        val cover = _appleAlbumMotionCover(candidate.id) ?: continue
        if (cover.motionCover.isNotEmpty()) return cover
    }
    return null
}

private suspend fun _appleAlbumMotionCover(albumId: String): AppleMotionCover? {
    val raw = runCatching {
        appleApiGet(
            "/v1/catalog/$AppleMotionStorefront/albums/$albumId",
            mapOf(
                "platform" to "web",
                "extend" to "editorialArtwork,editorialVideo",
                "l" to AppleMotionLang,
            ),
        )
    }.getOrNull() ?: return null
    val attrs = _appleObj(_appleArr(raw["data"]).firstOrNull())["attributes"]
        .let(::_appleObj)
    if (attrs.isEmpty()) return null
    val motion = _appleObj(attrs["editorialVideo"])
    val tallMotion = _appleObj(motion["motionDetailTall"])
        .ifEmpty { _appleObj(motion["motionTallVideo3x4"]) }
        .ifEmpty { _appleObj(motion["motionSquareVideo1x1"]) }
        .ifEmpty { _appleObj(motion["motionDetailSquare"]) }
    val video = _appleText(tallMotion["video"])
    if (video.isEmpty()) return null
    val tallArt = _appleObj(_appleObj(attrs["editorialArtwork"])["staticDetailTall"])
    val tallCover = if (tallArt.isEmpty()) {
        appleArtworkUrl(attrs["artwork"], AppleHeroWidth, AppleHeroHeight)
    } else {
        appleArtworkUrl(tallArt, AppleHeroWidth, AppleHeroHeight)
    }
    return AppleMotionCover(tallCover = tallCover, motionCover = video)
}

internal const val AppleArtistMotionTtlMs: Long = 7L * 24 * 3600 * 1000

internal const val AppleArtistSearchLimit: Int = 10

internal const val AppleArtistDetailTries: Int = 3

internal const val AppleArtistStillSize: Int = 1200

private const val AppleArtistMotionCachePrefix = "apple:artistmotion:v3:"

private const val AppleArtistMotionByIdCachePrefix = "apple:artistmotion:v1:"

data class AppleArtistMotion(
    val stillCover: String,
    val motionCover: String,
)

data class AppleArtistCandidate(
    val id: String,
    val name: String,
)

suspend fun findAppleArtistMotionCover(artistName: String): AppleArtistMotion? {
    val name = artistName.trim()
    if (name.isEmpty()) return null
    val cacheKey = AppleArtistMotionCachePrefix + appleNormalizeKeyword(name)
    CacheService.get<String>(cacheKey)?.let { cached ->
        val parts = cached.split('\n')
        val still = parts.getOrNull(0).orEmpty()
        val motion = parts.getOrNull(1).orEmpty()
        if (still.isEmpty() && motion.isEmpty()) return null
        return AppleArtistMotion(stillCover = still, motionCover = motion)
    }
    val found = _lookupAppleArtistMotion(name)
    CacheService.set(
        cacheKey,
        if (found == null) "" else found.stillCover + "\n" + found.motionCover,
        AppleArtistMotionTtlMs,
    )
    return found
}

internal suspend fun appleArtistMotionById(artistId: String): AppleArtistMotion? {
    val id = artistId.trim()
    if (id.isEmpty()) return null
    val cacheKey = AppleArtistMotionByIdCachePrefix + appleMusicStorefront + ":id:" + id
    CacheService.get<String>(cacheKey)?.let { cached ->
        val parts = cached.split('\n')
        val still = parts.getOrNull(0).orEmpty()
        val motion = parts.getOrNull(1).orEmpty()
        if (still.isEmpty() && motion.isEmpty()) return null
        return AppleArtistMotion(stillCover = still, motionCover = motion)
    }
    val found = fetchAppleArtistMotion(id)
    CacheService.set(
        cacheKey,
        if (found == null) "" else found.stillCover + "\n" + found.motionCover,
        AppleArtistMotionTtlMs,
    )
    return found
}

private suspend fun _lookupAppleArtistMotion(name: String): AppleArtistMotion? {
    val raw = runCatching {
        appleApiGet(
            "/v1/catalog/$AppleMotionStorefront/search",
            mapOf(
                "term" to name,
                "types" to "artists",
                "limit" to AppleArtistSearchLimit.toString(),
                "platform" to "web",
                "with" to "serverBubbles",
                "omit[resource]" to "autos",
                "l" to AppleMotionLang,
            ),
        )
    }.getOrNull() ?: return null
    val all = appleArtistCandidates(raw)
    val exact = all.filter { appleArtistExactMatch(name, it) }
    val relaxed = all
        .filter { candidate -> exact.none { it.id == candidate.id } }
        .filter { appleArtistNameMatches(name, it) }
    var stillOnly: AppleArtistMotion? = null
    for (candidate in (exact + relaxed).take(AppleArtistDetailTries)) {
        val found = _appleArtistMotionPinned(candidate.id) ?: continue
        if (found.motionCover.isNotEmpty()) return found
        if (stillOnly == null && found.stillCover.isNotEmpty()) stillOnly = found
    }
    return stillOnly
}

private suspend fun _appleArtistMotionPinned(artistId: String): AppleArtistMotion? {
    val raw = runCatching {
        appleApiGet(
            "/v1/catalog/$AppleMotionStorefront/artists/$artistId",
            mapOf(
                "platform" to "web",
                "extend" to "editorialArtwork,editorialVideo",
                "l" to AppleMotionLang,
            ),
        )
    }.getOrNull() ?: return null
    return appleArtistMotionOf(raw)
}

internal fun appleArtistMotionOf(raw: Map<String, Any?>): AppleArtistMotion? {
    val attrs = _appleObj(_appleArr(raw["data"]).firstOrNull())["attributes"]
        .let(::_appleObj)
    if (attrs.isEmpty()) return null
    val still = appleArtworkUrl(attrs["artwork"], AppleArtistStillSize, AppleArtistStillSize)
    val motion = _appleObj(attrs["editorialVideo"])
    val video = _appleText(_appleObj(motion["motionArtistSquare1x1"])["video"])
        .ifEmpty { _appleText(_appleObj(motion["motionArtistWide16x9"])["video"]) }
        .ifEmpty { _appleText(_appleObj(motion["motionArtistFullscreen16x9"])["video"]) }
    if (still.isEmpty() && video.isEmpty()) return null
    return AppleArtistMotion(stillCover = still, motionCover = video)
}

internal fun appleArtistCandidates(raw: Map<String, Any?>): List<AppleArtistCandidate> {
    val results = _appleObj(raw["results"])
    val group = _appleObj(results["artist"])
        .ifEmpty { _appleObj(results["artists"]) }
    return _appleArr(group["data"]).mapNotNull { value ->
        val item = _appleObj(value)
        val id = _appleText(item["id"])
        if (id.isEmpty()) return@mapNotNull null
        val name = _appleText(_appleObj(item["attributes"])["name"])
        if (name.isEmpty()) return@mapNotNull null
        AppleArtistCandidate(id = id, name = name)
    }
}

internal fun appleArtistExactMatch(name: String, candidate: AppleArtistCandidate): Boolean {
    val wanted = appleAlbumRawKey(name)
    if (wanted.isEmpty()) return false
    return appleAlbumRawKey(candidate.name) == wanted
}

internal fun appleArtistNameMatches(name: String, candidate: AppleArtistCandidate): Boolean {
    val wanted = appleNormalizeAlbumKey(name)
    if (wanted.isEmpty()) return false
    val got = appleNormalizeAlbumKey(candidate.name)
    if (got.isEmpty()) return false
    return got == wanted || got.contains(wanted) || wanted.contains(got)
}

internal fun appleAlbumCandidates(raw: Map<String, Any?>): List<AppleAlbumCandidate> {
    val results = _appleObj(raw["results"])
    val group = _appleObj(results["album"])
        .ifEmpty { _appleObj(results["albums"]) }
    return _appleArr(group["data"]).mapNotNull { value ->
        val item = _appleObj(value)
        val id = _appleText(item["id"])
        if (id.isEmpty()) return@mapNotNull null
        val attrs = _appleObj(item["attributes"])
        val name = _appleText(attrs["name"])
        if (name.isEmpty()) return@mapNotNull null
        AppleAlbumCandidate(
            id = id,
            name = name,
            artist = _appleText(attrs["artistName"]),
        )
    }
}

internal fun appleAlbumMatchScore(
    artist: String,
    album: String,
    candidate: AppleAlbumCandidate,
    rank: Int = 0,
): Int {
    val wanted = appleNormalizeAlbumKey(album)
    if (wanted.isEmpty()) return 0
    val got = appleNormalizeAlbumKey(candidate.name)
    if (got.isEmpty()) return 0
    val titleScore = when {
        got == wanted -> 100
        got.startsWith(wanted) || wanted.startsWith(got) -> 70
        got.contains(wanted) || wanted.contains(got) -> 40
        else -> return 0
    }
    val rawBonus = if (candidate.name.trim().equals(album.trim(), ignoreCase = true)) 5 else 0
    val rankBonus = when (rank) {
        0 -> 8
        1 -> 5
        2 -> 3
        else -> 0
    }
    val wantedArtist = appleNormalizeAlbumKey(artist)
    val gotArtist = appleNormalizeAlbumKey(candidate.artist)
    val artistScore = if (wantedArtist.isEmpty() || gotArtist.isEmpty()) {
        0
    } else {
        when {
            gotArtist == wantedArtist -> 20
            gotArtist.contains(wantedArtist) || wantedArtist.contains(gotArtist) -> 12
            else -> 0
        }
    }
    return titleScore + artistScore + rawBonus + rankBonus
}

internal fun appleAlbumRawKey(value: String): String =
    appleSpaceRun.replace(value.lowercase().trim(), " ").trim()

internal fun appleAlbumExactMatch(
    artist: String,
    album: String,
    candidate: AppleAlbumCandidate,
): Boolean {
    val wanted = appleAlbumRawKey(album)
    if (wanted.isEmpty()) return false
    if (appleAlbumRawKey(candidate.name) != wanted) return false
    val wantedArtist = appleAlbumRawKey(artist)
    if (wantedArtist.isEmpty()) return true
    return appleAlbumRawKey(candidate.artist) == wantedArtist
}

internal fun appleNormalizeAlbumKey(value: String): String {
    var text = value.lowercase()
    text = AppleBracketBlock.replace(text) { match ->
        val inner = match.value.lowercase()
        if (AppleEditionKeywords.any { inner.contains(it) }) "" else match.value
    }
    text = AppleNonWord.replace(text, "")
    val stripped = _stripEditionTokens(text)
    return if (stripped.isEmpty()) text else stripped
}

private fun _stripEditionTokens(value: String): String {
    var text = value
    while (true) {
        val token = AppleTrailingEditionToken.find(text) ?: return text
        if (token.range.first == 0) return text
        text = text.substring(0, token.range.first)
    }
}

internal fun appleNormalizeKeyword(value: String): String =
    value.lowercase().trim().replace(appleNonKeyword, " ").trim().replace(appleSpaceRun, " ")

private val appleNonKeyword = Regex("""[|/\\\n\r\t]+""")
private val appleSpaceRun = Regex("""\s{2,}""")

private val AppleTrailingEditionToken = Regex(
    """(deluxeedition|deluxe|expanded|remastered|remaster|anniversary|edition|version|bonus|special|single|ep)$""",
)

private fun _appleObj(value: Any?): Map<String, Any?> = when (value) {
    is Map<*, *> -> value.entries
        .mapNotNull { entry -> (entry.key as? String)?.let { it to entry.value } }
        .toMap()
    else -> emptyMap()
}

private fun _appleArr(value: Any?): List<Any?> = value as? List<Any?> ?: emptyList()

private fun _appleText(value: Any?): String = value?.toString()?.trim().orEmpty()
