package com.autolaunch.appscheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * 定时调度器 - 使用 AlarmManager 设置四个每日闹钟
 *
 * v2.4 关键改动：直接用 PendingIntent.getService 替代 getBroadcast
 *   AlarmManager → PendingIntent.getService(SchedulerService) → Service.onStartCommand
 *   彻底绕过 BroadcastReceiver，避开 ColorOS/OxygenOS 对 Receiver 广播的拦截层
 *
 *   手动"立即模拟"仍然走 sendBroadcast → AlarmReceiver（测试用）
 *   但真正的定时触发走 Service 直达路径
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    const val REQUEST_CODE_BASE = 1000

    /**
     * 构造指向 SchedulerService 的 Intent —— 闹钟触发时直接启动前台服务
     */
    fun buildServiceIntent(context: Context, index: Int, timeStr: String): Intent {
        return Intent(context, SchedulerService::class.java).apply {
            action = SchedulerService.ACTION_ALARM_TRIGGER
            putExtra(SchedulerService.EXTRA_TIME, timeStr)
            putExtra(SchedulerService.EXTRA_INDEX, index)
        }
    }

    /**
     * 构造指向 AlarmReceiver 的 Intent —— 手动触发测试用
     */
    fun buildBroadcastIntent(context: Context, index: Int): Intent {
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
     *
     * 用 PendingIntent.getForegroundService（Oreo 8+）
     * 或 PendingIntent.getService（Marshmallow 6+ / Nougat 7）
     * 直接让系统拉起 SchedulerService
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

            val intent = buildServiceIntent(context, index, timeStr)

            val pi = createServicePendingIntent(context, REQUEST_CODE_BASE + index, intent)
            if (pi == null) {
                result.append("✗ $timeStr PendingIntent 创建失败\n")
                return@forEachIndexed
            }

            val triggerAt = calendar.timeInMillis
            val shown = android.text.format.DateFormat.format("MM-dd HH:mm", triggerAt).toString()

            val ok = setOne(am, triggerAt, pi, result, timeStr, shown)
            if (ok) {
                successCount++
                if (triggerAt < earliest) earliest = triggerAt
            }
        }

        prefs.scheduledCount = successCount
        prefs.nextAlarmAt = if (successCount > 0) earliest else 0L

        result.insert(0, "设置闹钟: $successCount/${times.size} 成功\n")
        Log.i(TAG, result.toString())
        return result.toString()
    }

    /**
     * 三级降级：setExactAndAllowWhileIdle → setExact → set
     */
    private fun setOne(
        am: AlarmManager,
        triggerAt: Long,
        pi: PendingIntent,
        result: StringBuilder,
        timeStr: String,
        shown: String
    ): Boolean {
        if (canScheduleExactRuntime(am)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                result.append("✓ $timeStr → $shown  [精确+省电]\n")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setExactAndAllowWhileIdle 失败: ${e.message}")
            }
            try {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                result.append("✓ $timeStr → $shown  [精确]\n")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "setExact 失败: ${e.message}")
            }
        }
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
     * 创建指向 Service 的 PendingIntent —— 根据 Android 版本选择方法
     */
    private fun createServicePendingIntent(
        context: Context,
        requestCode: Int,
        intent: Intent
    ): PendingIntent? {
        val flags = piFlags(updatable = true)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent.getForegroundService 失败，退回 getService: ${e.message}")
            try {
                PendingIntent.getService(context, requestCode, intent, flags)
            } catch (e2: Exception) {
                Log.e(TAG, "getService 也失败: ${e2.message}")
                null
            }
        }
    }

    /**
     * 取消所有闹钟 —— 用和注册时完全一致的 PendingIntent 才能 cancel 掉
     */
    fun cancelAll(context: Context, prefs: Prefs = Prefs(context)) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (i in 0..3) {
            // 必须用和 scheduleAll 完全一致的 Intent/PendingIntent 才能 cancel 到
            val intent = buildServiceIntent(context, i, "00:00")
            val pi = createServicePendingIntent(context, REQUEST_CODE_BASE + i, intent)
            if (pi != null) {
                try { am.cancel(pi) } catch (_: Exception) {}
            }
            // 也尝试 cancel 旧的 Broadcast PendingIntent（升级 v2.4 前留下的）
            val oldPi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_BASE + i,
                buildBroadcastIntent(context, i),
                piFlags(updatable = false)
            )
            try { am.cancel(oldPi) } catch (_: Exception) {}
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

        sb.append("【无障碍服务】${if (LauncherAccessibilityService.isEnabled(context)) "✓ 已开启" else "✗ 未开启"}\n")
        sb.append("【悬浮窗权限】${if (android.provider.Settings.canDrawOverlays(context)) "✓ 已授权" else "✗ 未授权"}\n")
        sb.append("【服务运行标记】${prefs.serviceRunning}\n")
        sb.append("【工作日模式】${if (prefs.workdaysOnly) "开" else "关"}，今天${if (AlarmReceiver.isWorkdayToday()) "是工作日" else "是周末"}\n")
        sb.append("【目标应用】${prefs.targetPackage.ifEmpty { "未设置" }}\n")
        sb.append("【触发方式】AlarmManager → PendingIntent.getService → SchedulerService\n")
        sb.append("【系统版本】Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        val verName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "?" }
        sb.append("【App 版本】$verName\n")
        sb.append("\n【触发记录】\n")
        val log = prefs.fireLog
        sb.append(if (log.isBlank()) "（暂无，说明闹钟从未被系统叫醒）\n" else "$log\n")

        Log.i(TAG, "诊断:\n$sb")
        return sb.toString()
    }
}
