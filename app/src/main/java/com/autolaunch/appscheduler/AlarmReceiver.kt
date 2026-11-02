package com.autolaunch.appscheduler

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 定时闹钟触发入口 —— 自己把启动逻辑全做完，不依赖任何 Service
 *
 * 启动路径按优先级依次尝试：
 *   1. 无障碍服务（如果 instance 还活着）
 *   2. BroadcastReceiver context 直接 startActivity（有悬浮窗权限时被豁免）
 *   3. 全屏通知（兜底，用户点一下即可打开）
 *
 * 同时确保 SchedulerService 在运行（前台保活），但启动逻辑本身不依赖它。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        val time = intent.getStringExtra(EXTRA_TIME) ?: ""
        Log.i(TAG, "闹钟触发! time=$time")

        val prefs = Prefs(context)

        // 确保前台保活服务在跑（但不依赖它做启动）
        ensureForegroundService(context)

        // 工作日模式：周末到点自动跳过
        if (prefs.workdaysOnly && !isWorkdayToday()) {
            Log.i(TAG, "今天是周末，工作日模式开启，跳过启动")
            AlarmScheduler.scheduleAll(context, prefs.getTimes())
            return
        }

        val pkg = prefs.targetPackage
        if (pkg.isEmpty()) {
            Log.w(TAG, "未设置目标应用，跳过启动")
            AlarmScheduler.scheduleAll(context, prefs.getTimes())
            return
        }

        launchApp(context, pkg, time, prefs)
        prefs.serviceRunning = true

        // 一次性闹钟，触发后重设明天的
        AlarmScheduler.scheduleAll(context, prefs.getTimes())
    }

    private fun ensureForegroundService(context: Context) {
        try {
            val svc = Intent(context, SchedulerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "前台服务启动失败（不影响本次启动）: ${e.message}")
        }
    }

    /**
     * 三级降级启动：无障碍 → 悬浮窗 → 全屏通知
     */
    private fun launchApp(context: Context, packageName: String, time: String, prefs: Prefs) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "找不到应用启动入口: $packageName")
            sendLaunchNotification(context, packageName, time)
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        // 路径1：无障碍服务
        if (LauncherAccessibilityService.isEnabled(context) &&
            LauncherAccessibilityService.launch(launchIntent)
        ) {
            Log.i(TAG, "无障碍启动成功: $packageName")
            return
        }

        // 路径2：悬浮窗权限豁免，BroadcastReceiver context 直接 startActivity
        if (android.provider.Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(launchIntent)
                Log.i(TAG, "悬浮窗启动成功: $packageName")
                return
            } catch (e: Exception) {
                Log.w(TAG, "悬浮窗启动失败: ${e.message}")
            }
        }

        // 路径3：全屏通知兜底
        Log.w(TAG, "前两条路都失败，发送通知兜底")
        sendLaunchNotification(context, packageName, time)
    }

    private fun sendLaunchNotification(context: Context, packageName: String, time: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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

        val n: Notification = NotificationCompat.Builder(context, channelId)
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
            Log.i(TAG, "通知已发送")
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

        /** 今天是否为工作日（周一至周五）。离线版只按星期判断 */
        fun isWorkdayToday(): Boolean {
            val dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY
        }
    }
}
