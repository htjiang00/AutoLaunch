package com.autolaunch.appscheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务 —— 同时负责处理 pendingLaunch 启动请求
 *
 * 启动链路（最稳的路径）：
 *   AlarmReceiver → 写 pendingLaunch → startForegroundService 本服务
 *   → onStartCommand → 检查 pendingLaunch → 执行启动
 *
 * 本服务的 context 是完整有效的 ApplicationContext，不受 ReceiverRestrictedContext 限制。
 */
class SchedulerService : Service() {

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIF_ID, buildNotification())
            Log.i(TAG, "SchedulerService 已启动（前台）")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {}

        val prefs = Prefs(this)
        prefs.serviceRunning = true

        // 检查并执行 pendingLaunch（AlarmReceiver 写入的启动请求）
        val pending = prefs.consumePendingLaunch()
        if (pending != null) {
            val (pkg, time) = pending
            Log.i(TAG, "收到 pendingLaunch 请求: $pkg | $time")
            launchTargetApp(pkg, time)
        } else {
            Log.i(TAG, "无 pendingLaunch 请求")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SchedulerService 已停止")
    }

    /**
     * 用前台服务的 context 启动目标应用（不受 ReceiverRestrictedContext 限制）
     *
     * 降级链路：
     *   1. PendingIntent.send（ActivityManager 代理执行，最稳）
     *   2. startActivity（悬浮窗豁免时可工作）
     *   3. 全屏通知（兜底）
     */
    private fun launchTargetApp(packageName: String, time: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "找不到应用启动入口: $packageName")
            sendLaunchNotification(packageName, time, "找不到应用启动入口")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val overlay = android.provider.Settings.canDrawOverlays(this)
        Log.i(TAG, "SchedulerService 启动目标: $packageName, overlay=$overlay")

        // 路径1：PendingIntent.send —— ActivityManager 代理执行
        try {
            val pi = PendingIntent.getActivity(
                this,
                PID_LAUNCH_TARGET,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send(this, 0, null)
            Log.i(TAG, "✓ PendingIntent.send 启动成功")
            return
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.send 失败: ${e.message}")
        }

        // 路径2：直接 startActivity（前台服务 context 通常能工作）
        try {
            startActivity(launchIntent)
            Log.i(TAG, "✓ startActivity 启动成功")
            return
        } catch (e: Exception) {
            Log.w(TAG, "startActivity 失败: ${e.message}")
        }

        // 路径3：全屏通知兜底
        Log.w(TAG, "所有启动方式失败，发送全屏通知")
        sendLaunchNotification(packageName, time, "自动启动被系统拦截")
    }

    private fun sendLaunchNotification(packageName: String, time: String, reason: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "autolaunch_fire"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "定时启动提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "到点提醒你启动指定应用"
                enableVibration(true)
            }
            nm.createNotificationChannel(ch)
        }

        val contentPi = PendingIntent.getActivity(
            this,
            2000,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("定时启动：$time")
            .setContentText("点击打开目标应用（$reason）")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setFullScreenIntent(contentPi, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(NOTIF_FIRE_ID, n)
            Log.i(TAG, "通知已发送")
        } catch (e: Exception) {
            Log.e(TAG, "通知发送失败: ${e.message}")
        }
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
        private const val NOTIF_FIRE_ID = 101
        private const val PID_LAUNCH_TARGET = 3000
    }
}
