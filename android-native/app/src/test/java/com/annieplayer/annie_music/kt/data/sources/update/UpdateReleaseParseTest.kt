package com.annieplayer.annie_music.kt.data.sources.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateReleaseParseTest {

    private val releases = """
        [
          {
            "tag_name": "v1.3.0",
            "html_url": "https://github.com/owner/repo/releases/tag/v1.3.0",
            "body": "versionCode: 99\n草稿版本不应被选中",
            "draft": true,
            "prerelease": false,
            "assets": []
          },
          {
            "tag_name": "v1.4.0-beta1",
            "html_url": "https://github.com/owner/repo/releases/tag/v1.4.0-beta1",
            "body": "versionCode: 98",
            "draft": false,
            "prerelease": true,
            "assets": []
          },
          {
            "tag_name": "v1.2.0",
            "html_url": "https://github.com/owner/repo/releases/tag/v1.2.0",
            "body": "versionCode: 3\n## 更新内容\n- 稳定版",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-11-10T10:00:00Z",
            "assets": [
              {
                "name": "AnniePlayer-1.2.0-arm64.apk",
                "browser_download_url": "https://github.com/owner/repo/releases/download/v1.2.0/AnniePlayer-1.2.0-arm64.apk"
              }
            ]
          },
          {
            "tag_name": "v1.2.5",
            "html_url": "https://github.com/owner/repo/releases/tag/v1.2.5",
            "body": "",
            "draft": false,
            "prerelease": false,
            "published_at": "2026-11-18T10:00:00Z",
            "assets": [
              {
                "name": "SHA256SUMS.txt",
                "browser_download_url": "https://github.com/owner/repo/releases/download/v1.2.5/SHA256SUMS.txt"
              },
              {
                "name": "AnniePlayer-1.2.5-vc5.APK",
                "browser_download_url": "https://github.com/owner/repo/releases/download/v1.2.5/AnniePlayer-1.2.5-vc5.APK"
              }
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun `按 versionCode 取最大而不是按版本名`() {
        val info = parseLatestRelease(releases)
        assertEquals(5, info?.versionCode)
        assertEquals("1.2.5", info?.version)
    }

    @Test
    fun `versionCode 优先取正文标记而不是资产名`() {
        val single = """
            [
              {
                "tag_name": "v2.0.0",
                "html_url": "https://example.com/v2.0.0",
                "body": "版本号：9\n正文写明优先",
                "draft": false,
                "prerelease": false,
                "assets": [{"name": "app-vc2.apk", "browser_download_url": "https://example.com/app.apk"}]
              }
            ]
        """.trimIndent()
        assertEquals(9, parseLatestRelease(single)?.versionCode)
    }

    @Test
    fun `版本名写错也不影响按 versionCode 选择`() {
        val odd = """
            [
              {"tag_name": "v2.0.0", "html_url": "https://example.com/a", "body": "versionCode: 3", "draft": false, "prerelease": false},
              {"tag_name": "v1.9.0", "html_url": "https://example.com/b", "body": "versionCode: 7", "draft": false, "prerelease": false}
            ]
        """.trimIndent()
        val info = parseLatestRelease(odd)
        assertEquals("1.9.0", info?.version)
        assertEquals(7, info?.versionCode)
    }

    @Test
    fun `空 body 解析成空字符串而不是 null`() {
        assertEquals("", parseLatestRelease(releases)?.notes)
    }

    @Test
    fun `明细页地址取 html_url`() {
        assertEquals(
            "https://github.com/owner/repo/releases/tag/v1.2.5",
            parseLatestRelease(releases)?.pageUrl,
        )
    }

    @Test
    fun `apk 资产按扩展名匹配且忽略大小写`() {
        assertEquals(
            "https://github.com/owner/repo/releases/download/v1.2.5/AnniePlayer-1.2.5-vc5.APK",
            parseLatestRelease(releases)?.apkUrl,
        )
    }

    @Test
    fun `同发的 Release 取主程序 apk 而不是排在前面的 WrapperLite`() {
        val together = """
            [
              {
                "tag_name": "v2.1.0",
                "html_url": "https://example.com/v2.1.0",
                "body": "versionCode: 21",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "WrapperLite-1.0.0-vc9.apk",
                    "browser_download_url": "https://example.com/download/WrapperLite-1.0.0-vc9.apk"
                  },
                  {
                    "name": "AnniePlayer-2.1.0-arm64.apk",
                    "browser_download_url": "https://example.com/download/AnniePlayer-2.1.0-arm64.apk"
                  }
                ]
              }
            ]
        """.trimIndent()
        val info = parseLatestRelease(together)
        assertEquals(
            "https://example.com/download/AnniePlayer-2.1.0-arm64.apk",
            info?.apkUrl,
        )
    }

    @Test
    fun `只有 WrapperLite 时不给 apk 地址也不借它的 versionCode`() {
        val wrapperOnly = """
            [
              {
                "tag_name": "v2.2.0",
                "html_url": "https://example.com/v2.2.0",
                "body": "",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "WrapperLite-1.0.0-vc9.apk",
                    "browser_download_url": "https://example.com/download/WrapperLite-1.0.0-vc9.apk"
                  }
                ]
              }
            ]
        """.trimIndent()
        val info = parseLatestRelease(wrapperOnly)
        assertEquals("2.2.0", info?.version)
        assertNull(info?.apkUrl)
        assertNull(info?.versionCode)
    }

    @Test
    fun `完全没有 versionCode 标记时返回 null 而不是猜`() {
        val none = """
            [
              {
                "tag_name": "v3.0.0",
                "html_url": "https://example.com/v3.0.0",
                "body": "忘了写标记",
                "draft": false,
                "prerelease": false,
                "assets": [{"name": "AnniePlayer-3.0.0.apk", "browser_download_url": "https://example.com/app.apk"}]
              }
            ]
        """.trimIndent()
        val info = parseLatestRelease(none)
        assertEquals("3.0.0", info?.version)
        assertNull(info?.versionCode)
    }

    @Test
    fun `版本名不合规的发布被跳过`() {
        val broken = """
            [
              {"tag_name": "nightly", "html_url": "https://example.com/n", "body": "versionCode: 50", "draft": false, "prerelease": false},
              {"tag_name": "v2.9.0", "html_url": "https://example.com/v2.9.0", "body": "versionCode: 4", "draft": false, "prerelease": false}
            ]
        """.trimIndent()
        assertEquals("2.9.0", parseLatestRelease(broken)?.version)
    }

    @Test
    fun `非法 JSON 返回 null 而不是抛异常`() {
        assertNull(parseLatestRelease("<html>403 rate limit</html>"))
        assertNull(parseLatestRelease(""))
        assertNull(parseLatestRelease("{}"))
        assertNull(parseLatestRelease("[]"))
    }
}
