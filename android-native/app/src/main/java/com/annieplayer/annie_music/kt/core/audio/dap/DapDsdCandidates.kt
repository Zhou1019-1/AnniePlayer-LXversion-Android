package com.annieplayer.annie_music.kt.core.audio.dap

import com.annieplayer.annie_music.kt.core.audio.DsdLevel

/**
 * DAP 内置 DAC 的候选表 —— **逐条对照反编译**得来，不是猜的。
 *
 * 来源：`NativeDsdSink::open`（`libaudiophile_native.so` 的 `sub_229764`，593 行之外的
 * 建流函数是 `sub_236908`）。每条候选 16 字节 `{int32 format, uint32 rate, int32 flags,
 * int32 variant}`，`sub_22B510` 把 **+8 的 flags** 与 **+0 的 format**、**+4 的 rate**
 * 交给 `sub_236908`，后者再调 `libaudioclient.so` 的 `AudioTrack::set`。
 * `variant` 只用于日志，不参与建流。
 *
 * ## 采样率不是统一的 `bitRate / N`
 *
 * 这一条最容易搞错。`format` 的**低位字节段**其实是「容器宽度」：
 *
 * | format | 容器 | `sampleRate` 传什么 | 含义 |
 * |---|---|---|---|
 * | `0x1A000000` | 8 bit | `bitRate` | 一个 1-bit 样本占 1 字节 → 采样率就是比特率 |
 * | `0x1A000001` | 16 bit | `bitRate / 16` | 16 个 1-bit 装一个 16 bit 样本 |
 * | `0x1A000002` | 32 bit | `bitRate / 32` | 32 个 1-bit 装一个 word |
 * | `0x1F000000` | 8 bit | `bitRate` | 同 `...0000`，未知兜底 |
 *
 * 也就是说 **`0x1A000000` 与 `0x1F000000` 要传完整的比特率**（DSD64 → 2 822 400），
 * 只有 `0x1A000002` 才是 `bitRate / 32`。全表按 `bitRate / 32` 传的话，
 * 这两条永远被 `set()` 拒（率与容器宽度不自洽）。
 *
 * 容器宽度还决定缓冲：帧数恒为 `sampleRate / 5`（0.2 秒，
 * `sub_236908` 用 `0xCCCCCCCD` 魔数除以 5），乘上每帧字节数才是缓冲大小。
 *
 * ## 顺序是知识
 *
 * 前一条失败才试后一条。顺序错了不会崩，只是多撞几次；但对 FPGA 机器和
 * SDK33 那族，**顺序决定了能不能成功**（它们的首选端口不同）。
 */
internal const val AUDIO_FORMAT_IEC61937 = 0x0D000000

/** AOSP `audio_format_t` 的 DSD 段起点（8 bit 容器，采样率 = 比特率） */
internal const val AUDIO_FORMAT_DSD_LSB = 0x1A000000

/** 16 bit 容器（采样率 = `bitRate / 16`）；HiBy 那族在 32 bit 容器下也用这个值 */
internal const val AUDIO_FORMAT_DSD_MSB = 0x1A000001

/**
 * 32 bit 容器（采样率 = `bitRate / 32`）。
 *
 * 飞傲在 `AudioManager.setParameters("format=")` 里写的也是这个数（`436207618`），
 * 所以它同时是「HAL 认的 DSD 格式」和「飞傲私有参数」两个身份。
 */
internal const val AUDIO_FORMAT_DSD_VENDOR_PACKED = 0x1A000002

/** 未知兜底：格式段 `0x1F`，8 bit 容器，采样率 = 比特率 */
internal const val AUDIO_FORMAT_DAP_FALLBACK = 0x1F000000

/** SDK 33+ 那族用的一对值（先试哪个取决于 `ro.build.version.sdk`） */
internal const val AUDIO_FORMAT_SDK33_A = 0x2E000000
internal const val AUDIO_FORMAT_SDK33_B = 0x2F000000

/** `AUDIO_OUTPUT_FLAG_DIRECT`：绕开 mixer 的算术，DoP / Native 的硬前提 */
internal const val AUDIO_OUTPUT_FLAG_DIRECT = 0x1

