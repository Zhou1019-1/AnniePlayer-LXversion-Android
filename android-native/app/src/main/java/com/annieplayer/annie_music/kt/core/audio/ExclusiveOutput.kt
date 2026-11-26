package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeOboeStream
import com.annieplayer.annie_music.kt.core.audio.dsp.NativeUacStream
import com.annieplayer.annie_music.kt.core.diagnostics.RuntimeLog
import java.nio.ByteBuffer

/** [ExclusiveOutput.configure] 的结果 —— 决定上层还能不能退回系统通路 */
internal enum class ExclusiveOpenResult {
    /** 独占流成立（复用 / 重建 / 新开都算） */
    Opened,

    /** 没成立，但这一首**允许**退回系统混音（普通 PCM） */
    FallbackAllowed,

    /**
     * 没成立而且**不许退**：这一首送的是 DoP / raw DSD 帧。
     *
     * 系统通路里那条 24bit → float 的处理器会把 DoP 的 0x05 / 0xFA 标记除成 ~1e-7 的直流，
     * DAC 再也认不出合法帧序列。硬送出去等于装作在播，所以必须显式判失败，
     * 由上层记进失败记忆并按 PCM 重建。
     */
    ForceFail,
}

/**
 * **独占通路**（USB 直连 libusb / AAudio EXCLUSIVE）作为一条 [OutputPort]。
 *
 * 内核全在 native（`native-uac.cpp` / `native-oboe.cpp`）；这一层只做编排：
 * 选引擎 → 开流 → 把 PCM 拷进环形缓冲 → 用 `framesRead` 推时间轴。
 *
 * ## 三个不能抄近路的地方
 *
 * 1. **一次 configure 的意图是一个值**（[request]），不许中途回读上游可变状态 ——
 *    两次读之间翻面会让同一首歌的两半走不同的 alt（PCM 进 raw alt = 没声）。
 * 2. **`play()` 靠不住**：它只挂在 BUFFERING→READY 迁移上，而"播放中切模式"没有那次迁移。
 *    所以每次重建之后一律走 [applyPlayIntentToEngine]（sink 意图 **或** 播放器意图）。
 * 3. **失败只报一次**：每个 buffer 都报会重建几百次音源。
 */
