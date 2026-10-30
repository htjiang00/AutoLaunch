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
     * 构造一个与 scheduleAll 完全一致的 Intent（用于查询/取消）
     */
    fun buildIntent(context: Context, index: Int): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER
            putExtra(AlarmReceiver.EXTRA_INDEX, index)
        }
    }

    private fun piFlags(updatable: Boolean = true): Int {
        val base = if (updatable) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return base or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    /**
     * 为四个时间点分别设置每日重复闹钟
     */
    fun scheduleAll(context: Context, times: List<String>): String {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val result = StringBuilder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!am.canScheduleExactAlarms()) {
                result.append("⚠ 没有精确闹钟权限，降级为 inexact alarm\n")
            }
        }

        // 先取消所有旧闹钟
        cancelAll(context)

        var successCount = 0
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
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = buildIntent(context, index).apply {
                putExtra(AlarmReceiver.EXTRA_TIME, timeStr)
            }

            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + index,
                intent,
                piFlags(updatable = true)
            )

            val triggerAtMillis = calendar.timeInMillis
            val triggerTimeStr = android.text.format.DateFormat.format("MM-dd HH:mm", triggerAtMillis).toString()

            try {
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, pi)
                am.setAlarmClock(alarmClockInfo, pi)
                Log.i(TAG, "闹钟设置成功(setAlarmClock): $timeStr index=$index -> $triggerTimeStr")
                result.append("✓ $timeStr → $triggerTimeStr (setAlarmClock)\n")
                successCount++
            } catch (e: SecurityException) {
                Log.w(TAG, "setAlarmClock 失败(SecurityException), 降级到 setExactAndAllowWhileIdle: ${e.message}")
                try {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pi
                    )
                    Log.i(TAG, "闹钟设置成功(setExactAndAllowWhileIdle): $timeStr index=$index")
                    result.append("✓ $timeStr → $triggerTimeStr (setExact)\n")
                    successCount++
                } catch (e2: Exception) {
                    Log.e(TAG, "setExactAndAllowWhileIdle 也失败了: ${e2.message}")
                    result.append("✗ $timeStr 失败: ${e2.message}\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "setAlarmClock 未知异常: ${e.message}")
                result.append("✗ $timeStr 异常: ${e.message}\n")
            }
        }

        result.insert(0, "设置闹钟: $successCount/${times.size} 成功\n")
        Log.i(TAG, result.toString())
        return result.toString()
    }

    /**
     * 取消所有闹钟
     */
    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0..3) {
            val intent = buildIntent(context, i)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + i,
                intent,
                piFlags(updatable = true)
            )
            try {
                am.cancel(pi)
            } catch (_: Exception) {}
        }
        Log.i(TAG, "已取消全部闹钟")
    }

    /**
     * 方法1: 用 PendingIntent.FLAG_NO_CREATE 查询（Android 原生方式，但部分 ROM 行为不一致）
     */
    private fun checkByPendingIntent(context: Context): Pair<Int, Boolean> {
        var found = 0
        for (i in 0..3) {
            val intent = buildIntent(context, i)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + i,
                intent,
                piFlags(updatable = false) // FLAG_NO_CREATE
            )
            if (pi != null) {
                found++
            }
        }
        return Pair(found, found > 0)
    }

    /**
     * 方法2: 用 AlarmManager.getNextAlarmClock() 查询（更可靠，API 21+）
     * 注意: 只能查到"下一个"闹钟，且有些 ROM 上对非 setAlarmClock 设置的闹钟返回 null
     */
    private fun checkByNextAlarm(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = am.nextAlarmClock ?: return null
        val timeStr = android.text.format.DateFormat.format("MM-dd HH:mm", next.triggerTime).toString()
        return "下一个闹钟: $timeStr"
    }

    /**
     * 综合诊断：返回详细状态信息
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        val (piCount, piFound) = checkByPendingIntent(context)
        sb.append("【PendingIntent 查询】找到 $piCount/4 个\n")

        val nextInfo = checkByNextAlarm(context)
        if (nextInfo != null) {
            sb.append("【系统下一个闹钟】$nextInfo\n")
        } else {
            sb.append("【系统下一个闹钟】无（可能没设，或用的不是 setAlarmClock）\n")
        }

        // 再查一次 canScheduleExactAlarms
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("【精确闹钟权限】${if (am.canScheduleExactAlarms()) "✓ 已授权" else "✗ 未授权"}\n")
        }

        val prefs = Prefs(context)
        sb.append("【prefs.serviceRunning】${prefs.serviceRunning}\n")
        sb.append("【目标应用】${if (prefs.targetPackage.isNotEmpty()) prefs.targetAppName.ifEmpty { prefs.targetPackage } else "未设置"}\n")

        Log.i(TAG, "诊断:\n$sb")
        return sb.toString()
    }

    /**
     * 判断闹钟是否活跃——用 PendingIntent 查询
     */
    fun hasActiveAlarms(context: Context): Boolean {
        val (_, found) = checkByPendingIntent(context)
        return found
    }
}
