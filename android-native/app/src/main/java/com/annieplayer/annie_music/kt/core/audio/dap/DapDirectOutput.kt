package com.annieplayer.annie_music.kt.core.audio.dap

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.annieplayer.annie_music.kt.core.audio.ExclusiveClock
import com.annieplayer.annie_music.kt.core.audio.SinkKind
import com.annieplayer.annie_music.kt.core.audio.OutputPort
import com.annieplayer.annie_music.kt.core.audio.UacClock
import com.annieplayer.annie_music.kt.core.audio.UacRing
import java.nio.ByteBuffer

/**
 * DAP 内置 DAC 直通作为一条 [OutputPort]。
 *
 * ## 为什么自己管流，而不是像系统通路那样包一层 `DefaultAudioSink`
 *
 * `DefaultAudioSink` 的 `AudioFormat` 只认 Media3 的公开 encoding，私有 `audio_format_t`
 * 根本传不进去（framework 的 `AudioFormat.isEncodingValid` 会拒）。所以要自己开流。
 *
 * ## 为什么复用 `UacRing` / `UacClock`
 *
 * 它们的输入是**帧数**和**微秒**，不认 USB。水位（写入/已播/在飞）与时间轴（零点/首块/
 * 单调/不连续）这一组状态错了三次，每次都是时机问题 —— 把时机再抄一份到这里，
 * 等于把已经踩平的坑重新挖一遍。
 *
 * 与 USB 独占的唯一区别是**在飞**：AudioTrack 的缓冲已经算在"写入"里，没有 URB 那 63 ms，
 * 所以 [hasPendingData] 不扣 `UacRing.inFlightFrames`。
 *
 * ## 为什么 `write` 用非阻塞
 *
 * 阻塞写会在缓冲满时把 Media3 的渲染线程按在那里，流一旦异常就永远醒不过来。
 * 非阻塞返回 0 时按 [OutputPort] 的约定返回 `false`（"这块没收，用同一块重试"），
 * Media3 自己会退让。
 */
