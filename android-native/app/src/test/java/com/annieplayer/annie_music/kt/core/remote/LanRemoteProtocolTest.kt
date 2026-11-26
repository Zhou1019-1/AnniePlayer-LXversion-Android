package com.annieplayer.annie_music.kt.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanRemoteProtocolTest {

    @Test
    fun `全角配对码归一为半角`() {
        assertEquals("7403", normalizePairCode("７４０３"))
        assertEquals("7403", normalizePairCode(" 7 4 0 3 "))
        assertEquals("7403", normalizePairCode("7-4-0-3"))
        assertEquals("", normalizePairCode("abcd"))
    }

    @Test
    fun `地址补全默认端口`() {
        assertEquals("http://192.168.1.5:45823", remoteBaseUrl("192.168.1.5"))
        assertEquals("http://192.168.1.5:45823", remoteBaseUrl("192.168.1.5:45823"))
        assertEquals("http://192.168.1.5:5000", remoteBaseUrl("192.168.1.5:5000"))
        assertEquals("http://192.168.1.5:45823", remoteBaseUrl("http://192.168.1.5/"))
        assertEquals("http://192.168.1.5:45823", remoteBaseUrl("  https://192.168.1.5:45823  "))
        assertEquals(null, remoteBaseUrl("   "))
        assertEquals(null, remoteBaseUrl("192.168 .1.5"))
    }

    @Test
    fun `音量与进度钳制`() {
        assertEquals(1.0, clampRemoteVolume(1.7), 0.0001)
        assertEquals(0.0, clampRemoteVolume(-0.5), 0.0001)
        assertEquals(1.0, clampRemoteVolume(Double.NaN), 0.0001)
        assertEquals(0.4, clampRemoteVolume(0.4), 0.0001)

        assertEquals(0.0, clampRemoteSeek(-3.0, 200.0), 0.0001)
        assertEquals(200.0, clampRemoteSeek(999.0, 200.0), 0.0001)
        assertEquals(12.5, clampRemoteSeek(12.5, 0.0), 0.0001)
        assertEquals(0.0, clampRemoteSeek(Double.NaN, 200.0), 0.0001)
    }

    @Test
    fun `配对响应三分支`() {
        val ok = parsePairBody(200, """{"ok":true,"token":"abc123"}""")
        assertTrue(ok is PairOutcome.Ok)
        assertEquals("abc123", (ok as PairOutcome.Ok).token)

        assertTrue(parsePairBody(403, """{"ok":false}""") is PairOutcome.Rejected)
        assertTrue(parsePairBody(200, """{"ok":true}""") is PairOutcome.Rejected)
        assertTrue(parsePairBody(200, "not-json") is PairOutcome.Rejected)

        val locked = parsePairBody(429, """{"ok":false,"retryAfter":12}""")
        assertTrue(locked is PairOutcome.Locked)
        assertEquals(12, (locked as PairOutcome.Locked).retryAfterSeconds)

        val lockedDefault = parsePairBody(429, "")
        assertEquals(30, (lockedDefault as PairOutcome.Locked).retryAfterSeconds)
    }

    @Test
    fun `状态快照解析`() {
        val body = """
            {
              "playing": true,
              "title": "夜曲",
              "artist": "周杰伦",
              "album": "十一月的萧邦",
              "cover": "data:image/jpeg;base64,AAAA",
              "duration": 226.5,
              "position": 61.2,
              "volume": 0.42,
              "mode": {"id":"shuffle","icon":"🔀","label":"随机"},
              "lyric": {"cur": 3, "lines":[{"t":1000,"txt":"一行","tly":"one"},{"t":2000,"txt":"二行","tly":""}]}
            }
        """.trimIndent()
        val now = parseRemoteState(body)
        assertTrue(now.playing)
        assertEquals("夜曲", now.title)
        assertEquals("周杰伦", now.artist)
        assertEquals(226.5, now.duration, 0.0001)
        assertEquals(61.2, now.position, 0.0001)
        assertEquals(0.42, now.volume, 0.0001)
        assertEquals("shuffle", now.mode?.id)
        assertEquals(3, now.lyric.current)
        assertEquals(2, now.lyric.lines.size)
        assertEquals("一行", now.lyric.lines[0].text)
        assertEquals("one", now.lyric.lines[0].translation)
        assertEquals(2000, now.lyric.lines[1].timeMs)
    }

    @Test
    fun `状态快照缺字段走默认`() {
        val empty = parseRemoteState("{}")
        assertEquals("", empty.title)
        assertEquals(1.0, empty.volume, 0.0001)
        assertEquals(-1, empty.lyric.current)
        assertTrue(empty.lyric.lines.isEmpty())
        assertEquals(null, empty.mode)

        val broken = parseRemoteState("<<<")
        assertTrue(!broken.playing)
        assertEquals("", broken.title)
    }

    @Test
    fun `曲库分页解析`() {
        val page = parseRemoteLibrary(
            """{"total":2,"items":[{"p":"/a/1.flac","t":"一","ar":"A","al":"X"},{"p":"/a/2.flac","t":"二","ar":"B","al":"Y"}]}""",
        )
        assertEquals(2, page.total)
        assertEquals(2, page.items.size)
        assertEquals("/a/1.flac", page.items[0].path)
        assertEquals("X", page.items[0].album)

        val empty = parseRemoteLibrary("{}")
        assertEquals(0, empty.total)
        assertTrue(empty.items.isEmpty())
    }

    @Test
    fun `SSE 分帧保留半帧`() {
        val buffer = "retry: 2000\n\nevent: state\ndata: {\"title\":\"A\"}\n\nevent: state\ndata: {\"title\":\"B\"}\n\nevent: state\ndata: {\"ti"
        val parsed = parseSseChunk(buffer)
        assertEquals(2, parsed.events.size)
        assertEquals("""{"title":"A"}""", parsed.events[0].data)
        assertEquals("state", parsed.events[1].event)
        assertEquals("""{"title":"B"}""", parsed.events[1].data)
        assertTrue(parsed.rest.startsWith("event: state"))
        assertEquals("""{"ti""", parsed.rest.substringAfter("data: "))
    }

    @Test
    fun `SSE 多行 data 合并`() {
        val block = "event: state\ndata: line1\ndata: line2"
        val event = parseSseBlock(block)
        assertEquals("state", event.event)
        assertEquals("line1\nline2", event.data)
    }

    @Test
    fun `SSE 注释与十进制造默认事件`() {
        val event = parseSseBlock(": hello\nretry: 3000")
        assertEquals("message", event.event)
        assertEquals("", event.data)
        assertTrue(parseSseChunk(": keepalive\n\n").events.isEmpty())
    }

    @Test
    fun `网段主机列表排除 0 与 255`() {
        val hosts = subnetHosts("192.168.3")
        assertEquals(254, hosts.size)
        assertEquals("192.168.3.1", hosts.first())
        assertEquals("192.168.3.254", hosts.last())
        assertTrue(hosts.none { it.endsWith(".0") || it.endsWith(".255") })
        assertEquals("10.0.0.9", subnetHosts("10.0.0")[8])
    }

    @Test
    fun `只认安妮遥控的 401 响应`() {
        assertTrue(remoteServerSignature(401, """{"ok":false,"error":"unauthorized"}"""))
        assertTrue(!remoteServerSignature(401, """{"ok":false}"""))
        assertTrue(!remoteServerSignature(200, """{"ok":false,"error":"unauthorized"}"""))
        assertTrue(!remoteServerSignature(404, ""))
        assertTrue(!remoteServerSignature(401, "<html>401</html>"))
    }

    @Test
    fun `本机网段形如三段点分`() {
        val subnet = localSubnet()
        if (subnet != null) {
            assertEquals(3, subnet.prefix.split('.').size)
            assertTrue(subnet.selfAddress.startsWith(subnet.prefix + "."))
            assertTrue(!subnet.selfAddress.startsWith("169.254."))
        }
    }

    @Test
    fun `播放模式按桌面端顺序循环`() {
        assertEquals("list-seq", nextPlayMode("all-seq").id)
        assertEquals("all-rand", nextPlayMode("list-seq").id)
        assertEquals("list-rand", nextPlayMode("all-rand").id)
        assertEquals("repeat-one", nextPlayMode("list-rand").id)
        assertEquals("all-seq", nextPlayMode("repeat-one").id)
        assertEquals("all-seq", nextPlayMode("").id)
        assertEquals("all-seq", nextPlayMode("不认识的模式").id)
        assertEquals("顺序", nextPlayMode("repeat-one").label)
    }

    @Test
    fun `模式文案优先本地映射`() {
        assertEquals("单曲", playModeLabel("repeat-one", "单曲循环"))
        assertEquals("列表随机", playModeLabel("list-rand", ""))
        assertEquals("自定义", playModeLabel("weird", "自定义"))
        assertEquals("顺序", playModeLabel("", ""))
    }
}
