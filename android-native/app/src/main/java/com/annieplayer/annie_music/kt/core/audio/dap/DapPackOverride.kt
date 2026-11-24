package com.annieplayer.annie_music.kt.core.audio.dap

/**
 * Native DSD 线格式的**运行时覆盖**（诊断用，定位完删掉）。
 *
 * 为什么需要：`openRaw` 按 `codec_id` 推位序（DSF 是 LSB-first → 位反转），而容器
 * （`audio_format_t` 的 DSD 段）对位序另有要求，两者叠加是四种组合。打错方向**不会报错**：
 * `set()` 照样返回 0、轨道照样排空，只是 DAC 解出来是噪声。
 *
 * | 值 | 位序 | 字序 |
 * |---|---|---|
 * | `le-rev`   | 反转（MSB 优先） | 小端 |
 * | `be-rev`   | 反转             | 大端（= 字节顺序即时间顺序） |
 * | `le-norev` | 不反转           | 小端 |
 * | `be-norev` | 不反转           | 大端 |
 *
 * [compose] 是**唯一**的事实源：调用方只问一次，一次拿到（字序、位序、标签），
 * 不要再从两个地方各取一半 —— 分两次读会让 `rotate` 各吃掉一个组合。
 *
 * 写 `rotate` 则**每次取用换下一种**（顺序见 [ORDER]，指针存在 `dsd-pack-idx.txt`）。
 * 想让它在**同一首、不切歌**的前提下换组合：[changed] 在 `files/dsd-pack.txt` 的 mtime
 * 或长度动了时返回 true，实现方据此就地重配并倒回 0 重开（见 `FfmpegLocalExtractor`
 * 的 `pollPackChange`）。`adb shell touch` 一下就是"换下一种"。
 */
internal object DapPackOverride {

    /**
     * 一种具体的线格式。
     *
     * @param byteInterleaved `true` = **按字节交错**（`L0 R0 L1 R1 | L2 R2 L3 R3`，即 native 的
     *   `packMode = 0` 裸交错）；`false` = 按 32 位字交错（`[L0 L1 L2 L3][R0 R1 R2 R3]`）。
     *
     *   这一维此前**一次都没试过**：四种 `be/le × rev/norev` 组合只改了"字的字节顺序"，
     *   从没改过"交错粒度"。如果 HAL 把连续 4 字节当成同一路样本的连续位，那按字交错会让
     *   每个"字"里混进另一声道的数据 —— 满幅噪声，且与字节序/位序完全无关。
     */
    internal data class Choice(
        val bigEndian: Boolean,
        val bitReverse: Boolean,
        val byteInterleaved: Boolean,
        val label: String,
    )

    /** 没有覆盖文件时 [compose] 给出的标签 */
    const val DEFAULT_LABEL = "default"

    /**
     * `rotate` 的轮换顺序。
     *
     * **顺序本身就是实验设计**：已实测排除的排在最后（`be-rev`/`le-rev` 都是满幅噪声），
     * 没测过的排前面 —— 先试"交错粒度"（从没试过的维度），再试"关掉位反转"。
     */
    private val ORDER = listOf(
        Choice(bigEndian = true, bitReverse = true, byteInterleaved = true, label = "byte-rev"),
        Choice(bigEndian = false, bitReverse = true, byteInterleaved = true, label = "byte-le-rev"),
        Choice(bigEndian = true, bitReverse = false, byteInterleaved = false, label = "be-norev"),
        Choice(bigEndian = false, bitReverse = false, byteInterleaved = false, label = "le-norev"),
        Choice(bigEndian = true, bitReverse = true, byteInterleaved = false, label = "be-rev"),
        Choice(bigEndian = false, bitReverse = true, byteInterleaved = false, label = "le-rev"),
    )

    @Volatile
    private var spec: String? = null

    /** `rotate` 用：下一次该给第几种 */
    @Volatile
    private var rotateIndex: Int = 0

    @Volatile
    private var indexFile: java.io.File? = null

    /** [load] 给过的那个文件；[changed] 靠它再读一次，因此不需要 Context */
    @Volatile
    private var sourceFile: java.io.File? = null

    /**
     * 要不要用 **16 bit 容器**（`files/dsd-container.txt` 写 `16` 时开）。
     *
     * `0x1A000001 @ bitRate/16` = 每声道 2 字节 / 4 字节一帧。选它而不是 32 bit 容器
     * （`0x1A000002 @ bitRate/32`）的理由是我们自己的探测记录：
     *
     * ```
     * dsd-vendor | 0x1A000002 | 88200  | pos=0        ← 现在的（音乐 + 连续沙沙）
     * dsd-msb    | 0x1A000001 | 176400 | pos=14079    ← 唯一播放头真的在走的组合
     * ```
     *
     * 若 HAL 实际按 16 bit 吃而我们送 32 bit 字，等于每个字只用了半个 ——
     * 听感正是"音乐还在、底噪抬高"，而不是纯噪声。
     */
    @Volatile
    var container16: Boolean = false
        private set

    /**
     * 开流前要**多送**多少字节的 DSD 静音（`files/dsd-pad.txt` 里的整数）。
     *
     * 存在的唯一理由：飞傲 HAL 在 `processAudioInit` 之后会打
     * `processAudioEvent:skip frame 1` —— **它每次开流丢掉 1 个 frame**。
     * 而 AudioFlinger 对这个私有 format 算出的帧长是 1 字节
     * （`Processing frame size: 1 bytes`），所以被吃掉的很可能是 1 个**字节**。
     * 丢 1 字节之后每个 32 位字都变成"上一字节的尾巴 + 下一字节的头"，
     * 左右声道混在一起 ⇒ 满幅噪声，且**与字节序、位序完全无关**
     * （USB 独占同一条字节流能出声，就是因为它不丢）。
     *
     * 试法：写 1（或 8）→ 重开 App → 听。哪一种出音乐就写死成默认。
     */
    @Volatile
    var padBytes: Int = 0
        private set

