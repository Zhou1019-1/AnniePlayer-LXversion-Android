package com.annieplayer.annie_music.kt.core.audio.dap

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import java.nio.ByteBuffer

/** `audio_format_t` 的公开值（AOSP `system/media/audio/include/system/audio.h`） */
internal const val AUDIO_FORMAT_PCM_16_BIT = 0x1
internal const val AUDIO_FORMAT_PCM_8_BIT = 0x2
internal const val AUDIO_FORMAT_PCM_32_BIT = 0x3
internal const val AUDIO_FORMAT_PCM_8_24_BIT = 0x4
internal const val AUDIO_FORMAT_PCM_FLOAT = 0x5
internal const val AUDIO_FORMAT_PCM_24_BIT_PACKED = 0x6
internal const val AUDIO_FORMAT_PCM_32_BIT_PACKED = 0x7

/**
 * `audio_channel_mask_t` 的**输出**值。
 *
 * 与 Java 的 `AudioFormat.CHANNEL_OUT_*` **不是同一套数**，差得还很远：
 *
 * | 含义 | `audio_channel_mask_t` | `AudioFormat.CHANNEL_OUT_*` |
 * |---|---|---|
 * | 单声道 | `0x1` | `0x4` |
 * | 立体声 | `0x3` | `0xC` |
 *
 * 把 Java 的 `0xC` 传给 native `set()` 会得到非法 mask → `BAD_VALUE`（-22），
 * 症状是"连最普通的 16 bit 都建不出来"，很容易误判成设备不支持。
 */
internal const val AUDIO_CHANNEL_OUT_MONO = 0x1
internal const val AUDIO_CHANNEL_OUT_STEREO = 0x3

/**
 * 一条 DAP 内置 DAC 直通流的**参数**。
 *
 * 单独成类型而不是七个参数，是因为 `channelMask` 与 `channelCount` 必须**成对**给出：
 * - `AudioTrack` 的 Java 层要 mask，
 * - 而私有的 C++ `set()` 要 count 去算每帧字节数。
 *
 * 两者从同一个来源推导时不会错，分开传就迟早有人只改一边（结果是把立体声写成单声道、
 * 或每帧字节数错位 —— 后者在 DSD 上表现为满幅噪声，不是"音量不对"）。
 */
internal data class DapStreamSpec(
    val sampleRate: Int,
    val channelCount: Int,
    /** `audio_channel_mask_t`（**native** 编号，立体声 = 3；不是 Java 的 0xC） */
    val channelMask: Int,
    /**
     * **native `audio_format_t`**（不是 Java 的 `AudioFormat.ENCODING_*`）。
     *
     * 这两套编号完全不同，混用一次就是崩溃：
     *
     * | 含义 | `audio_format_t` | `AudioFormat.ENCODING_*` |
     * |---|---|---|
     * | PCM 16 bit | `1` | `2` |
     * | PCM 24 bit packed | `6` | `21` |
     * | PCM 32 bit | `3` | `22` |
     * | PCM float | `5` | `4` |
     * | DSD 段（私有） | `0x1A0000xx` | 不存在 |
     *
     * 曾经的 bug：把 Java 的 `21` 传给 native `set()`，得到 `audio_format_t = 0x15`
     * （未定义）→ framework 内部致命断言，栈保护失败后 `abort()`。
     * 所以这里统一用 native 编号，[JavaDapSink] 自己映射。
     */
    val encoding: Int,
    /** 一帧多少字节（`channelCount × bytesPerSample`）；0 = 这个 encoding 不支持 */
    val bytesPerFrame: Int,
    /** 目标缓冲帧数（`AudioTrack` 的 frameCount）；单位见 [frameCountInBytes] */
    val frameCount: Int,
    /**
     * [frameCount] 的单位：`true` = **framework 帧（1 字节）**，`false` = 音频帧。
     *
     * 私有 DSD `audio_format_t` 在 framework 里推不出每帧字节数
     * （`audio_bytes_per_sample()` 没有那些分支），`AudioTrack` 于是按 1 字节一帧收
     * `frameCount`（dumpsys 实测 `Processing frame size: 1 bytes`）。公开 PCM format
     * 反过来：它自己知道一帧几字节，`frameCount` 必须给**音频帧** —— 给字节数会开出
     * 大 4~8 倍的缓冲（0.2 s 变 1.6 s），起播延迟与 seek 手感都会跟着变。
     */
    val frameCountInBytes: Boolean = true,
    /**
     * `audio_output_flags_t`。DoP / Native 的硬前提是 `AUDIO_OUTPUT_FLAG_DIRECT`（=1），
     * 候选表里可能带 `DIRECT_PCM` / `FAST` 变体。
     *
     * Java 实现忽略它（公开 API 没有这个入口），只有 native 实现用。
     */
    val outputFlags: Int = 1,
    /**
     * `outputFlags` 失败时**依次再试**的 flags。
     *
     * 为什么需要一张阶梯而不是一个值：`set()` 返回 0 完全不代表这条流接通了 DAC。
     * 参考实现（ReSoNāda `openDirectPcm`）对每个格式都试
     * `{0x1, 0x2001, 0x9, 0x2009}` 一组，逐条 `getSampleRate()` 回读校验。
     * 其中 `0x2001 = DIRECT|DIRECT_PCM(0x2000)` 是最可疑的那一位：少了它，
     * framework 仍会给你一条"直出"轨并让播放头如实前进，而厂商 HAL 里没人接管这个端口
     * —— 实测症状是**纯静音**。
     */
    val alternateFlags: List<Int> = emptyList(),
)

