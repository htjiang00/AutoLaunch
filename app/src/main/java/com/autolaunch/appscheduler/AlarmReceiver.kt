package com.autolaunch.appscheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 定时闹钟触发入口 —— 只做调度判断，实际启动交给 SchedulerService 完成
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        val time = intent.getStringExtra(EXTRA_TIME) ?: ""
        Log.i(TAG, "闹钟触发! time=$time")

        val prefs = Prefs(context)

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

        // 最稳路径：把启动请求交给前台服务
        SchedulerService.requestLaunch(context, pkg, time)
        prefs.serviceRunning = true

        // 一次性闹钟，触发后重设明天的
        AlarmScheduler.scheduleAll(context, prefs.getTimes())
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_TRIGGER = "com.autolaunch.appscheduler.TRIGGER"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TIME = "time"

        /** 今天是否为工作日（周一至周五）。离线版只按星期判断，法定节假日需联网日历 */
        fun isWorkdayToday(): Boolean {
            val dow = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            return dow != java.util.Calendar.SATURDAY && dow != java.util.Calendar.SUNDAY
        }
    }
}
