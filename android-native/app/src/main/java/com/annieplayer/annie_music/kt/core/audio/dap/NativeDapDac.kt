package com.annieplayer.annie_music.kt.core.audio.dap

import java.nio.ByteBuffer

/**
 * `libanniedap.so` 的 JNI 面 —— 自己开一条 `AudioTrack` 的最小可用子集。
 *
 * ## 为什么这些方法返回错误码而不是抛异常
 *
 * 底下调的是**非公开 ABI**（`libaudioclient.so` 的 C++ `AudioTrack`）。失败原因分三类，
 * 都必须能原样带上来给候选表判断：
 *
 * | 码 | 含义 | 候选表该怎么做 |
 * |---|---|---|
 * | `0` | 成功 | — |
 * | `-1` / `-2` | 库或符号缺失（这批 ROM 的导出面不同） | **整条路放弃**，不要逐个试 |
 * | `-3` | 老 ABI（Android 7–9），native 侧未实现 | 整条路放弃 |
 * | `-4` | `set()` 返回非 0：HAL 明确拒绝这组参数 | **换下一个候选** |
 * | `-5` | 建流过程中收到致命信号（厂商 HAL 崩了） | **换下一个候选**，且记住这个组合危险 |
 *
 * 抛异常就没法区分"-4 换候选"和"-1 收工"。
 */
internal object NativeDapDac {

