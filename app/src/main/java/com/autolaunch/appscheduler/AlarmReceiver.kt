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
 * 启动路径（关键改进：用 PendingIntent.send 绕过 BroadcastReceiver 的受限 context）：
 *   1. 无障碍服务（如果 instance 还活着）
 *   2. 悬浮窗 + PendingIntent.send —— ActivityManager 代理执行，不受 ReceiverRestrictedContext 限制
 *   3. 全屏通知兜底 —— 用户点一下即可打开
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        val time = intent.getStringExtra(EXTRA_TIME) ?: ""
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        Log.i(TAG, "===== 闹钟触发 =====")
        Log.i(TAG, "time=$time, force=$force")

        val prefs = Prefs(context)

        // 确保前台保活服务在跑（try-catch 保护，失败不影响本次启动）
        try {
            val svc = Intent(context, SchedulerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.i(TAG, "SchedulerService 请求已发送")
        } catch (e: Exception) {
            Log.w(TAG, "前台服务启动失败（不影响本次启动）: ${e.message}")
        }

        // 工作日模式：周末到点自动跳过（手动强制触发时不受此限制）
        if (!force && prefs.workdaysOnly && !isWorkdayToday()) {
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

    /**
     * 三级降级启动 —— 每级都有明确的日志，方便排查
     */
    private fun launchApp(context: Context, packageName: String, time: String, prefs: Prefs) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "找不到应用启动入口: $packageName")
            sendLaunchNotification(context, packageName, time, "找不到应用启动入口")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val a11yEnabled = LauncherAccessibilityService.isEnabled(context)
        val overlayEnabled = android.provider.Settings.canDrawOverlays(context)
        Log.i(TAG, "权限状态: a11y=$a11yEnabled, overlay=$overlayEnabled")

        // 路径1：无障碍服务
        if (a11yEnabled) {
            val ok = LauncherAccessibilityService.launch(launchIntent)
            Log.i(TAG, "无障碍启动结果: $ok")
            if (ok) return
        }

        // 路径2：悬浮窗 + PendingIntent.send
        // PendingIntent.send 由 ActivityManager 代理执行，不受 ReceiverRestrictedContext 限制
        if (overlayEnabled) {
            try {
                val pi = PendingIntent.getActivity(
                    context,
                    PID_LAUNCH_TARGET,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pi.send(context, 0, null)
                Log.i(TAG, "悬浮窗 + PendingIntent.send 启动成功: $packageName")
                return
            } catch (e: Exception) {
                Log.w(TAG, "PendingIntent.send 失败: ${e.message}")
            }
        }

        // 路径3：全屏通知兜底
        Log.w(TAG, "前两条路都失败，发送全屏通知")
        sendLaunchNotification(context, packageName, time, "自动启动失败，需要手动点击")
    }

    private fun sendLaunchNotification(
        context: Context,
        packageName: String,
        time: String,
        reason: String
    ) {
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
            .setContentText("点击打开目标应用（原因: $reason）")
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
        private const val PID_LAUNCH_TARGET = 3000
        const val ACTION_TRIGGER = "com.autolaunch.appscheduler.TRIGGER"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TIME = "time"
        /** 手动强制触发标志 —— 跳过工作日判断 */
        const val EXTRA_FORCE = "force"

        /** 今天是否为工作日（周一至周五）。离线版只按星期判断 */
        fun isWorkdayToday(): Boolean {
            val dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY
        }
    }
}
