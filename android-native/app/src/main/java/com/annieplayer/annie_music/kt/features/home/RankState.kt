package com.annieplayer.annie_music.kt.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.rank.RankBoard
import com.annieplayer.annie_music.kt.data.sources.rank.fetchRankBoards

class RankState {

    var platform by mutableStateOf<MusicPlatform?>(null)
        private set

    var boards by mutableStateOf<List<RankBoard>>(emptyList())
        private set

    var loading by mutableStateOf(false)
        private set

    var loaded by mutableStateOf(false)
        private set

    private var requestSeq: Int = 0

    suspend fun load(target: MusicPlatform, force: Boolean = false) {
        if (!force && platform == target && loaded) return
        val seq = ++requestSeq
        if (force || platform != target) boards = emptyList()
        platform = target
        loading = true
        loaded = false
        val result = runCatching { fetchRankBoards(target) }.getOrDefault(emptyList())
        if (seq != requestSeq) return
        boards = result
        loading = false
        loaded = true
    }
}
