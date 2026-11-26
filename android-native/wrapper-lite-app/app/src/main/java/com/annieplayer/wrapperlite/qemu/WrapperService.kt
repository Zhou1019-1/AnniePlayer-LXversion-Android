package com.annieplayer.wrapperlite.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class WrapperService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastUptime = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            val uptime = SystemClock.uptimeMillis()
            val frozenMillis = (uptime - lastUptime) - HEARTBEAT_MILLIS
            lastUptime = uptime
            if (frozenMillis >= MIN_REPORT_MILLIS) {
                WrapperLite.appendLog(
                    "[local] 后台被冻结 ${frozenMillis / 1000} 秒（系统限制了后台运行）",
                )
            }
            handler.postDelayed(this, HEARTBEAT_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        WrapperLite.init(applicationContext)
        createChannel()
        startInForeground("运行中")
        acquireWakeLock()
        lastUptime = SystemClock.uptimeMillis()
        handler.postDelayed(heartbeat, HEARTBEAT_MILLIS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("运行中")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        WrapperLite.stopServing()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        WrapperLite.appendLog("[local] 前台服务达到系统时限（type=$fgsType），已按系统要求停止")
        WrapperLite.stopServing()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        releaseWakeLock()
        WrapperLite.onServiceDestroyed()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Wrapper Lite")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wrapper Lite 服务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WrapperLite::qemu").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private companion object {
        const val CHANNEL_ID = "wrapper_lite_service"
        const val NOTIFICATION_ID = 1201
        const val HEARTBEAT_MILLIS = 30_000L
        const val MIN_REPORT_MILLIS = 15_000L
    }
}