    /** 库能不能加载（`loadLibrary` 失败是 UnsatisfiedLinkError，不是异常链上的普通错误） */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("anniedap")
            true
        } catch (e: Throwable) {
            false
        }
    }

    const val OK = 0
    const val ERR_NO_LIB = -1
    const val ERR_NO_SYMBOL = -2
    const val ERR_ABI_UNSUPPORTED = -3
    const val ERR_SET_REJECTED = -4
    const val ERR_SET_CRASHED = -5
    const val ERR_NOT_OPEN = -6
    const val ERR_BUFFER_NOT_DIRECT = -7
    /**
     * `set()` 收下了，但回读的 `getSampleRate()` 与请求不一致。
     *
     * 与 [ERR_SET_REJECTED] 必须分开：那一个是"HAL 明确拒绝"，这一个恰恰是**最危险的一种**
     * ——参数被收下、轨道开始按标称字节率排空、`getPosition` 如实前进，而 DAC 一个采样
     * 都没收到（实测这台 DAP 的 PCM DIRECT 端口就是这样，听感是纯静音而不是噪声）。
     * 没有回读就完全看不见它。
     */
    const val ERR_RATE_MISMATCH = -10

    /**
     * 这台 ROM 的 `~AudioTrack()` **空转**不返回：`dap-teardown` 线程会一直占满一个核
     * （实测 JM21 上两个线程各 100 %，进程 CPU 217 %，整机调度被挤掉）。
     *
     * 反例对照：同一条收尾在 PLQ110 上是**睡着**不返回（线程 `S`、CPU 0 %），那边只泄漏
     * 一个线程、不发热 —— 所以这个标记**只对空转置位**，见 `spawnTeardown` 的 CPU 判据。
     *
     * 一旦观察到，本进程内不再建任何私有 `AudioTrack`：这条路已经证明收不了尾，
     * 再建只会再制造一个永不退出的线程。
     */
    const val ERR_TEARDOWN_STUCK = -11

    /**
     * 收尾线程超时未退出**且一直在跑**（`/proc` 状态 `R`，见 `spawnTeardown`）。
     *
     * 只对空转置位：同一条 `~AudioTrack()` 在 PLQ110 上是睡着不返回（`S`、CPU 0 %），
     * 那边只泄漏一个线程、不发热；JM21 上是空转不返回（`R`、100 %），两个线程就能把
     * 整机调度挤掉（实测进程 217 % CPU、UI p50 从 14 ms 涨到 24 ms）。
     */
    @Volatile
    var teardownStuck: Boolean = false

    /** 首次置位时回调一次；`MoreSettings.init` 用它写进 SharedPreferences。 */
    @Volatile
    var onTeardownStuck: (() -> Unit)? = null

    private const val TeardownWatchMs = 5_000L

    private fun threadStateOf(tid: Int): Char = try {
        val raw = java.io.File("/proc/${android.os.Process.myPid()}/task/$tid/stat").readText()
        val close = raw.lastIndexOf(')')
        if (close >= 0 && close + 2 < raw.length) raw[close + 2] else '?'
    } catch (e: Throwable) {
        '?'
    }

    internal fun spawnTeardown(name: String, body: () -> Unit) {
        val tidBox = java.util.concurrent.atomic.AtomicInteger(0)
        val worker = Thread({
            runCatching { tidBox.set(android.os.Process.myTid()) }
            body()
        }, name).apply {
            isDaemon = true
            start()
        }
        Thread({
            var samples = 0
            var busy = 0
            while (samples < 7 && worker.isAlive) {
                Thread.sleep(TeardownWatchMs / 7)
                samples++
                val tid = tidBox.get()
                if (tid != 0 && threadStateOf(tid) == 'R') busy++
            }
            if (!worker.isAlive || samples < 4) return@Thread
            if (busy * 2 < samples) return@Thread
            val first = !teardownStuck
            teardownStuck = true
            if (first) onTeardownStuck?.invoke()
        }, "dap-teardown-watch").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 最近一次 `open()` 的一句话结论，**给状态行用**。
     *
     * 这台 DAP 的 logcat 不保留 app 日志（`logcat -s annie-dap` 恒空），而"开流为什么失败"
     * 是唯一必须知道的信息 —— 没有它就只能猜。所以让它在数据路径上报出来。
     */
    @Volatile
    var lastOpenNote: String = ""
        internal set
    /**
     * 这次调用**没有在超时内返回** —— 由 Kotlin 侧填写，native 不会返回它。
     *
     * 为什么必须区分：`set()` 在某些 ROM 上会阻塞不返回（实测 PLQ110 上试私有 format 时
     * 出现过整条线程卡住、`syscall=running`、system time 一路涨）。它不是"被拒"，
     * 也不是"崩了"，而是**不可控** —— 换候选没用，但也不能等它。
     */
    const val ERR_TIMEOUT = -9

    external fun nativeStatus(): Int

    /** `set()` 的布局代号：3 = Android 14+（本实现只支持这一种），0 = 没判出来 */
    external fun nativeAbi(): Int

    /** 最近一次 `set()` 原样返回的 `status_t` —— 排错时唯一有信息量的数字 */
    external fun nativeLastSetResult(): Int

    /** `set` / `start` / `getPosition` 三段的返回值，形如 `set=0 start=-38 pos=-38` */
    external fun nativeStageResults(): String

    /** 诊断串：`status / abi / 各符号是否解析到`（给「输出能力（实测）」页用） */
    external fun nativeDescribe(): String

    /** 诊断用：列出 `.dynsym` 里含 [needle] 的 mangled 名（每行一个，最多 [limit] 个） */
    external fun nativeListSymbols(needle: String, limit: Int): String

    external fun nativeOpen(
        sampleRate: Int,
        channelCount: Int,
        channelMask: Int,
        format: Int,
        frameCount: Int,
        flags: Int,
    ): Long

    external fun nativeWrite(handle: Long, buffer: ByteBuffer, size: Int): Int

    external fun nativePlay(handle: Long)

    external fun nativePause(handle: Long)

    external fun nativeFlush(handle: Long)

    external fun nativePositionFrames(handle: Long): Long

    /**
     * 这条轨**实际**跑在什么率上（`AudioTrack::getSampleRate()`）；`0` = 拿不到那个符号。
     *
     * 建流判据的一半：`set()` 返回 0 只说明参数被收下，HAL 照样可以把率吞掉。
     * 参考实现（ReSoNāda 的 `openDirectPcm`）就是拿它当验收：`回读 == 请求 || 回读 == 0`。
     */
    external fun nativeSampleRate(handle: Long): Int

    /**
     * 拆开收尾，逐段调用：`1` = pause、`2` = flush、`3` = stop、`4` = 析构。
     *
     * 拆开的理由：`stop()` 在某些 ROM 上会**不返回**（实测 PLQ110），而"卡在哪一段"
     * 是唯一能定位的线索 —— 合成一个 `nativeClose` 时只能看到"close 卡住"。
     */
    external fun nativeClosePart(handle: Long, part: Int)

    /** 只释放我们自己 `calloc` 的内存，不碰 C++ 对象 */
    external fun nativeFree(handle: Long)
}

/**
 * 错误码区间：只覆盖 **-1024..-1**，不与"堆指针"混淆。
 *
 * `nativeOpen` 成功时返回的是 `calloc` 的堆指针，而 Android 用户态指针常落在
 * `0xb4...(MTE tag)` 这类高位 —— 作为 signed long 就是负数。所以绝不能用
 * `h <= 0` 判失败（实测把成功误判成 `lastError=-757532672`）。
 */
