package com.annieplayer.wrapperlite.qemu

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 启动并托管 QEMU guest。
 *
 * guest 是 x86_64 的 Android 层（initramfs 里带 `lite` + Apple 私有库），宿主用
 * `-serial stdio` 接管 guest console 的输入输出，因此：
 *
 * - **2FA**：lite 在需要验证码时打印 `2FA code: `（或 `Enter your 2FA code into …`），
 *   然后从 stdin 读一行。guest 的 stdin 是 `/dev/ttyS0`（字符设备，`isatty` 恒真），
 *   所以只要把验证码写进 QEMU 进程的 stdin 就能喂进去。
 * - **登录结果**：以 `login successful` / `login failed` 判定。
 * - 登录成功后 lite 会自己退出（`login complete, exiting`），token 落在 data.img。
 */
class QemuRunner(private val ctx: Context) {

    enum class Kind { LOG, NEED_2FA, LOGIN_OK, LOGIN_FAIL, SERVING, TOKEN_OK, TOKEN_FAIL, EXIT }

    data class Event(val kind: Kind, val line: String)

    private val _events = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    private var process: Process? = null
    private val busy = AtomicBoolean(false)

    /** 上一次 2FA 提示的行内容 / 时间戳，用于压制同一行重复上报（见 [handleLine]） */
    private var last2faLine: String = ""
    private var last2faAt: Long = 0L

    val running: Boolean get() = process?.isAlive == true

    /**
     * 登录模式。
     *
     * 同时把 `--host/--port` 一起传下去：如果 guest 里的 lite 支持「登录完继续 serve」，
     * 那么登录成功后会直接开始监听（省掉一次 20 秒的 QEMU 冷启动）；
     * 如果它不支持，这两个参数会被忽略，行为和只传 `--login` 一致，没有副作用。
     */
    fun login(email: String, password: String, port: Int = WrapperPaths.PORT) {
        launch(
            listOf(
                "--base-dir", "/data",
                "--login", "$email:$password",
                "--host", "0.0.0.0",
                "--port", port.toString(),
            ),
        )
    }

    /** 服务模式：guest 里起 HTTP，端口由 QEMU 转发到宿主。 */
    fun serve(port: Int = WrapperPaths.PORT) {
        launch(
            listOf(
                "--base-dir", "/data",
                "--host", "0.0.0.0",
                "--port", port.toString(),
            ),
        )
    }

    /** 把 6 位验证码送进 guest 的 stdin。 */
    fun send2fa(code: String) {
        val p = process ?: return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        runCatching {
            p.outputStream.write((trimmed + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
        }.onFailure {
            emit(Event(Kind.LOG, "[local] 验证码写入失败：${it.message}"))
        }
    }

    fun stop() {
        val p = process ?: return
        process = null
        busy.set(false)
        runCatching { p.destroy() }
        runCatching {
            // 只等 600ms 给 guest 收尾，超时直接强杀；等太久会把流程拖慢
            if (!p.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.destroyForcibly()
                }
            }
        }
        emit(Event(Kind.EXIT, "[local] 已停止 QEMU"))
    }

    private fun launch(guestArgs: List<String>) {
        stop()

        val qemu = WrapperPaths.qemuBin(ctx)
        if (!qemu.exists()) {
            emit(Event(Kind.LOG, "[local] 找不到 QEMU：${qemu.absolutePath}"))
            return
        }
        qemu.setExecutable(true)

        // guest 参数文件：一行一个参数，init 会 cat 到 /data/.lite-qemu-args
        val argsFile = WrapperPaths.argsFile(ctx)
        argsFile.parentFile?.mkdirs()
        argsFile.writeText(guestArgs.joinToString("\n") + "\n")

        val cmd = buildCommand(qemu, argsFile)
        emit(Event(Kind.LOG, "[local] " + cmd.joinToString(" ")))

        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).apply {
                // QEMU 是 Termux 编的动态可执行文件，依赖 gnutls/glib/pixman/curl 等一整套 so，
                // 它们被解压到 libDir，靠 LD_LIBRARY_PATH 让 linker 找得到。
                environment()["LD_LIBRARY_PATH"] = WrapperPaths.libDir(ctx).absolutePath
            }.start()
        } catch (e: Exception) {
            emit(Event(Kind.LOG, "[local] QEMU 启动失败：${e.message}"))
            return
        }
        process = p
        busy.set(true)

