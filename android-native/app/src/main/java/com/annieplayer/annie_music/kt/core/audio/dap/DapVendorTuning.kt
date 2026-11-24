package com.annieplayer.annie_music.kt.core.audio.dap

import android.content.Context
import android.media.AudioManager

/**
 * 厂商 HAL 的"切模式"开关 —— 目前只有飞傲有。
 *
 * ## 依据（逐条对照反编译）
 *
 * Resonāda 的 `FiioDsdHelper` 在建内置 DAC 的 DSD 流**之前**下发两个参数，
 * 退出时**必需**复位：
 *
 * ```
 * sampling_rate = bitRate / 32      // DSD64 → 88200
 * format        = 436207618         // 0x1A000002，与候选表里那个 format 同值
 * // 退出：
 * format        = 1                 // AUDIO_FORMAT_PCM_16_BIT
 * sampling_rate = pcmRate
 * ```
 *
 * 全 App 只有这一处 `AudioManager.setParameters`，而且**只对飞傲下发**
 * （别的厂商靠 `format` 正确就够了，多下发反而可能被 HAL 误读）。
 *
 * ## 为什么走反射
 *
 * `AudioManager.setParameters(String)` 在 SDK 里是隐藏的（`@UnsupportedAppUsage`），
 * 直接调编不过。它是**公开存在于所有机型**的 framework 方法，不是厂商扩展，
 * 所以反射拿一次、缓存住即可；拿不到就说明这个 ROM 删了它 —— 那时功能降级为"只靠 format"，
 * 由探测结果说话，不在这里假装成功。
 */
internal object DapVendorTuning {

    private val setParameters: java.lang.reflect.Method? by lazy {
        runCatching {
            AudioManager::class.java.getMethod("setParameters", String::class.java)
        }.getOrNull()
    }

    val supported: Boolean get() = setParameters != null

    private val setCodecParam: java.lang.reflect.Method? by lazy {
        runCatching {
            AudioManager::class.java.declaredMethods.firstOrNull { m ->
                m.name == "setCodecParam" && m.parameterTypes.size == 4 &&
                    m.parameterTypes.all { it == Int::class.javaPrimitiveType }
            }
        }.getOrNull()
    }

    val codecParamSupported: Boolean get() = setCodecParam != null

    private fun audioManager(context: Context?): AudioManager? =
        context?.applicationContext?.let {
            runCatching { it.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()
        }

    /**
     * 厂商 codec 参数（飞傲 HAL 白名单 id=100）。
     *
     * 依据：飞傲自家 Player 在建 DSD 轨之前反射调
     * `AudioManager.setCodecParam(100, isMQA ? 2 : isDSD ? 1 : 0, rate, 0)`，
     * 而 `/vendor/lib64/libfiioaudio.so` 的 `fiio_adev_set_codec_param` 恰好只放行 id==100(0x64)。
     * 它是"告诉 HAL 这一轨是 DSD"的通道，与 `setParameters` 的 `format=` 不是一回事。
     */
    private fun applyCodecParam(context: Context?, value: Int, rate: Int): String {
        if (context == null) return "codec=no-context"
        val m = setCodecParam ?: return "codec=no-method"
        val am = audioManager(context) ?: return "codec=no-audio-manager"
        val ok = runCatching { m.invoke(am, 100, value, rate, 0); true }.getOrDefault(false)
        return "codec=100/$value/$rate${if (ok) "" else "(失败)"}"
    }

    /** 依次下发；返回下发了什么（进探测记录），失败也返回原因 */
    fun apply(context: Context?, pairs: List<Pair<String, String>>): String {
        if (context == null) return "no-context"
        val m = setParameters ?: return "no-setParameters"
        val am = runCatching {
            context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }.getOrNull() ?: return "no-audio-manager"
        val done = ArrayList<String>(pairs.size)
        for ((k, v) in pairs) {
            val ok = runCatching { m.invoke(am, "$k=$v"); true }.getOrDefault(false)
            done += "$k=$v${if (ok) "" else "(失败)"}"
        }
        return done.joinToString(",")
    }

    /**
     * 进入 DSD 模式。**必须在建流之前**下发。
     *
     * @return 下发了什么；`""` = 这个厂商不需要（非飞傲）
     */
    fun enterDsd(context: Context?, vendor: DapVendor, bitRate: Int): String {
        if (vendor != DapVendor.fiio || bitRate <= 0) return ""
        val wordRate = bitRate / DAP_WORD_DIVISOR
        val params = apply(
            context,
            listOf(
                "sampling_rate" to wordRate.toString(),
                "format" to AUDIO_FORMAT_DSD_VENDOR_PACKED.toString(),
            ),
        )
        val codec = applyCodecParam(context, 1, wordRate)
        return "$params,$codec"
    }

    /**
     * 退出 DSD 模式。**必需**：不复位的话下一次普通 PCM 播放会带着 DSD 的 `format`
     * 进 HAL，表现为"放什么都沙沙响"。
     */
    fun reset(context: Context?, vendor: DapVendor, pcmRate: Int): String {
        if (vendor != DapVendor.fiio) return ""
        val params = apply(
            context,
            listOf(
                "format" to AUDIO_FORMAT_PCM_16_BIT.toString(),
                "sampling_rate" to pcmRate.toString(),
            ),
        )
        val codec = applyCodecParam(context, 0, pcmRate)
        return "$params,$codec"
    }
}
