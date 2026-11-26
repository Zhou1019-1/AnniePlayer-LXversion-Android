package com.annieplayer.annie_music.kt.data.sources.apple

import org.junit.Assert.assertEquals
import org.junit.Test

class AppleArtworkUrlTest {

    @Test
    fun tall_template_replaced_with_width_and_height() {
        val raw = mapOf("url" to "https://p.example/{w}x{h}bb.jpg")
        assertEquals("https://p.example/1200x1600bb.jpg", appleArtworkUrl(raw, 1200, 1600))
    }

    @Test
    fun square_template_uses_same_side() {
        val raw = mapOf("url" to "https://p.example/{w}x{h}bb.jpg")
        assertEquals("https://p.example/600x600bb.jpg", appleArtworkUrl(raw, 600))
    }

    @Test
    fun default_overload_still_square() {
        val raw = mapOf("url" to "https://p.example/{w}x{h}bb.jpg")
        assertEquals("https://p.example/600x600bb.jpg", appleArtworkUrl(raw))
    }

    @Test
    fun empty_url_returns_empty() {
        assertEquals("", appleArtworkUrl(null, 1200, 1600))
        assertEquals("", appleArtworkUrl(mapOf("url" to ""), 1200, 1600))
    }

    @Test
    fun url_without_template_is_untouched() {
        val raw = mapOf("url" to "https://p.example/fixed.jpg")
        assertEquals("https://p.example/fixed.jpg", appleArtworkUrl(raw, 1200, 1600))
    }
}
