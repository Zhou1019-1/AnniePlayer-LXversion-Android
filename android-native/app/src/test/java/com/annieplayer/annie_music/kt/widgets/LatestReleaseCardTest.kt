package com.annieplayer.annie_music.kt.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestReleaseCardTest {

    @Test
    fun full_date_is_formatted() {
        assertEquals("2026年10月16日", latestReleaseDateText("2026-10-16"))
    }

    @Test
    fun trailing_time_is_ignored() {
        assertEquals("2026年10月16日", latestReleaseDateText("2026-10-16 00:00:00"))
        assertEquals("2026年10月16日", latestReleaseDateText("2026-10-16T08:30:00Z"))
    }

    @Test
    fun single_digit_month_and_day() {
        assertEquals("2026年3月5日", latestReleaseDateText("2026-3-5"))
    }

    @Test
    fun year_month_only() {
        assertEquals("2026年10月", latestReleaseDateText("2026-10"))
    }

    @Test
    fun year_only() {
        assertEquals("2026年", latestReleaseDateText("2026"))
    }

    @Test
    fun invalid_month_falls_back_to_raw() {
        assertEquals("2026-13-01", latestReleaseDateText("2026-13-01"))
    }

    @Test
    fun blank_returns_empty() {
        assertEquals("", latestReleaseDateText(""))
        assertEquals("", latestReleaseDateText("   "))
    }

    @Test
    fun unknown_shape_returns_trimmed_raw() {
        assertEquals("2026/10/16", latestReleaseDateText("2026/10/16"))
    }

    @Test
    fun title_appends_kind_when_present() {
        assertEquals("Patient Zero - 单曲", latestReleaseTitle("Patient Zero", "单曲"))
        assertEquals("叶惠美 - 专辑", latestReleaseTitle("叶惠美", "专辑"))
    }

    @Test
    fun title_without_kind_is_plain_name() {
        assertEquals("Patient Zero", latestReleaseTitle("Patient Zero", ""))
    }

    @Test
    fun track_count_text() {
        assertEquals("3 首歌曲", latestReleaseTrackCountText(3))
        assertEquals("", latestReleaseTrackCountText(0))
        assertEquals("", latestReleaseTrackCountText(-1))
    }
}
