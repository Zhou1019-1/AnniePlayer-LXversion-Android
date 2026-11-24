package com.annieplayer.annie_music.kt.core.audio.dap

import android.content.Context
import android.os.Build
import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * 测一次「这台机器的内置 DAC 直通到哪一级」，结果落盘复用。
 *
 * ## 为什么必须真的写数据、真的读播放头
 *
 * `set()` 返回 0 只说明**参数被收下了**，不说明会出声。三种假成功都见过：
 * 端口被别的 App 占着（`set()` 成功但没人消费）、HAL 收下私有 format 却按 PCM 解
 * （播放头走但 DAC 没声）、`DIRECT` 端口其实被 mixer 接管。所以判据是三段全过：
 * **建流 → 写入 > 0 字节 → 播放头真的前进**。
 *
 * ## 为什么每个候选跑在自己的线程上
 *
 * 实测踩到过：某个私有 format 的 `set()` **不返回**，整条线程卡在 native 里
 * （`/proc/<tid>/syscall` 是 `running`，system time 一路涨）。那种线程杀不掉
 * —— `pthread_cancel` 对卡在 binder / HAL 里的代码无效，`siglongjmp` 也回不来。
 *
 * 所以：一个候选一个线程 + [CANDIDATE_TIMEOUT_MS] 硬上限，超时就记
 * [NativeDapDac.ERR_TIMEOUT]、放弃那条线程、继续下一个。**放弃线程是有代价的**
 * （它可能还握着 libaudioclient 的锁，后面的候选会跟着超时），但这比整次探测死掉强，
 * 也比"假装它不支持"诚实 —— 日志里能看出它是"不可控"而不是"被拒"。
 *
 * ## 为什么逐条落盘
 *
 * 超时之后进程可能已经被那条僵尸线程搅坏了。每测完一条就写一次文件，
 * 下一次启动至少知道停在哪个候选、前几条是什么结论。
 *
 * ## 代价与纪律
 *
 * - **一次**：结果按 [fingerprint] 落盘，命中就直接用，不再开流；
 * - **不在主线程**：`TRANSFER_SYNC` 下 `write` 会阻塞，挂主线程就是 ANR（踩过）；
 * - **写静音图案而不是 0**：见 [dapSilencePattern]。真机上写全 0 是一段直流，会响；
 * - 探测会短暂占用输出端口。放在**还没有开始播**的时刻跑，就不会打断任何东西。
 */
internal object DapCapabilityProbe {

    private const val FILE_NAME = "dap-capability.txt"

    /** 一次写多少帧。够让 HAL 有事可做，又不会把缓冲塞到触发阻塞 */
    private const val WRITE_FRAMES = DAP_PROBE_WRITE_FRAMES

    /** 写不进（`WOULD_BLOCK`）时每次等多久 */
    private const val WRITE_RETRY_MS = 5L

    /** 观察"这条流会不会持续消费"的窗口。要够长才能和"只吃一口"区分开（见 `played`） */
    private const val DRAIN_WINDOW_MS = 900L

    /** 连续多少回合写不进就判"服务端不消费"（每回合间隔 [WRITE_RETRY_MS]） */
    private const val MAX_IDLE_ROUNDS = 40

    /** 单个候选的硬上限。正常候选 < 1 s，2.5 s 只会被真卡住的那种用满 */
    private const val CANDIDATE_TIMEOUT_MS = 2_500L

    @Volatile
    private var cached: DapCapability? = null

