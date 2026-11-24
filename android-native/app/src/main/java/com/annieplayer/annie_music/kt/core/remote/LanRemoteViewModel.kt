package com.annieplayer.annie_music.kt.core.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class LanRemoteViewModel : ViewModel() {

    enum class Status { Idle, Pairing, Connecting, Live, Failed }

    data class UiState(
        val status: Status = Status.Idle,
        val hostInput: String = "",
        val codeInput: String = "",
        val recentHosts: List<String> = emptyList(),
        val endpoint: String = "",
        val error: String? = null,
        val busy: Boolean = false,
        val paired: Boolean = false,
        val discovering: Boolean = false,
        val scanned: Int = 0,
        val scanTotal: Int = 0,
        val library: List<RemoteLibRow> = emptyList(),
        val libraryTotal: Int = 0,
        val libraryQuery: String = "",
        val libraryLoading: Boolean = false,
        val libraryEnd: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _now = MutableStateFlow(RemoteNowPlaying())
    val now: StateFlow<RemoteNowPlaying> = _now.asStateFlow()

    private var client: LanRemoteClient? = null
    private var eventsJob: Job? = null
    private var libraryJob: Job? = null
    private var discoveryJob: Job? = null
    private var token: String = ""
    private var playOverrideUntil: Long = 0L
    private var playOverrideValue: Boolean = false

    private fun applyNow(value: RemoteNowPlaying) {
        _now.value = if (android.os.SystemClock.elapsedRealtime() < playOverrideUntil) {
            value.copy(playing = playOverrideValue)
        } else {
            value
        }
    }

    fun open() {
        val saved = LanRemoteSettings.host
        token = LanRemoteSettings.token
        _ui.value = _ui.value.copy(
            hostInput = if (saved.isNotEmpty()) saved else _ui.value.hostInput,
            recentHosts = LanRemoteSettings.recentHosts(),
            codeInput = "",
            error = null,
            busy = false,
        )
        if (token.isNotEmpty() && _ui.value.hostInput.isNotBlank()) {
            resume()
        } else {
            _ui.value = _ui.value.copy(status = Status.Pairing)
            if (_ui.value.hostInput.isBlank()) discover()
        }
    }

    fun discover() {
        if (_ui.value.discovering) return
        val recent = LanRemoteSettings.recentHosts()
            .ifEmpty { listOfNotNull(_ui.value.hostInput.takeIf { it.isNotBlank() }) }
        _ui.value = _ui.value.copy(
            discovering = true,
            scanned = 0,
            scanTotal = 0,
            status = Status.Pairing,
            busy = false,
            error = null,
        )
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val found = LanRemoteDiscovery.discover(recent = recent) { scanned, total ->
                if (scanned == total || scanned % 8 == 0) {
                    _ui.value = _ui.value.copy(scanned = scanned, scanTotal = total)
                }
            }
            if (found != null) {
                LanRemoteSettings.rememberHost(found)
                _ui.value = _ui.value.copy(
                    hostInput = found,
                    discovering = false,
                    scanned = _ui.value.scanTotal,
                    error = null,
                )
            } else {
                _ui.value = _ui.value.copy(
                    discovering = false,
                    error = "没找到电脑：确认电脑端已打开「设置 → 手机遥控」，且与本机在同一 Wi-Fi",
                )
            }
        }
    }

    fun close() {
        eventsJob?.cancel()
        eventsJob = null
        libraryJob?.cancel()
        libraryJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        client = null
        playOverrideUntil = 0L
        _now.value = RemoteNowPlaying()
        _ui.value = _ui.value.copy(
            status = Status.Idle,
            busy = false,
            error = null,
            discovering = false,
        )
    }

    fun setHostInput(value: String) {
        _ui.value = _ui.value.copy(hostInput = value, error = null)
    }

    fun setCodeInput(value: String) {
        _ui.value = _ui.value.copy(codeInput = normalizePairCode(value).take(4), error = null)
    }

    fun useRecentHost(host: String) {
        _ui.value = _ui.value.copy(hostInput = host, error = null)
    }

    fun retry() {
        when {
            _ui.value.paired -> resume()
            _ui.value.hostInput.isNotBlank() -> pair()
            else -> discover()
        }
    }

    fun pair() {
        val base = remoteBaseUrl(_ui.value.hostInput) ?: run {
            _ui.value = _ui.value.copy(status = Status.Pairing, error = "先搜索或填写电脑地址")
            return
        }
        val code = normalizePairCode(_ui.value.codeInput)
        if (code.length != 4) {
            _ui.value = _ui.value.copy(status = Status.Pairing, error = "配对码是 4 位数字")
            return
        }
        _ui.value = _ui.value.copy(status = Status.Connecting, busy = true, error = null)
        viewModelScope.launch {
            val created = LanRemoteClient(base)
            try {
                when (val outcome = created.pair(code)) {
                    is PairOutcome.Ok -> {
                        client = created
                        token = outcome.token
                        LanRemoteSettings.token = outcome.token
                        LanRemoteSettings.host = _ui.value.hostInput
                        LanRemoteSettings.rememberHost(_ui.value.hostInput)
                        _ui.value = _ui.value.copy(
                            status = Status.Live,
                            busy = false,
                            paired = true,
                            endpoint = remoteDisplayEndpoint(base),
                            recentHosts = LanRemoteSettings.recentHosts(),
                            codeInput = "",
                            error = null,
                        )
                        fetchNow(created)
                        startEvents(created)
                        loadLibrary(reset = true)
                    }
                    is PairOutcome.Locked -> _ui.value = _ui.value.copy(
                        status = Status.Pairing,
                        busy = false,
                        error = "尝试太频繁，${outcome.retryAfterSeconds} 秒后再试",
                    )
                    PairOutcome.Rejected -> _ui.value = _ui.value.copy(
                        status = Status.Pairing,
                        busy = false,
                        error = "配对码不对",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _ui.value = _ui.value.copy(
                    status = Status.Failed,
                    busy = false,
                    error = "连不上电脑，确认在同一 Wi-Fi",
                )
            }
        }
    }

    private fun resume() {
        val base = remoteBaseUrl(_ui.value.hostInput) ?: run {
            _ui.value = _ui.value.copy(status = Status.Pairing)
            return
        }
        _ui.value = _ui.value.copy(status = Status.Connecting, busy = true, error = null)
        viewModelScope.launch {
            val created = LanRemoteClient(base)
            try {
                when (val fetch = created.state(token)) {
                    is StateFetch.Ok -> {
                        client = created
                        applyNow(fetch.now)
                        _ui.value = _ui.value.copy(
                            status = Status.Live,
                            busy = false,
                            paired = true,
                            endpoint = remoteDisplayEndpoint(base),
                            error = null,
                        )
                        startEvents(created)
                        loadLibrary(reset = true)
                    }
                    StateFetch.Unauthorized -> {
                        token = ""
                        LanRemoteSettings.token = ""
                        _ui.value = _ui.value.copy(
                            status = Status.Pairing,
                            busy = false,
                            paired = false,
                            endpoint = "",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _ui.value = _ui.value.copy(
                    status = Status.Failed,
                    busy = false,
                    error = "连不上电脑，确认在同一 Wi-Fi",
                )
            }
        }
    }

    private fun fetchNow(created: LanRemoteClient) {
        val current = token
        if (current.isEmpty()) return
        viewModelScope.launch {
            runCatching { created.state(current) }
                .getOrNull()
                ?.let { if (it is StateFetch.Ok) applyNow(it.now) }
        }
    }

    private fun startEvents(created: LanRemoteClient) {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            var failures = 0
            while (true) {
                if (token.isEmpty()) break
                var completed = false
                try {
                    created.events(token).collect { snapshot ->
                        failures = 0
                        applyNow(snapshot)
                        if (_ui.value.status != Status.Live) {
                            _ui.value = _ui.value.copy(status = Status.Live, busy = false, error = null)
                        }
                    }
                    completed = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (e is UnauthorizedException) {
                        token = ""
                        LanRemoteSettings.token = ""
                        _ui.value = _ui.value.copy(
                            status = Status.Pairing,
                            busy = false,
                            paired = false,
                            endpoint = "",
                            error = "配对已失效，请重新配对",
                        )
                        return@launch
                    }
                    failures++
                }
                if (!completed) {
                    if (failures > 3) {
                        _ui.value = _ui.value.copy(status = Status.Failed, error = "连接已断开")
                        break
                    }
                    delay(1500L * failures)
                } else {
                    delay(1200L)
                }
            }
        }
    }

    fun togglePlay() {
        val target = !_now.value.playing
        playOverrideValue = target
        playOverrideUntil = android.os.SystemClock.elapsedRealtime() + 700L
        _now.value = _now.value.copy(playing = target)
        send("playpause", null)
    }

    fun next() = send("next", null)

    fun previous() = send("prev", null)

    fun seekTo(seconds: Double) {
        send("seek", JsonPrimitive(clampRemoteSeek(seconds, _now.value.duration)))
    }

    fun setVolume(value: Double) {
        val clamped = clampRemoteVolume(value)
        _now.value = _now.value.copy(volume = clamped)
        send("volume", JsonPrimitive(clamped))
    }

    fun cycleMode() {
        _now.value = _now.value.copy(mode = nextPlayMode(_now.value.mode?.id.orEmpty()))
        send("mode", JsonPrimitive("cycle"))
    }

    fun playPath(path: String) {
        if (path.isBlank()) return
        send("playpath", JsonPrimitive(path))
    }

    private fun send(cmd: String, value: JsonElement?) {
        val created = client ?: return
        val current = token
        if (current.isEmpty()) return
        viewModelScope.launch {
            val ok = runCatching { created.command(current, cmd, value) }.isSuccess
            if (!ok) fetchNow(created)
        }
    }

    fun setLibraryQuery(value: String) {
        _ui.value = _ui.value.copy(libraryQuery = value)
    }

    fun searchLibrary() {
        loadLibrary(reset = true)
    }

    fun loadMoreLibrary() {
        loadLibrary(reset = false)
    }

    private fun loadLibrary(reset: Boolean) {
        val created = client ?: return
        val current = token
        if (current.isEmpty()) return
        val snapshot = _ui.value
        if (snapshot.libraryLoading) return
        if (!reset && snapshot.libraryEnd) return
        val offset = if (reset) 0 else snapshot.library.size
        val query = snapshot.libraryQuery
        _ui.value = snapshot.copy(
            libraryLoading = true,
            libraryEnd = false,
            library = if (reset) emptyList() else snapshot.library,
            libraryTotal = if (reset) 0 else snapshot.libraryTotal,
        )
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            try {
                val page = created.library(current, query, offset, LibraryPageSize)
                val latest = _ui.value
                val merged = if (reset) page.items else latest.library + page.items
                _ui.value = latest.copy(
                    library = merged,
                    libraryTotal = page.total,
                    libraryLoading = false,
                    libraryEnd = page.items.isEmpty() || merged.size >= page.total,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _ui.value = _ui.value.copy(libraryLoading = false, libraryEnd = true)
            }
        }
    }

    fun forgetDevice() {
        token = ""
        LanRemoteSettings.token = ""
        close()
        _ui.value = _ui.value.copy(
            status = Status.Pairing,
            paired = false,
            endpoint = "",
            codeInput = "",
            error = null,
            library = emptyList(),
            libraryTotal = 0,
            libraryQuery = "",
            libraryLoading = false,
            libraryEnd = false,
        )
    }

    private companion object {
        const val LibraryPageSize = 80
    }
}
