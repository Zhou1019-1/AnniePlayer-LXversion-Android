package com.annieplayer.annie_music.kt.core.audio.dap

import com.annieplayer.annie_music.kt.core.audio.DsdLevel

/**
 * 内置 DAC 直通的**实测结论** —— 纯数据 + 文本编解码，可 JVM 单测。
 *
 * ## 为什么结论必须来自实测
 *
 * 候选表里那些 `0x1A0000xx` 是**私有** `audio_format_t`，没有任何设备会声明它存在的。
 * 唯一的判据是：真的开一条轨、真的写进去、播放头真的走了。三件事全成立才算"能用"。
 * 只看 `set()` 返 0 不够 —— HAL 收下参数之后仍然可能不出声（`variant` 不匹配、
 * 端口被别的 App 占着、DAC 反相静音）。
 *
 * 所以 [DapFormatVerdict] 把三段结果分开记：
 *
 * | 段 | 字段 | 说明 |
 * |---|---|---|
 * | 建流 | [DapFormatVerdict.openResult] | `0` = `set()` 接受；负 = [NativeDapDac] 的错误码 |
 * | 写入 | [DapFormatVerdict.writeBytes] | `0` 一个字节都没排上；`<0` 流坏了 |
 * | 出声 | [DapFormatVerdict.framesAfter] − [DapFormatVerdict.framesBefore] | 播放头前进量 |
 */
internal const val DAP_CAPABILITY_VERSION = 1

/**
 * 探测时一次写多少帧。
 *
 * 放在这里而不是探测器私有：判断"这条流是不是真的在消费"要用它换算成"几个缓冲"，
 * 而那个判断属于数据模型（[DapFormatVerdict.played]），不属于探测器。
 */
internal const val DAP_PROBE_WRITE_FRAMES = 2048

/** 一条候选的实测结果。**不是**"这台机器支持什么"的声明 —— 是"这一组参数当时成立"。 */
internal data class DapFormatVerdict(
    val tag: String,
    val format: Int,
    val sampleRate: Int,
    val outputFlags: Int,
    val bytesPerFrame: Int,
    /** `set()` 的结果：`0` 成功，负数是 [NativeDapDac] 的错误码 */
    val openResult: Int,
    /**
     * `set()` 原样返回的 `status_t`（只在对它有意义时有值）。
     *
     * 单独记它是因为 [openResult] 只区分"换成下一个候选"还是"整条路放弃"，
     * 而排错要看具体值：`BAD_VALUE(-22)` 是参数非法、`INVALID_OPERATION(-38)` 是
     * 这台设备的 HAL 没有那个端口、`PERMISSION_DENIED(-1)` 是 attribution 有问题。
     * 三者的修法完全不同，只看"被拒了"等于没信息。
     */
    val setStatus: Int,
    /** 一次写进去的字节数；`-1` = 没走到写入这一步 */
    val writeBytes: Int,
    val framesBefore: Long,
    val framesAfter: Long,
    /**
     * 卡在哪个阶段：`""` = 没卡；`open` / `play` / `write` / `position` / `close` 之一。
     *
     * 必须单独记。超时后 `openResult` 只能是 [NativeDapDac.ERR_TIMEOUT]（否则会被当成
     * "被拒"），而"卡在 play"和"卡在 close"是完全不同的两件事：前者不能开始播，
     * 后者只是收尾时卡 —— 后者其实可以先出声，只是收不干净。
     */
    val stalledAt: String = "",
    /**
     * native 那三段的原样返回值，形如 `set=0 start=-38 pos=-38`。
     *
     * 单独留一栏而不是拆成三个 int：它们的**组合**才是结论 ——
     * `start` 失败时 `getPosition` 恒 0 是必然的，把它当"设备不出声"就是误判。
     */
    val notes: String = "",
) {
    val opened: Boolean get() = openResult == NativeDapDac.OK

    /** 播放头前进量；没开流时是 0 */
    val framesAdvanced: Long get() = if (framesAfter > framesBefore) framesAfter - framesBefore else 0L

    /** 写进去的字节数够不够两个缓冲 —— 够就说明服务端**至少排空过一次** */
    val drainedBuffers: Int
        get() {
            val oneWrite = bytesPerFrame * DAP_PROBE_WRITE_FRAMES
            if (oneWrite <= 0 || writeBytes <= 0) return 0
            return writeBytes / oneWrite
        }

    /**
     * 这条流是活的：建流成功 + 写进去的字节够**半秒音频**。
     *
     * ## 为什么不是"播放头前进"
     *
     * 实测同一条候选跑两次，`getPosition` 一次返回前进 879 帧、一次返回 `-38`
     * （`INVALID_OPERATION`）。厂商 DSD 端口上的位置上报**不可靠**，拿它当门槛会把
     * 本来能出声的组合判死。
     *
     * ## 为什么也不是"能写进两个缓冲"
     *
     * 那是**更松**的一条，实测把它判成了假阳性：JM21 上 `0x1A000002 @ 88200` 只吃进
     * 51 664 字节（0.07 秒）就不再取，`framesAfter` 前进 1319 帧（15 ms）——
     * 旧判据（两个缓冲 + 播放头动过）照样判"通过"，于是播放时 buffer 填满（`FrmRdy` 满格、
     * `Underruns` 三万），进度条钉在 0.00。**能写进 ≠ 会持续取。**
     *
     * 半秒是本机环深（0.2 秒）+ 探针窗口（0.9 秒）之间留足余量后的判别线：
     * 真在消费的流在窗口里能写进 ≈0.9 秒的量，停摆的连 0.1 秒都过不去。
     */
    val played: Boolean
        get() {
            if (!opened || writeBytes <= 0 || sampleRate <= 0) return false
            val halfSecondBytes = sampleRate.toLong() * bytesPerFrame / 2
            return writeBytes.toLong() >= halfSecondBytes
        }

    /** 位置可用（`getPosition` 真的动过）。可用就用它做时钟，不可用就走时间轴。 */
    val positionOk: Boolean get() = played && framesAdvanced > 0L

    /** 有没有走 DIRECT 端口 —— DSD 数据被 mixer 碰过就毁了，这一项比"能播"更硬 */
    val isDirect: Boolean get() = outputFlags and AUDIO_OUTPUT_FLAG_DIRECT != 0

    /** `0x1A000002 @ 88200 · flags=1 · dsd-vendor` —— 给人看的一行 */
    val label: String
        get() = "0x${format.toString(16)} @ $sampleRate · flags=$outputFlags · $tag"

    /** 一行诊断：建流 / 写入 / 播放头三段各是什么，省得回头再猜 */
    val diagnostic: String
        get() {
            val tail = if (notes.isEmpty()) "" else "（$notes）"
            return when {
                stalledAt.isNotEmpty() ->
                    "$label → 卡在 $stalledAt 阶段不返回（建流=${if (opened) "成功" else "未成"}" +
                        "，写入=$writeBytes，播放头 $framesBefore→$framesAfter）$tail"
                openResult == NativeDapDac.ERR_TIMEOUT -> "$label → open 阶段超时不返回$tail"
                openResult == NativeDapDac.ERR_TEARDOWN_STUCK ->
                    "$label → 该 ROM 析构不返回，直出已停用$tail"
                !opened -> "$label → 拒 open=$openResult set=$setStatus$tail"
                writeBytes <= 0 -> "$label → 建流成功但写不进（write=$writeBytes）$tail"
                framesAdvanced <= 0L -> "$label → 写进 $writeBytes 字节但播放头不动$tail"
                else -> "$label → 出声，播放头 +$framesAdvanced 帧$tail"
            }
        }
}