    @Volatile
    private var kicked = false

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dap-capability-probe").apply { isDaemon = true }
    }

    /** 缓存指纹：换机型 / 换 ROM / 换 SDK 都作废旧结论 */
    fun fingerprint(): String = dapCapabilityFingerprint(
        Build.FINGERPRINT,
        Build.MODEL,
        Build.VERSION.SDK_INT,
    )

    /** 内存里的结论；没有就是 `null`（**不是**"不支持"） */
    val current: DapCapability? get() = cached

    /**
     * 取结论：缓存 → 文件 → 真测。
     *
     * 会开流，**只能在后台线程调**。
     */
    @Synchronized
    fun ensure(context: Context): DapCapability {
        val fp = fingerprint()
        cached?.let { if (it.fingerprint == fp) return it }
        val file = File(context.filesDir, FILE_NAME)
        readFile(file, fp)?.let {
            cached = it
            return it
        }
        val fresh = probe(DsdLevel.dsd64, context) { partial ->
            cached = partial
            runCatching { file.writeText(partial.encode()) }
        }
        cached = fresh
        runCatching { file.writeText(fresh.encode()) }
        return fresh
    }

    /** 后台跑一次。重复调用只有第一次真的干活 */
    fun ensureAsync(context: Context) {
        if (kicked) return
        kicked = true
        val app = context.applicationContext
        executor.execute { runCatching { ensure(app) } }
    }

    /** 「重新探测」：清内存与文件，下一次 [ensure] 会重跑 */
    @Synchronized
    fun reset(context: Context) {
        cached = null
        kicked = false
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    /**
     * 真测。**不读缓存**、**不落盘** —— 那两件事由 [ensure] 管，这里只在每测完一条时
     * 通过 [onProgress] 报一次。
     */
    fun probe(
        level: DsdLevel,
        context: Context? = null,
        onProgress: (DapCapability) -> Unit = {},
    ): DapCapability {
        val fp = fingerprint()
        if (!NativeDapDac.available) {
            return DapCapability(fp, NativeDapDac.ERR_NO_LIB, 0, level, emptyList())
        }
        val status = runCatching { NativeDapDac.nativeStatus() }.getOrDefault(NativeDapDac.ERR_NO_LIB)
        val abi = if (status == NativeDapDac.OK) {
            runCatching { NativeDapDac.nativeAbi() }.getOrDefault(0)
        } else {
            0
        }
        if (status != NativeDapDac.OK) {
            return DapCapability(fp, status, abi, level, emptyList())
        }

        val env = environment()
        val verdicts = ArrayList<DapFormatVerdict>(12)
        val vendorNotes = ArrayList<String>(2)
        fun record(v: DapFormatVerdict) {
            verdicts += v
            onProgress(
                DapCapability(fp, status, abi, level, verdicts.toList(), vendorNotes.joinToString("; ")),
            )
        }

        // ① 通路自检：公开 PCM 16 bit、不带 flag。这一条失败说明与 DSD 无关的东西坏了
        //    （attribution、端口、ABI），那时后面每一条都会失败，但原因不在这里。
        record(
            guarded(
                tag = "sanity/pcm16",
                format = AUDIO_FORMAT_PCM_16_BIT,
                sampleRate = 48_000,
                outputFlags = 0,
                bytesPerSample = 2,
                silence = 0,
            ),
        )

        // ② 厂商模式开关。飞傲的 HAL 要先把 `format` / `sampling_rate` 拨到 DSD，
        //    `set()` 才可能落在那条私有端口上；不下发就只能撞到 BAD_VALUE。
        val enterNote = DapVendorTuning.enterDsd(context, env.vendor, level.bitRate)
        if (enterNote.isNotEmpty()) vendorNotes += "enter: $enterNote"

        // ③ DSD 候选，按反编译出来的顺序逐个试
        for (c in dapDsdCandidates(
            level = level,
            vendor = env.vendor,
            isSmartAudioFamily = env.smartAudio,
            hasFpgaAudioNode = env.fpga,
            sdkInt = env.sdkInt,
        )) {
            record(
                guarded(
                    tag = c.tag,
                    format = c.format,
                    sampleRate = c.sampleRate,
                    outputFlags = c.outputFlags,
                    bytesPerSample = c.container.bytesPerSample,
                    silence = dapSilencePattern(c.format).toInt(),
                ),
            )
        }

        // ④ **必须**复位：不复位的话下一次普通 PCM 播放会带着 DSD 的 format 进 HAL
        val resetNote = DapVendorTuning.reset(context, env.vendor, 48_000)
        if (resetNote.isNotEmpty()) vendorNotes += "reset: $resetNote"

        return DapCapability(
            fp, status, abi, level, verdicts.toList(), vendorNotes.joinToString("; "),
        )
    }

    // ── 内部 ──

    private class Env(
        val vendor: DapVendor,
        val smartAudio: Boolean,
        val fpga: Boolean,
        val sdkInt: Int,
    )

    private fun environment(): Env {
        val fileExists: (String) -> Boolean = { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }
        val manufacturer = Build.MANUFACTURER
        val brand = Build.BRAND
        val model = Build.MODEL
        return Env(
            vendor = detectDapVendor(
                manufacturer = manufacturer,
                brand = brand,
                model = model,
                product = Build.PRODUCT,
                fileExists = fileExists,
                device = Build.DEVICE,
            ),
            smartAudio = isSmartAudioFamily(manufacturer, brand, model, fileExists),
            fpga = fileExists(FPGA_AUDIO_NODE),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * 一个候选的**进行中**状态。
     *
     * 它存在的唯一理由：超时那一刻 worker 线程还在 native 里，它已经拿到的结论
     * （`open` 成功没有、写进去多少、播放头到哪）不能跟着线程一起丢掉 ——
     * "卡在 close"和"卡在 play"是两件完全不同的事，而超时本身分不出来。
     */
    private class Attempt(
        val tag: String,
        val format: Int,
        val sampleRate: Int,
        val outputFlags: Int,
        val bytesPerFrame: Int,
    ) {
        @Volatile var stage: String = "open"
        @Volatile var openResult: Int = NativeDapDac.OK
        @Volatile var setStatus: Int = 0
        @Volatile var writeBytes: Int = -1
        @Volatile var framesBefore: Long = 0L
        @Volatile var framesAfter: Long = 0L
        @Volatile var notes: String = ""

        fun verdict(stalledAt: String = ""): DapFormatVerdict = DapFormatVerdict(
            tag = tag,
            format = format,
            sampleRate = sampleRate,
            outputFlags = outputFlags,
            bytesPerFrame = bytesPerFrame,
            // 卡在 open 里的话连 open 的返回值都没有，只能报超时；
            // 卡在它之后，open 的真实结果仍然有效 —— 那是有用信息，别覆盖掉。
            openResult = if (stalledAt.isNotEmpty() && openResult == NativeDapDac.OK &&
                stage == "open"
            ) {
                NativeDapDac.ERR_TIMEOUT
            } else {
                openResult
            },
            setStatus = setStatus,
            writeBytes = writeBytes,
            framesBefore = framesBefore,
            framesAfter = framesAfter,
            stalledAt = stalledAt,
            notes = notes,
        )
    }

    /**
     * 在一个**可放弃**的线程上试一个候选。
     *
     * 超时后线程不 join 回来，也不试图杀它（杀不掉）。它的句柄与对象就留在那里，
     * 进程退出时一起回收 —— 泄漏一个 AudioTrack 比脏内存便宜。
     */
    private fun guarded(
        tag: String,
        format: Int,
        sampleRate: Int,
        outputFlags: Int,
        bytesPerSample: Int,
        silence: Int,
    ): DapFormatVerdict {
        val attempt = Attempt(tag, format, sampleRate, outputFlags, bytesPerSample * 2)
        val done = AtomicReference<DapFormatVerdict?>()
        val worker = Thread({
            runCatching { probeOne(attempt, silence) }
            done.set(attempt.verdict())
        }, "dap-probe-$tag")
        worker.isDaemon = true
        worker.start()
        worker.join(CANDIDATE_TIMEOUT_MS)
        return done.get() ?: attempt.verdict(stalledAt = attempt.stage)
    }

    private fun probeOne(attempt: Attempt, silence: Int) {
        val sink = NativeDapSink()
        // 收尾被拆成四段，段名直接落进 `stalledAt`：卡在 `close:3` 就是 `stop()` 不返回
        sink.onStage = { attempt.stage = it }
        val spec = DapStreamSpec(
            sampleRate = attempt.sampleRate,
            channelCount = 2,
            channelMask = AUDIO_CHANNEL_OUT_STEREO,
            encoding = attempt.format,
            bytesPerFrame = attempt.bytesPerFrame,
            // 与生产路径同一个换算（framework 按 1 字节一帧）：单位错了会让"能写进多少"
            // 整体差 8 倍，判据跟着失真
            frameCount = dapFrameCountInWireFrames(attempt.sampleRate, attempt.bytesPerFrame),
            outputFlags = attempt.outputFlags,
        )
        val opened = runCatching { sink.open(spec) }.getOrDefault(false)
        if (!opened) {
            attempt.openResult = sink.lastError
            attempt.setStatus = runCatching { NativeDapDac.nativeLastSetResult() }.getOrDefault(0)
            return
        }
        try {
            // 先 play 再 write：TRANSFER_SYNC 下反了会阻塞（踩过）
            attempt.stage = "play"
            sink.play()
            attempt.notes = stageResults()
            // `framesBefore` 必须是**起播之后、写之前**的读数。
            // 以前硬编码 0，于是 `framesAdvanced` 等于绝对读数；一旦 `getPosition` 返回 0
            // 就判"位置不可用"，把本来可用的硬件时钟扔掉（实测踩到：playbackFrames 在真实
            // 播放里是按实时走的）。
            attempt.framesBefore = runCatching { sink.playbackFrames() }.getOrDefault(0L)
            attempt.stage = "write"
            val bytes = attempt.bytesPerFrame * WRITE_FRAMES
            val buffer = ByteBuffer.allocateDirect(bytes)
            repeat(bytes) { buffer.put(silence.toByte()) }
            buffer.flip()

            // 判据不是"写进去过一次"，而是**这条流会不会持续消费**：
            // 服务端不消费时第一次写也能成功（填进共享环），之后全是 `WOULD_BLOCK`。
            // 所以在 DRAIN_WINDOW_MS 里反复写、统计空转次数 —— 这才是"通路活着"的证据。
            //
            // ⚠️ **不许在"播放头前进"时提前跳出**。曾经有一句
            // `if (framesAfter > framesBefore) break`，于是"只吃了 15 ms"也立刻判定通过
            // （JM21 实测：88200 那条只吃 0.07 秒就停，播放时 buffer 填满、进度钉在 0.00）。
            // 现在只有两种收尾：窗口走完，或连续 MAX_IDLE_ROUNDS 次写不进。
            var totalWritten = 0
            var idleRounds = 0
            var okRounds = 0
            val startedAt = System.nanoTime()
            while ((System.nanoTime() - startedAt) / 1_000_000L < DRAIN_WINDOW_MS) {
                buffer.clear()
                val wrote = sink.write(buffer)
                if (wrote > 0) {
                    totalWritten += wrote
                    okRounds++
                    attempt.writeBytes = totalWritten
                    idleRounds = 0
                } else {
                    idleRounds++
                    Thread.sleep(WRITE_RETRY_MS)
                }
                if (idleRounds > MAX_IDLE_ROUNDS) break
                attempt.framesAfter =
                    runCatching { sink.playbackFrames() }.getOrDefault(attempt.framesBefore)
            }

            attempt.stage = "position"
            attempt.framesAfter = runCatching { sink.playbackFrames() }.getOrDefault(0L)
            // `writes` 与 `pos` 要一起看：`writes>1` 但 `pos=0` 是"环在消费但播放头不报"，
            // `writes=1` 且 `pos=0` 是"填了一次就满了，根本没人取" —— 两种情况修法完全不同。
            attempt.notes = "${stageResults()} writes=$okRounds idle=$idleRounds"
        } finally {
            runCatching { sink.close() }
        }
    }

    private fun stageResults(): String =
        runCatching { NativeDapDac.nativeStageResults() }.getOrDefault("")

    private fun readFile(file: File, fingerprint: String): DapCapability? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val parsed = DapCapability.decode(text) ?: return null
        return if (parsed.fingerprint == fingerprint) parsed else null
    }
}

/**
 * 缓存键。**必须含 ROM 指纹**：同一台机器刷了别的固件，厂商 HAL 的 DSD 端口可能整个不见，
 * 旧结论会变成"明明测通了却不出声"。
 */
internal fun dapCapabilityFingerprint(buildFingerprint: String, model: String, sdkInt: Int): String =
    "$buildFingerprint|$model|$sdkInt"
