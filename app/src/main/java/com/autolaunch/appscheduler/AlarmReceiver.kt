package com.autolaunch.appscheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 定时闹钟触发入口
 *
 * 注意：Android 10+ 禁止后台启动 Activity，唯一稳定豁免是持有
 * SYSTEM_ALERT_WINDOW（悬浮窗）权限。未授权时降级为高优先级通知，
 * 用户点击即可启动目标应用。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        val time = intent.getStringExtra(EXTRA_TIME) ?: ""
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        Log.i(TAG, "闹钟触发! time=$time, index=$index")

        val prefs = Prefs(context)
        val pkg = prefs.targetPackage
        if (pkg.isEmpty()) {
            Log.w(TAG, "未设置目标应用，跳过启动")
            return
        }

        launchApp(context, pkg, time)
        prefs.serviceRunning = true

        // 一次性闹钟，触发后重设明天的
        AlarmScheduler.scheduleAll(context, prefs.getTimes())
    }

    private fun launchApp(context: Context, packageName: String, time: String) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "找不到应用的启动入口: $packageName")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        // 有悬浮窗权限 → 后台启动 Activity 被系统豁免，直接拉起
        if (canDrawOverlays(context)) {
            try {
                context.startActivity(launchIntent)
                Log.i(TAG, "直接启动应用成功: $packageName")
                return
            } catch (e: Exception) {
                Log.e(TAG, "直接启动失败: ${e.message}")
            }
        }

        // 无悬浮窗权限 → 发全屏通知，用户点一下即可打开
        Log.w(TAG, "无悬浮窗权限，改用通知提示")
        showLaunchNotification(context, launchIntent, time)
    }

    private fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    private fun showLaunchNotification(context: Context, launchIntent: Intent, time: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            context,
            2000,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            android.app.Notification.Builder(context, channelId)
        else
            android.app.Notification.Builder(context)

        val n = builder
            .setContentTitle("定时启动：$time")
            .setContentText("点击打开目标应用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setFullScreenIntent(contentPi, true)
            .build()

        try {
            nm.notify(NOTIF_FIRE_ID, n)
        } catch (e: Exception) {
            Log.e(TAG, "通知发送失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val NOTIF_FIRE_ID = 101
        const val ACTION_TRIGGER = "com.autolaunch.appscheduler.TRIGGER"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TIME = "time"
    }
}
