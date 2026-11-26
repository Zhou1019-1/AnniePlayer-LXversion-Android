package com.annieplayer.annie_music.kt.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描范围的规则测试。
 *
 * 锁住的是「曲库里为什么会缺歌」这一类回归：
 * 1. 存储根必须包含**可移动卷**（SD 卡 / OTG U 盘），不只是内置共享存储；
 * 2. 各家客户端自己的下载目录（网易云 `netease/cloudmusic/Music` 等）必须在优先列表里 ——
 *    它们不挂在 `Music/` 底下，漏一个就等于「明明下过、曲库里没有」；
 * 3. 优先列表与跳过列表**不能有交集**：撞上的目录会被 `walk` 直接跳过，
 *    表现为「优先扫了但一首都没进来」，而且不会报错。
 */
class LocalScanScopeTest {

    // ── 卷根剥离 ──

    @Test
    fun `SD 卡与 U 盘从 App 私有目录剥出卷根`() {
        assertEquals(
            "/storage/1A2B-3C4D",
            volumeRootOf("/storage/1A2B-3C4D/Android/data/com.annieplayer.annie_music.kt/files"),
        )
        assertEquals(
            "/storage/9F8E-7D6C",
            volumeRootOf("/storage/9F8E-7D6C/Android/data/com.annieplayer.annie_music.kt/files/Music"),
        )
    }

    @Test
    fun `内置共享存储也能剥出卷根 且与 getExternalStorageDirectory 一致`() {
        assertEquals(
            "/storage/emulated/0",
            volumeRootOf("/storage/emulated/0/Android/data/com.annieplayer.annie_music.kt/files"),
        )
    }

    @Test
    fun `有些 ROM 的卷根带 public 前缀 一样能剥`() {
        assertEquals(
            "/storage/public:1A2B-3C4D",
            volumeRootOf("/storage/public:1A2B-3C4D/Android/data/pkg/files"),
        )
    }

    @Test
    fun `不含 Android 段的路径返回 null 而不是瞎猜`() {
        // 老式逻辑会按「最后几段」硬切，切错了会把某个子目录当成卷根，
        // 结果就是整棵音乐树都没被遍历
        assertNull(volumeRootOf("/storage/emulated/0/Music"))
        assertNull(volumeRootOf("/storage/1A2B-3C4D"))
        assertNull(volumeRootOf(""))
        assertNull(volumeRootOf("/Android/data/pkg/files"))
        assertNull(volumeRootOf("relative/path/Android/data/pkg/files"))
    }

    // ── 目录名单 ──

    @Test
    fun `优先目录覆盖各家客户端的下载目录`() {
        // 这几家都**不**把歌放在 `Music/` 下，只在全盘兜底时才扫得到；
        // 一旦优先列表漏了它们，用户最常抱怨的「网易云下的歌不进曲库」就会重现
        val required = listOf("netease", "cloudmusic", "CloudMusic", "kugou", "kuwo", "qqmusic", "migu")
        for (name in required) {
            assertTrue("优先目录缺少 $name", name in LocalMediaScanner.PRIORITY_DIRS)
        }
    }

    @Test
    fun `优先目录与跳过目录没有交集`() {
        val clash = LocalMediaScanner.PRIORITY_DIRS.filter { it in LocalMediaScanner.SKIP_DIRS }
        assertTrue("这些目录同时出现在两个名单里，会被静默跳过：$clash", clash.isEmpty())
    }

    @Test
    fun `优先目录没有重复项`() {
        val dup = LocalMediaScanner.PRIORITY_DIRS
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        assertTrue("优先目录有重复：$dup", dup.isEmpty())
    }
}
