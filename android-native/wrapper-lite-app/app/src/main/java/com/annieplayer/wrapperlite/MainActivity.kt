package com.annieplayer.wrapperlite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.annieplayer.wrapperlite.qemu.WrapperLite
import com.annieplayer.wrapperlite.ui.RootScreen
import com.annieplayer.wrapperlite.ui.WrapperLiteTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WrapperLite.init(applicationContext)
        WrapperLite.installIfNeeded()
        askNotificationPermission()
        ensureBatteryOptimization()

        setContent {
            WrapperLiteTheme {
                val phase by WrapperLite.phase.collectAsState()
                val logs by WrapperLite.logs.collectAsState()
                val account by WrapperLite.account.collectAsState()
                val region by WrapperLite.region.collectAsState()
                val tokenHealth by WrapperLite.tokenHealth.collectAsState()
                val progress by WrapperLite.installProgress.collectAsState()

                RootScreen(
                    phase = phase,
                    logs = logs,
                    account = account,
                    region = region,
                    tokenHealth = tokenHealth,
                    progress = progress,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // QEMU 由前台服务托管；这里只回收 UI
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    /**
     * 电池优化白名单：不设成「不限制」时，系统会冻结后台进程，
     * QEMU 停摆 —— 端口还在监听但请求没有任何响应（表现为「检测服务失败」）。
     *
     * 系统对话框只会弹一次（拒绝后不再弹），所以 HomeScreen 上还留了「后台保活」按钮兜底。
     */
    private fun ensureBatteryOptimization() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }
}
