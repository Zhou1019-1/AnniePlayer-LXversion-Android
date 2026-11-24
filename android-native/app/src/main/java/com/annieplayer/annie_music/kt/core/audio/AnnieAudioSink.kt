package com.annieplayer.annie_music.kt.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.annieplayer.annie_music.kt.core.audio.dap.DapDirectOutput
import com.annieplayer.annie_music.kt.core.audio.dap.dapTrace
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * **播放器的音频出口** —— Media3 的 `AudioSink` 唯一实现。
 *
 * 它只做三件事：**选后端**（[configure]）、把 Media3 的 setter 转给当前后端、
 * 把后端的状态转回 Media3。写路径、时钟、静音、结束判定全在 [OutputPort] 的实现里：
 *
 * | 后端 | 实现 | 位置/时钟 |
 * |---|---|---|
 * | USB 直连（libusb UAC2） | [ExclusiveOutput] | 我方环形缓冲 + `framesRead` |
 * | AAudio 独占（Oboe） | [ExclusiveOutput] | 同上（HAL 回调拉模式） |
 * | 系统混音（AudioTrack） | [SystemOutput] | `DefaultAudioSink` 自己的 tracker |
 *
 * 命名说明：以前叫 `AaudioSink`，但它管的是三条通路，不只是 AAudio —— 那个名字让
 * "系统通路在别处"成了默认假设，于是每个方法开头都要写一遍 `systemSink?.let { ...; return }`，
 * 写路径与时钟各有两份。现在只剩 [port] 一个入口。
 *
 * ## 绝不中途切换后端
 *
 * 后端在 `configure()` 里一次选定，整首不换。运行中失败（设备被抢 / 拔出）不走"偷偷切到
 * 系统混音"：[onEngineFailure] 通知上游重建音源，那时该率已被标记不可独占，
 * 下一次 `configure()` 自然会选中系统后端。
 *
 * ## raw DSD 不许落到系统通路
 *
 * DoP / Native 送的是帧，`DefaultAudioSink` 内部 24bit → float 的处理器会把 0x05/0xFA
 * 帧标记除成 ~1e-7 的直流，DAC 再也认不出合法序列。所以独占开不出来时
 * [ExclusiveOutput] 直接给 [ExclusiveOpenResult.ForceFail]，这里不回退、只上报失败。
 */
