package com.annieplayer.annie_music.kt.core.audio.dap

/**
 * 从「Media3 交给 sink 的格式」+「探测出来的可用组合」推出这条流要用的 [DapStreamSpec]。
 *
 * ## 为什么需要一层转换，而不是直接把 format 填进 spec
 *
 * 两边说的**不是同一件事**：
 *
 * - Media3 那边是 `pcmEncoding`（`C.ENCODING_PCM_*`），它只描述"每样本几个字节"；
 * - 内置 DAC 那边要 `audio_format_t`，它是厂商私有的 DSD 段值，并**隐含容器宽度**
 *   （见 `DapDsdCandidates` 文件头那张表）。
 *
 * 所以必须先确认"上游送来的字节形状"与"我们准备开的那种容器"是一回事，否则就是把
 * 32 bit word 当 16 bit 样本喂。`bytesPerFrame` 两边不一致时**宁可判不成立**。
 *
 * ## 为什么要求 `sampleRate == working.sampleRate`
 *
 * 这条流能成立，靠的是 HAL 认下了**那一组** `(format, rate, flags)`。
 * 拿同一个 format 换一个率去开，等于重新赌一次 —— 那应该由探测重新回答，不该在这里猜。
 *
 * ## 为什么只接受 32 bit / 16 bit 容器
 *
 * 8 bit 容器（`0x1A000000` / `0x1F000000`）在 Media3 里没有对应的 `pcmEncoding`
 * （`ENCODING_PCM_8BIT` 是 8 bit 定点 PCM，语义完全不同），传过去只会错位。
 * 这两条在实测里也被 HAL 拒了（`-38`），本来就走不通。
 */
internal fun dapStreamSpecFor(
    sampleRate: Int,
    pcmEncoding: Int,
    channelCount: Int,
    working: DapFormatVerdict,
): DapStreamSpec? {
    if (channelCount != 2) return null
    if (sampleRate <= 0 || sampleRate != working.sampleRate) return null
    val container = dapContainerOf(working.format) ?: return null
    if (pcmEncoding != expectedPcmEncoding(container)) return null
    val bytesPerFrame = container.bytesPerSample * 2
    return DapStreamSpec(
        sampleRate = sampleRate,
        channelCount = 2,
        channelMask = AUDIO_CHANNEL_OUT_STEREO,
        encoding = working.format,
        bytesPerFrame = bytesPerFrame,
        // 单位换算见 `dapFrameCountInWireFrames`：framework 按 1 字节一帧算
        frameCount = dapFrameCountInWireFrames(sampleRate, bytesPerFrame),
        outputFlags = working.outputFlags,
    )
}

/**
 * **PCM 直通**的规格 —— 与 [dapStreamSpecFor] 并列的另一条路。
 *
 * 内置 DAC 那条 DIRECT 端口不只接 DSD：`AUDIO_OUTPUT_FLAG_DIRECT` 的 **PCM** 端口是
 * 普通曲目（以及 DoP）唯一不经 mixer 的去处。三种内容各走各的：
 *
 * ```
 * 普通 PCM → 这条（PCM direct）
 * DoP      → 也是这条：DoP 的帧就是 24 bit PCM（6 字节/帧，`0x05 / 0xFA` 是帧标记）
 * Native   → dapStreamSpecFor（私有 DSD 容器）
 * ```
 *
 * ## 为什么这条不需要探测结论
 *
 * 私有 `0x1A0000xx` 没有任何设备会声明，只能实测出来；而 PCM 的 `audio_format_t`
 * 是**公开值**，收不收由 HAL 的 direct profile 决定 —— `set()` 拿它就能回答，
 * 不需要提前开一条试探轨（那会把"这首能不能放"变成开播前的几百毫秒）。
 * 开不成的结果由 `NativeDapDac.lastOpenNote` 如实带出来，不在这里假装成功。
 *
 * ## 为什么 `frameCount` 给音频帧而不是字节
 *
 * 见 [DapStreamSpec.frameCountInBytes]：PCM 的每帧字节数 framework 自己算得出来。
 */