private val ERR_CODE_RANGE = -1024L..-1L

/**
 * 非公开 ABI 的 [DapSink]：阶段 3 用它送私有 `audio_format_t`。
 *
 * 与 [JavaDapSink] 的分工：Java 那条只能送公开 encoding（framework 会拒私有值），
 * 所以"能不能送 DSD"这一项只有它能回答。上层（[DapDirectOutput]）对两者一视同仁。
 */
internal class NativeDapSink : DapSink {

    private var handle = 0L

    /** 收尾阶段回调（`close:1` .. `close:free`）；诊断用，正常播放留空 */
    var onStage: ((String) -> Unit)? = null

    /** 上一次失败的返回码，供候选表决定"换候选"还是"整条路放弃" */
    var lastError: Int = NativeDapDac.OK
        private set

    override val isOpen: Boolean get() = handle != 0L

    override fun open(spec: DapStreamSpec): Boolean {
        close()
        lastError = NativeDapDac.OK
        if (NativeDapDac.teardownStuck) {
            lastError = NativeDapDac.ERR_TEARDOWN_STUCK
            NativeDapDac.lastOpenNote = "该 ROM 析构不返回，直出已停用"
            return false
        }
        if (!NativeDapDac.available) {
            lastError = NativeDapDac.ERR_NO_LIB
            NativeDapDac.lastOpenNote = "loadLibrary 失败"
            return false
        }
        if (spec.sampleRate <= 0 || spec.frameCount <= 0 || spec.channelMask == 0) {
            lastError = NativeDapDac.ERR_NOT_OPEN
            NativeDapDac.lastOpenNote =
                "参数非法 rate=${spec.sampleRate} frames=${spec.frameCount} mask=${spec.channelMask}"
            return false
        }

        val status = try {
            NativeDapDac.nativeStatus()
        } catch (e: Throwable) {
            NativeDapDac.ERR_NO_SYMBOL
        }
        if (status != NativeDapDac.OK) {
            lastError = status
            NativeDapDac.lastOpenNote = "nativeStatus=$status"
            return false
        }

        val h = 0L
        // **flags 阶梯 + 回读校验。**
        //
        // 一条 `set()` 成功**不代表接通了 DAC**：实测这台 DAP 上 `0x1@44100` 与
        // `0x6@176400` 都"set=0、字节按标称率被排空、getPosition 实时前进"，而 DAC 纯静音
        // （HAL 侧连 `fiioprocess` 都没起来）。参考实现不靠这个骗自己：它对每个格式试一组
        // flags（`DAP_PCM_FLAG_LADDER`，其中 `0x2001` 带 `DIRECT_PCM`），每条都回读
        // `getSampleRate()`，不等就拆掉换下一组。
        var note = ""
        for (flags in listOf(spec.outputFlags) + spec.alternateFlags) {
            if (flags == 0) continue
            val opened = try {
                NativeDapDac.nativeOpen(
                    sampleRate = spec.sampleRate,
                    channelCount = spec.channelCount,
                    channelMask = spec.channelMask,
                    format = spec.encoding,
                    frameCount = spec.frameCount,
                    flags = flags,
                )
            } catch (e: Throwable) {
                // 错误码也按 Long 返回：与成功值同类型，否则 `opened` 会被推断成 Number 联合类型
                NativeDapDac.ERR_NO_SYMBOL.toLong()
            }
            // 返回值的编码必须分得清"错误码"与"指针"：
            // 错误码固定是 -1..-1024 里的小负数；成功值是 `calloc` 拿到的**堆指针**，
            // 而 Android 用户态指针常落在 0xb4...(MTE tag) 这类高位，作为 signed long
            // 就是**负数**。以前用 `opened <= 0` 判断，会把成功当成失败（实测
            // `lastError=-757532672` 就是指针低位被 toInt() 截断的样子）。
            if (opened == 0L) {
                lastError = NativeDapDac.ERR_NO_SYMBOL
                NativeDapDac.lastOpenNote = "nativeOpen 返回 0（符号没解析到？）"
                return false
            }
            if (opened in ERR_CODE_RANGE) {
                val code = opened.toInt()
                lastError = code
                note = noteOf(spec, code, flags, 0)
                // 库 / 符号 / ABI 缺失是**整条路**不行，换多少组 flags 都没用
                if (code == NativeDapDac.ERR_NO_LIB || code == NativeDapDac.ERR_NO_SYMBOL ||
                    code == NativeDapDac.ERR_ABI_UNSUPPORTED
                ) {
                    NativeDapDac.lastOpenNote = note
                    return false
                }
                continue
            }
            // 回读校验只对 PCM 做：私有 DSD format 上 `getSampleRate()` 的语义没验证过，
            // 拿它当判据会把唯一能出声的那条路否掉。
            val readback = if (spec.frameCountInBytes) {
                0
            } else {
                runCatching { NativeDapDac.nativeSampleRate(opened) }.getOrDefault(0)
            }
            if (readback != 0 && readback != spec.sampleRate) {
                lastError = NativeDapDac.ERR_RATE_MISMATCH
                note = noteOf(spec, NativeDapDac.ERR_RATE_MISMATCH, flags, readback)
                teardownAsync(opened)
                continue
            }
            handle = opened
            NativeDapDac.lastOpenNote = noteOf(spec, NativeDapDac.OK, flags, readback)
            return true
        }
        NativeDapDac.lastOpenNote = note.ifEmpty {
            "无可用 flags 组合（首组 ${spec.outputFlags}，备选 ${spec.alternateFlags}）"
        }
        return false
    }