/** `AUDIO_OUTPUT_FLAG_DIRECT_PCM`：部分 HAL 要求 direct 端口再带这个标记 */
internal const val AUDIO_OUTPUT_FLAG_DIRECT_PCM = 0x2000

/** `AUDIO_OUTPUT_FLAG_PRIMARY` / `AUDIO_OUTPUT_FLAG_DEEP_BUFFER`（参考实现的 PCM 阶梯里出现） */
internal const val AUDIO_OUTPUT_FLAG_PRIMARY = 0x2
internal const val AUDIO_OUTPUT_FLAG_DEEP_BUFFER = 0x8

/**
 * PCM 端口要依次试的 flags 阶梯 —— **只留保真组合**。
 *
 * 只剩 `DIRECT|DIRECT_PCM(0x2001)`：`DIRECT|DEEP_BUFFER`、`DIRECT|PRIMARY` 这类组合
 * 在 AudioFlinger 里本来就要求 flags 完全一致才能落到同一个输出，等于自己声明
 * "我要 mixer"，只会更没戏（而且一旦真的落到 mixer，PCM 会被改写、DoP 会变成噪声）。
 *
 * 实测（JM21）：即便只请求 `0x1` / `0x2001`，线性 PCM 也被 `libaudioclient` 的厂商补丁
 * 强制改成 `DEEP_BUFFER`（运行时日志：`AudioTrack: set(): … flags #1` 之后
 * `set(): check streamType 3 … flags #8`，即 policy 回写；与
 * `Not linear PCM, forcing to Direct Output` 那条字符串是同一个分支）。
 * 所以这条阶梯对**非 PCM 之外**的机型仍值得保留，但它救不了这台机器上的 PCM。
 */
internal val DAP_PCM_FLAG_LADDER: List<Int> = listOf(
    AUDIO_OUTPUT_FLAG_DIRECT or AUDIO_OUTPUT_FLAG_DIRECT_PCM,
)

/**
 * **raw 帧（DoP）只能试保真 flags**。
 *
 * 一旦落到会经过 mixer 的组合（例如带 `PRIMARY`/`DEEP_BUFFER`），`0x05 / 0xFA` 帧标记会被
 * 那一次 float 音量乘法改掉 —— 听感从"静音"变成"满幅噪声"，比不出声更糟。
 */
internal val DAP_DOP_FLAG_LADDER: List<Int> = listOf(
    AUDIO_OUTPUT_FLAG_DIRECT or AUDIO_OUTPUT_FLAG_DIRECT_PCM,
)

/**
 * Cayin 的候选带这一位（`0x40001` = `0x40000 | DIRECT`）。
 *
 * `0x40000` 不在 AOSP 的公开枚举里 —— 含义**未确认**，只知道它出现在 Cayin 那条
 * 追加候选上（`sub_227CF4` 判中之后）。原样传递，不解释。
 */
internal const val AUDIO_OUTPUT_FLAG_CAYIN_EXTRA = 0x40001

/** 容器宽度：`format` 的 DSD 段里隐含的每样本字节数 */
enum class DapContainer(val bytesPerSample: Int) {
    byte8(1),
    short16(2),
    word32(4),
}

/** 一个 32 bit word 装 32 个 1-bit 样本 */
internal const val DAP_WORD_DIVISOR = 32

/** DAP 内置 DAC 收 DSD 时的 word 率：DSD64 → 88 200、DSD128 → 176 400 */
val DsdLevel.dapWordRate: Int get() = bitRate / DAP_WORD_DIVISOR

/** 缓冲帧数：`sampleRate / 5`（0.2 秒），与反编译里的魔数除法一致 */
fun dapFrameCount(sampleRate: Int): Int = if (sampleRate <= 0) 0 else sampleRate / 5

/**
 * 交给 `set()` 的 `frameCount` —— **单位是"framework 帧"，不是音频帧**。
 *
 * framework 对这个私有 DSD format 算不出每帧字节数（`audio_bytes_per_sample()` 只有标准
 * enum 的分支），于是它把**1 字节当一帧**。实测证据：
 *
 * - `dumpsys media.audio_flinger`：`Processing frame size: 1 bytes`、`HAL buffer size: 32768 bytes`；
 * - 我们传 `frameCount=17640`，轨道**只吃进 17640 字节**（= 2205 个音频帧 = **25 ms**），
 *   而期望是 0.2 秒。
 *
 * 后果不是"差一点"，而是 1017 次写里 976 次返回 `-11`（`EWOULDBLOCK`）：每回合只能推 25 ms，
 * 余量比一块 512 KB 的样本小三个数量级，任何抖动都会 underrun。
 *
 * 所以想要 0.2 秒的容量，必须按**字节**给：`(sampleRate/5) × bytesPerFrame`。
 */
