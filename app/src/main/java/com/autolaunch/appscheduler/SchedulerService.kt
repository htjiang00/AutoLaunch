package com.autolaunch.appscheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务 - 让应用不容易被系统杀掉
 */
class SchedulerService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 关键：startForeground 在部分 ROM / Android 14 上可能抛 SecurityException，
        // 一旦抛出服务会崩溃，导致状态永远显示"已停止"。必须兜住。
        try {
            startForeground(NOTIF_ID, buildNotification())
            Log.i(TAG, "SchedulerService 已启动（前台）")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground 失败，降级为普通服务: ${e.message}")
        }
    }

    /**
     * 每次 START_STICKY 重启后也要确保前台通知存在
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {}
        Prefs(this).serviceRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SchedulerService 已停止")
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "SchedulerService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "auto_launch_channel"
    }
}
