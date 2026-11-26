package com.annieplayer.annie_music.kt.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.home.QobuzDiscoverSection
import com.annieplayer.annie_music.kt.data.sources.home.fetchHomeQobuz
import com.annieplayer.annie_music.kt.data.sources.qobuz.qobuzTokenProvider

enum class QobuzDiscoverFailure {
    None,
    NoToken,
    Unauthorized,
    Other,
}

class QobuzDiscoverState {

    var platform by mutableStateOf<MusicPlatform?>(null)
        private set

    var sections by mutableStateOf<List<QobuzDiscoverSection>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var loaded by mutableStateOf(false)
        private set

    var failure by mutableStateOf(QobuzDiscoverFailure.None)
        private set

    private var requestSeq: Int = 0

    suspend fun load(target: MusicPlatform, force: Boolean = false) {
        if (target != MusicPlatform.qobuz) {
            if (platform != target) {
                requestSeq += 1
                sections = emptyList()
                loaded = false
                loading = false
                failure = QobuzDiscoverFailure.None
            }
            platform = target
            return
        }
        if (!force && platform == target && loaded) return
        val seq = ++requestSeq
        if (force || platform != target) sections = emptyList()
        platform = target
        loading = true
        loaded = false
        failure = QobuzDiscoverFailure.None
        if (qobuzTokenProvider().orEmpty().isBlank()) {
            if (seq != requestSeq) return
            loading = false
            loaded = true
            failure = QobuzDiscoverFailure.NoToken
            return
        }
        val result = runCatching { fetchHomeQobuz(force) }
        if (seq != requestSeq) return
        val value = result.getOrNull()
        sections = value.orEmpty()
        failure = when {
            value == null -> qobuzFailureOf(result.exceptionOrNull())
            else -> QobuzDiscoverFailure.None
        }
        loading = false
        loaded = true
    }

    fun reload() {
        loaded = false
    }
}

internal fun qobuzFailureOf(error: Throwable?): QobuzDiscoverFailure {
    val message = error?.message.orEmpty()
    return when {
        message.contains("401") -> QobuzDiscoverFailure.Unauthorized
        else -> QobuzDiscoverFailure.Other
    }
}

internal fun qobuzEmptyHint(failure: QobuzDiscoverFailure): Pair<String, String>? = when (failure) {
    QobuzDiscoverFailure.NoToken ->
        "未配置 Qobuz Token" to "在菜单 →「Qobuz Token 管理」里填入 Token，然后下拉刷新"

    QobuzDiscoverFailure.Unauthorized ->
        "Qobuz Token 已失效" to "在菜单 →「Qobuz Token 管理」里换一个新的 Token，然后下拉刷新"

    QobuzDiscoverFailure.Other ->
        "Qobuz 内容加载失败" to "下拉刷新重试"

    QobuzDiscoverFailure.None -> null
}
