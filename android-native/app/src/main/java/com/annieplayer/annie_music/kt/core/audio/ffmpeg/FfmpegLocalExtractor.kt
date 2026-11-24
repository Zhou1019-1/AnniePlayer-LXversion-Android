package com.annieplayer.annie_music.kt.core.audio.ffmpeg

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.annieplayer.annie_music.kt.core.audio.dap.DapPackOverride
import com.annieplayer.annie_music.kt.core.audio.dap.dapTrace
import com.annieplayer.annie_music.kt.core.audio.dsd.DopPacker
import com.annieplayer.annie_music.kt.core.audio.dsd.DsdTransport
import com.annieplayer.annie_music.kt.core.audio.dsd.NativeDsdPacker
import java.io.IOException

/**
 * 本地全链路的 Media3 入口：把 [NativeFfmpegDemux] 解出来的 PCM 当成 `AUDIO_RAW` 样本喂给 Media3。
 *
 * 为什么用 Extractor 而不是自己写 MediaSource：Extractor 是 Media3 里"往上层供样本"的最小契约，
 * 接进来之后**时间轴、seek 校验、缓冲、AudioSink、MediaSession 全部仍然由 Media3 负责**，
 * 我们只替换"样本从哪来"。这比另起一个播放引擎（计划附录 B 提到的第二个 backend）风险小得多。
 *
 * seek 的坐标约定（关键）：`SeekMap` 返回的 `position` 单位就是**微秒**，
 * `read()` 里拿到的 `seekPosition.position` 直接当微秒丢给 native，不做任何换算。
 *
 * @param path 原始文件绝对路径（不经转码）
 * @param targetSampleRate native 的输出采样率；0 = 保持源率
 * @param outBits 16 或 24
 * @param keepSourceRate **native 不做重采样**（DSD 专用）。此时 [targetSampleRate] 被忽略，
 *   native 保持源率（DSD64 = 352800）交给下游 r8brain 抽取，见 `NativeFfmpegDemux.open`。
 * @param dsdTargetRate DSD 的**最终**目标率；0 = 不是 DSD。只用于回报
 *   （`FfmpegOutputStream.Spec.dsdTargetRate` 决定"要不要我方重采样"）。
 * @param dsdTransport DSD 的传输方式。非 [DsdTransport.pcm] 时本类**不解码**，
 *   直接把原始 1-bit 字节打包成 DoP / raw 帧交给 Media3 —— Media3 看到的是普通
 *   `AUDIO_RAW` 样本，时间轴 / seek / 缓冲 / MediaSession 全部照旧由它负责。
 */
