package com.annieplayer.wrapperlite.qemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 前台服务：只为把进程钉在后台，真正的 QEMU 由 [WrapperLite.runner] 托管。
 *
 * 不加这个的话，App 一退到后台就会被系统冻结，guest 里的 HTTP 服务跟着断。
 */
class WrapperService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        WrapperLite.init(applicationContext)
        createChannel()
        startInForeground("运行中")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("运行中")
        return START_STICKY
    }

    /**
     * 用户从最近任务里划掉 App 时调用。
     *
     * 这里选择「划掉即停」：key 服务靠前台通知常驻，用户主动划掉就是明确要它停，
     * 顺手把 QEMU 一起收掉，不留后台进程。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        WrapperLite.stopServing()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        // 服务没了就必须把 QEMU 也收掉，否则会留下孤儿进程一直占着端口
        WrapperLite.onServiceDestroyed()
        super.onDestroy()
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
                acquire(12 * 60 * 60 * 1000L)
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
    }
}
