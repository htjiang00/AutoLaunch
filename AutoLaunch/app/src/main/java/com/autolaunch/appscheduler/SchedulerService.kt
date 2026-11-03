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
 * 前台保活服务 —— 同时作为 AlarmManager 定时触发的直接接收端（v2.4）
 *
 * v2.4 启动链路：
 *   AlarmManager → PendingIntent.getForegroundService(SchedulerService)
 *   → onCreate() startForeground（必须 5 秒内完成）
 *   → onStartCommand(action=ALARM_TRIGGER) → 执行启动 + 重设明天闹钟
 *
 *   手动/旧路径兼容：
 *   AlarmReceiver → 写 pendingLaunch → startForegroundService(SchedulerService)
 *   → onStartCommand 无 action → 检查 pendingLaunch 并执行
 */
class SchedulerService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 5 秒内必须 startForeground，否则 Android O+ 会崩溃
        try {
            startForeground(NOTIF_ID, buildNotification())
            Log.i(TAG, "SchedulerService.onCreate 前台已启动")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground 失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保前台存活
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {}

        val prefs = Prefs(this)
        prefs.serviceRunning = true

        val action = intent?.action
        Log.i(TAG, "onStartCommand action=$action extras=${intent?.extras}")

        // ==== 主路径：AlarmManager 直接触发 ====
        if (action == ACTION_ALARM_TRIGGER) {
            val time = intent?.getStringExtra(EXTRA_TIME) ?: ""
            val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
            Log.i(TAG, "===== AlarmManager 闹钟触发 =====")
            Log.i(TAG, "time=$time force=$force")
            prefs.appendFireLog("闹钟触发 time=$time force=$force")

            // 工作日模式
            if (!force && prefs.workdaysOnly && !AlarmReceiver.isWorkdayToday()) {
                Log.i(TAG, "今天是周末，跳过")
                prefs.appendFireLog("周末，按工作日模式跳过")
                AlarmScheduler.scheduleAll(this, prefs.getTimes())
                return START_STICKY
            }

            val pkg = prefs.targetPackage
            if (pkg.isEmpty()) {
                Log.w(TAG, "未设置目标应用")
                prefs.appendFireLog("未设置目标应用")
                AlarmScheduler.scheduleAll(this, prefs.getTimes())
                return START_STICKY
            }

            // 启动目标应用
            launchTargetApp(pkg, time)
            // 重设明天的闹钟
            AlarmScheduler.scheduleAll(this, prefs.getTimes())
            prefs.appendFireLog("闹钟已重新排程")
            return START_STICKY
        }

        // ==== 兼容路径：AlarmReceiver 写了 pendingLaunch ====
        val pending = prefs.consumePendingLaunch()
        if (pending != null) {
            val (pkg, time) = pending
            Log.i(TAG, "pendingLaunch 路径: $pkg | $time")
            prefs.appendFireLog("广播路径触发 $pkg")
            launchTargetApp(pkg, time)
        } else {
            Log.i(TAG, "无 pendingLaunch，仅保活")
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
     * 关键策略：先无条件下发一个全屏高优先级通知（保证到点一定有反馈，
     *          即使 Activity 被 ColorOS 静默拦截也不会「什么都没发生」），
     *          然后再尝试直接拉起。
     *
     * 注意：后台 startActivity 被拦截时系统通常是「静默失败、不抛异常」，
     *      所以不能靠 catch 来判断成功与否 —— 必须靠通知兜底。
     */
    private fun launchTargetApp(packageName: String, time: String) {
        val prefs = Prefs(this)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "找不到应用启动入口: $packageName")
            prefs.appendFireLog("失败：找不到应用入口 $packageName")
            sendLaunchNotification(packageName, time, "找不到应用启动入口")
            return
        }
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        val overlay = android.provider.Settings.canDrawOverlays(this)
        Log.i(TAG, "启动目标: $packageName, overlay=$overlay")
        prefs.appendFireLog("开始启动 $packageName overlay=$overlay")

        // 第0步：无条件先发全屏通知，保证一定有可见反馈
        sendLaunchNotification(packageName, time, "已到点")

        // 路径1：PendingIntent.send（ActivityManager 代理执行，最稳）
        try {
            val pi = PendingIntent.getActivity(
                this,
                PID_LAUNCH_TARGET,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            pi.send(this, 0, null)
            Log.i(TAG, "✓ PendingIntent.send 已发出")
            prefs.appendFireLog("已发出 PendingIntent.send")
            return
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.send 失败: ${e.message}")
            prefs.appendFireLog("PendingIntent.send 失败 ${e.message}")
        }

        // 路径2：直接 startActivity
        try {
            startActivity(launchIntent)
            Log.i(TAG, "✓ startActivity 已发出")
            prefs.appendFireLog("已发出 startActivity")
            return
        } catch (e: Exception) {
            Log.w(TAG, "startActivity 失败: ${e.message}")
            prefs.appendFireLog("startActivity 失败 ${e.message}")
        }

        Log.w(TAG, "所有直接启动方式都失败，只能靠通知")
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
            Log.i(TAG, "全屏通知已发送")
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

        /** AlarmManager 通过 PendingIntent 触发本 Service 的 action */
        const val ACTION_ALARM_TRIGGER = "com.autolaunch.appscheduler.ALARM_TRIGGER"
        const val EXTRA_TIME = "time"
        const val EXTRA_INDEX = "index"
        const val EXTRA_FORCE = "force"
    }
}