    /** 上次观测到的 (mtime, 长度)：[changed] 只比这两个数 */
    @Volatile
    private var seenMtime: Long = 0L

    @Volatile
    private var seenLength: Long = -1L

    /** 从 `files/dsd-pack.txt` 读一行；没有 / 认不出就 null（= 用调用方给的默认值） */
    @Synchronized
    fun load(file: java.io.File) {
        sourceFile = file
        spec = parse(file)
        indexFile = file.parentFile?.let { java.io.File(it, INDEX_NAME) }
        val raw = runCatching {
            indexFile?.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        }.getOrNull()
        // 越界 / 损坏的指针从头轮换：coerceIn 会静默落到 last，看起来像"轮换没生效"
        rotateIndex = if (raw != null && raw in ORDER.indices) raw else 0
        padBytes = runCatching {
            file.parentFile?.let { java.io.File(it, PAD_NAME) }
                ?.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        }.getOrNull()?.coerceIn(0, MAX_PAD_BYTES) ?: 0
        container16 = runCatching {
            file.parentFile?.let { java.io.File(it, CONTAINER_NAME) }
                ?.takeIf { it.isFile }?.readText()?.trim()
        }.getOrNull() == "16"
        seenMtime = runCatching { file.lastModified() }.getOrDefault(0L)
        seenLength = runCatching { file.length() }.getOrDefault(0L)
    }

    /**
     * 这一次开流该用哪种线格式。**永远有返回值**：没有覆盖就用调用方给的默认值。
     *
     * **一次开流只调一次**：`rotate` 模式下它会推进指针，调两次会吃掉一个组合 ——
     * 这也正是字序与位序必须一起取（而不是分两个 getter）的原因。
     */
    @Synchronized
    fun compose(defaultBigEndian: Boolean, defaultBitReverse: Boolean): Choice {
        val s = spec ?: return Choice(defaultBigEndian, defaultBitReverse, false, DEFAULT_LABEL)
        if (s == ROTATE) {
            val choice = ORDER[rotateIndex]
            rotateIndex = (rotateIndex + 1) % ORDER.size
            runCatching { indexFile?.writeText(rotateIndex.toString()) }
            return choice
        }
        // `dsd660` 只决定**帧内布局**，与位序无关：没写 rev/norev 就沿用调用方的默认位序
        if (s.startsWith(DSD660) && !s.endsWith("rev") && !s.endsWith("norev")) {
            return Choice(true, defaultBitReverse, false, s)
        }
        return choiceOf(s)
    }

    /**
     * `files/dsd-pack.txt` 变了吗（mtime 或长度动过）。变了返回一次 true，并重新解析内容；
     * 调用方接着调 [compose] 拿新组合。
     *
     * 比 mtime 而不是只比内容，是为了 `rotate` 能"原地换下一种"：
     * `adb shell touch dsd-pack.txt` 只改时间、不改内容。
     */
    @Synchronized
    fun changed(): Boolean {
        val f = sourceFile ?: return false
        val mtime = runCatching { f.lastModified() }.getOrDefault(0L)
        val length = runCatching { f.length() }.getOrDefault(0L)
        if (mtime == seenMtime && length == seenLength) return false
        seenMtime = mtime
        seenLength = length
        spec = parse(f)
        return true
    }

    private fun parse(file: java.io.File): String? {
        val text = runCatching {
            if (file.isFile) file.readText().trim().lowercase() else ""
        }.getOrDefault("")
        return when {
            text.isEmpty() -> null
            text == ROTATE -> ROTATE
            text.startsWith(DSD660) -> text
            text.startsWith("byte") || text.startsWith("be") || text.startsWith("le") -> text
            else -> null
        }
    }

    private fun choiceOf(s: String) = Choice(
        bigEndian = s.startsWith("be"),
        // "norev" 也以 "rev" 结尾，必须先排除
        bitReverse = s.endsWith("rev") && !s.endsWith("norev"),
        // `byte` = 按字节交错（裸交错），`byte-le` 只是给个能和 le 家族区分的写法
        byteInterleaved = s.startsWith("byte"),
        label = s,
    )

    private const val ROTATE = "rotate"

    private const val DSD660 = "dsd660"
    private const val INDEX_NAME = "dsd-pack-idx.txt"
    private const val PAD_NAME = "dsd-pad.txt"
    private const val CONTAINER_NAME = "dsd-container.txt"

    /**
     * 补头上限（字节）。取 20 秒 DSD64（705 600 B/s）——够长到能单独听清这一段，
     * 又不至于让一次实验等太久。这个旋钮有两个用途：
     * 1. 补偿飞傲 HAL 开流时的 `skip frame 1`（小值，1/4/8/9）；
     * 2. **判定端点是否真的在 DSD 模式**（大值，例如 14 112 000 = 20 秒纯 `0x96`）：
     *    DSD 模式下 `0x96` 交替位型的均值为零 = 静音；若端点其实按 PCM 解，
     *    `0x96969696` 是一个满幅常量 —— 听感是轰响而不是静音。
     */
    const val MAX_PAD_BYTES = 20 * 705_600
}