/**
 * 一次探测的完整结果。
 *
 * [fingerprint] 把结论绑定到 ROM + 机器：换 ROM / 换机型之后旧结论必须作废
 * （同一台设备刷了别的固件，厂商 HAL 的 DSD 端口可能整个不见）。
 */
internal data class DapCapability(
    val fingerprint: String,
    val nativeStatus: Int,
    val abi: Int,
    /** 探测时用的档位。候选表只随档位换算率，`format`/`flags` 那部分不随档位变 */
    val probeLevel: DsdLevel,
    val verdicts: List<DapFormatVerdict>,
    /** 厂商模式开关下发了什么（飞傲才有）；空 = 没下发过 */
    val vendorNote: String = "",
) {
    /** native 私有 ABI 这条**整条路**能不能走（符号解析 + ABI 判定） */
    val reachable: Boolean get() = nativeStatus == NativeDapDac.OK

    /**
     * 生产用的那一条。
     *
     * 排序不是随便定的：
     *
     * 1. **必须先走 DIRECT** —— 只要过一次 AudioFlinger 的 mixer，音量就是一次 float 乘法，
     *    DSD 的 1-bit 流会被改写成噪声。这条比"能不能播"更硬。
     * 2. **容器必须是我们真能喂的那种** —— raw 管线只会产 32 位字（`chunk = 8 B/帧`，
     *    `0x1A000002` / `0x1A000003`）。`0x1A000001` 是 16 bit 容器（4 B/帧），
     *    打不进同一条管线：选它 = 形状对不上 = 降级回系统通路 = **满幅噪声**。
     *    实测踩到：`0x1A000001` 因为 `getPosition` 可用，把能喂的 `0x1A000002` 挤掉了。
     * 3. 同档之内**位置能用的优先** —— 有位置就能做 A/V 同步与精确 seek；
     *    没有就退回时间轴估算（见 [positionOk]：厂商 DSD 端口上它不可靠）。
     * 4. 同档之内保持**候选表原顺序**（那是反编译出来的厂商偏好）。
     */
    val working: DapFormatVerdict?
        get() = verdicts.firstOrNull { it.played && it.feedable && it.isDirect && it.positionOk }
            ?: verdicts.firstOrNull { it.played && it.feedable && it.isDirect }
            ?: verdicts.firstOrNull { it.played && it.feedable }
            ?: verdicts.firstOrNull { it.played && it.isDirect && it.positionOk }
            ?: verdicts.firstOrNull { it.played && it.isDirect }
            ?: verdicts.firstOrNull { it.played }

    /**
     * 这一条的容器是不是 raw 管线真能产出的形状。
     *
     * 32 bit 容器（`0x1A000002`，8 字节/帧）一直可用；**16 bit 容器（`0x1A000001`，4 字节/帧）
     * 由 `files/dsd-container.txt = 16` 打开**。这个开关存在的理由是我们自己的探测记录：
     * `0x1A000002 @88200` 的 `pos=0`（播放头不动），而 `0x1A000001 @176400` 是唯一
     * `pos` 非零的组合。若 HAL 实际按 16 bit 吃而我们送 32 bit 字，等于每个字只用半个 ——
     * 听感就是"音乐还在但底噪抬高"，而不是纯噪声。
     */
    private val DapFormatVerdict.feedable: Boolean
        get() = when (dapContainerOf(format)) {
            DapContainer.word32 -> true
            DapContainer.short16 -> DapPackOverride.container16
            else -> false
        }

    /** 只是"建成了流"（不保证在消费）。诊断用，不用于选路 */
    val openedAny: DapFormatVerdict? get() = verdicts.firstOrNull { it.opened }

    /** 是不是"测过了，一组都不成立"（与"还没测"要分开 —— 界面措辞不同） */
    val exhaustedWithoutLuck: Boolean get() = reachable && verdicts.isNotEmpty() && working == null

    fun encode(): String = buildString {
        append("dap-capability|").append(DAP_CAPABILITY_VERSION).append('\n')
        append("fingerprint=").append(fingerprint).append('\n')
        append("native=").append(nativeStatus).append('\n')
        append("abi=").append(abi).append('\n')
        append("level=").append(probeLevel.name).append('\n')
        append("vendor=").append(vendorNote).append('\n')
        for (v in verdicts) {
            append("v|").append(v.tag).append('|').append(v.format).append('|')
                .append(v.sampleRate).append('|').append(v.outputFlags).append('|')
                .append(v.bytesPerFrame).append('|').append(v.openResult).append('|')
                .append(v.setStatus).append('|').append(v.writeBytes).append('|')
                .append(v.framesBefore).append('|').append(v.framesAfter).append('|')
                .append(v.stalledAt).append('|').append(v.notes).append('\n')
        }
    }

    companion object {
        /** 解析失败（版本不符 / 字段缺失 / 数字不合法）一律返回 `null`，让调用方重测 */
        fun decode(text: String): DapCapability? {
            val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
            if (lines.size < 5) return null
            val header = lines[0].split('|')
            if (header.size != 2 || header[0] != "dap-capability") return null
            if (header[1].toIntOrNull() != DAP_CAPABILITY_VERSION) return null

            val fields = HashMap<String, String>(4)
            val verdicts = ArrayList<DapFormatVerdict>(12)
            for (line in lines.drop(1)) {
                if (line.startsWith("v|")) {
                    val p = line.split('|')
                    if (p.size != 13) return null
                    verdicts += DapFormatVerdict(
                        tag = p[1],
                        format = p[2].toIntOrNull() ?: return null,
                        sampleRate = p[3].toIntOrNull() ?: return null,
                        outputFlags = p[4].toIntOrNull() ?: return null,
                        bytesPerFrame = p[5].toIntOrNull() ?: return null,
                        openResult = p[6].toIntOrNull() ?: return null,
                        setStatus = p[7].toIntOrNull() ?: return null,
                        writeBytes = p[8].toIntOrNull() ?: return null,
                        framesBefore = p[9].toLongOrNull() ?: return null,
                        framesAfter = p[10].toLongOrNull() ?: return null,
                        stalledAt = p[11],
                        notes = p[12],
                    )
                } else {
                    val eq = line.indexOf('=')
                    if (eq <= 0) return null
                    fields[line.substring(0, eq)] = line.substring(eq + 1)
                }
            }
            return DapCapability(
                fingerprint = fields["fingerprint"] ?: return null,
                nativeStatus = fields["native"]?.toIntOrNull() ?: return null,
                abi = fields["abi"]?.toIntOrNull() ?: return null,
                probeLevel = DsdLevel.entries.firstOrNull { it.name == fields["level"] }
                    ?: return null,
                verdicts = verdicts,
                vendorNote = fields["vendor"] ?: "",
            )
        }
    }
}

/**
 * 写进私有 DSD 流的**静音**图案。
 *
 * 不能写 0：DSD 是 1-bit 密度调制，全 0 是一段直流（满幅负向），真机上下半秒就够
 * 听到一声闷响。两个标准静音图案是 `0x69`（LSB 优先）与 `0x96`（MSB 优先）。
 *
 * 探测时按容器宽度重复这个字节即可 —— 目的只是让 HAL 有数据可送，不是追求音质。
 */
internal fun dapSilencePattern(format: Int): Byte = when (format) {
    AUDIO_FORMAT_DSD_MSB -> 0x96.toByte()
    else -> 0x69.toByte()
}
