package com.annieplayer.annie_music.kt.data.sources.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseAssetsTest {

    @Test
    fun `主程序资产名各种写法都认得`() {
        assertTrue(isMainApkName("AnniePlayer-1.5.0-arm64.apk"))
        assertTrue(isMainApkName("annieplayer-1.5.0.apk"))
        assertTrue(isMainApkName("AnniePlayer_1.5.0.apk"))
        assertTrue(isMainApkName("AnnieMusic-1.5.0.apk"))
        assertTrue(isMainApkName("Annie Music 1.5.0.apk"))
        assertTrue(isMainApkName("app-release.apk"))
        assertTrue(isMainApkName("app-release-unsigned.apk"))
    }

    @Test
    fun `wrapper 资产不算主程序`() {
        assertTrue(isWrapperApkName("WrapperLite-1.0.0.apk"))
        assertTrue(isWrapperApkName("wrapper_lite_1.0.0.apk"))
        assertFalse(isMainApkName("WrapperLite-1.0.0.apk"))
        assertFalse(isMainApkName("wrapper-lite.apk"))
        assertFalse(isMainApkName("AnniePlayer-WrapperLite-1.5.0.apk"))
    }

    @Test
    fun `非 apk 文件不参与选包`() {
        assertFalse(isMainApkName("SHA256SUMS.txt"))
        assertFalse(isWrapperApkName("SHA256SUMS.txt"))
        assertFalse(isMainApkName("AnniePlayer-1.5.0.apk.sha256"))
        assertNull(selectMainApkName(listOf("SHA256SUMS.txt", "AnniePlayer-1.5.0.zip")))
    }

    @Test
    fun `同发时选主程序而不是排在前面的 wrapper`() {
        val names = listOf(
            "WrapperLite-1.0.0.apk",
            "AnniePlayer-1.5.0-arm64.apk",
            "SHA256SUMS.txt",
        )
        assertEquals("AnniePlayer-1.5.0-arm64.apk", selectMainApkName(names))
    }

    @Test
    fun `主程序排在前面也选主程序`() {
        val names = listOf(
            "AnniePlayer-1.5.0-arm64.apk",
            "WrapperLite-1.0.0.apk",
        )
        assertEquals("AnniePlayer-1.5.0-arm64.apk", selectMainApkName(names))
    }

    @Test
    fun `只有 wrapper 时返回 null 而不是硬选`() {
        assertNull(selectMainApkName(listOf("WrapperLite-1.0.0.apk")))
        assertNull(selectMainApkName(listOf("WrapperLite-1.0.0.apk", "SHA256SUMS.txt")))
    }

    @Test
    fun `单个未改名 apk 兜底选中`() {
        assertEquals("app.apk", selectMainApkName(listOf("app.apk", "SHA256SUMS.txt")))
        assertEquals("annie-1.5.0.apk", selectMainApkName(listOf("annie-1.5.0.apk")))
    }

    @Test
    fun `两个候选都无法判定时不猜`() {
        val names = listOf("one.apk", "two.apk")
        assertNull(selectMainApkName(names))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(selectMainApkName(emptyList()))
    }
}
