package com.autolaunch.appscheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 定时闹钟触发入口
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TRIGGER) {
            val time = intent.getStringExtra(EXTRA_TIME) ?: ""
            val index = intent.getIntExtra(EXTRA_INDEX, -1)
            Log.i(TAG, "闹钟触发! time=$time, index=$index")

            val prefs = Prefs(context)
            val pkg = prefs.targetPackage
            if (pkg.isEmpty()) {
                Log.w(TAG, "未设置目标应用，跳过启动")
                return
            }

            launchApp(context, pkg)

            // 重新设置明天的闹钟 (因 setAlarmClock 是一次性)
            AlarmScheduler.scheduleAll(context, prefs.getTimes())
        }
    }

    /**
     * 启动指定包名的应用
     */
    private fun launchApp(context: Context, packageName: String) {
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                context.startActivity(launchIntent)
                Log.i(TAG, "成功启动应用: $packageName")
            } else {
                Log.e(TAG, "找不到应用的启动入口: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_TRIGGER = "com.autolaunch.appscheduler.TRIGGER"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TIME = "time"
    }
}
