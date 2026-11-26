package com.annieplayer.annie_music.kt.core.remote

import com.annieplayer.annie_music.kt.data.network.globalOkHttp
import com.annieplayer.annie_music.kt.data.network.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class UnauthorizedException : IOException("unauthorized")

class LanRemoteClient(val baseUrl: String) {

    private val http: OkHttpClient = globalOkHttp

    private val sseHttp: OkHttpClient by lazy {
        globalOkHttp.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun pair(code: String): PairOutcome = withContext(Dispatchers.IO) {
        val body = jsonBody { put("code", JsonPrimitive(code)) }
        val request = Request.Builder()
            .url("$baseUrl/api/pair")
            .post(body.toRequestBody(JsonMediaType))
            .build()
        http.newCall(request).execute().use { response ->
            parsePairBody(response.code, response.body?.string().orEmpty())
        }
    }

    suspend fun state(token: String): StateFetch = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/state?token=${encodeQuery(token)}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> StateFetch.Unauthorized
                response.isSuccessful -> StateFetch.Ok(parseRemoteState(response.body?.string().orEmpty()))
                else -> throw IOException("state HTTP ${response.code}")
            }
        }
    }

    suspend fun library(token: String, query: String, offset: Int, limit: Int): RemoteLibraryPage =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/library?token=${encodeQuery(token)}" +
                "&q=${encodeQuery(query)}&off=$offset&lim=$limit"
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw UnauthorizedException()
                if (!response.isSuccessful) throw IOException("library HTTP ${response.code}")
                parseRemoteLibrary(response.body?.string().orEmpty())
            }
        }

    suspend fun command(token: String, cmd: String, value: JsonElement? = null): Boolean =
        withContext(Dispatchers.IO) {
            val body = jsonBody {
                put("token", JsonPrimitive(token))
                put("cmd", JsonPrimitive(cmd))
                if (value != null) put("value", value)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/cmd")
                .post(body.toRequestBody(JsonMediaType))
                .build()
            http.newCall(request).execute().use { response -> response.isSuccessful }
        }

    fun events(token: String): Flow<RemoteNowPlaying> = flow {
        val request = Request.Builder()
            .url("$baseUrl/events?token=${encodeQuery(token)}")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val call = sseHttp.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("events HTTP ${response.code}")
                val source = response.body?.source() ?: throw IOException("events empty body")
                val chunk = Buffer()
                val pending = StringBuilder()
                while (true) {
                    val read = source.read(chunk, 4096L)
                    if (read <= 0L) break
                    pending.append(chunk.readUtf8())
                    val parsed = parseSseChunk(pending.toString())
                    pending.setLength(0)
                    pending.append(parsed.rest)
                    for (event in parsed.events) {
                        if (event.event != "state") continue
                        emit(parseRemoteState(event.data))
                    }
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun jsonBody(block: JsonObjectBuilder.() -> Unit): String =
        json.encodeToString(JsonElement.serializer(), buildJsonObject(block))

    private fun encodeQuery(raw: String): String = URLEncoder.encode(raw, "UTF-8")

    private companion object {
        val JsonMediaType: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}
