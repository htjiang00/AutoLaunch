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
 * 前台保活服务 - 让应用不容易被系统杀掉，并在到点时执行启动目标应用
 *
 * 启动流程（最稳的路径）：
 *   AlarmReceiver 触发 → startService 给本服务发启动请求 → 本服务用自己的 context
 *   启动目标应用。前台服务进程存活 + context 启动 Activity 是系统豁免的后台启动通道。
 *
 * 备用路径（当前两条已不可靠，仅保留参考）：
 *   - 无障碍服务：进程被杀后 instance 丢失，不处理事件系统也不会主动拉起
 *   - 悬浮窗：BroadcastReceiver 的 ReceiverRestrictedContext 部分 ROM 上仍被拦截
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

        Prefs(this).serviceRunning = true

        // 处理启动目标应用的请求（最稳的路径：前台服务 context 启动 Activity）
        if (intent?.action == ACTION_LAUNCH) {
            val pkg = intent.getStringExtra(EXTRA_PKG)
            if (!pkg.isNullOrEmpty()) {
                val time = intent.getStringExtra(EXTRA_TIME) ?: ""
                handleLaunchRequest(pkg, time)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "SchedulerService 已停止")
    }

    /**
     * 用前台服务的 context 启动目标应用
     * 优先级：无障碍服务 → 悬浮窗直接启动 → 全屏通知
     */
    private fun handleLaunchRequest(packageName: String, time: String) {
        val prefs = Prefs(this)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "找不到应用启动入口: $packageName")
            sendLaunchNotification(prefs, packageName, time)
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        // 路径1：无障碍服务（如果 instance 还活着）
        if (LauncherAccessibilityService.isEnabled(this) &&
            LauncherAccessibilityService.launch(launchIntent)
        ) {
            Log.i(TAG, "无障碍启动成功: $packageName")
            return
        }

        // 路径2：前台服务 context 直接 startActivity（最常用，系统豁免）
        try {
            startActivity(launchIntent)
            Log.i(TAG, "前台服务启动成功: $packageName")
            return
        } catch (e: Exception) {
            Log.w(TAG, "前台服务启动失败: ${e.message}")
        }

        // 路径3：悬浮窗 context 启动（备用）
        if (android.provider.Settings.canDrawOverlays(this)) {
            try {
                startActivity(launchIntent)
                Log.i(TAG, "悬浮窗启动成功: $packageName")
                return
            } catch (e: Exception) {
                Log.w(TAG, "悬浮窗启动失败: ${e.message}")
            }
        }

        // 路径4：全屏通知（兜底）
        Log.w(TAG, "所有路径均失败，改用通知")
        sendLaunchNotification(prefs, packageName, time)
    }

    private fun sendLaunchNotification(prefs: Prefs, packageName: String, time: String) {
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
            .setContentText("点击打开目标应用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setFullScreenIntent(contentPi, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(NOTIF_FIRE_ID, n)
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

        const val ACTION_LAUNCH = "com.autolaunch.appscheduler.LAUNCH_TARGET"
        const val EXTRA_PKG = "package_name"
        const val EXTRA_TIME = "trigger_time"

        /** 向 SchedulerService 发送启动目标应用的请求 */
        fun requestLaunch(context: Context, packageName: String, time: String) {
            val intent = Intent(context, SchedulerService::class.java).apply {
                action = ACTION_LAUNCH
                putExtra(EXTRA_PKG, packageName)
                putExtra(EXTRA_TIME, time)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "无法启动 SchedulerService: ${e.message}")
            }
        }
    }
}
