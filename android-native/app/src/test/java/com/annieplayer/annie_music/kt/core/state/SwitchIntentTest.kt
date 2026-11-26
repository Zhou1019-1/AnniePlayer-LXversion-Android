package com.annieplayer.annie_music.kt.core.state

import com.annieplayer.annie_music.kt.data.model.AudioInfo
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchIntentTest {

    private fun song(id: String, duration: Int = 200) = Song(
        id = id,
        title = "t-$id",
        artist = "a",
        cover = "",
        duration = duration,
        platform = MusicPlatform.netease,
    )

    private val queue = listOf(song("1"), song("2"), song("3"))

    private val playing = PlayerState(
        queue = queue,
        currentIndex = 0,
        currentSong = queue[0],
        isPlaying = true,
        audioInfo = AudioInfo(codec = "FLAC", bitDepth = 24, sampleRate = 96000),
    )

    @Test
    fun `切歌意图立刻换曲目换下标`() {
        val next = switchState(playing, 1)!!
        assertEquals(1, next.currentIndex)
        assertEquals("2", next.currentSong?.id)
    }

    @Test
    fun `切歌意图清空音质胶囊`() {
        assertTrue(switchState(playing, 1)!!.audioInfo.isEmpty)
    }

    @Test
    fun `切歌意图不改变队列与循环模式`() {
        val next = switchState(playing, 2)!!
        assertEquals(queue, next.queue)
        assertEquals(playing.loopMode, next.loopMode)
    }

    @Test
    fun `越界下标不产生新状态`() {
        assertNull(switchState(playing, 3))
        assertNull(switchState(playing, -1))
    }

    @Test
    fun `空队列不产生新状态`() {
        assertNull(switchState(PlayerState(), 0))
    }

    @Test
    fun `暂停中切歌也带播放意图`() {
        val paused = playing.copy(isPlaying = false)
        assertTrue(switchState(paused, 1)!!.isPlaying)
    }
}