fun dapFrameCountInWireFrames(sampleRate: Int, bytesPerFrame: Int): Int =
    if (bytesPerFrame <= 0) 0 else dapFrameCount(sampleRate) * bytesPerFrame


/** 一个候选组合。**只是要试的东西**，不代表这台机器成立。 */
data class DapFormatCandidate(
    val format: Int,
    /** 交给 `set()` 的 `sampleRate`；与 [container] 必须自洽（见文件头那张表） */
    val sampleRate: Int,
    val outputFlags: Int,
    val container: DapContainer,
    /** 诊断标签（日志 / 「输出能力」页显示用），不参与判断 */
    val tag: String,
) {
    /** 立体声一条帧多少字节 */
    val bytesPerFrame: Int get() = container.bytesPerSample * 2

    /** 交给 `set()` 的 `frameCount` */
    val frameCount: Int get() = dapFrameCount(sampleRate)

    /** 缓冲多少字节（`frameCount × bytesPerFrame`），用于日志与内存上限判断 */
    val bufferBytes: Int get() = frameCount * bytesPerFrame
}

/**
 * 生成这台机器要依次尝试的组合。
 *
 * @param vendor 厂商家族。只有飞傲会拿到额外的 `flags = 0` 变体和 `bitRate / 16` 那两条；
 *   山灵 / Onix 会先拿到 `0x2E000000` / `0x2F000000` 四条；Cayin 会在末段多一条
 * @param isSmartAudioFamily `libsmartaudioservice.so` 存在，或品牌命中 HiBy / Cayin / TempoTec。
 *   **只用存在性 / 品牌判定，从不 `dlopen` 它**
 * @param hasFpgaAudioNode `/sys/class/fpga/fpga0/fpga_audio` 存在（FPGA 类 DAP）
 * @param sdkInt `ro.build.version.sdk`；只用来决定 `0x2E` / `0x2F` 谁先
 */
