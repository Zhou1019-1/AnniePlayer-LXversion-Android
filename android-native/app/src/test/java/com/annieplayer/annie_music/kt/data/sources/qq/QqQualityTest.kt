package com.annieplayer.annie_music.kt.data.sources.qq

import com.annieplayer.annie_music.kt.data.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqQualityTest {

    private fun song(file: Map<String, Any?>?): Map<String, Any?> =
        if (file == null) emptyMap() else mapOf("file" to file)

    private val qingtianFile: Map<String, Any?> = mapOf(
        "media_mid" to "003Qui1q2u1Zho",
        "size_128mp3" to 4317292L,
        "size_320mp3" to 0L,
        "size_flac" to 0L,
        "size_hires" to 0L,
        "size_new" to listOf(
            186980254L, 31168013L, 80437198L, 10331568L, 0L, 23183008L, 36638639L,
            2563200L, 58977493L, 0L, 0L, 0L, 28055659L, 0L, 0L, 0L,
        ),
    )

    private val xiaopingguoFile: Map<String, Any?> = mapOf(
        "size_128mp3" to 3413690L,
        "size_320mp3" to 0L,
        "size_flac" to 0L,
        "size_hires" to 0L,
        "size_new" to listOf(
            152875246L, 0L, 0L, 9124554L, 0L, 19658731L, 0L, 2026943L,
            48239550L, 0L, 11978542L, 40037486L, 22181946L, 0L, 0L, 0L,
        ),
    )

    @Test
    fun size_new_fills_qualities_when_file_sizes_are_zero() {
        assertEquals(
            listOf(Quality.flac, Quality.k320k, Quality.k128k),
            qqQualities(song(qingtianFile)),
        )
    }

    @Test
    fun size_new_missing_slots_are_skipped() {
        assertEquals(
            listOf(Quality.k320k, Quality.k128k),
            qqQualities(song(xiaopingguoFile)),
        )
    }

    @Test
    fun flat_top_level_fields_still_win() {
        val s: Map<String, Any?> = mapOf(
            "sizeflac" to 31000000L,
            "size320" to 10000000L,
            "size128" to 4000000L,
        )
        assertEquals(
            listOf(Quality.flac, Quality.k320k, Quality.k128k),
            qqQualities(s),
        )
    }

    @Test
    fun no_size_fields_falls_back_to_128k() {
        assertEquals(listOf(Quality.k128k), qqQualities(emptyMap()))
        assertEquals(listOf(Quality.k128k), qqQualities(song(null)))
    }

    @Test
    fun quality_sizes_come_from_size_new() {
        val sizes = qqQualitySizes(song(qingtianFile))
        assertEquals(31168013L, sizes[Quality.flac] ?: -1L)
        assertEquals(10331568L, sizes[Quality.k320k] ?: -1L)
        assertEquals(4317292L, sizes[Quality.k128k] ?: -1L)
        assertNull(sizes[Quality.hires])
    }

    @Test
    fun quality_sizes_skip_zero_slots() {
        val sizes = qqQualitySizes(song(xiaopingguoFile))
        assertNull(sizes[Quality.hires])
        assertNull(sizes[Quality.flac])
        assertEquals(9124554L, sizes[Quality.k320k] ?: -1L)
        assertEquals(3413690L, sizes[Quality.k128k] ?: -1L)
    }
}
