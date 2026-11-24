package com.annieplayer.wrapperlite.qemu

data class BackgroundState(
    val dozeExempt: Boolean?,
    val bucketRestricted: Boolean,
    val opsBlocked: Boolean,
    val oemPanelRequired: Boolean = false,
) {
    val unrestricted: Boolean
        get() = dozeExempt == true && !bucketRestricted && !opsBlocked

    fun describe(): String = when {
        opsBlocked -> "后台运行权限被禁"
        bucketRestricted -> "系统把本应用判为受限"
        dozeExempt != true -> "未进白名单"
        oemPanelRequired -> "白名单已加 · 耗电管理未确认"
        else -> "Doze 白名单已加"
    }

    val actionHint: String
        get() = if (oemPanelRequired) {
            "在「耗电管理」里选「完全允许后台行为」"
        } else {
            "在系统电池设置里把本应用设为「不受限制」"
        }
}
