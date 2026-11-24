package com.annieplayer.annie_music.kt.features.library

import com.annieplayer.annie_music.kt.data.local.LocalTrackMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAlbumFooterTextTest {

    @Test
    fun 日期_年月日() {
        assertEquals("2026年11月6日", albumDateText("2026-11-06"))
    }

    @Test
    fun 日期_只有年月() {
        assertEquals("2026年11月", albumDateText("2026-11"))
    }

    @Test
    fun 日期_只有年() {
        assertEquals("2026年", albumDateText("2026"))
    }

    @Test
    fun 日期_空值给空串() {
        assertEquals("", albumDateText(null))
        assertEquals("", albumDateText("  "))
        assertEquals("", albumDateText("不是日期"))
    }

    @Test
    fun 时长_小时分钟() {
        assertEquals("1 小时 4 分钟", albumDurationText(3840))
    }

    @Test
    fun 时长_不足一小时只给分钟() {
        assertEquals("47 分钟", albumDurationText(2820))
    }

    @Test
    fun 时长_不足一分钟给秒() {
        assertEquals("38 秒", albumDurationText(38))
        assertEquals("", albumDurationText(0))
    }

    @Test
    fun 曲目行_首数与总时长() {
        assertEquals("21 首歌曲，1 小时 4 分钟", albumCountDurationText(21, 3840))
        assertEquals("3 首歌曲", albumCountDurationText(3, 0))
        assertEquals("", albumCountDurationText(0, 100))
    }

    @Test
    fun 规格_取最高的那一首() {
        val tracks = listOf(
            meta(codec = "flac", bitDepth = 16, sampleRate = 44100),
            meta(codec = "flac", bitDepth = 24, sampleRate = 96000),
        )
        assertEquals("FLAC · 96kHz / 24bit", albumSpecText(tracks))
    }

    @Test
    fun 规格_没有位深采样率时退回比特率() {
        val tracks = listOf(meta(codec = "mp3", bitDepth = 0, sampleRate = 0, bitrate = 320, lossless = false))
        assertEquals("MP3 · 320 kbps", albumSpecText(tracks))
    }

    @Test
    fun 版权_缺省不加标记原样返回() {
        assertEquals("℗ 2026 VTA Entertainment", albumCopyrightText(listOf(meta(copyright = "2026 VTA Entertainment"))))
    }

    @Test
    fun 版权_已带标记不重复加() {
        assertEquals("℗ 2026 VTA", albumCopyrightText(listOf(meta(copyright = "℗ 2026 VTA"))))
    }

    @Test
    fun 版权_没有就空串() {
        assertEquals("", albumCopyrightText(listOf(meta(copyright = null))))
        assertEquals("", albumCopyrightText(emptyList()))
    }

    private fun meta(
        codec: String = "flac",
        bitDepth: Int = 24,
        sampleRate: Int = 48000,
        bitrate: Int = 0,
        copyright: String? = null,
        lossless: Boolean = true,
    ) = LocalTrackMeta(
        path = "/music/a.flac",
        codec = codec,
        bitDepth = bitDepth,
        sampleRate = sampleRate,
        bitrate = bitrate,
        lossless = lossless,
        copyright = copyright,
    )
}
