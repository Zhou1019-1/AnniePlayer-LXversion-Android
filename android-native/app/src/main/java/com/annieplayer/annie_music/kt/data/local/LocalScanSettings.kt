package com.annieplayer.annie_music.kt.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LocalScanSettings {

    private const val PREFS_NAME = "annie_shared_prefs"
    private const val KEY_SKIP_SHORT = "local-scan-skip-short"
    private const val KEY_MIN_SECONDS = "local-scan-min-seconds"
    private const val KEY_SKIP_HIDDEN = "local-scan-skip-hidden"
    private const val KEY_RESPECT_NOMEDIA = "local-scan-respect-nomedia"

    const val DEFAULT_MIN_SECONDS = 10

    private var prefs: SharedPreferences? = null

    private val _skipShortAudio = MutableStateFlow(true)
    val skipShortAudio: StateFlow<Boolean> = _skipShortAudio.asStateFlow()

    private val _minAudioSeconds = MutableStateFlow(DEFAULT_MIN_SECONDS)
    val minAudioSeconds: StateFlow<Int> = _minAudioSeconds.asStateFlow()

    private val _skipHiddenDirs = MutableStateFlow(true)
    val skipHiddenDirs: StateFlow<Boolean> = _skipHiddenDirs.asStateFlow()

    private val _respectNoMedia = MutableStateFlow(true)
    val respectNoMedia: StateFlow<Boolean> = _respectNoMedia.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        _skipShortAudio.value = p.getBoolean(KEY_SKIP_SHORT, true)
        _minAudioSeconds.value = p.getInt(KEY_MIN_SECONDS, DEFAULT_MIN_SECONDS)
            .coerceIn(MIN_SECONDS_RANGE)
        _skipHiddenDirs.value = p.getBoolean(KEY_SKIP_HIDDEN, true)
        _respectNoMedia.value = p.getBoolean(KEY_RESPECT_NOMEDIA, true)
    }

    val minDurationSecondsNow: Int
        get() = if (_skipShortAudio.value) _minAudioSeconds.value else 0

    val skipHiddenDirsNow: Boolean get() = _skipHiddenDirs.value

    val respectNoMediaNow: Boolean get() = _respectNoMedia.value

    fun setSkipShortAudio(value: Boolean) {
        if (_skipShortAudio.value == value) return
        _skipShortAudio.value = value
        runCatching { prefs?.edit()?.putBoolean(KEY_SKIP_SHORT, value)?.apply() }
    }

    fun setMinAudioSeconds(seconds: Int) {
        val v = seconds.coerceIn(MIN_SECONDS_RANGE)
        if (_minAudioSeconds.value == v) return
        _minAudioSeconds.value = v
        runCatching { prefs?.edit()?.putInt(KEY_MIN_SECONDS, v)?.apply() }
    }

    fun setSkipHiddenDirs(value: Boolean) {
        if (_skipHiddenDirs.value == value) return
        _skipHiddenDirs.value = value
        runCatching { prefs?.edit()?.putBoolean(KEY_SKIP_HIDDEN, value)?.apply() }
    }

    fun setRespectNoMedia(value: Boolean) {
        if (_respectNoMedia.value == value) return
        _respectNoMedia.value = value
        runCatching { prefs?.edit()?.putBoolean(KEY_RESPECT_NOMEDIA, value)?.apply() }
    }

    private val MIN_SECONDS_RANGE = 5..120
}
