package com.annieplayer.annie_music.kt.core.audio.dsd

import com.annieplayer.annie_music.kt.core.audio.DsdLevel
import com.annieplayer.annie_music.kt.core.audio.SinkKind

/**
 * DoP / Native 的可行性判定 —— **纯函数，可 JVM 单测**。
 *
 * ## 为什么是两层判据
 *
 * 两条模式都要求「字节原样到 DAC」，所以不能只看用户选了什么：
 *
 * 1. **通路层**：数据中途会不会被改写。AudioFlinger 的音量就是一次 float 乘法，
 *    DoP 的帧标记（0x05 / 0xFA）就在被乘的那些位里；PS/PDM 前缀一旦被改，
 *    DAC 认不出，听到的是满幅噪声。所以只有「逐字节保真」的通路才可能成立。
 * 2. **设备层**：这个档位的帧率 DAC 接不接得住。DoP 的帧率是 `bitRate / 16`、
 *    Native 是 `bitRate / 32`，两套数完全不是一回事。
 *
 * 任何一层不成立就往下退，且**只退不升**（见 [DsdTransport.fallbackChain]）：
 * 用户选 DoP 说明他不想碰厂商私有 alt，替他升到 Native 等于替他判断设备。
 *
 * ## 为什么 `pcmRates == null` 不算"不支持"
 *
 * 描述符残缺的 DAC 很多（`tSamFreq` 空 = continuous 或干脆没写），但它们实际能跑。
 * 拿「没声明」代理「做不到」，就会把这类设备误判成不支持 —— 与 `99.7` 里
 * `sourceRateIsSafe` 犯的是同一个错。所以 `null` 一律按「可以试」处理，
 * 真跑不了由开流报错，那时再降级。
 *
 * 相反 `hasRawAlt = false` 是**硬事实**：raw alt 不存在就是不存在，试也没用。
 */
data class UsbDacCaps(
    /**
     * PCM alt 声明的采样率并集（`tSamFreq[]`）。DoP 帧率必须落在这里。
     * `null` = 设备没声明 → 按"可以试"处理。
     */
    val pcmRates: Set<Int>? = null,
    /** 是否存在 raw DSD alt（UAC2 `bmFormats & 0x80000000` / UAC1 `FORMAT_TYPE_UNDEFINED`） */
    val hasRawAlt: Boolean = false,
    /** raw alt 声明的 word 率。空 = 没声明（同样按"可以试"处理） */
    val rawRates: Set<Int> = emptySet(),
)

/** 降级原因。文案契约与 [com.annieplayer.annie_music.kt.core.audio.DowngradeReason] 一致：一句原因、一句怎么办。 */
enum class DsdDowngradeReason(val label: String, val advice: String) {
    /** 通路不是 USB 直连：mixer 会缩放音量、改写 DoP 帧标记 */
    PathNotBitExact("当前通路会改写数据", "切到 USB 独占后自动恢复"),
    /** 还没实测到通路（刚启动 / 刚换设备），不确定就不做 */
    NotMeasured("还没测到实际通路", ""),
    /** 设备能力还没读出来 */
    DeviceUnknown("还没读到设备能力", ""),
    /** 设备没有 raw DSD 通道，Native 无路可走 */
    NoRawAlt("设备没有暴露 raw DSD 通道", "可改用 DoP"),
    /** 该档位的帧率不在设备声明里 */
    FrameRateUnsupported("设备不接受这个档位的帧率", "降低档位或改用 DSD 转 PCM"),
    /**
     * 这个档位在**这台设备**上已经实试失败过（开流被拒 / 中途断掉）。
     *
     * 与 [NoRawAlt] 的区别是它来自运行结果而不是描述符：描述符残缺的 DAC 很多，
     * 拿"没声明"代理"做不到"会误伤一批设备，所以先试，失败了再记住。
     * 记忆按**档位**分（`native@705600`），一次失败只否掉那一个率 ——
     * 否则播过一首 DSD512 就会把整台设备的 Native 永久否掉。
     */
    TransportFailed("这个档位刚在设备上失败过", "已自动改用更低一级的方式"),
    /**
     * DAP 内置 DAC 的直通还没测过。
     *
     * 没实测就建私有 `audio_format_t` 的轨，最坏情况是 HAL 收下但解不出声（静音或满幅噪声），
     * 最坏情况是 HAL 在 `set()` 里崩 —— 所以「没测过」必须降级，不能当"可以试"。
     */
    DapNotMeasured("内置 DAC 直通这次没成立", "可改用 DoP，或改成 DSD 转 PCM"),
}