fun dapDsdCandidates(
    level: DsdLevel,
    vendor: DapVendor = DapVendor.unknown,
    isSmartAudioFamily: Boolean = false,
    hasFpgaAudioNode: Boolean = false,
    sdkInt: Int = 0,
): List<DapFormatCandidate> {
    val bitRate = level.bitRate
    val wordRate = level.dapWordRate
    if (wordRate <= 0) return emptyList()

    val out = ArrayList<DapFormatCandidate>(12)
    fun add(format: Int, rate: Int, flags: Int, container: DapContainer, tag: String) {
        out.add(DapFormatCandidate(format, rate, flags, container, tag))
    }

    // ① FPGA 机器：走 IEC61937 passthrough 端口，且**带 0 个 flag**（不是 DIRECT）。
    //    这类机器的普通 direct 端口不接 DSD，所以整张表都不一样 —— 连末位兜底
    //    也从 `0x1F000000` 换成 `0x1A000000`（见 ③）。
    if (hasFpgaAudioNode) {
        add(AUDIO_FORMAT_IEC61937, wordRate, 0, DapContainer.word32, "fpga/iec61937")
        if (bitRate % DAP_WORD_DIVISOR == 0) {
            add(
                AUDIO_FORMAT_DSD_VENDOR_PACKED, wordRate, AUDIO_OUTPUT_FLAG_DIRECT,
                DapContainer.word32, "fpga/dsd-vendor",
            )
        }
        add(
            AUDIO_FORMAT_DSD_LSB, bitRate, AUDIO_OUTPUT_FLAG_DIRECT,
            DapContainer.byte8, "fpga/dsd-lsb",
        )
        return out
    }

    // ② 山灵 / Onix：先试一对 SDK33 私有值，各带 DIRECT 与 DIRECT|DIRECT_PCM 两种 flag。
    //    先试哪个由 `ro.build.version.sdk` 决定（>=33 先 0x2E）。
    val (sdkFirst, sdkSecond) =
        if (sdkInt >= 33) {
            AUDIO_FORMAT_SDK33_A to AUDIO_FORMAT_SDK33_B
        } else {
            AUDIO_FORMAT_SDK33_B to AUDIO_FORMAT_SDK33_A
        }
    if (vendor == DapVendor.shanling || vendor == DapVendor.onix) {
        if (bitRate % DAP_WORD_DIVISOR == 0) {
            for (format in listOf(sdkFirst, sdkSecond)) {
                add(
                    format, wordRate, AUDIO_OUTPUT_FLAG_DIRECT,
                    DapContainer.word32, "sdk33/0x${format.toString(16)}",
                )
                add(
                    format, wordRate,
                    AUDIO_OUTPUT_FLAG_DIRECT or AUDIO_OUTPUT_FLAG_DIRECT_PCM,
                    DapContainer.word32, "sdk33/0x${format.toString(16)}+directpcm",
                )
            }
        }
    }

    // ③ 32 bit 容器那一组。飞傲多一条 `flags = 0` 的先试版本
    //    （先不带 DIRECT 试，HAL 里有些端口反而不接受 DIRECT）。
    if (bitRate % DAP_WORD_DIVISOR == 0) {
        if (vendor == DapVendor.fiio) {
            add(
                AUDIO_FORMAT_DSD_VENDOR_PACKED, wordRate, 0,
                DapContainer.word32, "dsd-vendor/plain",
            )
        }
        add(
            AUDIO_FORMAT_DSD_VENDOR_PACKED, wordRate, AUDIO_OUTPUT_FLAG_DIRECT,
            DapContainer.word32, "dsd-vendor",
        )
    }

    // ④ 8 bit 容器：**采样率传完整比特率**。始终排在这条链的第四位。
    add(
        AUDIO_FORMAT_DSD_LSB, bitRate, AUDIO_OUTPUT_FLAG_DIRECT,
        DapContainer.byte8, "dsd-lsb",
    )

    // ⑤ 16 bit 容器。三族分叉：
    //    - 飞傲：先 `flags = 0` 再 DIRECT，率 `bitRate / 16`
    //    - smartaudio 族：率用 **word 率**（`bitRate / 32`）而不是 `bitRate / 16`
    //    - 其它：率 `bitRate / 16`，直接 DIRECT（没有 `flags = 0` 那条）
    when {
        vendor == DapVendor.fiio -> {
            if (bitRate % 16 == 0) {
                add(
                    AUDIO_FORMAT_DSD_MSB, bitRate / 16, 0,
                    DapContainer.short16, "dsd-msb/plain",
                )
                add(
                    AUDIO_FORMAT_DSD_MSB, bitRate / 16, AUDIO_OUTPUT_FLAG_DIRECT,
                    DapContainer.short16, "dsd-msb",
                )
            }
        }
        isSmartAudioFamily -> {
            if (bitRate % DAP_WORD_DIVISOR == 0) {
                add(
                    AUDIO_FORMAT_DSD_MSB, wordRate, AUDIO_OUTPUT_FLAG_DIRECT,
                    DapContainer.word32, "dsd-msb/smartaudio",
                )
            }
        }
        bitRate % 16 == 0 -> {
            add(
                AUDIO_FORMAT_DSD_MSB, bitRate / 16, AUDIO_OUTPUT_FLAG_DIRECT,
                DapContainer.short16, "dsd-msb",
            )
        }
    }

    // ⑥ Cayin：多一条带 `0x40001` 的 `0x1A000000`。位置在末位兜底之前。
    if (vendor == DapVendor.cayin) {
        add(
            AUDIO_FORMAT_DSD_LSB, bitRate, AUDIO_OUTPUT_FLAG_CAYIN_EXTRA,
            DapContainer.byte8, "dsd-lsb/cayin",
        )
    }

    // ⑦ 末位兜底。FPGA 机器走的是 ③ 里那条同值不同 flags 的（见 ① 的注释）。
    add(
        AUDIO_FORMAT_DAP_FALLBACK, bitRate, AUDIO_OUTPUT_FLAG_DIRECT,
        DapContainer.byte8, "fallback",
    )

    return out
}
