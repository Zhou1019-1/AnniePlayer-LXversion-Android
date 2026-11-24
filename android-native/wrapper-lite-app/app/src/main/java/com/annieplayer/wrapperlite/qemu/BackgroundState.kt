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
        opsBlocked -> "后台运行权限被禁（厂家面板限制，白名单救不回来）"
        bucketRestricted -> "系统把本应用判为受限（耗电管理没设无限制）"
        dozeExempt != true -> if (oemPanelRequired) {
            "未进白名单 · 去系统「耗电管理」设完全允许后台行为"
        } else {
            "未加入 Doze 白名单"
        }
        oemPanelRequired -> "白名单已加 · 系统「耗电管理」还需设完全允许后台行为"
        else -> "Doze 白名单已加"
    }
}
