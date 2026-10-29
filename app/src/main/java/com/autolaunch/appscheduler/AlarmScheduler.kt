package com.autolaunch.appscheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * 定时调度器 - 使用 AlarmManager 设置四个每日重复闹钟
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    const val REQUEST_CODE_BASE = 1000

    /**
     * 为四个时间点分别设置每日重复闹钟
     */
    fun scheduleAll(context: Context, times: List<String>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!am.canScheduleExactAlarms()) {
                Log.w(TAG, "没有精确闹钟权限，只能使用 inexact alarm")
            }
        }

        // 先取消所有旧闹钟
        cancelAll(context)

        times.forEachIndexed { index, timeStr ->
            val parts = timeStr.split(":")
            if (parts.size != 2) return@forEachIndexed
            val hour = parts[0].toIntOrNull() ?: return@forEachIndexed
            val minute = parts[1].toIntOrNull() ?: return@forEachIndexed

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // 如果这个时间已经过了，就设为明天
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_TRIGGER
                putExtra(AlarmReceiver.EXTRA_INDEX, index)
                putExtra(AlarmReceiver.EXTRA_TIME, timeStr)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + index,
                intent,
                flags
            )

            val triggerAtMillis = calendar.timeInMillis

            // 使用 setAlarmClock (最可靠) 或 setExactAndAllowWhileIdle
            try {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, pi)
                am.setAlarmClock(alarmClockInfo, pi)
                Log.i(TAG, "闹钟设置成功: $timeStr (index=$index) -> $triggerAtMillis")
            } catch (e: SecurityException) {
                Log.e(TAG, "setAlarmClock 失败，降级到 setExactAndAllowWhileIdle: ${e.message}")
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pi
                )
            }
        }
    }

    /**
     * 取消所有闹钟
     */
    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0..3) {
            val intent = Intent(context, AlarmReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE_BASE + i, intent, flags)
            am.cancel(pi)
        }
        Log.i(TAG, "已取消全部闹钟")
    }
}
