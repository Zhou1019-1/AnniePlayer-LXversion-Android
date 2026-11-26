package com.annieplayer.annie_music.kt.core.audio

enum class DowngradeReason(
    val label: String,
    val advice: String,
) {
    UsbNoPermission("未授予 USB 访问权限", "允许访问后自动恢复"),
    UsbNoDevice("未检测到 USB 音频设备", "接入后自动恢复"),
    UsbPlatformTooOld("系统版本过低", "需要 Android 8.0 及以上"),
    UsbOpenFailed("设备拒绝了这条 USB 流", "可改用 AAudio 独占"),
    UsbStreamLost("USB 流已中断", "已回落到系统输出"),
    AaudioDenied("本机不支持独占输出", "换设备后自动恢复"),
    AaudioStreamLost("独占流已中断", "已回落到系统输出"),
    DapUnavailable("内置 DAC 直出这条流没开成", "已回落系统输出"),
    BluetoothTakesOver("蓝牙接管了链路", "断开蓝牙后自动恢复"),
}

data class DowngradeNotice(
    val wanted: SinkKind,
    val actual: SinkKind,
    val reason: DowngradeReason,
    val detail: String = "",
) {
    val dedupeKey: String get() = "${wanted.key}|${actual.key}|${reason.name}|$detail"

    val transition: String get() = "${wanted.label} → ${actual.label}"

    val reasonText: String get() = if (detail.isEmpty()) reason.label else "${reason.label}（$detail）"
}

fun downgradeNoticeFor(
    wanted: SinkKind,
    actual: SinkKind,
    reason: DowngradeReason? = null,
    detail: String = "",
): DowngradeNotice? {
    if (wanted != SinkKind.usbUac) return null
    if (actual == SinkKind.usbUac) return null
    return DowngradeNotice(wanted, actual, reason ?: DowngradeReason.UsbOpenFailed, detail)
}