@OptIn(UnstableApi::class)
internal class DapDirectOutput(
    private val sink: DapSink,
    private val spec: DapStreamSpec,
    /** 首块数据到达时通知上游（`AnnieAudioSink` 用它把 `routingKnown` 置上） */
    private val onPositionAdvancing: () -> Unit = {},
    /**
     * 硬件播放头可不可信 —— 来自探测的 `DapFormatVerdict.positionOk`。
     *
     * 实测飞傲 JM21：同一条 DSD 候选两次跑，`getPosition` 一次前进 879 帧、一次返回
     * `-38`（`INVALID_OPERATION`）。所以位置上报**不能假定可用**；不可用时这条通路
     * 仍要能播，只是时钟改用"写入量 + 墙上时钟"自己推。
     */
    private val hardwarePosition: Boolean = true,
    /** 便于单测注入 */
    private val nanoTime: () -> Long = System::nanoTime,
    /** 流被释放时回调（厂商模式复位用：不复位下一次普通 PCM 会带着 DSD 的 format 进 HAL） */
    private val onClosed: () -> Unit = {},
    /**
     * [`open`] 失败时回调。
     *
     * 必须回调而不是"静默返回 false"：上游这时会退到系统通路，而送的是 **raw DSD 帧**
     * —— 那在 mixer 里不是"音质变差"，是满幅噪声。所以上层要据此记住这个档位失败过，
     * 并把音源按 PCM 重建一次。
     */
    private val onOpenFailed: () -> Unit = {},
) : OutputPort {

    override val path: SinkKind get() = SinkKind.dsdDirect

    /** 每样本位数（容器宽度 × 8）：32 = 32 bit word、16 = 16 bit 容器。上报路由时用 */
    val depthBits: Int
        get() = if (spec.channelCount > 0) spec.bytesPerFrame / spec.channelCount * 8 else 0

    /**
     * 缓冲容量，换算成**音频帧**。
     *
     * `spec.frameCount` 的单位随 format 变（见 [DapStreamSpec.frameCountInBytes]）：
     * 私有 DSD format 是"framework 帧 = 1 字节"，PCM 就是音频帧本身。
     * 这里统一换算一次，下面两处（交给设备的块大小、排空预算）都用音频帧。
     */
    private val bufferAudioFrames: Int
        get() = when {
            spec.bytesPerFrame <= 0 -> 0
            spec.frameCountInBytes -> spec.frameCount / spec.bytesPerFrame
            else -> spec.frameCount
        }

    private val ring = UacRing()
    private val clock = UacClock()

    /** 自管节奏的写入器（`open()` 时建，`release()` 时停） */
    @Volatile
    private var writer: SelfPacedWriter? = null

    /**
     * 已经 `release()` 过。**释放之后这个对象一个字段都不许再驱动设备。**
     *
     * 为什么需要它：`SelfPacedWriter.stop()` 只保证"不会再主动调度"，它拦不住
     * 一次**已经在飞**的 `write`。那条路径拿到已关闭的句柄后会返回 `-1`，
     * 被 `SelfPacedWriter` 当成致命错误 → `onFatal` → 上游把这一档 native 记成
     * "不支持"并重建 —— 而这完全是我方收尾顺序造成的假故障（实测）。
     * 所以释放后 `writeToDevice` 一律回 `0`（设备暂时不收），致命回调也不再上报。
     */
    @Volatile
    private var released = false

    /**
     * 硬件播放头可不可信 —— **运行时判据，不是探测的一次性结论**。
     *
     * 探测里 `framesAdvanced` 是"起播后读数 − 起播前读数"，早期版本把前半段硬编码成 0，
     * 只要那一刻 `getPosition` 返回 0 就判"位置不可用"（`positionOk=false`），于是整条流
     * 改用软时钟。而实测真实播放里 `playbackFrames()` 是按实时走的（0 → 1,413,279）。
     *
     * 软时钟的代价不是精度：它**跟设备有没有在取数完全无关**，`hasPendingData()` 会在
     * `written == played` 时翻成 false，等于告诉 Media3"没数据了"。
     *
     * 所以这里改成**只升级**：一旦观察到播放头前进过，就切硬件时钟；
     * 反向不做（避免在停顿时来回震荡）。
     */
    @Volatile
    private var hwUsable: Boolean = hardwarePosition
    private var hwPrev = 0L

    private fun refreshHwUsable() {
        // **这台 HAL 的播放头不可信，一律用软时钟。**
        //
        // 实测证据（JM21，`0x1A000002 @ 88200`）：`getPosition()` 会报 0、报 -38、
        // 还会在 2^32 附近回绕（一次跳到 4,295,427,535）。拿它当位置源的结果就是
        // 「进度条乱跳 / 拉不动 / 末尾跑满」—— 而与音频数据本身无关。
        // 软时钟的基准是"从开始播到现在过了多少纳秒"，这些私有端口实测是**持续实时排空**的
        // （非阻塞写一直有进展），所以软时钟就是正确的时间轴，且单调。
        if (!TRUST_HARDWARE_CLOCK) return
        if (!sink.isOpen) return
        val hw = runCatching { sink.playbackFrames() }.getOrDefault(-1L)
        if (hw > hwPrev) {
            hwPrev = hw
            if (!hwUsable) {
                hwUsable = true
                dapTrace("X") { "硬件时钟可用（播放头到 $hw），切离软时钟" }
            }
        }
    }

    /** 诊断：write 被调用了几次、positionUs 上一次采样时刻（定位用，事后删） */
    private var traceWrites = 0
    private var tracePosAtMs = 0L
    private var tracePendingLast = -2L
    @Volatile private var watchdogStarted = false
    @Volatile private var watchdogStop = false

    private var playRequested = false
    private var timeBaseNeedsSync = false
    /** 首块数据落盘后补过一次 `start()` 没有（见 `write` 里的说明） */
    private var kickedAfterData = false

    // 软件时钟：只有 hardwarePosition = false 时用。基准是"从开始播到现在过了多少纳秒"，
    // 因为实测这些私有端口**持续排空**（非阻塞写一直有进展），消费速率就是实时速率。
    private var softPlaying = false
    private var softAnchorNs = 0L
    private var softAnchorFrames = 0L

    /** 开流；失败时上层按"这条路不成立"降级，不要重试同一组参数 */
    fun open(): Boolean {
        ring.rebase(0L)
        clock.reset()
        timeBaseNeedsSync = false
        dapTrace("O") {
            "open fmt=0x${spec.encoding.toString(16)} rate=${spec.sampleRate} " +
                "bpf=${spec.bytesPerFrame} frames=${spec.frameCount} flags=${spec.outputFlags} " +
                "hw=${hwUsable}"
        }
        if (!sink.open(spec)) {
            dapTrace("O") { "open FAILED note=${NativeDapDac.lastOpenNote}" }
            onOpenFailed()
            return false
        }
        dapTrace("O") { "open OK stages=${NativeDapDac.nativeStageResults()}" }
        startWatchdog()
        // 基准取**当前读数**（新流是 0，但语义与 `UacRing` 一致：永远不要假定计数器从 0 起）
        ring.rebase(absolutePlaybackFrames())
        clock.reset()
        // 自管节奏的写入器：环 0.5 s、每次最多交给设备"一个缓冲"的量。
        // 从这里开始，设备拒收不再打回 Media3 —— 见 `SelfPacedWriter` 的说明。
        writer = SelfPacedWriter(
            // 环容量 2 秒：Media3 一次可能给一整秒的块，环必须能整块接住，
            // 否则 `write` 就要返回 partial，等于把背压推回给 Media3（那正是要消除的）。
            // 代价：88200 × 8 B × 2 s ≈ 1.4 MB，可接受。
            capacityFrames = spec.sampleRate * 2,
            bytesPerFrame = spec.bytesPerFrame,
            deviceChunkFrames = bufferAudioFrames,
            name = "dap-writer",
            // `-11`（`EWOULDBLOCK`）= 设备暂时不收，**不是流坏了** —— 必须映射成 0 让它等，
            // 否则第一次"暂时不收"就会被 `SelfPacedWriter` 当成致命错误：
            // 环被清空 → 上报 open 失败 → 记 native 失败 → 判定降 pcm → 内置 DAC 模式下直接没后端。
            // 实测就是这样"播放失败"的。
            writeToDevice = { buf ->
                if (released) {
                    0
                } else {
                    val r = runCatching { sink.write(buf) }.getOrDefault(-1)
                    if (r == -11) 0 else r
                }
            },
            onDeviceWritten = { bytes ->
                val f = (bytes / spec.bytesPerFrame).toLong()
                if (f > 0L) ring.onWritten(f)
            },
            // **只在没有释放时才上报。** 释放之后出现的 `-1` 是"句柄已经被我们关了"，
            // 不是设备接不住这个档位 —— 记成失败会让这一档 native 永久被跳过（实测）。
            onFatal = { if (!released) onOpenFailed() },
        )
        // **不在这里起线程**：`SelfPacedWriter` 只在设备第一次拒收时才拉起后台线程。
        // 设备健康时没有额外线程，行为与同步写一致。
        return true
    }

    override fun write(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (released || !sink.isOpen || spec.bytesPerFrame <= 0) return false
        val remaining = buffer.remaining()
        if (remaining < spec.bytesPerFrame) {
            // 不足一帧：AudioTrack 会拒。上层不该这么喂，但真发生就丢掉这截，
            // 不要返回 false —— 返回 false 会让 Media3 用同一块无限重试。
            buffer.position(buffer.position() + remaining)
            return true
        }

        // **交给自管写入器，不再直写设备。**
        //
        // 以前这里同步 `sink.write(buffer)`，设备返回 -11 就 `return false` 让 Media3
        // 重试同一块 —— 实测一次播放能撞 3883 次，既浪费又让"设备卡顿"直接变成
        // "播放器卡住"。现在由 `SelfPacedWriter` 吸收：环里先接住，后台按设备节奏喂。
        // 它内部会先同步试喂一次，所以设备健康时行为与旧实现一致（单测也能照跑）。
        val accepted = writer?.offer(buffer) ?: 0
        traceWrites++
        if (traceWrites <= 80 || traceWrites % 40 == 0 || accepted < remaining) {
            dapTrace("W") {
                "n=$traceWrites in=$remaining accepted=$accepted rem=${buffer.remaining()} " +
                    "queued=${writer?.pendingBytes() ?: -1} written=${ring.writtenFrames} " +
                    "played=${playedFramesNow()} hw=${hwUsable} " +
                    "hwPos=${runCatching { sink.playbackFrames() }.getOrDefault(-1)}"
            }
        }
        if (accepted <= 0) return false

        // **第一块数据落进设备之后补一次 `start()`。**
        //
        // `AudioTrack` 在"已 start 但缓冲是空的"那一刻会把自己退回 STOPPED，之后再写
        // 只填缓冲、没人取 —— 而部分 ROM 的 `getPosition` 仍会随服务端写入量前进，
        // 于是上层看到"位置在走、进度正常"，实际一个采样都没到 DAC。
        if (!kickedAfterData && playRequested) {
            kickedAfterData = true
            runCatching { sink.play() }
            dapTrace("L") {
                "kick start（数据已入队）stages=${NativeDapDac.nativeStageResults()}"
            }
        }

        if (timeBaseNeedsSync) {
            timeBaseNeedsSync = false
            clock.onDiscontinuity(presentationTimeUs)
        }
        if (clock.onWritten(presentationTimeUs)) {
            onPositionAdvancing()
        }
        // **必须如实回答"这块收完没有"**：`OutputPort` 的契约是 true = 整块已收下
        // （实现方负责推进 `buffer.position`）。设备缓冲满的时候只写进去一部分，
        // 返回 true 会让 Media3 直接丢掉剩下的字节 —— 那是静默丢音，
        // 而且会让"写入总量"永远等于"设备容量"，把真实缺口掩盖掉。
        // 兄弟实现 `ExclusiveOutput` 就是按 `!buffer.hasRemaining()` 判的。
        return !buffer.hasRemaining()
    }

    override fun positionUs(sourceEnded: Boolean): Long {
        if (!sink.isOpen) return C.TIME_UNSET
        val played = minOf(playedFramesNow(), ring.writtenFrames)
        val out = clock.positionUs(framesToUs(played)) ?: C.TIME_UNSET
        val now = nanoTime() / 1_000_000L
        if (now - tracePosAtMs >= 100L) {
            tracePosAtMs = now
            dapTrace("P") {
                "posUs=$out played=$played ring=${ring.writtenFrames} " +
                    "hwPos=${runCatching { sink.playbackFrames() }.getOrDefault(-1)} " +
                    "hw=${hwUsable} play=$playRequested ended=${sourceEnded} " +
                    "pending=${hasPendingData()}"
            }
        }
        return out
    }

    private fun framesToUs(frames: Long): Long = ExclusiveClock.framesToUs(frames, spec.sampleRate)

    /**
     * 看门狗：**不依赖 `write` / `positionUs` 被调用**，每 250 ms 记一行。
     *
     * 为什么必须有：其它所有采样点都是"被数据流驱动"的。一旦 Media3 停止喂数，
     * `W` 和 `P` 同时消失，恰好是唯一需要观测的时刻。这一行是唯一能区分
     * 「我方没喂」和「设备不取」的连续序列。
     */
    private fun startWatchdog() {
        if (!dapTraceEnabled || watchdogStarted) return
        watchdogStarted = true
        Thread({
            var n = 0
            while (!watchdogStop && n < 240) {
                n++
                dapTrace("K") {
                    "written=${ring.writtenFrames} played=${playedFramesNow()} " +
                        "hwPos=${runCatching { sink.playbackFrames() }.getOrDefault(-1)} " +
                        "hw=${hwUsable} softPlaying=$softPlaying play=$playRequested " +
                        "open=${sink.isOpen} pending=${runCatching { hasPendingData() }.getOrDefault(false)} " +
                        "stages=${runCatching { NativeDapDac.nativeStageResults() }.getOrDefault("")}"
                }
                try {
                    Thread.sleep(250L)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "dap-watchdog").apply { isDaemon = true }.start()
    }

    /**
     * 开流以来的**绝对**播放头（`UacRing.rebase` 的输入）。
     *
     * 这里**不夹**"已写入量"：两者坐标系不同（这个读数是开流累计的绝对值，
     * `ring.writtenFrames` 是相对上一次 rebase 的增量）。夹在 [playedFramesNow] 里，
     * 夹的是**差值**。
     */
    private fun absolutePlaybackFrames(): Long = sink.playbackFrames().coerceAtLeast(0L)

    /**
     * 本段已播帧（相对 [resetClock] 留下的基准）。
     *
     * 必须走 `UacRing.playedFrames` 做减法：播放头是**累计**读数，flush/seek 之后继续往前走，
     * 直接拿它算位置会一步跳到"从开流到现在"的位置（实测表现是 seek 后进度条跳到几分钟）。
     */
    private fun playedFramesNow(): Long {
        refreshHwUsable()
        val raw = if (hwUsable) {
            ring.playedFrames(absolutePlaybackFrames())
        } else {
            softFrames()
        }
        // **本段已播不可能超过本段已写**。这条对读数的脏值是硬约束：
        // 实测 `getPosition` 抖动能让它一次跳到 4,295,427,535（≈2^32），
        // 那时 `ring.writtenFrames` 才六十几万 —— 不夹就会让 `hasPendingData` 恒 false，
        // Media3 认为播完了，再也不喂数。
        return minOf(raw, ring.writtenFrames)
    }

    /** 软件时钟的当前读数（本段累计帧） */
    private fun softFrames(): Long {
        if (spec.sampleRate <= 0) return softAnchorFrames
        if (!softPlaying) return softAnchorFrames
        val ns = nanoTime() - softAnchorNs
        if (ns <= 0L) return softAnchorFrames
        return softAnchorFrames + ns * spec.sampleRate / 1_000_000_000L
    }

    private fun anchorSoft() {
        if (softPlaying) return
        softAnchorNs = nanoTime()
        softPlaying = true
    }

    private fun freezeSoft() {
        if (!softPlaying) return
        softAnchorFrames = softFrames()
        softPlaying = false
    }

    override fun play() {
        playRequested = true
        // **先锚时钟、再碰 native。** 实测 `pause()`/`flush()` 在这台 HAL 的私有 DSD 端口上
        // 会不返回（trace：`control stall: pause（800ms 未返回）`），所以凡是"卡住就再也回不来"
        // 的顺序都必须让记账排在最前面 —— 否则恢复播放之后位置永远停在暂停时那一刻。
        anchorSoft()
        // 恢复喂数据（暂停是靠停喂实现的，不靠 `AudioTrack.pause()`，见 `SelfPacedWriter.feeding`）
        writer?.setFeeding(true)
        // `flush()` 之后流是暂停态，恢复只有 `play()`；而发送首块时的那次 kick 只看
        // `kickedAfterData`，一次 flush 之后它必须是 false，否则 seek 完再也不会 start。
        kickedAfterData = false
        dapTrace("L") {
            "play open=${sink.isOpen} ring=${ring.writtenFrames} " +
                "stages=${NativeDapDac.nativeStageResults()}"
        }
        if (sink.isOpen) sink.play()
        dapTrace("L") {
            "play→stages=${NativeDapDac.nativeStageResults()} hwPos=${sink.playbackFrames()}"
        }
    }

    override fun pause() {
        playRequested = false
        dapTrace("L") { "pause ring=${ring.writtenFrames} played=${playedFramesNow()}" }
        freezeSoft()
        // **暂停的第一手段是"停喂"**：这一步同步生效，不依赖 native `pause()` 返回。
        writer?.setFeeding(false)
        if (sink.isOpen) sink.pause()
    }

    override fun flush(playRequested: Boolean) {
        dapTrace("L") { "flush play=$playRequested ring=${ring.writtenFrames}" }
        // **顺序即正确性**：记账（环、时钟、意图）全部同步做完，native 排最后。
        // 反过来的话，一次卡住的 `sink.flush()` 会让时钟永不复位 —— 表现就是
        // "拖进度条没反应 / 位置停在旧值"，而数据其实已经在重新灌了。
        writer?.reset()
        resetClock()
        this.playRequested = playRequested
        kickedAfterData = false
        // **只在带播放意图时打开喂数据；关掉的动作归 `pause()`。**
        // 暂停中的一次 seek 也会走到这里（`playRequested = false`），但那时 `feeding`
        // 已经被 `pause()` 置 false 了，这里再置一次是多余；更重要的是"seek 后是否喂"
        // 不该由 flush 决定 —— 那会让"暂停中拖进度条"顺手把喂数据打开/关掉，语义重复。
        if (playRequested) writer?.setFeeding(true)
        if (playRequested) anchorSoft()
        if (sink.isOpen) {
            sink.flush()
            // `AudioTrack.flush()` 之后流是暂停态，恢复只有 `play()` 一条路径
            if (playRequested) sink.play()
        }
    }

    override fun onTimelineDiscontinuity() {
        if (!sink.isOpen) return
        timeBaseNeedsSync = true
    }

    override fun reset() {
        // 同样先记账、后 native（理由见 `flush`）
        writer?.reset()
        resetClock()
        playRequested = false
        timeBaseNeedsSync = false
        kickedAfterData = false
        writer?.setFeeding(false)
        if (sink.isOpen) {
            sink.pause()
            sink.flush()
        }
    }

    /**
     * 时钟复位的**唯一入口**（open / flush / reset 都走这里）。
     *
     * 基准取当前读数而不是 0：`playbackHeadPosition` 系列在 flush 之后并不归零，
     * 置 0 就把"这条流从开到现在的累计帧数"当成了本首已播帧。
     */
    private fun resetClock() {
        ring.rebase(absolutePlaybackFrames())
        clock.reset()
        softAnchorFrames = 0L
        softPlaying = false
    }

    override fun release() {
        watchdogStop = true
        // **先关闸、再停线程、最后关流。** 顺序反过来就是"线程拿坏句柄写"：
        //  `stop()` 只是 `running=false + interrupt`，卡在 native `write` 里的那一轮
        // 会继续跑完；此时 `sink.close()` 已经把句柄置零 → 那一轮拿到 `-1` →
        // `onFatal` → 上游记 native 失败 → 判定降 PCM（实测症状：切回系统通路没声，
        // 必须重启 App）。现在 `released` 先置上（写路径直接回 0），`stop()` 内部
        // join 等线程退出，之后才 `sink.close()`。
        released = true
        writer?.stop()
        writer = null
        freezeSoft()
        softAnchorFrames = 0L
        sink.close()
        ring.rebase(0L)
        clock.reset()
        playRequested = false
        onClosed()
    }

    /**
     * 输入结束：等已写进 AudioTrack 的数据全部播出去。
     *
     * 不等的话 Media3 会立刻换下一首，新流的 `open()` 把缓冲冲掉 —— 听感就是"最后几秒没声"。
     * 预算按**缓冲真实时长**算（`frameCount / sampleRate`），再留 1 s 余量；
     * 暂停中不排（用户按了暂停，剩的就该剩着）。
     */
    override fun playToEndOfStream() {
        val budgetMs = drainBudgetMs()
        var waited = 0L
        while (sink.isOpen && playRequested && hasPendingData() && waited < budgetMs) {
            Thread.sleep(POLL_MS)
            waited += POLL_MS
        }
        clock.markEnded()
    }

    private fun drainBudgetMs(): Long {
        val audioFrames = bufferAudioFrames.toLong()
        val bufferMs = if (spec.sampleRate > 0) {
            audioFrames * 1000L / spec.sampleRate
        } else {
            0L
        }
        return (bufferMs + 1000L).coerceAtMost(MAX_DRAIN_MS)
    }

    override fun isEnded(): Boolean = clock.ended && !hasPendingData()

    /**
     * 还有没播出去的数据。
     *
     * **不扣在飞**：与 USB 独占不同，AudioTrack 的内部缓冲已经算进 `writtenFrames`，
     * 再扣一次会把 `isEnded` 判早（正是 USB 那侧要扣 63 ms 的反面）。
     */
    override fun hasPendingData(): Boolean {
        // 释放之后一律"没有数据"：否则 Media3 会一直等一条已经拆掉的流，
        // 表现成"进度条不动、也不换下一首"。
        if (released) return false
        // 两个来源都要算：环里排队还没交给设备的，和已经交给设备还没播出去的。
        // 以前只看后者，且用软时钟推"已播"——设备停了也报"没数据了"，Media3 于是不再喂数。
        val queued = writer?.pendingBytes() ?: 0
        val has = sink.isOpen && (queued > 0 || ring.writtenFrames > playedFramesNow())
        val p = if (has) 1L else 0L
        if (p != tracePendingLast) {
            tracePendingLast = p
            dapTrace("H") {
                "hasPending=$has written=${ring.writtenFrames} played=${playedFramesNow()} " +
                    "hw=${hwUsable} softPlaying=$softPlaying"
            }
        }
        return has
    }

    override fun isHealthy(): Boolean = sink.isOpen

    private companion object {
        /**
         * 要不要信这个端口的播放头（`getPosition()`）。
         *
         * **true = 保留硬件时钟（当前取值），但它只用于"本段已播多少"，不用于"绝对位置"。**
         *
         * 位置错乱的真正原因是**坐标系**：`UacClock` 把 Media3 给的绝对时间戳当零点，
         * 而那个时间戳带本机常量偏移 `+1e12 µs`（界面于是显示"11.6 天"，看起来偏快/乱）。
         * 那一层已经改成差分域（见 `UacClock.epochUs`），所以播放头本身可以继续用：
         * 它的读数只参与 `playedFramesNow()` 的**增量**，而且外面还夹着 `ring.writtenFrames`。
         *
         * 若将来发现某台机器的播放头会跳变到把增量算错，把这里置 false 即退回纯软时钟
         * （软时钟只依赖 `nanoTime()`，单调，代价是不跟随设备真实进度）。
         */
        const val TRUST_HARDWARE_CLOCK = true

        const val POLL_MS = 2L
        const val MAX_DRAIN_MS = 10_000L
    }
}