@OptIn(UnstableApi::class)
internal class AnnieAudioSink(
    /** 系统播放通路（`DefaultAudioSink`），**每次 configure 现场新建** */
    private val systemSinkFactory: () -> AudioSink,
    /** 现在该不该试独占（透明模式才试） */
    exclusiveWanted: () -> Boolean = { true },
    /** 独占中途失败 → 播放层重建音源 */
    onEngineFailure: (String, String) -> Unit = { _, _ -> },
    /** 这一首选哪个直出内核；null 也退到 AAudio */
    engineSelector: () -> PcmEngine? = { OboeEngine },
    /** 已经开着的引擎还算不算数（通路没被切走） */
    engineStillValid: () -> Boolean = { false },
    /** 实际生效通路 / 降级原因 / 端点位深 */
    onRouting: (SinkKind, DowngradeNotice?, Int) -> Unit = { _, _, _ -> },
    /** USB 走不成的原因 */
    private val usbUnavailable: () -> DowngradeReason = { DowngradeReason.UsbOpenFailed },
    private val usbExclusiveIntent: () -> Boolean = { false },
    /** 这一次 configure 要什么 */
    private val request: () -> OutputRequest = { OutputRequest.Conservative },
    private val outputDeviceId: () -> Int = { 0 },
    /** 独占通路的线性增益 */
    private val exclusiveGain: () -> Float = { 1f },
    /** 上游此刻想不想播 */
    private val playIntent: () -> Boolean = { true },
    private val playIntentAt: () -> Long = { 0L },
    /**
     * DAP 内置 DAC 直通。
     *
     * 只有**实测成立**时才会返回一个可开的对象（`DapCapability.working` 与上游格式对得上），
     * 其余情况一律 `null` —— 这里不做"猜一下试试"，试错由探测阶段完成。
     */
    private val dapOpener: (Format) -> DapDirectOutput? = { null },
    private val sinkRequest: (Format) -> SinkRequest = { _ ->
        SinkRequest(
            route = AudioRoute.other,
            usbExclusive = false,
            rawFrames = false,
            dsdNativeAvailable = false,
            aaudioExclusiveAvailable = true,
        )
    },
    /**
     * 这一首是 **raw DSD 帧**（DoP / Native 的 1-bit 载荷），而内置 DAC 那条流这次没开成。
     *
     * 必须回调：raw 帧落到系统 mixer 上不是"音质变差"，是**满幅噪声**（实测踩到）。
     * 上游据此把这一档的 native 记成失败，并按 PCM 重建音源。
     */
    private val rawUnroutable: () -> Unit = {},
) : AudioSink {

    private var listener: AudioSink.Listener? = null

    /** 诊断：handleBuffer 被调用了几次（定位用，事后删） */
    private var traceBuffers = 0

    /**
     * 上一次 `configure` 的入参 —— [handleBuffer] 自救时要用。
     *
     * 只在"port 为空但 Media3 不再 configure"那种僵局里用得上，正常路径不读它。
     */
    private var lastFormat: Format? = null
    private var bufferSizeHint = 0
    private var outputChannelsHint: IntArray? = null

    /**
     * 当前生效的后端 —— **不可变值，一次性替换**。
     *
     * 以前这里是三个互相引用的可变字段（`port` / `systemSink` / `dapOutput`），
     * 每个分支各改几处，于是"换了后端却没换 port"、"port 还指着已释放对象"、
     * "两条路同时非空"这些状态都构造得出来 —— 实测表现是播放器卡在 BUFFERING、
     * `loading=false`、只有重启 App 才恢复。
     *
     * 现在一次 configure 只做**一次赋值**：要么换成新后端，要么换成 null（这一首没后端）。
     * 旧后端在赋值前收掉，中间态不存在。
     */
    private class Backend(
        val port: OutputPort,
        /** 需要转发 Media3 setter 时的系统 sink；独占 / 直出为 null */
        val systemSink: AudioSink? = null,
        /** 是不是内置 DAC 直通 —— `releaseDap()` 要认这个 */
        val isDap: Boolean = false,
        /** configure 换后端时收旧后端（系统通路是 `reset`，直出是 `release`） */
        val onReconfigure: () -> Unit = {},
        /** `AudioSink.release()` 时收 */
        val onDispose: () -> Unit = {},
    )

    @Volatile
    private var backend: Backend? = null

    private var backendSlot: Backend?
        get() = backend
        set(value) {
            backend = value
            outletAlive = value != null
        }

    /** 当前后端；`null` = 这一首既没开成独占、也没建系统 sink（raw 判失败 / DAP 模式下的 PCM） */
    private val port: OutputPort? get() = backend?.port

    /**
     * 写路径与控制线程之间的互斥锁。
     *
     * 只有直出后端会真的竞争（控制调用搬到了 [DapControlManager] 上）。用 `tryLock`
     * 而不是 `lock`：渲染线程**永远不许**在这里等 —— 等的代价就是它和被卡住的那次
     * native 调用一起停住，那正是整条引擎永久停摆的起点。
     */
    private val portGate = java.util.concurrent.locks.ReentrantLock()

    /** 系统后端持有的 Media3 sink；只用来转发 setter，不参与数据路径 */
    private val systemSink: AudioSink? get() = backend?.systemSink

    /**
     * 内置 DAC 直通流的**控制调用**执行器（pause / play / flush / reset / release）。
     *
     * 为什么必须有：那条流底下是私有 ABI 的 `AudioTrack`，它的 `pause()` / `flush()` /
     * 析构在部分 ROM 上**不返回**（`NativeDapDac.close` 的注释里记了两处实测：
     * `~AudioTrack()` 卡在 `close:4`、同一 ROM 上 `pause()` 也会卡）。这些调用现在都发生在
     * Media3 的播放线程上（`MediaCodecAudioRenderer.onStopped()` → `audioSink.pause()`），
     * 卡一次就等于**整条引擎永久停摆**：播放线程再也不处理消息，`playWhenReady`、
     * `startRenderers`、`audioSink.play()` 全部排在这条线程后面。
     *
     * 实测 trace（`dapTrace` 的 `L`）就是这么断的：`L pause` 是播放线程打印的**最后一行**，
     * 之后 100 秒里既没有 resume 的 `L play`，也没有任何一次新的 `O`/`C`。
     * 换成"记账就返回"之后，播放线程不再碰这些调用，暂停 / 恢复 / 换通路都不会再被它拖住。
     */
    private val dapControl = DapControlManager(portGate)

    /** 构造参数转成字段：`onRouting` 没有 `val`，方法体里直接用不了 */
    private val routingReporter: (SinkKind, DowngradeNotice?, Int) -> Unit = onRouting

    private val exclusive = ExclusiveOutput(
        exclusiveWanted = exclusiveWanted,
        onEngineFailure = onEngineFailure,
        engineSelector = engineSelector,
        engineStillValid = engineStillValid,
        onRouting = onRouting,
        usbUnavailable = usbUnavailable,
        usbExclusiveIntent = usbExclusiveIntent,
        request = request,
        outputDeviceId = outputDeviceId,
        exclusiveGain = exclusiveGain,
        playIntent = playIntent,
        playIntentAt = playIntentAt,
        listenerProvider = { listener },
    )

    // ── 先记账的 setter（configure 时重放给系统 sink）──
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilence = false
    private var audioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var volume = 1f
    private var sessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffect = AuxEffectInfo(0, 0f)

    /** 最近一次 `setOutputStreamOffsetUs` 的值；只给 [currentPeriodPositionUs] 换算用 */
    private var outputStreamOffsetUs = 0L

    /** Media3 是否要求过"开始播放"。系统后端的 `flush()` 要靠它补一次 `play()` */
    private var playRequested = false

    private fun markOutlet(alive: Boolean) {
        outletAlive = alive
    }

    /** 这一首的**源**位深（由 `PcmAudioSink` 在处理器链之前读出来），开流时用来挑 alt */
    var wireBitsHint: Int
        get() = exclusive.wireBitsHint
        set(value) {
            exclusive.wireBitsHint = value
        }

    // ── AudioSink ──

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        systemSink?.let { runCatching { it.setListener(listener) } }
    }

    override fun supportsFormat(format: Format): Boolean =
        systemSink?.supportsFormat(format)
            ?: (getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)

    override fun getFormatSupport(format: Format): Int {
        systemSink?.let { return it.getFormatSupport(format) }
        // 独占通路里 32 bit 有两种含义：Native DSD 的 raw word，以及真正的 32 bit 整数 PCM。
        // 两者的字节处理是同一件事（`push()` 里 int32 恒等 memcpy），所以一律放行。
        //
        // ⚠️ 曾经按"此刻是不是 Native"判定 —— 于是格式协商结果取决于一个会异步变动的
        // 意图标志：切模式瞬间可能把 32 bit 判为不支持 → 渲染器不启用该轨 →
        // `configure()` 永不执行 → **整首没声且不报错**。
        val supported = when (format.pcmEncoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            -> true
            else -> false
        }
        return if (supported && format.sampleRate > 0 && format.channelCount in 1..8) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        systemSink?.getFormatOffloadSupport(format) ?: AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(format: Format, bufferSize: Int, outputChannels: IntArray?) {
        // 诊断计数：一首歌里 `configure` 被调几次。每调一次系统后端就会重建 AudioTrack，
        // 位置追踪器随之复位 —— 调换次数就是"时间轴被冻住几次"的上界。
        configureCount++
        lastConfigureNote = "${format.sampleRate}/${format.pcmEncoding}/${format.channelCount}"
        // 记下本首的帧宽与率：`handleBuffer` 用它们把字节数换成时长，位置按它累计。
        counterBegin(
            format.sampleRate,
            runCatching {
                androidx.media3.common.util.Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            }.getOrDefault(0),
        )
        // 先收干净上一首的后端（**单点**：这里收完，下面只做一次赋值）
        backend?.let { prev ->
            if (prev.isDap) {
                // 直出流的收尾既有 join（`SelfPacedWriter.stop`，上限 250 ms）又有 native 析构。
                // **必须与它的控制线程串行**：否则这里正在 `release()`（handle 归零 + 释放），
                // 控制线程上那条还没跑完的 `pause()` 会拿着同一个 handle 进 native。
                // 等的是那条队列，不是设备：正常路径下队列是空的，`await` 立刻返回。
                dapControl.runAndAwait("reconfigure") { runCatching { prev.onReconfigure() } }
            } else {
                runCatching { prev.onReconfigure() }
            }
        }
        backend = null

        val req = sinkRequest(format)
        val chain = resolveSinkChain(req)
        val wantExclusive = chain.contains(SinkKind.usbUac) || chain.contains(SinkKind.oboe)
        if (wantExclusive) {
            when (exclusive.configure(format)) {
                ExclusiveOpenResult.Opened -> {
                    backendSlot = Backend(port = exclusive)
                    return
                }

                ExclusiveOpenResult.ForceFail -> {
                    backendSlot = null
                    return
                }

                ExclusiveOpenResult.FallbackAllowed -> Unit
            }
        }

        val wantDsd = chain.contains(SinkKind.dsdDirect)
        val dap = if (wantDsd) dapOpener(format) else null
        val dapOpened = dap != null && dap.open()
        dapTrace("C") {
            "wanted=$wantDsd rate=${format.sampleRate} enc=${format.pcmEncoding} " +
                "ch=${format.channelCount} raw=${req.rawFrames} chain=$chain dapOpen=$dapOpened " +
                "note=${com.annieplayer.annie_music.kt.core.audio.dap.NativeDapDac.lastOpenNote}"
        }
        if (dapOpened) {
            val opened = dap!!
            backendSlot = Backend(
                port = opened,
                isDap = true,
                onReconfigure = { runCatching { opened.release() } },
                onDispose = { runCatching { opened.release() } },
            )
            routingReporter(SinkKind.dsdDirect, null, opened.depthBits)
            if (playRequested) dapControl.submit("configure-kick") { runCatching { opened.play() } }
            return
        }

        if (chain.contains(SinkKind.audioTrack)) {
            buildSystemSink(format, bufferSize, outputChannels)
            if (playRequested) systemSink?.play()
            exclusive.publishSystemFallback()
            return
        }

        backend = null
        if (req.rawFrames) rawUnroutable()
    }

    /**
     * 建系统通路（`AudioTrack`/AudioFlinger）。
     *
     * 抽成方法是为了让 [handleBuffer] 能在"`port` 还指着已释放的后端、而 Media3 又因为
     * 格式判等不再调 `configure`"时自救 —— 那是"切回系统通路也不出声"的直接原因。
     *
     * 顺序照 Media3 的真实调用序（listener → attributes → params → 其余），
     * 漏一条就等于让系统 sink 少一个回调/少一个属性。
     */
    private fun buildSystemSink(format: Format, bufferSize: Int, outputChannels: IntArray?) {
        val system = systemSinkFactory()
        listener?.let { runCatching { system.setListener(it) } }
        runCatching { system.setAudioAttributes(audioAttributes) }
        runCatching { system.setPlaybackParameters(playbackParameters) }
        runCatching { system.setSkipSilenceEnabled(skipSilence) }
        runCatching { system.setVolume(volume) }
        if (sessionId != C.AUDIO_SESSION_ID_UNSET) runCatching { system.setAudioSessionId(sessionId) }
        runCatching { system.setAuxEffectInfo(auxEffect) }
        // **高率曲目加大缓冲。** 实测飞傲 JM21 放 DSD→PCM（交付 176.4k）时，
        // 起播处播放头原地停了 1.81 秒，之后斜率精确 1.0 —— 那是 `AudioTrack`
        // 起播断流（缓冲被取空、重新填充），不是率的问题。手机把 176.4k 交给系统
        // 转 48k、深缓冲把抖动吸收了，所以只在直送高率的设备上暴露。
        val sinkBuffer = if (format.sampleRate > 48_000) bufferSize * 4 else bufferSize
        system.configure(format, sinkBuffer, outputChannels)
        val sysPort = SystemOutput(
            sink = system,
            deliveredUs = { deliveredUs },
        )
        backendSlot = Backend(
            port = sysPort,
            systemSink = system,
            // 换后端时系统 sink 是 `reset`（Media3 会在下次 configure 重配它），
            // 整条流销毁时才是 `release`
            onReconfigure = { runCatching { system.reset() } },
            onDispose = { runCatching { system.release() } },
        )
        lastFormat = format
        bufferSizeHint = bufferSize
        outputChannelsHint = outputChannels
    }

    /**
     * `playRequested` 的语义：**Media3 要求当前后端处于"播放"态**（电平，不是边沿）。
     *
     * 写入点只有两个（[play] / [pause]），读取点只有三处：新 `configure` 里补一次 kick
     * （[configure] 的两条后端分支）、[flush] 转交、以及 `SystemOutput` /
     * `DapDirectOutput` 自己的 `flush(playRequested)`。所以"暂停后恢复"要求的是：
     * 恢复时 Media3 必须再调一次本方法（`MediaCodecAudioRenderer.onStarted()` →
     * `audioSink.play()`），并且这次调用**必须真的到 port**。
     *
     * 以前它到不了 port，但原因不是这里少了一行转发 —— 是播放线程在 [pause] 里
     * 卡死在 native 调用上，`onStarted()` 根本没机会被调。见 [dapControl]。
     */
    override fun play() {
        playRequested = true
        val b = backend
        if (b != null && b.isDap) {
            dapControl.submit("play") { runCatching { b.port.play() } }
        } else {
            b?.port?.play()
        }
    }

    /**
     * 立刻拆掉内置 DAC 直通的那条流（并把厂商 HAL 复位回 PCM）。
     *
     * 必须在**切到非 DSD 曲目之前**调：那条 DIRECT 流一开，厂商 HAL 的 `format` /
     * `sampling_rate` 就被拨到 DSD、硬件率停在 DSD 的 hwRate 上（实测 176400）。
     * 只 `pause` 不 `release` 的话，HAL 会留在 DSD 模式，后面每一首 PCM 都被
     * "按设备率"重采样到 176.4 kHz —— 实测踩到。
     *
     * 幂等：没有直通流时什么都不做。
     */
    fun releaseDap() {
        val b = backend ?: return
        if (!b.isDap) return
        backendSlot = null
        // 与它的控制线程串行：这条流上可能还压着一条没跑完的 pause（见 [dapControl]）。
        // 有界等待：正常路径立刻返回，卡住的那条不再为它等。
        dapControl.runAndAwait("releaseDap") { runCatching { b.onDispose() } }
    }

    override fun handleDiscontinuity() {
        // 不连续 ≠ seek：只重同步时间轴基点，环里没播完的数据必须照播（见 `OutputPort` 的说明）。
        // 它是纯记账（只置 `timeBaseNeedsSync`），没有 native 调用，所以留在渲染线程上。
        port?.onTimelineDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        bufferCalls++
        // **写与控制动作之间必须有闸。**
        //
        // 直出后端的 pause / flush / reset 现在跑在控制线程上（见 [dapControl]），写还在渲染线程上：
        // ① 排队中的控制动作必须先落地 —— 否则 seek 之后的新位置数据会先写进旧环、再被 flush 掉，
        //    听感是"拖完进度条先响一下旧位置"；
        // ② 正在跑的那个要互斥（[portGate] 用 `tryLock`，渲染线程永不等）；
        // ③ 一旦超时（那条流已经卡住），既不再等也不加锁：写继续失败、健康状态已经记下，
        //    由播放层换引擎去救 —— 绝不能为了顺序把渲染线程一起锁死。
        //
        // 返回 false 是 Media3 的正规语义：它会用**同一块**重试，不丢样本。
        val dapBackend = backend?.isDap == true
        var gateLocked = false
        if (dapBackend && !dapControl.overdue()) {
            if (dapControl.busy()) return false
            gateLocked = portGate.tryLock()
            if (!gateLocked) return false
        }
        // **死 port 自救。**
        //
        // Media3 只在"输出格式变化"时调 `configure`。而 `port` 是跨曲目存活的：
        // 上一首的内置 DAC 流被 `release()` 之后 `port` 还是那个死对象，
        // `port?.write(...) ?: false` 就永远返回 false —— 播放器卡在 BUFFERING，
        // 而且因为下一首的格式可能和上一首**一模一样**（计划被残留的 176400 带偏），
        // `configure` 再也不会来。实测：切回「系统 · AudioTrack」也一句声音都没有。
        //
        // 所以这里必须能自己把系统通路建起来。raw 帧仍然绝不退系统通路（那是满幅噪声）。
        // 「播放器内置 DAC」模式下**也不自救**：那条路不降级，见 `configure` 里的说明。
        if (port == null && systemSink == null && !request().rawFrames) {
            val fmt = lastFormat
            if (fmt != null) {
                dapTrace("C") { "handleBuffer 自救：port 为空，按 $fmt 重建系统通路" }
                buildSystemSink(fmt, bufferSizeHint, outputChannelsHint)
            }
        }
        // 按 `position` 的**增量**累计交付量，再交给后端（后端会吃掉 buffer 的 position）。
        // 不能用 `remaining()`：写不进时 Media3 会用同一个 buffer 反复重试，每次重试都会多算一整块。
        val before = buffer.position()
        val accepted = try {
            port?.write(buffer, presentationTimeUs, encodedAccessUnitCount) ?: false
        } finally {
            if (gateLocked) portGate.unlock()
        }
        counterAdd(buffer.position() - before)
        traceBuffers++
        if (traceBuffers <= 40 || traceBuffers % 16 == 0) {
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("S") {
                "n=$traceBuffers port=${port?.path?.name ?: "null"} in=${buffer.limit()} " +
                    "direct=${buffer.isDirect()} ok=$accepted " +
                    "accepted=${buffer.position() - before} ptsUs=$presentationTimeUs"
            }
        }
        return accepted
    }

    override fun playToEndOfStream() {
        port?.playToEndOfStream()
    }

    override fun isEnded(): Boolean = port?.isEnded() ?: true

    override fun hasPendingData(): Boolean = port?.hasPendingData() ?: false

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val us = port?.positionUs(sourceEnded) ?: return AudioSink.CURRENT_POSITION_NOT_SET
        // Media3 的"没有位置"哨兵是 CURRENT_POSITION_NOT_SET（Long.MIN_VALUE），
        // 独占侧用的是 C.TIME_UNSET（MIN_VALUE + 1）。不归一化它就会被当成**合法位置**读走，
        // 媒体会话里直接显示成 9.2e15 ms（实测），进度条也会跟着抖。
        return if (us == C.TIME_UNSET) AudioSink.CURRENT_POSITION_NOT_SET else us
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
        systemSink?.let { runCatching { it.setPlaybackParameters(playbackParameters) } }
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        systemSink?.playbackParameters ?: playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
        systemSink?.let { runCatching { it.setSkipSilenceEnabled(skipSilenceEnabled) } }
    }

    override fun getSkipSilenceEnabled(): Boolean = systemSink?.skipSilenceEnabled ?: skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
        systemSink?.let { runCatching { it.setAudioAttributes(audioAttributes) } }
    }

    override fun getAudioAttributes(): AudioAttributes =
        systemSink?.audioAttributes ?: audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        sessionId = audioSessionId
        systemSink?.let { runCatching { it.setAudioSessionId(audioSessionId) } }
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        auxEffect = auxEffectInfo
        systemSink?.let { runCatching { it.setAuxEffectInfo(auxEffectInfo) } }
    }

    override fun enableTunnelingV21() {
        systemSink?.let { runCatching { it.enableTunnelingV21() } }
    }

    override fun disableTunneling() {
        systemSink?.let { runCatching { it.disableTunneling() } }
    }

    /**
     * Media3 的时间轴记账，**独占通路完全不碰**：位置一律在"首块缓冲区时间戳"那个坐标系里报，
     * offset 由 Media3 在时间轴层自己加减。加进 sink 就是加两次（实测 ±1×10^12 µs）。
     */
    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        systemSink?.let { runCatching { it.setOutputStreamOffsetUs(offsetUs) } }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        // 独占通路刻意不下发：透明模式不允许我方数字衰减（音量走 `ExclusiveVolume`）
        systemSink?.let { runCatching { it.setVolume(volume) } }
    }

    override fun pause() {
        playRequested = false
        val b = backend
        if (b != null && b.isDap) {
            // 内置 DAC 直通：native pause 不许发生在播放线程上（见 [dapControl]）。
            // 停喂在本方法返回时就已经成立（后面的 handleBuffer 会被闸住），
            // 设备侧只是把已经排进去的那点数据播完 —— 与真正的 pause 行为一致。
            dapControl.submit("pause") { runCatching { b.port.pause() } }
        } else {
            b?.port?.pause()
        }
    }

    override fun flush() {
        // 系统后端在 `SystemOutput.flush` 里已经按 playRequested 补过 play()；
        // 独占后端自己走 `applyPlayIntentToEngine()`。这里不重复补。
        val b = backend
        if (b != null && b.isDap) {
            // `sink.flush()` 同样是 native 调用，和 pause 一起排到控制线程上。
            // 顺序由那条线程的串行队列保证（pause → flush → play 不会乱序）。
            val wantPlay = playRequested
            dapControl.submit("flush") { runCatching { b.port.flush(wantPlay) } }
        } else {
            b?.port?.flush(playRequested)
        }
    }

    override fun reset() {
        val b = backend
        if (b != null && b.isDap) {
            dapControl.submit("reset") { runCatching { b.port.reset() } }
        } else {
            b?.port?.reset()
        }
    }

    override fun release() {
        // 后端只有一个：收它一次就干净了（以前这里要分别收 port / dapOutput / systemSink
        // 三个字段，漏一个就是泄漏一条流或一条未注销的服务端轨）
        val b = backend
        backendSlot = null
        if (b != null) {
            if (b.isDap) {
                // 直出流的 release 里有 `SelfPacedWriter.stop()`（最多 join 250 ms）和
                // 异步析构；放到控制线程上，渲染线程不必为它等。
                dapControl.submit("release") {
                    runCatching { b.port.release() }
                    runCatching { b.onDispose() }
                }
            } else {
                runCatching { b.port.release() }
                runCatching { b.onDispose() }
            }
        }
    }

    /** 仅供诊断：当前生效通路的位深（0 = 未知） */
    val endpointDepth: Int get() = exclusive.endpointDepth

    internal companion object {
        /** 进程内 `configure` 累计次数（诊断"时间轴反复复位"用） */
        @Volatile
        var configureCount: Int = 0
            private set

        /**
         * 进程内 `handleBuffer` 累计次数 —— **"数据真的到了出口"的唯一证据**。
         *
         * 上游拿它做恢复校验：`play()` 之后一段时间里这个数没动，说明渲染器根本没在跑
         * （或播放线程已经卡死），此时换引擎是唯一出路。`configureCount` 只能证明"出口被配置过"，
         * 证明不了"有样本流过去"。
         */
        @Volatile
        var bufferCalls: Int = 0
            private set

        /** sink 侧控制调用超时的时刻（毫秒，`System.nanoTime` 基准；0 = 从未超时） */
        @Volatile
        private var controlStallAtMs: Long = 0L

        /** 超时时的那句话（进日志用） */
        @Volatile
        private var controlStallNote: String = ""

        internal fun markControlStall(note: String) {
            controlStallAtMs = System.nanoTime() / 1_000_000L
            controlStallNote = note
        }

        /** 最近 [EngineHealth.STALL_WINDOW_MS] 内是否发生过控制调用超时 */
        internal fun controlStallRecent(windowMs: Long = EngineHealth.STALL_WINDOW_MS): Boolean =
            engineSuspectAt(controlStallAtMs, System.nanoTime() / 1_000_000L, windowMs)

        /** 换引擎之后清一次：否则一次几十秒前的卡顿会让之后每次计划变更都白换一条引擎 */
        internal fun clearControlStall() {
            controlStallAtMs = 0L
            controlStallNote = ""
        }

        internal fun lastControlStallNote(): String = controlStallNote

        @Volatile
        var outletAlive: Boolean = false
            private set

        /** 最近一次 configure 收到的格式：`率/编码/声道` */
        @Volatile
        var lastConfigureNote: String = ""
            private set

        /**
         * 换曲时清空 [lastConfigureNote]。
         *
         * 它是**进程级**的"最近一次 configure"，跨曲残留会把上一首的率带进这一首的状态行
         * （实测：DSD 那首显示 `cfglast=48000`，其实是前一首 48k 的普通曲目留下的），
         * 于是拿它当"这首的交付率"就会得出完全相反的结论。清空之后，空值表示
         * "这首还没 configure 过" —— 那是可信的信息，不是缺失。
         */
        fun clearConfigureNote() {
            lastConfigureNote = ""
        }

        // ── 我方交付量（位置计算的依据，不是诊断） ──
        //
        // 为什么位置要用它：这台 DAP 的 HAL 会进入一种会话级状态，让 `DefaultAudioSink`
        // 报的播放头在起播头几秒只按 0.5~0.67 倍前进，之后回到 1.000 倍 —— 那一段永久丢失
        // （实测 20 秒丢 1.845 秒，重启后同一首歌只丢 12 ms）。而"我方写进去多少字节"是硬的：
        // 实测 20 秒 5 775 360 B = 288 768 B/s = 48 000×6 的 1.0025 倍。
        /** 写进后端的累计时长（µs） */
        @Volatile
        var deliveredUs: Long = 0L
            private set

        /** 本首的帧宽与率，`counterAdd` 用它把字节换成时长 */
        @Volatile
        private var frameBytes: Int = 0

        @Volatile
        private var sampleRate: Int = 0

        internal fun counterBegin(rate: Int, bytesPerFrame: Int) {
            sampleRate = rate
            frameBytes = bytesPerFrame
            deliveredUs = 0L
        }

        internal fun counterAdd(bytes: Int) {
            if (bytes <= 0) return
            val fb = frameBytes
            val rate = sampleRate
            if (fb <= 0 || rate <= 0) return
            deliveredUs += bytes.toLong() * 1_000_000L / (fb.toLong() * rate.toLong())
        }
    }

    /** 独占通路里还有多少微秒没播出去（给界面判断"是否已进尾段"用；系统通路恒 0） */
    val pendingAudioUs: Long get() = if (port === exclusive) exclusive.pendingUs() else 0L
}

