package com.annieplayer.annie_music.kt.features.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.sources.home.NewTabRoom
import com.annieplayer.annie_music.kt.data.sources.home.fetchHomeNewTabRoom

class NewTabRoomState {

    var roomId by mutableStateOf<String?>(null)
        private set

    var room by mutableStateOf<NewTabRoom?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var loaded by mutableStateOf(false)
        private set

    private var requestSeq: Int = 0

    suspend fun load(platform: MusicPlatform, target: String, force: Boolean = false) {
        if (!force && roomId == target && loaded) return
        val seq = ++requestSeq
        if (roomId != target) room = null
        roomId = target
        loading = true
        loaded = false
        val result = runCatching { fetchHomeNewTabRoom(platform, target) }.getOrNull()
        if (seq != requestSeq) return
        room = result
        loading = false
        loaded = true
    }
}
