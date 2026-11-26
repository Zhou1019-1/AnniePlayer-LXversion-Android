package com.annieplayer.annie_music.kt.data.sources.kuwo

import org.junit.Assert.assertEquals
import org.junit.Test

class KuwoCoverSizeTest {

    @Test
    fun playlist_detail_suffix_raised_to_700() {
        assertEquals(
            "http://img1.kwcdn.kuwo.cn/star/userpl2015/10/13/1687855674366_132026710_700.jpg",
            kuwoCoverAtSize("http://img1.kwcdn.kuwo.cn/star/userpl2015/10/13/1687855674366_132026710_150.jpg"),
        )
    }

    @Test
    fun lowercase_suffix_matches() {
        assertEquals(
            "https://img1.kuwo.cn/a/b_700.jpg",
            kuwoCoverAtSize("https://img1.kuwo.cn/a/b_500.jpg"),
        )
    }

    @Test
    fun png_suffix_matches() {
        assertEquals(
            "https://img1.kuwo.cn/a/b_700.png",
            kuwoCoverAtSize("https://img1.kuwo.cn/a/b_120.png"),
        )
    }

    @Test
    fun already_big_enough_is_untouched() {
        val big = "https://img1.kuwo.cn/a/b_1000.jpg"
        assertEquals(big, kuwoCoverAtSize(big))
        val exact = "https://img1.kuwo.cn/a/b_700.jpg"
        assertEquals(exact, kuwoCoverAtSize(exact))
    }

    @Test
    fun path_segment_120_is_raised() {
        assertEquals(
            "https://img1.kuwo.cn/star/albumcover/700/aa/bb/cc.jpg",
            kuwoCoverAtSize("https://img1.kuwo.cn/star/albumcover/120/aa/bb/cc.jpg"),
        )
    }

    @Test
    fun no_size_marker_left_alone() {
        val url = "https://img1.kuwo.cn/a/b.jpg"
        assertEquals(url, kuwoCoverAtSize(url))
    }

    @Test
    fun empty_returns_empty() {
        assertEquals("", kuwoCoverAtSize(""))
    }

    @Test
    fun custom_size_applied() {
        assertEquals(
            "https://img1.kuwo.cn/a/b_500.jpg",
            kuwoCoverAtSize("https://img1.kuwo.cn/a/b_150.jpg", 500),
        )
    }
}
