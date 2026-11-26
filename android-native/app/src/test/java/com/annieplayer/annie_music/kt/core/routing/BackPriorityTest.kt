package com.annieplayer.annie_music.kt.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回裁决的优先级表（`core/routing/OverlayStack.kt`）。
 *
 * 这张表是纯数据 + 纯函数，改一行就改变全局返回行为，而 Compose 那边没有任何编译期保护
 * （`handleBack` 的调用方各自传 `enabled = handleBack(...) == 目标`），所以顺序必须钉在测试里。
 *
 * 这里只钉**语义契约**，不钉具体枚举全集 —— 新增浮层不该让这份测试变红，
 * 只有「谁比谁先吃返回」被改错时才红。
 */
class BackPriorityTest {

    /** 搜索页的两个状态：多选态 > 搜索态（一次返回只退一层）。 */
    @Test
    fun `search selection sits between page and search`() {
        val page = BACK_PRIORITY.indexOf(BackTarget.PAGE)
        val selection = BACK_PRIORITY.indexOf(BackTarget.SEARCH_SELECTION)
        val search = BACK_PRIORITY.indexOf(BackTarget.SEARCH)

        assertTrue("三个目标都必须登记在表里", listOf(page, selection, search).all { it >= 0 })
        assertTrue("详情页必须先于搜索页的任何状态", page < selection)
        assertTrue("多选态要先于搜索态本身", selection < search)
    }

    @Test
    fun `selection mode consumes back before collapsing search`() {
        val flags = OverlayVisibilityFlags(searchExpanded = true, searchSelection = true)
        assertEquals(BackTarget.SEARCH_SELECTION, handleBack(flags))
    }

    @Test
    fun `multi select sheet consumes back before the page underneath`() {
        // 抽屉由 AppShell 渲染在页面栈之上：开着时详情页 / 歌手页都不许再吃返回，
        // 否则页面会在抽屉背后把自己的多选态悄悄清掉（表现为「返回键没反应」）。
        val flags = OverlayVisibilityFlags(page = true, multiSelect = true)
        assertEquals(BackTarget.MULTI_SELECT, handleBack(flags))
    }

    @Test
    fun `quality picker wins over multi select sheet`() {
        // 抽屉里的「下载」先关抽屉再弹面板，两者本不会同时可见；万一同时可见，先关面板。
        val flags = OverlayVisibilityFlags(qualityPicker = true, multiSelect = true)
        assertEquals(BackTarget.QUALITY_PICKER, handleBack(flags))
    }

    @Test
    fun `open detail page still wins over search selection`() {
        val flags = OverlayVisibilityFlags(
            page = true,
            searchSelection = true,
            searchExpanded = true,
        )
        assertEquals(BackTarget.PAGE, handleBack(flags))
    }

    @Test
    fun `nothing visible hands back to system`() {
        assertEquals(BackTarget.NONE, handleBack(OverlayVisibilityFlags()))
    }

    /** 同一目标只能在表里出现一次，否则 `handleBack` 的语义取决于重复项的位置。 */
    @Test
    fun `priority table has no duplicate target`() {
        assertEquals(BACK_PRIORITY.size, BACK_PRIORITY.distinct().size)
    }
}
