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
 *
 * 关键设计：不依赖 PendingIntent 反查来判断状态（国产 ROM 行为不一致），
 * 而是把实际注册结果持久化到 Prefs。
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    const val REQUEST_CODE_BASE = 1000

    /**
     * 构造 Intent —— 设置/取消/查询三处必须完全一致
     */
    fun buildIntent(context: Context, index: Int): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_TRIGGER
            putExtra(AlarmReceiver.EXTRA_INDEX, index)
        }
    }

    private fun piFlags(updatable: Boolean): Int {
        val base = if (updatable) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return base or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * 为四个时间点分别设置每日闹钟，返回可读结果
     */
    fun scheduleAll(context: Context, times: List<String>): String {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = Prefs(context)
        val result = StringBuilder()

        if (!canScheduleExact(context)) {
            result.append("⚠ 未授予精确闹钟权限，改用非精确闹钟\n")
        }

        cancelAll(context, prefs)

        var successCount = 0
        var earliest = Long.MAX_VALUE

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
                if (timeInMillis <= System.currentTimeMillis()) {
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

            val triggerAt = calendar.timeInMillis
            val shown = android.text.format.DateFormat.format("MM-dd HH:mm", triggerAt).toString()

            val ok = setOne(am, triggerAt, pi, result, timeStr, shown)
            if (ok) {
                successCount++
                if (triggerAt < earliest) earliest = triggerAt
            }
        }

        // 持久化注册结果
        prefs.scheduledCount = successCount
        prefs.nextAlarmAt = if (successCount > 0) earliest else 0L

        result.insert(0, "设置闹钟: $successCount/${times.size} 成功\n")
        Log.i(TAG, result.toString())
        return result.toString()
    }

    /**
     * 三级降级：setAlarmClock → setExactAndAllowWhileIdle → set（永不失败）
     */
    private fun setOne(
        am: AlarmManager,
        triggerAt: Long,
        pi: PendingIntent,
        result: StringBuilder,
        timeStr: String,
        shown: String
    ): Boolean {
        // 1. setAlarmClock：优先级最高，锁屏也会唤醒
        if (canScheduleExactRuntime(am)) {
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
                result.append("✓ $timeStr → $shown  [精确]\n")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setAlarmClock 失败: ${e.message}")
            }
            // 2. setExactAndAllowWhileIdle
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                result.append("✓ $timeStr → $shown  [精确/省电]\n")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setExactAndAllowWhileIdle 失败: ${e.message}")
            }
        }
        // 3. set：非精确闹钟，不需要特殊权限，一定成功
        return try {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            result.append("✓ $timeStr → $shown  [非精确]\n")
            true
        } catch (e: Exception) {
            result.append("✗ $timeStr 失败: ${e.message}\n")
            false
        }
    }

    private fun canScheduleExactRuntime(am: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    /**
     * 取消所有闹钟
     */
    fun cancelAll(context: Context, prefs: Prefs = Prefs(context)) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0..3) {
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + i,
                buildIntent(context, i),
                piFlags(updatable = true)
            )
            try { am.cancel(pi) } catch (_: Exception) {}
        }
        prefs.scheduledCount = 0
        prefs.nextAlarmAt = 0L
        Log.i(TAG, "已取消全部闹钟")
    }

    /**
     * 综合诊断
     */
    fun diagnose(context: Context): String {
        val sb = StringBuilder()
        val prefs = Prefs(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        sb.append("【已注册闹钟】${prefs.scheduledCount}/4\n")
        val next = prefs.nextAlarmAt
        if (next > 0) {
            sb.append("【下次触发】${android.text.format.DateFormat.format("MM-dd HH:mm", next)}\n")
        } else {
            sb.append("【下次触发】无\n")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val nac = am.nextAlarmClock
            sb.append("【系统闹钟】${if (nac != null) android.text.format.DateFormat.format("MM-dd HH:mm", nac.triggerTime) else "无"}\n")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("【精确闹钟权限】${if (am.canScheduleExactAlarms()) "✓ 已授权" else "✗ 未授权"}\n")
        }

        sb.append("【悬浮窗权限】${if (android.provider.Settings.canDrawOverlays(context)) "✓ 已授权" else "✗ 未授权（到点弹不出应用）"}\n")
        sb.append("【服务运行标记】${prefs.serviceRunning}\n")
        sb.append("【目标应用】${prefs.targetPackage.ifEmpty { "未设置" }}\n")
        sb.append("【系统版本】Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        Log.i(TAG, "诊断:\n$sb")
        return sb.toString()
    }
}