        thread(name = "qemu-console", isDaemon = true) {
            runCatching {
                p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { handleLine(it) }
            }.onFailure {
                emit(Event(Kind.LOG, "[local] 读取 guest 输出中断：${it.message}"))
            }
            val code = runCatching { p.exitValue() }.getOrNull()
            emit(Event(Kind.EXIT, "[local] QEMU 退出（exit=$code）"))
            if (process === p) process = null
            busy.set(false)
        }
    }

    private fun buildCommand(qemu: File, argsFile: File): List<String> = buildList {
        add(qemu.absolutePath)
        add("-L"); add(WrapperPaths.firmwareDir(ctx).absolutePath)
        // MTTCG：多线程翻译执行。配合 -smp 2 让两个 vCPU 并行，
        // 主要收益在 guest 冷启动（加载 Apple 私有库 + 初始化）那一段。
        add("-accel"); add("tcg,thread=multi")
        // guest 里的 lite 是 x86_64 二进制，用到较新指令；默认 qemu64 会 Illegal instruction
        add("-cpu"); add("max")
        add("-m"); add("512")
        // 8 个 vCPU（骁龙 8 Elite 全 8 核）：guest 侧 HTTP 单线程 + SLIRP 单线程，
        // 并发请求靠多核摊平翻译开销。占满全部核心可能发热/掉帧，实测不行再降回 6。
        add("-smp"); add("8")
        add("-kernel"); add(WrapperPaths.kernel(ctx).absolutePath)
        add("-initrd"); add(WrapperPaths.initramfs(ctx).absolutePath)
        add("-append"); add("console=ttyS0 quiet net.ifnames=0 biosdevname=0")
        add("-display"); add("none")
        add("-serial"); add("stdio")
        add("-no-reboot")
        add("-nic")
        // 只绑本机：对外由 CorsProxy 在 WrapperPaths.PORT 上做转发并补 CORS 头
        add(
            "user,model=e1000,hostfwd=tcp:127.0.0.1:${WrapperPaths.UPSTREAM_PORT}" +
                "-:${WrapperPaths.PORT}",
        )
        add("-drive")
        add("file=" + WrapperPaths.dataImg(ctx).absolutePath + ",format=raw,if=virtio")
        // init 脚本按名字 lite_args 读 fw_cfg；不能加 opt/ 前缀，否则它找不到
        add("-fw_cfg"); add("name=lite_args,file=" + argsFile.absolutePath)
    }

    private fun handleLine(line: String) {
        emit(Event(Kind.LOG, line))
        when {
            is2faRequest(line) -> {
                // 同一条提示 2 秒内只报一次：guest 偶尔把同一个 credentialHandler 打两遍，
                // UI 那边就会「确认完又弹一次」。真的第二次挑战（隔了一会儿再来）照常上报。
                val now = System.currentTimeMillis()
                val duplicate = line == last2faLine && now - last2faAt < 2_000L
                if (!duplicate) {
                    last2faLine = line
                    last2faAt = now
                    emit(Event(Kind.NEED_2FA, line))
                }
            }

            line.contains("login successful") -> emit(Event(Kind.LOGIN_OK, line))
            line.contains("login failed") -> emit(Event(Kind.LOGIN_FAIL, line))
            // 登录后如果继续 serve，会打印这一行 → 直接进运行中状态
            line.contains("listening on") -> emit(Event(Kind.SERVING, line))
            // 后台 token 续期结果：失败说明登录态可能已经失效
            line.contains("background token refresh completed") ->
                emit(Event(Kind.TOKEN_OK, line))
            line.contains("background token refresh failed") ||
                line.contains("createMusicToken failed") ->
                emit(Event(Kind.TOKEN_FAIL, line))
        }
    }

    /**
     * guest 要验证码时的输出。lite 一共有三种写法，命中任意一种都算 2FA：
     *
     * - `credentialHandler: {title: , message: , 2FA: true}` —— **实际最先到的就是这行**，
     *   `2FA: false` 那次（登录框）不能算。
     * - `Enter your 2FA code into /data/2fa.txt` —— isatty(0) 为假时走文件轮询。
     * - `2FA code: ` —— tty 交互提示，末尾没有换行，按行读通常读不到，只作兜底。
     */
    private fun is2faRequest(line: String): Boolean {
        val l = line.lowercase()
        return l.contains("2fa code:") ||
            l.contains("enter your 2fa code") ||
            (l.contains("credentialhandler") && l.contains("2fa: true"))
    }

    private fun emit(e: Event) {
        _events.tryEmit(e)
    }
}
