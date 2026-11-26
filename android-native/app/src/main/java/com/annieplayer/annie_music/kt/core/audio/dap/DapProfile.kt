package com.annieplayer.annie_music.kt.core.audio.dap

/**
 * DAP 厂商家族 —— 只用来决定「候选 format 怎么排」，不参与任何能力判断。
 *
 * ## 为什么需要它
 *
 * 内置 DAC 的 DSD 直通靠的是**私有 `audio_format_t` 值**，不同厂商在 AOSP 的 DSD 段上
 * 各挑一个（见 [dapDsdCandidates]）。识别厂商的唯一目的，是把这些值排对顺序 ——
 * 排错了不会坏，只会多试几次。
 *
 * ## 为什么不识别手机厂的 Hi-Fi 模式
 *
 * LG Quad DAC / vivo / ROG / 小米这些是**手机上的 Hi-Fi 通路**，和 DAP 的内置 DAC 不是一回事：
 * 能力来源、要写的属性都不同。混进来只会把候选表撑大，而没有任何证据支撑。
 * 真要做，是另一个枚举、另一张表。
 */
enum class DapVendor(val key: String, val label: String) {
    fiio("fiio", "FiiO"),
    hiby("hiby", "HiBy"),
    ibasso("ibasso", "iBasso"),
    shanling("shanling", "Shanling"),
    onix("onix", "Onix"),
    astell("astell", "Astell&Kern"),
    cayin("cayin", "Cayin"),
    tempotec("tempotec", "TempoTec"),
    sony("sony", "Sony Walkman"),
    unknown("unknown", "未知"),
    ;

    /** 是不是一台内置 DAC 的 DAP（[unknown] 也可能是，只是没认出来） */
    val isDap: Boolean get() = this != unknown
}

/**
 * 厂商识别。判据只有两种，都不用 root、不用厂商私有库：
 *
 * 1. **`Build` 字段**：`MANUFACTURER` / `BRAND` / `MODEL` / `PRODUCT` / `DEVICE` 里
 *    出现厂商标记；
 * 2. **`File.exists()`**：某些 ROM 只在文件系统里留了厂商库文件名，`Build` 字段却是通用值
 *    （飞傲就是这样，它的 `MANUFACTURER` 未必带 fiio）。
 *
 * 字段清单是照反编译抄的：飞傲判定（`sub_227210`）逐个读
 * `ro.product.manufacturer` / `.brand` / `.model` / `.device` / `.name`，五个都做
 * 大小写不敏感的子串匹配，命中任一即算。`ro.product.name` 就是 `Build.PRODUCT`、
 * `ro.product.device` 就是 `Build.DEVICE` —— **少一个 `DEVICE` 就会漏掉一部分飞傲机器**。
 *
 * 厂商库**只用存在性**当标记，从不 `dlopen` —— 逆向对照：那台实现也是四个路径
 * `access()` 四选一，`libfiioaudio.so` 从不加载。
 *
 * ## 为什么 `fileExists` 是参数
 *
 * 把 IO 抽出去之后，识别逻辑本身变成纯字符串匹配，JVM 单测不用造文件。
 *
 * @param fileExists 探测文件是否存在；默认恒 false（纯字段判定）
 * @param device `Build.DEVICE`（`ro.product.device`）
 */
fun detectDapVendor(
    manufacturer: String?,
    brand: String?,
    model: String?,
    product: String?,
    fileExists: (String) -> Boolean = { false },
    device: String? = null,
): DapVendor {
    val hay = listOf(manufacturer, brand, model, product, device)
        .mapNotNull { it?.trim()?.lowercase() }
        .filter { it.isNotEmpty() }

    fun has(vararg needles: String): Boolean =
        hay.any { field -> needles.any { field.contains(it) } }

    if (has("fiio") || FIIO_MARKER_FILES.any { fileExists(it) }) return DapVendor.fiio
    if (has("hiby")) return DapVendor.hiby
    if (has("ibasso")) return DapVendor.ibasso
    if (has("shanling")) return DapVendor.shanling
    if (has("onix")) return DapVendor.onix
    if (has("cayin")) return DapVendor.cayin
    if (has("tempotec")) return DapVendor.tempotec
    // Astell&Kern 的字段写法五花八门：`astell&kern` / `astell & kern` / `astellnkern` / 只写 `astell`。
    // 小写化后它们都含 `astell`，一个判据覆盖四种写法。
    if (has("astell")) return DapVendor.astell
    // Sony 只认 Walkman 的型号前缀：Xperia 也是 sony，但它没有这条内置 DAC 通路
    if (has("sony") && SONY_WALKMAN_MODELS.any { m -> hay.any { it.contains(m) } }) {
        return DapVendor.sony
    }
    return DapVendor.unknown
}

/** 飞傲的库文件名：只当机型标记用，从不加载 */
internal val FIIO_MARKER_FILES: List<String> = listOf(
    "/vendor/lib64/libfiioaudio.so",
    "/vendor/lib/libfiioaudio.so",
    "/system/lib64/libfiioaudio.so",
    "/system/lib/libfiioaudio.so",
)

/** Sony Walkman 的型号前缀（`NW-ZX` / `NW-A` / `NW-WM`） */
internal val SONY_WALKMAN_MODELS: List<String> = listOf("nw-zx", "nw-a", "nw-wm")

/**
 * HiBy OS 那族的标记库路径（`sub_228188`）。
 *
 * 它决定两件事：`libsmartaudioservice.so` 存在 ⇒ 候选表里 `0x1A000001` 那一条
 * 用 **word 率**（`bitRate / 32`）而不是 `bitRate / 16`，且排在最后一条。
 * 同 [FIIO_MARKER_FILES] —— 只 `access()`，从不 `dlopen`。
 */
internal val SMART_AUDIO_MARKER_FILES: List<String> = listOf(
    "/system/lib64/libsmartaudioservice.so",
    "/vendor/lib64/libsmartaudioservice.so",
    "/system/lib/libsmartaudioservice.so",
    "/vendor/lib/libsmartaudioservice.so",
)

/** 没有该库时按品牌兜底判 smartaudio 一族（`sub_228884` 里的三个词） */
internal val SMART_AUDIO_BRANDS: List<String> = listOf("hiby", "cayin", "tempotec")

/** FPGA 类 DAP 的节点。存在 ⇒ 整张候选表都不一样（见 [dapDsdCandidates]） */
internal const val FPGA_AUDIO_NODE: String = "/sys/class/fpga/fpga0/fpga_audio"

/**
 * 是不是 smartaudio 一族（HiBy OS / Cayin / TempoTec）。
 *
 * 判据与反编译一致：先看四个库路径，再退回品牌子串。**两个 `fileExists` 分开传**
 * 是为了让 JVM 单测能只造一个命中（同一个 lambda 也行，分开更清楚）。
 */
fun isSmartAudioFamily(
    manufacturer: String?,
    brand: String?,
    model: String?,
    fileExists: (String) -> Boolean = { false },
): Boolean {
    if (SMART_AUDIO_MARKER_FILES.any { fileExists(it) }) return true
    val hay = listOf(manufacturer, brand, model)
        .mapNotNull { it?.trim()?.lowercase() }
        .filter { it.isNotEmpty() }
    return hay.any { field -> SMART_AUDIO_BRANDS.any { field.contains(it) } }
}