@OptIn(UnstableApi::class)
internal class FfmpegLocalExtractor(
    private val path: String,
    private val targetSampleRate: Int,
    private val outBits: Int,
    private val keepSourceRate: Boolean = false,
    private val dsdTargetRate: Int = 0,
    private val dsdTransport: DsdTransport = DsdTransport.pcm,
    /**
     * raw 模式下 raw DSD alt 的字节序。`false` = `DSD_U32_BE`（字节顺序 = 时间顺序，
     * 不换位），`true` = `DSD_U32_LE`（每 4 字节反转）。
     *
     * **默认 BE**：XMOS 系（iFi iDSD / DIYINHK / 绝大多数小尾巴）的 native DSD 端点是
     * `DSD_U32_BE` —— ALSA 早年把它们标成 `DSD_U32_LE` 是个 bug，后来专门修过
     * （Jussi Laako, `Add big-endian DSD sample formats and fix XMOS DSD sample format`）。
     * 标错的症状正是满幅噪声。真遇到 LE 设备再把这里改 `true`。
     */
    private val nativeLittleEndian: Boolean = false,
) : Extractor {

    private var handle: Long = 0L
    private var trackOutput: TrackOutput? = null

    /** 诊断：readRaw 被调用了几次（定位用，事后删） */
    private var traceReads = 0
    private val pcm = ByteArray(SAMPLE_BUFFER_BYTES)
    private val meta = LongArray(1)

    /** raw 模式的输出：native 已经封装好的 DoP / Native 线格式帧 */
    private val rawPacked = ByteArray(SAMPLE_BUFFER_BYTES * 2)

    /** `openRaw` 回填的文件自身位序：DSF 是 LSB-first → 需要逐字节位反转 */
    private var fileBitReverse = false

    /** 已经落地的线格式；就地重开时用来对比并进 trace */
    private var appliedPack: DapPackOverride.Choice? = null

    /** 还要补送多少字节的 DSD 静音（见 `emitPad`） */
    private var padRemaining = 0

    /** 出口线格式的一帧字节数（Native = 8）；补头要按它凑整帧 */
    private var specBytesPerFrame = 8

    /** 上一轮记过的剩余补头量（只为首尾各记一行 trace） */
    private var tracePadLeft = 0L

    private var ptsBaseUs: Long = Long.MIN_VALUE
    private var ptsSeedUs: Long = 0L

    /** 最近一次 native 报的 pts —— 重开的 `Y` 行拿它当"归零前"的证据 */
    private var lastPtsUs = 0L

    private var durationUs: Long = 0L

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        // 5 个槽：raw 入口把文件自身的位序回填在 `[4]`（`[0..3]` 与 PCM 入口共用）
        val info = IntArray(5)
        // raw DSD（DoP / Native）走独立 JNI 入口：只解封装、不解码。
        // 不能给 `open` 加参数 —— JNI 签名一旦两边产物不一致，native 会按错位读参数直接崩。
        val h = if (dsdTransport.isRaw) {
            NativeFfmpegDemux.openRaw(path, info)
        } else {
            NativeFfmpegDemux.open(path, targetSampleRate, outBits, info, keepSourceRate)
        }
        if (h == 0L) throw IOException("ffmpeg demux 打不开: $path")
        handle = h
        // 线格式封装在 native 里做：拆声道 → 位反转 → 打 DoP / Native 帧一次完成。
        // 封装后的字节流才是 Media3 眼里的"样本"，所以必须在这里就定下来。
        if (dsdTransport.isRaw) {
            fileBitReverse = info[4] != 0
            val pack = resolvePack()
            appliedPack = pack
            NativeFfmpegDemux.configRawPackEx(h, rawPackMode(pack), pack.bigEndian, pack.bitReverse)
            // 补头只对内置 DAC 有意义（USB 端点不丢字节，见 `emitPad`）
            specBytesPerFrame = when {
                // 16 bit 容器：每声道 2 字节 → 一帧 4 字节
                dsdTransport == DsdTransport.native && DapPackOverride.container16 -> 4
                dsdTransport == DsdTransport.native -> NativeDsdPacker.BYTES_PER_PAIR
                else -> DopPacker.BYTES_PER_FRAME
            }
            padRemaining = if (dsdTransport == DsdTransport.native) DapPackOverride.padBytes else 0
            dapTrace("Y") {
                "pack=${pack.label} be=${pack.bigEndian} rev=${pack.bitReverse} " +
                    "byte=${if (pack.byteInterleaved) 1 else 0} " +
                    "mode=${rawPackMode(pack)} fileRev=${if (fileBitReverse) 1 else 0} " +
                    "pad=$padRemaining open=1"
            }
        }

        val channels = info[1].takeIf { it > 0 } ?: 2
        // `info[0]` 的含义随入口而变：PCM 路径是输出网格率，raw 路径是 **1 bit 采样率**。
        val rawBitRate = info[0].takeIf { it > 0 } ?: 2_822_400
        val sampleRate: Int
        val bits: Int
        when (dsdTransport) {
            DsdTransport.dop -> {
                // DoP 交给端点的就是 PCM 采样率：每个 24 bit 帧装 16 个 1-bit 样本
                sampleRate = DopPacker.packetRate(rawBitRate)
                bits = NativeFfmpegDemux.BITS_24
            }
            DsdTransport.native -> {
                // 端点看到的是**容器 word 率**，而它由容器宽度决定：
                //   32 bit 容器（0x1A000002）→ bitRate/32，DSD64 = 88200（海贝的映射表也是这个）
                //   16 bit 容器（0x1A000001）→ bitRate/16，DSD64 = 176400
                // 两者数据率相同（705 600 B/s），只是"一帧多少字节"不同。
                val divisor = if (DapPackOverride.container16) 16 else 32
                sampleRate = if (rawBitRate > 0) rawBitRate / divisor else 0
                bits = if (DapPackOverride.container16) 16 else 32
            }
            DsdTransport.pcm -> {
                sampleRate = info[0].takeIf { it > 0 } ?: 44100
                bits = info[2]
            }
        }
        durationUs = info[3].coerceAtLeast(0).toLong() * 1000L
        // 真实输出规格回报给播放页（「输出」胶囊据此显示，而不是拿源字段冒充）。
        // DSD 时 `sampleRate` 是**中间格式**（352800），最终率在 `dsdTargetRate` 里 ——
        // 两者一起交出去，"要不要我方重采样"由后者判（见 `Spec` 的注释）。
        FfmpegOutputStream.publish(path, sampleRate, bits, channels, dsdTargetRate, dsdTransport)

        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        track.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(
                    when {
                        bits >= 32 -> C.ENCODING_PCM_32BIT
                        bits == NativeFfmpegDemux.BITS_24 -> C.ENCODING_PCM_24BIT
                        else -> C.ENCODING_PCM_16BIT
                    },
                )
                .setSampleRate(sampleRate)
                .setChannelCount(channels)
                // 码率决定 `LoadControl` 的目标缓冲字节数。缺了它就只剩时间维度，
                // 2.1 MB/s 的 DSD 会按 maxBufferMs 目标缓冲几十秒的样本。
                .setAverageBitrate(bitrateOf(sampleRate, channels, bits))
                .setMaxInputSize(SAMPLE_BUFFER_BYTES)
                .build(),
        )
        output.endTracks()
        output.seekMap(simpleSeekMap())
        trackOutput = track
    }

    private fun simpleSeekMap(): SeekMap = object : SeekMap {
        override fun isSeekable(): Boolean = true

        /**
         * 必须写 `this@FfmpegLocalExtractor.durationUs`。
         *
         * `SeekMap` 是 Java 接口，Kotlin 把它的 `getDurationUs()` 合成了一个属性
         * `durationUs`；匿名对象里裸写 `durationUs` 会解析到自己身上（= 调 `getDurationUs()`），
         * 直接无限递归 → `StackOverflowError` 崩溃。
         */
        override fun getDurationUs(): Long = this@FfmpegLocalExtractor.durationUs

        override fun getSeekPoints(positionUs: Long): SeekMap.SeekPoints =
            SeekMap.SeekPoints(SeekPoint(positionUs, positionUs))
    }

    /**
     * 线格式的**唯一**判定处：默认值（设备品类 + 文件自身位序）交给 [DapPackOverride.compose]
     * 与 `files/dsd-pack.txt` 合并。字序与位序必须一起取 —— `rotate` 会推进指针。
     */
    private fun resolvePack(): DapPackOverride.Choice = DapPackOverride.compose(
        defaultBigEndian = !nativeLittleEndian,
        // 位序不是猜的：`openRaw` 按 `codec_id` 回填（DSF = LSB-first → 要反转成 MSB 优先）。
        // 依据是真实数据量出来的带外能量（LSB 优先 +43 dB），不是"两种字序都噪声"的反推。
        defaultBitReverse = fileBitReverse,
    )

    /**
     * 封装模式。
     *
     * **`byteInterleaved` = 按字节交错**，走的正是 native 的 `PACK_OFF`（裸交错）：
     * `[L0 R0 L1 R1 | L2 R2 L3 R3]` 原样输出。这一维此前一次都没试过 ——
     * 四种 `be/le × rev/norev` 只改"字内字节顺序"，没改"交错粒度"。
     * 若 HAL 把连续 4 字节当成同一路的连续位，按字交错就会让每个字里混进另一声道。
     *
     * DoP 不使用这个开关（它的 6 字节帧由 `packDop` 固定）。
     */
    private fun rawPackMode(pack: DapPackOverride.Choice? = null): Int = when {
        dsdTransport == DsdTransport.dop -> NativeFfmpegDemux.PACK_DOP
        // 16 bit 容器（4 字节/帧）：`L0 R0 L1 R1` → `L0 L1 R0 R1`
        DapPackOverride.container16 -> NativeFfmpegDemux.PACK_NATIVE16
        pack?.label?.startsWith(DSD660_TOKEN) == true -> NativeFfmpegDemux.PACK_DSD660
        pack?.byteInterleaved == true -> NativeFfmpegDemux.PACK_OFF
        else -> NativeFfmpegDemux.PACK_NATIVE
    }

    /**
     * `files/dsd-pack.txt` 变了就**就地重配线格式并倒回 0** —— 同一首、不切歌也能把
     * 四种组合试齐（切歌会踩到别的通路 bug，见 `DapPackOverride` 的说明）。
     *
     * 为什么在 `read()` 里轮询：这条判据只依赖自己这个 handle 和一行文件，不碰 Media3 的
     * 播放状态，也不依赖"暂停→恢复"的语义。每读一块只多一次 `stat`；DSD64 一块 512 KB
     * ≈ 0.7 s，所以切换延迟 ≲ 1 s。
     */
    private fun pollPackChange() {
        if (handle == 0L || !dsdTransport.isRaw) return
        if (!DapPackOverride.changed()) return
        val previous = appliedPack
        val previousPtsUs = lastPtsUs
        val pack = resolvePack()
        appliedPack = pack
        NativeFfmpegDemux.configRawPackEx(handle, rawPackMode(pack), pack.bigEndian, pack.bitReverse)
        // 归零：两次听的是同一段音乐，才有可比性
        NativeFfmpegDemux.seek(handle, 0L)
        lastPtsUs = 0L
        traceReads = 0
        dapTrace("Y") {
            "pack=${pack.label} be=${pack.bigEndian} rev=${pack.bitReverse} " +
                "byte=${if (pack.byteInterleaved) 1 else 0} mode=${rawPackMode(pack)} " +
                "reopen=1 prev=${previous?.label} " +
                "prevPtsUs=$previousPtsUs pos=0 handle=$handle"
        }
    }

    /**
     * raw DSD：native 交来的**已经是线格式字节**（DoP 6 B/帧 或 Native 8 B/帧），
     * 这里只做转发，不做任何位运算。
     *
     * 封装在 native 的 demux 里做（拆声道 → 位反转 → 打包一次完成），原因有两条：
     * 1. 封装后的字节流才是 Media3 看到的样本，帧长决定时长与位置，必须在上游定死；
     * 2. DoP 标记相位是"跨块必须严格交替"的易失状态，只该有一个持有者 —— 现在它是
     *    demux handle 自己，Kotlin 侧不再有副本。
     *
     * 这条路径**不产生 PCM 样本**，所以处理器链必须旁路（见 `PcmAudioSink.rawPassthrough`）
     * —— r8brain 会重采样、EQ 会改最低位，而 DoP 的标记就在那些位里。
     */
    /**
     * 开流前先送 [DapPackOverride.padBytes] 个字节的 DSD 静音（默认 0 = 不送）。
     *
     * 为什么需要：飞傲 HAL 在 `processAudioInit` 之后会打
     * `processAudioEvent:skip frame 1` —— 它每次开流**丢掉 1 个 frame**。而 AudioFlinger
     * 对这个私有 format 算出的帧长是 1 字节（dumpsys: `Processing frame size: 1 bytes`），
     * 所以被吃掉的很可能是 1 个字节。丢 1 字节之后每个 32 位字都变成
     * "上一字节的尾巴 + 下一字节的头"，左右混在一起 ⇒ 满幅噪声，
     * 而 USB 独占同一条字节流能出声（它不丢）—— 这就是两条路唯一的实质差别。
     *
     * 送的是 `0x96`：DSD 的静音位型（1 与 0 交替，均值落在零）。填 `0x00` 不是静音，
     * 是满幅负直流（见 `native-uac.cpp` 里同一件事的记录）。
     */
    private fun emitPad(): Boolean {
        if (padRemaining <= 0) return false
        // **必须凑够一个整帧**：`DapDirectOutput.write` 对 `remaining < 8` 的样本直接丢弃，
        // 所以只送 1 字节等于什么都没送（第一版就踩了这个）。
        // 语义仍是"补偿 N 个被丢掉的前导字节"：N 字节垫片 + 补满一帧的余量，
        // 于是 HAL 吃掉 N 个字节之后，真正的数据仍然落在帧边界上。
        var want = minOf(padRemaining, rawPacked.size)
        if (want < specBytesPerFrame) want = specBytesPerFrame
        java.util.Arrays.fill(rawPacked, 0, want, 0x96.toByte())
        padRemaining -= want
        val track = trackOutput ?: return false
        track.sampleData(ParsableByteArray(rawPacked, want), want)
        track.sampleMetadata(0L, C.BUFFER_FLAG_KEY_FRAME, want, 0, null)
        // 大段补头（端点模式判定用）会分成很多块，只在首尾各记一行
        if (tracePadLeft <= 0L || padRemaining == 0) {
            dapTrace("Y") {
                "pad=$want 剩余=$padRemaining（0x96 = DSD 静音；若听到轰响说明端点在 PCM 模式）"
            }
        }
        tracePadLeft = padRemaining.toLong()
        return true
    }

    private fun readRaw(input: ExtractorInput): Int {
        pollPackChange()
        // 补头必须排在**第一次读 native 之前**：它属于流的最前面
        if (emitPad()) {
            advanceInput(input, 0)
            return Extractor.RESULT_CONTINUE
        }
        val written = NativeFfmpegDemux.read(handle, rawPacked, meta)
        lastPtsUs = meta[0]
        traceReads++
        if (traceReads <= 40 || traceReads % 16 == 0) {
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("R") {
                "n=$traceReads bytes=$written ptsUs=${meta[0]} " +
                    "inputPos=${runCatching { input.position }.getOrDefault(-1)}"
            }
        }
        if (written == 0) {
            // **源侧假 EOS 的唯一证据**：native 在"这一轮凑不满一个封装单元"时也返回 0，
            // 而 Media3 把 0 当 `RESULT_END_OF_INPUT`（此后永不供数）。
            // 这两件事在 Kotlin 侧完全同形，所以必须单独记一行。
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("R-STOP") {
                "bytes=0 n=$traceReads ptsUs=${meta[0]} handle=$handle"
            }
            return Extractor.RESULT_END_OF_INPUT
        }
        if (written < 0) {
            com.annieplayer.annie_music.kt.core.audio.dap.dapTrace("R-STOP") { "bytes=$written n=$traceReads" }
            throw IOException("ffmpeg raw 读取失败: $written")
        }

        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(ParsableByteArray(rawPacked, written), written)
        track.sampleMetadata(
            relPts(meta[0]),
            C.BUFFER_FLAG_KEY_FRAME,
            written,
            0,
            null,
        )
        advanceInput(input, written)
        return Extractor.RESULT_CONTINUE
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (dsdTransport.isRaw) return readRaw(input)

        val written = NativeFfmpegDemux.read(handle, pcm, meta)
        if (written == 0) return Extractor.RESULT_END_OF_INPUT
        if (written < 0) throw IOException("ffmpeg demux 读取失败: $written")

        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        // TrackOutput 只接受 ParsableByteArray 形态的样本数据
        track.sampleData(ParsableByteArray(pcm, written), written)
        track.sampleMetadata(
            relPts(meta[0]),
            C.BUFFER_FLAG_KEY_FRAME,
            written,
            0,
            null,
        )
        // **必须让 input 的位置前进**：`ProgressiveMediaPeriod` 每越过 50 KB 就关一次加载闸门、
        // 把控制权交回播放线程；位置不动就再也不交回，loader 会连读到 native EOF
        // （DSD64 的 PCM 是源的 6 倍，实测 0.34 秒 216 MB 撑爆堆，且永远准备不完）。
        // 配合 `PositionOnlyDataSource`，这一步是纯位置推进、零 IO。
        advanceInput(input, written)
        return Extractor.RESULT_CONTINUE
    }

    /**
     * Media3 通知"要跳到这里"，这是**唯一**的 seek 入口。
     *
     * position 单位是微秒（见 `SeekMap` 约定），直接丢给 native。
     * 不要试图在 [read] 里比较 `PositionHolder.position` 自己判 seek ——
     * `ExtractingLoadable` 每轮都会把它改写成 `input` 的当前位置，两者坐标系不同。
     */
    override fun seek(position: Long, timeUs: Long) {
        ptsSeedUs = timeUs
        if (handle != 0L) NativeFfmpegDemux.seek(handle, position)
    }

    private fun relPts(nativePtsUs: Long): Long {
        if (ptsBaseUs == Long.MIN_VALUE) ptsBaseUs = nativePtsUs - ptsSeedUs
        return (nativePtsUs - ptsBaseUs).coerceAtLeast(0L)
    }

    override fun release() {
        if (handle != 0L) {
            NativeFfmpegDemux.release(handle)
            handle = 0L
        }
        FfmpegOutputStream.clearIf(path)
        trackOutput = null
    }

    /** 每秒字节数 × 8 = bps；Media3 的缓冲估算全靠这个数 */
    private fun bitrateOf(sampleRate: Int, channels: Int, bits: Int): Int {
        val bytesPerSample = when {
            bits <= 16 -> 2
            bits <= 24 -> 3
            else -> 4
        }
        return sampleRate * channels * bytesPerSample * 8
    }

    private companion object {
        /**
         * 单次取样上限（字节）。
         *
         * 24bit 立体声 256KB ≈ 18,000 帧 ≈ 0.4 秒 @44.1k —— 一次读满能显著减少 JNI 往返，
         * 又不会让 seek 的响应粒度变粗太多。
         *
         * 对 DSD（352.8k/24bit = 2.1 MB/s）这个块只有 ~0.12 秒，仍然合适。
         */
        const val SAMPLE_BUFFER_BYTES = 256 * 1024

        const val DSD660_TOKEN = "dsd660"
    }
}

/**
 * 把 [ExtractorInput] 的位置真的推进 [count] 字节（[ExtractorInput.skip] 允许少跳）。
 *
 * 为什么必须是循环：`DefaultExtractorInput.skip` 实测一次只退 **4096 字节**
 * （`DapTrace` 的 `R` 行里 `inputPos` 就是按 4096 递增的，而每次读进 512 KB）。
 * `ProgressiveMediaPeriod` 靠"位置前进 50 KB"来关一次加载闸门、把控制权交回播放线程；
 * 位置只走 4096 的话，这个钩子要 13 个样本才够一次，于是 loader 一口气把整首读进
 * `SampleQueue` —— 实测一首 169 秒的 DSD64 读出 **120 MB**（`R n=230` × 512 KB），
 * 堆顶到 100 MB 以上，同时它"永远准备不完"。
 *
 * 不抛异常：位置推进只是节流信号，推进失败也不该让整首歌的解析失败。
 */
internal fun advanceInput(input: ExtractorInput, count: Int) {
    var left = count
    while (left > 0) {
        val n = try {
            input.skip(left)
        } catch (_: IOException) {
            return
        }
        if (n <= 0) return
        left -= n
    }
}
