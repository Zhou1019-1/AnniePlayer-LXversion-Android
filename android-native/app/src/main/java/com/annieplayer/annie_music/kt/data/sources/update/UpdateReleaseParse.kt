package com.annieplayer.annie_music.kt.data.sources.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val releaseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun parseLatestRelease(body: String): UpdateInfo? {
    val array = runCatching { releaseJson.parseToJsonElement(body) }.getOrNull() as? JsonArray
        ?: return null
    val candidates = array.asSequence()
        .mapNotNull { it as? JsonObject }
        .filter { release -> release.boolField("draft").not() }
        .filter { release -> release.boolField("prerelease").not() }
        .mapNotNull { release ->
            val tag = release.textField("tag_name") ?: return@mapNotNull null
            val version = versionNameOfTag(tag) ?: return@mapNotNull null
            val pageUrl = release.textField("html_url") ?: return@mapNotNull null
            val notes = release.textField("body").orEmpty()
            val assetNames = assetNames(release)
            UpdateInfo(
                version = version,
                versionCode = parseVersionCodeFromNotes(notes)
                    ?: parseVersionCodeFromAssets(assetNames),
                notes = notes,
                pageUrl = pageUrl,
                apkUrl = apkAssetUrl(release),
                publishedAt = release.textField("published_at"),
            )
        }
        .toList()

    val coded = candidates.filter { it.versionCode != null }
    if (coded.isNotEmpty()) {
        return coded.reduceOrNull { best, next ->
            if ((next.versionCode ?: 0) > (best.versionCode ?: 0)) next else best
        }
    }
    return candidates.maxWithOrNull { left, right ->
        compareVersionNames(left.version, right.version)
    }
}

private fun JsonObject.textField(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.boolField(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: false

private fun assetNames(release: JsonObject): List<String> {
    val assets = release["assets"] as? JsonArray ?: return emptyList()
    return assets.mapNotNull { asset ->
        (asset as? JsonObject)?.textField("name")
    }
}

private fun apkAssetUrl(release: JsonObject): String? {
    val assets = release["assets"] as? JsonArray ?: return null
    return assets.mapNotNull { it as? JsonObject }
        .firstOrNull { asset ->
            asset.textField("name")?.endsWith(".apk", ignoreCase = true) == true
        }
        ?.textField("browser_download_url")
}
