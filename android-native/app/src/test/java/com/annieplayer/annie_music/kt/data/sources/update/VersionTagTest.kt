package com.annieplayer.annie_music.kt.data.sources.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTagTest {

    @Test
    fun `tag 只用来取展示用的版本名`() {
        assertEquals("1.2.3", versionNameOfTag("v1.2.3"))
        assertEquals("1.2.3", versionNameOfTag("V1.2.3"))
        assertEquals("10.0.1", versionNameOfTag("10.0.1"))
        assertNull(versionNameOfTag("v1.2"))
        assertNull(versionNameOfTag("v1.2.3.4"))
        assertNull(versionNameOfTag("v1.2.3-beta"))
        assertNull(versionNameOfTag("nightly"))
        assertNull(versionNameOfTag(""))
    }

    @Test
    fun `正文里的 versionCode 标记`() {
        assertEquals(7, parseVersionCodeFromNotes("versionCode: 7\n## 更新内容\n- 修了个 bug"))
        assertEquals(7, parseVersionCodeFromNotes("versionCode=7"))
        assertEquals(7, parseVersionCodeFromNotes("Version-Code : 7"))
        assertEquals(7, parseVersionCodeFromNotes("version_code: 7"))
        assertEquals(7, parseVersionCodeFromNotes("版本号：7"))
        assertEquals(42, parseVersionCodeFromNotes("## 更新内容\n\n versionCode: 42 \n- 其他"))
    }

    @Test
    fun `没有标记时返回 null 而不是猜数字`() {
        assertNull(parseVersionCodeFromNotes(""))
        assertNull(parseVersionCodeFromNotes("## 更新内容\n- 只写了描述"))
        assertNull(parseVersionCodeFromNotes("versionCode 7"))
        assertNull(parseVersionCodeFromNotes("versionCode: abc"))
    }

    @Test
    fun `资产名里的 vc 数字作为兜底`() {
        assertEquals(12, parseVersionCodeFromAssets(listOf("AnniePlayer-1.0.1-vc12-arm64.apk")))
        assertEquals(12, parseVersionCodeFromAssets(listOf("AnniePlayer-vc12.apk")))
        assertEquals(3, parseVersionCodeFromAssets(listOf("SHA256SUMS.txt", "app_VC3.apk")))
        assertNull(parseVersionCodeFromAssets(listOf("AnniePlayer-1.0.1-arm64.apk")))
        assertNull(parseVersionCodeFromAssets(emptyList()))
    }

    @Test
    fun `只按 versionCode 判断新旧`() {
        assertTrue(isNewerVersion(2, 1))
        assertFalse(isNewerVersion(1, 1))
        assertFalse(isNewerVersion(0, 1))
    }

    @Test
    fun `versionName 只在无法比较时用于展示排序`() {
        assertTrue(compareVersionNames("1.10.0", "1.9.0") > 0)
        assertTrue(compareVersionNames("2.0.0", "1.99.99") > 0)
        assertEquals(0, compareVersionNames("1.0.0", "1.0.0"))
        assertTrue(compareVersionNames("1.0", "1.0.1") < 0)
    }
}