/**
 * DAP 内置 DAC 直通的**写目标**抽象。
 *
 * 存在的理由：这一层最终会换实现，而上面的时钟/水位逻辑一个字都不该跟着动。
 *
 * | 实现 | 用什么 | 什么时候 |
 * |---|---|---|
 * | [JavaDapSink] | `android.media.AudioTrack`（公开 API） | 现在：验证 OutputPort 语义与时钟 |
 * | `NativeDapSink` | `libaudioclient.so` 的 C++ `AudioTrack`（`dlopen`+`dlsym`） | 阶段 3：私有 `audio_format_t` |
 *
 * ## 为什么 Java 实现必须存在，而不是直接写 native
 *
 * 阶段 2 要验的是**上层**：写入、水位、位置、flush 之后的 play、EOS 排空。
 * 这些坑与 DSD 无关，用公开 API 就能全部暴露出来；等到阶段 3 换 native 时，
 * 只换 `write` 的目标，上层一行不改。反过来先写 native 的话，一个位置算错会同时怀疑
 * 时钟和 ABI 两件事。
 */
internal interface DapSink {

    val isOpen: Boolean

    /** 开流。返回 false = 这组参数在这台机器上不成立（**不许抛异常给上层**） */
    fun open(spec: DapStreamSpec): Boolean

    /**
     * 非阻塞写。
     *
     * @return `>0` 写进去的字节数（必然是 [DapStreamSpec.bytesPerFrame] 的整数倍）；
     *   `0` = 这次没排上，**请用同一块重试**；`<0` = 流坏了
     */
    fun write(buffer: ByteBuffer): Int

    fun play()

    fun pause()

    /** 丢掉未播数据（seek / 换曲）。之后要有 [play] 才继续出声 */
    fun flush()

    /**
     * 开流以来**累计已播帧数**（不因 [flush] 归零）。
     *
     * 不归零是刻意的：`UacRing.rebase()` 的语义就是"复位到当前读数"，把它当 0 会在
     * 每次 seek 后把整条流的累计帧数算成本首已播帧（位置会直接跳到几分钟）。
     */
    fun playbackFrames(): Long

    fun close()
}

/**
 * 公开 API 的实现：`android.media.AudioTrack`。
 *
 * 私有 `audio_format_t` 在这里**进不去也不会崩** —— framework 的
 * `AudioFormat.isEncodingValid` 会抛 `IllegalArgumentException`，而我们在
 * [DapStreamSpec.bytesPerFrame] 为 0 时干脆不碰 `AudioTrack`（它内部会用 format
 * 反推每帧字节数，未知 format 得到 0，那是 native 的除零/SIGFPE，Java 接不住）。
 */