/**
 * 内置 DAC 直通流的控制调用执行器：**每次调用一条独立线程，绝不阻塞渲染线程**。
 *
 * 那些 native 调用（私有 ABI 的 `AudioTrack.pause()` / `flush()` / 析构）在部分 ROM 上
 * **不返回**（见 `NativeDapDac.close` 的实测记录）。
 *
 * ⚠️ **不能用单线程串行执行器**（第一版就是那么写的，实测 19:33 那份 trace 证明它更糟）：
 * 第一次 `pause()` 卡死不返回之后，执行器那唯一的线程就没了 ——
 * 于是紧随其后的 `play()`、`flush()`（= seek）、`release()` **全部排队排在一条永不出队的任务后面**，
 * 症状正是：暂停不立刻停（`playRequested=false` 立即生效，但设备把缓冲播完才停）、
 * 停了之后点播放毫无反应、进度条拉不动（seek 等 sink flush 永远等不到）。
 *
 * 所以：一次调用一条 daemon 线程，卡死只泄漏那一条线程，不影响任何其它动作。
 * 顺序性靠**调用方**保证（`playRequested` 是电平，写路径靠 `busy()` 让一让），
 * 不靠"队列次序"。`port` 的替换仍由 [gate] 与 [GATED_OPS] 保护 —— 那是唯一必须互斥的窗口。
 */
