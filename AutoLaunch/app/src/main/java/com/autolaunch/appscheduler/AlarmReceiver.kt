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
 * 定时闹钟触发入口
 *
 * 核心设计：AlarmReceiver 自己不直接启动目标应用（BroadcastReceiver context 受限），
 * 而是先把启动请求写入 Prefs（pendingLaunch），然后唤醒 SchedulerService 和
 * LauncherAccessibilityService 去处理。前台服务和无障碍服务的 context 不受限制，
 * 它们一定会成功启动应用或走到通知兜底。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        // goAsync() 保证 onReceive 返回后进程还能活 30 秒，用来完成：
        //   1. 启动前台服务（进程不被杀）
        //   2. scheduleAll 重设明天的闹钟（进程不被杀）
        val pendingResult = goAsync()
        try {
            val time = intent.getStringExtra(EXTRA_TIME) ?: ""
            val force = intent.getBooleanExtra(EXTRA_FORCE, false)
            Log.i(TAG, "===== 闹钟触发 =====")
            Log.i(TAG, "time=$time, force=$force")

            val prefs = Prefs(context)
            prefs.appendFireLog("收到广播 time=$time force=$force")

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

            // 第1步：把启动请求写入 Prefs（持久化，进程重建也不会丢）
            prefs.writePendingLaunch(pkg, time)
            Log.i(TAG, "已写入 pendingLaunch: $pkg | $time")

            // 第2步：唤醒前台服务处理
            try {
                val svcIntent = Intent(context, SchedulerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
                Log.i(TAG, "SchedulerService 已请求启动")
            } catch (e: Exception) {
                Log.w(TAG, "SchedulerService 启动失败: ${e.message}")
            }

            // 第3步：唤醒无障碍服务（如果开启了）
            if (LauncherAccessibilityService.isEnabled(context)) {
                Log.i(TAG, "无障碍服务已开启，等待它处理 pendingLaunch")
            }

            prefs.serviceRunning = true
            // 第4步：重设明天的闹钟（必须在 goAsync 块里，进程才不会被杀）
            AlarmScheduler.scheduleAll(context, prefs.getTimes())
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
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
