package com.annieplayer.wrapperlite.qemu

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 给 wrapper-lite 套一层「加 CORS 头」的反向代理。
 *
 * 为什么需要：`https://amd.wol.moe/` 这类网页工具是 HTTPS 页面，它 fetch
 * `http://127.0.0.1:12340` 属于跨域请求。wrapper-lite 自己既不返回
 * `Access-Control-Allow-Origin`，也不响应 OPTIONS 预检（直接 404），
 * 于是浏览器把响应丢掉 —— 表现为「端口在听但页面没反应」。
 *
 * 结构：
 * ```
 * 浏览器  ->  [CorsProxy 0.0.0.0:12340]  ->  [QEMU hostfwd 127.0.0.1:12341]  ->  guest lite:12340
 * ```
 */
object CorsProxy {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    private const val CORS_HEADERS =
        "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Expose-Headers: *\r\n" +
            "Access-Control-Max-Age: 86400\r\n"

   fun start() {
        stop()
        scope.launch {
            val ss = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", WrapperPaths.PORT))
                }
            }.getOrNull() ?: run {
                WrapperLite.appendLog("[local] CORS 代理端口 ${WrapperPaths.PORT} 绑定失败")
                return@launch
            }
            server = ss
            WrapperLite.appendLog("[local] CORS 代理已监听 0.0.0.0:${WrapperPaths.PORT}")
            while (!ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                launch { runCatching { handle(client) } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 30_000
            val input = c.getInputStream()
            val out = c.getOutputStream()

            val requestLine = readLineCrLf(input) ?: return
            val sp1 = requestLine.indexOf(' ')
            if (sp1 < 0) return
            val method = requestLine.substring(0, sp1)
            val rest = requestLine.substring(sp1 + 1)
            val sp2 = rest.indexOf(' ')
            val path = if (sp2 >= 0) rest.substring(0, sp2) else rest

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLineCrLf(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            // 预检直接在这里答掉
            if (method.equals("OPTIONS", ignoreCase = true)) {
                out.write(
                    ("HTTP/1.1 204 No Content\r\n" + CORS_HEADERS + "Content-Length: 0\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1),
                )
                out.flush()
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val b = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(b, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                b
            } else {
                null
            }

            Socket().use { up ->
                up.connect(InetSocketAddress("127.0.0.1", WrapperPaths.UPSTREAM_PORT), 5_000)
                up.soTimeout = 60_000
                val uo = up.getOutputStream()

                val req = StringBuilder()
                req.append("$method $path HTTP/1.1\r\n")
                req.append("Host: 127.0.0.1:${WrapperPaths.UPSTREAM_PORT}\r\n")
                req.append("Connection: close\r\n")
                req.append("Accept: */*\r\n")
                headers["content-type"]?.let { req.append("Content-Type: $it\r\n") }
                if (contentLength > 0) req.append("Content-Length: $contentLength\r\n")
                req.append("\r\n")
                uo.write(req.toString().toByteArray(Charsets.ISO_8859_1))
                body?.let { uo.write(it) }
                uo.flush()

                val ui = up.getInputStream()
                val statusLine = readLineCrLf(ui) ?: return

                val respHeaderLines = StringBuilder()
                val respHeaderMap = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLineCrLf(ui) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val k = line.substring(0, idx).trim().lowercase()
                        val v = line.substring(idx + 1).trim()
                        respHeaderMap[k] = v
                        // hop-by-hop 头不要透传
                        if (k != "connection" && k != "keep-alive" && k != "transfer-encoding") {
                            respHeaderLines.append(line).append("\r\n")
                        }
                    }
                }

                out.write("$statusLine\r\n".toByteArray(Charsets.ISO_8859_1))
                out.write(respHeaderLines.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(CORS_HEADERS.toByteArray(Charsets.ISO_8859_1))
                out.write("\r\n".toByteArray(Charsets.ISO_8859_1))

                val respLen = respHeaderMap["content-length"]?.toIntOrNull() ?: -1
                if (respLen >= 0) {
                    val b = ByteArray(respLen)
                    var read = 0
                    while (read < respLen) {
                        val n = ui.read(b, read, respLen - read)
                        if (n <= 0) break
                        read += n
                    }
                    out.write(b, 0, read)
                } else {
                    ui.copyTo(out)
                }
                out.flush()
            }
        }
    }

    /** 按 CRLF 读一行，不能用 BufferedReader（会把 body 字节一起吃掉）。 */
    private fun readLineCrLf(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
