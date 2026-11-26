package com.annieplayer.annie_music.kt.data

import com.annieplayer.annie_music.kt.data.model.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 「推荐结果」的平台集合。
 *
 * 这两条是**产品决定**，不是实现细节：Qobuz / KKBOX 只在切到各自 Tab 时才搜索，
 * 也永远不进推荐排序（未配置 token / 未登录时它们只会给出错误）。
 * 谁把它们加回 [MetadataSearchPlatforms]，搜索页一进来就会多挂两条错误。
 */
class MetadataSearchPlatformsTest {

    @Test
    fun `推荐集合不含 Qobuz 与 KKBOX`() {
        assertFalse(MetadataSearchPlatforms.contains(MusicPlatform.qobuz))
        assertFalse(MetadataSearchPlatforms.contains(MusicPlatform.kkbox))
    }

    @Test
    fun `推荐集合就是那五家`() {
        assertEquals(5, MetadataSearchPlatforms.size)
        assertEquals(
            listOf(
                MusicPlatform.apple,
                MusicPlatform.netease,
                MusicPlatform.qq,
                MusicPlatform.kugou,
                MusicPlatform.kuwo,
            ),
            MetadataSearchPlatforms,
        )
    }
}