internal class JavaDapSink : DapSink {

    private var track: AudioTrack? = null
    private val timestamp = AudioTimestamp()

    override val isOpen: Boolean
        get() = track?.state == AudioTrack.STATE_INITIALIZED

    override fun open(spec: DapStreamSpec): Boolean {
        close()
        if (spec.sampleRate <= 0 || spec.bytesPerFrame <= 0 || spec.frameCount <= 0) return false
        if (spec.channelMask == 0) return false
        // native `audio_format_t` → Java `AudioFormat.ENCODING_*`。
        // 未知值（DSD 那一段）直接放弃：Java 层只有公开编号，私有 format 只能走 native。
        val javaEncoding = javaEncodingOf(spec.encoding) ?: return false
        // native `audio_channel_mask_t` → Java `CHANNEL_OUT_*`（两套值完全不同）
        val javaMask = javaChannelMaskOf(spec.channelMask) ?: return false

        return try {
            val format = AudioFormat.Builder()
                .setEncoding(javaEncoding)
                .setSampleRate(spec.sampleRate)
                .setChannelMask(javaMask)
                .build()
            val bufferBytes = spec.bytesPerFrame * spec.frameCount
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t.release() }
                return false
            }
            track = t
            true
        } catch (e: Throwable) {
            // IllegalArgumentException（私有 encoding）/ UnsupportedOperationException（率越界）
            close()
            false
        }
    }

    override fun write(buffer: ByteBuffer): Int {
        val t = track ?: return -1
        return try {
            // 三参版本：`write(ByteBuffer, int)` 这个重载在 android.jar 里**不存在**
            // （AOSP 头文件里有，SDK stub 没有），踩过一次编译错误。
            t.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Throwable) {
            -1
        }
    }

    override fun play() {
        try {
            track?.play()
        } catch (e: Throwable) {
            // 流已失效：交给上层按 isOpen 判
        }
    }

    override fun pause() {
        try {
            track?.pause()
        } catch (e: Throwable) {
        }
    }

    override fun flush() {
        try {
            track?.flush()
        } catch (e: Throwable) {
        }
    }

    override fun playbackFrames(): Long {
        val t = track ?: return 0L
        val ok = try {
            t.getTimestamp(timestamp)
        } catch (e: Throwable) {
            false
        }
        if (ok) return timestamp.framePosition
        // 退路：32 位回绕。44.1k 下约 27 小时才绕一圈，够用到时间戳恢复
        return try {
            t.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        } catch (e: Throwable) {
            0L
        }
    }

    override fun close() {
        val t = track ?: return
        track = null
        try {
            t.pause()
        } catch (e: Throwable) {
        }
        try {
            t.flush()
        } catch (e: Throwable) {
        }
        try {
            t.release()
        } catch (e: Throwable) {
        }
    }

    /** `audio_format_t` → `AudioFormat.ENCODING_*`；`null` = 这个值 Java 层不认识 */
    private fun javaEncodingOf(audioFormat: Int): Int? = when (audioFormat) {
        AUDIO_FORMAT_PCM_16_BIT -> AudioFormat.ENCODING_PCM_16BIT
        AUDIO_FORMAT_PCM_8_BIT -> AudioFormat.ENCODING_PCM_8BIT
        AUDIO_FORMAT_PCM_32_BIT -> AudioFormat.ENCODING_PCM_32BIT
        AUDIO_FORMAT_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        AUDIO_FORMAT_PCM_24_BIT_PACKED -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        else -> null
    }

    /** `audio_channel_mask_t` → `AudioFormat.CHANNEL_OUT_*`；`null` = 不认识的声道布局 */
    private fun javaChannelMaskOf(mask: Int): Int? = when (mask) {
        AUDIO_CHANNEL_OUT_MONO -> AudioFormat.CHANNEL_OUT_MONO
        AUDIO_CHANNEL_OUT_STEREO -> AudioFormat.CHANNEL_OUT_STEREO
        else -> null
    }
}
