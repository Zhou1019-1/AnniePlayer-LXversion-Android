package com.annieplayer.annie_music.kt.data.sources.update

import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object UpdateApi {
    const val UPSTREAM_REPO = "Zhou1019-1/AnniePlayer-LXversion-Android"
    const val FORK_REPO = "chenhaochen66/AnniePlayer-LXversion-Android"
    const val DEFAULT_REPO = UPSTREAM_REPO

    fun releasesUrl(repo: String = DEFAULT_REPO): String =
        "https://api.github.com/repos/$repo/releases?per_page=10"

    sealed interface Result {
        data class Loaded(val info: UpdateInfo?, val etag: String?) : Result

        data object NotModified : Result

        data object Failed : Result
    }

    suspend fun fetch(repo: String, userAgent: String, etag: String?): Result =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(releasesUrl(repo))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", userAgent)
            if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
            try {
                globalOkHttp.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.code == 304 -> Result.NotModified
                        !response.isSuccessful -> Result.Failed
                        else -> Result.Loaded(parseLatestRelease(body), response.header("ETag"))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.Failed
            }
        }
}