data class DsdTransportDecision(
    val transport: DsdTransport,
    /** `null` = 意图成立，没有降级 */
    val reason: DsdDowngradeReason? = null,
) {
    val isFallback: Boolean get() = reason != null
}

/** DoP：一个 24 bit 帧装 16 个 1-bit 样本（左 8 + 右 8，外加标记字节的高 8 位） */
const val DOP_FRAME_DIVISOR: Int = 16

/** Native：一个 32 bit word 装 32 个 1-bit 样本 */
const val NATIVE_WORD_DIVISOR: Int = 32

/** DoP 帧率（Hz）。DSD64 → 176400、DSD128 → 352800、DSD256 → 705600、DSD512 → 1411200 */
val DsdLevel.dopFrameRate: Int get() = bitRate / DOP_FRAME_DIVISOR

/** Native 的 word 率（Hz）。DSD64 → 88200、DSD128 → 176400、DSD256 → 352800、DSD512 → 705600 */
val DsdLevel.nativeWordRate: Int get() = bitRate / NATIVE_WORD_DIVISOR

fun dsdSinkFor(
    usbExclusive: Boolean,
    measured: SinkKind,
    dapReady: Boolean,
): SinkKind {
    if (usbExclusive) return measured
    if (measured == SinkKind.dsdDirect) return measured
    return if (dapReady) SinkKind.dsdDirect else SinkKind.audioTrack
}

fun decideDsdTransport(
    wanted: DsdTransport,
    level: DsdLevel?,
    sink: SinkKind,
    routingKnown: Boolean,
    caps: UsbDacCaps?,
    dapNativeReady: Boolean = false,
    aaudioDopReady: Boolean = false,
): DsdTransportDecision {
    if (level == null) return DsdTransportDecision(DsdTransport.pcm)
    if (wanted == DsdTransport.pcm) return DsdTransportDecision(DsdTransport.pcm)

    if (sink == SinkKind.oboe) {
        return when (wanted) {
            DsdTransport.dop -> if (aaudioDopReady) {
                DsdTransportDecision(DsdTransport.dop)
            } else {
                DsdTransportDecision(DsdTransport.pcm, DsdDowngradeReason.PathNotBitExact)
            }
            DsdTransport.native -> DsdTransportDecision(
                DsdTransport.pcm,
                DsdDowngradeReason.PathNotBitExact,
            )
            DsdTransport.pcm -> DsdTransportDecision(DsdTransport.pcm)
        }
    }

    if (sink == SinkKind.dsdDirect) {
        return when (wanted) {
            DsdTransport.native -> if (dapNativeReady) {
                DsdTransportDecision(DsdTransport.native)
            } else {
                DsdTransportDecision(DsdTransport.pcm, DsdDowngradeReason.DapNotMeasured)
            }
            // **DoP 在内置 DAC 上不可用**，而且这不是"还没试对参数"。
            //
            // 实测（JM21）：DoP 的帧是 24 bit PCM @176400，所以它只能走 **PCM 端口**；
            // 而公开 PCM 格式请求 `AUDIO_OUTPUT_FLAG_DIRECT` / `0x2001` 在这台机器上被
            // policy 静默降级到 `deep_buffer`：
            //   `AudioFlinger: createTrack_l(): requested flags (00000008) and output flags (00000008)`
            // 那条轨随后出现在 **176400 / PCM_FLOAT 的混音线程**上（`type 0 (MIXER)`），
            // `0x05/0xFA` 标记被那一次 float 乘法整片改写 —— 听感是**纯噪音**。
            // 更要命的副作用：这次建流把共享输出线程重配成 176400 并且**不再恢复**，
            // 之后每一首普通歌都被它重采样（实测：重启后 48k，播完 DSD 后变 176.4k）。
            //
            // 私有 format（native）之所以能出声，是因为它在 `deep_buffer` 里没有对应
            // profile，policy 无处可落，只能真开 `direct_pcm`。DoP 没有这种私有值可用
            // （AOSP 与这台 HAL 都没有 DoP 的 `audio_format_t`）。
            DsdTransport.dop -> DsdTransportDecision(
                DsdTransport.pcm,
                DsdDowngradeReason.PathNotBitExact,
            )
            DsdTransport.pcm -> DsdTransportDecision(DsdTransport.pcm)
        }
    }

    if (!routingKnown) return DsdTransportDecision(DsdTransport.pcm, DsdDowngradeReason.NotMeasured)
    if (sink != SinkKind.usbUac) {
        return DsdTransportDecision(DsdTransport.pcm, DsdDowngradeReason.PathNotBitExact)
    }

    if (caps == null) {
        // 能力未知时两条路分开对待，因为「未知」对它们的含义不同：
        //
        // - **DoP** 只要求「通路保真 + 这个帧率送得出去」，后者由开流时的 `SET_CUR`
        //   裁决（真不支持的 DAC 会拒绝，我们再降级）。所以可以试 —— 拿「没声明」
        //   代理「做不到」会误伤一大批描述符残缺的设备，与 99.7 那个错同源。
        // - **Native** 要求 raw alt **存在**，那是硬事实：猜错会把 32-bit PCM 灌进
        //   raw 通道，设备不报错、只把它当 1-bit 解成满幅噪声。未知就降 DoP。
        return if (wanted == DsdTransport.dop) {
            DsdTransportDecision(DsdTransport.dop)
        } else {
            DsdTransportDecision(DsdTransport.dop, DsdDowngradeReason.DeviceUnknown)
        }
    }

    val dopOk = caps.pcmRates?.contains(level.dopFrameRate) ?: true
    val nativeOk = caps.hasRawAlt &&
        (caps.rawRates.isEmpty() || level.nativeWordRate in caps.rawRates)

    return when (wanted) {
        DsdTransport.native -> when {
            nativeOk -> DsdTransportDecision(DsdTransport.native)
            dopOk -> DsdTransportDecision(DsdTransport.dop, DsdDowngradeReason.NoRawAlt)
            else -> DsdTransportDecision(DsdTransport.pcm, DsdDowngradeReason.FrameRateUnsupported)
        }
        DsdTransport.dop -> if (dopOk) {
            DsdTransportDecision(DsdTransport.dop)
        } else {
            DsdTransportDecision(DsdTransport.pcm, DsdDowngradeReason.FrameRateUnsupported)
        }
        DsdTransport.pcm -> DsdTransportDecision(DsdTransport.pcm)
    }
}