    /** 试流失败后拆掉。独立线程：这台 ROM 上 `~AudioTrack()` 可能不返回（见 [close]） */
    private fun teardownAsync(h: Long) {
        NativeDapDac.spawnTeardown("dap-teardown-try") {
            for (part in listOf(2, 3, 4)) {
                runCatching { NativeDapDac.nativeClosePart(h, part) }
            }
            runCatching { NativeDapDac.nativeFree(h) }
        }
    }

    /** 一句话说明这次开流：format / rate / flags / 回读率 / 错误码 / native 三段返回值 */
    private fun noteOf(spec: DapStreamSpec, code: Int, flags: Int, readback: Int): String {
        val stages = runCatching { NativeDapDac.nativeStageResults() }.getOrDefault("")
        val rb = if (readback > 0) " rb=$readback" else ""
        return "fmt=0x${spec.encoding.toString(16)} rate=${spec.sampleRate} " +
            "flags=$flags$rb bpf=${spec.bytesPerFrame} frames=${spec.frameCount} " +
            "rc=$code $stages"
    }

    override fun write(buffer: ByteBuffer): Int {
        if (handle == 0L) return -1
        val size = buffer.remaining()
        val written = try {
            NativeDapDac.nativeWrite(handle, buffer, size)
        } catch (e: Throwable) {
            -1
        }
        if (written > 0) buffer.position(buffer.position() + written)
        return written
    }

    override fun play() {
        if (handle == 0L) return
        try {
            NativeDapDac.nativePlay(handle)
        } catch (e: Throwable) {
        }
    }

    override fun pause() {
        if (handle == 0L) return
        try {
            NativeDapDac.nativePause(handle)
        } catch (e: Throwable) {
        }
    }

    override fun flush() {
        if (handle == 0L) return
        try {
            NativeDapDac.nativeFlush(handle)
        } catch (e: Throwable) {
        }
    }

    override fun playbackFrames(): Long {
        if (handle == 0L) return 0L
        return try {
            NativeDapDac.nativePositionFrames(handle).coerceAtLeast(0L)
        } catch (e: Throwable) {
            0L
        }
    }

    override fun close() {
        if (handle == 0L) return
        val h = handle
        handle = 0L
        // 收尾放到独立线程：**析构会不返回**。
        //
        // 实测 PLQ110：flush ✓、stop ✓，然后 `~AudioTrack()` 卡死（`stalledAt=close:4`）。
        // 同一个 ROM 上 `pause()` 也会卡（见下）。这两处都是 ROM 定制层
        // （`AudioTrackExtImpl` / `oplusAudioFade`）在起作用，不是我们的参数错。
        //
        // 卡住的线程杀不掉，所以唯一的选择是**不让它挡住调用方**。代价：真卡住时
        // 泄漏一个线程 + 一条服务端轨；在正常的 ROM 上它几毫秒就退出了。
        // 播放路径绝不能同步 close —— 那会把 Media3 的渲染线程一起拖死。
        val stages = onStage
        NativeDapDac.spawnTeardown("dap-teardown") {
            for (part in listOf(2, 3, 4)) {
                stages?.invoke("close:$part")
                try {
                    NativeDapDac.nativeClosePart(h, part)
                } catch (e: Throwable) {
                }
            }
            stages?.invoke("close:free")
            try {
                NativeDapDac.nativeFree(h)
            } catch (e: Throwable) {
            }
            stages?.invoke("")
        }
    }
}
