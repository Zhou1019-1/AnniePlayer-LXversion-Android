package com.annieplayer.annie_music.kt.core.state

import com.annieplayer.annie_music.kt.data.local.TagEditForm
import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import com.annieplayer.annie_music.kt.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * `UiState.copyWith` 的「不丢一次性槽位」契约。
 *
 * 背景（真实 bug）：`copyWith` 是**手工构造** `UiState(...)` 的，参数列表里没有
 * `pendingTagImport` / `localDeleteTarget`（它们要能被清空，不能走 null 回退语义）。
 * 少了显式传递的那两行，**任何一次 `copyWith`** 都会把它们重建成 null：
 * - 导入元数据点「导入」→ 先写出栈 → 出栈调用 `copyWith(pageStack=…)` → 回填槽被抹掉
 *   → 回到编辑页字段还是空的；
 * - 删除确认弹窗期间任何无关状态更新（播放页开关等）都会把弹窗关掉。
 */
class UiStateCopyWithTest {

    private val song = Song(
        id = "local:/music/a.flac",
        title = "A",
        artist = "B",
        cover = "",
        duration = 100,
        platform = MusicPlatform.local,
    )

    private fun withSlots(): UiState = UiState().copy(
        pendingTagImport = PendingTagImport("/music/a.flac", TagEditForm(title = "导入的标题")),
        pendingLyricImport = PendingLyricImport("/music/a.flac", "[00:01.00]词"),
        localDeleteTarget = song,
    )

    @Test
    fun `copyWith 之后 歌词回填槽仍然在`() {
        // 与元数据回填同一个坑：它是**单独**的一份槽，只替换歌词，不碰其它字段
        val after = withSlots().copyWith(pageStack = emptyList())
        val slot = after.pendingLyricImport
        assertNotNull("copyWith 把歌词回填槽清掉了 —— 导完歌词编辑页还是旧的", slot)
        assertEquals("/music/a.flac", slot?.path)
        assertEquals("[00:01.00]词", slot?.lyric)
    }

    @Test
    fun `copyWith 之后 导入回填槽仍然在`() {
        val after = withSlots().copyWith(pageStack = emptyList())
        val slot = after.pendingTagImport
        assertNotNull("copyWith 把回填槽清掉了 —— 导入完编辑页会是空的", slot)
        assertEquals("/music/a.flac", slot?.path)
        assertEquals("导入的标题", slot?.form?.title)
    }

    @Test
    fun `copyWith 之后 删除目标仍然在`() {
        val after = withSlots().copyWith(playerOverlayVisible = true)
        assertEquals(song, after.localDeleteTarget)
    }

    @Test
    fun `copyWith 不会把其它字段一起清空`() {
        val base = UiState(songActionsVisible = true, homeSearchExpanded = true, currentLineIndex = 7)
        val after = base.copyWith(pageStack = emptyList())
        assertEquals(true, after.songActionsVisible)
        assertEquals(true, after.homeSearchExpanded)
        assertEquals(7, after.currentLineIndex)
    }

    @Test
    fun `回填槽可以被专用 setter 清空`() {
        // 之所以不做进 copyWith：它需要能被清空，而 copyWith 的 null 是"保留旧值"
        val cleared = withSlots().copy(pendingTagImport = null)
        assertEquals(null, cleared.pendingTagImport)
    }
}