@OptIn(UnstableApi::class)
internal class ExclusiveOutput(
    /** 现在该不该试独占（透明模式才试） */
    private val exclusiveWanted: () -> Boolean = { true },
    /** 独占中途失败时通知上游（重建音源 → 下次 configure 选中系统引擎） */
    private val onEngineFailure: (String, String) -> Unit = { _, _ -> },
    /** 这一首选哪个直出内核；null 也退到 AAudio */
    private val engineSelector: () -> PcmEngine? = { OboeEngine },
    /** 已经开着的那个引擎现在还算不算数（通路没被切走） */
    private val engineStillValid: () -> Boolean = { false },
    /** 实际生效通路 / 降级原因上报 */
    private val onRouting: (SinkKind, DowngradeNotice?, Int) -> Unit = { _, _, _ -> },
    /** USB 走不成的原因；由宿主按当前设备状态回答（本类拿不到 Context） */
    private val usbUnavailable: () -> DowngradeReason = { DowngradeReason.UsbOpenFailed },
    private val usbExclusiveIntent: () -> Boolean = { false },
    /** 这一次 configure 要什么（见 [OutputRequest]） */
    private val request: () -> OutputRequest = { OutputRequest.Conservative },
    private val outputDeviceId: () -> Int = { 0 },
    /** 独占通路的线性增益（`ExclusiveVolume`） */
    private val exclusiveGain: () -> Float = { 1f },
    /** 上游此刻想不想播（`Player.playWhenReady`） */
    private val playIntent: () -> Boolean = { true },
    private val playIntentAt: () -> Long = { 0L },
    /** sink 的 listener：上报"位置不连续 / 开始推进" */
    private val listenerProvider: () -> AudioSink.Listener? = { null },
) : OutputPort {

    override val path: SinkKind
        get() = if (engine === OboeEngine) SinkKind.oboe else SinkKind.usbUac

    // ── 流与规格 ──
    private var engine: PcmEngine = OboeEngine

    @Volatile private var handle: Long = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var pcmDepth = 32
    private var pcmEncoding = C.ENCODING_PCM_16BIT
    private var spec: WireSpec? = null

    private val ioLock = java.util.concurrent.locks.ReentrantLock()

    @Volatile private var released = false

    private var primePending = false

    @Volatile private var lastPositionUs = C.TIME_UNSET

    private inline fun <T> locked(block: () -> T): T {
        ioLock.lock()
        try {
            return block()
        } finally {
            ioLock.unlock()
        }
    }

    /** 这一首**源**位深（`PcmAudioSink` 在处理器链之前读出）；只在开流时用来挑 alt */
    var wireBitsHint: Int = 0

    /** 端点上真正跑的位深（USB = alt 的 subslot，AAudio = HAL 回读格式） */
    var endpointDepth: Int = 0
        private set

    /** 这条流建在 raw DSD alt 上（只有 Native）。raw 与 PCM 的 alt 互斥，复用必须比对 */
    private var streamRaw = false

    /** 这条流上跑的不是 PCM 样本（DoP / Native）—— configure 时定死，增益与 DSP 的唯一判据 */
    @Volatile private var streamRawFrames = false

    /** 开流时的传输标签（`方式@档位`）；失败按它回报，不能现读全局量 */
    @Volatile private var openedTransportTag: String = ""

    // ── 播放意图 ──
    @Volatile private var playRequested = false
    private var stalePauseBuffers = 0
    private var deadSinceMs = 0L
    @Volatile private var pushedGain = 1f

    // ── 时间轴与记账 ──
    private var failureReported = false
    private var lastStatsMs = 0L
    /** 水位记账（写入/已播/在飞）。所有复位都走它的 [UacRing.rebase] */
    private val ring = UacRing()
    /** 时间轴状态机（零点/第一块/位置/结束）。所有复位都走它的 [UacClock.reset] */
    private val clock = UacClock()
    private var lastReadFrames = 0L
    private var lastConsumeMs = 0L
    private var lastWriteMs = 0L
    private var starveCount = 0L

    private var rateWinFrames = -1L
    private var rateWinAtMs = 0L
    private var rateProbed = false
    @Volatile internal var measuredRateHz = 0

    /** 时间轴不连续：下一次写入时把基点重设到那一块（数据照播，不清环） */
    private var timeBaseNeedsSync = false

    private var playbackThreadBoosted = false

    /** 上一次上报的通路 / 降级原因（只在变化时推，避免每首歌都推一次状态） */
    private var reportedPath: SinkKind? = null
    private var reportedNotice: DowngradeNotice? = null

    private var boundDeviceId = 0
    private var wireUnknown = 0

    // ── 对外 ──

    /** 上游是否想播（sink 自己的 `playRequested`，全局只有这一份） */
    val wantsPlay: Boolean get() = playRequested

    /**
     * **还有多少微秒的音频没播出去**（环里 + 在飞之外的部分）。
     *
     * 只给界面用：换曲时 `configure()` 会先把上一首的尾巴播完（约 2 s），这段时间播放线程被占住，
     * `ExoPlayer.getCurrentPosition()` 不刷新。进度侧只要这一个数就能判断"已经进到尾段"，
     * 不必去猜 PTS 与 offset 的换算（那条路依赖 Media3 两个不同步的值，反复出错）。
     */
    fun pendingUs(): Long {
        if (handle == 0L) return 0L
        return framesToUs(ring.pendingFrames(playedFramesNow()))
    }

    /** 当前这条流是否还活着；`false` = 只有拆流重建能救（`dead` 只在 startStream 清） */
    override fun isHealthy(): Boolean = locked {
        handle == 0L || engine.isHealthy(handle)
    }

    /**
     * 打开 / 复用 / 重建这条独占流。
     *
     * @return 见 [ExclusiveOpenResult]；只有 [ExclusiveOpenResult.Opened] 时本对象才可写入
     */
    fun configure(format: Format): ExclusiveOpenResult {
        if (released) return ExclusiveOpenResult.ForceFail
        // 本方法内**只读一次**上游意图（类注释第 1 条）
        val req = runCatching { request() }.getOrDefault(OutputRequest.Conservative)
        val wantRaw = req.rawAlt
        openedTransportTag = req.transportTag
        streamRawFrames = req.rawFrames

        // 换曲时"等上一首的尾巴播完"是**播放层的策略**，不在这里做（见 `_handleEnded` 的
        // `_awaitTailDrain`）。以前放在这里，代价是阻塞播放线程：那 2 秒里 Media3 报不出位置，
        // 进度条会停在"结尾前两秒"。sink 只保证"清环"这件事本身。

        // 死流不许复用：一直抱着它会让**连普通 PCM 也一起哑**，直到某次走"新会话"
        if (handle != 0L && !engine.isHealthy(handle)) {
            RuntimeLog.record("独占", "流已失效（dead）→ 丢弃并重开会话")
            closeEngine()
        }

        // 同规格切歌不重建流：每次重建都要放掉接口再 claim，DAC 要重锁时钟
        val reusable = handle != 0L && engine !== OboeEngine && engineStillValid() &&
            streamRaw == wantRaw &&
            sampleRate == format.sampleRate &&
            channelCount == format.channelCount &&
            wireUnchanged(wantWireBits())
        if (reusable) {
            locked {
                applyInput(format)
                engine.flush(handle)
                engine.setInputEncoding(handle, pcmEncoding)
                resetClock()
                resetConsumeWatchdog()
            }
            failureReported = false
            applyPlayIntentToEngine()
            listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
            publishRouting(path)
            return ExclusiveOpenResult.Opened
        }

        // 换率 / 换声道：只重建流，不重开会话。失败重试一次再放弃（是时序问题）
        val rebuilt = locked {
            handle != 0L && engine !== OboeEngine && engineStillValid() &&
                run {
                    val raw = wantRaw
                    val wire = wantWireBits()
                    // 退避重试 3 次（60 / 180 / 420 ms）。`reopen` 失败的唯一原因是上一条流的
                    // transfer 还没排空，而那是**几十毫秒到几百毫秒**量级：一次 60 ms 常常不够，
                    // 一放弃就落到"重开会话"这条最重的路（设备掉电重初始化、DAC 重锁 PLL）。
                    var ok = false
                    var delayMs = 60L
                    for (attempt in 0 until 3) {
                        ok = reopenStream(raw, format, wire)
                        if (ok) break
                        Thread.sleep(delayMs)
                        delayMs = (delayMs * 3).coerceAtMost(500L)
                    }
                    if (ok) streamRaw = raw
                    // 重开"成功"不等于流还活着：换率时端点可能在这里错一次 transfer
                    ok && engine.isHealthy(handle)
                }
        }
        if (rebuilt) {
            applyWire(format, wantWireBits())
            applyInput(format)
            resetClock()
            resetConsumeWatchdog()
            failureReported = false
            applyPlayIntentToEngine()
            listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
            publishRouting(path)
            RuntimeLog.record(
                "USB",
                "换规格重建：${format.sampleRate}Hz/${pcmDepth}bit/${channelCount}ch " +
                    "rawAlt=$streamRaw rawFrames=$streamRawFrames",
            )
            return ExclusiveOpenResult.Opened
        }

        // 这一首重新开始：先收干净旧流，再一次性选定引擎
        closeEngine()
        engine.release()
        spec = null
        resetClock()
        failureReported = false
        deadSinceMs = 0L
        stalePauseBuffers = 0

        engine = engineSelector() ?: OboeEngine
        val oboeEngine = engine === OboeEngine
        val ladder = wireCandidates(format, req, oboeEngine)
        val primary = ladder.first()
        applySpec(primary)
        AudioDirectProbe.recordExclusiveAttempt()
        val verdict: ExclusiveVerdict = when {
            oboeEngine &&
                AudioDirectProbe.deviceDeclaresEncoding(primary.encoding) == false ->
                ExclusiveVerdict.Denied(ExclusiveDenial.FormatNotDeclared)
            oboeEngine -> AudioDirectProbe.exclusiveVerdict(
                rate = primary.sampleRate,
                pcmFormat = primary.aaudioFormat,
                wanted = exclusiveWanted(),
            )
            else -> ExclusiveVerdict.Possible
        }
        if (verdict is ExclusiveVerdict.Denied) {
            // 事前拒绝**不写进运行时黑名单**：那不是失败，记进去会让"在系统通路播过一首"
            // 把该率永久拉黑（见 `ExclusivePolicyTest`）
            AudioDirectProbe.recordAaudioActive(false)
            AudioDirectProbe.notePreDenial(verdict.denial)
        }
        if (verdict is ExclusiveVerdict.Possible) {
            val raw = wantRaw
            boundDeviceId = if (oboeEngine && !raw) {
                runCatching { outputDeviceId() }.getOrDefault(0).coerceAtLeast(0)
            } else {
                0
            }
            val perCandidate = if (oboeEngine && !raw) AAUDIO_OPENS_PER_CANDIDATE else 1
            var remaining = if (oboeEngine && !raw) AAUDIO_OPENS_PER_CONFIGURE else ladder.size
            var lastDenial: ExclusiveDenial? = null
            var lastDetail = ""
            var exhausted = false
            for (cand in ladder) {
                if (exhausted) break
                applySpec(cand)
                spec = wireSpecOf(cand, oboeEngine)
                var attempt = 0
                while (attempt < perCandidate) {
                    if (remaining <= 0) {
                        exhausted = true
                        break
                    }
                    val explicit = attempt > 0
                    if (explicit) Thread.sleep(aaudioOpenRetryDelayMs(attempt))
                    remaining--
                    attempt++
                    val opened = openWire(cand, raw, explicit)
                    if (opened == 0L) {
                        lastDenial = ExclusiveDenial.OpenFailed
                        lastDetail = if (oboeEngine) "err=${NativeOboeStream.lastError()}" else ""
                        continue
                    }
                    var rejection: ExclusiveDenial? = null
                    var detail = ""
                    val accepted = locked {
                        if (released) {
                            engine.close(opened)
                            return ExclusiveOpenResult.ForceFail
                        }
                        handle = opened
                        streamRaw = raw
                        val readback = readbackOf(opened, cand, oboeEngine)
                        wireUnknown = readback.unknown
                        val why = readback.denial
                        rejection = why
                        detail = if (why == null) "" else rejectionDetail(opened, why, cand)
                        why == null
                    }
                    if (accepted) {
                        locked { commitOpen(spec ?: cand, oboeEngine) }
                        return ExclusiveOpenResult.Opened
                    }
                    lastDenial = rejection
                    lastDetail = detail
                    closeEngine()
                }
            }
            if (exhausted) lastDetail = openBudgetDetail(lastDetail)
            lastDenial?.let { reportFailure(it, lastDetail) }
        }

        // 走到这里说明独占没成立
        AudioDirectProbe.recordAaudioActive(false)
        boostPlaybackThread(false)
        if (wantRaw) {
            // 「播放器内置 DAC」自己就是一条**逐字节保真**的直出（`DapDirectOutput`），
            // raw 帧该由它接，不该在独占层被判死 —— 它排在 `ExclusiveOutput` **之后**，
            // 在这里 ForceFail 就等于永远轮不到它（实测症状：`dsdnote=TransportFailed`，
            // `files/dap-open.log` 一条都没有，说明 dap 的开流函数压根没被调用）。
            //
            // 其余通路维持原判：raw 帧落到系统 mixer 上不是"音质差"，是满幅噪声。
            if (!usbExclusiveIntent()) {
                return ExclusiveOpenResult.FallbackAllowed
            }
            // **不要在这里上报 systemMix**：`_effectivePath` 会被当成判定依据用回去，
            // 谎报事实会让 DSD 判定自激（失败→降级→换路→再失败，每轮拆一次流）。
            handle = 0L
            onEngineFailure("raw DSD 需要独占通路，系统通路会改写帧标记", openedTransportTag)
            return ExclusiveOpenResult.ForceFail
        }
        return ExclusiveOpenResult.FallbackAllowed
    }

    /** 退回系统通路时该报的降级原因（按用户意图取，不编"未知错误"） */
    fun fallbackReason(): Pair<DowngradeReason?, String> = if (usbExclusiveIntent()) {
        usbUnavailable() to ""
    } else {
        null to ""
    }

    fun publishSystemFallback() {
        val (reason, detail) = fallbackReason()
        publishRouting(SinkKind.audioTrack, reason, detail)
    }

    // ── 写入 ──

    override fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = locked { writeLocked(buffer, presentationTimeUs, encodedAccessUnitCount) }

    private fun writeLocked(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (handle == 0L || bytesPerFrame <= 0) {
            // 流不在了但数据还在来：等 1.5 s 再报（configure 期间也会短暂出现 handle == 0）。
            // **必须返回 false**：返回 true 等于告诉 Media3"这块播出去了"，
            // 故障会被伪装成正常播放（进度条走、没声、onPlayerError 不触发）。
            // 暂停中一律不报：用户按了暂停，流闲着不是故障。
            if (handle == 0L && buffer.remaining() > 0 && playIntent()) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!failureReported) {
                    if (deadSinceMs == 0L) {
                        deadSinceMs = now
                    } else if (now - deadSinceMs > 1_500L) {
                        failureReported = true
                        RuntimeLog.record("独占", "流已关闭但数据仍在到达 —— 请求重建音源")
                        onEngineFailure("独占流已关闭，数据仍在到达", openedTransportTag)
                    }
                }
            }
            return false
        }

        deadSinceMs = 0L

        // 写路径是唯一每块都会经过的地方：在这里发现 dead 立刻重建，
        // 不等"环满且设备不消费 3 秒"的看门狗（它还要 playRequested 成立）。
        // 不写进 AudioDirectProbe 的失败记忆 —— 瞬时故障不该把该率永久降级。
        if (!engine.isHealthy(handle)) {
            if (!failureReported) {
                failureReported = true
                RuntimeLog.record("独占", "写入前发现流已失效（dead）→ 丢弃并重建")
                logNativeFacts("写前失活")
                closeEngine()
                onEngineFailure("独占流已失效", openedTransportTag)
            }
            return true
        }

        val remaining = buffer.remaining()
        if (remaining < bytesPerFrame) return true

        // 与上游播放意图对齐（只动"想播"这一侧）：`play()` 只挂在 BUFFERING→READY
        // 迁移上，focus 抖动回来没有任何迁移，靠它抬不起闸门。
        if (!playRequested) {
            if (intentAllowsResume()) {
                stalePauseBuffers++
                if (stalePauseBuffers >= 2) {
                    stalePauseBuffers = 0
                    playRequested = true
                    engine.resume(handle)
                    RuntimeLog.record("独占", "数据仍在到达但流处于暂停 —— 已自行恢复")
                }
            } else {
                stalePauseBuffers = 0
            }
        } else {
            stalePauseBuffers = 0
        }

        // 设备还在不在消费：只有 framesRead 增长才算（用原始计数，不带基准）
        val now = android.os.SystemClock.elapsedRealtime()
        val readNow = engine.framesRead(handle)
        if (readNow > lastReadFrames) {
            lastReadFrames = readNow
            lastConsumeMs = now
        }

        if (playRequested) {
            val pendingBefore = ring.pendingFrames(ring.playedFrames(readNow))
            if (pendingBefore == 0L && lastWriteMs != 0L && now - lastWriteMs > STARVE_GAP_MS) {
                starveCount++
            }
            lastWriteMs = now
        }

        if (!streamRawFrames && playRequested && sampleRate > 0 && !rateProbed) {
            if (rateWinFrames < 0L) {
                if (readNow > 0L) {
                    rateWinFrames = readNow
                    rateWinAtMs = now
                }
            } else {
                val winMs = now - rateWinAtMs
                if (winMs >= RATE_PROBE_MS) {
                    rateProbed = true
                    val got = readNow - rateWinFrames
                    val want = sampleRate.toDouble() * winMs / 1000.0
                    val ratio = if (want > 0.0) got / want else 1.0
                    if (got > 0L) measuredRateHz = (got * 1000L / winMs).toInt()
                    if (want > 0.0 && got > want * 0.5 &&
                        kotlin.math.abs(ratio - 1.0) > RATE_TOLERANCE
                    ) {
                        val measured = (got * 1000L / winMs).toInt()
                        reportFailure(
                            ExclusiveDenial.RateChanged,
                            "实测 $measured Hz ≠ 请求 $sampleRate Hz",
                        )
                        logNativeFacts("速率不符 measured=$measured want=$sampleRate")
                    }
                }
            }
        }

        // 从 duplicate 读，原 buffer 的 position 留到确认写入后再推进
        val bytes = ByteArray(remaining)
        buffer.duplicate().get(bytes, 0, remaining)

        // 增益在 native 的消费侧施加（环里存未衰减样本），换音量只剩在飞数据的延迟。
        // **DSD 直出一律 1.0**：DoP 的标记在最低位，乘任何系数都会毁掉它。
        val gain = if (streamRawFrames) 1f else exclusiveGain()
        if (gain != pushedGain) {
            engine.setGain(handle, gain)
            pushedGain = gain
        }

        val frames = engine.write(handle, bytes, 0, remaining, bytesPerFrame)
        if (frames < 0) {
            // 流已废。必须立刻关流：handle 不归零的话 Media3 会用同一块反复重试，
            // 每秒数百次 onEngineFailure → 每次都重建整条音源。
            if (!failureReported) {
                failureReported = true
                reportFailure(ExclusiveDenial.WriteFailed, "err=$frames")
                logNativeFacts("写入失败 err=$frames")
                closeEngine()
                onEngineFailure("写入失败（err=$frames）", openedTransportTag)
            }
            return true
        }
        if (frames == 0) {
            // 环满 = 有数据、设备不收。偶尔是正常背压；持续不发说明这条流不再消费，
            // 必须主动掐掉，否则 `written > played` 永久成立 ⇒ isEnded 永远 false。
            // `playRequested` 这个条件必须有：暂停时回调只出静音、本来就不读环。
            if (playRequested && now - lastConsumeMs > STALL_TIMEOUT_MS) {
                reportFailure(ExclusiveDenial.Stalled, "${STALL_TIMEOUT_MS}ms 内已播帧无增长")
                logNativeFacts("停滞 ${STALL_TIMEOUT_MS}ms")
                onEngineFailure(
                    "设备不消费数据（${STALL_TIMEOUT_MS}ms 内已播帧无增长）",
                    openedTransportTag,
                )
                closeEngine()
                return true
            }
            return false
        }

        if (timeBaseNeedsSync) {
            timeBaseNeedsSync = false
            clock.onDiscontinuity(presentationTimeUs)
            ring.rebase(engine.framesRead(handle))
            listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
        }

        buffer.position(buffer.position() + frames * bytesPerFrame)
        ring.onWritten(frames.toLong())
        if (primePending && ring.pendingFrames(ring.playedFrames(readNow)) >= primeFrames()) {
            primePending = false
            engine.resume(handle)
        }
        if (clock.onWritten(presentationTimeUs)) {
            listenerProvider()?.let { runCatching { it.onPositionAdvancing(startTimeBasis()) } }
        }
        return !buffer.hasRemaining()
    }

    // ── 时钟 ──

    override fun positionUs(sourceEnded: Boolean): Long {
        val frozen = frozenUs
        if (frozen != C.TIME_UNSET && !playRequested) return frozen
        if (!ioLock.tryLock()) return lastPositionUs
        try {
            if (handle == 0L) return C.TIME_UNSET
            val playedFrames = seenFrames()
            // 零点与单调性全在 `UacClock` 里；帧数这一项自带上限，两项相加不会到荒谬量级
            val us = clock.positionUs(framesToUs(playedFrames)) ?: return C.TIME_UNSET
            lastPositionUs = us
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStatsMs > 500) {
                lastStatsMs = now
                val oboe = engine === OboeEngine
                AudioDirectProbe.recordAaudioStats(
                    "pos=${us / 1000}ms " +
                        "pend=${if (hasPendingData()) 1 else 0} play=${if (playRequested) 1 else 0} " +
                        "eos=${if (clock.ended) 1 else 0} sr=$sampleRate ch=$channelCount " +
                        "und=${engine.underruns(handle)} st=$starveCount " +
                        if (oboe) {
                            "buf=${NativeOboeStream.bufferSize(handle)}" +
                                "/${NativeOboeStream.bufferCapacity(handle)}"
                        } else {
                            "ring=${ring.pendingFrames(playedFrames)}"
                        },
                )
            }
            return us
        } finally {
            ioLock.unlock()
        }
    }

    private fun framesToUs(frames: Long): Long = ExclusiveClock.framesToUs(frames, sampleRate)

    private fun primeFrames(): Long =
        if (sampleRate <= 0) 1L else (sampleRate.toLong() * PRIME_MS / 1000L).coerceAtLeast(1L)

    /**
     * 位置零点 = 本段首块数据的媒体时间戳，**不加任何 offset**。
     *
     * `setOutputStreamOffsetUs` 只是 Media3 内部时间轴的记账：参考实现 `DefaultAudioSink` 里
     * 压根没有这个方法的实现（接口默认空实现），它的时钟走 `applyMediaPositionParameters`，
     * 用缓冲区时间戳 + 已播时长。sink 一旦自己把 offset 加进去，Media3 再按自己的时间轴
     * 减一次，就是整整 ±1×10^12 µs（界面 `16673:31` 或卡 `0:00`，两次都实测过）。
     */
    private fun startTimeBasis(): Long = clock.baseUs ?: C.TIME_UNSET

    /** 设备真的播出去多少帧（口径见 [UacRing.playedFrames]） */
    private fun playedFramesNow(): Long = locked {
        if (handle == 0L) 0L else ring.playedFrames(engine.framesRead(handle))
    }

    // ── 生命周期 ──

    override fun play() {
        playRequested = true
        frozenUs = C.TIME_UNSET
        locked { if (handle != 0L) engine.resume(handle) }
        com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("L") {
            "play handle=$handle written=${ring.writtenFrames} played=${playedFramesNow()}"
        }
    }

    /**
     * 暂停 = 让回调输出静音，不 `requestPause` 流本身。
     * `AAudioStream_getState` 这类 HAL 上会长期停在 PAUSING，真 pause 之后再也回不到 STARTED。
     */
    override fun pause() {
        playRequested = false
        primePending = false
        stalePauseBuffers = 0
        pausedAtMs = android.os.SystemClock.elapsedRealtime()
        val played = seenFrames()
        val stoppedAt = clock.positionUs(framesToUs(played))
        if (stoppedAt != null && played > 0L) {
            frozenUs = stoppedAt
            lastPositionUs = stoppedAt
        }
        locked { if (handle != 0L) engine.pause(handle) }
        com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("L") {
            "pause handle=$handle written=${ring.writtenFrames} played=$played"
        }
    }

    private fun seenFrames(): Long = minOf(playedFramesNow(), ring.writtenFrames)

    /**
     * 时间戳跳了（不是 seek）：记下"下一次写入时重设基点"，**一个字节都不丢**。
     *
     * 以前这里直接走 `flush()`，等于把环里没播完的尾巴扔掉 —— 听感就是"三分十四秒的歌
     * 三分十二秒就换下一首"，同时位置被归零，界面时钟退一步再爬。
     * 参考实现（`DefaultAudioSink.handleDiscontinuity`）也只置一个同步标志。
     */
    override fun onTimelineDiscontinuity() {
        if (handle == 0L) return
        timeBaseNeedsSync = true
    }

    override fun flush(playRequested: Boolean) {
        locked {
            primePending = false
            frozenUs = C.TIME_UNSET
            if (handle != 0L) {
                if (engine.restartInPlace) {
                    engine.flush(handle)
                } else {
                    val sr = sampleRate
                    val ch = channelCount
                    val enc = pcmEncoding
                    closeEngine()
                    val opened = if (sr > 0 && ch > 0) reopenWire(sr, enc, ch) else 0L
                    if (opened != 0L) {
                        handle = opened
                        resetConsumeWatchdog()
                        AudioDirectProbe.recordAaudioActive(true)
                    }
                }
            }
        }
        resetClock()
        applyPlayIntentToEngine()
        listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
    }

    override fun reset() {
        locked {
            primePending = false
            frozenUs = C.TIME_UNSET
            if (handle != 0L && engine.restartInPlace) {
                engine.pause(handle)
                engine.flush(handle)
            } else {
                closeEngine()
            }
        }
        resetClock()
        failureReported = false
    }

    /**
     * 时钟状态复位的**唯一入口**（configure 三条分支 / flush / reset 全走这里）。
     *
     * 以前这些赋值散在五处：每加一个行为（排空、不连续重同步……）都要同时改五处，
     * 漏一处就是"修好这里、那里坏掉"。现在只有这一份。
     *
     * 基准取**当前读数**，不是 0：native 侧的计数器在 flush 之后并不归零（见 `nativeFlush`），
     * 置 0 等于把"这条流从开到现在的累计帧数"当成本首已播帧。
     */
    private fun resetClock() {
        locked { ring.rebase(if (handle == 0L) 0L else engine.framesRead(handle)) }
        clock.reset()
        lastPositionUs = C.TIME_UNSET
    }

    override fun release() {
        released = true
        boostPlaybackThread(false)
        closeEngine()
        // 会话（USB 的 UsbDeviceConnection）到这里才关：换曲只重建流
        engine.release()
    }

    /**
     * 输入结束：**阻塞到已交给本 sink 的数据全部写出去**（照 Media3 的约定；参考实现走
     * `AudioTrack.playToEndOfStream()` 的 drain）。
     *
     * 不阻塞的代价是实测过的：Media3 立刻认为这本放完 → `_setSource()` 换下一首 →
     * 新流的 `configure()` 清环，环里还没播完的那段就永远没了（听感=最后几秒没声）。
     *
     * 等的预算必须按**环的真实容量**算，不能给固定值：`ringCapacityBytes` 里
     * `perSecond/4` 那一项是按管子的最大承载率取的，alt 3 下环能到 2~3 s ——
     * 固定 2 s 会在快到点的时候放弃，尾巴照样丢。
     *
     * 暂停时不排（用户按了暂停，环里剩的就是剩的）；流没了立即退出。
     */
    override fun playToEndOfStream() {
        val deadline = android.os.SystemClock.elapsedRealtime() + drainBudgetMs()
        while (handle != 0L && playRequested && hasPendingData()) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) break
            Thread.sleep(2L)
        }
        clock.markEnded()
    }

    /** 环深 + 1 s 余量（上限 10 s：真停了就别再等） */
    private fun drainBudgetMs(): Long {
        val ringFrames = locked { if (handle != 0L) engine.ringCapacityFrames(handle) else 0L }
        val ringMs = if (sampleRate > 0) ringFrames * 1000L / sampleRate else 0L
        return (ringMs + 1_000L).coerceAtMost(10_000L)
    }

    override fun isEnded(): Boolean {
        if (handle == 0L) return true
        return clock.ended && !hasPendingData()
    }

    /**
     * 还有没有**没出声**的数据 = 已写帧 > 已播帧 + 在飞。
     *
     * `framesRead` 算的是"读进 URB"，比真正出声早一个 pipe 的水深（[IN_FLIGHT_MS]），
     * 所以必须扣掉：不扣的话 `isEnded` 早了 63 ms 起，Media3 就会带着没播完的数据换曲。
     * 反过来这个判据也不能恒 true —— 那会让播放器永远判不出播完。
     */
    override fun hasPendingData(): Boolean {
        if (handle == 0L) return false
        return ring.writtenFrames > playedFramesNow() + ring.inFlightFrames(sampleRate)
    }

    // ── 内部 ──

    /** 上报实际生效通路。只在**变化**时推 —— 本方法每次切歌都会经过 */
    private fun publishRouting(
        actual: SinkKind,
        reason: DowngradeReason? = null,
        detail: String = "",
    ) {
        val notice = if (usbExclusiveIntent()) {
            downgradeNoticeFor(
                wanted = SinkKind.usbUac,
                actual = actual,
                reason = reason,
                detail = detail,
            )
        } else {
            null
        }
        if (actual == reportedPath && notice == reportedNotice) return
        reportedPath = actual
        reportedNotice = notice
        onRouting(actual, notice, endpointDepth)
    }

    /**
     * 失败上报分流：USB 的失败不能写进 AAudio 的运行时黑名单 ——
     * 那张表记的是"HAL 在某个率上开不开得出独占"，被 USB 写失败污染后，
     * 同一个采样率连 AAudio 都不再尝试。
     */
    private fun reportFailure(denial: ExclusiveDenial, detail: String) {
        if (engine === OboeEngine) {
            AudioDirectProbe.markExclusiveFailure(sampleRate, denial, detail)
        } else {
            AudioDirectProbe.noteUsbFailure(denial, detail)
        }
    }

    /**
     * 把 native 侧的**事实**打成一条日志（`日志诊断` 页可见）。
     *
     * 只在**故障路径**调用 —— 正常播放不产生任何日志。存在的理由：这台 ROM 丢 App 日志，
     * native 里发生了什么（transfer 失败 / 欠载 / 换率被拒 / 置 dead）在上层完全不可见，
     * 于是每个这类问题都只能靠猜或抓包。快照 + 事件环是唯一能让它开口的通道。
     *
     * **必须在 `closeEngine()` 之前调用**：句柄一清零，快照就取不到了。
     */
    private fun logNativeFacts(reason: String) {
        if (engine === OboeEngine) return
        val h = handle
        if (h == 0L) return
        val s = runCatching { NativeUacStream.snapshot(h) }.getOrNull() ?: return
        val events = runCatching { NativeUacStream.drainEvents(h) }.getOrDefault(IntArray(0))
        val ev = buildString {
            var i = (events.size / 3 - 6).coerceAtLeast(0)
            while (i * 3 + 2 < events.size) {
                if (isNotEmpty()) append(' ')
                append(events[i * 3]).append(':').append(events[i * 3 + 1])
                    .append('/').append(events[i * 3 + 2])
                i++
            }
        }
        RuntimeLog.record(
            "USB",
            "$reason · dead=${s.getOrElse(0) { -1 }} paused=${s.getOrElse(1) { -1 }} " +
                "stop=${s.getOrElse(2) { -1 }} rate=${s.getOrElse(3) { 0 }} " +
                "ch=${s.getOrElse(4) { 0 }} frame=${s.getOrElse(5) { 0 }}B " +
                "subslot=${s.getOrElse(6) { 0 }} alt=${s.getOrElse(7) { -1 }} " +
                "read=${s.getOrElse(8) { 0 }} written=${s.getOrElse(9) { 0 }} " +
                "under=${s.getOrElse(10) { 0 }} err=${s.getOrElse(12) { 0 }}" +
                if (ev.isEmpty()) "" else " ev=[$ev]",
        )
    }

    private fun closeEngine() {
        locked {
            val h = handle
            // 先清零再释放：位置查询与配置线程会同时读它
            handle = 0L
            streamRaw = false
            pushedGain = 1f
            primePending = false
            frozenUs = C.TIME_UNSET
            if (h != 0L) engine.close(h)
        }
        // 流没了：硬件音量下发口必须一起撤掉，否则音量键会往已关闭的句柄写
        ExclusiveVolume.attachHardware(null, null)
    }

    /**
     * 重建 / 复位之后"该不该出声"的**唯一判据**：sink 记的意图 **或** 播放器记的意图。
     * 播放中切模式走 `_reloadCurrentSource()`，没有 pause/play 循环，`play()` 永不来。
     */
    @Volatile private var pausedAtMs = 0L

    @Volatile private var frozenUs: Long = C.TIME_UNSET

    private fun intentAllowsResume(): Boolean {
        if (!playIntent()) return false
        val at = playIntentAt()
        if (at <= 0L) return true
        return at > pausedAtMs
    }

    private fun wantsPlay(): Boolean =
        playRequested || intentAllowsResume()

    private fun applyPlayIntentToEngine() {
        locked {
            if (handle == 0L) return
            if (wantsPlay()) {
                playRequested = true
                engine.resume(handle)
            } else {
                engine.pause(handle)
            }
        }
    }

    /** 独占成立时把**调用线程**（ExoPlayer 的 Playback 线程）提到音频优先级 */
    private fun boostPlaybackThread(on: Boolean) {
        if (on == playbackThreadBoosted) return
        playbackThreadBoosted = on
        runCatching {
            android.os.Process.setThreadPriority(
                if (on) {
                    android.os.Process.THREAD_PRIORITY_AUDIO
                } else {
                    android.os.Process.THREAD_PRIORITY_DEFAULT
                },
            )
        }
    }

    /** 开流时要向 native 声明的内容位宽；raw DSD 一律 0（不表态，由 alt 过滤条件决定） */
    private fun wantWireBits(): Int = if (streamRawFrames) 0 else wireBitsHint

    private fun wireCandidates(format: Format, req: OutputRequest, oboe: Boolean): List<WireSpec> {
        val sourceDepth = WireSpec.depthOf(format.pcmEncoding)
        val hint = wantWireBits()
        fun candidate(depth: Int): WireSpec = WireSpec.of(
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            sourceDepth = depth,
            deliveredDepth = 0,
            oboeEngine = false,
            wireBitsHint = hint,
        )
        val order = if (oboe) {
            buildList {
                if (sourceDepth in 16..32) add(sourceDepth)
                val delivered = req.deliveredDepth
                if (delivered in 16..32 && delivered != sourceDepth) add(delivered)
            }.ifEmpty { listOf(sourceDepth) }
        } else {
            listOf(sourceDepth)
        }
        val all = order.distinct().map(::candidate)
        if (!oboe) return all
        return all
            .filter { AudioDirectProbe.deviceDeclaresEncoding(it.encoding) != false }
            .ifEmpty { all }
    }

    private class Readback(val denial: ExclusiveDenial?, val unknown: Int)

    private fun readbackOf(h: Long, spec: WireSpec, oboe: Boolean): Readback {
        if (!oboe) return Readback(null, 0)
        if (NativeOboeStream.audioApi(h) == 2) return Readback(ExclusiveDenial.OpenSlBackend, 0)
        if (NativeOboeStream.sharingMode(h) != 1) return Readback(ExclusiveDenial.SharedStream, 0)
        val rate = NativeOboeStream.sampleRate(h)
        val format = NativeOboeStream.format(h)
        val hardwareRate = NativeOboeStream.hardwareSampleRate(h)
        val hardwareFormat = NativeOboeStream.hardwareFormat(h)
        var unknown = 0
        if (rate <= 0) unknown = unknown or AAUDIO_UNKNOWN_RATE
        if (format <= 0) unknown = unknown or AAUDIO_UNKNOWN_FORMAT
        if (hardwareFormat <= 0) unknown = unknown or AAUDIO_UNKNOWN_HW_FORMAT
        val denial = when {
            rate > 0 && rate != spec.sampleRate -> ExclusiveDenial.RateChanged
            hardwareRate > 0 && hardwareRate != spec.sampleRate -> ExclusiveDenial.RateChanged
            format > 0 && !WireSpec.formatAcceptable(spec.aaudioFormat, format) ->
                ExclusiveDenial.FormatChanged
            hardwareFormat > 0 && !WireSpec.formatAcceptable(spec.aaudioFormat, hardwareFormat) ->
                ExclusiveDenial.FormatChanged
            else -> null
        }
        return Readback(denial, unknown)
    }

    private fun openWire(cand: WireSpec, raw: Boolean, explicit: Boolean): Long {
        if (raw) return engine.openRaw(cand.sampleRate, cand.channelCount)
        if (engine !== OboeEngine) {
            return engine.open(cand.sampleRate, cand.encoding, cand.channelCount, cand.announceBits)
        }
        return OboeEngine.openAaudio(
            sampleRate = cand.sampleRate,
            depth = cand.depth,
            channels = cand.channelCount,
            deviceId = boundDeviceId,
            framesPerCallback = if (explicit) aaudioFramesPerCallback(cand.sampleRate) else 0,
            bufferCapacityFrames = if (explicit) aaudioBufferCapacityFrames(cand.sampleRate) else 0,
        )
    }

    private fun reopenWire(sampleRate: Int, encoding: Int, channels: Int): Long {
        if (engine !== OboeEngine) return engine.open(sampleRate, encoding, channels)
        return OboeEngine.openAaudio(
            sampleRate = sampleRate,
            depth = WireSpec.depthOf(encoding),
            channels = channels,
            deviceId = boundDeviceId,
            framesPerCallback = 0,
            bufferCapacityFrames = 0,
        )
    }

    private fun openBudgetDetail(detail: String): String {
        val tag = "openStream 已用满 " +
            if (engine === OboeEngine) "$AAUDIO_OPENS_PER_CONFIGURE 次" else "1 次"
        return if (detail.isEmpty()) tag else "$detail · $tag"
    }

    private fun rejectionDetail(h: Long, denial: ExclusiveDenial, spec: WireSpec): String =
        when (denial) {
            ExclusiveDenial.RateChanged -> "${NativeOboeStream.sampleRate(h)} Hz"
            ExclusiveDenial.FormatChanged ->
                "实际 ${NativeOboeStream.format(h)} / 请求 ${spec.aaudioFormat}"
            else -> ""
        }

    private fun commitOpen(wire: WireSpec, oboe: Boolean) {
        resetConsumeWatchdog()
        val hwCaps = engine.hardwareVolumeCaps(handle)
        ExclusiveVolume.attachHardware(
            caps = hwCaps,
            writer = if (hwCaps == null) {
                null
            } else {
                { v -> engine.setHardwareVolume(handle, v) }
            },
        )
        pushedGain = if (streamRawFrames) 1f else exclusiveGain()
        engine.setGain(handle, pushedGain)
        boostPlaybackThread(true)
        if (playRequested || intentAllowsResume()) {
            playRequested = true
            primePending = true
            engine.pause(handle)
        } else {
            primePending = false
            applyPlayIntentToEngine()
        }
        if (oboe) {
            AudioDirectProbe.recordAaudioActive(true)
            RuntimeLog.record(
                "AAudio",
                "独占流已开：${wire.sampleRate}Hz/${wire.channelCount}ch/${wire.depth}bit · " +
                    "Oboe" + when (NativeOboeStream.audioApi(handle)) {
                        1 -> "（AAudio）"
                        2 -> "（OpenSL ES，无独占）"
                        else -> ""
                    } + " · 缓冲 ${NativeOboeStream.bufferSize(handle)}/" +
                    "${NativeOboeStream.bufferCapacity(handle)} 帧" +
                    (if (boundDeviceId > 0) " · dev=$boundDeviceId" else "") +
                    (if (wireUnknown == 0) "" else " · 回读未知=$wireUnknown"),
            )
        } else {
            RuntimeLog.record(
                "USB",
                "独占流已开：${wire.sampleRate}Hz/${wire.channelCount}ch/${wire.depth}bit · " +
                    "环 ${NativeUacStream.ringCapacityFrames(handle)} 帧 · " +
                    "上线 ${NativeUacStream.outFrameBytes(handle)} B/帧",
            )
        }
        listenerProvider()?.let { runCatching { it.onPositionDiscontinuity() } }
        endpointDepth = if (oboe) {
            WireSpec.depthOfAaudioFormat(NativeOboeStream.format(handle))
        } else {
            val frameBytes = NativeUacStream.outFrameBytes(handle)
            if (frameBytes > 0 && wire.channelCount > 0) {
                (frameBytes / wire.channelCount) * 8
            } else {
                0
            }
        }
        publishRouting(path)
    }

    private fun applySpec(wire: WireSpec) {
        spec = wire
        sampleRate = wire.sampleRate
        channelCount = wire.channelCount
        pcmDepth = wire.depth
        pcmEncoding = wire.encoding
        bytesPerFrame = wire.bytesPerFrame
    }

    private fun applyInput(format: Format) {
        val depth = WireSpec.depthOf(format.pcmEncoding)
        pcmDepth = depth
        pcmEncoding = format.pcmEncoding
        bytesPerFrame = WireSpec.bytesPerSample(depth) * format.channelCount
    }

    private fun applyWire(format: Format, wireBits: Int) {
        val wireDepth = if (wireBits in 16..32) wireBits else WireSpec.depthOf(format.pcmEncoding)
        spec = WireSpec.of(
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            sourceDepth = wireDepth,
            deliveredDepth = 0,
            oboeEngine = false,
            wireBitsHint = wireBits,
        )
        sampleRate = format.sampleRate
        channelCount = format.channelCount
    }

    private fun wireSpecOf(cand: WireSpec, oboe: Boolean): WireSpec = if (oboe) {
        cand
    } else {
        WireSpec.of(
            sampleRate = cand.sampleRate,
            channelCount = cand.channelCount,
            sourceDepth = if (cand.announceBits in 16..32) cand.announceBits else cand.depth,
            deliveredDepth = 0,
            oboeEngine = false,
            wireBitsHint = cand.announceBits,
        )
    }

    private fun wireUnchanged(wireNow: Int): Boolean =
        WireSpec.wireMatches(wireNow, spec?.announceBits ?: 0)

    private fun reopenStream(raw: Boolean, format: Format, wire: Int): Boolean =
        if (raw) {
            engine.reopenRaw(handle, format.sampleRate, format.channelCount)
        } else {
            engine.reopen(handle, format.sampleRate, format.pcmEncoding, format.channelCount, wire)
        }

    /** 每次新开流之后必须重置：`framesRead` 每条流从 0 开始，不重置会误判停滞 */
    private fun resetConsumeWatchdog() {
        locked {
            lastReadFrames = if (handle != 0L) engine.framesRead(handle) else 0L
        }
        lastConsumeMs = android.os.SystemClock.elapsedRealtime()
        rateWinFrames = -1L
        rateWinAtMs = 0L
        rateProbed = false
    }

    private companion object {
        /** 写不进去 + 设备读不走持续这么久就判这条流已废（正常背压最长几百毫秒） */
        const val STALL_TIMEOUT_MS = 3_000L

        const val RATE_PROBE_MS = 900L

        const val RATE_TOLERANCE = 0.05

        const val STARVE_GAP_MS = 25L

        const val PRIME_MS = 10L
        /**
         * 在飞水深：`kUrbCount(21) × kPacketsPerUrb(24)` 个微帧 = 63 ms。
         *
         * 这段时间的数据已经提交给主机控制器、还没从 DAC 出声，`framesRead` 却已经算进去了。
         * 所以判"还有没有没播完的数据"时必须把它扣掉 —— 否则 `isEnded` 会早于真实出声，
         * Media3 提前换曲、我们 configure 时清环，尾巴就没了。
         */
        const val IN_FLIGHT_MS = 63L
    }
}
