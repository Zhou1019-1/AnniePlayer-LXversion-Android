package com.annieplayer.wrapperlite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    override fun onResume() {
        super.onResume()
        WrapperLite.reportBackgroundBlockIfAny(this)
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
}