internal class DapControlManager(
    /** 与写路径互斥的锁（由 sink 持有；写侧用 `tryLock`，永不等） */
    private val gate: java.util.concurrent.locks.ReentrantLock,
) {

    /** 正在跑、且尚未超时的"必须先落地"动作数 */
    private val gating = AtomicInteger(0)

    /** 正在跑的调用：name -> 开始时刻（ms）。卡住的那条会一直留在这里，用于判超时 */
    private val running = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Volatile
    private var markedSince: Long = 0L

    fun submit(name: String, block: () -> Unit) {
        val starts = nowMs()
        val isGating = name.startsWith("flush") || name.startsWith("reset")
        running[name] = starts
        if (isGating) gating.incrementAndGet()
        Thread({
            try {
                block()
            } catch (t: Throwable) {
                dapTrace("L") { "control $name 抛异常：${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                running.remove(name)
                if (isGating) gating.decrementAndGet()
            }
        }, "dap-ctl-$name").apply { isDaemon = true }.start()
    }

    /**
     * 提交并**有界等待**（只给 `configure` 收旧后端 / 换通路拆流用：那里必须等它拆完）。
     *
     * 已经卡住时**不再等**：为一条不返回的调用等下去，就是把这个线程也变成卡住的线程。
     */
    fun runAndAwait(name: String, block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        val starts = nowMs()
        running[name] = starts
        Thread({
            try {
                block()
            } catch (t: Throwable) {
                dapTrace("L") { "control $name 抛异常：${t.javaClass.simpleName}: ${t.message}" }
            } finally {
                running.remove(name)
                latch.countDown()
            }
        }, "dap-ctl-$name").apply { isDaemon = true }.start()
        if (overdue()) return
        runCatching {
            latch.await(EngineHealth.CONTROL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    /**
     * 写路径要不要让一让。
     *
     * 只在**必须先落地的动作**（flush / reset）在跑、且还没超时时让；
     * 超时之后必须放行写入——否则一条卡死的 flush 会把整条流永久堵成静音（实测正是如此）。
     */
    fun busy(): Boolean = gating.get() > 0 && !overdue()

    /**
     * 是否有控制调用**已经超时**（= 那条 native 流卡住了）。
     *
     * 第一次超时时记进健康状态，播放层据此换引擎；之后同一个 op 不再重复记，
     * 否则"超时窗口"会被无限续期，永久压在每一次计划变更上。
     */
    fun overdue(): Boolean {
        val now = nowMs()
        var worstNote: String? = null
        var worstSince = 0L
        for ((name, since) in running) {
            if (now - since > EngineHealth.CONTROL_TIMEOUT_MS) {
                if (worstNote == null || since < worstSince) {
                    worstNote = name
                    worstSince = since
                }
            }
        }
        val note = worstNote ?: return false
        if (markedSince != worstSince) {
            markedSince = worstSince
            AnnieAudioSink.markControlStall(
                "$note 超过 ${EngineHealth.CONTROL_TIMEOUT_MS}ms 未返回",
            )
            dapTrace("L") {
                "control stall: $note（${EngineHealth.CONTROL_TIMEOUT_MS}ms 未返回）"
            }
        }
        return true
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
