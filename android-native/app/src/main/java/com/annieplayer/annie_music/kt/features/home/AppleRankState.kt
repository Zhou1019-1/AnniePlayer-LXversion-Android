package com.annieplayer.annie_music.kt.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.rank.AppleChartSection
import com.annieplayer.annie_music.kt.data.sources.rank.fetchAppleRankSections

class AppleRankState {

    var platform by mutableStateOf<MusicPlatform?>(null)
        private set

    var scope by mutableStateOf("")
        private set

    var sections by mutableStateOf<List<AppleChartSection>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var loaded by mutableStateOf(false)
        private set

    private var requestSeq: Int = 0

    suspend fun load(
        target: MusicPlatform,
        targetScope: String = "",
        force: Boolean = false,
    ) {
        if (!force && platform == target && scope == targetScope && loaded) return
        val seq = ++requestSeq
        if (force || platform != target || scope != targetScope) sections = emptyList()
        platform = target
        scope = targetScope
        loading = true
        loaded = false
        val result = runCatching { fetchAppleRankSections() }.getOrDefault(emptyList())
        if (seq != requestSeq) return
        sections = result
        loading = false
        loaded = true
    }
}
