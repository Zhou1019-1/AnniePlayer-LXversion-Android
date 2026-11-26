package com.annieplayer.annie_music.kt.core.platform

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    readDozeExempt(context) == true

fun readBackgroundState(context: Context): BackgroundState = BackgroundState(
    dozeExempt = readDozeExempt(context),
    standbyBucket = readStandbyBucket(context),
    backgroundOpsAllowed = readBackgroundOps(context),
    freeze = FreezeWatch.stats.value,
    oemPanelRequired = oemFamily(),
)

private fun readDozeExempt(context: Context): Boolean? {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull()
}

private fun readStandbyBucket(context: Context): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return null
    return runCatching { usm.appStandbyBucket }.getOrNull()
}

private const val OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

private fun readBackgroundOps(context: Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return null
    return runCatching {
        val uid = context.applicationInfo.uid
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(OP_RUN_ANY_IN_BACKGROUND, uid, context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(OP_RUN_ANY_IN_BACKGROUND, uid, context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }.getOrNull()
}

@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: Context) {
    val wantDialog = !oemFamily() && readDozeExempt(context) == false
    startFirstAvailable(context, settingsCandidates(context, wantDialog))
}

fun openBatteryOptimizationSettings(context: Context) {
    startFirstAvailable(context, settingsCandidates(context, wantDialog = false))
}

private fun brandText(): String = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()

private fun oemFamily(): Boolean {
    val brand = brandText()
    return brand.contains("oppo") ||
        brand.contains("oneplus") ||
        brand.contains("oplus") ||
        brand.contains("realme") ||
        brand.contains("xiaomi") ||
        brand.contains("redmi") ||
        brand.contains("poco") ||
        brand.contains("huawei") ||
        brand.contains("honor") ||
        brand.contains("vivo") ||
        brand.contains("iqoo") ||
        brand.contains("samsung") ||
        brand.contains("meizu") ||
        brand.contains("nubia") ||
        brand.contains("lenovo")
}

private fun colorOsFamily(): Boolean {
    val brand = brandText()
    return brand.contains("oppo") ||
        brand.contains("oneplus") ||
        brand.contains("oplus") ||
        brand.contains("realme")
}

private fun oemCandidates(): List<Intent> {
    val brand = brandText()
    val targets: List<Pair<String, String>> = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        brand.contains("huawei") || brand.contains("honor") -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        brand.contains("vivo") || brand.contains("iqoo") -> listOf(
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        brand.contains("samsung") -> listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        else -> emptyList()
    }
    return targets.map { (pkg, cls) ->
        Intent().setComponent(android.content.ComponentName(pkg, cls))
    }
}

private fun settingsCandidates(context: Context, wantDialog: Boolean): List<Intent> {
    val pkg = context.packageName
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", pkg, null)
    }
    val whitelist = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val root = Intent(Settings.ACTION_SETTINGS)
    val pages = if (colorOsFamily()) {
        listOf(details, whitelist, root)
    } else {
        listOf(whitelist, details, root)
    }
    val oem = oemCandidates()
    if (!wantDialog) return oem + pages
    val dialog = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.fromParts("package", pkg, null)
    }
    return listOf(dialog) + oem + pages
}

private fun startFirstAvailable(context: Context, candidates: List<Intent>) {
    for (intent in candidates) {
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        if (!resolvable) continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (ok) return
    }
}