/**
 * 沿降级链 [DsdTransport.fallbackChain] 逐级试，第一个**能够成立**的胜出。
 *
 * ## 为什么不能"降一级再整体问一次"
 *
 * 那样写有两个坑（都实际发生过）：
 *
 * 1. 意图被改写成下一级之后，`decide` 的返回值**直接被当结论**。于是 DoP 若在同一次
 *    判定里也不成立，就一路掉到 PCM —— 看起来像"跳过了 DoP"，日志里也看不出来它试过。
 * 2. 失败记忆若是按传输方式记的，播过一次 DSD512 失败会把整台设备的 Native 永久否掉。
 *    这里把"是否失败"交给调用方以**方式+档位**为键判断（[failed]）。
 *
 * @param failed 该方式在**这个档位**上是否刚失败过
 */
fun resolveDsdChain(
    wanted: DsdTransport,
    level: DsdLevel?,
    sink: SinkKind,
    routingKnown: Boolean,
    caps: UsbDacCaps?,
    dapNativeReady: Boolean = false,
    aaudioDopReady: Boolean = false,
    failed: (DsdTransport) -> Boolean = { false },
): DsdTransportDecision {
    var reason: DsdDowngradeReason? = null
    for (candidate in DsdTransport.fallbackChain(wanted)) {
        if (candidate == DsdTransport.pcm) break
        if (failed(candidate)) {
            // **一旦"想要的那一级刚失败过"，就以它为准。**
            // 后面那些候选的理由是"它自己也做不到"，信息量更低：实测被它盖成
            // `DapNotMeasured`（看上去像"没测过"），而真相是 native 开流失败了 ——
            // 两者要查的地方完全不同。
            reason = DsdDowngradeReason.TransportFailed
            continue
        }
        val d = decideDsdTransport(
            candidate,
            level,
            sink,
            routingKnown,
            caps,
            dapNativeReady,
            aaudioDopReady,
        )
        // 判定返回的 transport 就是"它自己能做到的那一级"；等于 candidate 才算成立
        if (d.transport == candidate) {
            return DsdTransportDecision(candidate, if (candidate == wanted) null else reason)
        }
        if (reason != DsdDowngradeReason.TransportFailed) {
            reason = d.reason ?: reason
        }
    }
    return DsdTransportDecision(DsdTransport.pcm, reason)
}