internal fun dapPcmStreamSpecFor(
    sampleRate: Int,
    pcmEncoding: Int,
    channelCount: Int,
    /**
     * 首组 flags 失败后依次再试的组合；默认按 [DAP_PCM_FLAG_LADDER]（普通 PCM 可以容忍
     * 走到 mixer，DoP 不行 —— 那一侧由调用方传 [DAP_DOP_FLAG_LADDER]）。
     */
    alternateFlags: List<Int> = DAP_PCM_FLAG_LADDER,
): DapStreamSpec? {
    if (channelCount != 2) return null
    if (sampleRate <= 0) return null
    val format = when (pcmEncoding) {
        PCM_16BIT -> AUDIO_FORMAT_PCM_16_BIT
        PCM_24BIT -> AUDIO_FORMAT_PCM_24_BIT_PACKED
        PCM_32BIT -> AUDIO_FORMAT_PCM_32_BIT
        PCM_FLOAT -> AUDIO_FORMAT_PCM_FLOAT
        else -> return null
    }
    val bytesPerSample = pcmBytesPerSample(format)
    if (bytesPerSample <= 0) return null
    return DapStreamSpec(
        sampleRate = sampleRate,
        channelCount = 2,
        channelMask = AUDIO_CHANNEL_OUT_STEREO,
        encoding = format,
        bytesPerFrame = bytesPerSample * 2,
        frameCount = dapFrameCount(sampleRate),
        outputFlags = AUDIO_OUTPUT_FLAG_DIRECT,
        frameCountInBytes = false,
        alternateFlags = alternateFlags,
    )
}

/** `audio_format_t`（公开 PCM 段）→ 每样本字节数；0 = 不是这一族 */
internal fun pcmBytesPerSample(format: Int): Int = when (format) {
    AUDIO_FORMAT_PCM_8_BIT -> 1
    AUDIO_FORMAT_PCM_16_BIT -> 2
    AUDIO_FORMAT_PCM_24_BIT_PACKED -> 3
    AUDIO_FORMAT_PCM_32_BIT, AUDIO_FORMAT_PCM_8_24_BIT, AUDIO_FORMAT_PCM_FLOAT -> 4
    else -> 0
}

/** 私有 `audio_format_t` → 容器宽度；`null` = 没有可用的 Media3 对应物 */
internal fun dapContainerOf(format: Int): DapContainer? = when (format) {
    AUDIO_FORMAT_DSD_VENDOR_PACKED -> DapContainer.word32
    AUDIO_FORMAT_DSD_MSB -> DapContainer.short16
    else -> null
}

/**
 * 这个私有 format 的 32 位字**在内存里是不是小端**。
 *
 * 容器名字里就写着字节序，AOSP 的 `audio_format_t` DSD 段是：
 *
 * | 值 | AOSP 名字 | 每单元 | 字节序 |
 * |---|---|---|---|
 * | `0x1A000000` | `DSD_LSB` | 1 字节 | 位序 LSB 优先 |
 * | `0x1A000001` | `DSD_MSB` | 2 字节/声道 | 字节序 = 时间序 |
 * | `0x1A000002` | `DSD_U32_LE` | 4 字节/声道 | **小端** |
 * | `0x1A000003` | `DSD_U32_BE` | 4 字节/声道 | 大端 |
 *
 * 打错方向**不会报错**：`set()` 照样返回 0、轨道照样排空，只有 DAC 解出来是噪声。
 * 实测 JM21：按大端打 `0x1A000002`，出声全是噪声 —— 就是这个。
 * USB 独占那条走的是 UAC 的 raw alt，没有 format 可问，按 ALSA 的 `DSD_U32_BE` 走大端，
 * 所以这两条路的字节序**必须分开定**，不能共用一个写死的常量。
 */
internal fun dapWordsAreLittleEndian(format: Int): Boolean = when (format) {
    AUDIO_FORMAT_DSD_VENDOR_PACKED -> true // 0x1A000002 = DSD_U32_LE
    else -> false
}

/**
 * `C.ENCODING_PCM_*`（与 `AudioFormat.ENCODING_PCM_*` 同值）。
 *
 * 不直接引 Media3 的常量：这个文件要保持能纯 JVM 单测，
 * 而这些值本身就是 Android 的公开契约（16 bit = 2、24 bit packed = 21、
 * 32 bit = 22、float = 4）。
 */
private const val PCM_16BIT = 2
private const val PCM_24BIT = 21
private const val PCM_32BIT = 22
private const val PCM_FLOAT = 4

private fun expectedPcmEncoding(container: DapContainer): Int = when (container) {
    DapContainer.word32 -> PCM_32BIT
    DapContainer.short16 -> PCM_16BIT
    DapContainer.byte8 -> -1
}
